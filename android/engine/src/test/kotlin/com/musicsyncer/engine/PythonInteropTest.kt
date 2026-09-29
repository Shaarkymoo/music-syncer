package com.musicsyncer.engine

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * Wire-format interop against the REAL Python server (ms/server.py).
 *
 * The Kotlin engine and the Python server must speak the same JSON: snake_case
 * keys, manifest rows as [path, size, mtime_ns, sha256] arrays, conflicts as
 * [path, ts_ns, sha256] arrays, and epoch-ns Longs that round-trip exactly (no
 * Double). This test starts the actual Python SyncServer and runs a full sync
 * session against it; it is skipped when python3 / the ms package is
 * unavailable.
 */
class PythonInteropTest {
    @TempDir lateinit var dir: Path

    /** Walk up from the test working dir until we find the repo root (contains ms/). */
    private fun repoRoot(): Path {
        var p: Path? = Path.of("").toAbsolutePath()
        while (p != null && !Files.isDirectory(p.resolve("ms"))) p = p.parent
        return p!!
    }

    private fun pythonAvailable(root: Path): Boolean = try {
        val p = ProcessBuilder("python3", "-c", "import os, sys; sys.path.insert(0, os.environ['MS_REPO']); import ms")
            .apply { environment()["MS_REPO"] = root.toString() }
            .start()
        p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (e: Exception) {
        false
    }

    /** Read the port line the python server prints on stdout, with a deadline. */
    private fun readPort(proc: Process): Int? {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            val future = executor.submit<String> { proc.inputStream.bufferedReader().readLine() }
            future.get(30, TimeUnit.SECONDS)?.trim()?.toIntOrNull()
        } catch (e: Exception) {
            null
        } finally {
            executor.shutdownNow()
        }
    }

    private fun stderr(proc: Process): String =
        if (proc.isAlive) "" else try { proc.errorStream.bufferedReader().readText().take(2000) } catch (e: Exception) { "?" }

    @Test
    @Timeout(120)
    fun syncsAgainstRealPythonServer() {
        val root = repoRoot()
        assumeTrue(pythonAvailable(root), "python3 with the ms package is not available; skipping Python interop test")

        val serverRoot = dir.resolve("laptop"); Files.createDirectories(serverRoot)
        val clientRoot = dir.resolve("phone"); Files.createDirectories(clientRoot)
        val serverDb = dir.resolve("laptop.db")
        val clientDb = dir.resolve("phone.db")

        val content = "interop-content-12345".encodeToByteArray()
        Files.write(serverRoot.resolve("Song.mp3"), content)

        val proc = ProcessBuilder(
            "python3", "-c",
            """
            import os, sys
            sys.path.insert(0, os.environ['MS_REPO'])
            from pathlib import Path
            from ms.server import SyncServer
            s = SyncServer(Path(os.environ['MS_ROOT']), Path(os.environ['MS_DB']), 'laptop')
            print(s.port, flush=True)
            s.serve_forever()
            """.trimIndent(),
        ).apply {
            environment()["MS_REPO"] = root.toString()
            environment()["MS_ROOT"] = serverRoot.toString()
            environment()["MS_DB"] = serverDb.toString()
        }.start()

        try {
            val port = readPort(proc)
            assertTrue(port != null && port > 0, "python server failed to start: ${stderr(proc)}")
            val url = "http://127.0.0.1:$port"

            // First sync: the laptop file must propagate to the (empty) phone.
            val s1 = runSyncSession(url, PathFs(clientRoot), JdbcStore("jdbc:sqlite:$clientDb"), "phone")
            assertEquals(listOf("Song.mp3"), s1.fetched)
            val got = Files.readAllBytes(clientRoot.resolve("Song.mp3"))
            assertEquals(content.toList(), got.toList())
            assertEquals(Hashing.sha256(content), Hashing.sha256(got))

            // Second sync: converged state must be a no-op.
            val s2 = runSyncSession(url, PathFs(clientRoot), JdbcStore("jdbc:sqlite:$clientDb"), "phone")
            assertTrue(
                s2.fetched.isEmpty() && s2.copied.isEmpty() && s2.pushed.isEmpty() && s2.deleted.isEmpty() && s2.conflicts.isEmpty(),
                "second sync should be a no-op, got: $s2",
            )
        } finally {
            proc.destroy()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
    }
}