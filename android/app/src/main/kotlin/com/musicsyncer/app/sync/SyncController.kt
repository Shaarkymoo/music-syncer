package com.musicsyncer.app.sync

import android.content.Context
import com.musicsyncer.engine.Fs
import com.musicsyncer.engine.ProgressListener
import com.musicsyncer.engine.SyncCancelledException
import com.musicsyncer.engine.SyncPhase
import com.musicsyncer.engine.SyncStore
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
    val discovering: Boolean = false,
    val error: String? = null,
    val progress: ProgressState? = null,
)

/** Live progress snapshot for the status UI, mirroring the engine's (phase, done, total, rel). */
data class ProgressState(
    val phase: SyncPhase,
    val done: Int,
    val total: Int,
    val rel: String,
    val elapsedMs: Long = 0L,
    val phaseStartNs: Long = 0L,
)

/** Human-readable phase label for the status UI. */
fun SyncPhase.displayName(): String = when (this) {
    SyncPhase.SCAN -> "Scanning phone"
    SyncPhase.ADOPT -> "Matching files with laptop"
    SyncPhase.PLAN -> "Syncing with laptop"
    SyncPhase.TRANSFER -> "Transferring with laptop"
    SyncPhase.DONE -> "Final check"
}

class SyncController(
    private val context: Context,
    private val fs: Fs,
    private val store: SyncStore,
    private val ourDevice: String = "phone",
    private val prefs: android.content.SharedPreferences? = null,
    private val freeSpaceProvider: () -> Long? = { null },
    private val tokenProvider: () -> String? = { null },
) {
    private val _state = MutableStateFlow(
        SyncState(
            server = prefs?.getString("state_server", null),
            lastSync = prefs?.getString("state_last_sync", null),
            lastSummary = prefs?.getString("state_last_summary", null),
        ),
    )
    val state: StateFlow<SyncState> = _state
    private val scope = CoroutineScope(Dispatchers.IO)
    private val cancelToken = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Requests the running scan/sync/verify to stop at the next file boundary. */
    fun cancel() { cancelToken.set(true) }

    private fun persist(s: SyncState) {
        prefs?.edit()
            ?.putString("state_server", s.server)
            ?.putString("state_last_sync", s.lastSync)
            ?.putString("state_last_summary", s.lastSummary)
            ?.apply()
    }

    /** Monotonic start of the current phase, for per-stage elapsed timing. */
    private var phaseStartNs = 0L
    private var lastPhase: SyncPhase? = null

    fun setServer(url: String) { persist(_state.value.copy(server = url)); _state.value = _state.value.copy(server = url) }

    /** Elapsed ms since the current phase started; resets on a phase change or the scan walk marker. */
    private fun trackPhase(phase: SyncPhase, done: Int, total: Int, rel: String): Long {
        val isPhaseStart = phase != lastPhase || (phase == SyncPhase.SCAN && done == 0 && total == 0 && rel.isEmpty())
        if (isPhaseStart) {
            phaseStartNs = System.nanoTime()
            lastPhase = phase
        }
        return (System.nanoTime() - phaseStartNs) / 1_000_000
    }

    private fun progressListener(): ProgressListener = ProgressListener { phase, done, total, rel ->
        val elapsedMs = trackPhase(phase, done, total, rel)
        _state.value = _state.value.copy(progress = ProgressState(phase, done, total, rel, elapsedMs, phaseStartNs))
    }

    fun scan() {
        if (_state.value.busy) return
        scope.launch {
            cancelToken.set(false)
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                runScan(progressListener())
            } catch (e: SyncCancelledException) {
                _state.value = _state.value.copy(lastSummary = "Cancelled").also { persist(it) }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: e.javaClass.simpleName)
            } finally {
                _state.value = _state.value.copy(busy = false, progress = null)
            }
        }
    }

    private suspend fun runScan(progress: ProgressListener? = null): Long = withContext(Dispatchers.IO) {
        scan(fs, store, ourDevice, System.currentTimeMillis() * 1_000_000, progress, cancel = { cancelToken.get() })
    }

    fun sync(serverUrl: String?) {
        if (_state.value.busy) return
        val url = serverUrl ?: _state.value.server ?: return
        scope.launch {
            cancelToken.set(false)
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                runSync(url)
            } catch (e: SyncCancelledException) {
                _state.value = _state.value.copy(lastSummary = "Cancelled").also { persist(it) }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: e.javaClass.simpleName)
            } finally {
                _state.value = _state.value.copy(busy = false, progress = null)
            }
        }
    }

    /** Finds the laptop via [findLaptop], then syncs — "Looking for laptop…" stays visible while discovery runs. */
    fun discover(findLaptop: suspend () -> String?) {
        if (_state.value.busy) return
        scope.launch {
            cancelToken.set(false)
            _state.value = _state.value.copy(busy = true, discovering = true, error = null)
            try {
                var url = findLaptop()
                if (url == null) url = findLaptop() // mDNS can miss on the first pass
                url = url ?: _state.value.server    // last-known server fallback (persisted)
                if (url == null) throw IllegalStateException("Laptop not found")
                setServer(url)
                _state.value = _state.value.copy(discovering = false)
                runSync(url)
            } catch (e: SyncCancelledException) {
                _state.value = _state.value.copy(lastSummary = "Cancelled").also { persist(it) }
            } catch (e: Exception) {
                notify("Sync failed", e.message ?: e.javaClass.simpleName)
                _state.value = _state.value.copy(error = e.message ?: e.javaClass.simpleName)
            } finally {
                _state.value = _state.value.copy(busy = false, discovering = false, progress = null)
            }
        }
    }

    /** Shared sync body: run the session, rescan media, record the summary. */
    private suspend fun runSync(url: String) {
        val free = freeSpaceProvider()
        if (free != null && free < 500L * 1024 * 1024) {
            notify("Low free space", "Only ${"%.2f GB".format(free / 1073741824.0)} left on the music volume")
        }
        val lock = acquireWakeLock()
        try {
            val summary = runSyncSession(url, fs, store, ourDevice, progressListener(), cancel = { cancelToken.get() }, token = tokenProvider())
            MediaRescan.rescan(context, summary.fetched + summary.copied + summary.deleted + summary.conflicts)
            val lowSpace = free != null && free < 500L * 1024 * 1024
            _state.value = _state.value.copy(
                lastSync = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
                lastSummary = "fetched ${summary.fetched.size}, copied ${summary.copied.size}, pushed ${summary.pushed.size}, deleted ${summary.deleted.size}, conflicts ${summary.conflicts.size}" +
                    if (lowSpace) " · LOW SPACE" else "",
            ).also { persist(it) }
            notify("Sync complete", "fetched ${summary.fetched.size}, copied ${summary.copied.size}, deleted ${summary.deleted.size}")
        } finally {
            lock?.release()
        }
    }

    private fun acquireWakeLock(): android.os.PowerManager.WakeLock? = try {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "music-syncer:sync").also { it.acquire() }
    } catch (e: Exception) {
        null
    }

    private fun notify(title: String, text: String) {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        try {
            val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(android.app.NotificationChannel("sync", "Sync", android.app.NotificationManager.IMPORTANCE_LOW))
            nm.notify(
                1,
                android.app.Notification.Builder(context, "sync")
                    .setSmallIcon(com.musicsyncer.app.R.drawable.ic_launcher)
                    .setContentTitle(title).setContentText(text).setAutoCancel(true).build(),
            )
        } catch (e: Exception) {
            android.util.Log.w("MusicSyncer", "notification failed: ${e.message}")
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
                    if (cancelToken.get()) throw SyncCancelledException()
                    val elapsedMs = trackPhase(SyncPhase.SCAN, i + 1, manifest.size, m.path)
                    _state.value = _state.value.copy(progress = ProgressState(SyncPhase.SCAN, i + 1, manifest.size, m.path, elapsedMs, phaseStartNs))
                    m.sha256 != null && runCatching {
                        fs.openRead(m.path).use { com.musicsyncer.engine.Hashing.sha256(it) } == m.sha256
                    }.getOrDefault(false).not()
                }
                _state.value = _state.value.copy(
                    lastSummary = if (mismatches.isEmpty()) "OK: all files match stored hashes" else "MISMATCH: ${mismatches.joinToString { it.path }}",
                ).also { persist(it) }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: e.javaClass.simpleName)
            } finally {
                _state.value = _state.value.copy(busy = false, progress = null)
            }
        }
    }
}