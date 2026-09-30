package com.musicsyncer.engine

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FsTest {
    @TempDir lateinit var dir: Path

    @Test
    fun listReturnsFilesRecursivelyWithRelPaths() {
        Files.createDirectories(dir.resolve("Rock"))
        Files.write(dir.resolve("Rock/A.mp3"), byteArrayOf(1, 2, 3))
        Files.write(dir.resolve("B.mp3"), byteArrayOf(4))
        val entries = PathFs(dir).list().sortedBy { it.rel }
        assertEquals(listOf("B.mp3", "Rock/A.mp3"), entries.map { it.rel })
        assertEquals(3L, entries.first { it.rel == "Rock/A.mp3" }.size)
    }

    @Test
    fun readWriteRoundtrip() {
        val fs = PathFs(dir)
        fs.write("Fav/Song.mp3", byteArrayOf(9, 9))
        assertEquals(byteArrayOf(9, 9).toList(), fs.read("Fav/Song.mp3").toList())
    }

    @Test
    fun renameAndDelete() {
        val fs = PathFs(dir)
        fs.write("a.mp3", byteArrayOf(1))
        fs.rename("a.mp3", "b.mp3")
        assertEquals(true, fs.exists("b.mp3"))
        assertEquals(false, fs.exists("a.mp3"))
        fs.delete("b.mp3")
        assertEquals(false, fs.exists("b.mp3"))
    }

    @Test
    fun statReturnsEntryForSingleFile() {
        val fs = PathFs(dir)
        fs.write("Rock/A.mp3", byteArrayOf(1, 2, 3))
        val entry = fs.stat("Rock/A.mp3")
        assertEquals("Rock/A.mp3", entry.rel)
        assertEquals(3L, entry.size)
        assertTrue(entry.mtimeNs > 0)
    }

    @Test
    fun listDirReturnsSingleLevelFilesAndDirs() {
        val fs = PathFs(dir)
        fs.write("Rock/A.mp3", byteArrayOf(1))
        fs.write("Rock/sub/B.mp3", byteArrayOf(2))
        fs.write("C.mp3", byteArrayOf(1, 2, 3))
        val rootEntries = fs.listDir("").sortedBy { it.rel }
        assertEquals(listOf("C.mp3", "Rock"), rootEntries.map { it.rel })
        assertEquals(listOf(false, true), rootEntries.map { it.isDirectory })
        assertEquals(3L, rootEntries.first { it.rel == "C.mp3" }.size)
        val rockEntries = fs.listDir("Rock").sortedBy { it.rel }
        assertEquals(listOf("Rock/A.mp3", "Rock/sub"), rockEntries.map { it.rel })
        assertThrows(IOException::class.java) { fs.listDir("nope") }
    }

    @Test
    fun traversalRejected() {
        val fs = PathFs(dir)
        assertThrows(IllegalArgumentException::class.java) { fs.read("../evil") }
        assertThrows(IllegalArgumentException::class.java) { fs.delete("../../evil") }
    }
}