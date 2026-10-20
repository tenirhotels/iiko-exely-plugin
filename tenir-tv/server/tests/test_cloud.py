import io
import json
import re

import httpx
import pytest
from fastapi.testclient import TestClient
from PIL import Image
from sqlalchemy import select

from tenircloud.app import create_app
from tenircloud.db import Device, Property, Room


class FakeHub:
    """Hotel Hub API v1: rooms + guests; можно «уронить»."""

    def __init__(self):
        self.rooms = []
        self.guests = {}
        self.down = False
        self.code = 200
        self.calls = []

    def room(self, label, status, stay=None, out="2099-01-01T12:00:00"):
        self.rooms = [r for r in self.rooms if r["room"] != label]
        self.rooms.append({"room": label, "room_id": "8" + label, "status": status, "stay_id": stay,
                           "planned_in": "2026-10-18T14:00:00", "planned_out": out if stay else None})

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.calls.append((request.url.path, request.headers.get("authorization")))
        if self.down:
            raise httpx.ConnectError("refused")
        if self.code != 200:
            return httpx.Response(self.code, json={"error": "x"})
        path = request.url.path
        if path.endswith("/rooms"):
            return httpx.Response(200, json={"rooms": self.rooms})
        m = re.search(r"/stays/([^/]+)/guests$", path)
        if m:
            return httpx.Response(200, json={"guests": self.guests.get(m.group(1), [])})
        return httpx.Response(404, json={"error": "нет"})


@pytest.fixture
def hub():
    return FakeHub()


@pytest.fixture
def app(tmp_path, hub):
    return create_app(tmp_path, hub_transport=httpx.MockTransport(hub.handler), background=False,
                      https_only=False, public_url="http://tv.test")


@pytest.fixture
def admin(app):
    c = TestClient(app)
    r = c.post("/setup", data={"login": "admin", "name": "Админ", "password": "very-secret-1"})
    assert r.status_code in (200, 303)
    c.post("/settings", data={"code": "9305", "name": "TENIR", "timezone": "Asia/Almaty",
                              "hub_url": "http://hub.test", "hub_token": "hub-secret", "hub_poll_seconds": "60",
                              "service_pin": "4826"})
    return c


def tv(app):
    c = TestClient(app)
    r = c.post("/api/tv/v1/register", json={"model": "X96 Max", "android": "11", "app_version": "0.2.0"})
    assert r.status_code == 201
    d = r.json()
    c.headers["Authorization"] = f"Bearer {d['device_key']}"
    return c, d


def prop(app):
    with app.state.db.sessions() as db:
        return db.scalar(select(Property))


def pair(admin, app, code, label="305"):
    with app.state.db.sessions() as db:
        p = db.scalar(select(Property))
        room = db.scalar(select(Room).where(Room.label == label))
        if room is None:
            room = Room(property_id=p.id, label=label)
            db.add(room)
            db.commit()
        rid = room.id
    r = admin.post("/pair", data={"code": code, "room_id": rid}, follow_redirects=False)
    assert r.status_code == 303 and "/devices/" in r.headers["location"], r.headers.get("location")
    return rid


def hb(c, **status):
    r = c.post("/api/tv/v1/heartbeat", json={"status": status, "content_version": 0, "screen": "home"})
    assert r.status_code == 200, r.text
    return r.json()


# --- приставки ---

def test_register_pair_and_heartbeat(app, admin):
    c, d = tv(app)
    r = hb(c)
    assert r["paired"] is False and r["pairing_code"] == d["pairing_code"]
    assert r["content"]["version"] == 0 and "guest" not in r

    pair(admin, app, d["pairing_code"])
    r = hb(c, network={"type": "ethernet", "internet": True}, display={"width": 3840, "height": 2160, "uhd": True},
           app_version="0.2.0", model="X96 Max")
    assert r["paired"] is True and r["room"] == "305"
    assert r["config"]["servicePin"] == "4826"
    assert r["property"]["code"] == "9305"
    assert r["guest"] is None  # хаб ещё не опрашивался: персональных данных нет
    with app.state.db.sessions() as db:
        dev = db.scalar(select(Device))
        assert dev.status["display"]["uhd"] is True and dev.pairing_code is None


def test_wrong_key_and_wrong_code(app, admin):
    c = TestClient(app)
    assert c.post("/api/tv/v1/heartbeat", json={}, headers={"Authorization": "Bearer nope"}).status_code == 401
    r = admin.post("/pair", data={"code": "000000", "room_id": "1"}, follow_redirects=False)
    assert r.headers["location"] == "/devices"


