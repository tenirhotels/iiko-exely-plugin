"""Панель администратора (Jinja, без SPA). Роли: admin — всё; editor — дизайн, контент, сообщения, команды;
viewer — просмотр. Все изменения пишутся в журнал."""

import json
import re
from datetime import datetime, timedelta
from importlib import resources
from urllib.parse import urlparse
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse, Response
from fastapi.templating import Jinja2Templates
from sqlalchemy import func, select

from . import content, tvapi
from .content import LANGS, ContentError, tr
from .db import (
    Audit,
    Command,
    ContentVersion,
    Device,
    Group,
    Media,
    Message,
    Property,
    Room,
    User,
    audit,
    utcnow,
)
from .hubsync import insecure_over_internet, normalize_hub_url
from .media import MediaError, store
from .security import check_password, hash_password

router = APIRouter()
templates = Jinja2Templates(directory=str(resources.files("tenircloud").joinpath("templates")))
templates.env.globals.update(tr=tr, LANGS=LANGS, TILES=content.TILES, ACTIONS=content.ACTIONS,
                             KINDS=tvapi.KINDS, is_online=tvapi.is_online, warnings=tvapi.warnings)

ROLES = {"viewer": 0, "editor": 1, "admin": 2}
ROLE_NAMES = {"admin": "администратор", "editor": "редактор", "viewer": "наблюдатель"}
KIND_NAMES = {"refresh": "Обновить данные", "reload_content": "Сбросить кеш и загрузить контент",
              "clear_apps": "Выйти из приложений (Netflix, YouTube)", "restart_app": "Перезапустить TENIR TV",
              "reboot": "Перезагрузить приставку"}
templates.env.globals.update(ROLE_NAMES=ROLE_NAMES, KIND_NAMES=KIND_NAMES)
_pair_fail: dict[str, list[datetime]] = {}


class Redirect(Exception):
    def __init__(self, url: str):
        self.url = url


# --- общие помощники ---

def db_of(request: Request):
    return request.app.state.db.sessions()


def current_user(request: Request, db) -> User | None:
    uid = request.session.get("uid")
    u = db.get(User, uid) if uid else None
    return u if u and u.active else None


def need(request: Request, db, role: str = "viewer") -> User:
    u = current_user(request, db)
    if u is None:
        if db.scalar(select(func.count()).select_from(User)) == 0:
            raise Redirect("/setup")
        raise Redirect("/login")
    if ROLES[u.role] < ROLES[role]:
        raise HTTPException(403, f"нужна роль «{ROLE_NAMES[role]}»")
    return u


def check_origin(request: Request) -> None:
    """Защита от CSRF: POST панели только со своего адреса."""
    origin = request.headers.get("origin") or request.headers.get("referer")
    if origin and urlparse(origin).netloc != request.url.netloc:
        raise HTTPException(403, "запрос с чужого сайта")


def current_property(request: Request, db) -> Property | None:
    pid = request.session.get("prop")
    prop = db.get(Property, pid) if pid else None
    if prop is None:
        prop = db.scalar(select(Property).order_by(Property.id))
        if prop:
            request.session["prop"] = prop.id
    return prop


def need_property(request: Request, db) -> Property:
    prop = current_property(request, db)
    if prop is None:
        raise Redirect("/settings")
    return prop


def page(request: Request, name: str, db, user: User, **ctx) -> HTMLResponse:
    flash = request.session.pop("flash", None)
    props = list(db.scalars(select(Property).order_by(Property.name)))
    return templates.TemplateResponse(
        request, name,
        {"user": user, "prop": current_property(request, db), "props": props, "flash": flash,
         "can_edit": ROLES[user.role] >= 1, "is_admin": user.role == "admin", **ctx},
    )


def back(request: Request, url: str, msg: str | None = None, error: bool = False) -> RedirectResponse:
    if msg:
        request.session["flash"] = {"text": msg, "error": error}
    return RedirectResponse(url, status_code=303)


def handler(fn):
    """Перехват Redirect (не вошёл, нет отеля) и ошибок контента в одном месте."""
    import functools
    import inspect

    @functools.wraps(fn)
    async def wrap(*args, **kwargs):
        request = kwargs.get("request")
        try:
            if request is not None and request.method == "POST":
                check_origin(request)
            res = fn(*args, **kwargs)
            if inspect.isawaitable(res):
                res = await res
            return res
        except Redirect as r:
            return RedirectResponse(r.url, status_code=303)
        except (ContentError, MediaError) as e:
            ref = request.headers.get("referer") or "/"
            return back(request, urlparse(ref).path or "/", str(e), error=True)

    return wrap


def local(dt: datetime | None, prop: Property | None) -> str:
    if dt is None:
        return "—"
    try:
        tz = ZoneInfo(prop.timezone) if prop else ZoneInfo("UTC")
    except ZoneInfoNotFoundError:
        tz = ZoneInfo("UTC")
    return dt.replace(tzinfo=ZoneInfo("UTC")).astimezone(tz).strftime("%d.%m %H:%M")


templates.env.filters["local"] = local


def ago(dt: datetime | None) -> str:
    if dt is None:
        return "никогда"
    s = int((utcnow() - dt).total_seconds())
    if s < 90:
        return "сейчас"
    if s < 3600:
        return f"{s // 60} мин назад"
    if s < 86400:
        return f"{s // 3600} ч назад"
    return f"{s // 86400} дн назад"


templates.env.filters["ago"] = ago


# --- вход ---

