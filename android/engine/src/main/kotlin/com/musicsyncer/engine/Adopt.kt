package com.musicsyncer.engine

/** Adopt remote shas into local NULL-sha rows when the size matches exactly
 *  and the local file exists — no hashing, no transfer. (mirror ms/adopt.py) */
fun adoptShas(
    store: SyncStore,
    remoteManifest: Map<String, Triple<Long, Long, String?>>,
    fs: Fs,
    nowNs: Long,
): List<String> {
    val adopted = mutableListOf<String>()
    for ((path, v) in remoteManifest) {
        val sha = v.third ?: continue
        val row = store.manifestGet(path) ?: continue
        if (row.sha256 != null || row.size != v.first) continue
        if (!fs.exists(path)) continue
        store.manifestUpsert(path, row.size, row.mtimeNs, sha, nowNs)
        adopted.add(path)
    }
    return adopted
}