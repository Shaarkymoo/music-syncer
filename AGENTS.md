# AGENTS.md — music-syncer agent handover

Everything another agent needs to work on this repo: tools, scripts, scope,
constraints, environment quirks, and current project state. The design writeup
lives in `notes.md`; the execution ledger is `.superpowers/sdd/progress.md`.

---

## 1. What this project is

Bidirectional wireless mirror of a music library between a **Linux laptop**
(canonical copy, folders = playlists) and an **Android phone** (SD card copy).
A Python daemon on the laptop serves scan/merge/apply over HTTP; the Android
app (Kotlin + Jetpack Compose) syncs over Wi-Fi with **manual** Scan / Sync /
Verify buttons. mDNS discovery, no manual IP entry.

**Engine model (locked):**
- Identity = `sha256` of file content (naive engine). Moves/renames =
  DELETE + CREATE (no move detection).
- Full mirror: deletes propagate **both ways**.
- Conflicts (both sides changed a file): LWW (latest writer wins) +
  `.sync-conflict-<timestamp>` copies preserved.
- No trash — the timestamped change **journal** is the regret log.
- Sync = scan both sides → merge plans → apply transfers + deletes → done.

**Stack:** Python 3.11+ engine (`ms/`, stdlib + zeroconf) · pure-JVM Kotlin
engine port (`android/engine/`, no Android deps, tested against the real
Python server via interop tests) · Android app (`android/app/`, SAF, Room,
Compose, black+orange Material 3 dark theme).

---

## 2. Environment & paths

| Thing | Location / value |
|---|---|
| Repo root | `/media/shaarky/Data/Projects/music-syncer` |
| Laptop library (canonical) | `/media/shaarky/Data/Shaarav/my songs/` — **6,053 files, ~26 GB** |
| Phone library | SD card `/storage/3737-6133/my songs` — **6,076 files** (2 laptop-only files to transfer; laptop deleted a ~25-file Bee Gees album the phone still has) |
| App package / launch | `com.musicsyncer.app` / `MainActivity` |
| Phone | Samsung Galaxy M15 (SM-E156B), Android 16 (API 36), adb device `RZCX21YP99E` |
| Phone Wi-Fi IP | `192.168.29.139` (DHCP — may change) |
| Laptop Wi-Fi IP | `192.168.29.105` |
| LAN subnet | `192.168.29.0/24` |
| Server port | **8756** (HTTP, cleartext — LAN-only by firewall) |
| Python venv | Repo `.venv` is **broken** (dangling symlink). Use `python3 -m pytest` — the global venv (`~/.local/share/global-venv/bin/python3`, likely `python3` on PATH) has pytest + zeroconf. |
| JDK (Android builds) | `$HOME/.local/share/jdks/jdk-21.0.12.1+1` (system java is JRE-only) |
| Android SDK | `$HOME/android-sdk` (platform-tools has `adb`) |
| adb | `~/android-sdk/platform-tools/adb` |
| Gradle project | `android/` (settings.gradle.kts; `gradle.properties` pins the JDK path) |
| APK | `android/app/build/outputs/apk/debug/app-debug.apk` (~13.8 MB) |
| Laptop DB | `~/.local/share/music-syncer.db` (SQLite; XDG override via `--db`) |
| Laptop config | `~/.config/music-syncer/config.toml` (absent = defaults; `db_path`, `port`, `apk_path`; `MS_APK_PATH` env overrides `apk_path`) |
| Phone DB (app-private Room) | `/data/data/com.musicsyncer.app/databases/music-sync.db` (pull: `adb shell run-as com.musicsyncer.app cat databases/music-sync.db > out.db`) |
| Server log (when redirected) | `/tmp/msserve.log` |

**Git quirks:** repo files are root-owned → `safe.directory` is set in the
user's global git config (do not fight this). `core.fileMode false` is set
locally. Commit identity: `shaarky`.

---

## 3. Tools & scripts

### 3.1 Python CLI (`python3 -m ms.cli …` — run from repo root, `PYTHONPATH` set or `pip install -e .`)

