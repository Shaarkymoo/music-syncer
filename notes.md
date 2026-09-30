# Music-Syncer — Project Notes & History

A complete writeup of the project: what it is, every decision and why, what was
built, the bugs found (and how), and where things stand.

---

## 1. What it is

A bidirectional wireless mirror of a music library between a Linux laptop and an
Android phone. The laptop holds the canonical library organized as
**folders = playlists** (`/media/shaarky/Data/Shaarav/my songs/`, ~26 GB,
~6,050 mp3). The phone mirrors it. Every change on either device is recorded in
a timestamped journal, and when both devices are on the same network the user
taps Sync and the two converge.

The user's original problem: music was offloaded one-way to the phone; changes
made on the phone (delete, move between playlists, rename) were lost and had to
be manually replayed on the laptop. A monthly manual sync was "a long day's
work."

---

## 2. Design decisions (and why)

| Decision | Choice | Reason |
|---|---|---|
| Build vs buy | Custom engine (not Syncthing) | The user wanted to build it; a custom journal gives full control (conflict policy later, exact change history) |
| Sync semantics | **Naive engine**: identity = content hash (sha256) only; a rename/move = DELETE + CREATE; no move inference, no tag parsing | The user chose this: re-downloading a 5 MB file is cheap; and move-heuristics can misclassify copies as moves and *delete* real duplicates |
| Mirror model | **Full mirror** (phone = complete copy of laptop) | Simple, symmetric deletes; "I delete on the phone = I delete from the library" |
| Phone folder access | **SAF folder picker** (scoped grant, no all-files access) | The user refused all-files permission; SAF is the Android-idiomatic minimal grant |
| Player | **No player** — Samsung Music stays the player; the app is a file manager + syncer | Keep scope small; the app's job is sync + file ops (rename/move/delete/tag-edit) |
| Tag editing | **mp3 / ID3v2 only** (jaudiotagger), incl. lyrics (USLT frame) | Only mp3 matters (m4a files were leftovers being converted) |
| Trigger model | **Manual** — Scan / Sync / Verify buttons; no background service, no watcher, no auto-trigger | The user explicitly wanted manual control; automation can come later |
| Discovery | **mDNS** (`_music-syncer._tcp`), no manual IP entry | Home Wi-Fi has DHCP, so manual IPs break |
| Conflicts | Last-writer-wins by op timestamp; loser kept as `.sync-conflict-<ts>` file | Clash UX deferred by the user; LWW is the safe default |
| Trash | **None** — deletes are permanent; the journal is the "regret log" | The user prefers to re-download if they regret a delete |
| Transports | Wi-Fi, phone hotspot, laptop hotspot (LAN-only) | No internet relay; the user may be without Wi-Fi sometimes |
| In-app updates | **Yes** — app downloads the new APK from the laptop server and installs it | No adb reinstall needed |
| UI | **Black + orange M3 dark theme**, Space Grotesk type, edge-to-edge bars | The user's requested aesthetic |

---

## 3. Architecture

Two implementations of the SAME engine:

- **Python laptop daemon** (`ms/`): `scan` (change detection), `merge` (plan),
  `apply` (mutations), `client`/`server` (HTTP sync session), `cli` (`ms scan /
  serve / log / verify`). Runs the passive server on port 8756.
- **Kotlin engine** (`android/engine/`): a pure-JVM port of the same semantics —
  the Android app's brain. Tested on the JVM against temp folders, plus
  interop tests that run the **real Python server**.
- **Android app** (`android/app/`): SAF folder access, Room store, mDNS
  discovery, the 4 screens (Status, Browser, Log + Metadata editor), the
  sync controller, and the updater.

The sync protocol: phone initiates → handshake → journal exchange → manifest
reconciliation → **sha adoption** (identical files adopt the peer's checksum, no
re-transfer) → transfer only changed files (verified by hash) → apply
deletes-last → done.

The engine depends on two abstractions so it runs on both JVM and Android:
`Fs` (filesystem over relative paths) and `SyncStore` (persistence).

---

## 4. Build history — what happened, in order

### Phase 0 — Design (brainstorming)
Explored the whole space of phone↔laptop transfer methods, then settled the
scope through many clarifying rounds. The user's constraints shaped everything:
manual sync, no player, no all-files permission, full mirror, naive engine,
mp3-only, journal-as-regret-log.

### Plan 1 — Python engine + Linux daemon (10 tasks, all reviewed)
Built the engine the user runs as `msserve`/`msscan`. **Notable bugs caught by
the review process before they shipped:**

| Bug | How it would have failed | Fix |
|---|---|---|
| Server DB connection was main-thread-only | Every HTTP request would 500 | `check_same_thread=False` on the SQLite connection |
| Phone-side deletes never propagated | A deleted file would be "resurrected" on the laptop (and crash on phone-side deletes) | The unseen-local-DELETE gate in `merge` |
| Conflict files renamed *after* the winner overwrote the path | The loser preservation destroyed the winner | Conflict renames moved before fetches |
| `verify` re-scanned before comparing | Tamper detection defeated (it re-hashed the tampered file into the manifest) | Verify is a pure re-hash against stored hashes |
| mDNS advertised `0.0.0.0` | Discovery returned an unconnectable address | Advertise a real local IP |
| Default DB path computed to `~/.config` | Spec violation | XDG-data-home-aware default |
| Configured port never bound | `--port` was ignored; printed wrong port | `SyncServer` gained a `port` param |
| Client passed peer-cursors swapped vs the contract | Latent delete/resurrection risk | Swapped to the correct semantics |
| Merge was O(paths × journal) | Minutes of planning per sync as the journal grows | `ops_by_path` index |

