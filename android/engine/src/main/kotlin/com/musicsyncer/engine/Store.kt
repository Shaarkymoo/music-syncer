package com.musicsyncer.engine

import com.google.gson.annotations.SerializedName

data class ManifestRow(val path: String, val size: Long, val mtimeNs: Long, val sha256: String?, val lastSeenNs: Long)
// tsNs is @SerializedName("ts_ns") so Gson matches the Python journal row keys
// (ms/db.py journal_since: id, op, path, size, sha256, ts_ns, device).
data class JournalOp(val id: Long, val op: String, val path: String, val size: Long?, val sha256: String?, @SerializedName("ts_ns") val tsNs: Long, val device: String)
data class SyncStateRow(val peerDeviceId: String, val lastSeenJournalId: Long, val lastSyncNs: Long?)

interface SyncStore {
    fun manifestGet(path: String): ManifestRow?
    fun manifestUpsert(path: String, size: Long, mtimeNs: Long, sha256: String?, nowNs: Long)
    fun manifestDelete(path: String)
    fun manifestAll(): List<ManifestRow>
    fun journalAppend(op: String, path: String, size: Long?, sha256: String?, tsNs: Long, device: String): Long
    fun journalHead(): Long
    fun journalSince(afterId: Long): List<JournalOp>
    fun syncStateGet(peerDeviceId: String): SyncStateRow?
    fun syncStateSet(peerDeviceId: String, lastSeenJournalId: Long, lastSyncNs: Long)
}