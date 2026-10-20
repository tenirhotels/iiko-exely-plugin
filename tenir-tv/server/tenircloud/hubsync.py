"""Синхронизация с Hotel Hub (API v1, только чтение): номера и гости по заездам.

Один опрос на отель вместо опроса от каждой приставки. Имена гостей хранятся только в памяти процесса
и только для заселённых (checked_in) номеров; при выезде стираются.
"""

import ipaddress
import logging
import os
import threading
from dataclasses import dataclass, field
from datetime import UTC, datetime
from urllib.parse import quote, urlparse

import httpx
from sqlalchemy import select

from .db import Property, Room, audit, utcnow

log = logging.getLogger("tenircloud.hub")

GUEST_REFRESH_POLLS = 10  # имена перечитываются при смене стоянки и раз в 10 опросов


@dataclass
class Guest:
    stay_id: str
    first_name: str | None
    last_name: str | None
    poll: int


@dataclass
class HubState:
    last_ok_at: datetime | None = None
    last_error: str | None = None
    polls: int = 0
    fails: int = 0  # подряд неудачных опросов: интервал растёт (до ×8), пока хаб или интернет недоступны
    rooms_total: int = 0
    checked_in: int = 0
    guests: dict[int, Guest] = field(default_factory=dict)  # room.id → гость


class HubError(Exception):
    pass


def normalize_hub_url(raw: str) -> str:
    """«hub.example.com» → «https://hub.example.com»: хаб на VPS в интернете, схему часто не вводят."""
    t = (raw or "").strip().rstrip("/")
    if not t:
        return ""
    return t if t.startswith(("http://", "https://")) else "https://" + t


def insecure_over_internet(url: str) -> bool:
    """http:// к адресу не из локальной сети: токен хаба ушёл бы по интернету открытым текстом."""
    u = urlparse(normalize_hub_url(url))
    if u.scheme != "http" or not u.hostname:
        return False
    host = u.hostname
    try:
        ip = ipaddress.ip_address(host)
        return not (ip.is_private or ip.is_loopback or ip.is_link_local)
    except ValueError:
        return not (host == "localhost" or host.endswith((".local", ".lan", ".internal")) or "." not in host)


def probe(url: str, code: str, token: str, transport: httpx.BaseTransport | None = None) -> list[tuple[bool, str]]:
    """Проверка хаба с этой машины по шагам: [(успех, сообщение)]. Имена гостей не выводятся — только счётчики."""
    import time

    out: list[tuple[bool, str]] = []
    base = normalize_hub_url(url)
    if insecure_over_internet(base):
        out.append((False, "адрес по http:// не из локальной сети: токен пойдёт по интернету открытым текстом"))
    verify = os.environ.get("TENIR_CLOUD_HUB_CA") or True
    with httpx.Client(base_url=base + "/", headers={"Authorization": f"Bearer {token}"}, verify=verify,
                      timeout=httpx.Timeout(15.0, connect=8.0), transport=transport) as c:
        def get(path: str):
            t = time.perf_counter()
            r = c.get(path)
            return r, (time.perf_counter() - t) * 1000

        try:
            r, ms = get("v1/status")
        except httpx.HTTPError as e:
            msg = f"хаб недоступен из этой сети ({e.__class__.__name__}: {str(e)[:100]})"
            return [*out, (False, msg + ": проверьте интернет на сервере, адрес и DNS")]
        out.append((r.status_code == 200, f"v1/status → HTTP {r.status_code}, {ms:.0f} мс"
                    + ("" if r.status_code == 200 else " (токен не принят?)" if r.status_code == 401 else "")))
        if r.status_code != 200:
            return out
        r, ms = get(f"v1/properties/{quote(code)}/rooms")
        if r.status_code != 200:
            hint = {403: "нет права occupancy", 404: "отель с таким кодом не найден", 401: "токен не принят"}.get(
                r.status_code, "")
            return out + [(False, f"rooms → HTTP {r.status_code} {hint}".strip())]
        rooms = r.json().get("rooms", [])
        busy = [x for x in rooms if x.get("status") == "checked_in" and x.get("stay_id")]
        out.append((True, f"rooms → {len(rooms)} номеров, заселено {len(busy)}, {ms:.0f} мс (право occupancy есть)"))
        if busy:
            r, ms = get(f"v1/properties/{quote(code)}/stays/{quote(busy[0]['stay_id'])}/guests")
            if r.status_code == 200:
                g = [x for x in r.json().get("guests", []) if x.get("role") == "guest"]
                out.append((True, f"guests → HTTP 200, проживающих в стоянке: {len(g)} (право guests есть), {ms:.0f} мс"))
            else:
                out.append((False, f"guests → HTTP {r.status_code}: "
                            + ("нет права guests — приветствие будет без имени" if r.status_code == 403 else "ошибка")))
        else:
            out.append((True, "заселённых номеров нет — право guests проверить не на чем"))
    return out


