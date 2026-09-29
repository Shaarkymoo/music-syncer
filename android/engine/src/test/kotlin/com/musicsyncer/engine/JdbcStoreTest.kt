package com.musicsyncer.engine

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JdbcStoreTest {
    @TempDir lateinit var dir: Path

    private fun store(): JdbcStore = JdbcStore("jdbc:sqlite:${dir.resolve("t.db")}")

    @Test
    fun manifestRoundtrip() {
        val s = store()
        assertEquals(null, s.manifestGet("A.mp3"))
        s.manifestUpsert("A.mp3", 10, 5, "abc", 100)
        assertEquals("abc", s.manifestGet("A.mp3")?.sha256)
        assertEquals(10L, s.manifestGet("A.mp3")?.size)
        s.manifestDelete("A.mp3")
        assertEquals(null, s.manifestGet("A.mp3"))
    }

    @Test
    fun journalAppendSinceHead() {
        val s = store()
        assertEquals(0L, s.journalHead())
        val id1 = s.journalAppend("CREATE", "A.mp3", 10, "abc", 100, "laptop")
        val id2 = s.journalAppend("DELETE", "A.mp3", null, null, 200, "phone")
        assertEquals(id2, s.journalHead())
        assertEquals(listOf("DELETE"), s.journalSince(id1).map { it.op })
    }

    @Test
    fun syncStateRoundtrip() {
        val s = store()
        assertEquals(null, s.syncStateGet("phone"))
        s.syncStateSet("phone", 42, 999)
        assertEquals(42L, s.syncStateGet("phone")?.lastSeenJournalId)
    }

    @Test
    fun invalidOpRejected() {
        val s = store()
        assertThrows(Exception::class.java) { s.journalAppend("RENAME", "A.mp3", 1, "a", 1, "me") }
    }
}