```bash
# Scan the laptop library and print new journal ops
python3 -m ms.cli scan --path "/media/shaarky/Data/Shaarav/my songs/"

# Start the server (the laptop daemon the phone talks to)
# MUST survive the launching shell → use setsid + redirect + & :
cd /media/shaarky/Data/Projects/music-syncer
setsid env PYTHONPATH=$PWD \
  MS_APK_PATH=$PWD/android/app/build/outputs/apk/debug/app-debug.apk \
  /usr/bin/python3 -m ms.cli serve --path "/media/shaarky/Data/Shaarav/my songs/" \
  > /tmp/msserve.log 2>&1 < /dev/null &

# View the change journal
python3 -m ms.cli log --limit 50

# Verify the laptop tree matches the DB (re-scans + detects deletions)
python3 -m ms.cli verify --path "/media/shaarky/Data/Shaarav/my songs/"
```

All subcommands accept `--db PATH` (before or after the subcommand).

**Server HTTP endpoints** (`ms/server.py`): `GET /handshake` (schema+token),
`GET /manifest` (adopted shas), `GET /journal`, `GET /file?path=`, `GET
/version`, `GET /apk` (self-update payload; needs `MS_APK_PATH`), `POST
/sync`, `POST /file`, `POST /done`. Every request logs a
`[HH:MM:SS] METHOD path -> STATUS (N ms)` line to **stderr** (no silent
failures); stdout stays clean.

**Stop the server:** `pkill -f "[m]s.cli serve"` (the `[m]` prevents the
pkill matching itself).

### 3.2 Tests

```bash
# Python engine (77 tests) — from repo root
python3 -m pytest            # or: python3 -m pytest -v

# Kotlin engine + Android app (65 + 15 tests)
export JAVA_HOME=$HOME/.local/share/jdks/jdk-21.0.12.1+1
export ANDROID_HOME=$HOME/android-sdk
cd android
./gradlew :engine:test :app:testDebugUnitTest --rerun-tasks
./gradlew :app:assembleDebug   # build the APK
```

**Test gotcha:** `tests/test_discovery.py` fails with
`NonUniqueNameException` if a live `msserve` daemon is advertising mDNS.
Pass with **no daemon running** (kill it first).

### 3.3 Phone / adb

```bash
ADB=~/android-sdk/platform-tools/adb
$ADB devices                     # expect RZCX21YP99E  device

# Install / reinstall the APK (user-authorized; keep the folder+DB, just replaces code)
$ADB install -r android/app/build/outputs/apk/debug/app-debug.apk

# Launch the app
$ADB shell am start -n com.musicsyncer.app/.MainActivity

# Read the UI (screencap returns BLACK frames — Compose artifact; use uiautomator)
$ADB shell uiautomator dump /sdcard/ui.xml && $ADB shell cat /sdcard/ui.xml

# Pull the phone's Room DB for inspection
$ADB shell run-as com.musicsyncer.app cat databases/music-sync.db > /tmp/phone.db
```

---

## 4. Scope — what this project does / does NOT do

