package com.musicsyncer.engine

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Mirrors tests/test_progress.py: scan emits (SCAN, i, total, rel) per file; callbacks are best-effort. */
class ProgressTest {
    @TempDir lateinit var dir: Path

    private fun store(): JdbcStore = JdbcStore("jdbc:sqlite:${dir.resolve("t.db")}")
    private fun fs(): PathFs = PathFs(dir.resolve("root"))

    private data class Event(val phase: SyncPhase, val done: Int, val total: Int, val rel: String)

    @Test
    fun scanReportsProgressPerFile() {
        Files.createDirectories(dir.resolve("root"))
        for (name in listOf("a.mp3", "b.mp3", "c.mp3")) {
            Files.write(dir.resolve("root/$name"), byteArrayOf(1))
        }
        val events = mutableListOf<Event>()
        scan(fs(), store(), "laptop", 1) { phase, done, total, rel ->
            events.add(Event(phase, done, total, rel))
        }
        val fileEvents = events.drop(1)  // skip the (SCAN, 0, 0, "") walk marker
        assertEquals(listOf(SyncPhase.SCAN, SyncPhase.SCAN, SyncPhase.SCAN), fileEvents.map { it.phase })
        assertEquals(listOf(1, 2, 3), fileEvents.map { it.done })  // 1-based, monotonically increasing
        assertTrue(fileEvents.all { it.total == 3 })               // total == number of files
        assertEquals(listOf("a.mp3", "b.mp3", "c.mp3"), fileEvents.map { it.rel })
    }

    @Test
    fun scanEmitsWalkMarkerFirst() {
        Files.createDirectories(dir.resolve("root"))
        for (name in listOf("a.mp3", "b.mp3", "c.mp3")) {
            Files.write(dir.resolve("root/$name"), byteArrayOf(1))
        }
        val events = mutableListOf<Event>()
        scan(fs(), store(), "laptop", 1) { phase, done, total, rel ->
            events.add(Event(phase, done, total, rel))
        }
        assertEquals(Event(SyncPhase.SCAN, 0, 0, ""), events.first())  // walk marker before fs.list()
        assertEquals(listOf(1, 2, 3), events.drop(1).map { it.done })  // per-file events unchanged
    }

    @Test
    fun scanProgressCallbackExceptionIsIgnored() {
        Files.createDirectories(dir.resolve("root"))
        for (name in listOf("a.mp3", "b.mp3")) {
            Files.write(dir.resolve("root/$name"), byteArrayOf(1))
        }
        val head = scan(fs(), store(), "laptop", 1) { _, _, _, _ -> throw RuntimeException("callback bug") }
        assertTrue(head > 0)  // a raising callback must never break the engine
    }

    @Test
    fun syncSessionReportsTransferAndDone() {
        val rootA = dir.resolve("A"); val rootB = dir.resolve("B")
        Files.createDirectories(rootA.resolve("Rock")); Files.createDirectories(rootB)
        Files.write(rootA.resolve("Rock/A.mp3"), "content-a".encodeToByteArray())
        val serverStore = JdbcStore("jdbc:sqlite:${dir.resolve("s.db")}")
        scan(PathFs(rootA), serverStore, "laptop", 1)
        val srv = JvmServer(PathFs(rootA), serverStore, "laptop")
        srv.start()
        try {
            val events = mutableListOf<Event>()
            runSyncSession("http://127.0.0.1:${srv.port}", PathFs(rootB), store(), "phone") { phase, done, total, rel ->
                events.add(Event(phase, done, total, rel))
            }
            assertTrue(events.any { it.phase == SyncPhase.TRANSFER })  // a file transferred
            assertTrue(events.any { it.phase == SyncPhase.DONE })      // session ended
            assertEquals("content-a", Files.readString(rootB.resolve("Rock/A.mp3")))
        } finally { srv.stop() }
    }
}