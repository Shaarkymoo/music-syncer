import io
import json
from contextlib import redirect_stdout
from pathlib import Path

import pytest

from ms import cli, db


def test_scan_command(tmp_path: Path, capsys):
    root = tmp_path / "root"
    root.mkdir()
    (root / "A.mp3").write_bytes(b"x")
    cfg = {"base_path": str(root), "port": 8756, "db_path": str(tmp_path / "t.db")}
    code = cli.main(["scan", "--path", str(root), "--db", str(tmp_path / "t.db")])
    out = capsys.readouterr().out
    assert code == 0
    assert "CREATE" in out and "A.mp3" in out


def test_log_command(tmp_path: Path, capsys):
    conn = db.init_db(tmp_path / "t.db")
    db.journal_append(conn, "MODIFY", "Rock/A.mp3", 5, "sha", 1000, "phone")
    conn.close()
    code = cli.main(["log", "--db", str(tmp_path / "t.db")])
    out = capsys.readouterr().out
    assert code == 0
    assert "MODIFY" in out and "Rock/A.mp3" in out and "phone" in out


def test_verify_clean_tree_unverified(tmp_path: Path, capsys):
    root = tmp_path / "root"
    root.mkdir()
    (root / "A.mp3").write_bytes(b"data")
    cli.main(["scan", "--path", str(root), "--db", str(tmp_path / "t.db")])
    code = cli.main(["verify", "--path", str(root), "--db", str(tmp_path / "t.db")])
    out = capsys.readouterr().out
    assert code == 0 and "unverified" in out  # lazy scan -> no hashes -> unverified


def test_verify_detects_tamper_of_verified_file(tmp_path: Path, capsys):
    root = tmp_path / "root"
    root.mkdir()
    p = root / "A.mp3"
    p.write_bytes(b"data")
    conn = db.init_db(tmp_path / "t.db")
    db.journal_append(conn, "CREATE", "A.mp3", 4, "deadbeef", 1, "laptop")  # seed a real sha
    db.manifest_upsert(conn, "A.mp3", 4, 1, "deadbeef", 1)
    conn.close()
    p.write_bytes(b"TAMPERED!")
    code = cli.main(["verify", "--path", str(root), "--db", str(tmp_path / "t.db")])
    out = capsys.readouterr().out
    assert code == 0 and "MISMATCH" in out and "A.mp3" in out  # verified file tampered


def test_help_renders(capsys):
    # argparse exits (SystemExit 0) after printing --help; the exit code is
    # the process status, so assert on it via the raised exception.
    with pytest.raises(SystemExit) as exc:
        cli.main(["--help"])
    out = capsys.readouterr().out
    assert exc.value.code == 0 and "scan" in out and "serve" in out