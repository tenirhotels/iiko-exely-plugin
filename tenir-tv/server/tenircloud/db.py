"""База TENIR TV Cloud (SQLite). Имена гостей в базу не пишутся — они живут только в памяти (hubsync)."""

import json
from datetime import UTC, datetime
from pathlib import Path

from sqlalchemy import (
    Boolean,
    DateTime,
    ForeignKey,
    Integer,
    String,
    Text,
    create_engine,
    event,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship, sessionmaker


def utcnow() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"
    id: Mapped[int] = mapped_column(primary_key=True)
    login: Mapped[str] = mapped_column(String(64), unique=True)
    name: Mapped[str] = mapped_column(String(120), default="")
    password_hash: Mapped[str] = mapped_column(String(200))
    # admin — всё; editor — контент, дизайн, сообщения, команды; viewer — только просмотр
    role: Mapped[str] = mapped_column(String(16), default="editor")
    active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    last_login_at: Mapped[datetime | None] = mapped_column(DateTime)


class Property(Base):
    """Отель. Код совпадает с кодом отеля в Hotel Hub (например 9305)."""

    __tablename__ = "properties"
    id: Mapped[int] = mapped_column(primary_key=True)
    code: Mapped[str] = mapped_column(String(32), unique=True)
    name: Mapped[str] = mapped_column(String(120))
    timezone: Mapped[str] = mapped_column(String(64), default="Asia/Almaty")
    hub_url: Mapped[str] = mapped_column(String(300), default="")
    hub_token_enc: Mapped[str] = mapped_column(Text, default="")
    hub_poll_seconds: Mapped[int] = mapped_column(Integer, default=60)
    # настройки приставок: PIN сервисного меню, язык по умолчанию
    service_pin: Mapped[str] = mapped_column(String(16), default="0000")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class Group(Base):
    """Группа номеров (этаж, категория, «молодожёны»): свои баннеры, приветствие, плитки, акцент."""

    __tablename__ = "groups"
    id: Mapped[int] = mapped_column(primary_key=True)
    property_id: Mapped[int] = mapped_column(ForeignKey("properties.id", ondelete="CASCADE"))
    name: Mapped[str] = mapped_column(String(120))
    overrides_json: Mapped[str] = mapped_column(Text, default="{}")

    @property
    def overrides(self) -> dict:
        try:
            return json.loads(self.overrides_json or "{}")
        except ValueError:
            return {}


class Room(Base):
    """Номер. Список приходит из Hotel Hub; без хаба номера заводятся вручную."""

    __tablename__ = "rooms"
    id: Mapped[int] = mapped_column(primary_key=True)
    property_id: Mapped[int] = mapped_column(ForeignKey("properties.id", ondelete="CASCADE"))
    label: Mapped[str] = mapped_column(String(32))
    pms_room_id: Mapped[str | None] = mapped_column(String(64))
    group_id: Mapped[int | None] = mapped_column(ForeignKey("groups.id", ondelete="SET NULL"))
    from_hub: Mapped[bool] = mapped_column(Boolean, default=False)
    # состояние из хаба (без имён): free | booked | checked_in | unknown
    hub_status: Mapped[str] = mapped_column(String(16), default="unknown")
    stay_id: Mapped[str | None] = mapped_column(String(64))
    planned_out: Mapped[str | None] = mapped_column(String(32))
    hub_seen_at: Mapped[datetime | None] = mapped_column(DateTime)
    group: Mapped[Group | None] = relationship()


class Device(Base):
    __tablename__ = "devices"
    id: Mapped[int] = mapped_column(primary_key=True)
    uid: Mapped[str] = mapped_column(String(32), unique=True)  # публичный id в API
    key_hash: Mapped[str] = mapped_column(String(128))
    pairing_code: Mapped[str | None] = mapped_column(String(8), index=True)
    property_id: Mapped[int | None] = mapped_column(ForeignKey("properties.id", ondelete="SET NULL"))
    room_id: Mapped[int | None] = mapped_column(ForeignKey("rooms.id", ondelete="SET NULL"))
    name: Mapped[str] = mapped_column(String(120), default="")
    language: Mapped[str] = mapped_column(String(8), default="")  # пусто — из контента
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime)
    last_ip: Mapped[str] = mapped_column(String(64), default="")
    app_version: Mapped[str] = mapped_column(String(32), default="")
    model: Mapped[str] = mapped_column(String(120), default="")
    android: Mapped[str] = mapped_column(String(32), default="")
    status_json: Mapped[str] = mapped_column(Text, default="{}")
    room: Mapped[Room | None] = relationship()

    @property
    def status(self) -> dict:
        try:
            return json.loads(self.status_json or "{}")
        except ValueError:
            return {}


