package com.musicsyncer.engine

fun scan(fs: Fs, store: SyncStore, deviceId: String, nowNs: Long, progress: ProgressListener? = null): Long {
    emit(progress, SyncPhase.SCAN, 0, 0, "")  // walk marker: tree walk starting
    val disk = mutableMapOf<String, FsEntry>()
    for (e in fs.list()) {
        if (e.rel.substringAfterLast('/').startsWith(".ms-partial-")) { fs.delete(e.rel); continue }
        if (e.rel.split('/').any { it.startsWith(".") }) continue
        disk[e.rel] = e
    }
    val total = disk.size
    for ((i, rel) in disk.keys.sorted().withIndex()) {
        emit(progress, SyncPhase.SCAN, i + 1, total, rel)
        val e = disk.getValue(rel)
        val row = store.manifestGet(rel)
        if (row == null) {
            store.journalAppend("CREATE", rel, e.size, null, nowNs, deviceId)
            store.manifestUpsert(rel, e.size, e.mtimeNs, null, nowNs)
        } else if (row.size == e.size && row.mtimeNs == e.mtimeNs) {
            // unchanged
        } else {
            store.journalAppend("MODIFY", rel, e.size, null, nowNs, deviceId)
            store.manifestUpsert(rel, e.size, e.mtimeNs, null, nowNs)
        }
    }
    for (m in store.manifestAll()) {
        if (m.path !in disk) {
            store.journalAppend("DELETE", m.path, null, null, nowNs, deviceId)
            store.manifestDelete(m.path)
        }
    }
    return store.journalHead()
}