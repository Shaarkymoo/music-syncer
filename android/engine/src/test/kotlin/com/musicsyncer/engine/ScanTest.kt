package com.musicsyncer.engine

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ScanTest {
    @TempDir lateinit var dir: Path

    private fun store(): JdbcStore = JdbcStore("jdbc:sqlite:${dir.resolve("t.db")}")
    private fun fs(): PathFs = PathFs(dir.resolve("root"))

    @Test
    fun detectsCreate() {
        Files.createDirectories(dir.resolve("root/Rock"))
        Files.write(dir.resolve("root/Rock/A.mp3"), byteArrayOf(1))
        val s = store(); scan(fs(), s, "laptop", 1000)
        val ops = s.journalSince(0)
        assertEquals(listOf("CREATE"), ops.map { it.op })
        assertEquals("Rock/A.mp3", ops[0].path)
        assertEquals(true, s.manifestGet("Rock/A.mp3")?.sha256 != null)
    }

    @Test
    fun idempotentNoNewOps() {
        Files.createDirectories(dir.resolve("root"))
        Files.write(dir.resolve("root/A.mp3"), byteArrayOf(1))
        val s = store()
        scan(fs(), s, "laptop", 1000)
        val head = s.journalHead()
        scan(fs(), s, "laptop", 2000)
        assertEquals(head, s.journalHead())
    }

    @Test
    fun detectsModify() {
        Files.createDirectories(dir.resolve("root"))
        val p = dir.resolve("root/A.mp3")
        Files.write(p, byteArrayOf(1))
        val s = store()
        scan(fs(), s, "laptop", 1000)
        Files.write(p, byteArrayOf(2))
        Files.setLastModifiedTime(p, FileTime.from(p.toFile().lastModified() + 1_000_000_000, TimeUnit.NANOSECONDS))
        scan(fs(), s, "laptop", 2000)
        assertEquals(listOf("MODIFY"), s.journalSince(1).map { it.op })
    }

    @Test
    fun detectsDelete() {
        Files.createDirectories(dir.resolve("root"))
        val p = dir.resolve("root/A.mp3")
        Files.write(p, byteArrayOf(1))
        val s = store()
        scan(fs(), s, "laptop", 1000)
        Files.delete(p)
        scan(fs(), s, "laptop", 2000)
        assertEquals(listOf("DELETE"), s.journalSince(1).map { it.op })
        assertEquals(null, s.manifestGet("A.mp3"))
    }

    @Test
    fun skipsDotfilesAndCleansPartials() {
        Files.createDirectories(dir.resolve("root"))
        Files.write(dir.resolve("root/.hidden.mp3"), byteArrayOf(1))
        Files.write(dir.resolve("root/.ms-partial-1234-A.mp3"), byteArrayOf(2))
        val s = store()
        scan(fs(), s, "laptop", 1000)
        assertEquals(false, Files.exists(dir.resolve("root/.ms-partial-1234-A.mp3")))
        assertEquals(0L, s.journalHead())
    }
}