@router.get("/setup", response_class=HTMLResponse)
@handler
async def setup_form(request: Request):
    with db_of(request) as db:
        if db.scalar(select(func.count()).select_from(User)):
            return RedirectResponse("/login", status_code=303)
    return templates.TemplateResponse(request, "setup.html", {"error": None})


@router.post("/setup")
@handler
async def setup(request: Request):
    f = await request.form()
    with db_of(request) as db:
        if db.scalar(select(func.count()).select_from(User)):
            return RedirectResponse("/login", status_code=303)
        login, pw = str(f.get("login", "")).strip(), str(f.get("password", ""))
        if not re.fullmatch(r"[a-zA-Z0-9_.-]{3,64}", login) or len(pw) < 10:
            return templates.TemplateResponse(request, "setup.html",
                                              {"error": "логин 3–64 латинских символа, пароль от 10 символов"})
        u = User(login=login, name=str(f.get("name", "")).strip(), password_hash=hash_password(pw), role="admin")
        db.add(u)
        audit(db, login, "user.setup", "первый администратор")
        db.commit()
        request.session["uid"] = u.id
    return RedirectResponse("/settings", status_code=303)


@router.get("/login", response_class=HTMLResponse)
async def login_form(request: Request):
    return templates.TemplateResponse(request, "login.html", {"error": None})


@router.post("/login")
@handler
async def login(request: Request):
    f = await request.form()
    with db_of(request) as db:
        u = db.scalar(select(User).where(User.login == str(f.get("login", "")).strip()))
        if u is None or not u.active or not check_password(str(f.get("password", "")), u.password_hash):
            return templates.TemplateResponse(request, "login.html", {"error": "неверный логин или пароль"},
                                              status_code=401)
        u.last_login_at = utcnow()
        audit(db, u.login, "user.login")
        db.commit()
        request.session.clear()
        request.session["uid"] = u.id
    return RedirectResponse("/", status_code=303)


@router.post("/logout")
@handler
async def logout(request: Request):
    request.session.clear()
    return RedirectResponse("/login", status_code=303)


@router.post("/property")
@handler
async def switch_property(request: Request):
    f = await request.form()
    with db_of(request) as db:
        need(request, db)
        p = db.get(Property, int(f.get("id", 0) or 0))
        if p:
            request.session["prop"] = p.id
    return back(request, urlparse(request.headers.get("referer", "/")).path or "/")


# --- дашборд ---

@router.get("/", response_class=HTMLResponse)
@handler
async def dashboard(request: Request):
    with db_of(request) as db:
        user = need(request, db)
        cards = []
        for p in db.scalars(select(Property).order_by(Property.name)):
            devs = list(db.scalars(select(Device).where(Device.property_id == p.id)))
            online = sum(1 for d in devs if tvapi.is_online(d))
            st = request.app.state.hub.state(p.id)
            warn = [(d, w) for d in devs for w in tvapi.warnings(d)]
            versions: dict[str, int] = {}
            for d in devs:
                versions[d.app_version or "?"] = versions.get(d.app_version or "?", 0) + 1
            v = content.latest(db, p.id)
            cards.append({"p": p, "devices": len(devs), "online": online, "offline": len(devs) - online,
                          "hub": st, "warnings": warn[:12], "warn_count": len(warn), "versions": versions,
                          "content": v})
        pending = list(db.scalars(select(Device).where(Device.room_id.is_(None)).order_by(Device.created_at.desc())))
        return page(request, "dashboard.html", db, user, cards=cards, pending=pending)


# --- приставки ---

@router.get("/devices", response_class=HTMLResponse)
@handler
async def devices(request: Request, q: str = "", state: str = ""):
    with db_of(request) as db:
        user = need(request, db)
        prop = current_property(request, db)
        rows = list(db.scalars(select(Device).where((Device.property_id == prop.id) if prop else True)
                               .order_by(Device.name)))
        if q:
            ql = q.lower()
            rows = [d for d in rows if ql in (d.name or "").lower() or ql in (d.room.label if d.room else "").lower()
                    or ql in (d.model or "").lower()]
        if state == "online":
            rows = [d for d in rows if tvapi.is_online(d)]
        elif state == "offline":
            rows = [d for d in rows if not tvapi.is_online(d)]
        elif state == "warn":
            rows = [d for d in rows if tvapi.warnings(d)]
        rows.sort(key=lambda d: (d.room.label if d.room else "~", d.name))
        pending = list(db.scalars(select(Device).where(Device.property_id.is_(None))))
        rooms = list(db.scalars(select(Room).where(Room.property_id == prop.id).order_by(Room.label))) if prop else []
        return page(request, "devices.html", db, user, rows=rows, q=q, state=state, pending=pending, rooms=rooms)


@router.post("/pair")
@handler
async def pair(request: Request):
    f = await request.form()
    ip = request.client.host if request.client else "?"
    now = utcnow()
    fails = [t for t in _pair_fail.get(ip, []) if now - t < timedelta(minutes=15)]
    _pair_fail[ip] = fails
    if len(fails) >= 10:
        return back(request, "/devices", "слишком много неверных кодов, подождите 15 минут", True)
    with db_of(request) as db:
        user = need(request, db, "editor")
        prop = need_property(request, db)
        code = re.sub(r"\D", "", str(f.get("code", "")))
        d = db.scalar(select(Device).where(Device.pairing_code == code, Device.room_id.is_(None))) if code else None
        if d is None or now - d.created_at > timedelta(days=1) and d.last_seen_at is None:
            fails.append(now)
            return back(request, "/devices", "код не найден: проверьте цифры на экране телевизора", True)
        room = db.get(Room, int(f.get("room_id", 0) or 0))
        if room is None or room.property_id != prop.id:
            return back(request, "/devices", "выберите номер", True)
        d.property_id, d.room_id, d.pairing_code = prop.id, room.id, None
        if not d.name or d.name == d.model:
            d.name = f"ТВ {room.label}"
        audit(db, user.login, "device.pair", f"{prop.code}: {d.uid} → номер {room.label}")
        db.commit()
        return back(request, f"/devices/{d.id}", f"Приставка подключена к номеру {room.label}")


