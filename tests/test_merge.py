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