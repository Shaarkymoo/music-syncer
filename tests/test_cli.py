import io
import json
from contextlib import redirect_stdout
from pathlib import Path

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


def test_verify_clean_tree(tmp_path: Path, capsys):
    root = tmp_path / "root"
    root.mkdir()
    (root / "A.mp3").write_bytes(b"data")
    cli.main(["scan", "--path", str(root), "--db", str(tmp_path / "t.db")])
    code = cli.main(["verify", "--path", str(root), "--db", str(tmp_path / "t.db")])
    out = capsys.readouterr().out
    assert code == 0
    assert "OK" in out


def test_verify_detects_tamper(tmp_path: Path, capsys):
    root = tmp_path / "root"
    root.mkdir()
    p = root / "A.mp3"
    p.write_bytes(b"data")
    cli.main(["scan", "--path", str(root), "--db", str(tmp_path / "t.db")])
    p.write_bytes(b"TAMPERED!")
    code = cli.main(["verify", "--path", str(root), "--db", str(tmp_path / "t.db")])
    out = capsys.readouterr().out
    assert code == 0
    assert "MISMATCH" in out and "A.mp3" in out