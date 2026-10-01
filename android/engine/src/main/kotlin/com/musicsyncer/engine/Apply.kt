package com.musicsyncer.engine

import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom

data class ApplySummary(
    val fetched: MutableList<String> = mutableListOf(),
    val copied: MutableList<String> = mutableListOf(),
    val deleted: MutableList<String> = mutableListOf(),
    val conflicts: MutableList<String> = mutableListOf(),
)

private fun conflictName(rel: String, tsNs: Long): String {
    val name = rel.substringAfterLast('/')
    val ext = name.substringAfterLast('.', "")
    val suffix = if (ext.isNotEmpty() && ext != name) ".$ext" else ""
    // Keep the conflict file in the ORIGINAL directory (Python does
    // target.parent / _conflict_name(...)); top-level rels have no prefix.
    val dir = rel.substringBeforeLast('/', "")
    return (if (dir.isEmpty()) "" else "$dir/") + ".$name.sync-conflict-$tsNs$suffix"
}

private enum class FetchKind { COPIED, FETCHED, NOOP }

private data class FetchResult(val rel: String, val kind: FetchKind, val size: Long, val mtimeNs: Long, val sha: String?)

private const val WORKERS = 4
private const val BATCH_FLUSH = 100

/**
 * Applies a plan to the local store. Fetches/copies and deletes run on a small
 * worker pool (the phone's per-file SAF I/O is the bottleneck); the
 * journal/manifest writes are committed afterwards IN THE SEQUENTIAL ORDER the
 * loop would have produced, so journal ids and summaries are identical to a
 * single-threaded apply. [progress] emits a TRANSFER event per file (copies and
 * deletes included — the apply phase is otherwise silent for minutes on bulk
 * moves). A raising worker aborts the whole apply.
 */
fun applyPlan(
    fs: Fs, store: SyncStore, plan: Plan, ourDevice: String, remoteDevice: String,
    remoteOps: Map<String, String>, nowNs: Long,
    progress: ProgressListener? = null,
    isCancelled: () -> Boolean = { false },
    fetchBytes: (String) -> ByteArray,
): ApplySummary {
    val summary = ApplySummary()
    val localShaToPath = mutableMapOf<String, String>()
    for (m in store.manifestAll()) m.sha256?.let { localShaToPath.putIfAbsent(it, m.path) }

    // --- conflict losers first: preserve our old bytes as hidden file,
    //     before the fetch overwrites the path with the winner ---
    for ((rel, tsNs, _sha) in plan.conflictLoser) {
        if (fs.exists(rel)) {
            fs.rename(rel, conflictName(rel, tsNs))
            summary.conflicts.add(rel)
        }
    }

    val fetchItems = plan.fetch.sortedBy { it.first }
    val deleteItems = plan.delete.sorted()
    val total = fetchItems.size + deleteItems.size
    var done = 0
    val doneLock = Any()

    val fetchResults = arrayOfNulls<FetchResult>(fetchItems.size)
    runParallel(fetchItems.size) { i ->
        if (isCancelled()) throw SyncCancelledException()
        val item = fetchItems[i]
        val r = processFetch(fs, localShaToPath, fetchBytes, item)
        synchronized(doneLock) {
            fetchResults[i] = r
            done++
            emit(progress, SyncPhase.TRANSFER, done, total, item.first)
        }
        Unit
    }
    // Commit results in loop order, flushing every BATCH_FLUSH so a long copy
    // phase never holds one open transaction for hours (a kill would roll the
    // whole phase back; the files survive and the next scan re-derives them).
    for (start in 0 until fetchResults.size step BATCH_FLUSH) {
        store.withBatch {
            for (r in fetchResults.sliceArray(start until minOf(start + BATCH_FLUSH, fetchResults.size)).filterNotNull()) {
                when (r.kind) {
                    FetchKind.COPIED -> {
                        summary.copied.add(r.rel)
                        store.journalAppend(remoteOps[r.rel] ?: "CREATE", r.rel, r.size, r.sha, nowNs, remoteDevice)
                        store.manifestUpsert(r.rel, r.size, r.mtimeNs, r.sha, nowNs)
                    }
                    FetchKind.FETCHED -> {
                        summary.fetched.add(r.rel)
                        store.journalAppend(remoteOps[r.rel] ?: "CREATE", r.rel, r.size, r.sha, nowNs, remoteDevice)
                        store.manifestUpsert(r.rel, r.size, r.mtimeNs, r.sha, nowNs)
                    }
                    FetchKind.NOOP -> {}
                }
            }
        }
    }

    // --- deletes last ---
    val deletedFlags = arrayOfNulls<Boolean>(deleteItems.size)
    runParallel(deleteItems.size) { i ->
        if (isCancelled()) throw SyncCancelledException()
        val rel = deleteItems[i]
        val deleted = if (fs.exists(rel)) { fs.delete(rel); true } else false
        synchronized(doneLock) {
            deletedFlags[i] = deleted
            done++
            emit(progress, SyncPhase.TRANSFER, done, total, rel)
        }
        Unit
    }
    for (start in 0 until deleteItems.size step BATCH_FLUSH) {
        store.withBatch {
            for ((i, rel) in deleteItems.withIndex()) {
                if (i !in start until minOf(start + BATCH_FLUSH, deleteItems.size)) continue
                if (deletedFlags[i] == true) {
                    summary.deleted.add(rel)
                    store.journalAppend("DELETE", rel, null, null, nowNs, remoteDevice)
                    store.manifestDelete(rel)
                }
            }
        }
    }
    return summary
}

