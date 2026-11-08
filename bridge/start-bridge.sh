#!/usr/bin/env bash
# Запуск моста: ./start-bridge.sh   (правки файлов Claude Code разрешить: ./start-bridge.sh --allow-edits)
cd "$(dirname "$0")"
command -v claude >/dev/null || { echo "Claude Code не найден. Установите его и войдите в аккаунт."; exit 1; }
exec python3 rw_bridge.py --host 0.0.0.0 "$@"
