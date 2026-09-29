package com.musicsyncer.engine

import com.google.gson.Gson
import com.google.gson.GsonBuilder

object GsonHolder { val gson: Gson = GsonBuilder().create() }

data class HandshakeResp(val schemaVersion: Int, val serverDeviceId: String, val serverJournalHead: Long, val clientCursor: Long)
data class SyncRequest(val deviceId: String, val serverCursor: Long, val journalOps: List<JournalOp>, val manifest: List<List<Any?>>)
data class SyncResponse(val schemaVersion: Int, val serverJournalOps: List<JournalOp>, val serverManifest: List<List<Any?>>, val needsPush: List<List<Any?>>, val serverJournalHead: Long)
data class DoneRequest(val deviceId: String, val clientJournalHead: Long, val tsNs: Long, val delete: List<String> = emptyList(), val conflicts: List<List<Any?>> = emptyList())