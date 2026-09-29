package com.musicsyncer.engine

import com.google.gson.Gson
import com.google.gson.GsonBuilder

object GsonHolder { val gson: Gson = GsonBuilder().create() }

data class HandshakeResp(val schemaVersion: Int, val serverDeviceId: String, val serverJournalHead: Long, val clientCursor: Long)
// Typed wire DTOs: Gson round-trips List<List<Any?>> Longs through Double,
// losing precision above 2^53 (real epoch-ns mtimes ~1.77e18).
data class ManifestWire(val path: String, val size: Long, val mtimeNs: Long, val sha256: String?)
data class ConflictWire(val path: String, val tsNs: Long, val sha256: String)
data class SyncRequest(val deviceId: String, val serverCursor: Long, val journalOps: List<JournalOp>, val manifest: List<ManifestWire>)
data class SyncResponse(val schemaVersion: Int, val serverJournalOps: List<JournalOp>, val serverManifest: List<ManifestWire>, val needsPush: List<ManifestWire>, val serverJournalHead: Long)
data class DoneRequest(val deviceId: String, val clientJournalHead: Long, val tsNs: Long, val delete: List<String> = emptyList(), val conflicts: List<ConflictWire> = emptyList())