import hashlib
import json
import os
import secrets
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from ms import adopt, apply, db, scan as scan_mod
from ms.progress import Progress

PARTIAL_PREFIX = ".ms-partial-"
SCHEMA_VERSION = 1
# Keep in sync with android/app/build.gradle.kts versionName.
APP_VERSION = "0.1.2"

# Per-request start time so log_message can report handler duration. The
# server is threaded, so this must be thread-local.
_req_start = threading.local()


def _safe_join(root: Path, rel: str) -> Path | None:
    target = (root / rel).resolve()
    root_res = root.resolve()
    if root_res != target and root_res not in target.parents:
        return None
    return target


class SyncServer:
    def __init__(self, root: Path, db_path: Path, device_id: str, schema_version: int = SCHEMA_VERSION,
                 port: int = 0, progress: Progress | None = None, apk_path: str | None = None,
                 token: str | None = None):
        self.root = root
        self.device_id = device_id
        self.schema_version = schema_version
        self._progress = progress
        self.apk_path = apk_path
        self.token = token
        # ThreadingHTTPServer serves each request on a worker thread; the
        # server's _lock serializes all DB access, so cross-thread use is safe.
        self._conn = db.init_db(db_path, check_same_thread=False)
        self._lock = threading.Lock()
        self._sessions: dict[str, dict] = {}  # device_id -> client state
        httpd = ThreadingHTTPServer(("0.0.0.0", port), self._make_handler())
        self.httpd = httpd
        self.port = httpd.server_address[1]

    def _make_handler(self):
        server = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, format, *args):
                # BaseHTTPRequestHandler funnels every request/error here:
                # log_request passes (requestline, code, size); log_error
                # passes (code, message) or a free-form message. Nothing
                # fails silently: every line goes to stderr, stdout stays
                # clean for journal/scan output.
                if format == '"%s" %s %s':
                    parts = args[0].split()
                    method = parts[0]
                    path = parts[1] if len(parts) > 1 else args[0]
                    dur = ""
                    start = getattr(_req_start, "t", None)
                    if start is not None:
                        dur = f" ({int((time.monotonic() - start) * 1000)} ms)"
                    sys.stderr.write(
                        f"[{time.strftime('%H:%M:%S')}] {method} {path} -> {args[1]}{dur}\n")
                elif format.startswith("code %d"):
                    pass  # send_error duplicates the request line above
                else:
                    sys.stderr.write(f"[{time.strftime('%H:%M:%S')}] {format % args}\n")

            def _send_json(self, obj, code=200):
                body = json.dumps(obj).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def _authorized(self, parsed) -> bool:
                if server.token is None or parsed.path == "/handshake":
                    return True
                return self.headers.get("Authorization", "") == f"Bearer {server.token}"

            def do_GET(self):
                _req_start.t = time.monotonic()
                parsed = urllib.parse.urlparse(self.path)
                qs = urllib.parse.parse_qs(parsed.query)
                try:
                    if not self._authorized(parsed):
                        self._send_json({"error": "unauthorized"}, 401)
                        return
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
                                "token_required": server.token is not None,
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
                    elif parsed.path == "/version":
                        self._send_json({"version": APP_VERSION})
                    elif parsed.path == "/apk":
                        apk = Path(server.apk_path) if server.apk_path else None
                        if apk is None or not apk.is_file():
                            self._send_json({"error": "apk not available"}, 404)
                            return
                        data = apk.read_bytes()
                        self.send_response(200)
                        self.send_header("Content-Type", "application/vnd.android.package-archive")
                        self.send_header("Content-Length", str(len(data)))
                        self.end_headers()
                        self.wfile.write(data)
                    else:
                        self.send_error(404)
                except Exception as e:  # pragma: no cover
                    self.log_error("ERROR %s: %s", parsed.path, e)
                    self._send_json({"error": str(e)}, 500)

            def do_POST(self):
                _req_start.t = time.monotonic()
                parsed = urllib.parse.urlparse(self.path)
                qs = urllib.parse.parse_qs(parsed.query)
                if not self._authorized(parsed):
                    self._send_json({"error": "unauthorized"}, 401)
                    return
                length = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(length)
                try:
                    if parsed.path == "/sync":
                        req = json.loads(body)
                        with server._lock:
                            scan_mod.scan(server.root, server._conn, server.device_id,
                                          time.time_ns(), progress=server._progress)
                            client_manifest = {p: (s, m, h) for (p, s, m, h) in req.get("manifest", [])}
                            # Ops the client has NOT seen yet; paths with an unseen
                            # local MODIFY must not adopt the client's stale sha.
                            server_ops_since_client_cursor = db.journal_since(
                                server._conn, req.get("server_cursor", 0))
                            recently_modified = {op["path"] for op in server_ops_since_client_cursor
                                                 if op["op"] == "MODIFY" and op["device"] == server.device_id}
                            adopt.adopt_shas(server._conn, client_manifest, Path(server.root),
                                             time.time_ns(), recently_modified)
                            server._sessions[req["device_id"]] = {
                                "journal_ops": req.get("journal_ops", []),
                                "manifest": req.get("manifest", []),
                            }
                            server_journal_ops = server_ops_since_client_cursor
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
                        if not rel.strip():
                            self.send_error(400)
                            return
                        expected = qs.get("sha", [""])[0]
                        target = _safe_join(server.root, rel)
                        if target is None:
                            self.send_error(404)
                            return
                        actual = hashlib.sha256(body).hexdigest()
                        if expected and actual != expected:
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
                    self.log_error("ERROR %s: %s", parsed.path, e)
                    self._send_json({"error": str(e)}, 500)

        return Handler

    def serve_forever(self):
        self.httpd.serve_forever()

    def shutdown(self):
        self.httpd.shutdown()
        self.httpd.server_close()