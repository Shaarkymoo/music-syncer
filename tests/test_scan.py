import os
from pathlib import Path

from ms import db, scan


def _conn(tmp_path: Path):
    return db.init_db(tmp_path / "t.db")


def test_scan_detects_create_lazy(tmp_path: Path):
    root = tmp_path / "root"
    (root / "Rock").mkdir(parents=True)
    (root / "Rock" / "A.mp3").write_bytes(b"data")
    conn = _conn(tmp_path)
    scan.scan(root, conn, "laptop", 1000)
    ops = db.journal_since(conn, 0)
    assert [(o["op"], o["path"]) for o in ops] == [("CREATE", "Rock/A.mp3")]
    assert db.manifest_get(conn, "Rock/A.mp3")[2] is None  # lazy: no hash
    conn.close()


def test_scan_modify_is_lazy(tmp_path: Path):
    root = tmp_path / "root"
    root.mkdir(parents=True)
    p = root / "A.mp3"
    p.write_bytes(b"v1")
    conn = _conn(tmp_path)
    scan.scan(root, conn, "laptop", 1000)
    p.write_bytes(b"v2-longer")  # size change -> MODIFY
    scan.scan(root, conn, "laptop", 2000)
    ops = db.journal_since(conn, 1)
    assert [(o["op"], o["sha256"]) for o in ops] == [("MODIFY", None)]
    conn.close()


def test_scan_idempotent_no_new_ops(tmp_path: Path):
    root = tmp_path / "root"
    root.mkdir(parents=True)
    (root / "A.mp3").write_bytes(b"data")
    conn = _conn(tmp_path)
    scan.scan(root, conn, "laptop", 1000)
    head = db.journal_head(conn)
    scan.scan(root, conn, "laptop", 2000)
    assert db.journal_head(conn) == head  # unchanged -> no new ops
    conn.close()


def test_scan_detects_modify(tmp_path: Path):
    root = tmp_path / "root"
    root.mkdir(parents=True)
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
    root.mkdir(parents=True)
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
    root.mkdir(parents=True)
    (root / ".hidden.mp3").write_bytes(b"x")
    (root / ".ms-partial-1234-A.mp3").write_bytes(b"junk")
    conn = _conn(tmp_path)
    scan.scan(root, conn, "laptop", 1000)
    assert not (root / ".ms-partial-1234-A.mp3").exists()  # crash residue removed
    assert db.journal_head(conn) == 0  # nothing journaled
    conn.close()


def test_scan_with_hash_fills_manifest_shas(tmp_path: Path):
    root = tmp_path / "root"
    (root / "Rock").mkdir(parents=True)
    (root / "Rock" / "A.mp3").write_bytes(b"content-a")
    conn = _conn(tmp_path)
    scan.scan(root, conn, "laptop", 1000, hash_files=True)
    row = db.manifest_get(conn, "Rock/A.mp3")
    assert row[2] is not None  # hashed, not lazy
    # journal ops stay lazy (NULL sha): conflict tracking doesn't need the hash
    ops = db.journal_since(conn, 0)
    assert ops[0]["sha256"] is None
    # re-run: already hashed file is not re-journaled
    head = db.journal_head(conn)
    scan.scan(root, conn, "laptop", 2000, hash_files=True)
    assert db.journal_head(conn) == head
    conn.close()