import os
from pathlib import Path

from ms import db
from ms.hashing import fingerprint, sha256_file
from typing import Callable

from ms.progress import Progress, SyncCancelled, SyncPhase, emit


def scan(root: Path, conn, device_id: str, now_ns: int,
         progress: Progress | None = None, hash_files: bool = False,
         cancel: Callable[[], bool] | None = None) -> int:
    """Diff disk vs manifest; journal CREATE/MODIFY/DELETE; return journal head.

    Lazy by default (manifest shas stay NULL — hashing 26 GB on every scan is
    pointless). With hash_files=True, every file is hashed into the manifest
    (used after a library restructure so the phone can content-copy instead of
    re-downloading). Journal ops keep a NULL sha either way."""
    emit(progress, SyncPhase.SCAN, 0, 0, "")  # walk marker: tree walk starting
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
        if cancel and cancel():
            raise SyncCancelled()
        emit(progress, SyncPhase.SCAN, i, total, rel)
        path = disk[rel]
        size, mtime_ns = fingerprint(path)
        row = db.manifest_get(conn, rel)
        if row is None:
            sha = sha256_file(path) if hash_files else None
            db.journal_append(conn, "CREATE", rel, size, None, now_ns, device_id)
            db.manifest_upsert(conn, rel, size, mtime_ns, sha, now_ns)
        elif row[0] == size and row[1] == mtime_ns:
            if hash_files and row[2] is None:
                db.manifest_upsert(conn, rel, size, mtime_ns, sha256_file(path), now_ns)
        else:
            sha = sha256_file(path) if hash_files else None
            db.journal_append(conn, "MODIFY", rel, size, None, now_ns, device_id)
            db.manifest_upsert(conn, rel, size, mtime_ns, sha, now_ns)

    for (rel,) in [m[:1] for m in db.manifest_all(conn)]:
        if rel not in disk:
            db.journal_append(conn, "DELETE", rel, None, None, now_ns, device_id)
            db.manifest_delete(conn, rel)

    return db.journal_head(conn)