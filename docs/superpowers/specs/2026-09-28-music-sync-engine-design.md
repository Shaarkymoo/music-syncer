# Music Syncer — Design Spec

**Date:** 2026-09-28
**Status:** Approved by user (pending written-spec review)

## 1. Problem

The user maintains a canonical music library on a Linux laptop
(`/media/shaarky/Data/Shaarav/my songs/`, ~26 GB, ~6,050 mp3 + 25 m4a, organized
as subfolders = playlists). Music is offloaded to an Android phone for listening
via Samsung Music. Today this is a manual, one-way, monthly copy:

- Changes made on the phone (delete, move between playlist folders, rename) are
  lost — they must be remembered and manually replayed on the laptop.
- Changes on the laptop (new downloads via yt-dlp, renames, moves, lyrics edits)
  require a long manual sync session.

Goal: a two-way mirror that records every change with a timestamp, and syncs the
two devices over the local Wi-Fi network so both end up identical.

## 2. Goals

- Full two-way mirror of the music folder between Linux laptop and Android phone.
- Every change recorded and timestamped in a human-readable journal (the "regret
  log") so the user can see what changed when, and re-download anything deleted.
- Wireless sync over the local network when both devices are reachable.
- Manual trigger: user taps "Sync" on the phone and/or runs a command on the
  laptop. No background automation.
- Samsung Music remains the phone player; the sync app does not integrate with it
  for playback.
- In-app file/folder browsing (playlists = folders), so renaming/moving playlists
  does not require Samsung Files.
- In-app metadata editing on the phone: filename, title, artist, album, album
  artist, genre, track number, and lyrics — written into the file and synced to
  the laptop as an ordinary MODIFY.
- "Send to playlist" in two taps: move a song to another folder, synced both ways.
- Sync must work without Wi-Fi too: over a phone/laptop hotspot, USB tethering,
  or ADB forwarding — same protocol, manual-IP target.

## 3. Non-goals (explicitly cut)

- No playback UI. Samsung Music remains the player; this app adds browsing,
  editing, and sync.
- No all-files-access permission. Phone folder access via SAF folder picker
  (scoped, read-write, persisted).
- No 24/7 watchers, no foreground service, no auto-trigger on network presence.
- No trash folder. Deletes are permanent; the journal is the record.
- No rename/move detection in the ENGINE: identity is the content hash only; a
  rename is DELETE + CREATE. The engine does NOT parse tags or fingerprint
  metadata to infer moves. Duplicates (a song copied into two playlists) are
  preserved as-is — never misclassified as moves.
- The ANDROID APP does read/write tags for editing (that is its job); a tag edit
  surfaces to the engine as an ordinary MODIFY (same path, new hash).
- No internet dependency: v1 sync is local-network only (no cloud relay).
- No authentication (trusted home LAN). Shared token is a later option.
- Conflict policy is last-writer-wins by timestamp, with the loser preserved as a
  `.sync-conflict-<ts>` file. Full clash UX is deferred.

## 4. Architecture

**One engine, two deployments.** Both devices run the same logic:
change detection (scan) → journal → sync session. The engine core operates on a
*directory abstraction*, so it is testable on the laptop against two temp folders.

- **Linux daemon (Python):** watches/owns the laptop library; runs an HTTP server
  for sync sessions; advertises via mDNS. Started manually (`ms serve`).
- **Android app (Kotlin):** sync client; user points it at the music folder once
  via the SAF folder picker; Scan and Sync buttons; journal/log screen.

**Transport roles:** the phone initiates; the laptop is the passive server. Both
run the same engine logic; only the transport role differs.

**Engine metadata lives OUTSIDE the music folder** (so it never syncs itself):

- Laptop: `~/.local/share/music-syncer/` (SQLite DB, config).
- Phone: app-internal storage (SQLite via Room).

## 5. Data model (identical SQLite schema, both sides)

### `manifest`
Last-known state per file; the scan diffs disk against this.

| column | type | notes |
|---|---|---|
| `path` | TEXT | relative path from music root; PK |
| `size` | INTEGER | bytes |
| `mtime_ns` | INTEGER | mtime in nanoseconds — fast fingerprint |
| `sha256` | TEXT | hex; NULL until first hash |
| `last_seen_ns` | INTEGER | when this state was recorded |

### `journal`
Append-only change log; never purged. This IS the readable regret log.

| column | type | notes |
|---|---|---|
| `id` | INTEGER | PK, autoincrement; also the per-peer cursor |
| `op` | TEXT | `CREATE` \| `MODIFY` \| `DELETE` |
| `path` | TEXT | relative path |
| `size` | INTEGER | |
| `sha256` | TEXT | NULL for DELETE |
| `ts_ns` | INTEGER | scan time (approximate change time — accepted trade-off) |
| `device` | TEXT | which device recorded the op |

### `sync_state`
Per-peer sync progress.

| column | type | notes |
|---|---|---|
| `peer_device_id` | TEXT | PK |
| `last_seen_journal_id` | INTEGER | ops from us that this peer has seen |
| `last_sync_ns` | INTEGER | |

**No `MOVE` op.** Moves decompose into `DELETE` (old path) + `CREATE` (new path).

## 6. Change detection (scan-on-demand)

Triggered manually: phone "Scan" button / laptop `ms scan`, and automatically as
part of every sync session on both sides.

1. Walk the music tree (recursively; ignore hidden files/dirs).
2. Compare each entry against `manifest`:
   - new path → hash it → `CREATE`
   - same path, same `size` + `mtime_ns` → unchanged, skip (no re-hash)
   - same path, different size/mtime → re-hash → same sha256: no-op (update
     manifest); different sha256 → `MODIFY`
   - path in manifest, absent from disk → `DELETE`
3. Full-library hashing happens once (first scan); afterwards only candidates are
   hashed. 26 GB full hash ≈ minutes; incremental scans are fast.

**Duplicate safety:** a file with identical content at two paths is two library
entries. Both are mirrored. The engine never infers moves, so copies can never be
mistaken for moves.

## 7. Sync protocol

HTTP/1.1 over the local network, fixed port (default 8756). mDNS service name:
`music-syncer._tcp` (laptop advertises; phone discovers via `NsdManager`; manual
IP entry as fallback in the app settings).

**Session (phone = client, laptop = server):**

1. **Handshake** — exchange `{device_id, schema_version, journal_head}`. Reject on
   schema mismatch.
2. **Journal exchange** — each side sends its journal ops since the peer's cursor
   (phone POSTs to server; server includes its ops in the response).
3. **Reconciliation** — each side sends its manifest; both diff against their own:
   - file on remote, not local → need transfer
   - both, different sha256 → `MODIFY` (conflict rule §8)
   - in local manifest, absent from remote manifest AND no DELETE op covering it →
     remote deleted it → `DELETE` locally
   - dedupe with received journal ops; apply idempotently (skip if state already
     matches — duplicate ops collapse).
4. **Transfer plan** — ordered:
   a. create parent directories
   b. **content-addressed skip:** before streaming, if the remote already holds a
      file with the same sha256 under ANY path, copy it locally instead of
      transferring bytes. Makes moves/renames cost zero transfer.
   c. stream remaining files to temp names (`.ms-partial-*`), verifying sha256 on
      both ends
   d. atomic rename into place
   e. **deletes last**
5. **Commit** — update `manifest`, journal applied remote ops (tagged with the
   source device), advance peer cursor in `sync_state`.
6. **Post-sync (phone only)** — `MediaScannerConnection.scanFile()` on every
   changed path so Samsung Music's index reflects the new state.

**Endpoints (laptop server):**

| endpoint | purpose |
|---|---|
| `GET /handshake` | session metadata |
| `POST /sync` | phone sends device_id, journal cursor, manifest; response carries server journal delta + manifest |
| `GET /file?path=<rel>` | phone pulls a file from laptop |
| `POST /file?path=<rel>` | phone pushes a file to laptop |

**Crash safety:** temp files are cleaned on next session start; ops are idempotent;
the journal is never purged, so an interrupted session is simply re-attempted.

## 8. Conflict resolution (default; refinement deferred)

- Same path, both sides changed (different sha256): **last-writer-wins** by
  `ts_ns`. App-driven edits are journaled at action time (exact timestamps);
  scan-detected changes use scan time (approximate). Both sides may now edit
  metadata — the same rule applies.
- Loser is preserved as `path.sync-conflict-<ts>.mp3` on the side that had it, and
  the event is journaled. Nothing is silently dropped.
- True two-sided edits are expected to be rare (single user; only the laptop edits
  metadata). Policy is tunable later without touching the rest of the engine.

## 9. Journal / readable log

- The `journal` table is the log. Never purged.
- Laptop: `ms log` prints entries as readable lines
  (`2026-09-28 14:03  laptop  MODIFY  Favorites/Song A.mp3  sha256:...`).
- Phone: a Log screen listing the same entries.
- Regret flow: user reads the log, re-downloads the song manually (e.g. yt-dlp).

## 10. Linux daemon (Python)

Small modules in this repo:

| module | responsibility |
|---|---|
| `engine/` | core: scan, journal, diff/merge, apply — OS-independent (directory abstraction) |
| `watcher` | **not in v1** (cut — scan-on-demand only) |
| `db.py` | SQLite access (schema §5) |
| `server.py` | HTTP endpoints + mDNS advertisement (`zeroconf`) |
| `sync.py` | session orchestration (server side) |
| `cli.py` | `ms serve` / `ms scan` / `ms log` |

CLI (with suggested shell aliases):

- `ms serve` — scan, then listen for a phone sync session (Ctrl-C to stop).
- `ms scan` — scan + update journal/log without a session.
- `ms log` — print the readable journal.

Config file (e.g. `~/.config/music-syncer/config.toml`): `base_path`, `port`,
`db_path`. Only `base_path` is required.

## 11. Android app (Kotlin)

- **Folder access:** SAF `ACTION_OPEN_DOCUMENT_TREE` picker, one-time, persisted
  (`takePersistableUriPermission`). No all-files permission. The user points it at
  the main music folder (contains only music).
- **UI:**
  - **Browser** — folder tree (playlists); tap a folder to list songs; song
    actions: **Rename** (filename), **Edit tags**, **Move to folder** ("send to
    playlist", 2 taps), **Delete**. (Playback stays in Samsung Music; optionally
    a best-effort `ACTION_VIEW` intent to hand a file to it.)
  - **Metadata editor** — title, artist, album, album artist, genre, track
    number, and a multi-line **lyrics** field. Writes ID3v2 frames (mp3) / MP4
    atoms (m4a) into the file.
  - **Status screen** — folder status, laptop discovered?, last sync, transfer
    counts, manual laptop address.
  - **Scan** button — run the scan (§6), refresh the log.
  - **Sync** button — discover laptop (mDNS `NsdManager`, manual-IP fallback),
    run the session (§7), then the MediaStore rescan.
  - **Log** screen — readable journal entries (same as `ms log`).
- **App actions are journaled immediately at action time** (exact timestamps for
  LWW); the scan (§6) remains the safety net for everything else.
- **No foreground service, no watcher, no auto-trigger** (v1).
- **Dependencies:** Room (SQLite), OkHttp (HTTP), NsdManager (discovery), SAF
  DocumentFile, `MediaScannerConnection`, **jaudiotagger** (ID3v2 + MP4 tag
  read/write).
- **Implementation risk to verify:** jaudiotagger's MP4 lyrics support (`©lyr`) —
  if unsupported, disable lyrics editing for the 25 m4a files in v1 (mp3 is the
  primary path).
- **Android quirks to handle in implementation:** SAF tree access is slower than
  raw paths for large scans (acceptable at ~6k files); MediaStore may leave stale
  entries after deletes (player-side refresh behavior is out of scope).

## 12. Testability

- The `engine/` core operates on a directory abstraction → integration tests run
  entirely on the laptop: two engine instances over two temp folders syncing via
  the HTTP protocol on localhost, asserting convergence and hash verification.
- Unit tests: change detection (size/mtime gate, hash), diff/merge, LWW + conflict
  copies, idempotent apply, journal rebuild from scan, delete application,
  duplicate preservation.
- **Correctness focus:** delete application (a missed delete = duplicate ghosts; a
  wrong delete = data loss) and idempotency.

## 13. Open items / runtime config (not spec gaps)

- Phone music folder path — chosen in-app via SAF picker at first run.
- Laptop base path — config file; defaults to the user's library path.
- Port default 8756; adjustable in config if it collides.
- mDNS service may need `avahi` running on the laptop; manual-IP fallback covers
  cases where it is unavailable.

### Transports when there is no Wi-Fi

Same protocol; only the discovery/target changes. The manual-IP field covers all
of these with zero code changes:

| transport | how | cost |
|---|---|---|
| Phone hotspot (works offline) | laptop joins the phone's hotspot; mDNS works on it | free |
| Laptop hotspot | `nmcli device wifi hotspot`; phone joins | free |
| USB tethering | phone shares network over USB; laptop uses it; manual-IP | free |
| ADB forward | `adb forward tcp:8756 tcp:8756`; phone targets `127.0.0.1:8756` | free (one command) |

Deferred: Bluetooth PAN (fiddly, ~2-3 Mbps), cloud relay (needs a server + auth),
offline bundle (zip journal + changed files for manual transfer).

## 14. Explicitly deferred

- Auto-sync trigger on same-Wi-Fi.
- FileObserver / always-on watcher with true change timestamps.
- mDNS on the laptop auto-start (systemd service).
- Trash folder, playback UI, auth token.
- Clash UX refinement beyond LWW + `.sync-conflict` copies.
- Bluetooth PAN transport, cloud relay, offline bundle export.
- Laptop-side tag editor (laptop metadata edits continue via existing tools; they
  flow through the engine as MODIFY).