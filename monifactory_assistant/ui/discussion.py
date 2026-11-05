"""Loopback-only mirror of Discussion. Never connects to an AI or resumes a thread."""

import atexit
import json
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from monifactory_assistant.config import write_json


def recent_history(data_dir, provider):
    """Restore the game's recent display after restart, excluding an unfinished answer."""
    try:
        view = json.loads((Path(data_dir) / "vue_mod.json").read_text(encoding="utf-8"))
        if view.get("moteur") != provider:
            return []
        rows = view["ecran"]["conversation"]
        if (view.get("bulle") or {}).get("en_cours"):
            rows = rows[:-1]
        return [
            {"de": row["de"], "texte": row["texte"]}
            for row in rows[-30:]
            if isinstance(row, dict)
            and row.get("de") in ("toi", "claude")
            and isinstance(row.get("texte"), str)
        ]
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        return []


class DiscussionViewer:
    def __init__(self, data_dir, runtime, port=18764):
        self.source = Path(data_dir) / "vue_mod.json"
        self.runtime = Path(runtime)
        self.runtime.mkdir(parents=True, exist_ok=True)
        token_file = self.runtime / "suivi-token"
        try:
            token = token_file.read_text().strip()
        except OSError:
            token = ""
        if len(token) != 64 or any(c not in "0123456789abcdef" for c in token):
            token = secrets.token_hex(32)
            token_file.write_text(token, encoding="utf-8")
        token_file.chmod(0o600)
        self.prefix = "/" + token + "/"
        self.last_state = {
            "messages": [],
            "provider": "Assistant",
            "status": "En attente du mod…",
            "busy": False,
            "notice": "",
            "connection_error": "",
            "updated_at": 0,
        }
        self.lock = threading.Lock()
        self.source_signature = None
        viewer = self
        self.html = (Path(__file__).parent / "web/index.html").read_bytes()
        self.script = (Path(__file__).parent / "web/app.js").read_bytes()
        self.style = (Path(__file__).parent / "web/style.css").read_bytes()

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass  # URLs contain the local access token.

            def reply(self, code, content=b"", content_type="text/plain; charset=utf-8"):
                self.send_response(code)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(content)))
                self.send_header("Cache-Control", "no-store")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.send_header("Referrer-Policy", "no-referrer")
                self.send_header(
                    "Content-Security-Policy",
                    "default-src 'none'; script-src 'self'; "
                    "style-src 'self'; connect-src 'self'; frame-ancestors 'none'; "
                    "base-uri 'none'; form-action 'none'",
                )
                self.end_headers()
                self.wfile.write(content)

            def do_GET(self):
                host = f"127.0.0.1:{self.server.server_port}"
                if (
                    self.headers.get("Host") != host
                    or self.headers.get("Origin", "http://" + host) != "http://" + host
                ):
                    self.reply(403)
                elif self.path == viewer.prefix:
                    self.reply(200, viewer.html, "text/html; charset=utf-8")
                elif self.path == viewer.prefix + "app.js":
                    self.reply(200, viewer.script, "text/javascript; charset=utf-8")
                elif self.path == viewer.prefix + "style.css":
                    self.reply(200, viewer.style, "text/css; charset=utf-8")
                elif self.path == viewer.prefix + "state":
                    self.reply(
                        200,
                        json.dumps(viewer.snapshot(), ensure_ascii=False).encode(),
                        "application/json; charset=utf-8",
                    )
                else:
                    self.reply(404)

            def do_POST(self):
                self.reply(405)

            do_PUT = do_DELETE = do_PATCH = do_POST

        try:
            self.server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
        except OSError:
            if not port:
                raise
            self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_port}{self.prefix}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        try:
            write_json(self.runtime / "suivi.json", {"url": self.url})
            (self.runtime / "suivi.json").chmod(0o600)
        except OSError:
            self.close()
            raise
        atexit.register(self.close)

    def snapshot(self):
        # Only the game's display is exposed, never recipes, credentials or arbitrary files.
        with self.lock:
            try:
                stat = self.source.stat()
                signature = (stat.st_dev, stat.st_ino, stat.st_mtime_ns, stat.st_size)
                if signature == self.source_signature:
                    return self._state()
                with self.source.open(encoding="utf-8") as handle:
                    view = json.loads(handle.read(8 * 1024 * 1024))
                screen = view["ecran"]
                messages = [
                    {"role": "user" if m.get("de") == "toi" else "assistant", "text": m["texte"]}
                    for m in screen.get("conversation", [])[-31:]
                    if isinstance(m, dict) and isinstance(m.get("texte"), str)
                ]
                self.last_state = {
                    "messages": messages,
                    "provider": str(view.get("moteur", "Assistant")),
                    "status": str(screen.get("statut") or ""),
                    "busy": bool(screen.get("occupe")),
                    "connection_error": str(screen.get("erreur_connexion") or ""),
                    "notice": str((view.get("bulle") or {}).get("texte") or ""),
                    "updated_at": stat.st_mtime,
                }
                # Cache only a readable snapshot. Failed/partial writes are retried.
                self.source_signature = signature
            except (OSError, ValueError, KeyError, TypeError, AttributeError):
                pass  # A partial file write must not erase the previous readable display.
            return self._state()

    def _state(self):
        result = dict(self.last_state)
        result["online"] = time.time() - result["updated_at"] < 30
        return result

    def close(self):
        if self.thread.is_alive():
            self.server.shutdown()
            self.thread.join(timeout=2)
        self.server.server_close()
