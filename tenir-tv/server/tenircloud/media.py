"""Медиатека: изображения для фона, баннеров, меню. Имя файла — хеш содержимого (кеш на приставках бессрочный)."""

import hashlib
import io
import re

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import FileResponse
from PIL import Image, ImageOps

from .db import Media

router = APIRouter()

MAX_SIDE = 3840  # 4K UHD
MAX_UPLOAD = 25 * 1024 * 1024
NAME = re.compile(r"^[0-9a-f]{20}\.(jpg|png)$")


class MediaError(ValueError):
    pass


def store(db, media_dir, prop_id: int, data: bytes, original: str) -> Media:
    if len(data) > MAX_UPLOAD:
        raise MediaError("файл больше 25 МБ")
    try:
        img = Image.open(io.BytesIO(data))
        img.load()
    except Exception as e:  # Pillow бросает разные исключения на битых файлах
        raise MediaError("это не изображение (нужен JPEG, PNG или WebP)") from e
    img = ImageOps.exif_transpose(img)
    if max(img.size) > MAX_SIDE:
        img.thumbnail((MAX_SIDE, MAX_SIDE), Image.LANCZOS)
    alpha = img.mode in ("RGBA", "LA", "P") and "transparency" in img.info or img.mode in ("RGBA", "LA")
    out = io.BytesIO()
    if alpha:  # логотипы с прозрачностью
        img.convert("RGBA").save(out, "PNG", optimize=True)
        ext = "png"
    else:
        img.convert("RGB").save(out, "JPEG", quality=88, optimize=True, progressive=True)
        ext = "jpg"
    raw = out.getvalue()
    name = hashlib.sha256(raw).hexdigest()[:20] + "." + ext
    existing = db.query(Media).filter_by(filename=name).first()
    if existing:
        return existing
    (media_dir / name).write_bytes(raw)
    m = Media(property_id=prop_id, filename=name, original_name=original[:200], width=img.width,
              height=img.height, size=len(raw))
    db.add(m)
    db.flush()
    return m


@router.get("/media/{name}")
def get_media(name: str, request: Request):
    if not NAME.match(name):
        raise HTTPException(404)
    path = request.app.state.media_dir / name
    if not path.exists():
        raise HTTPException(404)
    return FileResponse(path, headers={"Cache-Control": "public, max-age=31536000, immutable"})
