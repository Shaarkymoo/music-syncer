package com.musicsyncer.engine

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class FsEntry(val rel: String, val size: Long, val mtimeNs: Long)

interface Fs {
    fun list(): List<FsEntry>
    fun read(rel: String): ByteArray
    fun openRead(rel: String): InputStream
    fun write(rel: String, data: ByteArray)
    fun mkdirs(relDir: String)
    fun delete(rel: String)
    fun rename(rel: String, newRel: String)
    fun exists(rel: String): Boolean
}

class PathFs(private val root: Path) : Fs {
    private fun resolve(rel: String): Path {
        val target = root.resolve(rel).normalize()
        val r = root.normalize()
        if (!target.startsWith(r)) throw IllegalArgumentException("path escapes root: $rel")
        return target
    }

    override fun list(): List<FsEntry> {
        val out = mutableListOf<FsEntry>()
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { p ->
                val rel = root.relativize(p).toString().replace('\\', '/')
                val st = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes::class.java)
                out.add(FsEntry(rel, st.size(), st.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS)))
            }
        }
        return out
    }

    override fun read(rel: String): ByteArray = Files.readAllBytes(resolve(rel))
    override fun openRead(rel: String): InputStream = Files.newInputStream(resolve(rel))
    override fun write(rel: String, data: ByteArray) {
        val p = resolve(rel)
        Files.createDirectories(p.parent)
        Files.write(p, data)
    }
    override fun mkdirs(relDir: String) { Files.createDirectories(resolve(relDir)) }
    override fun delete(rel: String) { Files.deleteIfExists(resolve(rel)) }
    override fun rename(rel: String, newRel: String) {
        val src = resolve(rel); val dst = resolve(newRel)
        Files.createDirectories(dst.parent)
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    override fun exists(rel: String): Boolean = Files.exists(resolve(rel))
}