@router.get("/devices/{did}", response_class=HTMLResponse)
@handler
async def device_card(did: int, request: Request):
    with db_of(request) as db:
        user = need(request, db)
        d = db.get(Device, did)
        if d is None:
            raise HTTPException(404)
        rooms = list(db.scalars(select(Room).where(Room.property_id == d.property_id).order_by(Room.label))) \
            if d.property_id else []
        cmds = list(db.scalars(select(Command).where(Command.device_id == d.id).order_by(Command.id.desc()).limit(20)))
        guest = request.app.state.hub.guest_for(d.room) if d.room else None
        return page(request, "device.html", db, user, d=d, rooms=rooms, cmds=cmds, guest=guest,
                    status=d.status, status_json=json.dumps(d.status, ensure_ascii=False, indent=2))


@router.post("/devices/{did}")
@handler
async def device_save(did: int, request: Request):
    f = await request.form()
    with db_of(request) as db:
        user = need(request, db, "editor")
        d = db.get(Device, did)
        if d is None:
            raise HTTPException(404)
        d.name = str(f.get("name", d.name)).strip()[:120]
        lang = str(f.get("language", ""))
        d.language = lang if lang in LANGS else ""
        rid = int(f.get("room_id", 0) or 0)
        if rid:
            room = db.get(Room, rid)
            if room and room.property_id == d.property_id:
                d.room_id = room.id
        audit(db, user.login, "device.update", f"{d.uid}: {d.name}, номер {d.room.label if d.room else '—'}")
        db.commit()
    return back(request, f"/devices/{did}", "Сохранено")


@router.post("/devices/{did}/command")
@handler
async def device_command(did: int, request: Request):
    f = await request.form()
    kind = str(f.get("kind", ""))
    if kind not in tvapi.KINDS:
        raise HTTPException(400, "неизвестная команда")
    with db_of(request) as db:
        user = need(request, db, "editor")
        d = db.get(Device, did)
        if d is None:
            raise HTTPException(404)
        db.add(Command(device_id=d.id, kind=kind, created_by=user.login))
        audit(db, user.login, "device.command", f"{d.uid}: {kind}")
        db.commit()
    return back(request, f"/devices/{did}", f"Команда «{KIND_NAMES[kind]}» отправится при следующем сеансе (≤ 1 мин)")


@router.post("/devices/{did}/unpair")
@handler
async def device_unpair(did: int, request: Request):
    """Отвязать от номера (замена ТВ): приставка снова покажет код подключения."""
    with db_of(request) as db:
        user = need(request, db, "admin")
        d = db.get(Device, did)
        if d is None:
            raise HTTPException(404)
        audit(db, user.login, "device.unpair", f"{d.uid}: был номер {d.room.label if d.room else '—'}")
        d.room_id = None
        d.property_id = None
        d.pairing_code = None  # новый код выдаст ближайший heartbeat
        db.commit()
    return back(request, "/devices", "Приставка отвязана и покажет новый код подключения")


@router.post("/devices/{did}/delete")
@handler
async def device_delete(did: int, request: Request):
    """Удаление = ключ недействителен; приставка зарегистрируется заново и покажет новый код."""
    with db_of(request) as db:
        user = need(request, db, "admin")
        d = db.get(Device, did)
        if d is None:
            raise HTTPException(404)
        audit(db, user.login, "device.delete", f"{d.uid} {d.name}")
        db.delete(d)
        db.commit()
    return back(request, "/devices", "Приставка удалена")


# --- номера и группы ---

@router.get("/rooms", response_class=HTMLResponse)
@handler
async def rooms(request: Request):
    with db_of(request) as db:
        user = need(request, db)
        prop = need_property(request, db)
        rows = list(db.scalars(select(Room).where(Room.property_id == prop.id)))
        rows.sort(key=lambda r: (len(r.label), r.label))
        devs: dict[int, list[Device]] = {}
        for d in db.scalars(select(Device).where(Device.property_id == prop.id)):
            if d.room_id:
                devs.setdefault(d.room_id, []).append(d)
        groups = list(db.scalars(select(Group).where(Group.property_id == prop.id).order_by(Group.name)))
        hub = request.app.state.hub
        guests = {r.id: hub.guest_for(r) for r in rows}
        return page(request, "rooms.html", db, user, rows=rows, devs=devs, groups=groups, guests=guests,
                    hub=hub.state(prop.id))


@router.post("/rooms")
@handler
async def room_add(request: Request):
    f = await request.form()
    with db_of(request) as db:
        user = need(request, db, "admin")
        prop = need_property(request, db)
        label = str(f.get("label", "")).strip()[:32]
        if not label:
            return back(request, "/rooms", "укажите номер", True)
        if db.scalar(select(Room).where(Room.property_id == prop.id, Room.label == label)):
            return back(request, "/rooms", f"номер {label} уже есть", True)
        db.add(Room(property_id=prop.id, label=label))
        audit(db, user.login, "room.add", f"{prop.code}: {label}")
        db.commit()
    return back(request, "/rooms", f"Номер {label} добавлен")


