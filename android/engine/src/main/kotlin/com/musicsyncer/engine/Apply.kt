package com.musicsyncer.engine

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

fun applyPlan(
    fs: Fs, store: SyncStore, plan: Plan, ourDevice: String, remoteDevice: String,
    remoteOps: Map<String, String>, nowNs: Long, fetchBytes: (String) -> ByteArray,
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

    // --- fetches (writes) ---
    for ((rel, _size, sha) in plan.fetch.sortedBy { it.first }) {
        val srcRel = sha?.let { localShaToPath[it] }
        if (srcRel == rel && fs.exists(rel)) continue  // already applied: no-op
        if (srcRel != null && srcRel != rel) {
            if (fs.exists(srcRel)) {  // content-addressed copy: no transfer
                fs.mkdirs(rel.substringBeforeLast('/', ""))
                val tmp = partialName(rel)
                fs.write(tmp, fs.read(srcRel))
                fs.rename(tmp, rel)
                summary.copied.add(rel)
                val entry = fs.stat(rel)
                store.journalAppend(remoteOps[rel] ?: "CREATE", rel, entry.size, sha, nowNs, remoteDevice)
                store.manifestUpsert(rel, entry.size, entry.mtimeNs, sha, nowNs)
                continue
            }
        }
        val data = fetchBytes(rel)
        val receivedSha = Hashing.sha256(data)
        if (sha != null && receivedSha != sha) throw IllegalArgumentException("sha256 mismatch after transfer: $rel")
        fs.mkdirs(rel.substringBeforeLast('/', ""))
        val tmp = partialName(rel)
        fs.write(tmp, data)
        fs.rename(tmp, rel)
        summary.fetched.add(rel)
        val entry = fs.stat(rel)
        store.journalAppend(remoteOps[rel] ?: "CREATE", rel, data.size.toLong(), receivedSha, nowNs, remoteDevice)
        store.manifestUpsert(rel, data.size.toLong(), entry.mtimeNs, receivedSha, nowNs)
    }

    // --- deletes last ---
    for (rel in plan.delete.sorted()) {
        if (fs.exists(rel)) {
            fs.delete(rel)
            summary.deleted.add(rel)
            store.journalAppend("DELETE", rel, null, null, nowNs, remoteDevice)
            store.manifestDelete(rel)
        }
    }
    return summary
}

private fun partialName(rel: String): String {
    val dir = rel.substringBeforeLast('/', "")
    val name = rel.substringAfterLast('/')
    val suffix = dir.ifEmpty { "" }
    return (if (suffix.isEmpty()) "" else "$suffix/") + ".ms-partial-" + ThreadLocalRandom.current().nextInt(0, Int.MAX_VALUE).toString(16) + "-" + name
}