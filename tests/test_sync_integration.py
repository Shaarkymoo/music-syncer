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


def test_reacquired_stale_copy_pushed_back_without_error(pair, tmp_path):
    # Phone re-acquires a file the laptop deleted: that is a fresh addition, so
    # it propagates both ways (mirror) and no error occurs.
    root_a, root_b, srv = pair
    (root_a / "A.mp3").write_bytes(b"content-a")
    scan.scan(root_a, srv._conn, "laptop", 1)
    db_path = _client_scan(root_b, tmp_path)
    _sync(root_b, db_path, srv, tmp_path)          # both have A.mp3; cursor past CREATE
    (root_a / "A.mp3").unlink()
    scan.scan(root_a, srv._conn, "laptop", 2)      # laptop deletes A.mp3
    db_path2 = _client_scan(root_b, tmp_path)
    _sync(root_b, db_path2, srv, tmp_path)         # phone deletes stale copy
    assert not (root_b / "A.mp3").exists()
    (root_b / "A.mp3").write_bytes(b"content-a")   # user re-adds it on the phone
    db_path3 = _client_scan(root_b, tmp_path)
    _sync(root_b, db_path3, srv, tmp_path)         # pushed back; server re-gains it
    assert (root_a / "A.mp3").exists() and (root_b / "A.mp3").exists()


def test_seen_server_delete_stale_copy_deleted_not_pushed(pair, tmp_path):
    # Legacy state: the phone saw the server's DELETE (cursor past it) but kept
    # its stale copy (pre-fix behavior). The stale copy must be deleted, not
    # pushed back, and the push must not error on the just-deleted file.
    root_a, root_b, srv = pair
    (root_a / "A.mp3").write_bytes(b"content-a")
    scan.scan(root_a, srv._conn, "laptop", 1)
    db_path = _client_scan(root_b, tmp_path)
    _sync(root_b, db_path, srv, tmp_path)          # both have A.mp3
    (root_a / "A.mp3").unlink()
    scan.scan(root_a, srv._conn, "laptop", 2)      # laptop deletes A.mp3
    conn = db.init_db(db_path)
    db.sync_state_set(conn, "laptop", srv._conn.execute("SELECT MAX(id) FROM journal").fetchone()[0], 3)
    conn.close()                                   # cursor past the DELETE, stale copy kept
    db_path2 = _client_scan(root_b, tmp_path)
    _sync(root_b, db_path2, srv, tmp_path)         # must not raise
    assert not (root_b / "A.mp3").exists()         # stale copy deleted
    assert not (root_a / "A.mp3").exists()         # not resurrected


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
    # The laptop's library is hashed: fill the moved file's sha (the lazy scan
    # leaves it NULL, and the content-addressed copy path needs a non-null sha).
    row = db.manifest_get(srv._conn, "Fav/Song.mp3")
    db.manifest_upsert(srv._conn, "Fav/Song.mp3", row[0], row[1],
                       sha256_file(root_a / "Fav" / "Song.mp3"), 2)
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


def test_identical_trees_sync_with_zero_transfer(pair, tmp_path):
    root_a, root_b, srv = pair
    # Seed BOTH sides with the SAME file (simulating "same library on both").
    (root_a / "Rock").mkdir(); (root_a / "Rock" / "A.mp3").write_bytes(b"same-content")
    (root_b / "Rock").mkdir(); (root_b / "Rock" / "A.mp3").write_bytes(b"same-content")
    # Laptop has a hashed library (real sha in its manifest — e.g. from a
    # previous sync/verify); the phone scans lazily (NULL sha).
    scan.scan(root_a, srv._conn, "laptop", 1)
    row = db.manifest_get(srv._conn, "Rock/A.mp3")
    db.manifest_upsert(srv._conn, "Rock/A.mp3", row[0], row[1],
                       sha256_file(root_a / "Rock" / "A.mp3"), 1)
    # Phone scans lazily; its manifest has NULL sha; sizes match the laptop's manifest.
    db_path = _client_scan(root_b, tmp_path)
    s = _sync(root_b, db_path, srv, tmp_path)
    assert s["fetched"] == [] and s["pushed"] == []   # zero transfer
    conn = db.init_db(db_path)
    assert db.manifest_get(conn, "Rock/A.mp3")[2] is not None  # sha adopted
    conn.close()


def test_same_size_modify_propagates(pair, tmp_path):
    root_a, root_b, srv = pair
    (root_a / "Rock").mkdir()
    (root_a / "Rock" / "A.mp3").write_bytes(b"content-a")
    (root_b / "Rock").mkdir()
    (root_b / "Rock" / "A.mp3").write_bytes(b"content-a")
    # Both libraries hashed (real shas in both manifests — hashed-phone scenario).
    scan.scan(root_a, srv._conn, "laptop", 1)
    row = db.manifest_get(srv._conn, "Rock/A.mp3")
    db.manifest_upsert(srv._conn, "Rock/A.mp3", row[0], row[1],
                       sha256_file(root_a / "Rock" / "A.mp3"), 1)
    db_path = _client_scan(root_b, tmp_path)
    conn = db.init_db(db_path)
    row = db.manifest_get(conn, "Rock/A.mp3")
    db.manifest_upsert(conn, "Rock/A.mp3", row[0], row[1],
                       sha256_file(root_b / "Rock" / "A.mp3"), 1)
    conn.close()
    # Laptop rewrites to DIFFERENT content of the SAME byte size; the lazy scan
    # journals a MODIFY with NULL sha.
    (root_a / "Rock" / "A.mp3").write_bytes(b"content-b")
    scan.scan(root_a, srv._conn, "laptop", 2)
    s = _sync(root_b, db_path, srv, tmp_path)
    assert s["fetched"] == ["Rock/A.mp3"]          # the MODIFY propagated
    assert (root_b / "Rock" / "A.mp3").read_bytes() == b"content-b"
    # The laptop's manifest sha was NOT poisoned with the phone's stale sha.
    assert db.manifest_get(srv._conn, "Rock/A.mp3")[2] is None


def test_second_sync_is_noop(pair, tmp_path):
    root_a, root_b, srv = pair
    (root_a / "A.mp3").write_bytes(b"x")
    scan.scan(root_a, srv._conn, "laptop", 1)
    db_path = _client_scan(root_b, tmp_path)
    _sync(root_b, db_path, srv, tmp_path)
    db_path2 = _client_scan(root_b, tmp_path)
    s = _sync(root_b, db_path2, srv, tmp_path)
    assert s["fetched"] == [] and s["deleted"] == [] and s["pushed"] == []


def test_schema_version_mismatch_raises(tmp_path: Path):
    root_a = tmp_path / "A"
    root_b = tmp_path / "B"
    root_a.mkdir(); root_b.mkdir()
    srv = SyncServer(root_a, tmp_path / "s.db", "laptop", schema_version=2)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    try:
        db_path = _client_scan(root_b, tmp_path)
        with pytest.raises(ValueError, match="schema mismatch"):
            run_sync_session(f"http://127.0.0.1:{srv.port}", root_b, db_path, "phone")
    finally:
        srv.shutdown()
        t.join(timeout=5)