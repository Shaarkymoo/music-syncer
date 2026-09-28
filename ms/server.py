import hashlib
import json
import os
import secrets
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from ms import apply, db, scan as scan_mod

PARTIAL_PREFIX = ".ms-partial-"
SCHEMA_VERSION = 1


def _safe_join(root: Path, rel: str) -> Path | None:
    target = (root / rel).resolve()
    root_res = root.resolve()
    if root_res != target and root_res not in target.parents:
        return None
    return target


class SyncServer:
    def __init__(self, root: Path, db_path: Path, device_id: str, schema_version: int = SCHEMA_VERSION):
        self.root = root
        self.device_id = device_id
        self.schema_version = schema_version
        # ThreadingHTTPServer serves each request on a worker thread; the
        # server's _lock serializes all DB access, so cross-thread use is safe.
        self._conn = db.init_db(db_path, check_same_thread=False)
        self._lock = threading.Lock()
        self._sessions: dict[str, dict] = {}  # device_id -> client state
        httpd = ThreadingHTTPServer(("0.0.0.0", 0), self._make_handler())
        self.httpd = httpd
        self.port = httpd.server_address[1]

    def _make_handler(self):
        server = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):  # silence
                pass

            def _send_json(self, obj, code=200):
                body = json.dumps(obj).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                parsed = urllib.parse.urlparse(self.path)
                qs = urllib.parse.parse_qs(parsed.query)
                try:
                    if parsed.path == "/handshake":
                        client_id = qs.get("device_id", [""])[0]
                        with server._lock:
                            st = db.sync_state_get(server._conn, client_id)
                            cursor = st[0] if st else 0
                            self._send_json({
                                "schema_version": server.schema_version,
                                "server_device_id": server.device_id,
                                "server_journal_head": db.journal_head(server._conn),
                                "client_cursor": cursor,
                            })
                    elif parsed.path == "/manifest":
                        with server._lock:
                            man = [[p, s, m, h] for (p, s, m, h, _l) in db.manifest_all(server._conn)]
                        self._send_json(man)
                    elif parsed.path == "/journal":
                        after = int(qs.get("after", ["0"])[0])
                        with server._lock:
                            ops = db.journal_since(server._conn, after)
                        self._send_json(ops)
                    elif parsed.path == "/file":
                        rel = qs.get("path", [""])[0]
                        target = _safe_join(server.root, rel)
                        if target is None or not target.is_file():
                            self.send_error(404)
                            return
                        data = target.read_bytes()
                        self.send_response(200)
                        self.send_header("Content-Length", str(len(data)))
                        self.end_headers()
                        self.wfile.write(data)
                    else:
                        self.send_error(404)
                except Exception as e:  # pragma: no cover
                    self._send_json({"error": str(e)}, 500)

            def do_POST(self):
                parsed = urllib.parse.urlparse(self.path)
                qs = urllib.parse.parse_qs(parsed.query)
                length = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(length)
                try:
                    if parsed.path == "/sync":
                        req = json.loads(body)
                        with server._lock:
                            scan_mod.scan(server.root, server._conn, server.device_id, time.time_ns())
                            server._sessions[req["device_id"]] = {
                                "journal_ops": req.get("journal_ops", []),
                                "manifest": req.get("manifest", []),
                            }
                            server_journal_ops = db.journal_since(server._conn, req.get("server_cursor", 0))
                            server_manifest = [[p, s, m, h] for (p, s, m, h, _l) in db.manifest_all(server._conn)]
                            head = db.journal_head(server._conn)
                        self._send_json({
                            "schema_version": server.schema_version,
                            "server_journal_ops": server_journal_ops,
                            "server_manifest": server_manifest,
                            "needs_push": [],  # computed by the CLIENT this session (see protocol note)
                            "server_journal_head": head,
                        })
                    elif parsed.path == "/file":
                        rel = qs.get("path", [""])[0]
                        expected = qs.get("sha", [""])[0]
                        target = _safe_join(server.root, rel)
                        if target is None:
                            self.send_error(404)
                            return
                        actual = hashlib.sha256(body).hexdigest()
                        if actual != expected:
                            self._send_json({"ok": False, "error": "sha256 mismatch"}, 400)
                            return
                        target.parent.mkdir(parents=True, exist_ok=True)
                        tmp = target.parent / f"{PARTIAL_PREFIX}{secrets.token_hex(4)}-{target.name}"
                        tmp.write_bytes(body)
                        os.replace(tmp, target)
                        self._send_json({"ok": True})
                    elif parsed.path == "/done":
                        req = json.loads(body)
                        with server._lock:
                            from ms.merge import Plan
                            plan = Plan(delete=req.get("delete", []),
                                        conflict_loser=[tuple(c) for c in req.get("conflicts", [])])
                            apply.apply_plan(server.root, plan, server._conn, server.device_id,
                                             req["device_id"], {}, req.get("ts_ns", 0), lambda p: b"")
                            db.sync_state_set(server._conn, req["device_id"],
                                              req.get("client_journal_head", 0), req.get("ts_ns", 0))
                        self._send_json({"ok": True})
                    else:
                        self.send_error(404)
                except Exception as e:  # pragma: no cover
                    self._send_json({"error": str(e)}, 500)

        return Handler

    def serve_forever(self):
        self.httpd.serve_forever()

    def shutdown(self):
        self.httpd.shutdown()
        self.httpd.server_close()