"""Сквозная проверка с НАСТОЯЩИМ Hotel Hub (без подмены API). Запускается, только если хаб поднят:

    TENIR_REAL_HUB_URL=http://127.0.0.1:8100 TENIR_REAL_HUB_TOKEN=… TENIR_REAL_HUB_CODE=9305 pytest tests/test_real_hub.py

Токен — потребителя с правами occupancy и guests. Для шага «выезд» нужен ещё TENIR_REAL_HUB_DB — путь к sqlite-базе
ТЕСТОВОГО хаба (в боевую не указывать): тест переводит стоянку R1001 в checked_out. Данные для хаба — fixtures,
которыми его заполняют (вымышленные: стоянки R1001 «АЙГЕРИМ САПАРОВА» в 305, R1002 John Smith в 306).
"""
import os
import sqlite3

import pytest
from fastapi.testclient import TestClient

from tenircloud.app import create_app

URL = os.environ.get("TENIR_REAL_HUB_URL")
TOKEN = os.environ.get("TENIR_REAL_HUB_TOKEN")
CODE = os.environ.get("TENIR_REAL_HUB_CODE", "9305")
DB = os.environ.get("TENIR_REAL_HUB_DB")

pytestmark = pytest.mark.skipif(not (URL and TOKEN), reason="настоящий Hotel Hub не задан")


def test_cloud_against_real_hub(tmp_path):
    app = create_app(tmp_path, background=False, https_only=False)  # настоящий HTTP-клиент, без MockTransport
    admin = TestClient(app)
    admin.post("/setup", data={"login": "admin", "name": "A", "password": "very-secret-1"})
    r = admin.post("/settings", data={"code": CODE, "name": "TENIR", "timezone": "Asia/Almaty", "hub_url": URL,
                                      "hub_token": TOKEN, "hub_poll_seconds": "60"}, follow_redirects=False)
    assert r.status_code == 303

    # «Проверить Hotel Hub» из панели: номера пришли по API
    r = admin.post("/settings/1/check")
    assert "хаб доступен" in r.text, r.text[:400]
    rooms = admin.get("/rooms").text
    assert "заселён" in rooms and ">305<" in rooms

    # приставка в номере 305 получает гостя из хаба
    c = TestClient(app)
    d = c.post("/api/tv/v1/register", json={"model": "Box", "android": "11", "app_version": "0.2.1"}).json()
    c.headers["Authorization"] = "Bearer " + d["device_key"]
    from sqlalchemy import select

    from tenircloud.db import Room
    with app.state.db.sessions() as db:
        rid = db.scalar(select(Room).where(Room.label == "305")).id
    admin.post("/pair", data={"code": d["pairing_code"], "room_id": rid})
    hb = c.post("/api/tv/v1/heartbeat", json={"status": {}, "content_version": 0, "screen": "home"}).json()
    assert hb["guest"]["status"] == "checked_in" and hb["guest"]["stay_id"] == "R1001"
    assert hb["guest"]["first_name"] == "АЙГЕРИМ" and hb["guest"]["last_name"] == "САПАРОВА"  # не заказчик ТОО
    assert "phones" not in hb["guest"] and "emails" not in hb["guest"]

    # ожидаемый заезд: имена не запрашиваются и не отдаются
    with app.state.db.sessions() as db:
        rid2 = db.scalar(select(Room).where(Room.label == "202")).id
    c2 = TestClient(app)
    d2 = c2.post("/api/tv/v1/register", json={"model": "Box2"}).json()
    c2.headers["Authorization"] = "Bearer " + d2["device_key"]
    admin.post("/pair", data={"code": d2["pairing_code"], "room_id": rid2})
    g2 = c2.post("/api/tv/v1/heartbeat", json={"status": {}}).json()["guest"]
    assert g2["status"] == "booked" and g2["first_name"] is None

    if DB:  # выезд в хабе → после опроса приставка получает «свободен», имя стёрто
        with sqlite3.connect(DB) as cx:
            cx.execute("update room_stays set status='checked_out', actual_out=datetime('now') "
                       "where external_id='R1001'")
        app.state.hub.poll(1)
        g = c.post("/api/tv/v1/heartbeat", json={"status": {}}).json()["guest"]
        assert g["status"] == "free" and g["first_name"] is None and g["stay_id"] is None
        assert "АЙГЕРИМ" not in admin.get("/audit").text
