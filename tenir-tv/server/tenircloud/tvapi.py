"""API для приставок: /api/tv/v1. Авторизация — Bearer-ключ приставки (выдаётся при регистрации один раз)."""

import json
import time
from collections import deque
from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from sqlalchemy import or_, select

from . import content
from .db import Command, Device, Message, Property, Room, utcnow
from .security import hash_key, new_device_key, new_pairing_code

router = APIRouter(prefix="/api/tv/v1")

ONLINE_SECONDS = 180
COMMAND_TTL = timedelta(hours=24)
KINDS = ("refresh", "reload_content", "clear_apps", "restart_app", "reboot")


def base_url(request: Request) -> str:
    return request.app.state.public_url or str(request.base_url).rstrip("/")


def device(request: Request) -> Device:
    auth = request.headers.get("authorization", "")
    if not auth.lower().startswith("bearer "):
        raise HTTPException(401, "нужен ключ приставки")
    with request.app.state.db.sessions() as db:
        dev = db.scalar(select(Device).where(Device.key_hash == hash_key(auth[7:].strip())))
        if dev is None:
            raise HTTPException(401, "приставка не зарегистрирована или ключ перевыпущен")
        return dev


@router.post("/register", status_code=201)
async def register(request: Request):
    ip = request.client.host if request.client else "?"
    now = time.monotonic()
    hits = request.app.state.__dict__.setdefault("register_hits", {})
    q = hits.setdefault(ip, deque())
    while q and now - q[0] > 60:
        q.popleft()
    if len(q) >= 10:
        raise HTTPException(429, "слишком много регистраций, повторите через минуту")
    q.append(now)
    body = await _json(request)
    key = new_device_key()
    with request.app.state.db.sessions() as db:
        code = _free_code(db)
        dev = Device(
            uid=new_device_key()[:16],
            key_hash=hash_key(key),
            pairing_code=code,
            model=str(body.get("model", ""))[:120],
            android=str(body.get("android", ""))[:32],
            app_version=str(body.get("app_version", ""))[:32],
            last_ip=ip,
            name=str(body.get("model", ""))[:120],
        )
        db.add(dev)
        db.commit()
        return {"device_id": dev.uid, "device_key": key, "pairing_code": code}


def _free_code(db) -> str:
    for _ in range(50):
        code = new_pairing_code()
        if db.scalar(select(Device.id).where(Device.pairing_code == code)) is None:
            return code
    raise HTTPException(503, "не удалось выдать код подключения")


@router.post("/heartbeat")
async def heartbeat(request: Request, dev: Device = Depends(device)):
    body = await _json(request)
    app = request.app.state
    with app.db.sessions() as db:
        d = db.get(Device, dev.id)
        status = body.get("status") if isinstance(body.get("status"), dict) else {}
        status["content_version"] = body.get("content_version")
        status["screen"] = body.get("screen")
        d.status_json = json.dumps(status, ensure_ascii=False)[:20000]
        d.last_seen_at = utcnow()
        d.last_ip = request.client.host if request.client else ""
        d.app_version = str(status.get("app_version", d.app_version))[:32]
        d.model = str(status.get("model", d.model))[:120]
        d.android = str(status.get("android", d.android))[:32]
        resp: dict = {"paired": d.property_id is not None and d.room_id is not None,
                      "server_time": utcnow().isoformat() + "Z"}
        if not resp["paired"]:
            if d.pairing_code is None:
                d.pairing_code = _free_code(db)
            resp["pairing_code"] = d.pairing_code
        prop = db.get(Property, d.property_id) if d.property_id else None
        room = db.get(Room, d.room_id) if d.room_id else None
        doc = content.for_device(db, d, base_url(request))
        resp["content"] = {"version": doc.get("version", 0), "etag": content.etag(doc)}
        if prop is not None:
            resp["property"] = {"code": prop.code, "name": prop.name, "timezone": prop.timezone}
            resp["config"] = {"language": d.language, "servicePin": prop.service_pin,
                              "pollSeconds": prop.hub_poll_seconds}
        if room is not None:
            resp["room"] = room.label
            max_age = int(doc.get("hub", {}).get("maxAgeMinutes", 10))
            resp["guest"] = app.hub.guest_for(room, max_age)
        resp["messages"] = _messages(db, d, room) if prop is not None else []
        resp["commands"] = _commands(db, d)
        db.commit()
    return resp


