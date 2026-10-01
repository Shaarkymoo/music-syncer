from ms.merge import Plan, build_plan, detect_dir_moves


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


def test_local_only_path_with_remote_delete_is_deleted():
    # The remote explicitly deleted A.mp3; we must mirror the delete even though
    # our CREATE is unseen — pushing would resurrect a file the remote removed
    # (the first-sync stale-copy case, e.g. the phone's Bee Gees album).
    local_journal = [{"id": 5, "op": "CREATE", "path": "A.mp3", "size": 10,
                      "sha256": "aaa", "ts_ns": 100, "device": "me"}]
    remote_journal = [{"id": 6, "op": "DELETE", "path": "A.mp3", "size": None,
                       "sha256": None, "ts_ns": 200, "device": "phone"}]
    plan = build_plan(_m("A.mp3", "aaa"), local_journal, {}, remote_journal, 0, "me")
    assert plan.delete == ["A.mp3"] and plan.push == []


def test_remote_path_with_unseen_local_delete_is_not_fetched():
    # We deleted A.mp3 (peer hasn't seen it); remote still has it — the remote's
    # plan will delete it, we must NOT fetch it back (resurrection).
    local_journal = [{"id": 6, "op": "DELETE", "path": "A.mp3", "size": None,
                      "sha256": None, "ts_ns": 200, "device": "me"}]
    plan = build_plan({}, local_journal, _m("A.mp3", "aaa"), [], 5, "me")
    assert plan.fetch == [] and plan.delete == [] and plan.push == []


def test_remote_path_with_seen_local_delete_is_fetched():
    # Peer already saw our DELETE (cursor 6) and still has the file: it re-created
    # it, so fetching is correct.
    local_journal = [{"id": 6, "op": "DELETE", "path": "A.mp3", "size": None,
                      "sha256": None, "ts_ns": 200, "device": "me"}]
    plan = build_plan({}, local_journal, _m("A.mp3", "aaa"), [], 6, "me")
    assert plan.fetch == [("A.mp3", 10, "aaa")]


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


def test_null_local_sha_same_size_is_identical():
    # local has NULL sha, remote has sha, sizes match -> no fetch, no conflict
    plan = build_plan({"A.mp3": (10, 5, None)}, [], {"A.mp3": (10, 5, "remotesha")}, [], 0, "me")
    assert plan.fetch == [] and plan.push == [] and plan.delete == []


def test_null_local_sha_different_size_is_change():
    plan = build_plan({"A.mp3": (10, 5, None)}, [], {"A.mp3": (20, 5, "remotesha")}, [], 0, "me")
    assert plan.fetch == [("A.mp3", 20, "remotesha")] or plan.push or plan.delete


def test_both_null_sha_different_size_is_change():
    # both sides lazy-scanned but sizes differ -> real change (LWW), not identical
    local_journal = [{"id": 1, "op": "MODIFY", "path": "A.mp3", "size": 10,
                      "sha256": None, "ts_ns": 100, "device": "me"}]
    remote_journal = [{"id": 1, "op": "MODIFY", "path": "A.mp3", "size": 20,
                       "sha256": None, "ts_ns": 200, "device": "phone"}]
    plan = build_plan({"A.mp3": (10, 5, None)}, local_journal,
                      {"A.mp3": (20, 5, None)}, remote_journal, 0, "me")
    assert plan.fetch == [("A.mp3", 20, None)] or plan.push or plan.delete


def test_null_local_sha_same_size_with_unseen_modify_is_change():
    # local has NULL sha, remote has sha, sizes match — but an unseen local
    # MODIFY means the local file was rewritten: NOT identical, local wins.
    local_journal = [{"id": 6, "op": "MODIFY", "path": "A.mp3", "size": 10,
                      "sha256": None, "ts_ns": 200, "device": "me"}]
    plan = build_plan({"A.mp3": (10, 5, None)}, local_journal,
                      {"A.mp3": (10, 5, "remotesha")}, [], 5, "me")
    assert plan.push == [("A.mp3", 10, None)]


def test_null_local_sha_same_size_with_seen_modify_is_identical():
    # Peer already saw our MODIFY (cursor 6): identical-trees behavior preserved.
    local_journal = [{"id": 6, "op": "MODIFY", "path": "A.mp3", "size": 10,
                      "sha256": None, "ts_ns": 200, "device": "me"}]
    plan = build_plan({"A.mp3": (10, 5, None)}, local_journal,
                      {"A.mp3": (10, 5, "remotesha")}, [], 6, "me")
    assert plan.fetch == [] and plan.push == [] and plan.delete == []


def test_adopt_shas_skips_recently_modified(tmp_path):
    from ms import adopt, db
    root = tmp_path / "root"
    root.mkdir()
    (root / "A.mp3").write_bytes(b"content-a")
    conn = db.init_db(tmp_path / "t.db")
    db.manifest_upsert(conn, "A.mp3", 9, 1, None, 1)  # lazy scan: NULL sha
    remote = {"A.mp3": (9, 1, "remotesha")}
    assert adopt.adopt_shas(conn, remote, root, 2) == ["A.mp3"]
    assert db.manifest_get(conn, "A.mp3")[2] == "remotesha"
    # Same-size local MODIFY unseen by the peer: adoption must be skipped so the
    # stale remote sha cannot freeze the rewrite into permanent divergence.
    db.manifest_upsert(conn, "A.mp3", 9, 3, None, 3)
    assert adopt.adopt_shas(conn, remote, root, 4, {"A.mp3"}) == []
    assert db.manifest_get(conn, "A.mp3")[2] is None

def test_detect_dir_moves_collapses_whole_directory():
    plan = Plan(fetch=[("new/A.mp3", 1, "sha1"), ("new/B.mp3", 1, "sha2"), ("other/C.mp3", 1, "sha3")],
                delete=["old/A.mp3", "old/B.mp3", "other/C.mp3"])
    local = {"old/A.mp3": "sha1", "old/B.mp3": "sha2", "other/C.mp3": "sha3"}
    detect_dir_moves(plan, local)
    assert plan.moves == [("old", "new", [("old/A.mp3", "new/A.mp3", "sha1"), ("old/B.mp3", "new/B.mp3", "sha2")])]
    assert plan.delete == ["other/C.mp3"]     # same-dir mapping is not a move
    assert plan.fetch == [("other/C.mp3", 1, "sha3")]


def test_detect_dir_moves_leaves_partial_matches_alone():
    plan = Plan(fetch=[("new/A.mp3", 1, "sha1")], delete=["old/A.mp3", "old/B.mp3"])
    detect_dir_moves(plan, {"old/A.mp3": "sha1"})  # B has no sha -> not 100%
    assert plan.moves == []
    assert plan.delete == ["old/A.mp3", "old/B.mp3"]
