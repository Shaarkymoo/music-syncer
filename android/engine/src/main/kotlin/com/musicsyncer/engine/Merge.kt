package com.musicsyncer.engine

/** A whole-directory move collapsed from {delete old, fetch new} plan items. */
data class DirMove(
    val oldDir: String,
    val newDir: String,
    val files: List<Triple<String, String, String?>>, // (oldRel, newRel, sha)
)

data class Plan(
    val fetch: MutableList<Triple<String, Long, String?>> = mutableListOf(),
    val push: MutableList<Triple<String, Long, String?>> = mutableListOf(),
    val delete: MutableList<String> = mutableListOf(),
    val conflictLoser: MutableList<Triple<String, Long, String?>> = mutableListOf(),
    val moves: MutableList<DirMove> = mutableListOf(),
)

private fun latestOpByPath(journal: List<JournalOp>): Map<String, JournalOp> {
    val latest = mutableMapOf<String, JournalOp>()
    for (op in journal) latest[op.path] = op
    return latest
}

/**
 * Collapses a whole-directory {delete old, fetch new} pair into one move when
 * every file under an oldDir maps 1:1 by sha to a file under a sibling newDir
 * (same parent) — the restructure case. Removes the moved items from
 * fetch/delete. Conservative: anything less than 100% stays per-file.
 */
fun detectDirMoves(plan: Plan, localShaByPath: Map<String, String?>): Plan {
    val fetchBySha = plan.fetch.filter { it.third != null }.associate { it.third!! to it.first }
    val deleteByDir = plan.delete.groupBy { it.substringBeforeLast('/') }
    val moved = mutableSetOf<String>()
    for ((oldDir, oldPaths) in deleteByDir) {
        if (oldDir.isEmpty() || oldPaths.size < 2) continue
        val mappings = oldPaths.mapNotNull { old ->
            val sha = localShaByPath[old] ?: return@mapNotNull null
            val new = fetchBySha[sha] ?: return@mapNotNull null
            if (new.substringBeforeLast('/') == oldDir) return@mapNotNull null
            Triple(old, new, sha)
        }
        if (mappings.size != oldPaths.size) continue
        val newDirs = mappings.map { it.second.substringBeforeLast('/') }.distinct()
        if (newDirs.size != 1) continue                                   // all into ONE dir
        val newDir = newDirs.first()
        if (newDir.substringBeforeLast('/') != oldDir.substringBeforeLast('/')) continue  // same parent
        if (mappings.map { it.second }.distinct().size != mappings.size) continue          // 1:1
        plan.moves.add(DirMove(oldDir, newDir, mappings))
        moved.addAll(mappings.map { it.first })
        moved.addAll(mappings.map { it.second })
    }
    if (moved.isNotEmpty()) {
        plan.delete.removeAll(moved)
        plan.fetch.removeAll { it.first in moved }
    }
    return plan
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
    val remoteOpsByPath = remoteJournal.groupBy { it.path }

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
        // The remote explicitly deleted this path: mirror the delete. This beats
        // an unseen local CREATE — otherwise a stale local copy whose CREATE is
        // unseen (e.g. the whole phone library on first sync) would be pushed
        // back, resurrecting a file the remote deliberately removed.
        val remoteDeleted = remoteOpsByPath[path].orEmpty().any { it.op == "DELETE" }
        val unseenLocalChange = opsByPath[path].orEmpty().any {
            it.id > peerCursor && (it.op == "CREATE" || it.op == "MODIFY") && it.device == ourDevice
        }
        if (remoteDeleted) plan.delete.add(path)
        else if (unseenLocalChange) plan.push.add(Triple(path, v.first, v.third))
        else plan.delete.add(path)
    }
    return plan
}