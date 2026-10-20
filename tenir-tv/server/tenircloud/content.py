"""Контент телевизоров: черновик, публикация версий, сборка документа для конкретной приставки.

Формат документа — tenir-tv/docs/content-format.md (его разбирает ContentParser приложения).
"""

import copy
import hashlib
import json
from importlib import resources

from sqlalchemy import func, select

from .db import ContentDraft, ContentVersion, Device, Group, Property, audit, utcnow

LANGS = ("ru", "en", "kk")
TILES = ("restaurant", "news", "apps", "info")
ACTIONS = ("none", "restaurant", "news", "app", "apps", "info")
# что группа может переопределить
GROUP_KEYS = ("welcome", "accentColor", "banners", "tiles", "language")


class ContentError(ValueError):
    pass


def default_content() -> dict:
    return json.loads(resources.files("tenircloud").joinpath("default-content.json").read_text("utf-8"))


def validate(doc: dict) -> dict:
    """Минимальные требования приложения: без них приставка отвергнет документ и останется на старом кеше."""
    if not isinstance(doc, dict):
        raise ContentError("документ должен быть объектом JSON")
    hotel = doc.get("hotel")
    if not isinstance(hotel, dict) or not str(hotel.get("name", "")).strip():
        raise ContentError("нужно название отеля (hotel.name)")
    for key in ("banners", "news", "apps"):
        if key in doc and not isinstance(doc[key], list):
            raise ContentError(f"{key} должен быть списком")
    tiles = doc.get("home", {}).get("tiles", [])
    if not isinstance(tiles, list):
        raise ContentError("home.tiles должен быть списком")
    return doc


def get_draft(db, prop: Property) -> dict:
    row = db.get(ContentDraft, prop.id)
    if row is None:
        doc = latest_doc(db, prop) or default_content()
        row = ContentDraft(property_id=prop.id, json=json.dumps(doc, ensure_ascii=False))
        db.add(row)
        db.flush()
    return json.loads(row.json)


def save_draft(db, prop: Property, doc: dict, who: str) -> None:
    validate(doc)
    row = db.get(ContentDraft, prop.id)
    text = json.dumps(doc, ensure_ascii=False)
    if row is None:
        db.add(ContentDraft(property_id=prop.id, json=text, updated_by=who))
    else:
        row.json, row.updated_at, row.updated_by = text, utcnow(), who


def latest(db, prop_id: int) -> ContentVersion | None:
    return db.scalar(
        select(ContentVersion)
        .where(ContentVersion.property_id == prop_id)
        .order_by(ContentVersion.version.desc())
        .limit(1)
    )


def latest_doc(db, prop: Property) -> dict | None:
    v = latest(db, prop.id)
    return json.loads(v.json) if v else None


def publish(db, prop: Property, who: str, note: str = "", doc: dict | None = None) -> ContentVersion:
    doc = validate(copy.deepcopy(doc if doc is not None else get_draft(db, prop)))
    n = (db.scalar(select(func.max(ContentVersion.version)).where(ContentVersion.property_id == prop.id)) or 0) + 1
    doc["version"] = n
    v = ContentVersion(property_id=prop.id, version=n, json=json.dumps(doc, ensure_ascii=False),
                       note=note[:200], created_by=who)
    db.add(v)
    audit(db, who, "content.publish", f"{prop.code}: версия {n} {note}".strip())
    db.flush()
    return v


def rollback(db, prop: Property, version: int, who: str) -> ContentVersion:
    old = db.scalar(
        select(ContentVersion).where(ContentVersion.property_id == prop.id, ContentVersion.version == version)
    )
    if old is None:
        raise ContentError(f"версии {version} нет")
    doc = json.loads(old.json)
    save_draft(db, prop, doc, who)
    return publish(db, prop, who, f"откат к версии {version}", doc)


def apply_group(doc: dict, group: Group | None) -> dict:
    """Переопределения группы поверх общего контента отеля."""
    if group is None:
        return doc
    o = group.overrides
    if o.get("welcome"):
        doc.setdefault("hotel", {})["welcome"] = o["welcome"]
    if o.get("accentColor"):
        doc.setdefault("hotel", {})["accentColor"] = o["accentColor"]
    if o.get("banners"):
        wanted = list(o["banners"])
        by_id = {b.get("id"): b for b in doc.get("banners", [])}
        doc["banners"] = [by_id[i] for i in wanted if i in by_id]
    if o.get("tiles"):
        doc.setdefault("home", {})["tiles"] = list(o["tiles"])
    if o.get("language"):
        doc["language"] = o["language"]
    return doc


def absolutize(value, base: str):
    """`/media/...` → полный адрес: приставка загружает изображения только по http(s) или asset:///."""
    if isinstance(value, dict):
        return {k: absolutize(v, base) for k, v in value.items()}
    if isinstance(value, list):
        return [absolutize(v, base) for v in value]
    if isinstance(value, str) and value.startswith("/media/"):
        return base.rstrip("/") + value
    return value


def for_device(db, device: Device, base_url: str) -> dict:
    prop = db.get(Property, device.property_id) if device.property_id else None
    if prop is None:
        doc = default_content()
        doc["version"] = 0
        return doc
    doc = latest_doc(db, prop) or {**default_content(), "version": 0}
    group = device.room.group if device.room is not None else None
    doc = apply_group(doc, group)
    if device.language:
        doc["language"] = device.language
    hub = doc.setdefault("hub", {})
    hub["pollSeconds"] = prop.hub_poll_seconds
    doc.setdefault("display", {})["timezone"] = prop.timezone
    return absolutize(doc, base_url)


def etag(doc: dict) -> str:
    raw = json.dumps(doc, ensure_ascii=False, sort_keys=True).encode()
    return '"' + hashlib.sha1(raw).hexdigest() + '"'


def text(form, name: str) -> dict:
    """Многоязычное поле из формы: name.ru / name.en / name.kk → {"ru": ..., ...} без пустых."""
    out = {}
    for lang in LANGS:
        v = str(form.get(f"{name}.{lang}", "")).strip()
        if v:
            out[lang] = v
    return out


def tr(value, lang: str = "ru") -> str:
    if isinstance(value, dict):
        return value.get(lang) or value.get("*") or value.get("ru") or next(iter(value.values()), "")
    return value or ""
