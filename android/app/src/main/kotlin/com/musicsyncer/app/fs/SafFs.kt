package com.musicsyncer.app.fs

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.musicsyncer.engine.Fs
import com.musicsyncer.engine.FsDirEntry
import com.musicsyncer.engine.FsEntry
import java.io.IOException
import java.io.InputStream

class SafFs(private val context: Context, treeUri: Uri) : Fs {
    private val resolver = context.contentResolver
    private val root: DocumentFile = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalArgumentException("not a SAF tree: $treeUri")

    private fun doc(rel: String): DocumentFile? {
        var current = root
        for (part in rel.split('/')) {
            if (part.isEmpty()) continue
            current = current.findFile(part) ?: return null
        }
        return current
    }

    override fun list(): List<FsEntry> {
        val out = mutableListOf<FsEntry>()
        fun walk(dir: DocumentFile, prefix: String) {
            for (child in dir.listFiles()) {
                val rel = if (prefix.isEmpty()) child.name ?: continue else "$prefix/${child.name}"
                if (child.isFile) {
                    out.add(FsEntry(rel, child.length(), child.lastModified() * 1_000_000))
                } else if (child.isDirectory) {
                    walk(child, rel)
                }
            }
        }
        walk(root, "")
        return out
    }

    override fun listDir(rel: String): List<FsDirEntry> {
        val dir = doc(rel) ?: throw IOException("not found: $rel")
        val out = mutableListOf<FsDirEntry>()
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val childRel = if (rel.isEmpty()) name else "$rel/$name"
            if (child.isDirectory) {
                out.add(FsDirEntry(childRel, true, 0L, 0L))
            } else if (child.isFile) {
                out.add(FsDirEntry(childRel, false, child.length(), child.lastModified() * 1_000_000))
            }
        }
        return out
    }

    override fun stat(rel: String): FsEntry {
        val d = doc(rel) ?: throw IOException("not found: $rel")
        return FsEntry(rel, d.length(), d.lastModified() * 1_000_000)
    }

    override fun read(rel: String): ByteArray = resolver.openInputStream(uri(rel))!!.use { it.readBytes() }
    override fun openRead(rel: String): InputStream = resolver.openInputStream(uri(rel))!!

    override fun write(rel: String, data: ByteArray) {
        mkdirs(rel.substringBeforeLast('/', ""))
        val parentRel = rel.substringBeforeLast('/', "")
        val parent = if (parentRel.isEmpty()) root else doc(parentRel) ?: throw IOException("parent not found: $parentRel")
        val target = doc(rel) ?: parent.createFile("application/octet-stream", rel.substringAfterLast('/'))
            ?: throw IOException("create failed: $rel")
        resolver.openOutputStream(target.uri, "wt")!!.use { it.write(data) }
    }

    override fun mkdirs(relDir: String) {
        if (relDir.isEmpty()) return
        var current = root
        for (part in relDir.split('/')) {
            if (part.isEmpty()) continue
            current = current.findFile(part) ?: current.createDirectory(part) ?: throw IOException("mkdir failed: $part")
        }
    }

    override fun delete(rel: String) {
        doc(rel)?.delete()
    }

    override fun rename(rel: String, newRel: String) {
        val target = doc(rel) ?: throw IOException("not found: $rel")
        val targetParent = rel.substringBeforeLast('/', "")
        val newParent = newRel.substringBeforeLast('/', "")
        val newName = newRel.substringAfterLast('/')
        require(targetParent == newParent) { "SAF rename must stay in the same directory: $rel -> $newRel" }
        if (!target.renameTo(newName)) {
            // Fallback: copy + delete.
            val bytes = read(rel)
            write(newRel, bytes)
            delete(rel)
        }
    }

    override fun exists(rel: String): Boolean = doc(rel) != null

    /** SAF content URI for [rel] (null if not found) — used to hand the file to an external player. */
    fun uriFor(rel: String): Uri? = doc(rel)?.uri

    private fun uri(rel: String): Uri {
        val d = doc(rel) ?: throw IOException("not found: $rel")
        return d.uri
    }
}