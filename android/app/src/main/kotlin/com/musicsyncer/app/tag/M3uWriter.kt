package com.musicsyncer.app.tag

/** Minimal .m3u playlist content: EXTM3U header + one file name per line. */
fun m3uContent(playlistName: String, songRels: List<String>): String =
    "#EXTM3U\n" + songRels.joinToString("\n") { it.substringAfterLast('/') } + "\n"