### Plan 2A — Kotlin engine port (8 tasks, all reviewed)
A faithful JVM port of the Python engine — including every fix above — so the
Android app inherits a *corrected* engine, not the original bugs. Two real
catch: sqlite-jdbc executes only the first statement of a multi-statement
string, and small JDBC ints need `Number` conversion; then the **binary-safe
transfer** issue (HTTP body decoded as a string would corrupt files).

### Plan 2B — Android app (9 tasks, all reviewed)
SAF picker, Room store, mDNS discovery, Browser + Metadata editor + Log +
Status screens, buildable APK. **The final review caught a showstopper:** the
Kotlin engine's JSON (camelCase DTOs) did not match the Python server's wire
format (snake_case + array manifests) — the Kotlin↔Kotlin tests masked it. Fixed
with `@SerializedName` + array adapters, and a **real interop test** that syncs
the Kotlin client against the actual Python server. Also fixed: cleartext HTTP
is blocked by default on Android 9+ (manifest flag), and the `SafFs.write` bug
(requiring a file to exist before writing new files).

### Phase 2A — Lazy hashing + sha adoption + progress hooks
The user's first real test was painful: the initial scan hashed 26 GB through
SAF and the first transfer moved 26 GB. Fix: **lazy scan** (metadata-only; the
manifest holds `sha = NULL` until a file transfers), **sha adoption** (when
sizes match exactly, adopt the peer's checksum — identical trees sync with
zero transfer), and **progress hooks** (SCAN/PLAN/TRANSFER/DONE events) on both
implementations.

Measured result: laptop scan of 6,053 files dropped from **~3.5 min to 0.48s**;
the phone's identical files adopt the laptop's checksums with no re-transfer.

**A subtle bug caught by the final review:** size-only adoption ignored the
journal — if a file was modified to the *same byte size*, the peer's stale
checksum got adopted and the change silently froze, forever. Fixed with a
journal-aware guard (skip adoption/identity for paths with an unseen local
MODIFY) and a regression test that reproduced the exact failure.

### Phase 2B — UI overhaul + self-update + security doc
Black+orange M3 dark theme, edge-to-edge app bars, Space Grotesk type, live
progress card, session-grouped readable log, in-app updates (`/version` +
`/apk` on the server), and `docs/security.md` answering exactly what is
exposed, what is always on, and where it works.

---

## 5. The current debugging session (2026-09-30)

The user reported "Find laptop and sync" stuck on "Working." Diagnosis by
running the app on the phone via adb:

1. **The laptop server was not running** → the phone's connect attempt timed
   out after 10 s and showed a cryptic error. (My first attempt to start it
   also died when the launching shell was killed; fixed with `setsid`.)
2. **The phone's SAF tree walk is the new invisible wall.** Measured: a plain
   Scan shows "Working" for ~2 minutes with zero progress, because
   `SafFs.list()` walks 6,067 files via DocumentFile before the scan loop
   (which emits progress) even starts. And `sync()` scans **twice** (once in
   `sync()`, once inside `runSyncSession`), so a sync is ~4-5 min of walking.
3. **The phone could not reach the laptop:8756** even with the server up —
   ICMP worked, mDNS worked, but TCP to 8756 (and 22) timed out. The laptop's
   firewall is **ufw active** → inbound TCP from the Wi-Fi is dropped. Fix:
   a ufw allow rule for port 8756 from the LAN (or the phone's IP).

User feedback driving the next work:
- **No silent failures** — everything must be visible in-app and in the
  terminal, including what goes wrong.
- **tqdm-style progress**: a top bar of phases ("phase 1: scanning phone,
  syncing with laptop, … deleting 20 songs, … exiting"), a playlist bar
  ("on playlist 3/16: guitar solos"), a song bar ("on song 50/78: hotel
  california") — always shown, even when fast, so the user always knows what
  is happening and where it's stuck.
- A **notes document** (this file).

---

## 6. Operational facts

### Commands (laptop)

```bash
msserve   # (alias) scan + serve; run in your own terminal to watch live output
msscan    # (alias) scan + journal (metadata-only, ~0.5 s)
ms log    # readable journal
ms verify # full re-hash against stored checksums
```

### Data locations

See the table in the chat: phone Room DB (app-private), laptop
`~/.local/share/music-syncer.db`, config `~/.config/music-syncer/config.toml`.

### Current test state

- The phone and laptop libraries are near-identical (6,076 files byte-identical
  when first compared; the laptop later removed a Bee Gees album → 6,053; the
  phone still holds the 25 Bee Gees files, which a successful sync will delete).
- The first end-to-end sync was never completed during testing because of the
  two blockers above (server down; firewall).

---

## 7. Known issues / next steps

1. **ufw rule for port 8756** (user to run) — unblocks the first real sync.
2. **No-silent-failures**: in-app catch-all error display + terminal logging
   (and consider a persistent log file).
3. **tqdm-style 3-bar progress** with phase/playlist/song granularity and
   elapsed times; make the SAF walk emit per-directory progress; fix the
   double-scan.
4. Live discovery status ("Looking for laptop…").
5. MediaStore enumeration instead of the DocumentFile walk (needs
   `READ_MEDIA_AUDIO` permission — to be flagged for the user) to make even
   the walk fast.
6. Optional hardening (deferred): auth token, APK checksum, TLS.

---

## 8. Credits / how the project was run

All implementation was subagent-driven with a review gate per task (a fresh
implementer, then a spec + quality reviewer) and a final whole-branch review by
a senior reviewer. That process caught every bug listed above — the engine is
correct *because* of the review loops. All code is on `master` in this repo;
the plan/spec docs live in `docs/superpowers/`.