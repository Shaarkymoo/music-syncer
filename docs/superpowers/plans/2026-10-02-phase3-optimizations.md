# Music-Syncer Phase 3 — Optimization & Capability Pass

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Resuming in a new session?** Read `AGENTS.md` first (tools, scripts, environment, constraints), then this plan. Everything needed to run/test is in Section "Tools & scripts (resume essentials)" below.

**Goal:** Make bulk operations (moves, restructures, initial syncs) fast, make the app pleasant for daily playlist organization, and harden the obvious failure modes — selected by the user from the Phase-3 proposal (items 1-18; #13 album-art skipped by user; #19/#20 pending scope confirmation → Phase 3b).

**Confirmed scope (this plan):**
- **Speed:** parallel bulk ops (copies/deletes/transfers) · opt-in folder-level move detection · batched DB writes
- **Reliability:** wake-lock + free-space check · conflict viewer (Listen/Keep/Delete — NO restore) · mDNS retry + last-known server · journal pruning
- **UI/UX:** multi-select · move-dialog song counts + "new playlist" · sorting + folder counts · playlist create/rename · progress ETA · empty states + completion notification
- **Capability:** stop-scan/sync button · shared-token auth
- **Stats & tags:** library stats screen (songs/size/per-playlist/sync history) · tag-editor extras (year, composer, track N/total — no artwork) · per-playlist `.m3u` export

**Explicitly out of scope:** album art (user skipped), conflict *restore* (user chose viewer without restore), light theme (keeps black+orange dark-only), trash/recycle, auto-sync, move-heuristics beyond the opt-in folder mode, multiple devices.

**Architecture:** The engine is two identical implementations (Python `ms/` laptop daemon, Kotlin `android/engine/` phone brain) with strict parity enforced by interop tests. Per-file SAF I/O is the phone's bottleneck (~0.5-1 s/op); parallelism and whole-folder moves attack it. All UI work is Compose in `android/app/`. The wire protocol stays unchanged except the auth token (Task 15).

**Tech Stack:** Python 3.12 + Kotlin/JVM engine + Jetpack Compose app. New deps: none (concurrency from stdlib / coroutines / ThreadPoolExecutor; StatFs from the Android framework).

## Global Constraints

- **Engine parity:** every semantic change lands in BOTH `ms/` and `android/engine/` with mirrored tests; `PythonInteropTest` must still pass. Where a behavior is Kotlin-only (parallel SAF apply), the Python side keeps identical *results* (same summary, same journal order) even if it stays sequential.
- **Journal order is sacred:** ops must be appended in a deterministic order (LWW + cursors depend on it). Parallelism must re-order results before committing to the journal.
- **No silent failures:** every new path (free-space warning, conflict viewer, auth failure, cancellation) surfaces in the UI/log; `e.message ?: e.javaClass.simpleName` everywhere.
- **The phone is the user's device with real data:** folder-move detection (Task 3) is OPT-IN and must be conservative (only 100% sha-matched subtree moves). Cancellation (Task 14) must never leave partials that the scan can't clean.
- **No new Android permissions** without flagging the user (Task 5's wake-lock needs `WAKE_LOCK` — already in the manifest? NO: it must be added → FLAG TO USER; `POST_NOTIFICATIONS` already present).
- Full test suites green before declaring done (Python 81+, engine 70+, app 28+); `--rerun-tasks` for Gradle.

## Tools & scripts (resume essentials)

```bash
# Server (scan-at-startup + serve :8756) — MUST survive the shell:
setsid env PYTHONPATH=$PWD \
  MS_APK_PATH=$PWD/android/app/build/outputs/apk/debug/app-debug.apk \
  /usr/bin/python3 -m ms.cli serve --path "/media/shaarky/Data/Shaarav/my songs/" \
  > /tmp/msserve.log 2>&1 < /dev/null &
pkill -f "[m]s.cli serve"      # stop (the [m] avoids self-match); kill by PID in scripts
# Tests:
python3 -m pytest               # Python (81) — mDNS discovery test fails if a server advertises
export JAVA_HOME=$HOME/.local/share/jdks/jdk-21.0.12.1+1
export ANDROID_HOME=$HOME/android-sdk
cd android && ./gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug --rerun-tasks
# Phone:
ADB=~/android-sdk/platform-tools/adb
$ADB devices; $ADB install -r android/app/build/outputs/apk/debug/app-debug.apk
$ADB shell uiautomator dump /sdcard/ui.xml && $ADB shell cat /sdcard/ui.xml   # UI (screencap is black)
$ADB shell run-as com.musicsyncer.app cat databases/music-sync.db > /tmp/phone.db   # pull DB
$ADB logcat -d -s MusicSyncer  # app logs tag "MusicSyncer" (lister counts, HybridFs timing)
# In-app wireless update: bump versionCode/versionName (build.gradle.kts) + APP_VERSION (ms/server.py),
# restart server, phone taps Check for update.
# Journal/scan CLIs: msscan, mslog, ms verify (see docs/terminal-commands.md).
```

## File Structure (changes only)

```
ms/
  apply.py           # Task 1: batched writes (parallel stays Kotlin-side; results identical)
  merge.py           # Task 3: folder-move detection → plan.moves
  client.py          # Task 4: concurrent transfers; Task 14: cancellation
  server.py          # Task 15: token auth on all endpoints
  db.py              # Task 8: journal prune
  cli.py             # Task 8: prune flag; Task 3: --moves flag; Task 15: token env
  config.py          # Task 8: journal_retention_days; Task 15: token
android/engine/:
  Apply.kt           # Tasks 1,3: parallel copy/delete, move-dir execution, batch flush
  Merge.kt           # Task 3: moves in Plan
  SyncClient.kt      # Tasks 4,14: concurrent transfer, cancellation
  Store.kt           # Task 1: beginBatch/endBatch (Room/JdbcStore)
  JdbcStore.kt / (RoomStore in app)  # Task 1: batch support
android/app/:
  sync/SyncController.kt   # Task 5: wake-lock + free-space; Task 14: cancel flag; Task 13: notification
  sync/Updater.kt / Discovery.kt    # Task 7: retry + last-known fallback
  ui/BrowserScreen.kt      # Tasks 9,10,11,12: multi-select, new playlist, sorting, playlist mgmt
  ui/StatusScreen.kt       # Tasks 13,14: ETA, cancel button, empty states
  ui/ConflictsScreen.kt    # Task 6 (new)
  ui/SettingsScreen.kt     # Task 15 (new): token entry; Task 3: moves toggle
  fs/SafFs.kt              # Task 3: moveDir (directory rename); Task 5: free-space via volume path
  store/RoomStore.kt       # Task 1: batch
tests: mirror each change in tests/ + engine tests; app tests for pure logic.
```

---

## Task 1: Batched DB writes (both engines)

**Files:** `ms/apply.py`, `android/engine/Apply.kt`, `Store.kt`, `JdbcStore.kt`, `RoomStore.kt` + tests.

**Interfaces:** add to `SyncStore`/Python conn helpers: `begin_batch()/end_batch()` (or a `batch: List[...]` flush in apply). Each copy/delete/transfer currently issues per-file journal+manifest transactions; accumulate per directory group (e.g. flush every 50 ops) in ONE transaction. **Journal order must be preserved** — batch in the same sequence the sequential loop would produce.

**Acceptance:** identical summaries/journal ordering vs sequential; a bulk-move integration test still passes; no visible behavior change.

## Task 2: Parallel bulk apply + per-file progress (Kotlin; Python sequential with identical results)

**Files:** `android/engine/Apply.kt` + `ApplyTest.kt`, `SyncIntegrationTest.kt`, `ms/apply.py`.

**Interfaces:** applyPlan runs fetch/copy/delete with a `ThreadPoolExecutor(4)` (or coroutines); per-file results collected with their index and re-ordered before summary/journal commit (journal order preserved). Deletes still run after all fetches complete. **Both applyPlan/apply_plan gain a `progress` callback emitting TRANSFER-phase events per copied/deleted file** (the copy/delete loops are currently SILENT — the UI froze at the plan count during the 2,444-file restructure, looking crashed). Python stays sequential but emits the same events.

**Acceptance:** on a JVM integration test with a few hundred files, wall time drops ≥2×; summaries and journal order byte-identical to sequential; per-file progress events fire for every copy and delete; `PythonInteropTest` still passes.

## Task 3: Folder-level move detection (OPT-IN, both engines)

**Files:** `ms/merge.py` + `Merge.kt` (Plan gains `moves: list[(oldDir, newDir)]`), `Apply.kt`/`ms/apply.py` (execute via new `Fs.moveDir(oldDir, newDir)`), `SafFs.kt` (directory `renameTo` — SAF supports same-parent dir rename; the restructure `playlists2/albums` → `albums` is same-parent), `PathFs`/Python (os.rename of a dir), `SyncController`/`config.toml`/`ms/cli.py` toggle, `SettingsScreen` toggle (default OFF).

**Rule (conservative):** a set of `{DELETE old, CREATE new}` plan items where every `old` file's sha exists under `new` with the same size AND every file under `oldDir` is accounted for AND `oldDir`/`newDir` share a parent → ONE `moveDir`. Anything less than 100% match falls back to per-file.

**Acceptance:** a restructure integration test produces a single `moveDir` (no fetch/delete); toggle OFF keeps per-file behavior; interop passes.

## Task 4: Concurrent transfers (both clients)

**Files:** `SyncClient.kt`, `ms/client.py` + tests.

**Interfaces:** fetch/push loops run N=4 concurrent HTTP requests (OkHttp sync calls in an executor; Python ThreadPoolExecutor). Progress emits per completed file (order may interleave; counts must be right). The server is `ThreadingHTTPServer` — safe.

**Acceptance:** a multi-file transfer integration test still converges; progress totals correct.

## Task 5: Wake-lock + free-space check (app)

**Files:** `SyncController.kt` + manifest + `SafFs`/helper.

**Interfaces:** acquire a partial `WAKE_LOCK` (+ `WifiLock` high-perf) at sync start, release in `finally`. **FLAG USER:** adds `android.permission.WAKE_LOCK` (normal permission, auto-granted). Free space: resolve the volume root path (`/storage/<volumeId>`) → `StatFs` → before transfers, if `needsBytes > availableBytes` set a warning in the summary/UI (not a hard stop). Settings hint: "unrestricted battery" for the app.

**Acceptance:** wake-lock acquired/released around a session (unit-testable via a fake lock interface); free-space warning appears when simulated.

## Task 6: Conflict viewer (app UI)

**Files:** `ui/ConflictsScreen.kt` (new) + wiring in `MainActivity`/nav; filter `.sync-conflict-*` from the fast list.

**Interfaces:** a nav entry (or Browse section) listing conflict files (path, ts, size) with actions: **Listen** (external player), **Keep** (rename to `original.sync-conflict-<ts>.mp3` → `original.mp3` — collision-guarded), **Delete**. Restore/swap deferred to Phase 3b.

**Acceptance:** conflict files listed; Keep/Delete produce correct journaled ops; empty state message.

## Task 7: mDNS retry + last-known server (app)

**Files:** `sync/Discovery.kt`, `SyncController.kt`, `StatusScreen.kt`.

**Interfaces:** "Find laptop & sync" does up to 2 discovery passes (e.g. 3s each); on failure, if a persisted server URL exists, offer "Use last-known server (…)" instead of a bare failure; a Retry button.

**Acceptance:** with the server down, the flow shows the last-known option, not a dead end.

## Task 8: Journal pruning (engine + CLI)

**Files:** `ms/db.py` (prune), `cli.py` (`log --prune` + auto at server start), `config.py` (`journal_retention_days`, default 90), Kotlin mirror `JdbcStore`/`RoomStore`.

**Rule (threshold answer):** prune ops OLDER than `journal_retention_days` (default **90 days**) but NEVER past the oldest peer cursor (an unsynced device must not lose ops). Runs at server start and via `ms log --prune`.

**Acceptance:** ops older than retention are removed; ops newer than any peer cursor survive; integration test with an old-op journal + a stale cursor.

## Task 9: Multi-select in Browse (app)

**Files:** `ui/BrowserScreen.kt`.

**Interfaces:** long-press a song → selection mode (checkboxes, count bar); actions: **Move to playlist** (reuses the Task-10 dialog, batch copy+delete), **Delete** (one confirm). Exit on back/nav.

**Acceptance:** batch move/delete of N songs journals N correct ops; progress visible; selection clears on navigate.

## Task 10: Move dialog — song counts + "New playlist" (app)

**Files:** `ui/BrowserScreen.kt`.

**Interfaces:** each playlist row shows its song count (`listDir` sizes); a **+ New playlist** row → inline text field → `mkdirs("playlists/<name>")` → immediately moves the song(s) into it.

**Acceptance:** counts shown; creating a playlist from the dialog works and is journaled (empty playlist visible to the engine on the next scan).

## Task 11: Sorting + folder counts (app)

**Files:** `ui/BrowserScreen.kt`.

**Interfaces:** a sort toggle (name / size / date) applied to the current listing; folder rows show the count of songs inside (from the full fast list).

**Acceptance:** sorts correct; counts correct.

## Task 12: Playlist management (app)

**Files:** `ui/BrowserScreen.kt`.

**Interfaces:** long-press a playlist folder → **Rename** (same-parent SAF rename) / **Delete** (only when empty; destructive file-delete of a playlist is NOT included — phase 3b with confirmation).

**Acceptance:** rename works (journaled DELETE+CREATE via the scan); delete of an empty playlist works.

## Task 13: Progress ETA + empty states + completion notification (app)

**Files:** `ui/StatusScreen.kt` (ProgressCard), `SyncController.kt`.

**Interfaces:** ETA on the phase bar = `elapsed * (total-done)/done` when done>0; per-folder "No songs in this playlist" empty state in Browse; a `POST_NOTIFICATIONS` completion notification on sync success/failure.

**Acceptance:** ETA renders sensibly; empty state shows; notification fires (permission already requested; if denied, no crash).

## Task 14: Stop-scan/sync button (cooperative cancellation, both clients + app)

**Files:** `SyncClient.kt`, `ms/client.py`, `SyncController.kt`, `StatusScreen.kt`.

**Interfaces:** a `cancel` flag checked at every loop boundary (scan per-file, adopt per-path, copy/delete per-file, transfer per-file); on cancel, abort cleanly (flush partials so the scan cleans them; summary reflects partial work; no /done). App: a **Cancel** button on the progress card → sets the flag. Reuses the existing busy-guard.

**Acceptance:** cancellation mid-sync returns control <2s, leaves the DB consistent (resumable), and the next sync continues cleanly.

## Task 15: Shared-token auth (server + client + app settings)

**Files:** `ms/server.py`, `ms/config.py`, `ms/client.py`, `SyncClient.kt`, `JvmServer.kt`, `Wire.kt`, `SyncController.kt`, `ui/SettingsScreen.kt`, `docs/security.md`.

**Interfaces:** server reads `MS_TOKEN`/`config.toml` token; every endpoint (except handshake which FAILS) requires `Authorization: Bearer <token>` (or a `?token=` query for GET); client sends it after handshake (handshake returns a `token_required` flag); app: token field in Settings, stored in prefs. Wrong/missing token → 401 with a clear message.

**Acceptance:** no token → handshake 200 (says auth required) but /sync 401; correct token → full session works; interop test covers the 401 path.

## Task 16: Library stats screen (app)

**Files:** `ui/StatsScreen.kt` (new) + nav wiring; reads `store.manifestAll()` + journal.

**Interfaces:** total songs, total size, per-playlist song counts (folders under `playlists/`), last-sync time, and a compact sync-history view (recent `DONE` sessions with per-phase timing from the persisted summary). Reuses the fast list for folder counts.

**Acceptance:** counts match `fs.list()`; renders on real data; empty state before the first sync.

## Task 17: Tag-editor extras (app)

**Files:** `tag/TagEditor.kt`, `ui/EditorScreen.kt` + tests.

**Interfaces:** add `year`, `composer`, `trackTotal` to `SongTags`; write via `FieldKey.YEAR`, `FieldKey.COMPOSER`, and `TRCK` as `N/total`; read them back. No artwork.

**Acceptance:** roundtrip test on a temp mp3 (title/artist/…/year/composer/track-total); existing editor layout unchanged otherwise.

## Task 18: Per-playlist `.m3u` export (app)

**Files:** `ui/BrowserScreen.kt` (or playlist context menu), a small `M3uWriter` (pure fn, testable).

**Interfaces:** from a playlist folder's context menu, "Export .m3u" writes `playlists/<name>/<name>.m3u` with one song file name per line (EXTM3U header). The exported file itself syncs to the laptop like any other file (engine treats it as a normal file — harmless). Note: this is a convenience export for external players, not a Samsung-Music playlist (it has its own DB).

**Acceptance:** writer unit test; the file appears in Browse and syncs.

---

## Explicitly out of scope (user decisions)

- Conflict **restore** (reversing LWW) — viewer is Listen/Keep/Delete only.
- Light theme / theme-follows-system — black+orange dark stays.
- Album art in any form (browse/editor).

## Test counts expected after Phase 3

Python 81+ (auth, prune, moves, concurrent, cancellation) · engine 70+ · app 28+ (multi-select logic, batch, conflicts filter, ETA, free-space, stats, m3u writer, tag roundtrip). Full suites + interop must pass; `ms verify` unaffected.

## Priorities / order of execution

1. Task 2 (parallel apply) + Task 1 (batching) — the speed win for the current pain
2. Task 3 (folder moves) — the restructure win
3. Task 14 (stop button) — user-facing control
4. Task 9/10 (multi-select + new playlist) — daily playlist workflow
5. Tasks 5-8 (reliability), 11-13 (UX), 15 (auth) in any order after