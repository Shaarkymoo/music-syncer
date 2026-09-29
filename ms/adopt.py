from pathlib import Path

from ms import db


def adopt_shas(conn, remote_manifest: dict[str, tuple[int, int, str]],
               root: Path, now_ns: int) -> list[str]:
    """Adopt remote shas into local NULL-sha rows when the size matches exactly
    and the local file exists — no hashing, no transfer."""
    adopted: list[str] = []
    for path, (size, _mtime, sha) in remote_manifest.items():
        if sha is None:
            continue
        row = db.manifest_get(conn, path)
        if row is None or row[2] is not None or row[0] != size:
            continue
        if not (root / path).is_file():
            continue
        db.manifest_upsert(conn, path, row[0], row[1], sha, now_ns)
        adopted.append(path)
    return adopted