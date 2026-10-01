# How sync works (lazy scanning, the journal, and what happens in each case)

Plain-language explanation of the engine, for when you're wondering "what will
happen if I…". The authoritative code is `ms/` (laptop) and `android/engine/`
(phone) — they implement the same semantics.

---

## 1. The three building blocks

**The journal** — an append-only log of every change, on both devices:
`CREATE`, `MODIFY`, `DELETE` ops with a path, a timestamp, and the device that
made the change. This is the "regret log": you can always see what happened and
when. No trash — a delete is permanent, but it's recorded.

**The manifest** — the current state per file: path, size, modified time, and a
`sha256` checksum. The checksum is the file's **identity**.

**Lazy scanning** — scans are *metadata-only* by default: they compare (size,
modified-time) against the manifest and journal changes, but do **not** hash
every file. Hashing 26 GB on every scan would be pointless when nothing
changed. The laptop scan takes ~0.5 s; the phone's used to take ~2.5 min until
the MediaStore fast path made it ~2 s.

A file's checksum gets filled in only when it's actually needed:

1. **Sha adoption** — if both sides have a file with the same size, one side
   adopts the other's checksum without reading the bytes. On the first sync
   this gave ~6,050 files their checksums in seconds, zero transfer.
2. **Transfers** — when a file is fetched/pushed, its checksum is computed and
   stored during the transfer.

So after the first sync, every file has a real checksum and identical trees
sync with **zero transfer** — the whole sync is just scans + planning.

---

## 2. What a sync session does

Phone-initiated, over Wi-Fi (the laptop runs a passive server on port 8756):

1. **Handshake** — phone and laptop exchange identities and journal cursors.
2. **Scan** — the phone scans its folder (fast, lazy) and journals changes.
3. **Plan** — the phone sends its journal + manifest; the laptop replies with
   its own. Each side computes the differences: what to fetch, what to push,
   what to delete, what conflicts (last-writer-wins + a `.sync-conflict-…`
   copy preserved).
4. **Transfer** — changed files are fetched/pushed (verified by checksum).
5. **Done** — deletes are applied, cursors advance, both sides now agree.

Deletes are applied **last** (after transfers), so a file that was both
changed and deleted on the other side is handled sensibly.

---

## 3. What happens in each case

### A song is deleted

| Where | What happens |
|---|---|
| **Laptop** | Next `ms scan`/server start journals `DELETE`. Phone syncs: the phone's copy is deleted too. |
| **Phone** (via the app's Delete, or any file manager / Samsung Music) | Next scan journals `DELETE`. Phone syncs: the laptop deletes its copy. |

Deletes propagate **both ways** — full mirror. No trash; the journal entry is
the only record. If you regret it, re-add the file and it syncs back.

### A song is moved / renamed (folder restructure, rename, move to playlist)

The engine has **no move detection** (locked design choice): a move is seen as
**DELETE old path + CREATE new path**.

| Where | What happens |
|---|---|
| **Laptop** | Scan journals `DELETE` (old) + `CREATE` (new). Phone syncs: deletes the old copy, then fetches the file at the new path. |
| **Phone** | Same in reverse: the laptop deletes the old path and fetches the new one. |

**The cost depends on whether the server knows the file's checksum:**

- If the server's manifest already has the checksum (it was hashed before),
  the phone **copies the file locally** — it already has the same content at
  the old path (content-addressable copy). A rename/move costs **zero
  download**.
- If the server only knows size+mtime (lazy scan, `sha = NULL`), the phone
  **re-downloads** the file.

This matters after you **restructure the laptop library** (see §5): run
`ms scan --hash` once after the move so the phone can content-copy instead of
re-downloading ~10 GB.

### A song's metadata is edited (title, artist, lyrics…)

Metadata (ID3 tags) lives **inside the mp3 file**. Editing it changes the file
content — so a tag edit is exactly like a content change:

| Where | What happens |
|---|---|
| **Phone** (app's metadata editor) | The app rewrites the tags, then the next scan journals `MODIFY` (size and/or mtime changed). Phone syncs: the *modified file* is pushed to the laptop. |
| **Laptop** (any tag editor) | Scan journals `MODIFY`. Phone syncs: fetches the updated file. |

Since a tag edit usually changes the file size (or at least the mtime), the
lazy scan detects it, the checksum is computed during the transfer, and the
updated file replaces the old one on the other side. **Both devices end up
with the same tags.** (Same-size edits are handled too — the journal-aware
adoption guard prevents a same-size rewrite from being frozen.)

---

## 4. Conflicts (both sides changed the same file)

Last-writer-wins by operation timestamp. The loser's old bytes are kept as a
hidden `.sync-conflict-<timestamp>` file on the winning side, so nothing is
silently lost. If a file is deleted on one side and modified on the other, the
delete wins (a deliberate delete beats a stale copy) — unless the file is
re-added, in which case it propagates back.

---

## 5. After a laptop library restructure — step by step

You moved folders (e.g. `playlists2/albums` → `albums`, 2,444 files). Here's
the safe sequence:

1. **Stop the server** if it's running: `pkill -f "ms.cli serve"`.
2. **Start it fresh** — the startup scan journals the renames as
   `DELETE + CREATE`:
   `setsid env PYTHONPATH=$PWD /usr/bin/python3 -m ms.cli serve --path "/media/shaarky/Data/Shaarav/my songs/" > /tmp/msserve.log 2>&1 < /dev/null &`
3. **Hash the new files once** so the phone copies locally instead of
   downloading ~10 GB:
   `python3 -m ms.cli scan --path "/media/shaarky/Data/Shaarav/my songs/" --hash`
   (one-time; reads 26 GB, takes a minute or two; the laptop scan is read-only)
4. **On the phone**: tap **Find laptop & sync**. Expect: old folders deleted,
   new folders populated — with near-zero download because the phone already
   has the same content under the old paths.
5. Check the Log screen / server log for anything unexpected.

If you skip step 3, the sync still works — the phone just downloads the moved
files again (~10 GB over Wi-Fi).