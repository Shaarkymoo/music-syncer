# music-syncer

Bidirectional wireless mirror of a music library between a Linux laptop and an
Android phone. Records every change in a timestamped journal; syncs when you
tell it to.

See `docs/superpowers/specs/2026-09-28-music-sync-engine-design.md` for the
full design.

## Usage (laptop side)

    pip install -r requirements.txt
    python -m ms.cli scan --path "/media/shaarky/Data/Shaarav/my songs/"
    python -m ms.cli serve --path "/media/shaarky/Data/Shaarav/my songs/"
    python -m ms.cli log
    python -m ms.cli verify --path "/media/shaarky/Data/Shaarav/my songs/"

Suggested aliases:

    alias msscan='python -m ms.cli scan --path "/media/shaarky/Data/Shaarav/my songs/"'
    alias msserve='python -m ms.cli serve --path "/media/shaarky/Data/Shaarav/my songs/"'

## Lazy hashing

Scans are metadata-only: the first scan records paths, sizes and mtimes without
hashing file contents, so it is near-instant even on a large library. Files are
verified (SHA-256) at transfer time or on demand via `ms verify`. Identical
files present on both devices adopt the peer's checksum during sync, so no
bytes are re-transferred.

## Status

Engine + Linux daemon implemented. Android app (Plan 2) implemented — it ports
the same engine semantics (scan → journal → merge → apply) over mDNS + HTTP.

## Android app

Build the APK:

    cd android && JAVA_HOME=$HOME/.local/share/jdks/jdk-21.0.12.1+1 ./gradlew :app:assembleDebug

Install on the phone (USB debugging enabled, phone connected):

    adb install android/app/build/outputs/apk/debug/app-debug.apk

First run: tap "Choose music folder" and pick the music folder (e.g. `/sdcard/Music`).
Then: start the laptop daemon (`msserve`), tap "Find laptop & sync". Subsequent syncs:
make changes, tap Scan, then Sync.