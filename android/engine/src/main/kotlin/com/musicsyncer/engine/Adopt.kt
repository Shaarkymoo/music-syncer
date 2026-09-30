package com.musicsyncer.engine

/** Adopt remote shas into local NULL-sha rows when the size matches exactly
 *  and the local file exists — no hashing, no transfer. Paths with an unseen
 *  local MODIFY are skipped: a same-size rewrite must not be frozen to the
 *  peer's stale sha. (mirror ms/adopt.py)
 *
 *  [exists] decides whether the local file exists. Callers with an expensive
 *  existence check (SAF `fs.exists` is a ~200ms ContentResolver round-trip per
 *  path — 6k paths ≈ 20 min on a first sync) may pass a cheap predicate: the
 *  just-completed scan makes the manifest an exact disk snapshot, so a non-null
 *  [SyncStore.manifestGet] row already proves existence. */
fun adoptShas(
    store: SyncStore,
    remoteManifest: Map<String, Triple<Long, Long, String?>>,
    fs: Fs,
    nowNs: Long,
    recentlyModified: Set<String> = emptySet(),
    exists: (String) -> Boolean = { rel -> fs.exists(rel) },
    progress: ProgressListener? = null,
): List<String> {
    val adopted = mutableListOf<String>()
    val total = remoteManifest.size
    var i = 0
    for ((path, v) in remoteManifest) {
        i++
        emit(progress, SyncPhase.ADOPT, i, total, path)
        val sha = v.third ?: continue
        if (path in recentlyModified) continue
        val row = store.manifestGet(path) ?: continue
        if (row.sha256 != null || row.size != v.first) continue
        if (!exists(path)) continue
        store.manifestUpsert(path, row.size, row.mtimeNs, sha, nowNs)
        adopted.add(path)
    }
    return adopted
}