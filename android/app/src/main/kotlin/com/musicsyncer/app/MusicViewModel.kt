package com.musicsyncer.app

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.musicsyncer.app.fs.HybridFs
import com.musicsyncer.app.fs.MediaStoreLister
import com.musicsyncer.app.fs.SafFs
import com.musicsyncer.app.fs.volumeAndPath
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
    private var _saf: SafFs? = null
    private var _lister: MediaStoreLister? = null
    private var _controller: SyncController? = null
    val controller: SyncController? get() = _controller
    val folderName: String? get() = prefs.getString("folder_name", null)

    fun pickFolder(uri: Uri, name: String) {
        val app = getApplication<Application>()
        try { app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: SecurityException) {}
        prefs.edit().putString("folder_uri", uri.toString()).putString("folder_name", name).apply()
        buildFs(app, uri)
    }

    fun restoreFolder() {
        val uri = prefs.getString("folder_uri", null) ?: return
        val fs = try {
            buildFs(getApplication(), Uri.parse(uri))
        } catch (e: Exception) {
            // SAF grant revoked (e.g. permission removed or app data cleared):
            // SafFs throws in its constructor. Clear the stale prefs and fall
            // back to the folder picker instead of crashing on startup.
            prefs.edit().remove("folder_uri").remove("folder_name").apply()
            _controller = null
            null
        }
        _fs = fs
        if (fs != null) _controller = SyncController(getApplication(), fs, store, prefs = prefs)
    }

    /** Rebuilds the Fs (and controller) after the audio permission is granted, so the fast path activates. */
    fun refreshFs() {
        val uri = prefs.getString("folder_uri", null) ?: return
        val app = getApplication<Application>()
        try {
            val fs = buildFs(app, Uri.parse(uri))
            _fs = fs
            _controller = SyncController(app, fs, store, prefs = prefs)
        } catch (e: Exception) {
            // Grant is optional: keep the existing (slow) setup if the rebuild fails.
        }
    }

    private fun buildFs(app: Application, uri: Uri): Fs {
        val saf = SafFs(app, uri)
        _saf = saf
        val lister = fastLister(app, uri)
        _lister = lister
        Log.i(TAG, if (lister != null) "buildFs: HybridFs active (volume=${lister.volumeId}, relPath=${lister.relPath})" else "buildFs: plain SafFs (no fast path)")
        return lister?.let { HybridFs(saf, it, store) } ?: saf
    }

    /** MediaStore fast list, only when the audio permission is held (Android 13+). */
    private fun fastLister(app: Application, uri: Uri): MediaStoreLister? {
        if (Build.VERSION.SDK_INT < 33) return null
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        val treeId = try { DocumentsContract.getTreeDocumentId(uri) } catch (e: Exception) { return null }
        val (volume, relPath) = volumeAndPath(treeId) ?: return null
        return MediaStoreLister(app, volume, relPath)
    }

    /** Hands [rel] to an external audio player (Samsung Music) via ACTION_VIEW, preferring its MediaStore URI. */
    fun playInExternalPlayer(rel: String) {
        val app = getApplication<Application>()
        val uri = _lister?.entry(rel)?.first ?: _saf?.uriFor(rel) ?: return
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            app.startActivity(Intent.createChooser(intent, "Play with"))
        } catch (e: Exception) {
            Log.w(TAG, "no audio player for $rel: ${e.message}")
        }
    }

    /** Epoch seconds when the media index first saw [rel] (null if not indexed / no permission). */
    fun mediaAddedDate(rel: String): Long? = _lister?.entry(rel)?.second

    override fun onCleared() {
        discovery.close()
    }

    private companion object { const val TAG = "MusicSyncer" }
}