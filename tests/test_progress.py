import threading
from pathlib import Path

import pytest

from ms import db, scan
from ms.client import run_sync_session
from ms.progress import SyncPhase
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


def test_scan_reports_progress_per_file(tmp_path):
    root = tmp_path / "music"
    root.mkdir()
    for name in ("a.mp3", "b.mp3", "c.mp3"):
        (root / name).write_bytes(b"x")
    conn = db.init_db(tmp_path / "t.db")
    events = []
    scan.scan(root, conn, "laptop", 1,
              progress=lambda phase, done, total, rel: events.append((phase, done, total, rel)))
    file_events = events[1:]                            # skip the (SCAN, 0, 0, "") walk marker
    assert [e[0] for e in file_events] == [SyncPhase.SCAN] * 3
    assert [e[1] for e in file_events] == [1, 2, 3]     # 1-based, monotonically increasing
    assert all(e[2] == 3 for e in file_events)          # total == number of files
    assert [e[3] for e in file_events] == ["a.mp3", "b.mp3", "c.mp3"]
    conn.close()


def test_scan_emits_walk_marker_first(tmp_path):
    root = tmp_path / "music"
    root.mkdir()
    for name in ("a.mp3", "b.mp3", "c.mp3"):
        (root / name).write_bytes(b"x")
    conn = db.init_db(tmp_path / "t.db")
    events = []
    scan.scan(root, conn, "laptop", 1,
              progress=lambda phase, done, total, rel: events.append((phase, done, total, rel)))
    assert events[0] == (SyncPhase.SCAN, 0, 0, "")     # walk marker before fs.list()
    assert [e[1] for e in events[1:]] == [1, 2, 3]     # per-file events unchanged
    conn.close()


def test_scan_progress_callback_exception_is_ignored(tmp_path):
    root = tmp_path / "music"
    root.mkdir()
    for name in ("a.mp3", "b.mp3"):
        (root / name).write_bytes(b"x")
    conn = db.init_db(tmp_path / "t.db")

    def boom(phase, done, total, rel):
        raise RuntimeError("callback bug")

    head = scan.scan(root, conn, "laptop", 1, progress=boom)  # must not raise
    assert head > 0
    conn.close()


def test_sync_session_reports_transfer(pair, tmp_path):
    root_a, root_b, srv = pair
    (root_a / "Rock").mkdir()
    (root_a / "Rock" / "A.mp3").write_bytes(b"content-a")
    scan.scan(root_a, srv._conn, "laptop", 1)
    db_path = tmp_path / "c.db"
    conn = db.init_db(db_path)
    scan.scan(root_b, conn, "phone", 1)
    conn.close()
    events = []
    run_sync_session(f"http://127.0.0.1:{srv.port}", root_b, db_path, "phone",
                     progress=lambda phase, done, total, rel: events.append((phase, done, total, rel)))
    assert any(e[0] is SyncPhase.TRANSFER for e in events)   # a file transferred
    assert any(e[0] is SyncPhase.DONE for e in events)       # session ended
    assert (root_b / "Rock" / "A.mp3").read_bytes() == b"content-a"