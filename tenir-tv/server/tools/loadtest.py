#!/usr/bin/env python3
"""Нагрузочная проверка локального сервера: N «приставок» регистрируются, подключаются к номерам и шлют heartbeat.

    python tools/loadtest.py --url http://192.168.1.10:8090 --login admin --password '…' --devices 200 --rounds 5

Создаёт в панели отель LOADTEST (код 9999) и номера L1…LN; после проверки удалите их или всю базу.
Пароль можно передать переменной TENIR_CLOUD_PASSWORD.
"""
import argparse
import os
import re
import statistics
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import httpx


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--login", default="admin")
    ap.add_argument("--password", default=os.environ.get("TENIR_CLOUD_PASSWORD", ""))
    ap.add_argument("--devices", type=int, default=100)
    ap.add_argument("--rounds", type=int, default=3)
    ap.add_argument("--interval", type=float, default=0.0, help="пауза между кругами, с (в жизни — 60)")
    a = ap.parse_args()
    base = a.url.rstrip("/")

    admin = httpx.Client(base_url=base, follow_redirects=False, timeout=15)
    r = admin.post("/login", data={"login": a.login, "password": a.password})
    if r.status_code != 303:
        print("вход в панель не удался:", r.status_code)
        return 2
    admin.post("/settings", data={"code": "9999", "name": "LOADTEST", "timezone": "Asia/Almaty", "hub_poll_seconds": "60"})
    for i in range(1, a.devices + 1):
        admin.post("/rooms", data={"label": f"L{i}"})
    page = admin.get("/devices").text
    room_ids = {label: rid for rid, label in re.findall(r'<option value="(\d+)">(L\d+)</option>', page)}

    lat: list[float] = []
    errors: list[str] = []

    def register(i: int):
        c = httpx.Client(base_url=base, timeout=15, headers={"X-Forwarded-For": f"10.0.{i // 250}.{i % 250 + 1}"})
        r = c.post("/api/tv/v1/register", json={"model": f"LoadBox {i}", "android": "11", "app_version": "0.2.0"})
        if r.status_code != 201:
            errors.append(f"register {r.status_code}")
            return None
        d = r.json()
        c.headers["Authorization"] = "Bearer " + d["device_key"]
        return c, d["pairing_code"], i

    # регистрация: лимит сервиса — 10 в минуту с одного IP, поэтому на нагрузочном тесте он будет срабатывать
    with ThreadPoolExecutor(8) as ex:
        devs = [d for d in ex.map(register, range(1, a.devices + 1)) if d]
    if len(devs) < a.devices:
        print(f"зарегистрировано {len(devs)} из {a.devices}: сработал лимит регистраций (10/мин с одного IP) — это норма")
    for c, code, i in devs:
        admin.post("/pair", data={"code": code, "room_id": room_ids[f"L{i}"]})

    body = {"status": {"app_version": "0.2.0", "model": "LoadBox", "network": {"type": "ethernet", "internet": True},
                       "display": {"width": 3840, "height": 2160, "uhd": True}, "memory": {"total_mb": 3900, "avail_mb": 1500},
                       "codecs": {"HEVC": "hw, 4K@30 — да", "VP9": "hw, 4K@30 — да"}, "default_home": True},
            "content_version": 0, "screen": "home"}

    def beat(dev):
        c, _, _ = dev
        t = time.perf_counter()
        try:
            r = c.post("/api/tv/v1/heartbeat", json=body)
            lat.append((time.perf_counter() - t) * 1000)
            if r.status_code != 200:
                errors.append(f"heartbeat {r.status_code}")
            r2 = c.get("/api/tv/v1/content")
            if r2.status_code != 200:
                errors.append(f"content {r2.status_code}")
        except httpx.HTTPError as e:
            errors.append(type(e).__name__)

    t0 = time.perf_counter()
    for n in range(a.rounds):
        with ThreadPoolExecutor(32) as ex:
            list(ex.map(beat, devs))
        if a.interval and n < a.rounds - 1:
            time.sleep(a.interval)
    total = time.perf_counter() - t0
    if lat:
        lat.sort()
        print(f"приставок: {len(devs)}, heartbeat: {len(lat)}, ошибок: {len(errors)}, время: {total:.1f} с, "
              f"{len(lat) / total:.0f} heartbeat/с")
        print(f"задержка heartbeat, мс: p50 {statistics.median(lat):.0f}, p95 {lat[int(len(lat) * .95) - 1]:.0f}, макс {lat[-1]:.0f}")
    if errors:
        print("ошибки:", {e: errors.count(e) for e in set(errors)})
    online = re.search(r"(\d+) / (\d+)\s*<small>приставок онлайн", admin.get("/").text)
    print("дашборд «онлайн»:", online.group(0).split("<")[0].strip() if online else "не найдено")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
