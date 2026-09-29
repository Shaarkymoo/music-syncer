# Music-Syncer Phase 2A — Lazy Hashing, SHA Adoption, Progress Hooks

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the 26 GB initial-sync cost by making the engine metadata-only on first scan (lazy hashing), adopt the peer's sha when sizes match exactly (so identical libraries sync in seconds, not hours), and expose live progress so the Phase 2B UI can show what's happening.

**Architecture:** Change detection switches to a pure metadata walk (path + exact byte-size + mtime; sha256 = NULL until a file is transferred/verified). A new *sha adoption* step reconciles local NULL-shas against the peer's shas when sizes match — no transfer, no hashing. Progress reporting is added as an optional callback threaded through scan/sync on both implementations. The full on-demand `ms verify` remains for integrity checks. The wire protocol is unchanged (NULL shas already serialize as `null`).

**Tech Stack:** Python 3.12 (laptop daemon `ms/`) + Kotlin/JVM engine (`android/engine/`) + the existing test suites. No new dependencies.

## Global Constraints

- Identity stays content-hash-first, but the manifest may hold `sha256 = NULL` (never hashed). NULL-sha files are treated as "unverified"; integrity is guaranteed at transfer time (L2) and by `ms verify` (L5).
- **Lazy scan:** a new path journals `CREATE` with `sha256 = NULL` and upserts the manifest with `sha = NULL` (size + mtime filled). A path whose size/mtime changed journals `MODIFY` with `sha = NULL` (can't tell content without hashing) and updates size/mtime. The scan NEVER hashes during the scan pass.
- **SHA adoption:** during a sync session, for each path where the REMOTE manifest has a non-null sha, the LOCAL manifest has NULL sha, the local file EXISTS, and the sizes match EXACTLY → upsert the local manifest with the remote sha (no hash, no transfer). Runs on BOTH sides (client adopts from server; server adopts from client via the same reconcile step in its session handling).
- **No adoption on size mismatch** — a size mismatch means the file differs; it goes through the normal create/modify path (which now transfers without a prior hash).
- **Transfer-time verification is conditional:** `apply` ALWAYS computes the sha of received bytes and stores it in the manifest (lazy fill — integrity is recorded, not skipped). It verifies against the expected sha ONLY when one is present; when the expected sha is None (source never hashed), no comparison is made. The content-addressed copy path still requires a non-null sha; None-sha fetches take the normal transfer path.
- **`ms verify` semantics:** files with `sha = None` are counted as UNVERIFIED, not MISMATCH. Output: `OK: N verified, M unverified` when no verified file mismatches; `MISMATCH` only for files with a stored sha whose content differs.
- **Merge change:** in `buildPlan`, when both sides have the path and local sha is NULL but remote sha is non-null AND sizes match → treat as identical (no fetch, no conflict); the adoption step writes the sha. When local sha is NULL and sizes DIFFER → treat as a real change (fetch remote or push local per LWW on journal ts).
- **Progress hooks:** `scan(fs, store, device, nowNs, progress: ((done: int, total: int, rel: str) -> None) | None = None)` and `runSyncSession(..., progress: (SyncPhase, int, int, str) -> None | None = None)` with `SyncPhase = SCAN | PLAN | TRANSFER | DONE` (Python) and a matching `ProgressListener` interface in Kotlin. Callbacks are best-effort (must never throw into the engine).
- The wire protocol and JSON shapes are UNCHANGED (NULL sha already legal).
- Both implementations keep their full test suites green; the Python↔Kotlin `PythonInteropTest` must still pass and gains an adoption scenario.

## File Structure (changes only)

```
ms/
  scan.py            # lazy scan (no hashing)
  merge.py           # NULL-sha same-size = identical
  apply.py           # no change (transfer-time verify already hashes)
  client.py          # adoption step + progress
  server.py          # adoption step in /sync + /done + progress passthrough
  cli.py             # print progress in serve/scan
  protocol.py        # (if exists) SyncPhase enum — else define in a small ms/progress.py
ms tests:
  test_scan.py       # update: CREATE/MODIFY now NULL sha
  test_merge.py      # add: NULL-sha adoption cases
  test_sync_integration.py  # add: adoption scenario (identical trees sync with zero transfer)
android/engine/...:
  Scan.kt            # lazy scan
  Merge.kt           # NULL-sha same-size = identical
  SyncClient.kt      # adoption step + progress
  JvmServer.kt       # adoption in /sync + progress
  Progress.kt        # ProgressListener + SyncPhase (new)
  Store.kt           # unchanged
android/engine tests:
  ScanTest.kt        # update CREATE/MODIFY NULL sha
  MergeTest.kt       # add adoption cases
  SyncIntegrationTest.kt  # add adoption scenario
  PythonInteropTest.kt    # add adoption scenario (Python server, Kotlin client)
```

---

## Task 1: Python — lazy scan

**Files:**
- Modify: `ms/scan.py`, `tests/test_scan.py`

**Interfaces:**
- Consumes: `ms.db`, `ms.hashing.fingerprint`
- Produces: `scan(fs, store, deviceId, nowNs)` unchanged signature; behavior: CREATE/MODIFY journal with `sha256=None`; manifest upsert with `sha=None`; NO hashing anywhere in scan.

- [ ] **Step 1: Update the failing tests**

`tests/test_scan.py` — the CREATE test currently asserts `manifest_get(...)[2] is not None`. Change to `is None` (lazy). Add a test: a modified file (size change) journals MODIFY with `sha256 is None`. Keep the idempotency, delete, dot-skip, partial-cleanup tests unchanged (they don't depend on sha).

```python
def test_scan_detects_create_lazy(tmp_path: Path):
    root = tmp_path / "root"
    (root / "Rock").mkdir(parents=True)
    (root / "Rock" / "A.mp3").write_bytes(b"data")
    conn = _conn(tmp_path)
    scan.scan(root, conn, "laptop", 1000)
    ops = db.journal_since(conn, 0)
    assert [(o["op"], o["path"]) for o in ops] == [("CREATE", "Rock/A.mp3")]
    assert db.manifest_get(conn, "Rock/A.mp3")[2] is None  # lazy: no hash
    conn.close()

def test_scan_modify_is_lazy(tmp_path: Path):
    root = tmp_path / "root"
    p = root / "A.mp3"
    p.write_bytes(b"v1")
    conn = _conn(tmp_path)
    scan.scan(root, conn, "laptop", 1000)
    p.write_bytes(b"v2-longer")  # size change -> MODIFY
    scan.scan(root, conn, "laptop", 2000)
    ops = db.journal_since(conn, 1)
    assert [(o["op"], o["sha256"]) for o in ops] == [("MODIFY", None)]
    conn.close()
```

- [ ] **Step 2: Run to verify they fail**

Run: `.venv/bin/python -m pytest tests/test_scan.py -v`
Expected: FAIL (the two new/updated tests fail against the hashing scan). NOTE: the repo `.venv` is broken — run tests with the system python: `/usr/bin/python3 -m pytest tests/test_scan.py -v` (zeroconf is installed system-wide; pytest too). Use `/usr/bin/python3` for ALL Python test runs in this plan.

- [ ] **Step 3: Implement lazy scan**

`ms/scan.py` — remove the hashing: CREATE journals `(rel, e.size, None, now_ns, device_id)` and upserts `(rel, e.size, e.mtime_ns, None, now_ns)`; the size/mtime-differ branch journals MODIFY with `sha256=None` and upserts with `None`. Delete the `sha256_file` import if unused.

- [ ] **Step 4: Run to verify they pass**

Run: `/usr/bin/python3 -m pytest tests/test_scan.py -v` — 6 tests pass. Then full Python suite (`/usr/bin/python3 -m pytest -v`) — note: integration tests may fail at Task 2 (adoption missing); document which fail and why (they're the ones to fix in Task 2).

- [ ] **Step 5: Commit**

```bash
git add ms/ tests/
git commit -m "feat(engine): lazy scan — metadata-only first pass (no hashing)"
```

---

## Task 2: Python — sha adoption + merge NULL-sha handling

**Files:**
- Modify: `ms/merge.py`, `ms/apply.py`, `ms/client.py`, `ms/server.py`, `ms/cli.py`, `tests/test_merge.py`, `tests/test_sync_integration.py`, `tests/test_cli.py`

**Interfaces:**
- Produces: `adopt_shas(store, remote_manifest: dict[str, tuple[int, int, str]], fs) -> list[str]` — for each path in remote_manifest with non-null sha where the local manifest row exists with NULL sha and equal size and the local file exists → `manifest_upsert(path, size, mtime_ns, remote_sha, now_ns)`; returns the adopted paths.
- `merge.build_plan`: in the both-sides-differ branch, add before the LWW logic: `if local_sha[path] is None and local size == remote size → continue` (identical, adoption handles the sha). If local sha is None and sizes differ → fall through to LWW as a real change. Fetches may carry `sha=None` (lazy source) — that is legal.
- `apply_plan` fetch branch: always compute the received bytes' sha; store it in the manifest; raise `ValueError` only when the expected sha is non-null AND differs. (Was: always compare + store the expected sha.)
- `client.run_sync_session`: after building `server_manifest`, call `adopt_shas(store, server_manifest, fs)` BEFORE `build_plan` (so the client's plan sees the adopted shas → no fetches for identical files).
- `server.py` `/sync` handler: after its server-side scan, call `adopt_shas(server._conn, client_manifest, fs)` so the server adopts phone-side shas too (before computing its response).
- `ms/cli.py verify`: count None-sha rows as UNVERIFIED; `OK: N verified, M unverified` when no mismatch; MISMATCH only for verified files that differ.

- [ ] **Step 1: Write the failing tests**

`tests/test_merge.py` — add:
```python
def test_null_local_sha_same_size_is_identical():
    # local has NULL sha, remote has sha, sizes match -> no fetch, no conflict
    plan = build_plan({"A.mp3": (10, 5, None)}, [], {"A.mp3": (10, 5, "remotesha")}, [], 0, "me")
    assert plan.fetch == [] and plan.push == [] and plan.delete == []

def test_null_local_sha_different_size_is_change():
    plan = build_plan({"A.mp3": (10, 5, None)}, [], {"A.mp3": (20, 5, "remotesha")}, [], 0, "me")
    assert plan.fetch == [("A.mp3", 20, "remotesha")] or plan.push or plan.delete
```

`tests/test_sync_integration.py` — add the adoption scenario (the crown-jewel test):
```python
def test_identical_trees_sync_with_zero_transfer(pair, tmp_path):
    root_a, root_b, srv = pair
    # Seed BOTH sides with the SAME file (simulating "same library on both").
    (root_a / "Rock").mkdir(); (root_a / "Rock" / "A.mp3").write_bytes(b"same-content")
    (root_b / "Rock").mkdir(); (root_b / "Rock" / "A.mp3").write_bytes(b"same-content")
    # Laptop scans lazily (no hashes).
    scan(root_a, srv._conn, "laptop", 1)
    # Phone scans lazily; its manifest has NULL sha; sizes match the laptop's manifest.
    db_path = _client_scan(root_b, tmp_path)
    s = _sync(root_b, db_path, srv, tmp_path)
    assert s["fetched"] == [] and s["pushed"] == []   # zero transfer
    assert db.manifest_get(conn_for(db_path), "Rock/A.mp3")[2] is not None  # sha adopted
```
(NOTE: the existing `_client_scan`/`_sync` helpers open fresh connections; adapt the assertion to re-open the phone DB or have the helper return the db_path and open it for the assertion.)

`tests/test_cli.py` — update the two verify tests for the new None-sha semantics:
```python
def test_verify_clean_tree_unverified(tmp_path: Path, capsys):
    root = tmp_path / "root"
    (root / "A.mp3").write_bytes(b"data")
    cli.main(["scan", "--path", str(root), "--db", str(tmp_path / "t.db")])
    code = cli.main(["verify", "--path", str(root), "--db", str(tmp_path / "t.db")])
    out = capsys.readouterr().out
    assert code == 0 and "unverified" in out  # lazy scan -> no hashes -> unverified

def test_verify_detects_tamper_of_verified_file(tmp_path: Path, capsys):
    root = tmp_path / "root"
    p = root / "A.mp3"
    p.write_bytes(b"data")
    conn = db.init_db(tmp_path / "t.db")
    db.journal_append(conn, "CREATE", "A.mp3", 4, "deadbeef", 1, "laptop")  # seed a real sha
    db.manifest_upsert(conn, "A.mp3", 4, 1, "deadbeef", 1)
    conn.close()
    p.write_bytes(b"TAMPERED!")
    code = cli.main(["verify", "--path", str(root), "--db", str(tmp_path / "t.db")])
    out = capsys.readouterr().out
    assert code == 0 and "MISMATCH" in out and "A.mp3" in out  # verified file tampered
```

- [ ] **Step 2: Run to verify they fail**

Run: `/usr/bin/python3 -m pytest tests/test_merge.py tests/test_sync_integration.py -v`
Expected: FAIL — the NULL-sha cases and the adoption scenario fail (fetch happens; sha not adopted).

- [ ] **Step 3: Implement**

`ms/merge.py`: add the NULL-sha-same-size → identical branch in the both-sides loop.
`ms/adopt.py` (new, or inside client.py): `adopt_shas(store, remote_manifest, fs, now_ns)` as specified. Reads the local manifest rows via `db.manifest_get`, checks `row.sha256 is None and row.size == remote_size and fs.exists(path)`.
`ms/apply.py`: in the fetch branch, always compute `received_sha = sha256_file_bytes(data)`; if the expected sha is non-null and `received_sha != expected` → raise; store `received_sha` in the manifest and journal (instead of the expected sha). The content-addressed copy path requires a non-null sha (leave as-is).
`ms/client.py`: call `adopt_shas(conn, server_manifest, fs, now_ns)` after parsing the response, before `build_plan`.
`ms/server.py`: in `/sync`, after `scan_mod.scan(...)`, call `adopt_shas(server._conn, client_manifest_from_request, PathFs(server.root), time.time_ns())` before building `server_journal_ops`/`server_manifest`. (The server needs the client's manifest as a dict — the request's `manifest` list of arrays → dict.)
`ms/cli.py verify`: build a sha lookup only over manifest rows with a non-null sha; rows with None sha count as UNVERIFIED; print `OK: N verified, M unverified` when no mismatch; `MISMATCH <path>` only for verified rows whose content differs. Exit code 0 regardless (as before).

- [ ] **Step 4: Run to verify they pass**

Run: `/usr/bin/python3 -m pytest tests/test_merge.py tests/test_sync_integration.py -v`
Expected: all pass — including `test_identical_trees_sync_with_zero_transfer` (zero fetch/push, sha adopted). Then the FULL Python suite: `/usr/bin/python3 -m pytest -v` — all green.

- [ ] **Step 5: Commit**

```bash
git add ms/ tests/
git commit -m "feat(engine): sha adoption — identical trees sync with zero transfer"
```

---

## Task 3: Python — progress hooks + CLI output

**Files:**
- Create: `ms/progress.py`
- Modify: `ms/scan.py`, `ms/client.py`, `ms/server.py`, `ms/cli.py`

**Interfaces:**
- Produces:
  - `ms/progress.py`: `class SyncPhase(Enum): SCAN, PLAN, TRANSFER, DONE`; `Progress = Callable[[SyncPhase, int, int, str], None]` (phase, done, total, current_rel).
  - `scan(..., progress: Progress | None = None)`: reports `(SCAN, i, total, rel)` per file processed.
  - `runSyncSession(..., progress: Progress | None = None)`: reports `(SCAN, ...)` during the local scan, `(PLAN, i, n, "")` while applying, `(TRANSFER, i, n, rel)` while pushing/fetching, `(DONE, n, n, "")`.
  - `ms.cli serve/scan`: pass a progress callback that prints `\r[phase] i/n rel` every 100 files (or on phase change) to stderr. `ms verify` unchanged.

- [ ] **Step 1: Write a small test** (in `tests/test_progress.py`): a scan over a 3-file dir with a progress callback captures `(SCAN, 1, 3, rel)`, `(SCAN, 3, 3, rel)` — assert the callback fired with monotonically increasing `done` and total == 3. And `runSyncSession` progress: at least one TRANSFER event when a file transfers.

- [ ] **Step 2: Run to verify it fails** — `/usr/bin/python3 -m pytest tests/test_progress.py -v` → FAIL (no progress param).

- [ ] **Step 3: Implement** — add the optional `progress` params and emit the events. Callbacks wrapped so an exception in the callback is caught and ignored (best-effort).

- [ ] **Step 4: Run to verify it passes** — the new test + full suite green.

- [ ] **Step 5: Commit** — `git commit -m "feat(engine): progress hooks + CLI progress output"`

---

## Task 4: Kotlin — lazy scan + adoption

**Files:**
- Modify: `android/engine/src/main/kotlin/com/musicsyncer/engine/Scan.kt`, `Merge.kt`, `SyncClient.kt`, `JvmServer.kt`
- Modify: `android/engine/src/test/kotlin/com/musicsyncer/engine/ScanTest.kt`, `MergeTest.kt`, `SyncIntegrationTest.kt`

**Interfaces:**
- Produces: same as Tasks 1-2 but in Kotlin:
  - `Scan.kt`: no hashing — CREATE/MODIFY journal with `sha256 = null`; manifest upsert `sha = null`.
  - `Merge.kt`: NULL-sha-same-size → identical (skip) branch.
  - `SyncClient.kt`: after parsing the response, adopt server shas into the local store (iterate `serverManifest`; for each row with non-null sha where local `manifestGet` has NULL sha + equal size + `fs.exists(path)` → `manifestUpsert(path, size, stat.mtimeNs, sha, nowNs)`).
  - `JvmServer.kt` `/sync`: after the server-side scan, adopt client manifest shas into the server store (the request's `manifest: List<ManifestWire>`).
  - Store.kt unchanged (sha256 already nullable).
- Tests: update `ScanTest` (CREATE/MODIFY sha null), add `MergeTest` NULL-sha cases, add `SyncIntegrationTest.testIdenticalTreesSyncWithZeroTransfer` (both sides seeded with the same file; assert `summary.fetched`/`pushed` empty and the phone store's manifest sha now non-null).

- [ ] **Step 1: Update/add failing tests** (transliterate from Tasks 1-2)
- [ ] **Step 2: Run to verify they fail** — `cd android && JAVA_HOME=... ./gradlew :engine:test` (expected failures in ScanTest/MergeTest/SyncIntegrationTest)
- [ ] **Step 3: Implement**
- [ ] **Step 4: Run to verify they pass** — `./gradlew :engine:test --rerun-tasks` all green; also `:app:testDebugUnitTest` and `:app:assembleDebug` (the app consumes the engine; nothing else changes)
- [ ] **Step 5: Commit** — `git commit -m "feat(engine): Kotlin lazy scan + sha adoption"`

---

## Task 5: Kotlin — progress hooks + PythonInterop adoption test

**Files:**
- Create: `android/engine/src/main/kotlin/com/musicsyncer/engine/Progress.kt`
- Modify: `Scan.kt`, `SyncClient.kt`, `JvmServer.kt`, `SyncIntegrationTest.kt`, `PythonInteropTest.kt`

**Interfaces:**
- Produces:
  - `Progress.kt`: `enum class SyncPhase { SCAN, PLAN, TRANSFER, DONE }`; `fun interface ProgressListener { fun onProgress(phase: SyncPhase, done: Int, total: Int, rel: String) }`.
  - `scan(..., progress: ProgressListener? = null)`, `runSyncSession(..., progress: ProgressListener? = null)` — emit events as in Task 3; listener exceptions swallowed (best-effort).
- Tests:
  - A `ProgressTest` capturing scan events over a 3-file dir.
  - `PythonInteropTest`: ADD an adoption scenario — seed the Python server root and the Kotlin client root with the SAME file (laptop scans lazily; client root pre-seeded with the identical file), run the client, assert zero fetched/pushed and the client store's manifest sha is non-null (adopted from the real Python server).

- [ ] **Step 1: Write failing tests**
- [ ] **Step 2: Run to verify they fail**
- [ ] **Step 3: Implement**
- [ ] **Step 4: Run to verify they pass** — `./gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug --rerun-tasks` all green (PythonInteropTest must RUN, not skip)
- [ ] **Step 5: Commit** — `git commit -m "feat(engine): Kotlin progress hooks + interop adoption test"`

---

## Task 6: Rebuild APK + full regression + README update

**Files:**
- Modify: `README.md`

**Interfaces:** none

- [ ] **Step 1: Full regression**
  - Python: `/usr/bin/python3 -m pytest -v` (all green, incl. integration + progress + adoption)
  - Android: `cd android && JAVA_HOME=... ./gradlew :app:assembleDebug :engine:test :app:testDebugUnitTest --rerun-tasks` (all green)
- [ ] **Step 2: Real-library smoke** — run `/usr/bin/python3 -m ms.cli scan --path "/media/shaarky/Data/Shaarav/my songs/"` against the real library with progress output: the first run should now be near-instant (metadata walk, no hashing) and journal nothing new (already in the manifest). Verify it completes in seconds, not minutes.
- [ ] **Step 3: README** — note the lazy-hash behavior ("first scan is metadata-only; files are verified on transfer or via `ms verify`").
- [ ] **Step 4: Commit** — `git commit -m "docs: lazy-hash behavior + Phase 2A notes"`

---

## Self-Review

**Spec coverage:** lazy scan (Tasks 1, 4), sha adoption both sides (Tasks 2, 4), merge NULL-sha handling (Tasks 2, 4), progress hooks Python+Kotlin (Tasks 3, 5), interop adoption test against the real Python server (Task 5), full regression + real-library smoke + APK rebuild (Task 6). The wire protocol is untouched (NULL sha already valid JSON). The user's identical-trees scenario is exactly the adoption test.
**Placeholder scan:** every step has concrete code or a precise transliteration instruction; no TBDs.
**Type consistency:** `Progress`/`SyncPhase` (Python) and `ProgressListener`/`SyncPhase` (Kotlin) defined once and used consistently; `adopt_shas` signature matches its call sites; the Kotlin adoption mirrors the Python exactly. `manifest sha256` remains nullable everywhere.