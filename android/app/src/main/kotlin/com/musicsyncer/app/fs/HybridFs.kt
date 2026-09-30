package com.musicsyncer.app.fs

import android.util.Log
import com.musicsyncer.engine.Fs
import com.musicsyncer.engine.FsDirEntry
import com.musicsyncer.engine.FsEntry
import com.musicsyncer.engine.SyncStore
import java.io.InputStream

/**
 * SAF-backed Fs whose recursive walk is replaced by a MediaStore query when the
 * app holds READ_MEDIA_AUDIO. All writes/deletes/renames still go through SAF.
 *
 * Two reconciliations keep the engine correct despite the index being an
 * eventually-consistent snapshot:
 *  - Files the index has not caught up on (e.g. just written by this app) are
 *    re-verified against SAF, so the engine never sees phantom deletes.
 *  - Sub-second mtime differences between the listing source (MediaStore =
 *    seconds, SAF = milliseconds) are precision artifacts, not changes: they
 *    are re-stamped into the manifest WITHOUT journaling so the engine diff
 *    never journals a phantom MODIFY when a row flips sources.
 */
class HybridFs(
    private val saf: SafFs,
    private val lister: MediaStoreLister,
    private val store: SyncStore,
) : Fs {
    override fun list(): List<FsEntry> {
        val t0 = System.nanoTime()
        val listed = lister.list().associateBy { it.rel }.toMutableMap()
        var fallback = 0
        for (m in store.manifestAll()) {
            if (m.path in listed) continue
            if (saf.exists(m.path)) {
                listed[m.path] = saf.stat(m.path) // index lag: keep the row alive
                fallback++
            }
        }
        baselineMtimes(store, listed.values.toList())
        Log.i(TAG, "HybridFs.list: ${listed.size} total (${fallback} SAF-fallback) in ${(System.nanoTime() - t0) / 1_000_000}ms")
        return listed.values.toList()
    }

    override fun listDir(rel: String): List<FsDirEntry> = saf.listDir(rel)
    override fun stat(rel: String): FsEntry = saf.stat(rel)
    override fun read(rel: String): ByteArray = saf.read(rel)
    override fun openRead(rel: String): InputStream = saf.openRead(rel)
    override fun write(rel: String, data: ByteArray) = saf.write(rel, data)
    override fun mkdirs(relDir: String) = saf.mkdirs(relDir)
    override fun delete(rel: String) = saf.delete(rel)
    override fun rename(rel: String, newRel: String) = saf.rename(rel, newRel)
    override fun exists(rel: String): Boolean = saf.exists(rel)

    private companion object { const val TAG = "MusicSyncer" }
}

/**
 * Silently (no journal) re-stamps manifest mtimes to the listing source's
 * values when size matches and the mtime gap is under a second — a precision
 * artifact of MediaStore's second-resolution dates, not a real change. A real
 * modification older than a second keeps its gap, so the engine still sees and
 * propagates it.
 */
fun baselineMtimes(store: SyncStore, entries: List<FsEntry>) {
    val nowNs = System.currentTimeMillis() * 1_000_000
    for (e in entries) {
        val row = store.manifestGet(e.rel) ?: continue
        if (row.size != e.size || row.mtimeNs == e.mtimeNs) continue
        if (kotlin.math.abs(row.mtimeNs - e.mtimeNs) < 1_000_000_000L) {
            store.manifestUpsert(e.rel, e.size, e.mtimeNs, row.sha256, nowNs)
        }
    }
}