**In scope (locked decisions, don't change without asking):**
- Full mirror both directions; deletes propagate; folder structure mirrored.
- Manual Scan / Sync / Verify triggers only — **no watchers, no auto-sync**.
- SAF folder picker — **no all-files access** (user refused).
- mp3-only tag editing (the app's Metadata editor).
- Self-update in-app (`/version` + `/apk`).
- mDNS discovery — no manual IP entry (user refused).
- Session-grouped in-app log + 3-bar live progress (phase / playlist / song)
  + server stderr logging — **no silent failures anywhere, ever** (explicit
  user requirement: "i want to see anything and everything that happens
  including what goes wrong").
- Black + orange Material 3 dark theme, Space Grotesk font.

**Out of scope / deferred:**
- Shared-token auth (user: "lets implement that later"). Currently the port
  is protected only by the LAN-only ufw rule.
- Move/rename detection (stays DELETE+CREATE), a media player (Samsung Music
  is the player), Android background auto-sync, trash/recycle bin.

---

## 5. Constraints — hard rules for any agent

1. **The phone is the user's personal device with real data.** Stay in scope,
   and **ask permission before touching anything on the phone**. Where
   stricter permissions are needed, tell the user.
2. **Before doing anything to the phone, write a one-line statement** of
   exactly what is being done and how (user's standing security request).
3. **No silent failures** — app and terminal must surface everything,
   including errors. Never swallow exceptions into blank messages (use
   `e.message ?: e.javaClass.simpleName`).
4. **Never add Android permissions** without flagging the user.
5. **Don't change locked engine/scope decisions** (Section 4) without asking.
6. **Follow existing patterns**: engine semantics must stay identical between
   Python (`ms/`) and Kotlin (`android/engine/`) — the interop tests enforce
   this. Wire format is JSON with `@SerializedName` snake_case + array
   adapters in `Wire.kt`.
7. **Run the full test suites before declaring work done** (Python 77,
   engine 65, app 15 — all must pass; `--rerun-tasks` for Gradle).
8. **UI changes**: user wants to be told all changes and approve what they
   like — summarize proposed UI changes for the user before shipping.
9. **Git:** commit only when asked, or per the SDD task flow. Don't amend
   history; don't touch git config. Don't commit `.superpowers/` (gitignored
   local ledger — append to it, don't commit it).

---

## 6. Current state (last checkpoint: Phase 3 planned, Oct 2026)

- `master` at `1f64ce4` — real-sync debugging round merged (fast-path rel-prefix
  fix, `?`-filename renames, delete-propagation + fresh-state fixes).
- **Real-sync hang fixed** (`02e39e0`): first sync froze 20+ min in `adoptShas`
  (per-path SAF `exists()` ~200ms × 6k files). Now: `exists` predicate param
  (phone passes `{ true }` — post-scan manifest is the disk snapshot), new
  `SyncPhase.ADOPT` with per-file progress. Browser was calling `fs.list()`
  (full 6,076-file recursive SAF walk) per navigation — new `Fs.listDir(rel)`
  lists one level; browse is instant and errors surface.
- **MediaStore fast path** (permission user-approved): optional `READ_MEDIA_AUDIO`
  (Android 13+, runtime, read-only, revocable; denied → graceful SAF fallback).
  When granted, `HybridFs` lists the picked folder via one MediaStore query
  (seconds instead of the ~2.5-min SAF walk); SAF still does all writes/deletes.
  `HybridFs` reconciles index lag (SAF-fallback for manifest rows the index
  missed) and re-stamps sub-second mtime precision artifacts without journaling,
  so the index never causes phantom MODIFY/DELETE storms. UI: "Instant sync is
  off" card with an Enable button on the Status screen.
- **First real sync DONE (verified 2026-10-01):** after the fixes below, the
  phone and laptop are fully converged — 6,053 files each, the 25 Bee Gees
  files deleted from the phone, the 2 laptop-only files (renamed to drop a
  `?` in the filename, which SAF/FAT32 can't store) transferred. Sync now
  takes ~5 s (MediaStore fast list) with zero transfers on identical trees.
  Phone DB state: manifest 6,053, 0 NULL-sha rows, sync_state cursor 6,109.
- **Version 0.1.1 shipped** (wireless self-update works; version bumps are
  REQUIRED for each build — see docs/terminal-commands.md "Building &
  installing updates").
- **Library restructure (user, 2026-10-02):** `playlists2/{albums,lowkey}` →
  `albums/`, `lowkey/` (2,444 files). Server journaled DELETE+CREATE; `ms scan
  --hash` filled all 6,053 shas so the phone content-copies (no downloads).
  A long one-time copy+delete sync was in flight (interrupted once — resumable;
  the SAF dir-document cache in `SafFs` cut per-file cost ~6×; expect ~1-2
  copies/s). **Verify the phone reaches manifest 6,053 / no `playlists2/`
  leftovers before Phase 3 work.**
- **Phase 3 PLANNED — optimization & capability pass.** Scope, tasks, tools,
  and constraints: `docs/superpowers/plans/2026-10-02-phase3-optimizations.md`.
  Confirmed: parallel bulk ops, opt-in folder-move detection, batched DB
  writes, concurrent transfers, wake-lock + free-space, conflict viewer
  (Listen/Keep/Delete), mDNS retry, journal prune (90d, cursor-guarded),
  multi-select, new-playlist + counts in move dialog, sorting, playlist
  rename/create, ETA + empty states, stop button, shared-token auth, library
  stats screen, tag-editor extras (year/composer/track-total), per-playlist
  .m3u export. Out: album art, conflict restore, light theme. **Do NOT start
  implementing without a fresh session reading AGENTS.md + the plan.**
- Known remaining work: shared-token auth (Phase 3 Task 15), stop-scan button
  (Phase 3 Task 14), conflict viewer (Task 6), any follow-ups from the real
  sync test.

**Test counts:** Python 81 · engine 70 · app 28. **Delegated sessions** for
prior work are in `.superpowers/sdd/progress.md`; `notes.md` has the full
project writeup and design rationale.