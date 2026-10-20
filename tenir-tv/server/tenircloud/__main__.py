"""Запуск: python -m tenircloud serve [--host 0.0.0.0 --port 8090]; сброс пароля: python -m tenircloud passwd ЛОГИН."""

import argparse
import getpass
import os
import sys


def main() -> None:
    ap = argparse.ArgumentParser(prog="tenircloud", description="TENIR TV Cloud")
    ap.add_argument("--data", default=os.environ.get("TENIR_CLOUD_DATA", "data"), help="каталог данных")
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("serve", help="запустить сервис")
    s.add_argument("--host", default="127.0.0.1")
    s.add_argument("--port", type=int, default=8090)
    p = sub.add_parser("passwd", help="задать пароль пользователю (и включить его)")
    p.add_argument("login")
    a = ap.parse_args()

    if a.cmd == "serve":
        import uvicorn

        from .app import create_app

        uvicorn.run(create_app(a.data), host=a.host, port=a.port, proxy_headers=True, forwarded_allow_ips="*")
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