@router.post("/rooms/groups")
@handler
async def rooms_groups(request: Request):
    """Назначение групп номерам одной формой (select на каждый номер)."""
    f = await request.form()
    with db_of(request) as db:
        user = need(request, db, "editor")
        prop = need_property(request, db)
        valid = {g.id for g in db.scalars(select(Group).where(Group.property_id == prop.id))}
        changed = 0
        for r in db.scalars(select(Room).where(Room.property_id == prop.id)):
            gid = int(f.get(f"g{r.id}", 0) or 0) or None
            if gid is not None and gid not in valid:
                continue
            if r.group_id != gid:
                r.group_id, changed = gid, changed + 1
        audit(db, user.login, "room.groups", f"{prop.code}: изменено {changed}")
        db.commit()
    return back(request, "/rooms", f"Группы сохранены ({changed})")


@router.get("/groups", response_class=HTMLResponse)
@handler
async def groups(request: Request):
    with db_of(request) as db:
        user = need(request, db)
        prop = need_property(request, db)
        rows = list(db.scalars(select(Group).where(Group.property_id == prop.id).order_by(Group.name)))
        counts = dict(db.execute(select(Room.group_id, func.count()).where(Room.property_id == prop.id)
                                 .group_by(Room.group_id)).all())
        return page(request, "groups.html", db, user, rows=rows, counts=counts)


@router.post("/groups")
@handler
async def group_add(request: Request):
    f = await request.form()
    with db_of(request) as db:
        user = need(request, db, "editor")
        prop = need_property(request, db)
        name = str(f.get("name", "")).strip()[:120]
        if not name:
            return back(request, "/groups", "укажите название", True)
        g = Group(property_id=prop.id, name=name)
        db.add(g)
        audit(db, user.login, "group.add", f"{prop.code}: {name}")
        db.commit()
        return back(request, f"/groups/{g.id}", "Группа создана")


@router.get("/groups/{gid}", response_class=HTMLResponse)
@handler
async def group_card(gid: int, request: Request):
    with db_of(request) as db:
        user = need(request, db)
        prop = need_property(request, db)
        g = db.get(Group, gid)
        if g is None or g.property_id != prop.id:
            raise HTTPException(404)
        draft = content.get_draft(db, prop)
        rooms_in = list(db.scalars(select(Room).where(Room.group_id == g.id).order_by(Room.label)))
        return page(request, "group.html", db, user, g=g, o=g.overrides, banners=draft.get("banners", []),
                    apps=draft.get("apps", []), rooms_in=rooms_in)


@router.post("/groups/{gid}")
@handler
async def group_save(gid: int, request: Request):
    f = await request.form()
    with db_of(request) as db:
        user = need(request, db, "editor")
        prop = need_property(request, db)
        g = db.get(Group, gid)
        if g is None or g.property_id != prop.id:
            raise HTTPException(404)
        if f.get("delete"):
            for r in db.scalars(select(Room).where(Room.group_id == g.id)):
                r.group_id = None
            audit(db, user.login, "group.delete", g.name)
            db.delete(g)
            db.commit()
            return back(request, "/groups", "Группа удалена")
        o = {}
        welcome = content.text(f, "welcome")
        if welcome:
            o["welcome"] = welcome
        color = str(f.get("accentColor", "")).strip()
        if color and f.get("useAccent"):
            o["accentColor"] = _color(color)
        banners = f.getlist("banners")
        if banners:
            o["banners"] = [str(b) for b in banners]
        tiles = _tiles(f)
        if tiles and f.get("useTiles"):
            o["tiles"] = tiles
        lang = str(f.get("language", ""))
        if lang in LANGS:
            o["language"] = lang
        g.name = str(f.get("name", g.name)).strip()[:120] or g.name
        g.overrides_json = json.dumps(o, ensure_ascii=False)
        audit(db, user.login, "group.update", f"{g.name}: {sorted(o)}")
        db.commit()
    return back(request, f"/groups/{gid}", "Сохранено. Изменения группы применяются сразу, без публикации")


# --- дизайн ---

def _color(v: str) -> str:
    v = v.strip()
    if not re.fullmatch(r"#[0-9a-fA-F]{6}", v):
        raise ContentError("цвет в формате #RRGGBB")
    return v.upper()


def _tiles(f) -> list[str]:
    """Плитки по порядку: поля tile_<id> с номером позиции; 0 или пусто — не показывать."""
    pos = []
    for key, val in f.items():
        if key.startswith("tile_"):
            try:
                n = int(val)
            except ValueError:
                continue
            if n > 0:
                pos.append((n, key[5:]))
    return [t for _, t in sorted(pos)]


@router.get("/design", response_class=HTMLResponse)
@handler
async def design(request: Request):
    with db_of(request) as db:
        user = need(request, db)
        prop = need_property(request, db)
        doc = content.get_draft(db, prop)
        db.commit()
        media = list(db.scalars(select(Media).where(Media.property_id == prop.id).order_by(Media.id.desc())))
        tiles = doc.get("home", {}).get("tiles", [])
        all_tiles = list(content.TILES) + [a.get("id") for a in doc.get("apps", []) if a.get("id")]
        return page(request, "design.html", db, user, doc=doc, h=doc.get("hotel", {}), dp=doc.get("display", {}),
                    tiles=tiles, all_tiles=all_tiles, media=media)


