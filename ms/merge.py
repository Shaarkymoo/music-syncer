from dataclasses import dataclass, field


@dataclass
class Plan:
    fetch: list[tuple[str, int, str | None]] = field(default_factory=list)  # get bytes from remote
    push: list[tuple[str, int, str | None]] = field(default_factory=list)   # remote needs our bytes
    delete: list[str] = field(default_factory=list)
    conflict_loser: list[tuple[str, int, str | None]] = field(default_factory=list)  # (path, ts_ns, sha256)
    # whole-directory moves: (old_dir, new_dir, [(old_rel, new_rel, sha), ...])
    moves: list[tuple[str, str, list[tuple[str, str, str | None]]]] = field(default_factory=list)


def detect_dir_moves(plan: Plan, local_sha_by_path: dict[str, str | None]) -> Plan:
    """Collapse a whole-directory {delete old, fetch new} pair into one move when
    every file under an old_dir maps 1:1 by sha to a file under a sibling new_dir
    (same parent) — the restructure case. Removes moved items from fetch/delete.
    Conservative: anything less than 100% stays per-file."""
    fetch_by_sha = {sha: rel for rel, _s, sha in plan.fetch if sha}
    delete_by_dir: dict[str, list[str]] = {}
    for rel in plan.delete:
        old_dir = rel.rsplit("/", 1)[0] if "/" in rel else ""
        delete_by_dir.setdefault(old_dir, []).append(rel)
    moved: set[str] = set()
    for old_dir, old_paths in delete_by_dir.items():
        if not old_dir or len(old_paths) < 2:
            continue
        mappings: list[tuple[str, str, str | None]] = []
        ok = True
        for old in old_paths:
            sha = local_sha_by_path.get(old)
            new = fetch_by_sha.get(sha) if sha else None
            if new is None or (new.rsplit("/", 1)[0] if "/" in new else "") == old_dir:
                ok = False
                break
            mappings.append((old, new, sha))
        if not ok or len(mappings) != len(old_paths):
            continue
        new_dirs = {new.rsplit("/", 1)[0] if "/" in new else "" for _old, new, _s in mappings}
        if len(new_dirs) != 1:
            continue
        new_dir = new_dirs.pop()
        if (new_dir.rsplit("/", 1)[0] if "/" in new_dir else "") != (old_dir.rsplit("/", 1)[0] if "/" in old_dir else ""):
            continue
        if len({new for _old, new, _s in mappings}) != len(mappings):
            continue
        plan.moves.append((old_dir, new_dir, mappings))
        moved.update(old for old, _new, _s in mappings)
        moved.update(new for _old, new, _s in mappings)
    if moved:
        plan.delete = [r for r in plan.delete if r not in moved]
        plan.fetch = [f for f in plan.fetch if f[0] not in moved]
    return plan


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
    # Index the local journal once so the per-path predicates below don't
    # rescan the whole journal for every path (O(paths × journal) -> O(paths + journal)).
    ops_by_path: dict[str, list[dict]] = {}
    for op in local_journal:
        ops_by_path.setdefault(op["path"], []).append(op)
    remote_ops_by_path: dict[str, list[dict]] = {}
    for op in remote_journal:
        remote_ops_by_path.setdefault(op["path"], []).append(op)

    for path, (size, _mtime, sha) in sorted(remote_manifest.items()):
        if path not in local_manifest:
            unseen_local_delete = any(
                op["id"] > peer_cursor and op["op"] == "DELETE" and op["device"] == our_device
                for op in ops_by_path.get(path, []))
            if unseen_local_delete:
                continue  # we deleted it; the remote's plan will delete it too
            plan.fetch.append((path, size, sha))
        elif local_sha[path] == sha and sha is not None:
            continue  # both sides have the same known sha
        else:  # both sides have different content (or unknown)
            if local_sha[path] is None and local_manifest[path][0] == size:
                unseen_local_modify = any(
                    op["id"] > peer_cursor and op["op"] == "MODIFY" and op["device"] == our_device
                    for op in ops_by_path.get(path, []))
                if not unseen_local_modify:
                    continue  # local never hashed, sizes match: identical; adoption fills the sha
            local_ts = local_latest[path]["ts_ns"] if path in local_latest else 0
            remote_ts = remote_latest[path]["ts_ns"] if path in remote_latest else 0
            if local_ts > remote_ts:  # local wins; remote must take ours
                plan.push.append((path, local_manifest[path][0], local_manifest[path][2]))
            else:  # remote wins; we take theirs, preserve ours
                plan.fetch.append((path, size, sha))
                plan.conflict_loser.append((path, local_ts, local_sha[path]))

    for path, (_size, _mtime, _sha) in sorted(local_manifest.items()):
        if path in remote_manifest:
            continue
        # The remote explicitly deleted this path: mirror the delete. This beats
        # an unseen local CREATE — otherwise a stale local copy whose CREATE is
        # unseen (e.g. the whole phone library on first sync) would be pushed
        # back, resurrecting a file the remote deliberately removed.
        remote_deleted = any(op["op"] == "DELETE" for op in remote_ops_by_path.get(path, []))
        unseen_local_change = any(
            op["id"] > peer_cursor and op["op"] in ("CREATE", "MODIFY") and op["device"] == our_device
            for op in ops_by_path.get(path, []))
        if remote_deleted:
            plan.delete.append(path)
        elif unseen_local_change:
            plan.push.append((path, local_manifest[path][0], local_manifest[path][2]))
        else:
            plan.delete.append(path)

    return plan