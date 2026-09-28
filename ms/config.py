import os
import tomllib
from pathlib import Path

DEFAULT_PORT = 8756


def _default_path() -> Path:
    return Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "music-syncer" / "config.toml"


def load_config(path: Path | None = None) -> dict:
    p = path or _default_path()
    cfg: dict = {"base_path": None, "port": DEFAULT_PORT, "db_path": None}
    if p.exists():
        with p.open("rb") as f:
            data = tomllib.load(f)
        cfg.update({k: v for k, v in data.items() if k in cfg})
    if cfg["db_path"] is None:
        data_home = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share"))
        cfg["db_path"] = str(data_home / "music-syncer.db")
    return cfg