@router.post("/design")
@handler
async def design_save(request: Request):
    f = await request.form()
    with db_of(request) as db:
        user = need(request, db, "editor")
        prop = need_property(request, db)
        doc = content.get_draft(db, prop)
        h = doc.setdefault("hotel", {})
        h["name"] = str(f.get("name", "")).strip() or h.get("name", "")
        h["tagline"] = content.text(f, "tagline")
        h["welcome"] = content.text(f, "welcome")
        h["accentColor"] = _color(str(f.get("accentColor", "#C9A96E")))
        h["logo"] = str(f.get("logo", "")).strip() or None
        h["background"] = str(f.get("background", "")).strip() or None
        d = doc.setdefault("display", {})
        for key in ("showClock", "showWeather", "showGuestName", "showRoomNumber", "use24h"):
            d[key] = bool(f.get(key))
        fmt = str(f.get("guestNameFormat", "first_name"))
        d["guestNameFormat"] = fmt if fmt in ("first_name", "full", "none") else "first_name"
        d["bannerIntervalSeconds"] = max(3, min(120, int(f.get("bannerIntervalSeconds", 9) or 9)))
        d["headlineFont"] = "sans" if f.get("headlineFont") == "sans" else "serif"
        d["backgroundDim"] = max(0, min(100, int(f.get("backgroundDim", 70) or 70)))
        lang = str(f.get("language", "ru"))
        doc["language"] = lang if lang in LANGS else "ru"
        tiles = _tiles(f)
        if not tiles:
            raise ContentError("включите хотя бы одну плитку")
        doc.setdefault("home", {})["tiles"] = tiles
        content.save_draft(db, prop, doc, user.login)
        audit(db, user.login, "design.update", prop.code)
        db.commit()
    return back(request, "/design", "Черновик сохранён. Чтобы приставки получили изменения — «Опубликовать»")


@router.get("/preview", response_class=HTMLResponse)
@handler
async def preview(request: Request, lang: str = "ru", guest: int = 1, group: int = 0, version: int = 0):
    """Предпросмотр главного экрана 960×540 (как на телевизоре) по черновику или версии."""
    with db_of(request) as db:
        user = need(request, db)
        prop = need_property(request, db)
        if version:
            v = db.scalar(select(ContentVersion).where(ContentVersion.property_id == prop.id,
                                                        ContentVersion.version == version))
            doc = json.loads(v.json) if v else content.get_draft(db, prop)
        else:
            doc = content.get_draft(db, prop)
        g = db.get(Group, group) if group else None
        doc = content.apply_group(doc, g if g and g.property_id == prop.id else None)
        lang = lang if lang in LANGS else "ru"
        apps = {a.get("id"): a for a in doc.get("apps", [])}
        try:
            now = datetime.now(ZoneInfo(prop.timezone))
        except ZoneInfoNotFoundError:
            now = datetime.now()
        return templates.TemplateResponse(request, "preview.html", {
            "doc": doc, "h": doc.get("hotel", {}), "dp": doc.get("display", {}), "lang": lang,
            "guest": bool(guest), "now": now, "apps": apps, "user": user,
            "restaurant": doc.get("restaurant") or {}})


# --- контент ---

@router.get("/content", response_class=HTMLResponse)
@handler
async def content_page(request: Request):
    with db_of(request) as db:
        user = need(request, db)
        prop = need_property(request, db)
        doc = content.get_draft(db, prop)
        db.commit()
        versions = list(db.scalars(select(ContentVersion).where(ContentVersion.property_id == prop.id)
                                   .order_by(ContentVersion.version.desc()).limit(15)))
        published = json.loads(versions[0].json) if versions else None
        dirty = published is None or {k: v for k, v in published.items() if k != "version"} != \
            {k: v for k, v in doc.items() if k != "version"}
        return page(request, "content.html", db, user, doc=doc, h=doc.get("hotel", {}), versions=versions,
                    dirty=dirty, r=doc.get("restaurant") or {})


@router.post("/content/hotel")
@handler
async def content_hotel(request: Request):
    f = await request.form()
    with db_of(request) as db:
        user = need(request, db, "editor")
        prop = need_property(request, db)
        doc = content.get_draft(db, prop)
        h = doc.setdefault("hotel", {})
        h["info"] = content.text(f, "info")
        h["phone"] = str(f.get("phone", "")).strip() or None
        ssid = str(f.get("ssid", "")).strip()
        h["wifi"] = {"ssid": ssid, "password": str(f.get("wifiPassword", "")).strip()} if ssid else None
        lat, lon = str(f.get("lat", "")).strip(), str(f.get("lon", "")).strip()
        if lat and lon:
            try:
                doc["weather"] = {"latitude": float(lat), "longitude": float(lon), "label": content.text(f, "wlabel")}
            except ValueError:
                raise ContentError("координаты погоды — числа, например 43.1286") from None
        else:
            doc.pop("weather", None)
        content.save_draft(db, prop, doc, user.login)
        db.commit()
    return back(request, "/content", "Черновик сохранён")


def _items(doc: dict, kind: str) -> list:
    if kind == "banners":
        return doc.setdefault("banners", [])
    if kind == "news":
        return doc.setdefault("news", [])
    if kind == "apps":
        return doc.setdefault("apps", [])
    raise HTTPException(404)


