package com.musicsyncer.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.musicsyncer.app.store.MusicSyncDatabase
import com.musicsyncer.app.store.RoomStore
import com.musicsyncer.engine.SyncStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomStoreTest {
    private lateinit var store: SyncStore
    private lateinit var db: MusicSyncDatabase

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, MusicSyncDatabase::class.java).allowMainThreadQueries().build()
        store = RoomStore(db)
    }

    @Test fun manifestRoundtrip() {
        assertNull(store.manifestGet("A.mp3"))
        store.manifestUpsert("A.mp3", 10, 5, "abc", 100)
        assertEquals("abc", store.manifestGet("A.mp3")?.sha256)
        assertEquals(10L, store.manifestGet("A.mp3")?.size)
        store.manifestDelete("A.mp3")
        assertNull(store.manifestGet("A.mp3"))
    }

    @Test fun journalAppendSinceHead() {
        assertEquals(0L, store.journalHead())
        val id1 = store.journalAppend("CREATE", "A.mp3", 10, "abc", 100, "laptop")
        val id2 = store.journalAppend("DELETE", "A.mp3", null, null, 200, "phone")
        assertEquals(id2, store.journalHead())
        assertEquals(listOf("DELETE"), store.journalSince(id1).map { it.op })
    }

    @Test fun invalidOpRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            store.journalAppend("RENAME", "A.mp3", 1, "a", 1, "me")
        }
    }

    @Test fun syncStateRoundtrip() {
        assertNull(store.syncStateGet("phone"))
        store.syncStateSet("phone", 42, 999)
        assertEquals(42L, store.syncStateGet("phone")?.lastSeenJournalId)
    }

    @Test fun manifestAllSorted() {
        store.manifestUpsert("b", 1, 1, "x", 1)
        store.manifestUpsert("a", 1, 1, "y", 1)
        assertEquals(listOf("a", "b"), store.manifestAll().map { it.path })
    }
}