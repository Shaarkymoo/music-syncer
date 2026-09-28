# Music-Syncer Engine + Linux Daemon — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the OS-independent sync engine (scan → journal → merge → apply) plus the Linux `ms` CLI/daemon that bidirectionally mirrors a music folder between two engines over localhost HTTP — the foundation the Android app will later port.

**Architecture:** One engine, symmetric on both sides. Each side keeps an SQLite `manifest` (per-file state), an append-only `journal` (CREATE/MODIFY/DELETE ops), and `sync_state` (per-peer cursors). A manual scan diffs disk vs manifest (size+mtime fingerprint gate, sha256 only on candidates). A sync session exchanges journal deltas + manifests, each side independently computes its own apply plan (LWW conflicts, content-addressed transfer skip), then transfers only needed bytes with sha256 verification. No rename/move detection: moves are DELETE+CREATE; the content-addressed skip makes them free.

**Tech Stack:** Python 3.12, stdlib `sqlite3`/`http.server`/`hashlib`/`tomllib`, `pytest` for tests, `zeroconf` for mDNS.

## Global Constraints

- Identity = content hash (sha256) only. NO move inference, NO tag parsing, NO metadata fingerprinting anywhere in the engine.
- Ops are only `CREATE | MODIFY | DELETE`. No MOVE op.
- Deletes-last ordering during apply; transfers write to a temp name (`.ms-partial-*`), verify sha256, then atomic rename.
- Content-addressed skip: before fetching a file, if local manifest already has that sha256 at any path, copy locally instead of transferring.
- Conflict rule: last-writer-wins by op `ts_ns`; loser preserved as a hidden file `.<basename>.sync-conflict-<ts>.<ext>` in the same directory (dot-prefixed → scan skips it → never synced; MediaStore ignores dotfiles).
- Scan skips any path component starting with `.`; also deletes stale `.ms-partial-*` files as crash residue.
- Journal is append-only and never purged. Applied remote ops are journaled (device = remote) ONLY when an actual mutation occurred (no echo-journaling on idempotent no-ops).
- Push-vs-delete decision uses the peer cursor: a path absent from the remote manifest is PUSH if our journal has an unseen (id > peer_cursor) CREATE/MODIFY tagged with OUR device, else DELETE.
- Files are mirrored as opaque bytes; hidden files/dirs (dot-prefix) are never mirrored.
- Sync is manual: `ms scan`, `ms serve`, `ms log`, `ms verify`. No watchers, no daemonize, no auto-trigger.
- Multi-level verification: L1 per-file sha256 (scan), L2 transfer double-check (sender+receiver hash), L3 tree root digest (skip reconciliation when equal), L4 post-sync re-check, L5 `ms verify` (full re-hash).
- No auth (trusted LAN). Port default 8756. Schema version 1.

## File Structure

```
pyproject.toml            # project metadata + pytest config
requirements.txt          # pytest, zeroconf
ms/
  __init__.py             # __version__ = "0.1.0"
  hashing.py              # sha256_file, fingerprint, tree_root_digest
  db.py                   # SQLite schema + manifest/journal/sync_state access
  scan.py                 # scan(): disk-vs-manifest diff -> journal ops
  merge.py                # build_plan(): LWW conflict + push/fetch/delete decisions
  apply.py                # apply_plan(): temp+rename+verify, content-addressed copy, deletes-last, conflict files
  server.py               # ThreadingHTTPServer: /handshake /sync /manifest /journal /file /done
  client.py               # run_sync_session(): client-driven session orchestration
  discovery.py            # mDNS advertise/discover via zeroconf
  config.py               # TOML config loading
  cli.py                  # ms scan|serve|log|verify
tests/
  conftest.py             # tmp_db, tmp_root, populated roots fixtures
  test_hashing.py
  test_db.py
  test_scan.py
  test_merge.py
  test_apply.py
  test_server.py
  test_sync_integration.py   # two engines over localhost: the correctness crown jewel
  test_cli.py
```

---

## Task 1: Scaffold + hashing module

**Files:**
- Create: `pyproject.toml`, `requirements.txt`, `ms/__init__.py`, `ms/hashing.py`
- Test: `tests/test_hashing.py`

**Interfaces:**
- Produces: `sha256_file(path: Path) -> str`, `fingerprint(path: Path) -> tuple[int, int]`, `tree_root_digest(entries: Iterable[tuple[str, str]]) -> str`

- [ ] **Step 1: Write the failing tests**

`tests/test_hashing.py`:
```python
import hashlib
from pathlib import Path

from ms.hashing import fingerprint, sha256_file, tree_root_digest


def test_sha256_file_matches_hashlib(tmp_path: Path):
    p = tmp_path / "a.bin"
    p.write_bytes(b"hello world" * 1000)
    assert sha256_file(p) == hashlib.sha256(b"hello world" * 1000).hexdigest()


def test_sha256_file_large(tmp_path: Path):
    p = tmp_path / "big.bin"
    p.write_bytes(b"x" * (3 * 1024 * 1024))  # > 1 MiB chunk, forces multi-chunk read
    assert sha256_file(p) == hashlib.sha256(b"x" * (3 * 1024 * 1024)).hexdigest()


def test_fingerprint_returns_size_and_mtime_ns(tmp_path: Path):
    p = tmp_path / "s.txt"
    p.write_text("abc")
    size, mtime_ns = fingerprint(p)
    assert size == 3
    assert mtime_ns == p.stat().st_mtime_ns


def test_tree_root_digest_is_deterministic_and_order_independent():
    a = tree_root_digest([("b/x.mp3", "aa"), ("a/y.mp3", "bb")])
    b = tree_root_digest([("a/y.mp3", "bb"), ("b/x.mp3", "aa")])
    c = tree_root_digest([("b/x.mp3", "aa")])
    assert a == b
    assert a != c
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest tests/test_hashing.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'ms'`

- [ ] **Step 3: Create scaffold + minimal implementation**

`pyproject.toml`:
```toml
[project]
name = "music-syncer"
version = "0.1.0"
requires-python = ">=3.12"
dependencies = ["zeroconf>=0.132"]

[project.scripts]
ms = "ms.cli:main"

[tool.pytest.ini_options]
testpaths = ["tests"]
```

`requirements.txt`:
```
pytest>=8
zeroconf>=0.132
```

`ms/__init__.py`:
```python
__version__ = "0.1.0"
```

`ms/hashing.py`:
```python
import hashlib
from pathlib import Path
from typing import Iterable

CHUNK = 1 << 20  # 1 MiB


def sha256_file(path: Path) -> str:
    """Full-file sha256 hex digest, streamed in 1 MiB chunks."""
    h = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(CHUNK):
            h.update(chunk)
    return h.hexdigest()


def fingerprint(path: Path) -> tuple[int, int]:
    """Fast change-detection fingerprint: (size, mtime_ns)."""
    st = path.stat()
    return st.st_size, st.st_mtime_ns


def tree_root_digest(entries: Iterable[tuple[str, str]]) -> str:
    """Deterministic digest over (path, sha256) pairs — the L3 root digest."""
    h = hashlib.sha256()
    for path, sha in sorted(entries):
        h.update(path.encode("utf-8"))
        h.update(b"\0")
        h.update(sha.encode("ascii"))
        h.update(b"\n")
    return h.hexdigest()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python -m pytest tests/test_hashing.py -v`
Expected: 4 PASS

- [ ] **Step 5: Commit**

```bash
git add pyproject.toml requirements.txt ms/ tests/
git commit -m "feat: scaffold project and hashing module"
```

---

## Task 2: SQLite data layer

**Files:**
- Create: `ms/db.py`
- Test: `tests/test_db.py`

**Interfaces:**
- Consumes: nothing (stdlib only)
- Produces:
  - `init_db(db_path: Path) -> sqlite3.Connection`
  - `manifest_get(conn, path) -> tuple[int, int, str, int] | None`  (size, mtime_ns, sha256, last_seen_ns)
  - `manifest_upsert(conn, path, size, mtime_ns, sha256, now_ns) -> None`
  - `manifest_delete(conn, path) -> None`
  - `manifest_all(conn) -> list[tuple]`  (path, size, mtime_ns, sha256, last_seen_ns)
  - `journal_append(conn, op, path, size, sha256, ts_ns, device) -> int`  (returns new id)
  - `journal_head(conn) -> int`  (max id, 0 when empty)
  - `journal_since(conn, after_id) -> list[dict]`  (keys: id, op, path, size, sha256, ts_ns, device)
  - `sync_state_get(conn, peer_device_id) -> tuple[int, int | None] | None`  (last_seen_journal_id, last_sync_ns)
  - `sync_state_set(conn, peer_device_id, last_seen_journal_id, last_sync_ns) -> None`

- [ ] **Step 1: Write the failing tests**