@router.get("/content/{kind}/{item_id}", response_class=HTMLResponse)
@handler
async def item_form(kind: str, item_id: str, request: Request):
    with db_of(request) as db:
        user = need(request, db)
        prop = need_property(request, db)
        doc = content.get_draft(db, prop)
        items = _items(doc, kind)
        item = next((x for x in items if x.get("id") == item_id), None) if item_id != "new" else {}
        if item is None:
            raise HTTPException(404)
        media = list(db.scalars(select(Media).where(Media.property_id == prop.id).order_by(Media.id.desc())))
        return page(request, f"item_{kind}.html", db, user, item=item, new=item_id == "new", kind=kind,
                    media=media, news=doc.get("news", []), apps=doc.get("apps", []))


@router.post("/content/{kind}/{item_id}")
@handler
async def item_save(kind: str, item_id: str, request: Request):
    f = await request.form()
    with db_of(request) as db:
        user = need(request, db, "editor")
        prop = need_property(request, db)
        doc = content.get_draft(db, prop)
        items = _items(doc, kind)
        idx = next((i for i, x in enumerate(items) if x.get("id") == item_id), None)
        if f.get("delete"):
            if idx is not None:
                items.pop(idx)
            content.save_draft(db, prop, doc, user.login)
            db.commit()
            return back(request, "/content", "Удалено из черновика")
        if f.get("move") and idx is not None:
            j = idx - 1 if f.get("move") == "up" else idx + 1
            if 0 <= j < len(items):
                items[idx], items[j] = items[j], items[idx]
            content.save_draft(db, prop, doc, user.login)
            db.commit()
            return back(request, "/content")
        new_id = re.sub(r"[^a-z0-9_-]", "", str(f.get("id", "")).strip().lower()) or None
        if kind == "apps":
            pk = [p.strip() for p in str(f.get("packages", "")).replace(",", "\n").splitlines() if p.strip()]
            if not new_id or not pk:
                raise ContentError("нужны id и хотя бы один пакет")
            item = {"id": new_id, "title": str(f.get("title", new_id)).strip(), "packages": pk}
            color = str(f.get("color", "")).strip()
            if color:
                item["color"] = _color(color)
        elif kind == "banners":
            title = content.text(f, "title")
            image = str(f.get("image", "")).strip() or None
            if not title and not image:
                raise ContentError("нужен заголовок или изображение")
            action = str(f.get("action", "none"))
            item = {"id": new_id or f"b{len(items) + 1}", "label": content.text(f, "label"), "title": title,
                    "subtitle": content.text(f, "subtitle"), "image": image,
                    "action": {"type": action if action in content.ACTIONS else "none",
                               "target": str(f.get("target", "")).strip() or None}}
        else:
            title = content.text(f, "title")
            if not title:
                raise ContentError("нужен заголовок")
            item = {"id": new_id or f"n{len(items) + 1}", "date": str(f.get("date", "")).strip() or None,
                    "title": title, "summary": content.text(f, "summary"), "body": content.text(f, "body"),
                    "image": str(f.get("image", "")).strip() or None}
        clash = next((i for i, x in enumerate(items) if x.get("id") == item["id"]), None)
        if clash is not None and clash != idx:
            raise ContentError(f"id «{item['id']}» уже занят")
        if idx is None:
            items.append(item)
        else:
            items[idx] = item
        content.save_draft(db, prop, doc, user.login)
        db.commit()
    return back(request, "/content", "Черновик сохранён")


@router.post("/content/restaurant")
@handler
async def restaurant_save(request: Request):
    """Ресторан и меню: категории и блюда — текстом, по строке на блюдо (удобно для правки и вставки из Excel)."""
    f = await request.form()
    with db_of(request) as db:
        user = need(request, db, "editor")
        prop = need_property(request, db)
        doc = content.get_draft(db, prop)
        name = content.text(f, "rname")
        if not name:
            doc.pop("restaurant", None)
        else:
            cats = json.loads(str(f.get("menu_json", "[]")) or "[]")
            if not isinstance(cats, list):
                raise ContentError("меню: список категорий")
            doc["restaurant"] = {
                "name": name, "description": content.text(f, "rdesc"), "hours": content.text(f, "rhours"),
                "orderInfo": content.text(f, "rorder"), "image": str(f.get("rimage", "")).strip() or None,
                "currency": str(f.get("currency", "₸")).strip() or "₸", "categories": cats,
            }
        content.save_draft(db, prop, doc, user.login)
        db.commit()
    return back(request, "/content", "Черновик сохранён")


@router.get("/content/json", response_class=HTMLResponse)
@handler
async def raw_form(request: Request):
    with db_of(request) as db:
        user = need(request, db)
        prop = need_property(request, db)
        doc = content.get_draft(db, prop)
        db.commit()
        return page(request, "raw.html", db, user, text=json.dumps(doc, ensure_ascii=False, indent=2))


@router.post("/content/json")
@handler
async def raw_save(request: Request):
    f = await request.form()
    with db_of(request) as db:
        user = need(request, db, "admin")
        prop = need_property(request, db)
        try:
            doc = json.loads(str(f.get("json", "")))
        except ValueError as e:
            raise ContentError(f"не JSON: {e}") from None
        content.save_draft(db, prop, doc, user.login)
        audit(db, user.login, "content.raw", prop.code)
        db.commit()
    return back(request, "/content", "Черновик заменён")


@router.post("/content/publish")
@handler
async def publish(request: Request):
    f = await request.form()
    with db_of(request) as db:
        user = need(request, db, "editor")
        prop = need_property(request, db)
        v = content.publish(db, prop, user.login, str(f.get("note", "")).strip())
        db.commit()
    return back(request, "/content", f"Опубликована версия {v.version}: приставки получат её за ≤ 1 минуту")