class ContentDraft(Base):
    __tablename__ = "content_drafts"
    property_id: Mapped[int] = mapped_column(ForeignKey("properties.id", ondelete="CASCADE"), primary_key=True)
    json: Mapped[str] = mapped_column(Text)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    updated_by: Mapped[str] = mapped_column(String(64), default="")


class ContentVersion(Base):
    """Опубликованные версии: приставки получают последнюю, откат — публикация старой как новой."""

    __tablename__ = "content_versions"
    id: Mapped[int] = mapped_column(primary_key=True)
    property_id: Mapped[int] = mapped_column(ForeignKey("properties.id", ondelete="CASCADE"), index=True)
    version: Mapped[int] = mapped_column(Integer)
    json: Mapped[str] = mapped_column(Text)
    note: Mapped[str] = mapped_column(String(200), default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    created_by: Mapped[str] = mapped_column(String(64), default="")


class Message(Base):
    """Сообщение на экран: всем, группе, номеру или одной приставке."""

    __tablename__ = "messages"
    id: Mapped[int] = mapped_column(primary_key=True)
    property_id: Mapped[int] = mapped_column(ForeignKey("properties.id", ondelete="CASCADE"))
    target: Mapped[str] = mapped_column(String(16), default="all")  # all | group | room | device
    target_id: Mapped[int | None] = mapped_column(Integer)
    text: Mapped[str] = mapped_column(Text)
    starts_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    expires_at: Mapped[datetime | None] = mapped_column(DateTime)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    created_by: Mapped[str] = mapped_column(String(64), default="")


class Command(Base):
    """Команда приставке. Доставляется в ответе на heartbeat, приставка подтверждает выполнение."""

    __tablename__ = "commands"
    id: Mapped[int] = mapped_column(primary_key=True)
    device_id: Mapped[int] = mapped_column(ForeignKey("devices.id", ondelete="CASCADE"), index=True)
    kind: Mapped[str] = mapped_column(String(32))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    created_by: Mapped[str] = mapped_column(String(64), default="")
    delivered_at: Mapped[datetime | None] = mapped_column(DateTime)
    done_at: Mapped[datetime | None] = mapped_column(DateTime)
    result: Mapped[str] = mapped_column(String(300), default="")


class Media(Base):
    __tablename__ = "media"
    id: Mapped[int] = mapped_column(primary_key=True)
    property_id: Mapped[int] = mapped_column(ForeignKey("properties.id", ondelete="CASCADE"))
    filename: Mapped[str] = mapped_column(String(80), unique=True)
    original_name: Mapped[str] = mapped_column(String(200), default="")
    width: Mapped[int] = mapped_column(Integer, default=0)
    height: Mapped[int] = mapped_column(Integer, default=0)
    size: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class Audit(Base):
    __tablename__ = "audit"
    id: Mapped[int] = mapped_column(primary_key=True)
    at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, index=True)
    who: Mapped[str] = mapped_column(String(64), default="")
    action: Mapped[str] = mapped_column(String(64))
    detail: Mapped[str] = mapped_column(Text, default="")


class Database:
    def __init__(self, path: Path | str):
        url = "sqlite://" if str(path) == ":memory:" else f"sqlite:///{path}"
        kw = {"connect_args": {"check_same_thread": False}}
        if str(path) == ":memory:":
            from sqlalchemy.pool import StaticPool

            kw["poolclass"] = StaticPool
        self.engine = create_engine(url, **kw)

        @event.listens_for(self.engine, "connect")
        def _pragmas(conn, _):
            cur = conn.cursor()
            cur.execute("PRAGMA foreign_keys=ON")
            cur.execute("PRAGMA journal_mode=WAL")
            cur.close()

        Base.metadata.create_all(self.engine)
        self.sessions = sessionmaker(self.engine, expire_on_commit=False)


def audit(db, who: str, action: str, detail: str = "") -> None:
    db.add(Audit(who=who, action=action, detail=detail[:2000]))
