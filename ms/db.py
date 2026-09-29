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


def init_db(db_path: Path, check_same_thread: bool = True) -> sqlite3.Connection:
    conn = sqlite3.connect(str(db_path), check_same_thread=check_same_thread)
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