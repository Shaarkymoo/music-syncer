import json
import threading
import urllib.error
import urllib.request
from pathlib import Path

import pytest

from ms import config
from ms import db
from ms.server import APP_VERSION, SyncServer


@pytest.fixture  # noqa: F821 (pytest fixture)
def server(tmp_path: Path):
    root = tmp_path / "root"
    root.mkdir()
    db_path = tmp_path / "s.db"
    conn = db.init_db(db_path)
    conn.close()
    srv = SyncServer(root, db_path, "laptop", schema_version=1)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    yield srv
    srv.shutdown()
    t.join(timeout=5)


def _start(srv: SyncServer):
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    return t


def test_version_endpoint(server):
    with urllib.request.urlopen(f"http://127.0.0.1:{server.port}/version") as r:
        assert json.loads(r.read()) == {"version": APP_VERSION}


def test_apk_404_when_unset(server):
    with pytest.raises(urllib.error.HTTPError) as exc:  # noqa: F821
        urllib.request.urlopen(f"http://127.0.0.1:{server.port}/apk")
    assert exc.value.code == 404
    assert json.loads(exc.value.read()) == {"error": "apk not available"}


def test_apk_serves_bytes_when_configured(tmp_path: Path):
    apk = tmp_path / "app-debug.apk"
    apk.write_bytes(b"PK\x03\x04fake-apk-bytes")
    root = tmp_path / "root"
    root.mkdir()
    srv = SyncServer(root, tmp_path / "s.db", "laptop", apk_path=str(apk))
    t = _start(srv)
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{srv.port}/apk") as r:
            assert r.headers["Content-Type"] == "application/vnd.android.package-archive"
            assert r.read() == b"PK\x03\x04fake-apk-bytes"
    finally:
        srv.shutdown()
        t.join(timeout=5)


def test_apk_404_when_path_missing(tmp_path: Path):
    root = tmp_path / "root"
    root.mkdir()
    srv = SyncServer(root, tmp_path / "s.db", "laptop", apk_path=str(tmp_path / "nope.apk"))
    t = _start(srv)
    try:
        with pytest.raises(urllib.error.HTTPError) as exc:  # noqa: F821
            urllib.request.urlopen(f"http://127.0.0.1:{srv.port}/apk")
        assert exc.value.code == 404
    finally:
        srv.shutdown()
        t.join(timeout=5)


def test_config_apk_path_from_toml(tmp_path: Path):
    p = tmp_path / "c.toml"
    p.write_text('apk_path = "/music/app.apk"\n')
    cfg = config.load_config(p)
    assert cfg["apk_path"] == "/music/app.apk"


def test_config_apk_path_env_overrides_toml(tmp_path: Path, monkeypatch):
    p = tmp_path / "c.toml"
    p.write_text('apk_path = "/from/toml.apk"\n')
    monkeypatch.setenv("MS_APK_PATH", "/from/env.apk")
    cfg = config.load_config(p)
    assert cfg["apk_path"] == "/from/env.apk"