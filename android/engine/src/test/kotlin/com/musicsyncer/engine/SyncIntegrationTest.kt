package com.musicsyncer.engine

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SyncIntegrationTest {
    @TempDir lateinit var dir: Path

    private class Pair(val rootA: Path, val rootB: Path, val srv: JvmServer, val serverStore: JdbcStore)

    private fun pair(): Pair {
        val rootA = dir.resolve("A"); val rootB = dir.resolve("B")
        Files.createDirectories(rootA); Files.createDirectories(rootB)
        val serverStore = JdbcStore("jdbc:sqlite:${dir.resolve("s.db")}")
        val srv = JvmServer(PathFs(rootA), serverStore, "laptop")
        srv.start()
        return Pair(rootA, rootB, srv, serverStore)
    }

    // Stable path per test (matches the Python fixture's tmp_path/"c.db"): the
    // phone's journal + sync_state must persist across sync() calls, otherwise
    // phone-initiated deletes/moves are forgotten between sessions.
    private fun clientStore(): JdbcStore = JdbcStore("jdbc:sqlite:${dir.resolve("c.db")}")

    private fun sync(p: Pair): SyncSummary {
        val store = clientStore()
        val summary = runSyncSession("http://127.0.0.1:${p.srv.port}", PathFs(p.rootB), store, "phone")
        return summary
    }

    @Test fun oneWayCreatePropagates() {
        val p = pair()
        try {
            Files.createDirectories(p.rootA.resolve("Rock"))
            Files.write(p.rootA.resolve("Rock/A.mp3"), "content-a".encodeToByteArray())
            scan(PathFs(p.rootA), p.serverStore, "laptop", 1)
            val s = sync(p)
            assertEquals(listOf("Rock/A.mp3"), s.fetched)
            assertEquals("content-a", Files.readString(p.rootB.resolve("Rock/A.mp3")))
            assertEquals(
                Hashing.sha256(Files.readAllBytes(p.rootA.resolve("Rock/A.mp3"))),
                Hashing.sha256(Files.readAllBytes(p.rootB.resolve("Rock/A.mp3"))),
            )
        } finally { p.srv.stop() }
    }

    @Test fun bidirectionalCreates() {
        val p = pair()
        try {
            Files.write(p.rootA.resolve("A.mp3"), "from-laptop".encodeToByteArray())
            Files.write(p.rootB.resolve("B.mp3"), "from-phone".encodeToByteArray())
            scan(PathFs(p.rootA), p.serverStore, "laptop", 1)
            sync(p)
            assertEquals("from-phone", Files.readString(p.rootA.resolve("B.mp3")))
            assertEquals(true, Files.exists(p.rootA.resolve("A.mp3")) && Files.exists(p.rootB.resolve("A.mp3")))
        } finally { p.srv.stop() }
    }

    @Test fun deletePropagates() {
        val p = pair()
        try {
            Files.write(p.rootA.resolve("A.mp3"), byteArrayOf(1))
            scan(PathFs(p.rootA), p.serverStore, "laptop", 1)
            sync(p)
            Files.delete(p.rootA.resolve("A.mp3"))
            scan(PathFs(p.rootA), p.serverStore, "laptop", 2)
            sync(p)
            assertEquals(false, Files.exists(p.rootB.resolve("A.mp3")))
        } finally { p.srv.stop() }
    }

    @Test fun phoneDeletePropagatesToLaptop() {
        val p = pair()
        try {
            Files.write(p.rootA.resolve("A.mp3"), byteArrayOf(1))
            scan(PathFs(p.rootA), p.serverStore, "laptop", 1)
            sync(p)
            Files.delete(p.rootB.resolve("A.mp3"))
            sync(p)
            assertEquals(false, Files.exists(p.rootA.resolve("A.mp3")))
        } finally { p.srv.stop() }
    }

    @Test fun phoneMovePropagates() {
        val p = pair()
        try {
            Files.createDirectories(p.rootA.resolve("Rock"))
            Files.write(p.rootA.resolve("Rock/Song.mp3"), "content".encodeToByteArray())
            scan(PathFs(p.rootA), p.serverStore, "laptop", 1)
            sync(p)
            Files.createDirectories(p.rootB.resolve("Fav"))
            Files.move(p.rootB.resolve("Rock/Song.mp3"), p.rootB.resolve("Fav/Song.mp3"))
            sync(p)
            assertEquals(true, Files.exists(p.rootA.resolve("Fav/Song.mp3")))
            assertEquals(false, Files.exists(p.rootA.resolve("Rock/Song.mp3")))
        } finally { p.srv.stop() }
    }

    @Test fun conflictRemoteWinsAndConverges() {
        val p = pair()
        try {
            Files.write(p.rootA.resolve("A.mp3"), "base".encodeToByteArray())
            scan(PathFs(p.rootA), p.serverStore, "laptop", 1)
            sync(p)
            Files.write(p.rootA.resolve("A.mp3"), "laptop-edit".encodeToByteArray())
            Files.setLastModifiedTime(p.rootA.resolve("A.mp3"), java.nio.file.attribute.FileTime.from(3_000_000_000L, java.util.concurrent.TimeUnit.NANOSECONDS))
            // Long.MAX_VALUE: the phone's scan inside runSyncSession stamps its
            // MODIFY op with System.nanoTime() (~1e15), which would beat any
            // fixed literal. Long.MAX_VALUE guarantees the laptop edit is newer
            // (Python's equivalent: the client scan uses now_ns=1, making the
            // phone edit older).
            scan(PathFs(p.rootA), p.serverStore, "laptop", Long.MAX_VALUE)
            Files.write(p.rootB.resolve("A.mp3"), "phone-edit".encodeToByteArray())
            Files.setLastModifiedTime(p.rootB.resolve("A.mp3"), java.nio.file.attribute.FileTime.from(2_000_000_000L, java.util.concurrent.TimeUnit.NANOSECONDS))
            val s = sync(p)
            assertEquals("laptop-edit", Files.readString(p.rootB.resolve("A.mp3")))
            assertEquals(listOf("A.mp3"), s.conflicts)
            val conflicts = Files.list(p.rootB).use { it.filter { f -> f.fileName.toString().startsWith(".A.mp3.sync-conflict-") }.toList() }
            assertEquals(1, conflicts.size)
        } finally { p.srv.stop() }
    }

    @Test fun moveCostZeroTransfer() {
        val p = pair()
        try {
            Files.createDirectories(p.rootA.resolve("Rock"))
            Files.write(p.rootA.resolve("Rock/Song.mp3"), "same-content".encodeToByteArray())
            scan(PathFs(p.rootA), p.serverStore, "laptop", 1)
            sync(p)
            Files.createDirectories(p.rootA.resolve("Fav"))
            Files.move(p.rootA.resolve("Rock/Song.mp3"), p.rootA.resolve("Fav/Song.mp3"))
            scan(PathFs(p.rootA), p.serverStore, "laptop", 2)
            // The laptop's library is hashed: fill the moved file's sha (the lazy
            // scan leaves it NULL, and the content-addressed copy path needs a
            // non-null sha). Mirrors the Python test's hashed-laptop scenario.
            val row = p.serverStore.manifestGet("Fav/Song.mp3")!!
            p.serverStore.manifestUpsert("Fav/Song.mp3", row.size, row.mtimeNs, Hashing.sha256(Files.readAllBytes(p.rootA.resolve("Fav/Song.mp3"))), 2)
            val count = AtomicInteger(0)
            val store = clientStore()
            // count GET /file calls by wrapping the fetch in runSyncSession? Instead:
            // assert via summary: the phone must COPY locally, so nothing fetched.
            // We verify content-addressed behavior directly: the second sync must
            // not transfer Rock/... or Fav/... bytes. Since SyncSummary counts
            // copied vs fetched, assert copied contains the moved path.
            val summary = runSyncSession("http://127.0.0.1:${p.srv.port}", PathFs(p.rootB), store, "phone")
            assertEquals("same-content", Files.readString(p.rootB.resolve("Fav/Song.mp3")))
            assertEquals(false, Files.exists(p.rootB.resolve("Rock/Song.mp3")))
            assertEquals(listOf("Fav/Song.mp3"), summary.copied)
        } finally { p.srv.stop() }
    }

    @Test fun identicalTreesSyncWithZeroTransfer() {
        val p = pair()
        try {
            // Seed BOTH sides with the SAME file (simulating "same library on both").
            Files.createDirectories(p.rootA.resolve("Rock"))
            Files.write(p.rootA.resolve("Rock/A.mp3"), "same-content".encodeToByteArray())
            Files.createDirectories(p.rootB.resolve("Rock"))
            Files.write(p.rootB.resolve("Rock/A.mp3"), "same-content".encodeToByteArray())
            // Laptop has a hashed library (real sha in its manifest — e.g. from a
            // previous sync/verify); the phone scans lazily (NULL sha).
            scan(PathFs(p.rootA), p.serverStore, "laptop", 1)
            val row = p.serverStore.manifestGet("Rock/A.mp3")!!
            p.serverStore.manifestUpsert("Rock/A.mp3", row.size, row.mtimeNs, Hashing.sha256(Files.readAllBytes(p.rootA.resolve("Rock/A.mp3"))), 1)
            val s = sync(p)
            assertEquals(true, s.fetched.isEmpty() && s.pushed.isEmpty())  // zero transfer
            assertEquals(true, clientStore().manifestGet("Rock/A.mp3")?.sha256 != null)  // sha adopted
        } finally { p.srv.stop() }
    }

    @Test fun sameSizeModifyPropagates() {
        val p = pair()
        try {
            Files.createDirectories(p.rootA.resolve("Rock"))
            Files.write(p.rootA.resolve("Rock/A.mp3"), "content-a".encodeToByteArray())
            Files.createDirectories(p.rootB.resolve("Rock"))
            Files.write(p.rootB.resolve("Rock/A.mp3"), "content-a".encodeToByteArray())
            // Both libraries hashed (real shas in both manifests — hashed-phone scenario).
            scan(PathFs(p.rootA), p.serverStore, "laptop", 1)
            val srow = p.serverStore.manifestGet("Rock/A.mp3")!!
            p.serverStore.manifestUpsert("Rock/A.mp3", srow.size, srow.mtimeNs, Hashing.sha256(Files.readAllBytes(p.rootA.resolve("Rock/A.mp3"))), 1)
            val store = clientStore()
            scan(PathFs(p.rootB), store, "phone", 1)
            val crow = store.manifestGet("Rock/A.mp3")!!
            store.manifestUpsert("Rock/A.mp3", crow.size, crow.mtimeNs, Hashing.sha256(Files.readAllBytes(p.rootB.resolve("Rock/A.mp3"))), 1)
            // Laptop rewrites to DIFFERENT content of the SAME byte size; the lazy
            // scan journals a MODIFY with NULL sha.
            Files.write(p.rootA.resolve("Rock/A.mp3"), "content-b".encodeToByteArray())
            scan(PathFs(p.rootA), p.serverStore, "laptop", 2)
            val s = runSyncSession("http://127.0.0.1:${p.srv.port}", PathFs(p.rootB), store, "phone")
            assertEquals(listOf("Rock/A.mp3"), s.fetched)  // the MODIFY propagated
            assertEquals("content-b", Files.readString(p.rootB.resolve("Rock/A.mp3")))
            // The laptop's manifest sha was NOT poisoned with the phone's stale sha.
            assertEquals(null, p.serverStore.manifestGet("Rock/A.mp3")?.sha256)
        } finally { p.srv.stop() }
    }

    @Test fun secondSyncIsNoop() {
        val p = pair()
        try {
            Files.write(p.rootA.resolve("A.mp3"), byteArrayOf(1))
            scan(PathFs(p.rootA), p.serverStore, "laptop", 1)
            sync(p)
            val s = sync(p)
            assertEquals(true, s.fetched.isEmpty() && s.deleted.isEmpty() && s.pushed.isEmpty() && s.copied.isEmpty())
        } finally { p.srv.stop() }
    }

    @Test fun schemaMismatchRaises() {
        Files.createDirectories(dir.resolve("A"))
        val store = JdbcStore("jdbc:sqlite:${dir.resolve("sm.db")}")
        val srv = JvmServer(PathFs(dir.resolve("A")), store, "laptop", schemaVersion = 2)
        srv.start()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                runSyncSession("http://127.0.0.1:${srv.port}", PathFs(dir.resolve("B")), clientStore(), "phone")
            }
        } finally { srv.stop() }
    }
}