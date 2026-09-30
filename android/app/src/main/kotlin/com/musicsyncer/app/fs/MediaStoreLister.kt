package com.musicsyncer.app.fs

import android.content.Context
import android.provider.MediaStore
import com.musicsyncer.engine.FsEntry

/**
 * Maps a SAF tree document id ("volume:relative/path") to a MediaStore volume
 * name + folder path. SAF calls the internal volume "primary"; MediaStore calls
 * it "external_primary". SD-card volumes keep their id (e.g. "3737-6133").
 */
internal fun volumeAndPath(treeDocumentId: String): Pair<String, String>? {
    val colon = treeDocumentId.indexOf(':')
    if (colon <= 0) return null
    val volume = treeDocumentId.substring(0, colon)
    val path = treeDocumentId.substring(colon + 1)
    return (if (volume == "primary") "external_primary" else volume) to path
}

/**
 * Fast audio enumeration from the MediaStore index (needs READ_MEDIA_AUDIO,
 * Android 13+). Returns every indexed audio file under [relPath] on the
 * [volumeId] volume as engine FsEntries (mtime at second precision). Files the
 * index has not caught up on are NOT returned here — HybridFs reconciles those
 * against SAF so the engine never sees phantom deletes.
 */
class MediaStoreLister(
    private val context: Context,
    private val volumeId: String,
    private val relPath: String,
) {
    fun list(): List<FsEntry> {
        val projection = arrayOf(
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.RELATIVE_PATH,
        )
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("$relPath/%")
        val out = mutableListOf<FsEntry>()
        context.contentResolver.query(
            MediaStore.Audio.Media.getContentUri(volumeId),
            projection, selection, args, null,
        )?.use { c ->
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mtimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: continue
                val size = c.getLong(sizeCol)
                if (size < 0) continue
                val relativePath = c.getString(pathCol) ?: ""
                val rel = "$relativePath$name" // RELATIVE_PATH ends with '/'
                if (relPath.isNotEmpty() && !rel.startsWith("$relPath/")) continue
                out.add(FsEntry(rel, size, c.getLong(mtimeCol) * 1_000_000_000))
            }
        }
        return out.distinctBy { it.rel }
    }
}