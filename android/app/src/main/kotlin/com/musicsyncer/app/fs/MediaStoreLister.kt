package com.musicsyncer.app.fs

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
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
 * Maps a MediaStore audio row to an engine FsEntry whose rel is relative to
 * the picked folder ([relPath]) — e.g. folder "my songs", row path
 * "my songs/engsongs/A.mp3" -> rel "engsongs/A.mp3". Rows outside the folder,
 * nameless rows, or unknown-size rows map to null.
 */
internal fun mediaStoreRowToEntry(
    relPath: String,
    relativePath: String?,
    displayName: String?,
    size: Long,
    mtimeSeconds: Long,
): FsEntry? {
    if (displayName == null || size < 0) return null
    val full = "${relativePath ?: ""}$displayName" // RELATIVE_PATH ends with '/'
    if (relPath.isNotEmpty() && !full.startsWith("$relPath/")) return null
    return FsEntry(full.removePrefix("$relPath/"), size, mtimeSeconds * 1_000_000_000)
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
    val volumeId: String,
    val relPath: String,
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
        val t0 = System.nanoTime()
        context.contentResolver.query(
            MediaStore.Audio.Media.getContentUri(volumeId),
            projection, selection, args, null,
        )?.use { c ->
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mtimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                mediaStoreRowToEntry(
                    relPath,
                    c.getString(pathCol),
                    c.getString(nameCol),
                    c.getLong(sizeCol),
                    c.getLong(mtimeCol),
                )?.let { out.add(it) }
            }
        }
        Log.i(TAG, "list(volume=$volumeId, relPath=$relPath) -> ${out.size} rows in ${(System.nanoTime() - t0) / 1_000_000}ms; sample=${out.take(3).map { it.rel }}")
        return out.distinctBy { it.rel }
    }

    /** MediaStore content URI + "added to library" date (epoch seconds) for [rel], or null if not indexed. */
    fun entry(rel: String): Pair<Uri, Long>? {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} = ?"
        val args = arrayOf("$relPath/${rel.substringBeforeLast('/', "")}/%", rel.substringAfterLast('/'))
        val uri = MediaStore.Audio.Media.getContentUri(volumeId)
        context.contentResolver.query(uri, projection, selection, args, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            while (c.moveToNext()) {
                if (c.getString(pathCol) + c.getString(nameCol) == "$relPath/$rel") {
                    return ContentUris.withAppendedId(uri, c.getLong(idCol)) to c.getLong(addedCol)
                }
            }
        }
        return null
    }

    private companion object { const val TAG = "MusicSyncer" }
}