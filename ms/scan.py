import os
from pathlib import Path

from ms import db
from ms.hashing import fingerprint, sha256_file


def scan(root: Path, conn, device_id: str, now_ns: int) -> int:
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

    for rel in sorted(disk):
        path = disk[rel]
        size, mtime_ns = fingerprint(path)
        row = db.manifest_get(conn, rel)
        if row is None:
            sha = sha256_file(path)
            db.journal_append(conn, "CREATE", rel, size, sha, now_ns, device_id)
            db.manifest_upsert(conn, rel, size, mtime_ns, sha, now_ns)
        elif row[0] == size and row[1] == mtime_ns:
            pass  # unchanged
        else:
            sha = sha256_file(path)
            if sha != row[2]:
                db.journal_append(conn, "MODIFY", rel, size, sha, now_ns, device_id)
            db.manifest_upsert(conn, rel, size, mtime_ns, sha, now_ns)

    for (rel,) in [m[:1] for m in db.manifest_all(conn)]:
        if rel not in disk:
            db.journal_append(conn, "DELETE", rel, None, None, now_ns, device_id)
            db.manifest_delete(conn, rel)

    return db.journal_head(conn)