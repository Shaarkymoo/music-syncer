package com.musicsyncer.app.fs

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
}