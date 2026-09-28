import os
import secrets
from pathlib import Path
from typing import Callable

from ms import db
from ms.hashing import sha256_file
from ms.merge import Plan

PARTIAL_PREFIX = ".ms-partial-"


def _resolve(root: Path, rel: str) -> Path:
    """Path traversal guard: rel must resolve strictly inside root."""
    target = (root / rel).resolve()
    root_resolved = root.resolve()
    if root_resolved != target and root_resolved not in target.parents:
        raise ValueError(f"path escapes root: {rel}")
    return target


def _conflict_name(rel: str, ts_ns: str) -> str:
    p = Path(rel)
    return f".{p.name}.sync-conflict-{ts_ns}{p.suffix}"


def apply_plan(root: Path, plan: Plan, conn, our_device: str, remote_device: str,
               remote_ops: dict[str, str], now_ns: int,
               fetch_bytes: Callable[[str], bytes]) -> dict:
    summary: dict = {"fetched": [], "copied": [], "deleted": [], "conflicts": []}

    # Local sha lookup for content-addressed skip.
    local_sha_to_path: dict[str, str] = {}
    for (path, _s, _m, sha, _l) in db.manifest_all(conn):
        if sha:
            local_sha_to_path.setdefault(sha, path)

    # --- conflict losers first: preserve our old bytes as hidden file,
    #     before the fetch overwrites the path with the winner ---
    for rel, ts_ns, _sha in plan.conflict_loser:
        target = _resolve(root, rel)
        if target.is_file():
            os.replace(target, target.parent / _conflict_name(rel, ts_ns))
            summary["conflicts"].append(rel)

    # --- fetches (writes) ---
    for rel, _size, sha in sorted(plan.fetch):
        target = _resolve(root, rel)
        src_rel = local_sha_to_path.get(sha)
        if src_rel == rel and target.is_file():
            continue  # already applied: same content at same path — no-op
        if src_rel and src_rel != rel:
            src = _resolve(root, src_rel)
            if src.is_file():  # content-addressed copy: no transfer
                target.parent.mkdir(parents=True, exist_ok=True)
                tmp = target.parent / f"{PARTIAL_PREFIX}{secrets.token_hex(4)}-{target.name}"
                tmp.write_bytes(src.read_bytes())
                os.replace(tmp, target)
                summary["copied"].append(rel)
                db.journal_append(conn, remote_ops.get(rel, "CREATE"), rel,
                                  target.stat().st_size, sha, now_ns, remote_device)
                db.manifest_upsert(conn, rel, target.stat().st_size,
                                   target.stat().st_mtime_ns, sha, now_ns)
                continue
        data = fetch_bytes(rel)
        if sha256_file_bytes(data) != sha:
            raise ValueError(f"sha256 mismatch after transfer: {rel}")
        target.parent.mkdir(parents=True, exist_ok=True)
        tmp = target.parent / f"{PARTIAL_PREFIX}{secrets.token_hex(4)}-{target.name}"
        tmp.write_bytes(data)
        os.replace(tmp, target)
        summary["fetched"].append(rel)
        db.journal_append(conn, remote_ops.get(rel, "CREATE"), rel,
                          len(data), sha, now_ns, remote_device)
        db.manifest_upsert(conn, rel, len(data), target.stat().st_mtime_ns, sha, now_ns)

    # --- deletes last ---
    for rel in sorted(plan.delete):
        target = _resolve(root, rel)
        if target.is_file():
            target.unlink()
            summary["deleted"].append(rel)
            db.journal_append(conn, "DELETE", rel, None, None, now_ns, remote_device)
            db.manifest_delete(conn, rel)

    return summary


def sha256_file_bytes(data: bytes) -> str:
    import hashlib
    return hashlib.sha256(data).hexdigest()