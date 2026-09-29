package com.musicsyncer.app.tag

import android.content.Context
import com.musicsyncer.engine.Fs
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyUSLT
import org.jaudiotagger.tag.id3.ID3v24Frame
import java.io.File

data class SongTags(
    val title: String?, val artist: String?, val album: String?,
    val albumArtist: String?, val genre: String?, val track: String?, val lyrics: String?,
)

object TagEditor {
    fun read(fs: Fs, rel: String): SongTags {
        // jaudiotagger 3.0.1 reads only from File (no InputStream overload), so
        // roundtrip through a temp file, mirroring the write flow.
        val cache = File.createTempFile("tag", ".mp3")
        try {
            cache.writeBytes(fs.read(rel))
            val tag = AudioFileIO.read(cache).tag ?: return SongTags(null, null, null, null, null, null, null)
            return SongTags(
                tag.getFirst(FieldKey.TITLE), tag.getFirst(FieldKey.ARTIST), tag.getFirst(FieldKey.ALBUM),
                tag.getFirst(FieldKey.ALBUM_ARTIST), tag.getFirst(FieldKey.GENRE), tag.getFirst(FieldKey.TRACK),
                readLyrics(tag),
            )
        } finally {
            cache.delete()
        }
    }

    fun write(context: Context, fs: Fs, rel: String, tags: SongTags) {
        val cache = File(context.cacheDir, "tag-${System.nanoTime()}.mp3")
        cache.writeBytes(fs.read(rel))
        try {
            val audioFile = AudioFileIO.read(cache)
            val tag = (audioFile.tag as? AbstractID3v2Tag) ?: ID3v24Tag().also { audioFile.tag = it }
            tag.setField(FieldKey.TITLE, tags.title ?: "")
            tag.setField(FieldKey.ARTIST, tags.artist ?: "")
            tag.setField(FieldKey.ALBUM, tags.album ?: "")
            tag.setField(FieldKey.ALBUM_ARTIST, tags.albumArtist ?: "")
            tag.setField(FieldKey.GENRE, tags.genre ?: "")
            tag.setField(FieldKey.TRACK, tags.track ?: "")
            writeLyrics(tag, tags.lyrics ?: "")
            AudioFileIO.write(audioFile)
            fs.write(rel, cache.readBytes())
        } finally {
            cache.delete()
        }
    }

    private fun readLyrics(tag: org.jaudiotagger.tag.Tag): String? =
        (tag as? AbstractID3v2Tag)?.let { t ->
            t.getFirst("USLT").ifEmpty { null }
        }

    private fun writeLyrics(tag: AbstractID3v2Tag, lyrics: String) {
        tag.deleteField("USLT")
        if (lyrics.isNotBlank()) {
            val frame = ID3v24Frame("USLT")
            (frame.body as FrameBodyUSLT).apply {
                setLanguage("eng")
                setDescription("")
                setLyric(lyrics)
            }
            tag.setFrame(frame)
        }
    }
}