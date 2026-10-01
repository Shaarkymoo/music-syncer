# Terminal commands (laptop / PC)

Everything you can run on the PC side: what it does and when to use it. Run
everything from the repo root (`/media/shaarky/Data/Projects/music-syncer`).
`PYTHONPATH=$PWD` is needed so Python finds the `ms` package; the repo `.venv`
is broken, so use the system `python3`.

---

## The server (the thing the phone talks to)

```bash
# Start the server (scans once at startup, then serves on port 8756).
# MUST survive the shell closing -> setsid + redirect + background:
setsid env PYTHONPATH=$PWD \
  MS_APK_PATH=$PWD/android/app/build/outputs/apk/debug/app-debug.apk \
  /usr/bin/python3 -m ms.cli serve --path "/media/shaarky/Data/Shaarav/my songs/" \
  > /tmp/msserve.log 2>&1 < /dev/null &

# Stop it (the [m] prevents pkill matching itself):
pkill -f "[m]s.cli serve"

# Watch its log (every request is logged; errors are never silent):
tail -f /tmp/msserve.log
```

**When:** the phone needs the laptop reachable for Scan/Sync/Find-laptop.
Without it, the app shows "Server: not found".

---

## Scanning (change detection)

```bash
# Normal (lazy) scan: metadata-only, ~0.5 s. Journals CREATE/MODIFY/DELETE
# and prints the new journal lines. Read-only.
python3 -m ms.cli scan --path "/media/shaarky/Data/Shaarav/my songs/"

# After restructuring the library: hash every file into the manifest once, so
# the phone content-copies moved files instead of re-downloading them.
# One-time cost: reads ~26 GB (~1-2 min).
python3 -m ms.cli scan --path "/media/shaarky/Data/Shaarav/my songs/" --hash
```

**When:** to see what the laptop will report as changed, or after a manual
restructure/rename/tag-edit on the laptop.

---

## The journal (the change history / regret log)

```bash
# Read the last N journal lines (what changed, when, by which device):
python3 -m ms.cli log --limit 50
```

**When:** "did X get synced?" or "what did I change two days ago?" — the
journal is the answer.

---

## Verify (integrity check)

```bash
# Re-hash every file and compare against the stored checksums.
# Reports mismatches (tampered/corrupted files) and counts of never-hashed
# (NULL) rows as UNVERIFIED. Read-only.
python3 -m ms.cli verify --path "/media/shaarky/Data/Shaarav/my songs/"
```

**When:** you suspect a file got corrupted, or after a disk hiccup.

---

## Tests (run before declaring anything done)

```bash
# Python engine (79 tests) — from repo root. NOTE: the mDNS discovery test
# fails if a live server is advertising; kill the server first.
python3 -m pytest

# Kotlin engine + Android app (70 + 27 tests) + build the APK:
export JAVA_HOME=$HOME/.local/share/jdks/jdk-21.0.12.1+1
export ANDROID_HOME=$HOME/android-sdk
cd android
./gradlew :engine:test :app:testDebugUnitTest --rerun-tasks
./gradlew :app:assembleDebug
```

---

## Phone / adb

```bash
ADB=~/android-sdk/platform-tools/adb

$ADB devices                        # is the phone connected? (RZCX21YP99E)

# Install / reinstall the APK (keeps the folder grant + database):
$ADB install -r android/app/build/outputs/apk/debug/app-debug.apk

# Launch the app:
$ADB shell am start -n com.musicsyncer.app/.MainActivity

# Read the UI (screencap returns black frames — use uiautomator):
$ADB shell uiautomator dump /sdcard/ui.xml && $ADB shell cat /sdcard/ui.xml

# App logcat (the app logs sync-path details under tag "MusicSyncer"):
$ADB logcat -d -s MusicSyncer

# Pull the phone's Room DB for inspection:
$ADB shell run-as com.musicsyncer.app cat databases/music-sync.db > /tmp/phone.db
```

**When:** installing new builds, reading what the app shows/logs, debugging.

---

## Firewall (one-time, already done)

```bash
sudo ufw allow from 192.168.29.0/24 to any port 8756 proto tcp
```

The phone must reach the laptop on port 8756. If you change the laptop's LAN
subnet, re-run with the new subnet.