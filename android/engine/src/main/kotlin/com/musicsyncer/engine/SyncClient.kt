package com.musicsyncer.engine

import java.io.IOException
import java.net.URLEncoder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class SyncSummary(
    val fetched: List<String> = emptyList(),
    val copied: List<String> = emptyList(),
    val pushed: List<String> = emptyList(),
    val deleted: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
)

const val SCHEMA_VERSION = 1

private val JSON = "application/json".toMediaType()

private fun httpJson(client: OkHttpClient, url: String, method: String = "GET", body: String? = null): String {
    val rb = body?.toRequestBody(JSON)
    val req = Request.Builder().url(url).method(method, rb).build()
    client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${resp.body?.string()}")
        return resp.body!!.string()
    }
}

private fun httpBytes(client: OkHttpClient, url: String, method: String = "GET", body: ByteArray? = null): ByteArray {
    val rb = body?.toRequestBody()
    val req = Request.Builder().url(url).method(method, rb).build()
    client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
        return resp.body!!.bytes()
    }
}

fun runSyncSession(serverUrl: String, fs: Fs, store: SyncStore, ourDevice: String): SyncSummary {
    val client = OkHttpClient()
    val nowNs = System.nanoTime()

    // 1. Handshake: learn server id + how much of OUR journal the server has seen.
    val hs = GsonHolder.gson.fromJson(
        httpJson(client, "$serverUrl/handshake?device_id=${URLEncoder.encode(ourDevice, "UTF-8")}"),
        HandshakeResp::class.java,
    )
    if (hs.schemaVersion != SCHEMA_VERSION)
        throw IllegalArgumentException("schema mismatch: server ${hs.schemaVersion} != client $SCHEMA_VERSION")
    val serverDevice = hs.serverDeviceId
    val serverCursorForUs = hs.clientCursor  // server.sync_state[us]

    // 2. Local scan, then gather our state.
    scan(fs, store, ourDevice, nowNs)
    val ourCursor = store.syncStateGet(serverDevice)?.lastSeenJournalId ?: 0L  // what we've seen of server
    val ourOps = store.journalSince(serverCursorForUs)
    val ourManifest = store.manifestAll().associate { it.path to Triple(it.size, it.mtimeNs, it.sha256 ?: "") }

    // 3. Submit state; receive server journal + manifest.
    val req = SyncRequest(
        deviceId = ourDevice,
        serverCursor = ourCursor,
        journalOps = ourOps,
        manifest = ourManifest.map { (p, v) -> listOf(p, v.first, v.second, v.third) },
    )
    val resp = GsonHolder.gson.fromJson(
        httpJson(client, "$serverUrl/sync", "POST", GsonHolder.gson.toJson(req)),
        SyncResponse::class.java,
    )
    val serverOps = resp.serverJournalOps
    val serverManifest = resp.serverManifest.associate { row ->
        row[0] as String to Triple((row[1] as Double).toLong(), (row[2] as Double).toLong(), row[3] as String)
    }

    // 4. OUR plan: peer_cursor = how much of OUR journal the server has seen.
    val plan = buildPlan(ourManifest, ourOps, serverManifest, serverOps, serverCursorForUs, ourDevice)
    val remoteOps = serverOps.associate { it.path to it.op }
    val applied = applyPlan(fs, store, plan, ourDevice, serverDevice, remoteOps, nowNs) { rel ->
        httpBytes(client, "$serverUrl/file?path=${URLEncoder.encode(rel, "UTF-8")}")
    }

    // 5. SERVER's plan: peer_cursor = how much of the SERVER's journal WE have seen.
    val serverPlan = buildPlan(serverManifest, serverOps, ourManifest, ourOps, ourCursor, serverDevice)
    val pushed = mutableListOf<String>()
    for ((rel, _size, sha) in serverPlan.fetch) {
        val data = fs.read(rel)
        httpBytes(client, "$serverUrl/file?path=${URLEncoder.encode(rel, "UTF-8")}&sha=$sha", "POST", data)
        pushed.add(rel)
    }

    // 6. Commit: server applies its deletions + conflict preservations; advance cursors.
    store.syncStateSet(serverDevice, resp.serverJournalHead, nowNs)
    val done = DoneRequest(
        deviceId = ourDevice,
        clientJournalHead = store.journalHead(),
        tsNs = nowNs,
        delete = serverPlan.delete,
        conflicts = serverPlan.conflictLoser.map { listOf(it.first, it.second.toDouble(), it.third) },
    )
    httpJson(client, "$serverUrl/done", "POST", GsonHolder.gson.toJson(done))

    return SyncSummary(
        fetched = applied.fetched,
        copied = applied.copied,
        pushed = pushed,
        deleted = applied.deleted,
        conflicts = applied.conflicts,
    )
}