`tests/test_db.py`:
```python
import sqlite3
from pathlib import Path

from ms import db


def test_init_creates_schema(tmp_path: Path):
    conn = db.init_db(tmp_path / "t.db")
    tables = {r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table'")}
    assert {"manifest", "journal", "sync_state"} <= tables
    conn.close()


def test_manifest_roundtrip(tmp_path: Path):
    conn = db.init_db(tmp_path / "t.db")
    assert db.manifest_get(conn, "A.mp3") is None
    db.manifest_upsert(conn, "A.mp3", 10, 5, "abc", 100)
    assert db.manifest_get(conn, "A.mp3") == (10, 5, "abc", 100)
    db.manifest_delete(conn, "A.mp3")
    assert db.manifest_get(conn, "A.mp3") is None
    conn.close()


def test_journal_append_and_since(tmp_path: Path):
    conn = db.init_db(tmp_path / "t.db")
    assert db.journal_head(conn) == 0
    id1 = db.journal_append(conn, "CREATE", "A.mp3", 10, "abc", 100, "laptop")
    id2 = db.journal_append(conn, "DELETE", "A.mp3", None, None, 200, "phone")
    assert db.journal_head(conn) == id2
    since = db.journal_since(conn, id1)
    assert [op["op"] for op in since] == ["DELETE"]
    assert since[0]["path"] == "A.mp3"
    conn.close()


def test_sync_state_roundtrip(tmp_path: Path):
    conn = db.init_db(tmp_path / "t.db")
    assert db.sync_state_get(conn, "phone") is None
    db.sync_state_set(conn, "phone", 42, 999)
    assert db.sync_state_get(conn, "phone") == (42, 999)
    conn.close()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest tests/test_db.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'ms.db'`

- [ ] **Step 3: Write the implementation**

`ms/db.py`:
```python
import sqlite3
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS manifest (
    path        TEXT PRIMARY KEY,
    size        INTEGER NOT NULL,
    mtime_ns    INTEGER NOT NULL,
    sha256      TEXT,
    last_seen_ns INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS journal (
    id    INTEGER PRIMARY KEY AUTOINCREMENT,
    op    TEXT NOT NULL CHECK (op IN ('CREATE','MODIFY','DELETE')),
    path  TEXT NOT NULL,
    size  INTEGER,
    sha256 TEXT,
    ts_ns INTEGER NOT NULL,
    device TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS sync_state (
    peer_device_id       TEXT PRIMARY KEY,
    last_seen_journal_id INTEGER NOT NULL DEFAULT 0,
    last_sync_ns         INTEGER
);
"""


def init_db(db_path: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(str(db_path))
    conn.row_factory = sqlite3.Row
    conn.executescript(SCHEMA)
    conn.commit()
    return conn


def manifest_get(conn, path: str):
    row = conn.execute(
        "SELECT size, mtime_ns, sha256, last_seen_ns FROM manifest WHERE path=?",
        (path,)).fetchone()
    return None if row is None else (row["size"], row["mtime_ns"], row["sha256"], row["last_seen_ns"])


def manifest_upsert(conn, path: str, size: int, mtime_ns: int, sha256: str | None, now_ns: int) -> None:
    conn.execute(
        """INSERT INTO manifest (path, size, mtime_ns, sha256, last_seen_ns)
           VALUES (?,?,?,?,?)
           ON CONFLICT(path) DO UPDATE SET size=excluded.size,
             mtime_ns=excluded.mtime_ns, sha256=excluded.sha256,
             last_seen_ns=excluded.last_seen_ns""",
        (path, size, mtime_ns, sha256, now_ns))
    conn.commit()


def manifest_delete(conn, path: str) -> None:
    conn.execute("DELETE FROM manifest WHERE path=?", (path,))
    conn.commit()


def manifest_all(conn) -> list[tuple]:
    rows = conn.execute(
        "SELECT path, size, mtime_ns, sha256, last_seen_ns FROM manifest ORDER BY path").fetchall()
    return [(r["path"], r["size"], r["mtime_ns"], r["sha256"], r["last_seen_ns"]) for r in rows]


def journal_append(conn, op: str, path: str, size: int | None, sha256: str | None,
                   ts_ns: int, device: str) -> int:
    cur = conn.execute(
        "INSERT INTO journal (op, path, size, sha256, ts_ns, device) VALUES (?,?,?,?,?,?)",
        (op, path, size, sha256, ts_ns, device))
    conn.commit()
    return cur.lastrowid


def journal_head(conn) -> int:
    row = conn.execute("SELECT COALESCE(MAX(id), 0) AS m FROM journal").fetchone()
    return row["m"]


def journal_since(conn, after_id: int) -> list[dict]:
    rows = conn.execute(
        "SELECT id, op, path, size, sha256, ts_ns, device FROM journal WHERE id > ? ORDER BY id",
        (after_id,)).fetchall()
    return [dict(r) for r in rows]


def sync_state_get(conn, peer_device_id: str):
    row = conn.execute(
        "SELECT last_seen_journal_id, last_sync_ns FROM sync_state WHERE peer_device_id=?",
        (peer_device_id,)).fetchone()
    return None if row is None else (row["last_seen_journal_id"], row["last_sync_ns"])


def sync_state_set(conn, peer_device_id: str, last_seen_journal_id: int, last_sync_ns: int) -> None:
    conn.execute(
        """INSERT INTO sync_state (peer_device_id, last_seen_journal_id, last_sync_ns)
           VALUES (?,?,?)
           ON CONFLICT(peer_device_id) DO UPDATE SET
             last_seen_journal_id=excluded.last_seen_journal_id,
             last_sync_ns=excluded.last_sync_ns""",
        (peer_device_id, last_seen_journal_id, last_sync_ns))
    conn.commit()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python -m pytest tests/test_db.py -v`
Expected: 4 PASS

- [ ] **Step 5: Commit**

```bash
git add ms/db.py tests/test_db.py
git commit -m "feat: sqlite data layer (manifest, journal, sync_state)"
```

---

## Task 3: Change detection — scan

**Files:**
- Create: `ms/scan.py`
- Test: `tests/test_scan.py`

**Interfaces:**
- Consumes: `ms.db` (all functions), `ms.hashing.fingerprint`, `ms.hashing.sha256_file`
- Produces: `scan(root: Path, conn, device_id: str, now_ns: int) -> int` (returns journal head after scan)

Behavior (from spec §6): walk tree (skip dot-components, delete stale `.ms-partial-*`); new path → CREATE; same size+mtime → no-op; size/mtime changed → re-hash → same sha: update manifest only, different sha: MODIFY; manifest path missing on disk → DELETE.

- [ ] **Step 1: Write the failing tests**

`tests/test_scan.py`:
```python
import os
from pathlib import Path

from ms import db, scan


def _conn(tmp_path: Path):
    return db.init_db(tmp_path / "t.db")


def test_scan_detects_create(tmp_path: Path):
    root = tmp_path / "root"
    (root / "Rock").mkdir(parents=True)
    (root / "Rock" / "A.mp3").write_bytes(b"data")
    conn = _conn(tmp_path)
    scan.scan(root, conn, "laptop", 1000)
    ops = db.journal_since(conn, 0)
    assert [(o["op"], o["path"]) for o in ops] == [("CREATE", "Rock/A.mp3")]
    assert db.manifest_get(conn, "Rock/A.mp3")[2] is not None  # has sha256
    conn.close()


def test_scan_idempotent_no_new_ops(tmp_path: Path):
    root = tmp_path / "root"
    (root / "A.mp3").write_bytes(b"data")
    conn = _conn(tmp_path)
    scan.scan(root, conn, "laptop", 1000)
    head = db.journal_head(conn)
    scan.scan(root, conn, "laptop", 2000)
    assert db.journal_head(conn) == head  # unchanged -> no new ops
    conn.close()


def test_scan_detects_modify(tmp_path: Path):
    root = tmp_path / "root"
    p = root / "A.mp3"
    p.write_bytes(b"v1")
    conn = _conn(tmp_path)
    scan.scan(root, conn, "laptop", 1000)
    p.write_bytes(b"v2-changed")
    os.utime(p, ns=(p.stat().st_atime_ns, p.stat().st_mtime_ns + 10**9))
    scan.scan(root, conn, "laptop", 2000)
    ops = db.journal_since(conn, 1)
    assert [(o["op"], o["path"]) for o in ops] == [("MODIFY", "A.mp3")]
    conn.close()


def test_scan_detects_delete(tmp_path: Path):
    root = tmp_path / "root"
    p = root / "A.mp3"
    p.write_bytes(b"data")
    conn = _conn(tmp_path)
    scan.scan(root, conn, "laptop", 1000)
    p.unlink()
    scan.scan(root, conn, "laptop", 2000)
    ops = db.journal_since(conn, 1)
    assert [(o["op"], o["path"]) for o in ops] == [("DELETE", "A.mp3")]
    assert db.manifest_get(conn, "A.mp3") is None
    conn.close()


def test_scan_skips_dotfiles_and_cleans_partials(tmp_path: Path):
    root = tmp_path / "root"
    (root / ".hidden.mp3").write_bytes(b"x")
    (root / ".ms-partial-1234-A.mp3").write_bytes(b"junk")
    conn = _conn(tmp_path)
    scan.scan(root, conn, "laptop", 1000)
    assert not (root / ".ms-partial-1234-A.mp3").exists()  # crash residue removed
    assert db.journal_head(conn) == 0  # nothing journaled
    conn.close()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest tests/test_scan.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'ms.scan'`

