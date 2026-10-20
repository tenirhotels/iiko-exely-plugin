"""Запуск: python -m tenircloud serve [--host 0.0.0.0 --port 8090]; сброс пароля: python -m tenircloud passwd ЛОГИН."""

import argparse
import getpass
import os
import sys


def _lan_addresses() -> list[str]:
    import socket

    out = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127.") and ip not in out:
                out.append(ip)
    except OSError:
        pass
    try:  # адрес «по умолчанию»: интерфейс, которым уходит трафик в локальную сеть
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("10.255.255.255", 1))
            ip = sock.getsockname()[0]
            if ip not in out and not ip.startswith("127."):
                out.insert(0, ip)
    except OSError:
        pass
    return out


def main() -> None:
    ap = argparse.ArgumentParser(prog="tenircloud", description="TENIR TV Cloud")
    ap.add_argument("--data", default=os.environ.get("TENIR_CLOUD_DATA", "data"), help="каталог данных")
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("serve", help="запустить сервис")
    s.add_argument("--host", default="0.0.0.0", help="по умолчанию — вся локальная сеть (приставки должны достучаться)")
    s.add_argument("--port", type=int, default=8090)
    h = sub.add_parser("hubcheck", help="проверить связь с Hotel Hub с этого сервера (имён гостей не печатает)")
    h.add_argument("--url", required=True, help="например https://hub.example.com")
    h.add_argument("--code", required=True, help="код отеля в хабе, например 9305")
    h.add_argument("--token-env", default="HUB_TOKEN", help="переменная окружения с токеном (по умолчанию HUB_TOKEN)")
    p = sub.add_parser("passwd", help="задать пароль пользователю (и включить его)")
    p.add_argument("login")
    a = ap.parse_args()

    if a.cmd == "serve":
        import uvicorn

        from .app import create_app

        for ip in _lan_addresses():
            print(f"  панель и адрес для приставок: http://{ip}:{a.port}")
        # X-Forwarded-* принимаются только от локального прокси (nginx/Caddy на этой же машине)
        uvicorn.run(create_app(a.data), host=a.host, port=a.port, proxy_headers=True, forwarded_allow_ips="127.0.0.1")
    elif a.cmd == "hubcheck":
        from .hubsync import probe

        token = os.environ.get(a.token_env, "")
        if not token:
            sys.exit(f"задайте токен: {a.token_env}='…' tenircloud hubcheck --url … --code …")
        results = probe(a.url, a.code, token)
        for ok, msg in results:
            print(("✓ " if ok else "✕ ") + msg)
        sys.exit(0 if results and all(ok for ok, _ in results) else 1)
    elif a.cmd == "passwd":
        from sqlalchemy import select

        from .db import Database, User, audit
        from .security import hash_password

        db = Database(os.path.join(a.data, "cloud.db"))
        pw = getpass.getpass("Новый пароль (от 10 символов): ")
        if len(pw) < 10:
            sys.exit("пароль короче 10 символов")
        with db.sessions() as s:
            u = s.scalar(select(User).where(User.login == a.login))
            if u is None:
                sys.exit("нет такого пользователя")
            u.password_hash, u.active = hash_password(pw), True
            audit(s, "cli", "user.passwd", a.login)
            s.commit()
        print("готово")


if __name__ == "__main__":
    main()
