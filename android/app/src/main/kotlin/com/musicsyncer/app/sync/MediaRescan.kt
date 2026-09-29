package com.musicsyncer.app.sync

import android.content.Context
import android.media.MediaScannerConnection

object MediaRescan {
    fun rescan(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
    }
}