#!/usr/bin/env python3
"""Мост RW AI -> Claude Code на вашем компьютере.

Принимает POST /ask с JSON {"task": "..."} и заголовком Authorization: Bearer <токен>,
запускает `claude -p` (работает от вашей подписки Claude Code) и возвращает ответ.

Безопасность:
  * без токена (RW_BRIDGE_TOKEN, минимум 16 символов) не запускается;
  * по умолчанию слушает только 127.0.0.1; для телефона укажите --host (лучше адрес Tailscale/VPN, а не открытый интернет);
  * по умолчанию Claude Code работает в режиме "plan" (только чтение/планирование); правки файлов — только с --allow-edits;
  * команда запускается без оболочки (shell), размер запроса и ответа ограничен.
"""
import argparse
import hmac
import json
import os
import secrets
import socket
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY = 8 * 1024
MAX_OUT = 20_000


def run_claude(task: str, cmd: str, cwd: str | None, allow_edits: bool, timeout: int) -> tuple[int, str]:
    args = [cmd, "-p", task, "--permission-mode", "acceptEdits" if allow_edits else "plan"]
    try:
        p = subprocess.run(args, capture_output=True, text=True, timeout=timeout, cwd=cwd or None, shell=False)
    except FileNotFoundError:
        return 500, f"Команда '{cmd}' не найдена. Установите Claude Code и войдите в аккаунт."
    except subprocess.TimeoutExpired:
        return 504, f"Claude Code не ответил за {timeout} с."
    out = (p.stdout or "").strip() or (p.stderr or "").strip()
    return (200 if p.returncode == 0 else 502), out[:MAX_OUT]


def make_handler(token: str, cmd: str, cwd: str | None, allow_edits: bool, timeout: int):
    class Handler(BaseHTTPRequestHandler):
        server_version = "RWBridge/1.0"

        def log_message(self, fmt, *a):  # в журнал — без содержимого запросов
            sys.stderr.write("%s %s\n" % (self.address_string(), fmt % a if a else fmt))

        def _send(self, code: int, obj: dict):
            data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _authorized(self) -> bool:
            got = self.headers.get("Authorization", "")
            return got.startswith("Bearer ") and hmac.compare_digest(got[7:].encode(), token.encode())

        def do_GET(self):
            if self.path == "/health" and self._authorized():
                return self._send(200, {"ok": True})
            self._send(404 if self._authorized() else 401, {"error": "not found" if self._authorized() else "unauthorized"})

        def do_POST(self):
            if not self._authorized():
                return self._send(401, {"error": "unauthorized"})
            if self.path != "/ask":
                return self._send(404, {"error": "not found"})
            try:
                n = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                return self._send(400, {"error": "bad length"})
            if n <= 0 or n > MAX_BODY:
                return self._send(413, {"error": "bad size"})
            try:
                task = str(json.loads(self.rfile.read(n).decode("utf-8")).get("task", "")).strip()
            except Exception:
                return self._send(400, {"error": "bad json"})
            if not task:
                return self._send(400, {"error": "empty task"})
            code, out = run_claude(task, cmd, cwd, allow_edits, timeout)
            self._send(200 if code == 200 else code, {"answer": out} if code == 200 else {"error": out})

    return Handler


def local_ip() -> str:
    """Адрес компьютера в локальной сети (без отправки данных в интернет)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def load_or_create_token() -> str:
    """Токен из RW_BRIDGE_TOKEN, иначе из файла рядом со скриптом (создаётся при первом запуске)."""
    t = os.environ.get("RW_BRIDGE_TOKEN", "")
    if t:
        return t
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".rw_bridge_token")
    if os.path.exists(path):
        t = open(path, encoding="utf-8").read().strip()
        if len(t) >= 16:
            return t
    t = secrets.token_urlsafe(24)
    with open(path, "w", encoding="utf-8") as f:
        f.write(t)
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass
    return t


def main() -> None:
    ap = argparse.ArgumentParser(description="Мост RW AI -> Claude Code")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--cwd", default=None, help="рабочая папка для Claude Code")
    ap.add_argument("--allow-edits", action="store_true", help="разрешить Claude Code править файлы (по умолчанию только чтение)")
    ap.add_argument("--timeout", type=int, default=280)
    a = ap.parse_args()
    token = load_or_create_token()
    if len(token) < 16:
        sys.exit("Токен RW_BRIDGE_TOKEN слишком короткий (нужно не менее 16 символов).")
    cmd = os.environ.get("RW_CLAUDE_CMD", "claude")
    srv = ThreadingHTTPServer((a.host, a.port), make_handler(token, cmd, a.cwd, a.allow_edits, a.timeout))
    shown = local_ip() if a.host in ("0.0.0.0", "") else a.host
    print("=" * 60)
    print("Мост запущен. Впишите это в приложении Руся AI")
    print("(Настройки → Дополнительно → Мост к Claude Code):")
    print(f"  Адрес:  http://{shown}:{a.port}")
    print(f"  Токен:  {token}")
    print(f"  Правки файлов Claude Code: {'разрешены' if a.allow_edits else 'запрещены (режим чтения)'}")
    print("Окно не закрывайте, пока мост нужен. Остановка: Ctrl+C.")
    print("=" * 60, flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
