package com.musicsyncer.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MergeTest {
    private fun m(path: String, sha: String, size: Long = 10, mtime: Long = 5) =
        mapOf(path to Triple(size, mtime, sha))

    private fun op(id: Long, op: String, path: String, device: String, tsNs: Long = id * 100, sha: String? = null) =
        JournalOp(id, op, path, null, sha, tsNs, device)

    @Test fun remoteOnlyPathIsFetched() {
        val plan = buildPlan(emptyMap(), emptyList(), m("B.mp3", "bbb"), emptyList(), 0, "me")
        assertEquals(listOf(Triple("B.mp3", 10L, "bbb")), plan.fetch)
    }

    @Test fun identicalFilesAreNoop() {
        val plan = buildPlan(m("A.mp3", "aaa"), emptyList(), m("A.mp3", "aaa"), emptyList(), 0, "me")
        assertEquals(true, plan.fetch.isEmpty() && plan.push.isEmpty() && plan.delete.isEmpty())
    }

    @Test fun localOnlyWithUnseenCreateIsPushed() {
        val plan = buildPlan(m("A.mp3", "aaa"), listOf(op(5, "CREATE", "A.mp3", "me")), emptyMap(), emptyList(), 0, "me")
        assertEquals(listOf(Triple("A.mp3", 10L, "aaa")), plan.push)
    }

    @Test fun localOnlyWithoutUnseenChangeIsDeleted() {
        val plan = buildPlan(m("A.mp3", "aaa"), listOf(op(5, "CREATE", "A.mp3", "me")), emptyMap(), emptyList(), 5, "me")
        assertEquals(listOf("A.mp3"), plan.delete)
    }

    @Test fun echoOpsDoNotCauseWrongPush() {
        val plan = buildPlan(m("A.mp3", "aaa"), listOf(op(5, "CREATE", "A.mp3", "phone")), emptyMap(), emptyList(), 0, "me")
        assertEquals(listOf("A.mp3"), plan.delete)
        assertEquals(true, plan.push.isEmpty())
    }

    @Test fun remotePathWithUnseenLocalDeleteIsNotFetched() {
        val plan = buildPlan(emptyMap(), listOf(op(6, "DELETE", "A.mp3", "me", 200)), m("A.mp3", "aaa"), emptyList(), 5, "me")
        assertEquals(true, plan.fetch.isEmpty() && plan.delete.isEmpty() && plan.push.isEmpty())
    }

    @Test fun remotePathWithSeenLocalDeleteIsFetched() {
        val plan = buildPlan(emptyMap(), listOf(op(6, "DELETE", "A.mp3", "me", 200)), m("A.mp3", "aaa"), emptyList(), 6, "me")
        assertEquals(listOf(Triple("A.mp3", 10L, "aaa")), plan.fetch)
    }

    @Test fun conflictRemoteWins() {
        val local = listOf(op(1, "CREATE", "A.mp3", "me", 100))
        val remote = listOf(op(1, "MODIFY", "A.mp3", "phone", 200))
        val plan = buildPlan(m("A.mp3", "local"), local, m("A.mp3", "remote"), remote, 0, "me")
        assertEquals(listOf(Triple("A.mp3", 10L, "remote")), plan.fetch)
        assertEquals(listOf(Triple("A.mp3", 100L, "local")), plan.conflictLoser)
    }

    @Test fun conflictLocalWins() {
        val local = listOf(op(1, "MODIFY", "A.mp3", "me", 300))
        val remote = listOf(op(1, "MODIFY", "A.mp3", "phone", 200))
        val plan = buildPlan(m("A.mp3", "local"), local, m("A.mp3", "remote"), remote, 0, "me")
        assertEquals(listOf(Triple("A.mp3", 10L, "local")), plan.push)
        assertEquals(true, plan.fetch.isEmpty() && plan.conflictLoser.isEmpty())
    }
}