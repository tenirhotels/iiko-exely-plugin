#!/usr/bin/env bash
# Установка TENIR TV Cloud как службы на локальный сервер (Ubuntu/Debian).
#   sudo ./deploy/install.sh install | update | backup | status | uninstall
# Проверка без изменений в системе: TENIR_DRY_RUN=1 ./deploy/install.sh install
set -euo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP=/opt/tenircloud
DATA="${TENIR_CLOUD_DATA:-/var/lib/tenircloud}"
BACKUPS="${TENIR_BACKUP_DIR:-/var/backups/tenircloud}"
PORT="${TENIR_PORT:-8090}"
USER_NAME=tenircloud
UNIT=/etc/systemd/system/tenircloud.service
DRY="${TENIR_DRY_RUN:-0}"

say() { printf '%s\n' "$*"; }
die() { printf '\033[31m✕ %s\033[0m\n' "$*" >&2; exit 1; }
run() { if [ "$DRY" = 1 ]; then printf '+ %s\n' "$*"; else "$@"; fi; }
need_root() { [ "$DRY" = 1 ] || [ "$(id -u)" -eq 0 ] || die "Нужны права администратора: sudo $0 $*"; }

install_app() {
  need_root "$@"
  command -v python3 >/dev/null || die "нужен python3 (3.11+): apt install python3 python3-venv"
  python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)' || die "нужен Python 3.11 или новее"
  id "$USER_NAME" >/dev/null 2>&1 || run useradd --system --home "$DATA" --shell /usr/sbin/nologin "$USER_NAME"
  run mkdir -p "$APP" "$DATA" "$BACKUPS"
  run cp -r "$SRC/pyproject.toml" "$SRC/tenircloud" "$APP/"
  run python3 -m venv "$APP/.venv"
  run "$APP/.venv/bin/pip" install --quiet "$APP"
  run chown -R "$USER_NAME": "$DATA"
  run chmod 700 "$DATA"
  if [ "$DRY" = 1 ]; then
    say "+ записать $UNIT"
  else
    cat > "$UNIT" <<UNITFILE
[Unit]
Description=TENIR TV Cloud
After=network-online.target
Wants=network-online.target

[Service]
User=$USER_NAME
Environment=TENIR_CLOUD_DATA=$DATA
ExecStart=$APP/.venv/bin/tenircloud serve --host 0.0.0.0 --port $PORT
Restart=always
RestartSec=3
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=$DATA
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNITFILE
  fi
  run systemctl daemon-reload
  run systemctl enable --now tenircloud
  say "Готово. Панель и адрес для приставок:"
  for ip in $(hostname -I 2>/dev/null); do say "  http://$ip:$PORT"; done
  say "Первый вход в панель создаёт администратора. Закрепите IP сервера в роутере (резерв DHCP)."
}

backup() {
  need_root "$@"
  local f
  f="$BACKUPS/tenircloud-$(date +%F-%H%M).tar.gz"
  run mkdir -p "$BACKUPS"
  if [ "$DRY" = 1 ]; then say "+ sqlite3 .backup + tar → $f"; return; fi
  local tmp
  tmp="$(mktemp -d)"
  cp -r "$DATA"/. "$tmp"/
  if command -v sqlite3 >/dev/null; then sqlite3 "$DATA/cloud.db" ".backup '$tmp/cloud.db'"; fi
  rm -f "$tmp"/cloud.db-wal "$tmp"/cloud.db-shm
  tar -czf "$f" -C "$tmp" .
  chmod 600 "$f"
  rm -rf "$tmp"
  say "Копия: $f (внутри ключи fernet.key и session.key — храните как пароль)"
}

case "${1:-}" in
  install) install_app "$@" ;;
  update)
    need_root "$@"
    run cp -r "$SRC/pyproject.toml" "$SRC/tenircloud" "$APP/"
    run "$APP/.venv/bin/pip" install --quiet "$APP"
    run systemctl restart tenircloud
    ;;
  backup) backup "$@" ;;
  status)
    systemctl status tenircloud --no-pager || true
    curl -s "http://127.0.0.1:$PORT/healthz" || true
    echo
    ;;
  uninstall)
    need_root "$@"
    run systemctl disable --now tenircloud || true
    run rm -f "$UNIT"
    run rm -rf "$APP"
    say "Данные оставлены в $DATA"
    ;;
  *) say "Использование: sudo $0 install|update|backup|status|uninstall"; exit 1 ;;
esac
