package com.musicsyncer.engine

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JvmServerTest {
    @TempDir lateinit var dir: Path

    private val json = "application/json".toMediaType()
    private val client = OkHttpClient()

    private fun server(): Pair<JvmServer, JdbcStore> {
        // DB lives OUTSIDE the served root (as in the Python fixture), so the
        // /sync scan sees only the music files and is a no-op.
        val root = dir.resolve("root")
        Files.createDirectories(root.resolve("Rock"))
        val data = "content-a".encodeToByteArray()
        Files.write(root.resolve("Rock/A.mp3"), data)
        Files.setLastModifiedTime(root.resolve("Rock/A.mp3"), FileTime.from(1, TimeUnit.NANOSECONDS))
        val store = JdbcStore("jdbc:sqlite:${dir.resolve("s.db")}")
        val sha = Hashing.sha256(data)
        store.journalAppend("CREATE", "Rock/A.mp3", data.size.toLong(), sha, 1, "laptop")
        store.manifestUpsert("Rock/A.mp3", data.size.toLong(), 1, sha, 1)
        val srv = JvmServer(PathFs(root), store, "laptop")
        srv.start()
        return srv to store
    }

    private fun get(srv: JvmServer, path: String): String =
        client.newCall(Request.Builder().url("http://127.0.0.1:${srv.port}$path").build()).execute().use { it.body!!.string() }

    @Test fun handshake() {
        val (srv, _) = server()
        try {
            val hs = GsonHolder.gson.fromJson(get(srv, "/handshake?device_id=phone"), HandshakeResp::class.java)
            assertEquals(1, hs.schemaVersion); assertEquals("laptop", hs.serverDeviceId); assertEquals(0L, hs.clientCursor)
        } finally { srv.stop() }
    }

    @Test fun manifest() {
        val (srv, _) = server()
        try {
            val body = get(srv, "/manifest")
            assertTrue(body.contains("Rock/A.mp3"))
        } finally { srv.stop() }
    }

    @Test fun journalSince() {
        val (srv, _) = server()
        try {
            val ops = GsonHolder.gson.fromJson(get(srv, "/journal?after=0"), Array<JournalOp>::class.java).toList()
            assertEquals(1, ops.size); assertEquals("Rock/A.mp3", ops[0].path)
        } finally { srv.stop() }
    }

    @Test fun getFile() {
        val (srv, _) = server()
        try {
            val bytes = client.newCall(Request.Builder().url("http://127.0.0.1:${srv.port}/file?path=${java.net.URLEncoder.encode("Rock/A.mp3", "UTF-8")}").build()).execute().use { it.body!!.bytes() }
            assertEquals("content-a", bytes.decodeToString())
        } finally { srv.stop() }
    }

    @Test fun postFileVerifiesSha() {
        val (srv, _) = server()
        try {
            val body = "new-bytes".encodeToByteArray()
            val sha = Hashing.sha256(body)
            val resp = client.newCall(Request.Builder().url("http://127.0.0.1:${srv.port}/file?path=${java.net.URLEncoder.encode("Rock/B.mp3", "UTF-8")}&sha=$sha").post(body.toRequestBody()).build()).execute()
            assertEquals(200, resp.code)
            assertEquals("new-bytes", Files.readString(dir.resolve("root/Rock/B.mp3")))
        } finally { srv.stop() }
    }

    @Test fun postFileRejectsBadSha() {
        val (srv, _) = server()
        try {
            val resp = client.newCall(Request.Builder().url("http://127.0.0.1:${srv.port}/file?path=${java.net.URLEncoder.encode("Rock/C.mp3", "UTF-8")}&sha=deadbeef").post("x".encodeToByteArray().toRequestBody()).build()).execute()
            assertEquals(400, resp.code)
        } finally { srv.stop() }
    }

    @Test fun postSyncReturnsServerState() {
        val (srv, _) = server()
        try {
            val req = SyncRequest("phone", 1, emptyList(), emptyList())
            val resp = client.newCall(Request.Builder().url("http://127.0.0.1:${srv.port}/sync").post(GsonHolder.gson.toJson(req).toRequestBody(json)).build()).execute().use { GsonHolder.gson.fromJson(it.body!!.string(), SyncResponse::class.java) }
            assertEquals(0, resp.serverJournalOps.size)
            assertTrue(resp.serverManifest.isNotEmpty())
        } finally { srv.stop() }
    }
}