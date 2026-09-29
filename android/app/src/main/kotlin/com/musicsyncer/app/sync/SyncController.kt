package com.musicsyncer.app.sync

import android.content.Context
import com.musicsyncer.engine.Fs
import com.musicsyncer.engine.ProgressListener
import com.musicsyncer.engine.SyncPhase
import com.musicsyncer.engine.SyncStore
import com.musicsyncer.engine.applyPlan
import com.musicsyncer.engine.runSyncSession
import com.musicsyncer.engine.scan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SyncState(
    val server: String? = null,
    val lastSync: String? = null,
    val lastSummary: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val progress: ProgressState? = null,
)

/** Live progress snapshot for the status UI, mirroring the engine's (phase, done, total, rel). */
data class ProgressState(val phase: SyncPhase, val done: Int, val total: Int, val rel: String)

/** Human-readable phase label for the status UI. */
fun SyncPhase.displayName(): String = when (this) {
    SyncPhase.SCAN -> "Scanning"
    SyncPhase.PLAN -> "Planning"
    SyncPhase.TRANSFER -> "Transferring"
    SyncPhase.DONE -> "Done"
}

class SyncController(
    private val context: Context,
    private val fs: Fs,
    private val store: SyncStore,
    private val ourDevice: String = "phone",
) {
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state
    private val scope = CoroutineScope(Dispatchers.IO)

    fun setServer(url: String) { _state.value = _state.value.copy(server = url) }

    private fun progressListener(): ProgressListener = ProgressListener { phase, done, total, rel ->
        _state.value = _state.value.copy(progress = ProgressState(phase, done, total, rel))
    }

    fun scan() {
        if (_state.value.busy) return
        scope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                runScan(progressListener())
            } finally {
                _state.value = _state.value.copy(busy = false, progress = null)
            }
        }
    }

    private suspend fun runScan(progress: ProgressListener? = null): Long = withContext(Dispatchers.IO) {
        scan(fs, store, ourDevice, System.currentTimeMillis() * 1_000_000, progress)
    }

    fun sync(serverUrl: String?) {
        if (_state.value.busy) return
        val url = serverUrl ?: _state.value.server ?: return
        scope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                runScan(progressListener())
                val summary = runSyncSession(url, fs, store, ourDevice, progressListener())
                MediaRescan.rescan(context, summary.fetched + summary.copied + summary.deleted + summary.conflicts)
                _state.value = _state.value.copy(
                    lastSync = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
                    lastSummary = "fetched ${summary.fetched.size}, copied ${summary.copied.size}, pushed ${summary.pushed.size}, deleted ${summary.deleted.size}, conflicts ${summary.conflicts.size}",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            } finally {
                _state.value = _state.value.copy(busy = false, progress = null)
            }
        }
    }

    fun verify() {
        if (_state.value.busy) return
        scope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                runScan(progressListener())
                val manifest = store.manifestAll()
                val mismatches = manifest.filterIndexed { i, m ->
                    _state.value = _state.value.copy(progress = ProgressState(SyncPhase.SCAN, i + 1, manifest.size, m.path))
                    m.sha256 != null && runCatching {
                        fs.openRead(m.path).use { com.musicsyncer.engine.Hashing.sha256(it) } == m.sha256
                    }.getOrDefault(false).not()
                }
                _state.value = _state.value.copy(
                    lastSummary = if (mismatches.isEmpty()) "OK: all files match stored hashes" else "MISMATCH: ${mismatches.joinToString { it.path }}",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            } finally {
                _state.value = _state.value.copy(busy = false, progress = null)
            }
        }
    }
}