@router.get("/content")
def get_content(request: Request, dev: Device = Depends(device)):
    with request.app.state.db.sessions() as db:
        d = db.get(Device, dev.id)
        doc = content.for_device(db, d, base_url(request))
    tag = content.etag(doc)
    if request.headers.get("if-none-match") == tag:
        return Response(status_code=304, headers={"ETag": tag})
    return Response(json.dumps(doc, ensure_ascii=False), media_type="application/json; charset=utf-8",
                    headers={"ETag": tag, "Cache-Control": "no-cache"})


@router.post("/commands/{cid}/ack")
async def ack(cid: int, request: Request, dev: Device = Depends(device)):
    body = await _json(request)
    with request.app.state.db.sessions() as db:
        c = db.get(Command, cid)
        if c is None or c.device_id != dev.id:
            raise HTTPException(404, "команда не найдена")
        c.done_at = utcnow()
        ok = bool(body.get("ok", True))
        c.result = (("выполнено" if ok else "ошибка") + (": " + str(body.get("result")) if body.get("result") else ""))[:300]
        db.commit()
    return {"ok": True}


def _messages(db, d: Device, room: Room | None) -> list[dict]:
    now = utcnow()
    conds = [Message.target == "all", (Message.target == "device") & (Message.target_id == d.id)]
    if room is not None:
        conds.append((Message.target == "room") & (Message.target_id == room.id))
        if room.group_id:
            conds.append((Message.target == "group") & (Message.target_id == room.group_id))
    rows = db.scalars(
        select(Message)
        .where(Message.property_id == d.property_id, Message.starts_at <= now,
               or_(Message.expires_at.is_(None), Message.expires_at > now), or_(*conds))
        .order_by(Message.created_at.desc())
        .limit(5)
    )
    out = []
    for m in rows:
        try:
            text = json.loads(m.text)
        except ValueError:
            text = {"ru": m.text}
        out.append({"id": m.id, "text": text,
                    "expires_at": m.expires_at.isoformat() + "Z" if m.expires_at else None})
    return out


def _commands(db, d: Device) -> list[dict]:
    now = utcnow()
    out = []
    for c in db.scalars(select(Command).where(Command.device_id == d.id, Command.delivered_at.is_(None))):
        if now - c.created_at > COMMAND_TTL:
            c.delivered_at, c.result = now, "просрочена: приставка не выходила на связь 24 часа"
            continue
        c.delivered_at = now
        out.append({"id": c.id, "kind": c.kind})
    return out


async def _json(request: Request) -> dict:
    try:
        data = await request.json()
    except ValueError:
        return {}
    return data if isinstance(data, dict) else {}


def is_online(dev: Device) -> bool:
    return dev.last_seen_at is not None and (utcnow() - dev.last_seen_at).total_seconds() <= ONLINE_SECONDS


def warnings(dev: Device) -> list[str]:
    """Предупреждения для дашборда по последнему состоянию приставки."""
    s = dev.status
    out = []
    if dev.last_seen_at is None:
        out.append("не выходила на связь")
    elif not is_online(dev):
        out.append("нет связи")
    if not dev.room_id:
        out.append("не привязана к номеру")
    if not s:
        return out
    net = s.get("network") or {}
    if net.get("type") == "wifi":
        out.append("Wi-Fi вместо Ethernet")
    if net and not net.get("internet", True):
        out.append("нет интернета")
    if s.get("default_home") is False:
        out.append("TENIR TV не домашний экран")
    mem = s.get("memory") or {}
    if mem.get("avail_mb") is not None and mem.get("avail_mb") < 200:
        out.append(f"мало памяти: {mem.get('avail_mb')} МБ")
    if s.get("errors"):
        out.append(f"ошибки: {len(s['errors'])}")
    return out
