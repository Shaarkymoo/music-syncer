"""Live progress reporting for scan/sync sessions (consumed by the app UI)."""

from enum import Enum
from typing import Callable


class SyncPhase(Enum):
    SCAN = "SCAN"
    ADOPT = "ADOPT"  # Kotlin client emits this during sha adoption; the laptop
    # adoption (ms/adopt.py) is cheap on a real filesystem, so Python never does.
    PLAN = "PLAN"
    TRANSFER = "TRANSFER"
    DONE = "DONE"


# (phase, done, total, current_rel) — done is 1-based, total is the phase's
# file count, current_rel is the file currently being processed ("" if none).
Progress = Callable[[SyncPhase, int, int, str], None]


def emit(progress: Progress | None, phase: SyncPhase, done: int, total: int, rel: str) -> None:
    """Best-effort progress callback: a raising callback never breaks the engine."""
    if progress is None:
        return
    try:
        progress(phase, done, total, rel)
    except Exception:
        pass  # progress is advisory; swallow callback bugs