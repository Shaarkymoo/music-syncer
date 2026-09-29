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
    sha = apply.sha256_file_bytes(b"abcd")
    plan = Plan(fetch=[("Rock/A.mp3", 4, sha)])
    summary = apply.apply_plan(root, plan, conn, "me", "phone",
                               {"Rock/A.mp3": "CREATE"}, 1000, lambda p: b"abcd")
    assert (root / "Rock" / "A.mp3").read_bytes() == b"abcd"
    assert summary["fetched"] == ["Rock/A.mp3"]
    assert db.manifest_get(conn, "Rock/A.mp3")[2] == sha
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
    root.mkdir()
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
    root.mkdir()
    (root / "A.mp3").write_bytes(b"my-old-content")
    conn = _conn(tmp_path)
    db.manifest_upsert(conn, "A.mp3", 15, 1, "oldsha", 100)
    sha = apply.sha256_file_bytes(b"newsha")
    plan = Plan(fetch=[("A.mp3", 6, sha)],
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
    root.mkdir()
    (root / "A.mp3").write_bytes(b"same")
    conn = _conn(tmp_path)
    sha = sha256_file(root / "A.mp3")
    db.manifest_upsert(conn, "A.mp3", 4, 1, sha, 100)
    plan = Plan(fetch=[("A.mp3", 4, sha)])
    apply.apply_plan(root, plan, conn, "me", "phone", {"A.mp3": "MODIFY"}, 1000,
                     lambda p: root.joinpath(p).read_bytes())
    assert db.journal_head(conn) == 0  # nothing new journaled
    conn.close()