@router.post("/versions/{version}/rollback")
@handler
async def rollback(version: int, request: Request):
    with db_of(request) as db:
        user = need(request, db, "editor")
        prop = need_property(request, db)
        v = content.rollback(db, prop, version, user.login)
        db.commit()
    return back(request, "/content", f"Версия {version} опубликована заново как {v.version}")


@router.post("/content/discard")
@handler
async def discard(request: Request):
    with db_of(request) as db:
        user = need(request, db, "editor")
        prop = need_property(request, db)
        doc = content.latest_doc(db, prop) or content.default_content()
        content.save_draft(db, prop, doc, user.login)
        db.commit()
    return back(request, "/content", "Черновик возвращён к опубликованной версии")


# --- сообщения ---

@router.get("/messages", response_class=HTMLResponse)
@handler
async def messages(request: Request):
    with db_of(request) as db:
        user = need(request, db)
        prop = need_property(request, db)
        rows = list(db.scalars(select(Message).where(Message.property_id == prop.id)
                               .order_by(Message.created_at.desc()).limit(50)))
        now = utcnow()
        groups = list(db.scalars(select(Group).where(Group.property_id == prop.id).order_by(Group.name)))
        rooms = list(db.scalars(select(Room).where(Room.property_id == prop.id).order_by(Room.label)))
        names = {("group", g.id): g.name for g in groups} | {("room", r.id): f"номер {r.label}" for r in rooms}
        items = []
        for m in rows:
            try:
                text = json.loads(m.text)
            except ValueError:
                text = {"ru": m.text}
            active = m.starts_at <= now and (m.expires_at is None or m.expires_at > now)
            items.append({"m": m, "text": text, "active": active,
                          "target": "все" if m.target == "all" else names.get((m.target, m.target_id), m.target)})
        return page(request, "messages.html", db, user, items=items, groups=groups, rooms=rooms)


@router.post("/messages")
@handler
async def message_add(request: Request):
    f = await request.form()
    with db_of(request) as db:
        user = need(request, db, "editor")
        prop = need_property(request, db)
        text = content.text(f, "text")
        if not text:
            raise ContentError("текст сообщения пустой")
        target = str(f.get("target", "all"))
        tid = None
        if target.startswith("group:"):
            target, tid = "group", int(target[6:])
        elif target.startswith("room:"):
            target, tid = "room", int(target[5:])
        else:
            target = "all"
        hours = float(f.get("hours", 0) or 0)
        m = Message(property_id=prop.id, target=target, target_id=tid, text=json.dumps(text, ensure_ascii=False),
                    expires_at=utcnow() + timedelta(hours=hours) if hours > 0 else None, created_by=user.login)
        db.add(m)
        audit(db, user.login, "message.add", f"{target}:{tid or ''} {tr(text)[:80]}")
        db.commit()
    return back(request, "/messages", "Сообщение отправлено: появится на экранах за ≤ 1 минуту")


@router.post("/messages/{mid}/stop")
@handler
async def message_stop(mid: int, request: Request):
    with db_of(request) as db:
        user = need(request, db, "editor")
        m = db.get(Message, mid)
        if m:
            m.expires_at = utcnow()
            audit(db, user.login, "message.stop", str(mid))
            db.commit()
    return back(request, "/messages", "Сообщение снято")


# --- медиатека ---

@router.get("/library", response_class=HTMLResponse)
@handler
async def library(request: Request):
    with db_of(request) as db:
        user = need(request, db)
        prop = need_property(request, db)
        rows = list(db.scalars(select(Media).where(Media.property_id == prop.id).order_by(Media.id.desc())))
        return page(request, "library.html", db, user, rows=rows)


@router.post("/library")
@handler
async def library_upload(request: Request):
    f = await request.form()
    with db_of(request) as db:
        user = need(request, db, "editor")
        prop = need_property(request, db)
        n = 0
        warn = []
        for up in f.getlist("files"):
            if not hasattr(up, "read"):
                continue
            m = store(db, request.app.state.media_dir, prop.id, await up.read(), up.filename or "")
            n += 1
            if m.width < 1920:
                warn.append(f"{up.filename}: {m.width}×{m.height} — мало для 4K")
        audit(db, user.login, "media.upload", f"{prop.code}: {n}")
        db.commit()
    msg = f"Загружено: {n}" + (". " + "; ".join(warn) if warn else "")
    return back(request, "/library", msg, error=bool(warn))


@router.post("/library/{mid}/delete")
@handler
async def library_delete(mid: int, request: Request):
    with db_of(request) as db:
        user = need(request, db, "editor")
        m = db.get(Media, mid)
        if m:
            used = any(f"/media/{m.filename}" in json.dumps(json.loads(v.json)) for v in
                       db.scalars(select(ContentVersion).where(ContentVersion.property_id == m.property_id)
                                  .order_by(ContentVersion.version.desc()).limit(1)))
            if used:
                return back(request, "/library", "файл используется в опубликованном контенте", True)
            (request.app.state.media_dir / m.filename).unlink(missing_ok=True)
            audit(db, user.login, "media.delete", m.filename)
            db.delete(m)
            db.commit()
    return back(request, "/library", "Удалено")


# --- отели и настройки ---

