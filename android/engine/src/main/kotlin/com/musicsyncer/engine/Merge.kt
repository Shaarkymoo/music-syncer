package com.musicsyncer.engine

data class Plan(
    val fetch: MutableList<Triple<String, Long, String?>> = mutableListOf(),
    val push: MutableList<Triple<String, Long, String?>> = mutableListOf(),
    val delete: MutableList<String> = mutableListOf(),
    val conflictLoser: MutableList<Triple<String, Long, String?>> = mutableListOf(),
)

private fun latestOpByPath(journal: List<JournalOp>): Map<String, JournalOp> {
    val latest = mutableMapOf<String, JournalOp>()
    for (op in journal) latest[op.path] = op
    return latest
}

fun buildPlan(
    localManifest: Map<String, Triple<Long, Long, String?>>,
    localJournal: List<JournalOp>,
    remoteManifest: Map<String, Triple<Long, Long, String?>>,
    remoteJournal: List<JournalOp>,
    peerCursor: Long,
    ourDevice: String,
): Plan {
    val plan = Plan()
    val localLatest = latestOpByPath(localJournal)
    val remoteLatest = latestOpByPath(remoteJournal)
    val localSha = localManifest.mapValues { it.value.third }
    val opsByPath = localJournal.groupBy { it.path }

    for ((path, v) in remoteManifest.entries.sortedBy { it.key }) {
        val size = v.first
        val sha = v.third
        if (path !in localManifest) {
            val unseenLocalDelete = opsByPath[path].orEmpty().any {
                it.id > peerCursor && it.op == "DELETE" && it.device == ourDevice
            }
            if (unseenLocalDelete) continue  // we deleted it; the remote's plan will delete it too
            plan.fetch.add(Triple(path, size, sha))
        } else if (localSha[path] == sha && sha != null) {
            continue  // both sides have the same known sha
        } else {  // both sides have different content (or unknown)
            if (localSha[path] == null && localManifest.getValue(path).first == size) {
                val unseenLocalModify = opsByPath[path].orEmpty().any {
                    it.id > peerCursor && it.op == "MODIFY" && it.device == ourDevice
                }
                if (!unseenLocalModify) continue  // local never hashed, sizes match: identical; adoption fills the sha
            }
            val localTs = localLatest[path]?.tsNs ?: 0L
            val remoteTs = remoteLatest[path]?.tsNs ?: 0L
            if (localTs > remoteTs) {  // local wins; remote must take ours
                plan.push.add(Triple(path, localManifest.getValue(path).first, localSha.getValue(path)))
            } else {  // remote wins; we take theirs, preserve ours
                plan.fetch.add(Triple(path, size, sha))
                plan.conflictLoser.add(Triple(path, localTs, localSha.getValue(path)))
            }
        }
    }

    for ((path, v) in localManifest.entries.sortedBy { it.key }) {
        if (path in remoteManifest) continue
        val unseenLocalChange = opsByPath[path].orEmpty().any {
            it.id > peerCursor && (it.op == "CREATE" || it.op == "MODIFY") && it.device == ourDevice
        }
        if (unseenLocalChange) plan.push.add(Triple(path, v.first, v.third))
        else plan.delete.add(path)
    }
    return plan
}