def test_guest_from_hub_and_checkout(app, admin, hub):
    hub.room("305", "checked_in", "S1")
    hub.room("101", "booked", "S9")
    hub.guests["S1"] = [{"role": "customer", "first_name": "ТОО", "last_name": "Ромашка"},
                        {"role": "guest", "first_name": "АЙГЕРИМ", "last_name": "САПАРОВА", "phones": ["+7"]}]
    hub.guests["S9"] = [{"role": "guest", "first_name": "Иван", "last_name": "Иванов"}]
    p = prop(app)
    app.state.hub.poll(p.id)
    assert ("/v1/properties/9305/rooms", "Bearer hub-secret") in hub.calls
    assert not any("S9" in path for path, _ in hub.calls)  # имена ожидаемых гостей не запрашиваются

    c, d = tv(app)
    pair(admin, app, d["pairing_code"], "305")
    g = hb(c)["guest"]
    assert g["status"] == "checked_in" and g["stay_id"] == "S1"
    assert g["first_name"] == "АЙГЕРИМ" and "phones" not in g

    # в базе сервиса имён нет
    import glob
    raw = b""
    for name in glob.glob(app.state.db.engine.url.database + "*"):  # cloud.db, -wal, -shm
        with open(name, "rb") as f:
            raw += f.read()
    assert "АЙГЕРИМ".encode() not in raw and "САПАРОВА".encode() not in raw

    hub.room("305", "free")
    app.state.hub.poll(p.id)
    g = hb(c)["guest"]
    assert g["status"] == "free" and g["first_name"] is None and g["stay_id"] is None
    assert 1 not in app.state.hub.state(p.id).guests

    r = admin.get("/audit")
    assert "guest.checkout" in r.text and "АЙГЕРИМ" not in r.text


def test_hub_down_hides_guest(app, admin, hub):
    hub.room("305", "checked_in", "S1")
    hub.guests["S1"] = [{"role": "guest", "first_name": "Айгерим", "last_name": "С"}]
    p = prop(app)
    app.state.hub.poll(p.id)
    c, d = tv(app)
    pair(admin, app, d["pairing_code"])
    assert hb(c)["guest"]["first_name"] == "Айгерим"

    hub.down = True
    st = app.state.hub.poll(p.id)
    assert "недоступен" in st.last_error
    # подтверждение старше maxAgeMinutes → данных нет
    from datetime import timedelta
    st.last_ok_at -= timedelta(minutes=11)
    assert hb(c)["guest"] is None
    assert "недоступен" in admin.get("/").text


def test_revoked_token_drops_names(app, hub):
    hub.room("305", "checked_in", "S1")
    hub.guests["S1"] = [{"role": "guest", "first_name": "А", "last_name": "Б"}]
    pid = None
    with app.state.db.sessions() as db:
        p = Property(code="9305", name="T", hub_url="http://hub.test", hub_token_enc=app.state.secrets.encrypt("t"))
        db.add(p)
        db.commit()
        pid = p.id
    app.state.hub.poll(pid)
    assert app.state.hub.state(pid).guests
    hub.code = 401
    app.state.hub.poll(pid)
    assert not app.state.hub.state(pid).guests


# --- контент ---

def test_publish_etag_and_device_content(app, admin):
    c, d = tv(app)
    pair(admin, app, d["pairing_code"])
    r = c.get("/api/tv/v1/content")
    assert r.status_code == 200 and r.json()["hotel"]["name"] == "TENIR" and r.json()["version"] == 0

    form = {"name": "TENIR Shymbulak", "tagline.ru": "3200 м", "welcome.ru": "Рады видеть", "accentColor": "#112233",
            "logo": "", "background": "/media/abc.jpg", "guestNameFormat": "full", "language": "ru",
            "bannerIntervalSeconds": "7", "headlineFont": "sans", "backgroundDim": "60", "showGuestName": "on",
            "tile_restaurant": "1", "tile_netflix": "2", "tile_info": "3"}
    assert admin.post("/design", data=form, follow_redirects=False).status_code == 303
    # до публикации приставка видит старое
    assert c.get("/api/tv/v1/content").json()["hotel"]["name"] == "TENIR"
    admin.post("/content/publish", data={"note": "новый дизайн"})
    r = c.get("/api/tv/v1/content")
    doc = r.json()
    assert doc["version"] == 1 and doc["hotel"]["name"] == "TENIR Shymbulak"
    assert doc["hotel"]["background"] == "http://tv.test/media/abc.jpg"  # абсолютный адрес для приставки
    assert doc["home"]["tiles"] == ["restaurant", "netflix", "info"]
    assert doc["display"]["guestNameFormat"] == "full" and doc["display"]["timezone"] == "Asia/Almaty"
    tag = r.headers["etag"]
    assert c.get("/api/tv/v1/content", headers={"If-None-Match": tag}).status_code == 304
    assert hb(c)["content"]["version"] == 1

    admin.post("/versions/1/rollback")
    assert c.get("/api/tv/v1/content").json()["version"] == 2


