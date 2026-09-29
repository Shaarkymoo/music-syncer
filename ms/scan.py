import os
from pathlib import Path

from ms import db
from ms.hashing import fingerprint
from ms.progress import Progress, SyncPhase, emit


def scan(root: Path, conn, device_id: str, now_ns: int,
         progress: Progress | None = None) -> int:
    """Diff disk vs manifest; journal CREATE/MODIFY/DELETE; return journal head."""
    disk: dict[str, Path] = {}
    for p in root.rglob("*"):
        if not p.is_file():
            continue
        if p.name.startswith(".ms-partial-"):
            p.unlink()  # crash residue
            continue
        rel = p.relative_to(root).as_posix()
        if any(part.startswith(".") for part in rel.split("/")):
            continue  # hidden file/dir: never mirrored
        disk[rel] = p

    total = len(disk)
    for i, rel in enumerate(sorted(disk), 1):
        emit(progress, SyncPhase.SCAN, i, total, rel)
        path = disk[rel]
        size, mtime_ns = fingerprint(path)
        row = db.manifest_get(conn, rel)
        if row is None:
            db.journal_append(conn, "CREATE", rel, size, None, now_ns, device_id)
            db.manifest_upsert(conn, rel, size, mtime_ns, None, now_ns)
        elif row[0] == size and row[1] == mtime_ns:
            pass  # unchanged
        else:
            db.journal_append(conn, "MODIFY", rel, size, None, now_ns, device_id)
            db.manifest_upsert(conn, rel, size, mtime_ns, None, now_ns)

    for (rel,) in [m[:1] for m in db.manifest_all(conn)]:
        if rel not in disk:
            db.journal_append(conn, "DELETE", rel, None, None, now_ns, device_id)
            db.manifest_delete(conn, rel)

    return db.journal_head(conn)