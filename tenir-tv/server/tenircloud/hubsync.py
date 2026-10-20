"""Синхронизация с Hotel Hub (API v1, только чтение): номера и гости по заездам.

Один опрос на отель вместо опроса от каждой приставки. Имена гостей хранятся только в памяти процесса
и только для заселённых (checked_in) номеров; при выезде стираются.
"""

import logging
import threading
from dataclasses import dataclass, field
from datetime import UTC, datetime
from urllib.parse import quote

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
    rooms_total: int = 0
    checked_in: int = 0
    guests: dict[int, Guest] = field(default_factory=dict)  # room.id → гость


class HubError(Exception):
    pass


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
        return httpx.Client(
            base_url=prop.hub_url.rstrip("/") + "/",
            headers={"Authorization": f"Bearer {token}", "Accept": "application/json"},
            timeout=10.0,
            transport=self.transport,
        )

    @staticmethod
    def _get(client: httpx.Client, path: str) -> dict:
        try:
            r = client.get(path)
        except httpx.HTTPError as e:
            raise HubError(f"Hotel Hub недоступен: {e.__class__.__name__}") from e
        if r.status_code == 401:
            raise HubError("токен не принят (401)")
        if r.status_code == 403:
            raise HubError(f"у токена нет права для {path.split('?')[0]} (403)")
        if r.status_code == 429:
            raise HubError("лимит запросов хаба (429)")
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
        try:
            with self._client(prop) as c:
                rooms = self._get(c, f"v1/properties/{quote(prop.code)}/rooms").get("rooms", [])
        except HubError as e:
            return str(e)
        busy = sum(1 for r in rooms if r.get("status") == "checked_in")
        return f"хаб доступен, номеров: {len(rooms)}, заселено: {busy}"

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
        st.last_ok_at, st.last_error = datetime.now(UTC), None

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
                if now - last.get(pid, 0) >= max(every, 15):
                    last[pid] = now
                    try:
                        self.poll(pid)
                    except Exception:  # цикл не должен падать из-за одного отеля
                        log.exception("опрос хаба")
