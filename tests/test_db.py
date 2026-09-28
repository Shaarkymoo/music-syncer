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