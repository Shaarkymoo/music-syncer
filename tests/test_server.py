import json
import os
import socket
import threading
import urllib.request
import urllib.parse
from pathlib import Path

import pytest

from ms import db
from ms.server import SyncServer


@pytest.fixture  # noqa: F821 (pytest fixture)
def server(tmp_path: Path):
    root = tmp_path / "root"
    root.mkdir()
    (root / "Rock").mkdir()
    (root / "Rock" / "A.mp3").write_bytes(b"content-a")
    os.utime(root / "Rock" / "A.mp3", ns=(1, 1))  # match fixture DB fingerprint (9, 1)
    db_path = tmp_path / "s.db"
    conn = db.init_db(db_path)
    db.journal_append(conn, "CREATE", "Rock/A.mp3", 9, "sha", 1, "laptop")
    db.manifest_upsert(conn, "Rock/A.mp3", 9, 1, "sha", 1)
    conn.close()
    srv = SyncServer(root, db_path, "laptop", schema_version=1)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    yield srv
    srv.shutdown()
    t.join(timeout=5)


def _get(url: str) -> dict:
    with urllib.request.urlopen(url) as r:
        return json.loads(r.read())


def test_handshake(server):
    data = _get(f"http://127.0.0.1:{server.port}/handshake?device_id=phone")
    assert data["schema_version"] == 1
    assert data["server_device_id"] == "laptop"
    assert data["client_cursor"] == 0  # never synced


def test_manifest(server):
    data = _get(f"http://127.0.0.1:{server.port}/manifest")
    assert data == [["Rock/A.mp3", 9, 1, "sha"]]


def test_journal_since(server):
    data = _get(f"http://127.0.0.1:{server.port}/journal?after=0")
    assert len(data) == 1 and data[0]["path"] == "Rock/A.mp3"


def test_get_file(server):
    with urllib.request.urlopen(
            f"http://127.0.0.1:{server.port}/file?path={urllib.parse.quote('Rock/A.mp3')}") as r:
        assert r.read() == b"content-a"


def test_post_file_verifies_sha(server):
    import urllib.request
    body = b"new-bytes"
    sha = __import__("hashlib").sha256(body).hexdigest()
    req = urllib.request.Request(
        f"http://127.0.0.1:{server.port}/file?path={urllib.parse.quote('Rock/B.mp3')}&sha={sha}",
        data=body, method="POST")
    with urllib.request.urlopen(req) as r:
        assert json.loads(r.read())["ok"] is True
    assert (server.root / "Rock" / "B.mp3").read_bytes() == b"new-bytes"


def test_post_file_rejects_bad_sha(server):
    import urllib.request
    req = urllib.request.Request(
        f"http://127.0.0.1:{server.port}/file?path={urllib.parse.quote('Rock/C.mp3')}&sha=deadbeef",
        data=b"anything", method="POST")
    import urllib.error
    with pytest.raises(urllib.error.HTTPError):  # noqa: F821
        urllib.request.urlopen(req)


def test_post_sync_returns_server_state(server):
    body = json.dumps({
        "device_id": "phone", "server_cursor": 1,  # client already saw fixture op id=1
        "journal_ops": [], "manifest": [],
    }).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{server.port}/sync", data=body,
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req) as r:
        data = json.loads(r.read())
    assert data["server_journal_ops"] == []
    assert data["server_manifest"] == [["Rock/A.mp3", 9, 1, "sha"]]


def test_server_binds_requested_port(tmp_path: Path):
    # Grab a free port by binding to 0, reading it, and closing (tiny race ok).
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    root = tmp_path / "root"
    root.mkdir()
    srv = SyncServer(root, tmp_path / "s.db", "laptop", port=port)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    try:
        assert srv.port == port
    finally:
        srv.shutdown()
        t.join(timeout=5)


def test_post_file_rejects_empty_path(server):
    import urllib.error
    import hashlib
    body = b"anything"
    sha = hashlib.sha256(body).hexdigest()  # valid sha: only the path guard may reject
    req = urllib.request.Request(
        f"http://127.0.0.1:{server.port}/file?path=&sha={sha}",
        data=body, method="POST")
    with pytest.raises(urllib.error.HTTPError) as exc:
        urllib.request.urlopen(req)
    assert exc.value.code == 400
    # No stray partial file may be written (e.g. in the parent of the root).
    assert not list(server.root.parent.glob(f".ms-partial-*"))
    assert not list(server.root.glob(f".ms-partial-*"))


def test_request_logged_to_stderr(server, capsys):
    _get(f"http://127.0.0.1:{server.port}/version")
    err = capsys.readouterr().err
    assert "/version" in err
    assert "200" in err


def test_404_logged_to_stderr(server, capsys):
    import urllib.error
    with pytest.raises(urllib.error.HTTPError):
        urllib.request.urlopen(f"http://127.0.0.1:{server.port}/no-such-endpoint")
    err = capsys.readouterr().err
    assert "/no-such-endpoint" in err
    assert "404" in err


def test_exception_logged_to_stderr(server, capsys):
    import urllib.error
    req = urllib.request.Request(
        f"http://127.0.0.1:{server.port}/sync", data=b"{not json",
        headers={"Content-Type": "application/json"}, method="POST")
    with pytest.raises(urllib.error.HTTPError) as exc:
        urllib.request.urlopen(req)
    assert exc.value.code == 500
    err = capsys.readouterr().err
    assert "ERROR" in err
    assert "/sync" in err