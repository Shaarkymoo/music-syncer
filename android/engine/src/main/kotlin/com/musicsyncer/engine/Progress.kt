package com.musicsyncer.engine

/** Live progress reporting for scan/sync sessions (consumed by the app UI). */
enum class SyncPhase { SCAN, ADOPT, PLAN, TRANSFER, DONE }

/** (phase, done, total, current_rel) — done is 1-based, total is the phase's file count, current_rel is the file being processed ("" if none). */
fun interface ProgressListener {
    fun onProgress(phase: SyncPhase, done: Int, total: Int, rel: String)
}

/** Best-effort progress callback: a raising callback never breaks the engine. */
fun emit(progress: ProgressListener?, phase: SyncPhase, done: Int, total: Int, rel: String) {
    if (progress == null) return
    try {
        progress.onProgress(phase, done, total, rel)
    } catch (e: Exception) {
        // progress is advisory; swallow callback bugs
    }
}