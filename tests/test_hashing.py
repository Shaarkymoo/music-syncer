import hashlib
from pathlib import Path

from ms.hashing import fingerprint, sha256_file, tree_root_digest


def test_sha256_file_matches_hashlib(tmp_path: Path):
    p = tmp_path / "a.bin"
    p.write_bytes(b"hello world" * 1000)
    assert sha256_file(p) == hashlib.sha256(b"hello world" * 1000).hexdigest()


def test_sha256_file_large(tmp_path: Path):
    p = tmp_path / "big.bin"
    p.write_bytes(b"x" * (3 * 1024 * 1024))  # > 1 MiB chunk, forces multi-chunk read
    assert sha256_file(p) == hashlib.sha256(b"x" * (3 * 1024 * 1024)).hexdigest()


def test_fingerprint_returns_size_and_mtime_ns(tmp_path: Path):
    p = tmp_path / "s.txt"
    p.write_text("abc")
    size, mtime_ns = fingerprint(p)
    assert size == 3
    assert mtime_ns == p.stat().st_mtime_ns


def test_tree_root_digest_is_deterministic_and_order_independent():
    a = tree_root_digest([("b/x.mp3", "aa"), ("a/y.mp3", "bb")])
    b = tree_root_digest([("a/y.mp3", "bb"), ("b/x.mp3", "aa")])
    c = tree_root_digest([("b/x.mp3", "aa")])
    assert a == b
    assert a != c