package com.musicsyncer.engine

fun scan(fs: Fs, store: SyncStore, deviceId: String, nowNs: Long): Long {
    val disk = mutableMapOf<String, FsEntry>()
    for (e in fs.list()) {
        if (e.rel.substringAfterLast('/').startsWith(".ms-partial-")) { fs.delete(e.rel); continue }
        if (e.rel.split('/').any { it.startsWith(".") }) continue
        disk[e.rel] = e
    }
    for (rel in disk.keys.sorted()) {
        val e = disk.getValue(rel)
        val row = store.manifestGet(rel)
        if (row == null) {
            val sha = fs.openRead(rel).use { Hashing.sha256(it) }
            store.journalAppend("CREATE", rel, e.size, sha, nowNs, deviceId)
            store.manifestUpsert(rel, e.size, e.mtimeNs, sha, nowNs)
        } else if (row.size == e.size && row.mtimeNs == e.mtimeNs) {
            // unchanged
        } else {
            val sha = fs.openRead(rel).use { Hashing.sha256(it) }
            if (sha != row.sha256) store.journalAppend("MODIFY", rel, e.size, sha, nowNs, deviceId)
            store.manifestUpsert(rel, e.size, e.mtimeNs, sha, nowNs)
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