@router.get("/settings", response_class=HTMLResponse)
@handler
async def settings(request: Request, check: str = ""):
    with db_of(request) as db:
        user = need(request, db)
        rows = list(db.scalars(select(Property).order_by(Property.name)))
        return page(request, "settings.html", db, user, rows=rows, check=check)


@router.post("/settings")
@handler
async def settings_save(request: Request):
    f = await request.form()
    with db_of(request) as db:
        user = need(request, db, "admin")
        pid = int(f.get("id", 0) or 0)
        p = db.get(Property, pid) if pid else None
        code = str(f.get("code", "")).strip()
        name = str(f.get("name", "")).strip()
        if not code or not name:
            raise ContentError("нужны код отеля (как в Hotel Hub) и название")
        tz = str(f.get("timezone", "Asia/Almaty")).strip()
        try:
            ZoneInfo(tz)
        except (ZoneInfoNotFoundError, ValueError):
            raise ContentError(f"неизвестный часовой пояс {tz}") from None
        if p is None:
            if db.scalar(select(Property).where(Property.code == code)):
                raise ContentError(f"отель с кодом {code} уже есть")
            p = Property(code=code, name=name)
            db.add(p)
        p.code, p.name, p.timezone = code, name, tz
        p.hub_url = normalize_hub_url(str(f.get("hub_url", "")))
        token = str(f.get("hub_token", "")).strip()
        if token:
            p.hub_token_enc = request.app.state.secrets.encrypt(token)
        if f.get("clear_token"):
            p.hub_token_enc = ""
        p.hub_poll_seconds = max(15, min(3600, int(f.get("hub_poll_seconds", 60) or 60)))
        pin = str(f.get("service_pin", "")).strip()
        if pin:
            if not re.fullmatch(r"\d{4,8}", pin):
                raise ContentError("PIN — 4–8 цифр")
            p.service_pin = pin
        audit(db, user.login, "property.save", f"{p.code} {p.name}, хаб {p.hub_url or '—'}")
        db.commit()
        request.session["prop"] = p.id
        if insecure_over_internet(p.hub_url):
            return back(request, "/settings", "Сохранено, но адрес хаба по http:// не из локальной сети: токен будет "
                        "уходить по интернету открытым текстом. Используйте https://", error=True)
    return back(request, "/settings", "Сохранено")


@router.post("/settings/{pid}/check")
@handler
async def settings_check(pid: int, request: Request):
    with db_of(request) as db:
        need(request, db, "editor")
        p = db.get(Property, pid)
        if p is None:
            raise HTTPException(404)
        msg = request.app.state.hub.check(p)
    if msg.startswith("хаб доступен"):
        request.app.state.hub.poll(pid)
    return back(request, "/settings", f"{p.code}: {msg}", error=not msg.startswith("хаб доступен"))


# --- пользователи и журнал ---

@router.get("/users", response_class=HTMLResponse)
@handler
async def users(request: Request):
    with db_of(request) as db:
        user = need(request, db, "admin")
        rows = list(db.scalars(select(User).order_by(User.login)))
        return page(request, "users.html", db, user, rows=rows)


@router.post("/users")
@handler
async def user_save(request: Request):
    f = await request.form()
    with db_of(request) as db:
        user = need(request, db, "admin")
        uid = int(f.get("id", 0) or 0)
        u = db.get(User, uid) if uid else None
        login = str(f.get("login", "")).strip()
        pw = str(f.get("password", ""))
        role = str(f.get("role", "viewer"))
        if role not in ROLES:
            raise ContentError("неизвестная роль")
        if u is None:
            if not re.fullmatch(r"[a-zA-Z0-9_.-]{3,64}", login) or len(pw) < 10:
                raise ContentError("логин 3–64 латинских символа, пароль от 10 символов")
            if db.scalar(select(User).where(User.login == login)):
                raise ContentError("такой логин уже есть")
            u = User(login=login, password_hash=hash_password(pw))
            db.add(u)
        elif pw:
            if len(pw) < 10:
                raise ContentError("пароль от 10 символов")
            u.password_hash = hash_password(pw)
        if u.id == user.id and (role != "admin" or not f.get("active")):
            raise ContentError("нельзя снять роль администратора или отключить себя")
        u.name = str(f.get("name", "")).strip()
        u.role = role
        u.active = bool(f.get("active")) if uid else True
        audit(db, user.login, "user.save", f"{u.login}: {role}, {'активен' if u.active else 'отключён'}")
        db.commit()
    return back(request, "/users", "Сохранено")


@router.get("/audit", response_class=HTMLResponse)
@handler
async def audit_log(request: Request):
    with db_of(request) as db:
        user = need(request, db, "admin")
        rows = list(db.scalars(select(Audit).order_by(Audit.id.desc()).limit(300)))
        return page(request, "audit.html", db, user, rows=rows)


@router.get("/static/style.css")
async def style():
    css = resources.files("tenircloud").joinpath("static/style.css").read_text("utf-8")
    return Response(css, media_type="text/css", headers={"Cache-Control": "public, max-age=3600"})



@router.get("/static/demo/{name}")
async def demo_image(name: str):
    """Демо-изображения для предпросмотра (на приставках они встроены в APK как asset:///demo/…)."""
    if not re.fullmatch(r"[a-z0-9-]+\.jpg", name):
        raise HTTPException(404)
    f = resources.files("tenircloud").joinpath("static/demo").joinpath(name)
    if not f.is_file():
        raise HTTPException(404)
    return Response(f.read_bytes(), media_type="image/jpeg", headers={"Cache-Control": "public, max-age=86400"})
