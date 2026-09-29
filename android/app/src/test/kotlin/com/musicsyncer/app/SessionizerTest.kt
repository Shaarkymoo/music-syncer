package com.musicsyncer.app

import com.musicsyncer.app.log.Sessionizer
import com.musicsyncer.engine.JournalOp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-Kotlin unit tests for the session-grouping algorithm (no Robolectric). */
class SessionizerTest {

    private fun op(id: Long, op: String, path: String, tsNs: Long, device: String) =
        JournalOp(id, op, path, null, null, tsNs, device)

    private val t0 = 1_700_000_000_000_000_000L // some wall-clock epoch-ns

    /** (a) A device switch splits a run, even when timestamps are close. */
    @Test fun deviceSwitchSplitsSession() {
        val ops = listOf(
            op(1, "CREATE", "playlists/a/s1.mp3", t0, "phone"),
            op(2, "CREATE", "playlists/b/s2.mp3", t0 + 1_000_000_000, "laptop"),
            op(3, "CREATE", "playlists/a/s3.mp3", t0 + 2_000_000_000, "phone"),
        )
        val sessions = Sessionizer.group(ops)
        assertEquals(listOf("phone", "laptop", "phone"), sessions.map { it.device })
        assertEquals(1, sessions[0].ops.size)
        assertEquals(1, sessions[1].ops.size)
        assertEquals(1, sessions[2].ops.size)
    }

    /** (b) A gap over 5 minutes splits a run even on the same device. */
    @Test fun gapOverFiveMinutesSplitsSession() {
        val ops = listOf(
            op(1, "CREATE", "playlists/a/s1.mp3", t0, "phone"),
            op(2, "CREATE", "playlists/a/s2.mp3", t0 + 300_000_000_001, "phone"),
        )
        val sessions = Sessionizer.group(ops)
        assertEquals(2, sessions.size)
        assertEquals(t0, sessions[0].startTsNs)
        assertEquals(t0 + 300_000_000_001, sessions[1].startTsNs)
    }

    /** (c) Playlist count is the number of distinct parent directories. */
    @Test fun playlistCountIsDistinctParents() {
        val ops = listOf(
            op(1, "CREATE", "playlists/lowkey/s1.mp3", t0, "phone"),
            op(2, "CREATE", "playlists/lowkey/s2.mp3", t0 + 1_000_000_000, "phone"),
            op(3, "CREATE", "playlists/chill/s3.mp3", t0 + 2_000_000_000, "phone"),
            op(4, "CREATE", "solo.mp3", t0 + 3_000_000_000, "phone"),
        )
        val sessions = Sessionizer.group(ops)
        assertEquals(1, sessions.size)
        // distinct parents: playlists/lowkey, playlists/chill, root (no slash)
        assertEquals(3, sessions[0].playlists)
    }

    /** (d) Counts per op type, and member ops are preserved for expansion. */
    @Test fun countsPerOpType() {
        val ops = listOf(
            op(1, "CREATE", "playlists/a/s1.mp3", t0, "phone"),
            op(2, "CREATE", "playlists/a/s2.mp3", t0 + 1_000_000_000, "phone"),
            op(3, "MODIFY", "playlists/a/s1.mp3", t0 + 2_000_000_000, "phone"),
            op(4, "DELETE", "playlists/a/s3.mp3", t0 + 3_000_000_000, "phone"),
        )
        val sessions = Sessionizer.group(ops)
        assertEquals(1, sessions.size)
        val s = sessions[0]
        assertEquals(2, s.created)
        assertEquals(1, s.modified)
        assertEquals(1, s.deleted)
        assertEquals(4, s.ops.size)
        assertEquals("phone", s.device)
        assertEquals(t0, s.startTsNs)
    }
}