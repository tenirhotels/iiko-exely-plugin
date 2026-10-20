"""Сборка приложения FastAPI: API приставок, панель администратора, медиафайлы, фоновый опрос хаба."""

import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from sqlalchemy import text
from starlette.middleware.sessions import SessionMiddleware

from . import __version__, admin, media, tvapi
from .db import Database
from .hubsync import HubSync
from .security import Secrets


def create_app(data_dir: str | Path | None = None, *, hub_transport=None, background: bool = True,
               public_url: str | None = None, https_only: bool | None = None) -> FastAPI:
    data = Path(data_dir or os.environ.get("TENIR_CLOUD_DATA", "data")).resolve()
    data.mkdir(parents=True, exist_ok=True)
    (data / "media").mkdir(exist_ok=True)
    secrets = Secrets(data)
    database = Database(data / "cloud.db")
    hub = HubSync(database, secrets, transport=hub_transport)

    @asynccontextmanager
    async def lifespan(_):
        if background:
            hub.start()
        yield
        hub.stop()

    app = FastAPI(title="TENIR TV Cloud", lifespan=lifespan, docs_url=None, redoc_url=None)
    app.state.db = database
    app.state.secrets = secrets
    app.state.hub = hub
    app.state.media_dir = data / "media"
    # регистраций в минуту с одного адреса; за NAT (много ТВ за одним IP) лимит можно поднять
    app.state.register_limit = int(os.environ.get("TENIR_CLOUD_REGISTER_LIMIT", "10"))
    # адрес, по которому приставки видят сервис (для ссылок на изображения); без него — из запроса
    app.state.public_url = (public_url or os.environ.get("TENIR_CLOUD_PUBLIC_URL", "")).rstrip("/") or None
    secure = https_only if https_only is not None else os.environ.get("TENIR_CLOUD_HTTPS", "0") == "1"
    app.add_middleware(SessionMiddleware, secret_key=secrets.session_secret, same_site="lax",
                       https_only=secure, max_age=12 * 3600, session_cookie="tenircloud")

    @app.get("/healthz")
    def healthz():
        """Для мониторинга и проверок с приставки/ноутбука: сервис жив, база открыта."""
        with database.sessions() as db:
            db.execute(text("select 1"))
        return {"ok": True, "version": __version__}

    app.include_router(tvapi.router)
    app.include_router(media.router)
    app.include_router(admin.router)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    return app
