package com.musicsyncer.app.fs

import com.musicsyncer.app.tag.m3uContent
import com.musicsyncer.engine.FsEntry
import com.musicsyncer.engine.JdbcStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaStoreHelpersTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(name: String): JdbcStore = JdbcStore("jdbc:sqlite:${tmp.newFile(name)}")

    @Test fun volumeAndPathMapsPrimaryToExternalPrimary() {
        assertEquals("external_primary" to "my songs", volumeAndPath("primary:my songs"))
    }

    @Test fun volumeAndPathKeepsSdVolumeId() {
        assertEquals("3737-6133" to "my songs", volumeAndPath("3737-6133:my songs"))
    }

    @Test fun volumeAndPathHandlesEmptyPath() {
        assertEquals("external_primary" to "", volumeAndPath("primary:"))
    }

    @Test fun volumeAndPathRejectsMalformed() {
        assertNull(volumeAndPath("nocolon"))
        assertNull(volumeAndPath(":path"))
        assertNull(volumeAndPath(""))
    }

    @Test fun baselineReStampsSubSecondGapWithoutJournaling() {
        val store = store("baseline.db")
        store.manifestUpsert("A.mp3", 100, 1_700_000_000_123_000_000L, "sha", 1) // SAF ms precision
        baselineMtimes(store, listOf(FsEntry("A.mp3", 100, 1_700_000_000_000_000_000L))) // MediaStore seconds
        assertEquals(1_700_000_000_000_000_000L, store.manifestGet("A.mp3")?.mtimeNs)
        assertEquals("sha", store.manifestGet("A.mp3")?.sha256)
        assertEquals(0L, store.journalHead()) // re-stamp must not journal
    }

    @Test fun baselineLeavesRealChangesAlone() {
        val store = store("baseline2.db")
        store.manifestUpsert("A.mp3", 100, 1_700_000_000_000_000_000L, null, 1)
        baselineMtimes(store, listOf(FsEntry("A.mp3", 100, 1_700_000_030_000_000_000L))) // >1s gap = real change
        assertEquals(1_700_000_000_000_000_000L, store.manifestGet("A.mp3")?.mtimeNs)
    }

    @Test fun baselineLeavesSizeMismatchesAlone() {
        val store = store("baseline3.db")
        store.manifestUpsert("A.mp3", 100, 1_700_000_000_123_000_000L, null, 1)
        baselineMtimes(store, listOf(FsEntry("A.mp3", 200, 1_700_000_000_000_000_000L)))
        assertEquals(1_700_000_000_123_000_000L, store.manifestGet("A.mp3")?.mtimeNs)
    }

    @Test fun baselineIgnoresUnknownEntries() {
        val store = store("baseline4.db")
        baselineMtimes(store, listOf(FsEntry("ghost.mp3", 100, 1L)))
        assertEquals(0L, store.journalHead())
    }

    @Test fun rowToEntryStripsFolderPrefix() {
        val e = mediaStoreRowToEntry("my songs", "my songs/engsongs/", "A.mp3", 100, 1_700_000_000L)
        assertEquals("engsongs/A.mp3", e?.rel) // folder-relative, like the manifest
        assertEquals(100L, e?.size)
        assertEquals(1_700_000_000_000_000_000L, e?.mtimeNs) // seconds -> ns
    }

    @Test fun rowToEntryHandlesTopLevelFile() {
        val e = mediaStoreRowToEntry("my songs", "my songs/", "B.mp3", 5, 2L)
        assertEquals("B.mp3", e?.rel)
    }

    @Test fun rowToEntryRejectsOutsideFolder() {
        assertEquals(null, mediaStoreRowToEntry("my songs", "Music/", "C.mp3", 5, 2L))
    }

    @Test fun rowToEntryRejectsNamelessAndUnknownSize() {
        assertEquals(null, mediaStoreRowToEntry("my songs", "my songs/", null, 5, 2L))
        assertEquals(null, mediaStoreRowToEntry("my songs", "my songs/", "D.mp3", -1, 2L))
    }

    @Test fun deriveDirEntriesBuildsSingleLevelFromFullList() {
        val files = listOf(
            FsEntry("engsongs/A.mp3", 1, 1),
            FsEntry("engsongs/sub/B.mp3", 2, 1),
            FsEntry("C.mp3", 3, 1),
        )
        val root = deriveDirEntries("", files)
        assertEquals(setOf("C.mp3", "engsongs"), root.map { it.rel }.toSet())
        assertEquals(false, root.first { it.rel == "C.mp3" }.isDirectory)
        assertEquals(true, root.first { it.rel == "engsongs" }.isDirectory)
        val engsongs = deriveDirEntries("engsongs", files)
        assertEquals(setOf("engsongs/A.mp3", "engsongs/sub"), engsongs.map { it.rel }.toSet())
        assertEquals(true, engsongs.first { it.rel == "engsongs/sub" }.isDirectory)
        assertEquals(1L, engsongs.first { it.rel == "engsongs/A.mp3" }.size)
    }

    @Test fun m3uContentListsFileNames() {
        val content = m3uContent("lovely", listOf("playlists/lovely/A.mp3", "playlists/lovely/B.mp3"))
        assertEquals("#EXTM3U\nA.mp3\nB.mp3\n", content)
    }
}