class HubSync:
    def __init__(self, database, secrets, transport: httpx.BaseTransport | None = None):
        self.db = database
        self.secrets = secrets
        self.transport = transport
        self.states: dict[int, HubState] = {}
        self.lock = threading.Lock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def state(self, prop_id: int) -> HubState:
        with self.lock:
            return self.states.setdefault(prop_id, HubState())

    # --- HTTP ---

    def _client(self, prop: Property) -> httpx.Client:
        token = self.secrets.decrypt(prop.hub_token_enc)
        # свой корневой сертификат (внутренний CA, прокси с перехватом TLS): TENIR_CLOUD_HUB_CA=/путь/ca.pem
        verify = os.environ.get("TENIR_CLOUD_HUB_CA") or True
        return httpx.Client(
            base_url=normalize_hub_url(prop.hub_url) + "/",
            headers={"Authorization": f"Bearer {token}", "Accept": "application/json"},
            timeout=httpx.Timeout(15.0, connect=8.0),  # хаб в интернете: задержки больше, чем в LAN
            transport=self.transport,
            verify=verify,
            follow_redirects=False,  # токен не уходит на другой адрес по редиректу
        )

    @staticmethod
    def _get(client: httpx.Client, path: str) -> dict:
        try:
            r = client.get(path)
        except httpx.HTTPError as e:
            detail = str(e)
            if "CERTIFICATE" in detail.upper() or "SSL" in detail.upper():
                raise HubError("сертификат Hotel Hub не принят (TLS): проверьте адрес и дату на сервере, "
                               "для внутреннего CA задайте TENIR_CLOUD_HUB_CA") from e
            raise HubError(f"Hotel Hub недоступен из этой сети: {e.__class__.__name__}"
                           f"{': ' + detail[:80] if detail else ''}") from e
        if r.status_code == 401:
            raise HubError("токен не принят (401)")
        if r.status_code == 403:
            raise HubError(f"у токена нет права для {path.split('?')[0]} (403)")
        if r.status_code == 429:
            raise HubError("лимит запросов хаба (429)")
        if 300 <= r.status_code < 400:
            raise HubError(f"Hotel Hub перенаправляет запрос (HTTP {r.status_code}): укажите конечный адрес, "
                           "обычно https://")
        if r.status_code >= 400:
            raise HubError(f"Hotel Hub вернул HTTP {r.status_code}")
        try:
            return r.json()
        except ValueError as e:
            raise HubError("ответ хаба не JSON") from e

    def check(self, prop: Property) -> str:
        """Проверка подключения для панели (без записи в базу)."""
        if not prop.hub_url or not prop.hub_token_enc:
            return "адрес или токен хаба не заданы"
        warn = (" ВНИМАНИЕ: адрес по http:// — токен идёт по интернету открытым текстом, используйте https://"
                if insecure_over_internet(prop.hub_url) else "")
        try:
            with self._client(prop) as c:
                rooms = self._get(c, f"v1/properties/{quote(prop.code)}/rooms").get("rooms", [])
        except HubError as e:
            return str(e)
        busy = sum(1 for r in rooms if r.get("status") == "checked_in")
        return f"хаб доступен, номеров: {len(rooms)}, заселено: {busy}.{warn}"

    # --- опрос ---

    def poll(self, prop_id: int) -> HubState:
        st = self.state(prop_id)
        with self.db.sessions() as db:
            prop = db.get(Property, prop_id)
            if prop is None or not prop.hub_url or not prop.hub_token_enc:
                return st
            code = quote(prop.code)
            try:
                with self._client(prop) as c:
                    rows = self._get(c, f"v1/properties/{code}/rooms").get("rooms", [])
                    self._apply(db, prop, st, rows, c, code)
            except HubError as e:
                st.fails += 1
                st.last_error = str(e)
                if "401" in st.last_error or "403" in st.last_error:
                    st.guests.clear()  # отказ токена: имена больше не подтверждены
                db.commit()
                log.warning("%s: %s", prop.code, e)
                return st
            db.commit()
        return st

    def _apply(self, db, prop: Property, st: HubState, rows: list, client, code: str) -> None:
        st.polls += 1
        now = utcnow()
        existing = {r.label.lower(): r for r in db.scalars(select(Room).where(Room.property_id == prop.id))}
        busy = 0
        for row in rows:
            label = str(row.get("room") or row.get("room_id") or "").strip()
            if not label:
                continue
            room = existing.get(label.lower())
            if room is None:
                room = Room(property_id=prop.id, label=label, from_hub=True)
                db.add(room)
                db.flush()
                existing[label.lower()] = room
            room.from_hub = True
            room.pms_room_id = row.get("room_id")
            status = row.get("status") or "free"
            stay = row.get("stay_id") if status == "checked_in" else None
            prev = st.guests.get(room.id)
            if prev is not None and prev.stay_id != stay:
                del st.guests[room.id]
                audit(db, "hub", "guest.checkout", f"{prop.code}: номер {room.label}, стоянка {prev.stay_id}")
            room.hub_status, room.stay_id = status, stay
            room.planned_out = row.get("planned_out")
            room.hub_seen_at = now
            if stay:
                busy += 1
                cur = st.guests.get(room.id)
                if cur is None or st.polls - cur.poll >= GUEST_REFRESH_POLLS:
                    st.guests[room.id] = self._guest(client, code, stay, st.polls)
        seen = {str(r.get("room") or r.get("room_id") or "").strip().lower() for r in rows}
        for label, room in existing.items():
            if room.from_hub and label not in seen:
                room.hub_status, room.stay_id = "unknown", None
                st.guests.pop(room.id, None)
        st.rooms_total, st.checked_in = len(rows), busy
        st.last_ok_at, st.last_error, st.fails = datetime.now(UTC), None, 0

    def _guest(self, client, code: str, stay: str, poll: int) -> Guest:
        try:
            data = self._get(client, f"v1/properties/{code}/stays/{quote(stay)}/guests").get("guests", [])
        except HubError as e:
            if "403" in str(e) or "404" in str(e):
                return Guest(stay, None, None, poll)  # нет права guests: номер без имени
            raise
        # проживающий, не заказчик брони: заказчик может не жить в номере
        g = next((x for x in data if x.get("role") == "guest" and x.get("first_name")), None) or next(
            (x for x in data if x.get("role") == "guest" and x.get("last_name")), None
        )
        return Guest(stay, (g or {}).get("first_name"), (g or {}).get("last_name"), poll)

    def guest_for(self, room: Room | None, max_age_minutes: int = 10) -> dict | None:
        """Состояние гостя для приставки номера. None — нет подтверждённых данных."""
        if room is None:
            return None
        st = self.state(room.property_id)
        if st.last_ok_at is None or (datetime.now(UTC) - st.last_ok_at).total_seconds() > max_age_minutes * 60:
            return None
        if room.hub_status != "checked_in" or not room.stay_id:
            return {"status": room.hub_status or "free", "stay_id": None, "first_name": None,
                    "last_name": None, "planned_out": None, "confirmed_at": st.last_ok_at.isoformat()}
        g = st.guests.get(room.id)
        return {
            "status": "checked_in",
            "stay_id": room.stay_id,
            "first_name": g.first_name if g and g.stay_id == room.stay_id else None,
            "last_name": g.last_name if g and g.stay_id == room.stay_id else None,
            "planned_out": room.planned_out,
            "confirmed_at": st.last_ok_at.isoformat(),
        }

    # --- фоновый цикл ---

    def start(self) -> None:
        self._thread = threading.Thread(target=self._loop, name="hub-sync", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    def _loop(self) -> None:
        last: dict[int, float] = {}
        while not self._stop.wait(5):
            with self.db.sessions() as db:
                props = [(p.id, p.hub_poll_seconds) for p in db.scalars(select(Property))]
            now = datetime.now(UTC).timestamp()
            for pid, every in props:
                backoff = min(2 ** self.state(pid).fails, 8)  # хаб/интернет недоступны — реже, не долбим лимит
                if now - last.get(pid, 0) >= max(every, 15) * backoff:
                    last[pid] = now
                    try:
                        self.poll(pid)
                    except Exception:  # цикл не должен падать из-за одного отеля
                        log.exception("опрос хаба")
