package com.musicsyncer.app.log

import com.musicsyncer.engine.JournalOp

/**
 * A contiguous run of journal ops made by one device with no gap longer than
 * [Sessionizer.SESSION_GAP_NS] between adjacent ops. Summarized for display.
 */
data class LogSession(
    val device: String,
    val startTsNs: Long,
    val created: Int,
    val modified: Int,
    val deleted: Int,
    val playlists: Int,
    val ops: List<JournalOp>,
)

/**
 * Groups journal ops (ordered by id, ascending) into sessions. Pure Kotlin —
 * no Android dependencies, so it is unit-testable on the JVM.
 *
 * A new session starts when the device changes or when the wall-clock gap
 * between adjacent ops exceeds 5 minutes.
 */
object Sessionizer {

    /** 5 minutes in nanoseconds. */
    const val SESSION_GAP_NS = 300_000_000_000L

    fun group(ops: List<JournalOp>): List<LogSession> {
        if (ops.isEmpty()) return emptyList()
        val sessions = mutableListOf<LogSession>()
        var run = mutableListOf(ops.first())
        for (op in ops.drop(1)) {
            val prev = run.last()
            if (op.device != prev.device || op.tsNs - prev.tsNs > SESSION_GAP_NS) {
                sessions.add(summarize(run))
                run = mutableListOf(op)
            } else {
                run.add(op)
            }
        }
        sessions.add(summarize(run))
        return sessions
    }

    private fun summarize(run: List<JournalOp>): LogSession {
        var created = 0
        var modified = 0
        var deleted = 0
        val parents = mutableSetOf<String>()
        for (op in run) {
            when (op.op) {
                "CREATE" -> created++
                "MODIFY" -> modified++
                "DELETE" -> deleted++
            }
            val parent = op.path.substringBeforeLast('/', missingDelimiterValue = "")
            parents.add(if (parent.isEmpty()) "root" else parent)
        }
        return LogSession(
            device = run.first().device,
            startTsNs = run.first().tsNs,
            created = created,
            modified = modified,
            deleted = deleted,
            playlists = parents.size,
            ops = run,
        )
    }
}