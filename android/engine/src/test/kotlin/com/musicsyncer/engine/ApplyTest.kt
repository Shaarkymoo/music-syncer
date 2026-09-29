package com.musicsyncer.engine

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ApplyTest {
    @TempDir lateinit var dir: Path

    private fun store(): JdbcStore = JdbcStore("jdbc:sqlite:${dir.resolve("t.db")}")
    private fun fs() = PathFs(dir)

    @Test fun fetchWritesVerifiedFile() {
        val sha = Hashing.sha256("abcd".encodeToByteArray())
        val plan = Plan(fetch = mutableListOf(Triple("Rock/A.mp3", 4L, sha)))
        val summary = applyPlan(fs(), store(), plan, "me", "phone", mapOf("Rock/A.mp3" to "CREATE"), 1000) { "abcd".encodeToByteArray() }
        assertEquals("abcd", Files.readString(dir.resolve("Rock/A.mp3")))
        assertEquals(listOf("Rock/A.mp3"), summary.fetched)
        assertEquals(sha, store().manifestGet("Rock/A.mp3")?.sha256)
        assertEquals(listOf("CREATE"), store().journalSince(0).map { it.op })
    }

    @Test fun rejectsBadSha() {
        val plan = Plan(fetch = mutableListOf(Triple("A.mp3", 4L, "abcd")))
        assertThrows(IllegalArgumentException::class.java) {
            applyPlan(fs(), store(), plan, "me", "phone", mapOf("A.mp3" to "CREATE"), 1000) { "XXXX".encodeToByteArray() }
        }
        assertEquals(false, Files.exists(dir.resolve("A.mp3")))
    }

    @Test fun nullShaFetchStoresReceivedHash() {
        // Lazy source: fetch carries sha=null; the received hash is stored, no raise.
        val plan = Plan(fetch = mutableListOf(Triple("A.mp3", 4L, null)))
        val summary = applyPlan(fs(), store(), plan, "me", "phone", mapOf("A.mp3" to "CREATE"), 1000) { "abcd".encodeToByteArray() }
        assertEquals("abcd", Files.readString(dir.resolve("A.mp3")))
        assertEquals(listOf("A.mp3"), summary.fetched)
        assertEquals(Hashing.sha256("abcd".encodeToByteArray()), store().manifestGet("A.mp3")?.sha256)
    }

    @Test fun contentAddressedCopy() {
        Files.createDirectories(dir.resolve("Old"))
        Files.write(dir.resolve("Old/song.mp3"), "same-content".encodeToByteArray())
        val s = store()
        val sha = Hashing.sha256(fs().openRead("Old/song.mp3"))
        s.manifestUpsert("Old/song.mp3", 12, 1, sha, 100)
        val plan = Plan(fetch = mutableListOf(Triple("New/song.mp3", 12L, sha)))
        var calls = 0
        val summary = applyPlan(fs(), s, plan, "me", "phone", mapOf("New/song.mp3" to "CREATE"), 1000) { calls++; ByteArray(0) }
        assertEquals("same-content", Files.readString(dir.resolve("New/song.mp3")))
        assertEquals(listOf("New/song.mp3"), summary.copied)
        assertEquals(0, calls)  // zero bytes fetched from remote
    }

    @Test fun deleteLastAndJournaled() {
        Files.write(dir.resolve("A.mp3"), byteArrayOf(1))
        val s = store(); s.manifestUpsert("A.mp3", 1, 1, "x", 100)
        val summary = applyPlan(fs(), s, Plan(delete = mutableListOf("A.mp3")), "me", "phone", emptyMap(), 1000) { ByteArray(0) }
        assertEquals(false, Files.exists(dir.resolve("A.mp3")))
        assertEquals(listOf("A.mp3"), summary.deleted)
        assertEquals(listOf("DELETE"), s.journalSince(0).map { it.op })
    }

    @Test fun conflictLoserPreservedAsHiddenFile() {
        Files.write(dir.resolve("A.mp3"), "my-old-content".encodeToByteArray())
        val s = store(); s.manifestUpsert("A.mp3", 15, 1, "oldsha", 100)
        val sha = Hashing.sha256("newsha".encodeToByteArray())
        val plan = Plan(
            fetch = mutableListOf(Triple("A.mp3", 6L, sha)),
            conflictLoser = mutableListOf(Triple("A.mp3", 100L, "oldsha")),
        )
        val summary = applyPlan(fs(), s, plan, "me", "phone", mapOf("A.mp3" to "MODIFY"), 1000) { "newsha".encodeToByteArray() }
        assertEquals("newsha", Files.readString(dir.resolve("A.mp3")))
        val conflicts = Files.list(dir).use { s2 -> s2.filter { it.fileName.toString().startsWith(".A.mp3.sync-conflict-") }.toList() }
        assertEquals(1, conflicts.size)
        assertEquals(listOf("A.mp3"), summary.conflicts)
    }

    @Test fun conflictLoserKeepsOriginalDirectory() {
        Files.createDirectories(dir.resolve("Rock"))
        Files.write(dir.resolve("Rock/A.mp3"), "my-old-content".encodeToByteArray())
        val s = store(); s.manifestUpsert("Rock/A.mp3", 15, 1, "oldsha", 100)
        val sha = Hashing.sha256("newsha".encodeToByteArray())
        val plan = Plan(
            fetch = mutableListOf(Triple("Rock/A.mp3", 6L, sha)),
            conflictLoser = mutableListOf(Triple("Rock/A.mp3", 100L, "oldsha")),
        )
        val summary = applyPlan(fs(), s, plan, "me", "phone", mapOf("Rock/A.mp3" to "MODIFY"), 1000) { "newsha".encodeToByteArray() }
        assertEquals("newsha", Files.readString(dir.resolve("Rock/A.mp3")))
        assertTrue(Files.exists(dir.resolve("Rock/.A.mp3.sync-conflict-100.mp3")))
        assertEquals(listOf("Rock/A.mp3"), summary.conflicts)
    }

    @Test fun idempotentNoopJournalsNothing() {
        Files.write(dir.resolve("A.mp3"), "same".encodeToByteArray())
        val s = store()
        val sha = Hashing.sha256(fs().openRead("A.mp3"))
        s.manifestUpsert("A.mp3", 4, 1, sha, 100)
        val plan = Plan(fetch = mutableListOf(Triple("A.mp3", 4L, sha)))
        applyPlan(fs(), s, plan, "me", "phone", mapOf("A.mp3" to "MODIFY"), 1000) { fs().read("A.mp3") }
        assertEquals(0L, s.journalHead())
    }

    @Test fun traversalRejectedByResolve() {
        val plan = Plan(fetch = mutableListOf(Triple("../evil", 1L, "abc")))
        assertThrows(IllegalArgumentException::class.java) {
            applyPlan(fs(), store(), plan, "me", "phone", emptyMap(), 1000) { ByteArray(0) }
        }
    }
}