/** Runs [block] across [count] indices on a small pool; rethrows the first failure (unwrapped). */
private fun runParallel(count: Int, block: (Int) -> Unit) {
    if (count == 0) return
    val executor = Executors.newFixedThreadPool(WORKERS)
    try {
        val futures = (0 until count).map { i -> executor.submit { block(i) } }
        futures.forEach {
            try {
                it.get()
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            }
        }
    } finally {
        executor.shutdown()
    }
}

private fun processFetch(
    fs: Fs, localShaToPath: Map<String, String>, fetchBytes: (String) -> ByteArray,
    item: Triple<String, Long, String?>,
): FetchResult {
    val (rel, _size, sha) = item
    val srcRel = sha?.let { localShaToPath[it] }
    if (srcRel == rel && fs.exists(rel)) return FetchResult(rel, FetchKind.NOOP, 0L, 0L, sha)
    if (srcRel != null && srcRel != rel) {
        if (fs.exists(srcRel)) { // content-addressed copy: no transfer
            fs.mkdirs(rel.substringBeforeLast('/', ""))
            val tmp = partialName(rel)
            fs.write(tmp, fs.read(srcRel))
            fs.rename(tmp, rel)
            val entry = fs.stat(rel)
            return FetchResult(rel, FetchKind.COPIED, entry.size, entry.mtimeNs, sha)
        }
    }
    val data = fetchBytes(rel)
    val receivedSha = Hashing.sha256(data)
    if (sha != null && receivedSha != sha) throw IllegalArgumentException("sha256 mismatch after transfer: $rel")
    fs.mkdirs(rel.substringBeforeLast('/', ""))
    val tmp = partialName(rel)
    fs.write(tmp, data)
    fs.rename(tmp, rel)
    val entry = fs.stat(rel)
    return FetchResult(rel, FetchKind.FETCHED, data.size.toLong(), entry.mtimeNs, receivedSha)
}

private fun partialName(rel: String): String {
    val dir = rel.substringBeforeLast('/', "")
    val name = rel.substringAfterLast('/')
    val suffix = dir.ifEmpty { "" }
    return (if (suffix.isEmpty()) "" else "$suffix/") + ".ms-partial-" + ThreadLocalRandom.current().nextInt(0, Int.MAX_VALUE).toString(16) + "-" + name
}