- [ ] **Step 3: Write the implementation**

`ms/scan.py`:
```python
import os
from pathlib import Path

from ms import db
from ms.hashing import fingerprint, sha256_file


def scan(root: Path, conn, device_id: str, now_ns: int) -> int:
    """Diff disk vs manifest; journal CREATE/MODIFY/DELETE; return journal head."""
    disk: dict[str, Path] = {}
    for p in root.rglob("*"):
        if not p.is_file():
            continue
        if p.name.startswith(".ms-partial-"):
            p.unlink()  # crash residue
            continue
        rel = p.relative_to(root).as_posix()
        if any(part.startswith(".") for part in rel.split("/")):
            continue  # hidden file/dir: never mirrored
        disk[rel] = p

    for rel in sorted(disk):
        path = disk[rel]
        size, mtime_ns = fingerprint(path)
        row = db.manifest_get(conn, rel)
        if row is None:
            sha = sha256_file(path)
            db.journal_append(conn, "CREATE", rel, size, sha, now_ns, device_id)
            db.manifest_upsert(conn, rel, size, mtime_ns, sha, now_ns)
        elif row[0] == size and row[1] == mtime_ns:
            pass  # unchanged
        else:
            sha = sha256_file(path)
            if sha != row[2]:
                db.journal_append(conn, "MODIFY", rel, size, sha, now_ns, device_id)
            db.manifest_upsert(conn, rel, size, mtime_ns, sha, now_ns)

    for (rel,) in [m[:1] for m in db.manifest_all(conn)]:
        if rel not in disk:
            db.journal_append(conn, "DELETE", rel, None, None, now_ns, device_id)
            db.manifest_delete(conn, rel)

    return db.journal_head(conn)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python -m pytest tests/test_scan.py -v`
Expected: 5 PASS

- [ ] **Step 5: Commit**

```bash
git add ms/scan.py tests/test_scan.py
git commit -m "feat: scan-on-demand change detection"
```

---

## Task 4: Merge planning

**Files:**
- Create: `ms/merge.py`
- Test: `tests/test_merge.py`

**Interfaces:**
- Consumes: nothing (pure function)
- Produces:
  - `Plan` dataclass with fields: `fetch: list[tuple[str, int, str]]` (path, size, sha256), `push: list[tuple[str, int, str]]`, `delete: list[str]`, `conflict_loser: list[tuple[str, str, str]]` (path, ts_ns, sha256)
  - `build_plan(local_manifest: dict[str, tuple[int,int,str]], local_journal: list[dict], remote_manifest: dict[str, tuple[int,int,str]], remote_journal: list[dict], peer_cursor: int, our_device: str) -> Plan`

Manifest dict: `{path: (size, mtime_ns, sha256)}`. Journal: list of op dicts (from `db.journal_since`), ordered by id. `peer_cursor` = how much of OUR journal the peer has seen. `our_device` = our device id (used to ignore echo ops in the push-vs-delete decision).

- [ ] **Step 1: Write the failing tests**

`tests/test_merge.py`:
```python
from ms.merge import Plan, build_plan


def _m(path, sha, size=10, mtime=5):
    return {path: (size, mtime, sha)}


def test_remote_only_path_is_fetched():
    plan = build_plan({}, [], _m("B.mp3", "bbb"), [], 0, "me")
    assert plan.fetch == [("B.mp3", 10, "bbb")]
    assert plan.push == [] and plan.delete == [] and plan.conflict_loser == []


def test_identical_files_are_noop():
    plan = build_plan(_m("A.mp3", "aaa"), [], _m("A.mp3", "aaa"), [], 0, "me")
    assert plan.fetch == [] and plan.push == [] and plan.delete == []


def test_local_only_path_with_unseen_create_is_pushed():
    local_journal = [{"id": 5, "op": "CREATE", "path": "A.mp3", "size": 10,
                      "sha256": "aaa", "ts_ns": 100, "device": "me"}]
    plan = build_plan(_m("A.mp3", "aaa"), local_journal, {}, [], 0, "me")
    assert plan.push == [("A.mp3", 10, "aaa")]


def test_local_only_path_without_unseen_change_is_deleted():
    # peer already saw our CREATE (cursor 5); remote has since deleted it.
    local_journal = [{"id": 5, "op": "CREATE", "path": "A.mp3", "size": 10,
                      "sha256": "aaa", "ts_ns": 100, "device": "me"}]
    plan = build_plan(_m("A.mp3", "aaa"), local_journal, {}, [], 5, "me")
    assert plan.delete == ["A.mp3"]


def test_echo_ops_do_not_cause_wrong_push():
    # Remote deleted A.mp3; our journal only has an echo CREATE tagged remote.
    local_journal = [{"id": 5, "op": "CREATE", "path": "A.mp3", "size": 10,
                      "sha256": "aaa", "ts_ns": 100, "device": "phone"}]
    plan = build_plan(_m("A.mp3", "aaa"), local_journal, {}, [], 0, "me")
    assert plan.delete == ["A.mp3"] and plan.push == []


def test_conflict_remote_wins():
    local_journal = [{"id": 1, "op": "CREATE", "path": "A.mp3", "size": 10,
                      "sha256": "local", "ts_ns": 100, "device": "me"}]
    remote_journal = [{"id": 1, "op": "MODIFY", "path": "A.mp3", "size": 10,
                       "sha256": "remote", "ts_ns": 200, "device": "phone"}]
    plan = build_plan(_m("A.mp3", "local"), local_journal,
                      _m("A.mp3", "remote"), remote_journal, 0, "me")
    assert plan.fetch == [("A.mp3", 10, "remote")]          # take remote content
    assert plan.conflict_loser == [("A.mp3", 100, "local")]  # preserve our old bytes


def test_conflict_local_wins():
    local_journal = [{"id": 1, "op": "MODIFY", "path": "A.mp3", "size": 10,
                      "sha256": "local", "ts_ns": 300, "device": "me"}]
    remote_journal = [{"id": 1, "op": "MODIFY", "path": "A.mp3", "size": 10,
                       "sha256": "remote", "ts_ns": 200, "device": "phone"}]
    plan = build_plan(_m("A.mp3", "local"), local_journal,
                      _m("A.mp3", "remote"), remote_journal, 0, "me")
    assert plan.push == [("A.mp3", 10, "local")]
    assert plan.fetch == [] and plan.conflict_loser == []
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest tests/test_merge.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'ms.merge'`

- [ ] **Step 3: Write the implementation**

`ms/merge.py`:
```python
from dataclasses import dataclass, field


@dataclass
class Plan:
    fetch: list[tuple[str, int, str]] = field(default_factory=list)      # get bytes from remote
    push: list[tuple[str, int, str]] = field(default_factory=list)       # remote needs our bytes
    delete: list[str] = field(default_factory=list)
    conflict_loser: list[tuple[str, str, str]] = field(default_factory=list)  # (path, ts_ns, sha256)


def _latest_op_by_path(journal: list[dict]) -> dict[str, dict]:
    """Last op per path (journal is ordered by id; later wins)."""
    latest: dict[str, dict] = {}
    for op in journal:
        latest[op["path"]] = op
    return latest


def build_plan(local_manifest: dict, local_journal: list[dict],
               remote_manifest: dict, remote_journal: list[dict],
               peer_cursor: int, our_device: str) -> Plan:
    plan = Plan()
    local_latest = _latest_op_by_path(local_journal)
    remote_latest = _latest_op_by_path(remote_journal)
    local_sha = {p: v[2] for p, v in local_manifest.items()}
    remote_sha = {p: v[2] for p, v in remote_manifest.items()}

    for path, (size, _mtime, sha) in sorted(remote_manifest.items()):
        if path not in local_manifest:
            plan.fetch.append((path, size, sha))
        elif local_sha[path] == sha:
            continue
        else:  # both sides have different content
            local_ts = local_latest[path]["ts_ns"] if path in local_latest else 0
            remote_ts = remote_latest[path]["ts_ns"] if path in remote_latest else 0
            if local_ts > remote_ts:  # local wins; remote must take ours
                plan.push.append((path, *local_manifest[path][0::2] if False else
                                  (local_manifest[path][0], local_manifest[path][2])))
            else:  # remote wins; we take theirs, preserve ours
                plan.fetch.append((path, size, sha))
                plan.conflict_loser.append((path, local_ts, local_sha[path]))

    for path, (_size, _mtime, _sha) in sorted(local_manifest.items()):
        if path in remote_manifest:
            continue
        unseen_local_change = any(
            op["id"] > peer_cursor and op["op"] in ("CREATE", "MODIFY") and op["device"] == our_device
            for op in local_journal if op["path"] == path)
        if unseen_local_change:
            plan.push.append((path, local_manifest[path][0], local_manifest[path][2]))
        else:
            plan.delete.append(path)

    return plan
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python -m pytest tests/test_merge.py -v`
Expected: 8 PASS

