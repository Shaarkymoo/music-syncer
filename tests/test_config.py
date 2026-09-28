from pathlib import Path

from ms import config


def test_load_config_defaults_when_missing(tmp_path: Path):
    cfg = config.load_config(tmp_path / "nope.toml")
    assert cfg["port"] == 8756


def test_load_config_reads_toml(tmp_path: Path):
    p = tmp_path / "c.toml"
    p.write_text('base_path = "/music"\nport = 9000\n')
    cfg = config.load_config(p)
    assert cfg["base_path"] == "/music"
    assert cfg["port"] == 9000