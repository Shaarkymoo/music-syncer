from dataclasses import dataclass, field


@dataclass
class Plan:
    fetch: list[tuple[str, int, str | None]] = field(default_factory=list)  # get bytes from remote
    push: list[tuple[str, int, str | None]] = field(default_factory=list)   # remote needs our bytes
    delete: list[str] = field(default_factory=list)
    conflict_loser: list[tuple[str, int, str | None]] = field(default_factory=list)  # (path, ts_ns, sha256)


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