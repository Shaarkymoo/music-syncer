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

---

## 9. The first real sync and everything after (2026-10-01 → 2026-10-02)

This is the long session that took the project from "engine works in tests" to
"the phone and laptop actually converge, fast". Every bug below was found with
**live evidence** (adb, the phone's Room DB, the server's request log, and
temporary instrumentation), not by reading code. The stories are the most
useful part of this writeup.

### 9.1 The 20-minute "stuck on a song" — a real first-sync blocker

The first end-to-end sync froze for 20+ minutes. The phone UI sat on the last
scanned song. The server log told the real story:

```
[00:44:56] GET /handshake?device_id=phone -> 200 (0 ms)
[00:47:33] POST /sync -> 200 (875 ms)
            ← nothing after this, ever
```

The phone hung **after** `/sync` and never made a single `GET /file`. The phone
DB showed `sync_state` empty and the manifest **half-adopted** (1,851 shas
filled, 4,225 still NULL). The culprit: `adoptShas` — on the first sync every
phone row is a NULL-sha size-match, so for each of ~6,050 server files it ran
`fs.exists()` = a **per-path SAF `findFile` walk (~200 ms each)** on the SD
card. That's ~20 minutes of *zero-progress* loop. The fix: the just-completed
scan makes the manifest an exact disk snapshot, so `manifestGet() != null` is
existence proof — the phone passes `exists = { true }` and adoption became
seconds. (Python keeps its real `is_file()` — it's free on a laptop.)

**Lesson:** on Android, "does this file exist?" is a binder round-trip, not a
syscall. Count them.

### 9.2 The MediaStore fast path that was slower than the walk it replaced

To kill the ~2.5-minute SAF tree walk, we added the optional
`READ_MEDIA_AUDIO` fast path: one `MediaStore.Audio` query returns all ~6,000
files in ~150 ms, and `HybridFs` reconciles index lag against SAF. The user
granted the permission, installed the build… and the sync got *slower*, not
faster. Instrumentation (one `Log.i` per list) found it in minutes:

```
list(volume=3737-6133, relPath=my songs) -> 6075 rows in 167ms
sample=[my songs/engsongs/Titanic remix.mp3, ...]   ← THE BUG
```

The lister returned rels **with the `my songs/` folder prefix baked in**, but
the engine's paths are folder-relative (`engsongs/…`). `HybridFs`'s index-lag
reconcile compared manifest paths against prefixed keys, found zero matches,
and ran `saf.exists() + saf.stat()` for **all 6,076 rows** — the "fast" path
was ~40 minutes. One line (`removePrefix`) fixed it: 2,057 ms per full list,
5-second scans.

**Lesson:** when you replace a slow primitive with a fast cache, check the
*shape* of the data the cache returns, not just its speed.

### 9.3 The `?` in a filename that blocked every sync

The first fetch failed with `not found: new music/.ms-partial-…-What's Up?.mp3`.
Exactly two laptop files had a literal `?` in their names — a URI-reserved
character that SAF can't create or look up (and FAT32 forbids). Every sync
would abort on them forever. The user approved renaming the two files; the
engine surfaced the failure clearly (no silent failures — by design).

**Lesson:** filenames are a contract between the laptop's ext4 and the phone's
SD card; the mirror inherits the narrower filesystem.

### 9.4 The delete that wouldn't propagate — an engine gap, found by the Bee Gees

After the transfer fixes, the sync plan finally listed the 25 deleted Bee Gees
files… but the phone's plan put them in `push`, not `delete`. Why: a local-only
file with an *unseen local CREATE* is pushed, and the server (which had
*deleted* them) correctly refused to fetch them back. **Stalemate — permanent
divergence.** The phone's delete loop never consulted the remote journal. The
fix: a remote `DELETE` op beats an unseen local CREATE — mirror the delete.
The engine also built the *server's* plan from a pre-apply snapshot, so after
the phone deleted stale copies it tried to `fs.read()` a just-deleted file and
errored — fixed by recomputing the phone state after apply. Both changes
landed in **both engines** (Python + Kotlin) with mirrored regression tests,
including a legacy-cursor setup verified red first.

**Lesson:** "deletes propagate both ways" needs the *remote's* journal, not
just the local one — and plans must be built from post-apply state.

### 9.5 Daily-use features

- **Move-to-playlist**: 2-click scrollable playlist picker (the user's exact
  spec: pick a playlist, stay on the folder, 20+ playlists scrollable). SAF can
  only rename within a directory, so moves are copy+delete.
- **Persisted state**: server URL / last sync / summary now survive app
  restarts (they were in-memory — "why is it empty again?").
- **Play in Samsung Music**: `ACTION_VIEW` with the MediaStore URI (fallback
  SAF), so the app never needs a player.
- **Metadata reality check**: most songs genuinely have *no* tags — some have
  only lyrics, a few are fully tagged. The editor shows reality, not a bug.
- Two docs: `docs/how-sync-works.md` (lazy scan, journal/manifest, and what
  happens on delete/move/tag-edit on both devices) and
  `docs/terminal-commands.md` (every PC command and when to use it).
- **Wireless update that never triggered**: the whole `/version` + `/apk`
  self-update existed, but the version string was **never bumped**, so
  "Check for update" always answered "Up to date". Bumped to 0.1.1 → the flow
  worked, no adb needed.

### 9.6 The 2,444-file restructure: a mirror's worst case

The user moved `playlists2/{albums,lowkey}` to the root (`albums/`, `lowkey/`).
The naive engine (no move detection — locked design) sees 2,444 deletes +
2,444 creates. Two problems surfaced:

1. **The copy is silent.** `applyPlan` copied files but emitted **no progress
   events** — the UI sat frozen at the plan count for the whole phase, looking
   crashed while it worked. The fix (later, in Phase 3) threads progress into
   every copy and delete.
2. **The copy doubles the space.** Content-addressable copy keeps old + new
   paths on disk until deletes run — a 26 GB library briefly needs ~52 GB.
   The SD card ran out mid-copy ("no space"), Android auto-renamed colliding
   writes to `"Song (1).mp3"` (34+ confirmed duplicates), and a partial
   folder was manually deleted. The mirror self-healed on the next resumable
   sync, but the lesson is structural: **a full-library restructure is the
   worst case for a copy-based mirror.**

We added two weapons: a **SAF directory-document cache** (~6× fewer provider
queries per file op) and, in Phase 3, **opt-in folder-level move detection**
— when every file under an old dir maps 1:1 by sha to a sibling dir, the whole
thing collapses to ONE directory rename (verified live: a playlist rename
mirrors in a 6-second sync with zero file transfers, both directions).

### 9.7 Phase 3 — the optimization & capability pass (all 18 tasks)

Planned in `docs/superpowers/plans/2026-10-02-phase3-optimizations.md`,
implemented over a long session, all green (Python 86, engine 71, app 30):

- **Speed**: parallel bulk apply (4 workers, journal order preserved), batched
  DB writes flushed every 100 ops (a kill can't roll back the whole phase),
  concurrent-friendly SAF cache, per-file progress for copies/deletes.
- **Control**: stop button (cooperative cancellation at every loop boundary),
  wake-lock during sync, low-free-space warning, journal prune (90 days,
  cursor-guarded), mDNS retry + last-known server.
- **Daily UX**: multi-select in Browse (batch move/delete), move-dialog song
  counts + "new playlist", sorting (name/size/date) + folder counts, playlist
  rename + .m3u export, ETA on the progress card, completion notification,
  empty states, Stats tab, tag editor year/composer/track-total.
- **Security**: shared-token auth, default OFF (401 without it).
- **Status screen** finally scrolls.

### 9.8 Lessons learned (the dev.to takeaway)

1. **Instrument before you theorize.** Every "why is it slow/broken" here was
   answered by a one-line log statement or a DB query, not by reading code.
2. **Count SAF round-trips like syscalls.** On Android, existence checks,
   stat, and rename each cost a ContentResolver binder call; ~6,000 of them
   is minutes.
3. **Check the shape, not just the speed, of a cache's output.**
4. **"Deletes propagate both ways" is a journal-algebra problem** — the
   remote's ops matter, and plans must reflect post-apply state.
5. **A copy-based mirror doubles space during a restructure.** If you can't
   rename, expect the worst case.
6. **Silent progress is indistinguishable from a crash.** Every phase must
   report per item, even when it's fast.
7. **Version bumps are the self-update trigger.** A perfect update mechanism
   with a static version string is a dead button.
8. **The mirror inherits the narrower filesystem** — `?` on ext4 never makes
   it to the SD card.