def test_invalid_content_rejected(app, admin):
    r = admin.post("/content/json", data={"json": '{"hotel": {"name": ""}}'})
    assert "hotel.name" in r.text
    r = admin.post("/content/json", data={"json": "{oops"})
    assert "не JSON" in r.text


def test_banner_news_app_restaurant_editors(app, admin):
    admin.post("/content/banners/new", data={"id": "spa2", "title.ru": "Спа", "title.en": "Spa", "action": "news",
                                             "target": "sauna", "image": ""})
    admin.post("/content/news/new", data={"id": "n1", "title.ru": "Каток открыт", "date": "2026-12-01"})
    admin.post("/content/apps/new", data={"id": "ivi", "title": "Иви", "packages": "ru.ivi.client\nru.ivi.tv",
                                          "color": "#ff0055"})
    menu = [{"id": "c1", "name": {"ru": "Супы"}, "items": [{"id": "i1", "name": {"ru": "Сорпа"}, "price": 2500}]}]
    admin.post("/content/restaurant", data={"rname.ru": "Tenir", "menu_json": json.dumps(menu), "currency": "₸"})
    admin.post("/content/banners/spa2", data={"move": "up"})
    admin.post("/content/publish", data={})
    with app.state.db.sessions() as db:
        from tenircloud import content
        doc = content.latest_doc(db, db.scalar(select(Property)))
    assert doc["banners"][-2]["id"] == "spa2"
    assert doc["banners"][-2]["action"] == {"type": "news", "target": "sauna"}
    assert any(n["id"] == "n1" for n in doc["news"])
    assert {"id": "ivi", "title": "Иви", "packages": ["ru.ivi.client", "ru.ivi.tv"], "color": "#FF0055"} in doc["apps"]
    assert doc["restaurant"]["categories"][0]["items"][0]["price"] == 2500
    r = admin.post("/content/news/new", data={"id": "n1", "title.ru": "Дубль"})
    assert "уже занят" in r.text


def test_group_overrides(app, admin):
    c, d = tv(app)
    rid = pair(admin, app, d["pairing_code"], "501")
    admin.post("/groups", data={"name": "Молодожёны"})
    with app.state.db.sessions() as db:
        from tenircloud.db import Group
        gid = db.scalar(select(Group)).id
    admin.post(f"/groups/{gid}", data={"name": "Молодожёны", "welcome.ru": "Поздравляем!", "banners": ["sunset"],
                                       "useTiles": "on", "tile_restaurant": "1", "tile_info": "2"})
    admin.post("/rooms/groups", data={f"g{rid}": str(gid)})
    admin.post("/content/publish", data={})
    doc = c.get("/api/tv/v1/content").json()
    assert doc["hotel"]["welcome"] == {"ru": "Поздравляем!"}
    assert [b["id"] for b in doc["banners"]] == ["sunset"]
    assert doc["home"]["tiles"] == ["restaurant", "info"]
    # другой номер — без переопределений
    c2, d2 = tv(app)
    pair(admin, app, d2["pairing_code"], "502")
    assert len(c2.get("/api/tv/v1/content").json()["banners"]) == 3


