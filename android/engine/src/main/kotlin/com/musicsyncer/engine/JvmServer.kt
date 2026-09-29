package com.musicsyncer.engine

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class JvmServer(
    private val fs: Fs,
    private val store: SyncStore,
    private val deviceId: String,
    val schemaVersion: Int = 1,
    port: Int = 0,
) {
    val port: Int
    private val server: HttpServer
    private val executor: ExecutorService = Executors.newCachedThreadPool()

    init {
        server = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)
        server.executor = executor
        this.port = server.address.port
        route()
    }

    // Deliberately simple: drops "", "." and ".." segments. Real traversal
    // safety is enforced by the engine's Fs implementations (PathFs guards
    // with startsWith(root)), so the server only needs path normalization.
    private fun safeJoin(rel: String): String =
        rel.split('/').filter { it != "" && it != "." && it != ".." }.joinToString("/")

    private fun ok(exchange: HttpExchange, json: String) {
        val bytes = json.encodeToByteArray()
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun err(exchange: HttpExchange, code: Int, json: String) {
        val bytes = json.encodeToByteArray()
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun route() {
        server.createContext("/") { exchange ->
            try {
                val path = exchange.requestURI.path
                val qs = exchange.requestURI.rawQuery ?: ""
                val params = qs.split('&').filter { it.isNotBlank() }.associate { kv ->
                    val i = kv.indexOf('='); if (i < 0) kv to "" else URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8) to URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8)
                }
                when (path) {
                    "/handshake" -> {
                        val clientId = params["device_id"] ?: ""
                        val st = store.syncStateGet(clientId)
                        ok(exchange, GsonHolder.gson.toJson(HandshakeResp(schemaVersion, deviceId, store.journalHead(), st?.lastSeenJournalId ?: 0L)))
                    }
                    "/manifest" -> {
                        val man = store.manifestAll().map { ManifestWire(it.path, it.size, it.mtimeNs, it.sha256) }
                        ok(exchange, GsonHolder.gson.toJson(man))
                    }
                    "/journal" -> {
                        val after = params["after"]?.toLongOrNull() ?: 0L
                        ok(exchange, GsonHolder.gson.toJson(store.journalSince(after)))
                    }
                    "/file" -> {
                        val rel = safeJoin(params["path"] ?: "")
                        if (exchange.requestMethod == "POST") {
                            val expected = params["sha"] ?: ""
                            if (rel.isBlank()) { err(exchange, 400, """{"ok":false,"error":"empty path"}"""); return@createContext }
                            val body = exchange.requestBody.readBytes()
                            if (expected.isNotEmpty() && Hashing.sha256(body) != expected) { err(exchange, 400, """{"ok":false,"error":"sha256 mismatch"}"""); return@createContext }
                            // Random-token partial in the target's OWN directory (Python parity):
                            // a fixed ".ms-partial-x-$rel" name would create a dot-DIRECTORY for
                            // nested rels that scan never cleans, and collides under concurrency.
                            val dir = rel.substringBeforeLast('/', "")
                            val name = rel.substringAfterLast('/')
                            val partial = (if (dir.isEmpty()) "" else "$dir/") + ".ms-partial-" + java.util.concurrent.ThreadLocalRandom.current().nextInt(0, Int.MAX_VALUE).toString(16) + "-" + name
                            fs.mkdirs(dir)
                            fs.write(partial, body)
                            fs.rename(partial, rel)
                            ok(exchange, """{"ok":true}""")
                        } else {
                            // Empty/directory path must 404, not 500: fs.exists("") is true for
                            // the root dir and read() would throw.
                            if (rel.isBlank() || !fs.exists(rel)) { exchange.sendResponseHeaders(404, -1); exchange.close(); return@createContext }
                            val bytes = fs.read(rel)
                            exchange.sendResponseHeaders(200, bytes.size.toLong())
                            exchange.responseBody.use { it.write(bytes) }
                        }
                    }
                    "/sync" -> {
                        val req = GsonHolder.gson.fromJson(exchange.requestBody.reader(StandardCharsets.UTF_8), SyncRequest::class.java)
                        scan(fs, store, deviceId, System.currentTimeMillis() * 1_000_000)
                        val clientManifest = req.manifest.associate { it.path to Triple(it.size, it.mtimeNs, it.sha256) }
                        adoptShas(store, clientManifest, fs, System.currentTimeMillis() * 1_000_000)
                        val serverOps = store.journalSince(req.serverCursor)
                        val serverManifest = store.manifestAll().map { ManifestWire(it.path, it.size, it.mtimeNs, it.sha256) }
                        ok(exchange, GsonHolder.gson.toJson(SyncResponse(schemaVersion, serverOps, serverManifest, emptyList(), store.journalHead())))
                    }
                    "/done" -> {
                        val req = GsonHolder.gson.fromJson(exchange.requestBody.reader(StandardCharsets.UTF_8), DoneRequest::class.java)
                        val plan = Plan(
                            delete = req.delete.toMutableList(),
                            conflictLoser = req.conflicts.map { Triple(it.path, it.tsNs, it.sha256) }.toMutableList(),
                        )
                        applyPlan(fs, store, plan, deviceId, req.deviceId, emptyMap(), req.tsNs) { ByteArray(0) }
                        store.syncStateSet(req.deviceId, req.clientJournalHead, req.tsNs)
                        ok(exchange, """{"ok":true}""")
                    }
                    else -> { exchange.sendResponseHeaders(404, -1); exchange.close() }
                }
            } catch (e: Exception) {
                err(exchange, 500, """{"error":${GsonHolder.gson.toJson(e.message ?: "unknown")}}""")
            }
        }
    }

    fun start() = server.start()
    fun stop() {
        server.stop(0)
        executor.shutdown()
    }
}