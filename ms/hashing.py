import hashlib
from pathlib import Path
from typing import Iterable

CHUNK = 1 << 20  # 1 MiB


def sha256_file(path: Path) -> str:
    """Full-file sha256 hex digest, streamed in 1 MiB chunks."""
    h = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(CHUNK):
            h.update(chunk)
    return h.hexdigest()


def fingerprint(path: Path) -> tuple[int, int]:
    """Fast change-detection fingerprint: (size, mtime_ns)."""
    st = path.stat()
    return st.st_size, st.st_mtime_ns


def tree_root_digest(entries: Iterable[tuple[str, str]]) -> str:
    """Deterministic digest over (path, sha256) pairs — the L3 root digest."""
    h = hashlib.sha256()
    for path, sha in sorted(entries):
        h.update(path.encode("utf-8"))
        h.update(b"\0")
        h.update(sha.encode("ascii"))
        h.update(b"\n")
    return h.hexdigest()