def test_messages_and_commands(app, admin):
    c, d = tv(app)
    rid = pair(admin, app, d["pairing_code"], "305")
    c2, d2 = tv(app)
    pair(admin, app, d2["pairing_code"], "306")
    admin.post("/messages", data={"text.ru": "Бассейн закрыт", "text.en": "Pool closed", "target": "all", "hours": "2"})
    admin.post("/messages", data={"text.ru": "Ваш трансфер в 9:00", "target": f"room:{rid}", "hours": "0"})
    r1, r2 = hb(c), hb(c2)
    assert {m["text"]["ru"] for m in r1["messages"]} == {"Бассейн закрыт", "Ваш трансфер в 9:00"}
    assert [m["text"]["ru"] for m in r2["messages"]] == ["Бассейн закрыт"]

    with app.state.db.sessions() as db:
        did = db.scalar(select(Device).where(Device.uid == d["device_id"])).id
    admin.post(f"/devices/{did}/command", data={"kind": "reload_content"})
    cmds = hb(c)["commands"]
    assert [x["kind"] for x in cmds] == ["reload_content"]
    assert hb(c)["commands"] == []  # доставляется один раз
    assert c.post(f"/api/tv/v1/commands/{cmds[0]['id']}/ack", json={"ok": True, "result": "кеш сброшен"}).json()["ok"]
    assert c2.post(f"/api/tv/v1/commands/{cmds[0]['id']}/ack", json={}).status_code == 404  # чужая команда
    assert "выполнено: кеш сброшен" in admin.get(f"/devices/{did}").text
    assert admin.post(f"/devices/{did}/command", data={"kind": "rm -rf"}).status_code == 400


def test_unpair_shows_new_code(app, admin):
    c, d = tv(app)
    pair(admin, app, d["pairing_code"])
    with app.state.db.sessions() as db:
        did = db.scalar(select(Device)).id
    admin.post(f"/devices/{did}/unpair")
    r = hb(c)
    assert r["paired"] is False and len(r["pairing_code"]) == 6


def test_media_upload(app, admin):
    buf = io.BytesIO()
    Image.new("RGB", (4000, 2000), (200, 150, 90)).save(buf, "JPEG")
    r = admin.post("/library", files={"files": ("bg.jpg", buf.getvalue(), "image/jpeg")})
    assert "Загружено: 1" in r.text
    from tenircloud.db import Media
    with app.state.db.sessions() as db:
        m = db.scalar(select(Media))
    assert m.width == 3840  # уменьшено до 4K
    r = TestClient(app).get(f"/media/{m.filename}")
    assert r.status_code == 200 and "immutable" in r.headers["cache-control"]
    r = admin.post("/library", files={"files": ("x.jpg", b"not an image", "image/jpeg")})
    assert "не изображение" in r.text


# --- панель: доступ ---

def test_all_pages_render(app, admin, hub):
    hub.room("305", "checked_in", "S1")
    app.state.hub.poll(prop(app).id)
    _, d = tv(app)
    pair(admin, app, d["pairing_code"])
    admin.post("/groups", data={"name": "VIP"})
    for url in ["/", "/devices", "/devices/1", "/rooms", "/groups", "/groups/1", "/design", "/preview",
                "/preview?lang=en&guest=0", "/content", "/content/banners/new", "/content/banners/sunset",
                "/content/news/skipass", "/content/apps/netflix", "/content/json", "/messages", "/library",
                "/settings", "/users", "/audit", "/static/style.css", "/static/demo/background.jpg"]:
        r = admin.get(url)
        assert r.status_code == 200, (url, r.status_code, r.text[:300])
    assert "hub-secret" not in admin.get("/settings").text  # токен хаба не выводится


def test_roles_and_login(app, admin):
    admin.post("/users", data={"login": "viewer1", "name": "Смотрящий", "role": "viewer", "password": "viewer-pass-1"})
    v = TestClient(app)
    assert v.get("/", follow_redirects=False).headers["location"] == "/login"
    assert v.post("/login", data={"login": "viewer1", "password": "wrong"}).status_code == 401
    v.post("/login", data={"login": "viewer1", "password": "viewer-pass-1"})
    assert v.get("/devices").status_code == 200
    assert v.post("/content/publish", data={}).status_code == 403
    assert v.get("/users").status_code == 403
    # CSRF: POST с чужого сайта
    assert admin.post("/content/publish", data={}, headers={"Origin": "https://evil.example"}).status_code == 403


def test_tv_cannot_read_other_rooms(app, admin, hub):
    hub.room("305", "checked_in", "S1")
    hub.room("306", "checked_in", "S2")
    hub.guests["S1"] = [{"role": "guest", "first_name": "Один", "last_name": "А"}]
    hub.guests["S2"] = [{"role": "guest", "first_name": "Второй", "last_name": "Б"}]
    app.state.hub.poll(prop(app).id)
    c, d = tv(app)
    pair(admin, app, d["pairing_code"], "305")
    body = json.dumps(hb(c), ensure_ascii=False)
    assert "Один" in body and "Второй" not in body and "hub-secret" not in body
