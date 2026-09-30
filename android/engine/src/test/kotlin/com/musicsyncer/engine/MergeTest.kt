package com.musicsyncer.engine

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MergeTest {
    @TempDir lateinit var dir: Path

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

    @Test fun nullLocalShaSameSizeIsIdentical() {
        // local has NULL sha, remote has sha, sizes match -> no fetch, no conflict
        val plan = buildPlan(mapOf("A.mp3" to Triple(10L, 5L, null)), emptyList(), m("A.mp3", "remotesha"), emptyList(), 0, "me")
        assertEquals(true, plan.fetch.isEmpty() && plan.push.isEmpty() && plan.delete.isEmpty())
    }

    @Test fun nullLocalShaDifferentSizeIsChange() {
        val plan = buildPlan(mapOf("A.mp3" to Triple(10L, 5L, null)), emptyList(), m("A.mp3", "remotesha", size = 20), emptyList(), 0, "me")
        assertEquals(true, plan.fetch.isNotEmpty() || plan.push.isNotEmpty() || plan.delete.isNotEmpty())
    }

    @Test fun bothNullShaDifferentSizeIsChange() {
        // both sides lazy-scanned but sizes differ -> real change (LWW), not identical
        val local = listOf(op(1, "MODIFY", "A.mp3", "me", 100))
        val remote = listOf(op(1, "MODIFY", "A.mp3", "phone", 200))
        val plan = buildPlan(mapOf("A.mp3" to Triple(10L, 5L, null)), local, mapOf("A.mp3" to Triple(20L, 5L, null)), remote, 0, "me")
        assertEquals(true, plan.fetch.isNotEmpty() || plan.push.isNotEmpty() || plan.delete.isNotEmpty())
    }

    @Test fun nullLocalShaSameSizeWithUnseenModifyIsChange() {
        // local has NULL sha, remote has sha, sizes match — but an unseen local
        // MODIFY means the local file was rewritten: NOT identical, local wins.
        val local = listOf(op(6, "MODIFY", "A.mp3", "me", 200))
        val plan = buildPlan(mapOf("A.mp3" to Triple(10L, 5L, null)), local, m("A.mp3", "remotesha"), emptyList(), 5, "me")
        assertEquals(listOf(Triple("A.mp3", 10L, null)), plan.push)
    }

    @Test fun nullLocalShaSameSizeWithSeenModifyIsIdentical() {
        // Peer already saw our MODIFY (cursor 6): identical-trees behavior preserved.
        val local = listOf(op(6, "MODIFY", "A.mp3", "me", 200))
        val plan = buildPlan(mapOf("A.mp3" to Triple(10L, 5L, null)), local, m("A.mp3", "remotesha"), emptyList(), 6, "me")
        assertEquals(true, plan.fetch.isEmpty() && plan.push.isEmpty() && plan.delete.isEmpty())
    }

    @Test fun adoptShasSkipsRecentlyModified() {
        val root = dir.resolve("root"); Files.createDirectories(root)
        Files.write(root.resolve("A.mp3"), "content-a".encodeToByteArray())
        val store = JdbcStore("jdbc:sqlite:${dir.resolve("adopt.db")}")
        store.manifestUpsert("A.mp3", 9, 1, null, 1)  // lazy scan: NULL sha
        val remote = mapOf("A.mp3" to Triple(9L, 1L, "remotesha"))
        assertEquals(listOf("A.mp3"), adoptShas(store, remote, PathFs(root), 2))
        assertEquals("remotesha", store.manifestGet("A.mp3")?.sha256)
        // Same-size local MODIFY unseen by the peer: adoption must be skipped so
        // the stale remote sha cannot freeze the rewrite into permanent divergence.
        store.manifestUpsert("A.mp3", 9, 3, null, 3)
        assertEquals(emptyList<String>(), adoptShas(store, remote, PathFs(root), 4, setOf("A.mp3")))
        assertEquals(null, store.manifestGet("A.mp3")?.sha256)
    }

    @Test fun adoptShasHonorsExistsPredicate() {
        val root = dir.resolve("root"); Files.createDirectories(root)
        Files.write(root.resolve("A.mp3"), "content-a".encodeToByteArray())
        val store = JdbcStore("jdbc:sqlite:${dir.resolve("adopt-exists.db")}")
        store.manifestUpsert("A.mp3", 9, 1, null, 1)  // lazy scan: NULL sha
        val remote = mapOf("A.mp3" to Triple(9L, 1L, "remotesha"))
        // The phone passes exists = { true } because its post-scan manifest is the
        // disk snapshot; a per-path SAF exists() would cost ~200ms each.
        assertEquals(emptyList<String>(), adoptShas(store, remote, PathFs(root), 2, exists = { false }))
        assertEquals(null, store.manifestGet("A.mp3")?.sha256)
        assertEquals(listOf("A.mp3"), adoptShas(store, remote, PathFs(root), 2, exists = { true }))
        assertEquals("remotesha", store.manifestGet("A.mp3")?.sha256)
    }
}