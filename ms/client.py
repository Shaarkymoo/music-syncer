import json
import time
import urllib.parse
import urllib.request
from pathlib import Path

from ms import adopt, apply, db, merge, scan as scan_mod
from ms.progress import Progress, SyncPhase, emit

SCHEMA_VERSION = 1


def _http_json(url: str, method: str = "GET", body: bytes | None = None) -> dict:
    req = urllib.request.Request(url, data=body, method=method,
                                 headers={"Content-Type": "application/json"} if body else {})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())


def _http_get_bytes(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def run_sync_session(server_url: str, root: Path, db_path: Path, our_device: str,
                     progress: Progress | None = None) -> dict:
    conn = db.init_db(db_path)
    now_ns = time.time_ns()

    # Count scanned files so the final DONE event can report the session total.
    scanned = 0

    def _counting(phase: SyncPhase, done: int, total: int, rel: str) -> None:
        nonlocal scanned
        if phase is SyncPhase.SCAN:
            scanned = total
        emit(progress, phase, done, total, rel)

    # 1. Handshake: learn server id + how much of OUR journal the server has seen.
    hs = _http_json(f"{server_url}/handshake?device_id={urllib.parse.quote(our_device)}")
    if hs["schema_version"] != SCHEMA_VERSION:
        raise ValueError(f"schema mismatch: server {hs['schema_version']} != client {SCHEMA_VERSION}")
    server_device = hs["server_device_id"]
    server_cursor_for_us = hs["client_cursor"]            # server.sync_state[us]
    server_journal_head = hs["server_journal_head"]

    # 2. Local scan (fresh change detection on demand), then gather our state.
    scan_mod.scan(root, conn, our_device, now_ns, progress=_counting)
    our_cursor_for_server = db.sync_state_get(conn, server_device)  # what we've seen of server
    our_cursor = our_cursor_for_server[0] if our_cursor_for_server else 0
    our_ops = db.journal_since(conn, server_cursor_for_us)
    our_manifest = {p: (s, m, h) for (p, s, m, h, _l) in db.manifest_all(conn)}

    # 3. Submit state; receive server journal + manifest.
    body = json.dumps({
        "device_id": our_device,
        "server_cursor": our_cursor,
        "journal_ops": our_ops,
        "manifest": [[p, s, m, h] for p, (s, m, h) in our_manifest.items()],
    }).encode()
    resp = _http_json(f"{server_url}/sync", method="POST", body=body)
    server_ops = resp["server_journal_ops"]
    server_manifest = {p: (s, m, h) for (p, s, m, h) in resp["server_manifest"]}

    # 3.5. Adopt server shas for identical local files (no transfer, no hashing),
    #      then refresh our manifest so both plans see the adopted shas.
    #      Paths with an unseen local MODIFY are skipped: a same-size rewrite
    #      must not be frozen to the server's stale sha.
    recently_modified = {op["path"] for op in our_ops
                         if op["op"] == "MODIFY" and op["device"] == our_device}
    adopt.adopt_shas(conn, server_manifest, root, now_ns, recently_modified)
    our_manifest = {p: (s, m, h) for (p, s, m, h, _l) in db.manifest_all(conn)}

    # 4. OUR plan: what we must fetch / delete / conflict-preserve.
    #    peer_cursor = how much of OUR journal the peer (server) has seen.
    plan = merge.build_plan(our_manifest, our_ops, server_manifest, server_ops,
                            server_cursor_for_us, our_device)
    plan_items = [rel for rel, _s, _sha in plan.fetch]
    plan_items += list(plan.delete)
    plan_items += [rel for rel, _ts, _sha in plan.conflict_loser]
    for i, rel in enumerate(plan_items, 1):
        emit(progress, SyncPhase.PLAN, i, len(plan_items), "")
    remote_ops = {op["path"]: op["op"] for op in server_ops}
    summary = apply.apply_plan(root, plan, conn, our_device, server_device, remote_ops,
                               now_ns, lambda rel: _http_get_bytes(
                                   f"{server_url}/file?path={urllib.parse.quote(rel)}"))
    for i, rel in enumerate(summary["fetched"], 1):
        emit(progress, SyncPhase.TRANSFER, i, len(summary["fetched"]), rel)

    # 5. SERVER's plan: what the server needs (fetch = push to it; delete/conflict = applied at /done).
    #    peer_cursor = how much of the SERVER's journal WE have seen.
    #    Recompute OUR state after apply so the server plan sees files deleted
    #    this session as gone — otherwise it would fetch (push) a file the phone
    #    just deleted and the push read would fail.
    our_manifest = {p: (s, m, h) for (p, s, m, h, _l) in db.manifest_all(conn)}
    our_ops = db.journal_since(conn, server_cursor_for_us)
    server_plan = merge.build_plan(server_manifest, server_ops,
                                   our_manifest, our_ops, our_cursor,
                                   server_device)
    pushed: list[str] = []
    for i, (rel, _size, sha) in enumerate(server_plan.fetch, 1):
        emit(progress, SyncPhase.TRANSFER, i, len(server_plan.fetch), rel)
        data = (root / rel).read_bytes()
        url = f"{server_url}/file?path={urllib.parse.quote(rel)}"
        if sha:
            url += f"&sha={sha}"
        _http_json(url, method="POST", body=data)
        pushed.append(rel)

    # 6. Commit: tell the server to apply its deletions + conflict preservations, advance cursors.
    server_cursor_for_us_next = resp["server_journal_head"]
    db.sync_state_set(conn, server_device, server_cursor_for_us_next, now_ns)
    _http_json(f"{server_url}/done", method="POST",
               body=json.dumps({
                   "device_id": our_device,
                   "client_journal_head": db.journal_head(conn),
                   "ts_ns": now_ns,
                   "delete": server_plan.delete,
                   "conflicts": [[c[0], c[1], c[2]] for c in server_plan.conflict_loser],
               }).encode())
    conn.close()

    summary["pushed"] = pushed
    total = scanned + len(plan_items) + len(pushed)
    emit(progress, SyncPhase.DONE, total, total, "")
    return summary