- [ ] **Step 5: Commit**

```bash
git add ms/merge.py tests/test_merge.py
git commit -m "feat: LWW merge planning (push/fetch/delete/conflict)"
```

---

## Task 5: Apply — local mutations

**Files:**
- Create: `ms/apply.py`
- Test: `tests/test_apply.py`

**Interfaces:**
- Consumes: `ms.db`, `ms.hashing.sha256_file`, `ms.merge.Plan`
- Produces:
  - `apply_plan(root: Path, plan: Plan, conn, our_device: str, remote_device: str, remote_ops: dict[str, str], now_ns: int, fetch_bytes: Callable[[str], bytes]) -> dict`
  - `remote_ops` maps path → "CREATE"/"MODIFY" (the remote's op type, for journaling applied fetches).
  - Returns summary dict: `{"fetched": [...], "copied": [...], "deleted": [...], "conflicts": [...]}`.

Order: (1) create parent dirs; (2) for each fetch — content-addressed copy if the sha exists locally, else `fetch_bytes(path)`; write temp, verify sha256, atomic rename, journal + manifest; (3) conflict losers — rename the current local file to `.<basename>.sync-conflict-<ts>.<ext>`; (4) deletes last — unlink + manifest delete + journal. Journal only on actual mutations. Content-addressed copy is journaled as the fetch's op type.

- [ ] **Step 1: Write the failing tests**

`tests/test_apply.py`:
```python
import os
from pathlib import Path

from ms import apply, db
from ms.hashing import sha256_file
from ms.merge import Plan


def _conn(tmp_path: Path):
    return db.init_db(tmp_path / "t.db")


def test_apply_fetch_writes_verified_file(tmp_path: Path):
    root = tmp_path / "root"
    root.mkdir()
    conn = _conn(tmp_path)
    plan = Plan(fetch=[("Rock/A.mp3", 4, "abcd")])
    summary = apply.apply_plan(root, plan, conn, "me", "phone",
                               {"Rock/A.mp3": "CREATE"}, 1000, lambda p: b"abcd")
    assert (root / "Rock" / "A.mp3").read_bytes() == b"abcd"
    assert summary["fetched"] == ["Rock/A.mp3"]
    assert db.manifest_get(conn, "Rock/A.mp3")[2] == "abcd"
    ops = [o["op"] for o in db.journal_since(conn, 0)]
    assert ops == ["CREATE"]
    conn.close()


def test_apply_rejects_bad_sha(tmp_path: Path):
    root = tmp_path / "root"
    root.mkdir()
    conn = _conn(tmp_path)
    plan = Plan(fetch=[("A.mp3", 4, "abcd")])
    try:
        apply.apply_plan(root, plan, conn, "me", "phone", {"A.mp3": "CREATE"}, 1000,
                         lambda p: b"XXXX")
    except ValueError as e:
        assert "sha256" in str(e)
    else:
        raise AssertionError("expected ValueError")
    assert not (root / "A.mp3").exists()
    conn.close()


def test_apply_content_addressed_copy(tmp_path: Path):
    root = tmp_path / "root"
    (root / "Old").mkdir(parents=True)
    (root / "Old" / "song.mp3").write_bytes(b"same-content")
    conn = _conn(tmp_path)
    sha = sha256_file(root / "Old" / "song.mp3")
    db.manifest_upsert(conn, "Old/song.mp3", 12, 1, sha, 100)
    # Remote moved it: fetch path New/song.mp3 with the SAME sha.
    plan = Plan(fetch=[("New/song.mp3", 12, sha)])
    calls = []
    summary = apply.apply_plan(root, plan, conn, "me", "phone",
                               {"New/song.mp3": "CREATE"}, 1000,
                               lambda p: calls.append(p) or b"")
    assert (root / "New" / "song.mp3").read_bytes() == b"same-content"
    assert summary["copied"] == ["New/song.mp3"]
    assert calls == []  # no bytes fetched from remote
    conn.close()


def test_apply_delete_last_and_journaled(tmp_path: Path):
    root = tmp_path / "root"
    (root / "A.mp3").write_bytes(b"x")
    conn = _conn(tmp_path)
    db.manifest_upsert(conn, "A.mp3", 1, 1, "x", 100)
    summary = apply.apply_plan(root, Plan(delete=["A.mp3"]), conn, "me", "phone",
                               {}, 1000, lambda p: b"")
    assert not (root / "A.mp3").exists()
    assert summary["deleted"] == ["A.mp3"]
    assert db.manifest_get(conn, "A.mp3") is None
    assert [o["op"] for o in db.journal_since(conn, 0)] == ["DELETE"]
    conn.close()


def test_apply_conflict_loser_preserved_as_hidden_file(tmp_path: Path):
    root = tmp_path / "root"
    (root / "A.mp3").write_bytes(b"my-old-content")
    conn = _conn(tmp_path)
    db.manifest_upsert(conn, "A.mp3", 15, 1, "oldsha", 100)
    plan = Plan(fetch=[("A.mp3", 6, "newsha")],
                conflict_loser=[("A.mp3", "100", "oldsha")])
    summary = apply.apply_plan(root, plan, conn, "me", "phone",
                               {"A.mp3": "MODIFY"}, 1000, lambda p: b"newsha")
    assert (root / "A.mp3").read_bytes() == b"newsha"          # winner in place
    conflict_files = [f.name for f in root.iterdir() if f.name.startswith(".A.mp3.sync-conflict-")]
    assert len(conflict_files) == 1                            # loser preserved
    assert summary["conflicts"] == ["A.mp3"]
    conn.close()


def test_apply_idempotent_noop_journals_nothing(tmp_path: Path):
    root = tmp_path / "root"
    (root / "A.mp3").write_bytes(b"same")
    conn = _conn(tmp_path)
    sha = sha256_file(root / "A.mp3")
    db.manifest_upsert(conn, "A.mp3", 4, 1, sha, 100)
    plan = Plan(fetch=[("A.mp3", 4, sha)])
    apply.apply_plan(root, plan, conn, "me", "phone", {"A.mp3": "MODIFY"}, 1000,
                     lambda p: root.joinpath(p).read_bytes())
    assert db.journal_head(conn) == 0  # nothing new journaled
    conn.close()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest tests/test_apply.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'ms.apply'`

- [ ] **Step 3: Write the implementation**

`ms/apply.py`:
```python
import os
import secrets
from pathlib import Path
from typing import Callable

from ms import db
from ms.hashing import sha256_file
from ms.merge import Plan

PARTIAL_PREFIX = ".ms-partial-"


def _resolve(root: Path, rel: str) -> Path:
    """Path traversal guard: rel must resolve strictly inside root."""
    target = (root / rel).resolve()
    root_resolved = root.resolve()
    if root_resolved != target and root_resolved not in target.parents:
        raise ValueError(f"path escapes root: {rel}")
    return target


def _conflict_name(rel: str, ts_ns: str) -> str:
    p = Path(rel)
    return f".{p.name}.sync-conflict-{ts_ns}{p.suffix}"


def apply_plan(root: Path, plan: Plan, conn, our_device: str, remote_device: str,
               remote_ops: dict[str, str], now_ns: int,
               fetch_bytes: Callable[[str], bytes]) -> dict:
    summary: dict = {"fetched": [], "copied": [], "deleted": [], "conflicts": []}

    # Local sha lookup for content-addressed skip.
    local_sha_to_path: dict[str, str] = {}
    for (path, _s, _m, sha, _l) in db.manifest_all(conn):
        if sha:
            local_sha_to_path.setdefault(sha, path)

    # --- fetches (writes) ---
    for rel, _size, sha in sorted(plan.fetch):
        target = _resolve(root, rel)
        src_rel = local_sha_to_path.get(sha)
        if src_rel and src_rel != rel:
            src = _resolve(root, src_rel)
            if src.is_file():  # content-addressed copy: no transfer
                target.parent.mkdir(parents=True, exist_ok=True)
                tmp = target.parent / f"{PARTIAL_PREFIX}{secrets.token_hex(4)}-{target.name}"
                tmp.write_bytes(src.read_bytes())
                os.replace(tmp, target)
                summary["copied"].append(rel)
                db.journal_append(conn, remote_ops.get(rel, "CREATE"), rel,
                                  target.stat().st_size, sha, now_ns, remote_device)
                db.manifest_upsert(conn, rel, target.stat().st_size,
                                   target.stat().st_mtime_ns, sha, now_ns)
                continue
        data = fetch_bytes(rel)
        if sha256_file_bytes(data) != sha:
            raise ValueError(f"sha256 mismatch after transfer: {rel}")
        target.parent.mkdir(parents=True, exist_ok=True)
        tmp = target.parent / f"{PARTIAL_PREFIX}{secrets.token_hex(4)}-{target.name}"
        tmp.write_bytes(data)
        os.replace(tmp, target)
        summary["fetched"].append(rel)
        db.journal_append(conn, remote_ops.get(rel, "CREATE"), rel,
                          len(data), sha, now_ns, remote_device)
        db.manifest_upsert(conn, rel, len(data), target.stat().st_mtime_ns, sha, now_ns)

    # --- conflict losers (preserve our old bytes as hidden file) ---
    for rel, ts_ns, _sha in plan.conflict_loser:
        target = _resolve(root, rel)
        if target.is_file():
            os.replace(target, target.parent / _conflict_name(rel, ts_ns))
            summary["conflicts"].append(rel)

    # --- deletes last ---
    for rel in sorted(plan.delete):
        target = _resolve(root, rel)
        if target.is_file():
            target.unlink()
            summary["deleted"].append(rel)
            db.journal_append(conn, "DELETE", rel, None, None, now_ns, remote_device)
            db.manifest_delete(conn, rel)

    return summary


def sha256_file_bytes(data: bytes) -> str:
    import hashlib
    return hashlib.sha256(data).hexdigest()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python -m pytest tests/test_apply.py -v`
Expected: 6 PASS

- [ ] **Step 5: Commit**

```bash
git add ms/apply.py tests/test_apply.py
git commit -m "feat: apply engine — verified writes, content-addressed copy, deletes-last, conflict files"
```

---

## Task 6: HTTP server

**Files:**
- Create: `ms/server.py`
- Test: `tests/test_server.py`

**Interfaces:**
- Consumes: `ms.db`, `ms.scan.scan`, `ms.merge.build_plan`, `ms.apply.apply_plan`
- Produces:
  - `SyncServer(root: Path, db_path: Path, device_id: str, schema_version: int = 1)` — thread-safe server object with `serve_forever()` / `shutdown()` and a `port` attribute.
  - Endpoints:
    - `GET /handshake?device_id=<id>` → `{schema_version, server_device_id, server_journal_head, client_cursor}`
    - `POST /sync` body `{device_id, server_cursor, journal_ops, manifest}` → `{schema_version, server_journal_ops, server_manifest, needs_push, server_journal_head}`
    - `GET /manifest` → `[[path, size, mtime_ns, sha256], ...]`
    - `GET /journal?after=<id>` → `[op dicts, ...]`
    - `GET /file?path=<rel>` → raw bytes (404 if absent)
    - `POST /file?path=<rel>&sha=<hex>` → verify sha, write temp+rename, journal as `remote_ops[path]`, 200 `{"ok": true}`; 400 on mismatch
    - `POST /done` body `{device_id, client_journal_head}` → updates server sync_state, 200
  - Server holds its own engine state: DB connection + a lock; the journal/manifest it serves reflect its DB.
  - The server does NOT mutate its tree during `/sync` (the client pushes files; server applies deletes+conflicts at `/done`).

- [ ] **Step 1: Write the failing tests**

`tests/test_server.py`:
```python
import json
import threading
import urllib.request
import urllib.parse
from pathlib import Path

from ms import db
from ms.server import SyncServer


@pytest.fixture  # noqa: F821 (pytest fixture)
def server(tmp_path: Path):
    root = tmp_path / "root"
    root.mkdir()
    (root / "Rock").mkdir()
    (root / "Rock" / "A.mp3").write_bytes(b"content-a")
    db_path = tmp_path / "s.db"
    conn = db.init_db(db_path)
    db.journal_append(conn, "CREATE", "Rock/A.mp3", 9, "sha", 1, "laptop")
    db.manifest_upsert(conn, "Rock/A.mp3", 9, 1, "sha", 1)
    conn.close()
    srv = SyncServer(root, db_path, "laptop", schema_version=1)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    yield srv
    srv.shutdown()
    t.join(timeout=5)


def _get(url: str) -> dict:
    with urllib.request.urlopen(url) as r:
        return json.loads(r.read())


def test_handshake(server):
    data = _get(f"http://127.0.0.1:{server.port}/handshake?device_id=phone")
    assert data["schema_version"] == 1
    assert data["server_device_id"] == "laptop"
    assert data["client_cursor"] == 0  # never synced


def test_manifest(server):
    data = _get(f"http://127.0.0.1:{server.port}/manifest")
    assert data == [["Rock/A.mp3", 9, 1, "sha"]]


def test_journal_since(server):
    data = _get(f"http://127.0.0.1:{server.port}/journal?after=0")
    assert len(data) == 1 and data[0]["path"] == "Rock/A.mp3"


def test_get_file(server):
    with urllib.request.urlopen(
            f"http://127.0.0.1:{server.port}/file?path={urllib.parse.quote('Rock/A.mp3')}") as r:
        assert r.read() == b"content-a"


def test_post_file_verifies_sha(server):
    import urllib.request
    body = b"new-bytes"
    sha = __import__("hashlib").sha256(body).hexdigest()
    req = urllib.request.Request(
        f"http://127.0.0.1:{server.port}/file?path={urllib.parse.quote('Rock/B.mp3')}&sha={sha}",
        data=body, method="POST")
    with urllib.request.urlopen(req) as r:
        assert json.loads(r.read())["ok"] is True
    assert (server.root / "Rock" / "B.mp3").read_bytes() == b"new-bytes"


def test_post_file_rejects_bad_sha(server):
    import urllib.request
    req = urllib.request.Request(
        f"http://127.0.0.1:{server.port}/file?path={urllib.parse.quote('Rock/C.mp3')}&sha=deadbeef",
        data=b"anything", method="POST")
    import urllib.error
    with pytest.raises(urllib.error.HTTPError):  # noqa: F821
        urllib.request.urlopen(req)


def test_post_sync_returns_server_state(server):
    body = json.dumps({
        "device_id": "phone", "server_cursor": 0,
        "journal_ops": [], "manifest": [],
    }).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{server.port}/sync", data=body,
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req) as r:
        data = json.loads(r.read())
    assert data["server_journal_ops"] == []
    assert data["server_manifest"] == [["Rock/A.mp3", 9, 1, "sha"]]
```

Note: `tests/conftest.py` must define the `pytest` fixture import — add at the top of `tests/conftest.py` (created in Task 1):
```python
import pytest  # noqa: F401
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest tests/test_server.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'ms.server'`

- [ ] **Step 3: Write the implementation**

`ms/server.py`:
```python
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
        self._conn = db.init_db(db_path)
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
```

**Protocol note (important for Task 7):** the server does NOT run its own merge in v1. The client (phone, or the test harness) drives the session and computes BOTH sides' plans. The server is a file store + journal source plus a small mutation executor: `/sync` runs a server-side scan (so files the phone pushed get journaled/manifested, and re-pushes stop after one session), and `/done` carries the server's deletions and conflict-preservations for the server to apply — this is what makes **phone-side deletes propagate to the laptop**. Symmetric self-planning (spec §7) can replace this later without protocol changes.

- [ ] **Step 4: Run tests to verify they pass**

Run: `python -m pytest tests/test_server.py -v`
Expected: 7 PASS

- [ ] **Step 5: Commit**

```bash
git add ms/server.py tests/test_server.py
git commit -m "feat: threaded HTTP server (handshake/sync/manifest/journal/file/done)"
```

---

## Task 7: Sync client + integration tests (the correctness crown jewel)

**Files:**
- Create: `ms/client.py`
- Test: `tests/test_sync_integration.py`

**Interfaces:**
- Consumes: everything so far
- Produces: `run_sync_session(server_url: str, root: Path, db_path: Path, our_device: str) -> dict`
  - Summary dict: `{"fetched": [...], "copied": [...], "pushed": [...], "deleted": [...], "conflicts": [...]}`.
  - Flow: handshake → local scan → POST /sync → build_plan → apply_plan (fetch via GET /file, content-addressed copies, deletes, conflicts) → push `needs_push`… wait — in the simplified protocol the CLIENT computes both sides' plans. The client determines what the SERVER needs (paths in client manifest absent from server manifest that the client changed unseen) and pushes those files. The client's own deletes (paths the server deleted) are applied client-side. The server's deletes are applied by the server's own scan on its next run.
  - Specifically: client plan = build_plan(client_manifest, client_ops_after_server_cursor, server_manifest, server_ops_after_client_cursor, client_cursor_for_server, our_device). Client applies it (fetch/delete/conflict). Client computes the SERVER's plan = build_plan(server_manifest, server_ops_after_client_cursor, client_manifest, client_ops_after_server_cursor, server_cursor_for_client, server_device) and pushes `server_plan.fetch` files. Then POST /done, update sync_state.

- [ ] **Step 1: Write the failing tests**

`tests/test_sync_integration.py`:
```python
import os
import threading
from pathlib import Path

import pytest

from ms import db, scan
from ms.client import run_sync_session
from ms.hashing import sha256_file
from ms.server import SyncServer


@pytest.fixture
def pair(tmp_path: Path):
    """Two engines: server root A (device 'laptop'), client root B (device 'phone')."""
    root_a = tmp_path / "A"
    root_b = tmp_path / "B"
    root_a.mkdir(); root_b.mkdir()
    srv = SyncServer(root_a, tmp_path / "s.db", "laptop")
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    yield root_a, root_b, srv
    srv.shutdown()
    t.join(timeout=5)


def _client_scan(root_b: Path, tmp_path: Path):
    db_path = tmp_path / "c.db"
    conn = db.init_db(db_path)
    scan.scan(root_b, conn, "phone", 1)
    return db_path


def _sync(root_b: Path, db_path: Path, srv, tmp_path: Path):
    return run_sync_session(f"http://127.0.0.1:{srv.port}", root_b, db_path, "phone")


def test_one_way_create_propagates(pair, tmp_path):
    root_a, root_b, srv = pair
    (root_a / "Rock").mkdir()
    (root_a / "Rock" / "A.mp3").write_bytes(b"content-a")
    scan.scan(root_a, srv._conn, "laptop", 1)
    db_path = _client_scan(root_b, tmp_path)
    s = _sync(root_b, db_path, srv, tmp_path)
    assert s["fetched"] == ["Rock/A.mp3"]
    assert (root_b / "Rock" / "A.mp3").read_bytes() == b"content-a"
    assert sha256_file(root_a / "Rock" / "A.mp3") == sha256_file(root_b / "Rock" / "A.mp3")


def test_bidirectional_creates(pair, tmp_path):
    root_a, root_b, srv = pair
    (root_a / "A.mp3").write_bytes(b"from-laptop")
    (root_b / "B.mp3").write_bytes(b"from-phone")
    scan.scan(root_a, srv._conn, "laptop", 1)
    db_path = _client_scan(root_b, tmp_path)
    _sync(root_b, db_path, srv, tmp_path)
    assert (root_a / "A.mp3").exists() and (root_b / "A.mp3").exists()
    assert (root_a / "B.mp3").read_bytes() == b"from-phone"
    assert (root_b / "B.mp3").read_bytes() == b"from-phone"


def test_delete_propagates(pair, tmp_path):
    root_a, root_b, srv = pair
    (root_a / "A.mp3").write_bytes(b"x")
    scan.scan(root_a, srv._conn, "laptop", 1)
    db_path = _client_scan(root_b, tmp_path)
    _sync(root_b, db_path, srv, tmp_path)          # initial sync
    (root_a / "A.mp3").unlink()
    scan.scan(root_a, srv._conn, "laptop", 2)
    db_path2 = _client_scan(root_b, tmp_path)
    _sync(root_b, db_path2, srv, tmp_path)          # server deleted it; client must too
    assert not (root_b / "A.mp3").exists()


def test_phone_delete_propagates_to_laptop(pair, tmp_path):
    root_a, root_b, srv = pair
    (root_a / "A.mp3").write_bytes(b"x")
    scan.scan(root_a, srv._conn, "laptop", 1)
    db_path = _client_scan(root_b, tmp_path)
    _sync(root_b, db_path, srv, tmp_path)          # initial sync: both have A.mp3
    (root_b / "A.mp3").unlink()                    # phone deletes (e.g. via Samsung Music)
    db_path2 = _client_scan(root_b, tmp_path)
    _sync(root_b, db_path2, srv, tmp_path)
    assert not (root_a / "A.mp3").exists()         # laptop must delete too


def test_phone_move_propagates(pair, tmp_path):
    root_a, root_b, srv = pair
    (root_a / "Rock").mkdir()
    (root_a / "Rock" / "Song.mp3").write_bytes(b"content")
    scan.scan(root_a, srv._conn, "laptop", 1)
    db_path = _client_scan(root_b, tmp_path)
    _sync(root_b, db_path, srv, tmp_path)          # initial: both have Rock/Song.mp3
    (root_b / "Fav").mkdir()
    os.replace(root_b / "Rock" / "Song.mp3", root_b / "Fav" / "Song.mp3")  # phone moves it
    db_path2 = _client_scan(root_b, tmp_path)
    _sync(root_b, db_path2, srv, tmp_path)
    assert (root_a / "Fav" / "Song.mp3").exists()       # laptop gets it at new path
    assert not (root_a / "Rock" / "Song.mp3").exists()  # old path gone


def test_conflict_remote_wins_and_converges(pair, tmp_path):
    root_a, root_b, srv = pair
    (root_a / "A.mp3").write_bytes(b"base")
    scan.scan(root_a, srv._conn, "laptop", 1)
    db_path = _client_scan(root_b, tmp_path)
    _sync(root_b, db_path, srv, tmp_path)           # both at "base"
    (root_a / "A.mp3").write_bytes(b"laptop-edit")
    os.utime(root_a / "A.mp3", ns=(1, 3_000_000_000))  # newer
    scan.scan(root_a, srv._conn, "laptop", 3_000_000_000)
    (root_b / "A.mp3").write_bytes(b"phone-edit")
    os.utime(root_b / "A.mp3", ns=(1, 2_000_000_000))  # older
    db_path2 = _client_scan(root_b, tmp_path)
    s = _sync(root_b, db_path2, srv, tmp_path)
    assert (root_b / "A.mp3").read_bytes() == b"laptop-edit"   # newer wins
    assert len(s["conflicts"]) == 1                            # phone's old bytes preserved
    assert any(f.name.startswith(".A.mp3.sync-conflict-") for f in root_b.iterdir())


def test_move_cost_zero_transfer(pair, tmp_path, monkeypatch):
    root_a, root_b, srv = pair
    (root_a / "Rock").mkdir()
    (root_a / "Rock" / "Song.mp3").write_bytes(b"same-content")
    scan.scan(root_a, srv._conn, "laptop", 1)
    db_path = _client_scan(root_b, tmp_path)
    _sync(root_b, db_path, srv, tmp_path)           # initial: full transfer
    # Laptop moves the song to a new playlist folder (delete + create, same content).
    (root_a / "Fav").mkdir()
    os.replace(root_a / "Rock" / "Song.mp3", root_a / "Fav" / "Song.mp3")
    scan.scan(root_a, srv._conn, "laptop", 2)
    db_path2 = _client_scan(root_b, tmp_path)
    # Count actual byte transfers during the second sync.
    import ms.client as client_mod
    original = client_mod._http_get_bytes
    transfers: list[str] = []
    def counting_get(url):
        transfers.append(url)
        return original(url)
    monkeypatch.setattr(client_mod, "_http_get_bytes", counting_get)
    _sync(root_b, db_path2, srv, tmp_path)
    assert (root_b / "Fav" / "Song.mp3").read_bytes() == b"same-content"
    assert not (root_b / "Rock" / "Song.mp3").exists()
    assert transfers == []  # content-addressed copy: zero bytes over the wire


def test_second_sync_is_noop(pair, tmp_path):
    root_a, root_b, srv = pair
    (root_a / "A.mp3").write_bytes(b"x")
    scan.scan(root_a, srv._conn, "laptop", 1)
    db_path = _client_scan(root_b, tmp_path)
    _sync(root_b, db_path, srv, tmp_path)
    db_path2 = _client_scan(root_b, tmp_path)
    s = _sync(root_b, db_path2, srv, tmp_path)
    assert s["fetched"] == [] and s["deleted"] == [] and s["pushed"] == []
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest tests/test_sync_integration.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'ms.client'`

- [ ] **Step 3: Write the implementation**

`ms/client.py`:
```python
import json
import time
import urllib.parse
import urllib.request
from pathlib import Path

from ms import apply, db, merge, scan as scan_mod

SCHEMA_VERSION = 1


def _http_json(url: str, method: str = "GET", body: bytes | None = None) -> dict:
    req = urllib.request.Request(url, data=body, method=method,
                                 headers={"Content-Type": "application/json"} if body else {})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())


def _http_get_bytes(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def run_sync_session(server_url: str, root: Path, db_path: Path, our_device: str) -> dict:
    conn = db.init_db(db_path)
    now_ns = time.time_ns()

    # 1. Handshake: learn server id + how much of OUR journal the server has seen.
    hs = _http_json(f"{server_url}/handshake?device_id={urllib.parse.quote(our_device)}")
    server_device = hs["server_device_id"]
    server_cursor_for_us = hs["client_cursor"]            # server.sync_state[us]
    server_journal_head = hs["server_journal_head"]

    # 2. Local scan (fresh change detection on demand), then gather our state.
    scan_mod.scan(root, conn, our_device, now_ns)
    our_cursor_for_server = db.sync_state_get(conn, server_device)  # what we've seen of server
    our_cursor = our_cursor_for_server[0] if our_cursor_for_server else 0
    our_ops = db.journal_since(conn, server_cursor_for_us)
    our_manifest = {p: (s, m, h) for (p, s, m, h, _l) in db.manifest_all(conn)}

    # 3. Submit state; receive server journal + manifest.
    body = json.dumps({
        "device_id": our_device,
        "server_cursor": our_cursor,
        "journal_ops": our_ops,
        "manifest": [[p, s, m, h] for (p, s, m, h) in our_manifest.items() for (s, m, h) in [our_manifest[p]]],
    }).encode()
    resp = _http_json(f"{server_url}/sync", method="POST", body=body)
    server_ops = resp["server_journal_ops"]
    server_manifest = {p: (s, m, h) for (p, s, m, h) in resp["server_manifest"]}

    # 4. OUR plan: what we must fetch / delete / conflict-preserve.
    plan = merge.build_plan(our_manifest, our_ops, server_manifest, server_ops,
                            our_cursor, our_device)
    remote_ops = {op["path"]: op["op"] for op in server_ops}
    summary = apply.apply_plan(root, plan, conn, our_device, server_device, remote_ops,
                               now_ns, lambda rel: _http_get_bytes(
                                   f"{server_url}/file?path={urllib.parse.quote(rel)}"))

    # 5. SERVER's plan: what the server needs (fetch = push to it; delete/conflict = applied at /done).
    server_plan = merge.build_plan(server_manifest, server_ops,
                                   our_manifest, our_ops, server_cursor_for_us,
                                   server_device)
    pushed: list[str] = []
    for rel, _size, sha in server_plan.fetch:
        data = (root / rel).read_bytes()
        url = f"{server_url}/file?path={urllib.parse.quote(rel)}&sha={sha}"
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
    return summary
```

**Note on `test_one_way_create_propagates`:** the server-side scan in that test is done via the server's own connection (the test reaches into `srv._conn`). In production the laptop runs `ms scan` (Task 9) before serving. The `server_plan` in step 5 uses `server_cursor_for_us` as the server's peer-cursor (what the server has seen of our journal) — the same value the handshake returned, and which the server's `/done` will advance to our new head.

- [ ] **Step 4: Run tests to verify they pass**

Run: `python -m pytest tests/test_sync_integration.py -v`
Expected: 6 PASS (this is the correctness gate — if any fail, fix the engine, not the tests)

- [ ] **Step 5: Commit**

```bash
git add ms/client.py tests/test_sync_integration.py
git commit -m "feat: sync client session + integration tests (create/delete/conflict/move/idempotent)"
```

---

## Task 8: mDNS discovery + config

**Files:**
- Create: `ms/discovery.py`, `ms/config.py`
- Test: `tests/test_config.py`, `tests/test_discovery.py`

**Interfaces:**
- Produces:
  - `advertise(device_id: str, port: int) -> context manager` (registers `music-syncer._tcp` with props `{device_id}`)
  - `discover(timeout: float = 3.0) -> list[dict]` → `[{"device_id": ..., "address": str, "port": int}, ...]`
  - `load_config(path: Path | None) -> dict` → `{"base_path": str | None, "port": int, "db_path": Path | None}`
  - Defaults: port 8756, config at `~/.config/music-syncer/config.toml` (created from defaults if missing).

- [ ] **Step 1: Write the failing tests**

`tests/test_config.py`:
```python
from pathlib import Path

from ms import config


def test_load_config_defaults_when_missing(tmp_path: Path):
    cfg = config.load_config(tmp_path / "nope.toml")
    assert cfg["port"] == 8756


def test_load_config_reads_toml(tmp_path: Path):
    p = tmp_path / "c.toml"
    p.write_text('base_path = "/music"\nport = 9000\n')
    cfg = config.load_config(p)
    assert cfg["base_path"] == "/music"
    assert cfg["port"] == 9000
```

`tests/test_discovery.py`:
```python
import ms.discovery as disc


def test_advertise_and_discover_localhost():
    with disc.advertise("laptop", 8756):
        found = disc.discover(timeout=2.0)
    assert any(f["device_id"] == "laptop" for f in found)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest tests/test_config.py tests/test_discovery.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'ms.config'`

- [ ] **Step 3: Write the implementation**

`ms/config.py`:
```python
import os
import tomllib
from pathlib import Path

DEFAULT_PORT = 8756


def _default_path() -> Path:
    return Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "music-syncer" / "config.toml"


def load_config(path: Path | None = None) -> dict:
    p = path or _default_path()
    cfg: dict = {"base_path": None, "port": DEFAULT_PORT, "db_path": None}
    if p.exists():
        with p.open("rb") as f:
            data = tomllib.load(f)
        cfg.update({k: v for k, v in data.items() if k in cfg})
    if cfg["db_path"] is None:
        cfg["db_path"] = str(p.parent.parent / "music-syncer.db")  # ~/.local/share/music-syncer.db
    return cfg
```

`ms/discovery.py`:
```python
import socket
from contextlib import contextmanager
from typing import Iterator

from zeroconf import ServiceInfo, Zeroconf, ServiceBrowser, ServiceListener

SERVICE_TYPE = "_music-syncer._tcp.local."
SERVICE_NAME = "music-syncer"


@contextmanager
def advertise(device_id: str, port: int) -> Iterator[None]:
    zc = Zeroconf()
    info = ServiceInfo(
        SERVICE_TYPE, f"{SERVICE_NAME}.{SERVICE_TYPE}",
        addresses=[socket.inet_aton("0.0.0.0")], port=port,
        properties={"device_id": device_id})
    zc.register_service(info)
    try:
        yield
    finally:
        zc.unregister_service(info)
        zc.close()


class _Listener(ServiceListener):
    def __init__(self):
        self.found: list[dict] = []

    def add_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        info = zc.get_service_info(type_, name)
        if info:
            self.found.append({
                "device_id": (info.properties.get(b"device_id") or b"").decode(),
                "address": socket.inet_ntoa(info.addresses[0]) if info.addresses else "",
                "port": info.port,
            })

    def update_service(self, zc, type_, name):  # noqa: D102
        self.add_service(zc, type_, name)

    def remove_service(self, zc, type_, name):  # noqa: D102
        pass


def discover(timeout: float = 3.0) -> list[dict]:
    zc = Zeroconf()
    listener = _Listener()
    ServiceBrowser(zc, SERVICE_TYPE, listener)
    try:
        import time
        time.sleep(timeout)
    finally:
        zc.close()
    return listener.found
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python -m pytest tests/test_config.py tests/test_discovery.py -v`
Expected: 3 PASS (if `test_advertise_and_discover_localhost` is flaky on a network-less CI, mark it `@pytest.mark.integration` and skip with `-m "not integration"` — the laptop run will exercise it for real)

- [ ] **Step 5: Commit**

```bash
git add ms/discovery.py ms/config.py tests/test_config.py tests/test_discovery.py
git commit -m "feat: mDNS advertise/discover and TOML config"
```

---

## Task 9: CLI

**Files:**
- Create: `ms/cli.py`
- Test: `tests/test_cli.py`

**Interfaces:**
- Consumes: everything
- Produces: `main(argv: list[str] | None = None) -> int` — entry point for `ms`.
  - `ms scan --path DIR` — scan + journal; prints ops added.
  - `ms serve [--path DIR] [--port N]` — scan, then run server (with mDNS advertise). Ctrl-C to stop.
  - `ms log [--limit N]` — print readable journal lines.
  - `ms verify --path DIR` — L5: full re-hash of the tree vs stored manifest; prints mismatches.

- [ ] **Step 1: Write the failing tests**

`tests/test_cli.py`:
```python
import io
import json
from contextlib import redirect_stdout
from pathlib import Path

from ms import cli, db


def test_scan_command(tmp_path: Path, capsys):
    root = tmp_path / "root"
    (root / "A.mp3").write_bytes(b"x")
    cfg = {"base_path": str(root), "port": 8756, "db_path": str(tmp_path / "t.db")}
    code = cli.main(["scan", "--path", str(root), "--db", str(tmp_path / "t.db")])
    out = capsys.readouterr().out
    assert code == 0
    assert "CREATE" in out and "A.mp3" in out


def test_log_command(tmp_path: Path, capsys):
    conn = db.init_db(tmp_path / "t.db")
    db.journal_append(conn, "MODIFY", "Rock/A.mp3", 5, "sha", 1000, "phone")
    conn.close()
    code = cli.main(["log", "--db", str(tmp_path / "t.db")])
    out = capsys.readouterr().out
    assert code == 0
    assert "MODIFY" in out and "Rock/A.mp3" in out and "phone" in out


def test_verify_clean_tree(tmp_path: Path, capsys):
    root = tmp_path / "root"
    (root / "A.mp3").write_bytes(b"data")
    cli.main(["scan", "--path", str(root), "--db", str(tmp_path / "t.db")])
    code = cli.main(["verify", "--path", str(root), "--db", str(tmp_path / "t.db")])
    out = capsys.readouterr().out
    assert code == 0
    assert "OK" in out


def test_verify_detects_tamper(tmp_path: Path, capsys):
    root = tmp_path / "root"
    p = root / "A.mp3"
    p.write_bytes(b"data")
    cli.main(["scan", "--path", str(root), "--db", str(tmp_path / "t.db")])
    p.write_bytes(b"TAMPERED!")
    code = cli.main(["verify", "--path", str(root), "--db", str(tmp_path / "t.db")])
    out = capsys.readouterr().out
    assert code == 0
    assert "MISMATCH" in out and "A.mp3" in out
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest tests/test_cli.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'ms.cli'`

- [ ] **Step 3: Write the implementation**

`ms/cli.py`:
```python
import argparse
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from ms import config as config_mod
from ms import db, discovery, scan as scan_mod, server as server_mod


def _args() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="ms")
    p.add_argument("--db", default=None, help="override DB path")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("scan").add_argument("--path", required=True)
    serve = sub.add_parser("serve")
    serve.add_argument("--path", required=True)
    serve.add_argument("--port", type=int, default=None)
    sub.add_parser("log").add_argument("--limit", type=int, default=50)
    sub.add_parser("verify").add_argument("--path", required=True)
    return p


def main(argv: list[str] | None = None) -> int:
    args = _args().parse_args(argv)
    cfg = config_mod.load_config()
    db_path = Path(args.db) if args.db else Path(cfg["db_path"])

    if args.cmd == "scan":
        root = Path(args.path)
        conn = db.init_db(db_path)
        head_before = db.journal_head(conn)
        scan_mod.scan(root, conn, "laptop", time.time_ns())
        for op in db.journal_since(conn, head_before):
            ts = datetime.fromtimestamp(op["ts_ns"] / 1e9, tz=timezone.utc).isoformat(timespec="seconds")
            print(f"{ts}  laptop  {op['op']:<6} {op['path']}")
        conn.close()
        return 0

    if args.cmd == "log":
        conn = db.init_db(db_path)
        for op in db.journal_since(conn, 0)[-args.limit:]:
            ts = datetime.fromtimestamp(op["ts_ns"] / 1e9, tz=timezone.utc).isoformat(timespec="seconds")
            print(f"{ts}  {op['device']:<8} {op['op']:<6} {op['path']}")
        conn.close()
        return 0

    if args.cmd == "verify":
        root = Path(args.path)
        conn = db.init_db(db_path)
        scan_mod.scan(root, conn, "laptop", time.time_ns())
        mismatches = []
        for (rel, size, mtime_ns, sha, _l) in db.manifest_all(conn):
            p = root / rel
            if not p.is_file():
                mismatches.append(rel)
                continue
            from ms.hashing import sha256_file
            if sha256_file(p) != sha:
                mismatches.append(rel)
        if mismatches:
            for rel in mismatches:
                print(f"MISMATCH  {rel}")
        else:
            print("OK: all files match stored hashes")
        conn.close()
        return 0

    if args.cmd == "serve":
        root = Path(args.path)
        port = args.port or cfg["port"]
        conn = db.init_db(db_path)
        scan_mod.scan(root, conn, "laptop", time.time_ns())
        conn.close()
        print(f"music-syncer serving {root} on :{port} — Ctrl-C to stop")
        srv = server_mod.SyncServer(root, db_path, "laptop")
        with discovery.advertise("laptop", srv.port):
            try:
                srv.serve_forever()
            except KeyboardInterrupt:
                print("\nstopped")
        return 0

    return 1


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python -m pytest tests/test_cli.py -v`
Expected: 4 PASS

- [ ] **Step 5: Commit**

```bash
git add ms/cli.py tests/test_cli.py
git commit -m "feat: ms CLI (scan/serve/log/verify)"
```

---

## Task 10: Real-library smoke test + README

**Files:**
- Create: `README.md`
- Test: `tests/test_cli.py` (extended: `--help` renders)

**Interfaces:** none (operational verification)

- [ ] **Step 1: Add a `--help` smoke test**

Append to `tests/test_cli.py`:
```python
def test_help_renders(capsys):
    code = cli.main(["--help"])
    out = capsys.readouterr().out
    assert code == 0 and "scan" in out and "serve" in out
```

- [ ] **Step 2: Run the suite + real-library smoke**

Run: `python -m pytest -v`
Expected: all PASS

Run against the real library (read-only — scan only, no sync):
```bash
python -m ms.cli scan --path "/media/shaarky/Data/Shaarav/my songs/" --db /tmp/ms-smoke.db
```
Expected: prints ~6,050 CREATE lines (one per mp3); second run prints nothing (idempotent). If the first run is slow, that is the expected one-time full-hash cost.

- [ ] **Step 3: Write README**

`README.md`:
```markdown
# music-syncer

Bidirectional wireless mirror of a music library between a Linux laptop and an
Android phone. Records every change in a timestamped journal; syncs when you
tell it to.

See `docs/superpowers/specs/2026-09-28-music-sync-engine-design.md` for the
full design.

## Usage (laptop side)

    pip install -r requirements.txt
    python -m ms.cli scan --path "/media/shaarky/Data/Shaarav/my songs/"
    python -m ms.cli serve --path "/media/shaarky/Data/Shaarav/my songs/"
    python -m ms.cli log
    python -m ms.cli verify --path "/media/shaarky/Data/Shaarav/my songs/"

Suggested aliases:

    alias msscan='python -m ms.cli scan --path "/media/shaarky/Data/Shaarav/my songs/"'
    alias msserve='python -m ms.cli serve --path "/media/shaarky/Data/Shaarav/my songs/"'

## Status

Engine + Linux daemon implemented. Android app (Plan 2) pending — it ports the
same engine semantics (scan → journal → merge → apply) over mDNS + HTTP.
```

- [ ] **Step 4: Run tests once more, commit**

Run: `python -m pytest -v`
Expected: all PASS

```bash
git add README.md tests/test_cli.py
git commit -m "docs: README + CLI help smoke test"
```

---

## Self-Review

**Spec coverage:** engine core (scan §6 → Task 3), data model (§5 → Task 2), naive DELETE+CREATE semantics (§3 → Tasks 3-4), content-addressed skip (§7 → Task 5 + integration test), LWW + `.sync-conflict` (§8 → Tasks 4-5), multi-level verification L1-L5 (§7 → Tasks 1,5,9), journal/regret log (§9 → Tasks 2,9), manual trigger CLI (§10 → Task 9), mDNS automatic discovery (§7 → Task 8), transports (localhost in tests; hotspot = same mDNS), deletes-last ordering (Task 5). Android app, tag editor, SAF, MediaStore rescan, jaudiotagger = Plan 2 (separate subsystem, per scope check). yt-dlp, trash, player = explicitly deferred in spec — correctly absent.

**Placeholder scan:** every step has concrete code/commands; no TBD/TODO; the one intentional note (protocol simplification in Task 6/7) explains a decision, not a gap.

**Type consistency:** `sha256_file`, `fingerprint`, `tree_root_digest` (T1) — used in T3/T5/T9. `init_db`, `manifest_*`, `journal_*`, `sync_state_*` (T2) — used everywhere. `build_plan`/`Plan` (T4) — used in T5/T7. `apply_plan` (T5) — used in T7. `SyncServer` endpoints (T6) — consumed by `run_sync_session` (T7) exactly. `scan(root, conn, device_id, now_ns)` (T3) — consistent across T7/T9. Confirmed no drift.

**Known simplification (flagged, not hidden):** the server runs no merge in this plan — the client drives both sides' plans. Spec §7 describes symmetric self-planning; the client-driven variant is wire-compatible and simpler; revisit when porting to Android if the phone should ever serve.