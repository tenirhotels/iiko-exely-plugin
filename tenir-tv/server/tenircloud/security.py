"""Пароли, ключи приставок, шифрование токена Hotel Hub."""

import hashlib
import hmac
import os
import secrets
from pathlib import Path

from cryptography.fernet import Fernet, InvalidToken


def hash_password(password: str, salt: bytes | None = None) -> str:
    salt = salt or os.urandom(16)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 310_000)
    return f"pbkdf2${salt.hex()}${dk.hex()}"


def check_password(password: str, stored: str) -> bool:
    try:
        _, salt, digest = stored.split("$")
    except ValueError:
        return False
    return hmac.compare_digest(hash_password(password, bytes.fromhex(salt)), stored) and bool(digest)


def new_device_key() -> str:
    return secrets.token_urlsafe(32)


def hash_key(key: str) -> str:
    return hashlib.sha256(key.encode()).hexdigest()


def new_pairing_code() -> str:
    return f"{secrets.randbelow(1_000_000):06d}"


class Secrets:
    """Ключ шифрования и секрет сессий — файлы в каталоге данных (права 600)."""

    def __init__(self, data_dir: Path):
        self.dir = data_dir

    def _load(self, name: str, make) -> bytes:
        f = self.dir / name
        if not f.exists():
            f.write_bytes(make())
            f.chmod(0o600)
        return f.read_bytes().strip()

    @property
    def fernet(self) -> Fernet:
        return Fernet(self._load("fernet.key", Fernet.generate_key))

    @property
    def session_secret(self) -> str:
        return self._load("session.key", lambda: secrets.token_hex(32).encode()).decode()

    def encrypt(self, value: str) -> str:
        return self.fernet.encrypt(value.encode()).decode() if value else ""

    def decrypt(self, blob: str) -> str:
        if not blob:
            return ""
        try:
            return self.fernet.decrypt(blob.encode()).decode()
        except InvalidToken:
            return ""
