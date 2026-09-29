package com.musicsyncer.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.musicsyncer.app.fs.SafFs
import com.musicsyncer.app.store.MusicSyncDatabase
import com.musicsyncer.app.store.RoomStore
import com.musicsyncer.app.sync.Discovery
import com.musicsyncer.app.sync.SyncController
import com.musicsyncer.engine.Fs
import com.musicsyncer.engine.SyncStore

class MusicViewModel(app: Application) : AndroidViewModel(app) {
    val db: MusicSyncDatabase by lazy { androidx.room.Room.databaseBuilder(app, MusicSyncDatabase::class.java, "music-sync.db").build() }
    val store: SyncStore by lazy { RoomStore(db) }
    val discovery = Discovery(app)
    private val prefs = app.getSharedPreferences("music-sync", android.content.Context.MODE_PRIVATE)

    private var _fs: Fs? = null
    val fs: Fs? get() = _fs
    private var _controller: SyncController? = null
    val controller: SyncController? get() = _controller
    val folderName: String? get() = prefs.getString("folder_name", null)

    fun pickFolder(uri: Uri, name: String) {
        val app = getApplication<Application>()
        try { app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: SecurityException) {}
        prefs.edit().putString("folder_uri", uri.toString()).putString("folder_name", name).apply()
        _fs = SafFs(app, uri)
        _controller = SyncController(app, _fs!!, store)
    }

    fun restoreFolder() {
        val uri = prefs.getString("folder_uri", null) ?: return
        val fs = try {
            SafFs(getApplication(), Uri.parse(uri))
        } catch (e: Exception) {
            // SAF grant revoked (e.g. permission removed or app data cleared):
            // SafFs throws in its constructor. Clear the stale prefs and fall
            // back to the folder picker instead of crashing on startup.
            prefs.edit().remove("folder_uri").remove("folder_name").apply()
            _controller = null
            null
        }
        _fs = fs
        if (fs != null) _controller = SyncController(getApplication(), fs, store)
    }

    override fun onCleared() {
        discovery.close()
    }
}