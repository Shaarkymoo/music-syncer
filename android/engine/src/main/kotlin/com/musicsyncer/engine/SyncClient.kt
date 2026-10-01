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

private fun httpJson(client: OkHttpClient, url: String, method: String = "GET", body: String? = null, token: String? = null): String {
    val rb = body?.toRequestBody(JSON)
    val reqBuilder = Request.Builder().url(url).method(method, rb)
    if (token != null) reqBuilder.header("Authorization", "Bearer $token")
    val req = reqBuilder.build()
    client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${resp.body?.string()}")
        return resp.body!!.string()
    }
}

private fun httpBytes(client: OkHttpClient, url: String, method: String = "GET", body: ByteArray? = null, token: String? = null): ByteArray {
    val rb = body?.toRequestBody()
    val reqBuilder = Request.Builder().url(url).method(method, rb)
    if (token != null) reqBuilder.header("Authorization", "Bearer $token")
    val req = reqBuilder.build()
    client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
        return resp.body!!.bytes()
    }
}

fun runSyncSession(serverUrl: String, fs: Fs, store: SyncStore, ourDevice: String, progress: ProgressListener? = null,
                   cancel: () -> Boolean = { false }, token: String? = null): SyncSummary {
    val client = OkHttpClient()
    // Wall-clock epoch-ns (Python uses time.time_ns()); System.nanoTime() is
    // monotonic-since-boot and incomparable across devices, which would break
    // cross-device LWW.
    val nowNs = System.currentTimeMillis() * 1_000_000

    // Count scanned files so the final DONE event can report the session total.
    var scanned = 0
    val counting = ProgressListener { phase, done, total, rel ->
        if (phase == SyncPhase.SCAN) scanned = total
        emit(progress, phase, done, total, rel)
    }

    // 1. Handshake: learn server id + how much of OUR journal the server has seen.
    val hs = GsonHolder.gson.fromJson(
        httpJson(client, "$serverUrl/handshake?device_id=${URLEncoder.encode(ourDevice, "UTF-8")}", token = token),
        HandshakeResp::class.java,
    )
    if (hs.schemaVersion != SCHEMA_VERSION)
        throw IllegalArgumentException("schema mismatch: server ${hs.schemaVersion} != client $SCHEMA_VERSION")
    if (hs.tokenRequired && token == null)
        throw IllegalArgumentException("server requires an auth token — set it in the app's Status screen")
    val serverDevice = hs.serverDeviceId
    val serverCursorForUs = hs.clientCursor  // server.sync_state[us]

    // 2. Local scan, then gather our state.
    scan(fs, store, ourDevice, nowNs, counting, cancel)
    if (cancel()) throw SyncCancelledException()
    val ourCursor = store.syncStateGet(serverDevice)?.lastSeenJournalId ?: 0L  // what we've seen of server
    val ourOps = store.journalSince(serverCursorForUs)
    var ourManifest = store.manifestAll().associate { it.path to Triple(it.size, it.mtimeNs, it.sha256) }

    // 3. Submit state; receive server journal + manifest.
    val req = SyncRequest(
        deviceId = ourDevice,
        serverCursor = ourCursor,
        journalOps = ourOps,
        manifest = ourManifest.map { (p, v) -> ManifestWire(p, v.first, v.second, v.third) },
    )
    val resp = GsonHolder.gson.fromJson(
        httpJson(client, "$serverUrl/sync", "POST", GsonHolder.gson.toJson(req), token = token),
        SyncResponse::class.java,
    )
    val serverOps = resp.serverJournalOps
    val serverManifest = resp.serverManifest.associate { it.path to Triple(it.size, it.mtimeNs, it.sha256) }

    // 3.5. Adopt server shas for identical local files (no transfer, no hashing),
    //      then refresh our manifest so both plans see the adopted shas.
    //      Paths with an unseen local MODIFY are skipped: a same-size rewrite
    //      must not be frozen to the server's stale sha.
    //      exists = { true }: the scan just above made the manifest an exact disk
    //      snapshot (missing files were journaled DELETE and removed), so the
    //      manifestGet non-null check inside adoptShas is existence proof. A
    //      per-path SAF exists() would be a ~200ms ContentResolver round-trip —
    //      ~6050 of them ≈ 20 min of frozen UI on the first sync.
    val recentlyModified = ourOps.filter { it.op == "MODIFY" && it.device == ourDevice }.map { it.path }.toSet()
    adoptShas(store, serverManifest, fs, nowNs, recentlyModified, exists = { true }, progress = counting, cancel = cancel)
    ourManifest = store.manifestAll().associate { it.path to Triple(it.size, it.mtimeNs, it.sha256) }

    // 4. OUR plan: peer_cursor = how much of OUR journal the server has seen.
    val plan = buildPlan(ourManifest, ourOps, serverManifest, serverOps, serverCursorForUs, ourDevice)
    val planItems = plan.fetch.map { it.first } + plan.delete + plan.conflictLoser.map { it.first }
    for ((i, rel) in planItems.withIndex()) emit(progress, SyncPhase.PLAN, i + 1, planItems.size, "")
    val remoteOps = serverOps.associate { it.path to it.op }
    val applied = applyPlan(fs, store, plan, ourDevice, serverDevice, remoteOps, nowNs, progress, cancel) { rel ->
        httpBytes(client, "$serverUrl/file?path=${URLEncoder.encode(rel, "UTF-8")}", token = token)
    }

    // 5. SERVER's plan: peer_cursor = how much of the SERVER's journal WE have seen.
    //    Recompute OUR state after apply so the server plan sees files deleted
    //    this session as gone — otherwise it would fetch (push) a file the phone
    //    just deleted and the push read would fail.
    val serverPlan = buildPlan(
        serverManifest, serverOps,
        store.manifestAll().associate { it.path to Triple(it.size, it.mtimeNs, it.sha256) },
        store.journalSince(serverCursorForUs),
        ourCursor, serverDevice,
    )
    val pushed = mutableListOf<String>()
    for ((i, item) in serverPlan.fetch.withIndex()) {
        if (cancel()) throw SyncCancelledException()
        val (rel, _size, sha) = item
        emit(progress, SyncPhase.TRANSFER, i + 1, serverPlan.fetch.size, rel)
        val data = fs.read(rel)
        val url = "$serverUrl/file?path=${URLEncoder.encode(rel, "UTF-8")}" + if (sha != null) "&sha=$sha" else ""
        httpBytes(client, url, "POST", data, token = token)
        pushed.add(rel)
    }

    // 6. Commit: server applies its deletions + conflict preservations; advance cursors.
    store.syncStateSet(serverDevice, resp.serverJournalHead, nowNs)
    val done = DoneRequest(
        deviceId = ourDevice,
        clientJournalHead = store.journalHead(),
        tsNs = nowNs,
        delete = serverPlan.delete,
        conflicts = serverPlan.conflictLoser.map { ConflictWire(it.first, it.second, it.third) },
    )
    httpJson(client, "$serverUrl/done", "POST", GsonHolder.gson.toJson(done), token = token)

    val total = scanned + planItems.size + pushed.size
    emit(progress, SyncPhase.DONE, total, total, "")

    return SyncSummary(
        fetched = applied.fetched,
        copied = applied.copied,
        pushed = pushed,
        deleted = applied.deleted,
        conflicts = applied.conflicts,
    )
}