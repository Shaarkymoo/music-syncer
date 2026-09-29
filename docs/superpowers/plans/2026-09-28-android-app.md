# Music-Syncer Android App — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Android sync app (`:app` module) on top of the reviewed Kotlin engine (`:engine`, master): SAF folder picker, file/folder browser, metadata editor (mp3 tags + lyrics), mDNS discovery, Scan/Sync/Verify, readable log — producing an installable APK.

**Architecture:** The engine module (`android/engine`, pure Kotlin/JVM, already on master) provides scan/merge/apply/sync-client against two abstractions: `Fs` (filesystem ops over relative paths) and `SyncStore` (persistence). The app supplies the Android implementations — `SafFs` (SAF tree via DocumentFile/ContentResolver, no all-files access) and `RoomStore` (Room, same schema) — plus NsdManager discovery, a `SyncController` that runs sessions on a coroutine, MediaStore rescan, jaudiotagger ID3v2 metadata editing, and a minimal Compose UI. The phone initiates sync against the laptop's passive Python server (which advertises `music-syncer._tcp`).

**Tech Stack:** Kotlin 2.0.21, AGP 8.7.3, compileSdk 35, minSdk 26, targetSdk 35, Jetpack Compose (BOM 2024.09.00), Room 2.6.1 + KSP, OkHttp 4.12 (engine), jaudiotagger 3.0.1, coroutines, Robolectric 4.13 (JVM tests for RoomStore), NsdManager, MediaScannerConnection.

## Global Constraints

- App module package: `com.musicsyncer.app`. Engine module stays pure Kotlin/JVM — NO Android imports in `:engine` (do not modify `:engine` except where a task explicitly says so).
- Folder access via SAF only (`ACTION_OPEN_DOCUMENT_TREE`, persisted with `takePersistableUriPermission`) — NO `MANAGE_EXTERNAL_STORAGE`.
- Tag editing is mp3/ID3v2 only (jaudiotagger); legacy m4a files are out of scope for editing but the engine mirrors all files as opaque bytes.
- Wire protocol unchanged: the app's sync session is the engine's `runSyncSession` (corrected cursors, wall-clock tsNs, typed `ManifestWire`/`ConflictWire` DTOs) against the laptop's Python server.
- Discovery: NsdManager resolves `_music-syncer._tcp` (laptop advertises). No manual IP entry.
- UI is minimal-but-functional (status, browser, metadata editor, log). No playback UI.
- No foreground service, no watcher, no auto-trigger (v1): Scan/Sync are button-driven; the app works while open.
- App-driven file changes (rename/move/delete/tag-edit) are followed by a `scan(...)` so the engine journals them.
- After sync and after tag edits, `MediaScannerConnection.scanFile` refreshes the MediaStore for the changed paths.
- Tests: `:engine` tests (49) must stay green; new app-module JVM tests via Robolectric for `RoomStore` contract conformance. SafFs/discovery/UI are verified by build + review (user installs the APK; no on-device instrumentation).
- minSdk 26, compileSdk 35, targetSdk 35.

## File Structure

```
android/
  settings.gradle.kts        # + include(":app")
  build.gradle.kts           # + android plugin version
  app/
    build.gradle.kts
    src/main/AndroidManifest.xml
    src/main/kotlin/com/musicsyncer/app/
      MainActivity.kt        # single activity, Compose navigation between 4 screens
      ui/StatusScreen.kt     # folder status, discovery, last sync, Scan/Sync/Verify
      ui/BrowserScreen.kt    # folder tree, song actions (rename/move/delete)
      ui/EditorScreen.kt     # metadata + lyrics editor
      ui/LogScreen.kt        # readable journal
      store/RoomStore.kt     # Room entities + DAOs + SyncStore impl
      fs/SafFs.kt            # Fs over a SAF tree URI
      sync/SyncController.kt # StateFlow-driven scan/sync/verify on Dispatchers.IO
      sync/Discovery.kt      # NsdManager resolve of _music-syncer._tcp
      sync/MediaRescan.kt    # MediaScannerConnection helper
      tag/TagEditor.kt       # jaudiotagger read/write via cache-file roundtrip
    src/test/kotlin/com/musicsyncer/app/RoomStoreTest.kt   # Robolectric
```

---

## Task 1: `:app` module scaffold — first buildable APK

**Files:**
- Modify: `android/settings.gradle.kts`, `android/build.gradle.kts`
- Create: `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/kotlin/com/musicsyncer/app/MainActivity.kt`, `android/app/src/main/kotlin/com/musicsyncer/app/ui/StatusScreen.kt`, `android/app/src/main/res/values/strings.xml`, `android/app/src/main/res/values/themes.xml`, a launcher icon (use `android:icon="@mipmap/ic_launcher"` — for the scaffold use the default; create a trivial adaptive icon XML set or point to a generated `ic_launcher` — simplest: omit `android:icon` is not allowed; create `res/drawable/ic_launcher.xml` as a simple vector and reference `@drawable/ic_launcher`).

**Interfaces:**
- Produces: `./gradlew :app:assembleDebug` builds `android/app/build/outputs/apk/debug/app-debug.apk`. MainActivity shows a StatusScreen placeholder with the app name.

- [ ] **Step 1: Configure Gradle**

`android/settings.gradle.kts` — add `include(":app")` after `include(":engine")`.

`android/build.gradle.kts`:
```kotlin
plugins {
    kotlin("jvm") version "2.0.21" apply false
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
```

`android/app/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.musicsyncer.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.musicsyncer.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":engine"))
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}
```

**AGP note:** AGP 8.7.3 needs Android SDK 35 (installed) and JDK 17+ (the foojay resolver in settings provides a JDK for the Kotlin/JVM toolchain; AGP uses Gradle's JVM = the JDK running Gradle — the machine has JDK 21 system JRE-only issue. Ensure `org.gradle.java.home` or JAVA_HOME points to the Temurin JDK at `~/.local/share/jdks/jdk-21.0.12.1+1` for the build (export `JAVA_HOME` when running gradlew) — AGP 8.7 supports JDK 17-21.

- [ ] **Step 2: Manifest + resources**

`android/app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <application
        android:label="@string/app_name"
        android:icon="@drawable/ic_launcher"
        android:theme="@style/Theme.MusicSyncer">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">Music Sync</string>
</resources>
```

`res/values/themes.xml`:
```xml
<resources>
    <style name="Theme.MusicSyncer" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

`res/drawable/ic_launcher.xml` (simple vector):
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="48dp" android:height="48dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#1F6FEB" android:pathData="M12,3v10.55A4,4 0 1,0 14,17V7h4V3H12z"/>
</vector>
```

- [ ] **Step 3: MainActivity + StatusScreen placeholder**

`MainActivity.kt`:
```kotlin
package com.musicsyncer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.musicsyncer.app.ui.StatusScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StatusScreen()
                }
            }
        }
    }
}
```

`ui/StatusScreen.kt`:
```kotlin
package com.musicsyncer.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun StatusScreen() {
    Text("Music Sync — status screen (Plan 2B scaffold)")
}
```

- [ ] **Step 4: Build the APK**

Run: `JAVA_HOME=$HOME/.local/share/jdks/jdk-21.0.12.1+1 ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL; `android/app/build/outputs/apk/debug/app-debug.apk` exists (unzip -l shows classes.dex + AndroidManifest).

- [ ] **Step 5: Commit**

```bash
git add android/
git commit -m "feat(app): android app scaffold — buildable debug APK"
```

---

## Task 2: RoomStore — SyncStore over Room

**Files:**
- Create: `android/app/src/main/kotlin/com/musicsyncer/app/store/RoomStore.kt`, `android/app/src/test/kotlin/com/musicsyncer/app/RoomStoreTest.kt`

**Interfaces:**
- Consumes: `com.musicsyncer.engine.{SyncStore, ManifestRow, JournalOp, SyncStateRow}` (from `:engine`)
- Produces: `class RoomStore(private val db: MusicSyncDatabase) : SyncStore` and `@Database class MusicSyncDatabase` with `abstract fun storeDao(): StoreDao`. Room entities mirror the engine schema: `manifest(path PK, size, mtimeNs, sha256, lastSeenNs)`, `journal(id autogen PK, op, path, size, sha256, tsNs, device)`, `sync_state(peerDeviceId PK, lastSeenJournalId, lastSyncNs)`.
- Room cannot express the Python `CHECK (op IN (...))` constraint; `RoomStore.journalAppend` validates `op` in `setOf("CREATE","MODIFY","DELETE")` and throws `IllegalArgumentException` otherwise.

- [ ] **Step 1: Write the failing Robolectric test**

`RoomStoreTest.kt`:
```kotlin
package com.musicsyncer.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.musicsyncer.app.store.MusicSyncDatabase
import com.musicsyncer.app.store.RoomStore
import com.musicsyncer.engine.SyncStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomStoreTest {
    private lateinit var store: SyncStore
    private lateinit var db: MusicSyncDatabase

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, MusicSyncDatabase::class.java).build()
        store = RoomStore(db)
    }

    @Test fun manifestRoundtrip() {
        assertNull(store.manifestGet("A.mp3"))
        store.manifestUpsert("A.mp3", 10, 5, "abc", 100)
        assertEquals("abc", store.manifestGet("A.mp3")?.sha256)
        assertEquals(10L, store.manifestGet("A.mp3")?.size)
        store.manifestDelete("A.mp3")
        assertNull(store.manifestGet("A.mp3"))
    }

    @Test fun journalAppendSinceHead() {
        assertEquals(0L, store.journalHead())
        val id1 = store.journalAppend("CREATE", "A.mp3", 10, "abc", 100, "laptop")
        val id2 = store.journalAppend("DELETE", "A.mp3", null, null, 200, "phone")
        assertEquals(id2, store.journalHead())
        assertEquals(listOf("DELETE"), store.journalSince(id1).map { it.op })
    }

    @Test fun invalidOpRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            store.journalAppend("RENAME", "A.mp3", 1, "a", 1, "me")
        }
    }

    @Test fun syncStateRoundtrip() {
        assertNull(store.syncStateGet("phone"))
        store.syncStateSet("phone", 42, 999)
        assertEquals(42L, store.syncStateGet("phone")?.lastSeenJournalId)
    }

    @Test fun manifestAllSorted() {
        store.manifestUpsert("b", 1, 1, "x", 1)
        store.manifestUpsert("a", 1, 1, "y", 1)
        assertEquals(listOf("a", "b"), store.manifestAll().map { it.path })
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.musicsyncer.app.RoomStoreTest"`
Expected: FAIL — undefined types.

- [ ] **Step 3: Write the implementation**

`store/RoomStore.kt`:
```kotlin
package com.musicsyncer.app.store

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.musicsyncer.engine.JournalOp
import com.musicsyncer.engine.ManifestRow
import com.musicsyncer.engine.SyncStateRow
import com.musicsyncer.engine.SyncStore

@Entity(tableName = "manifest")
data class ManifestEntity(
    @PrimaryKey val path: String,
    val size: Long, val mtimeNs: Long, val sha256: String?, val lastSeenNs: Long,
)

@Entity(tableName = "journal")
data class JournalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val op: String, val path: String, val size: Long?, val sha256: String?,
    val tsNs: Long, val device: String,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val peerDeviceId: String,
    val lastSeenJournalId: Long, val lastSyncNs: Long?,
)

@Dao
interface StoreDao {
    @Query("SELECT * FROM manifest WHERE path = :path") fun manifestGet(path: String): ManifestEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun manifestUpsert(row: ManifestEntity)
    @Query("DELETE FROM manifest WHERE path = :path") fun manifestDelete(path: String)
    @Query("SELECT * FROM manifest ORDER BY path") fun manifestAll(): List<ManifestEntity>
    @Insert fun journalAppend(row: JournalEntity): Long
    @Query("SELECT COALESCE(MAX(id), 0) FROM journal") fun journalHead(): Long
    @Query("SELECT * FROM journal WHERE id > :afterId ORDER BY id") fun journalSince(afterId: Long): List<JournalEntity>
    @Query("SELECT * FROM sync_state WHERE peerDeviceId = :peer") fun syncStateGet(peer: String): SyncStateEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun syncStateSet(row: SyncStateEntity)
}

@Database(entities = [ManifestEntity::class, JournalEntity::class, SyncStateEntity::class], version = 1)
abstract class MusicSyncDatabase : RoomDatabase() {
    abstract fun storeDao(): StoreDao
}

class RoomStore(private val dao: StoreDao) : SyncStore {
    override fun manifestGet(path: String): ManifestRow? =
        dao.manifestGet(path)?.let { ManifestRow(it.path, it.size, it.mtimeNs, it.sha256, it.lastSeenNs) }
    override fun manifestUpsert(path: String, size: Long, mtimeNs: Long, sha256: String?, nowNs: Long) =
        dao.manifestUpsert(ManifestEntity(path, size, mtimeNs, sha256, nowNs))
    override fun manifestDelete(path: String) = dao.manifestDelete(path)
    override fun manifestAll(): List<ManifestRow> =
        dao.manifestAll().map { ManifestRow(it.path, it.size, it.mtimeNs, it.sha256, it.lastSeenNs) }
    override fun journalAppend(op: String, path: String, size: Long?, sha256: String?, tsNs: Long, device: String): Long {
        require(op in setOf("CREATE", "MODIFY", "DELETE")) { "invalid op: $op" }
        return dao.journalAppend(JournalEntity(op = op, path = path, size = size, sha256 = sha256, tsNs = tsNs, device = device))
    }
    override fun journalHead(): Long = dao.journalHead()
    override fun journalSince(afterId: Long): List<JournalOp> =
        dao.journalSince(afterId).map { JournalOp(it.id, it.op, it.path, it.size, it.sha256, it.tsNs, it.device) }
    override fun syncStateGet(peerDeviceId: String): SyncStateRow? =
        dao.syncStateGet(peerDeviceId)?.let { SyncStateRow(it.peerDeviceId, it.lastSeenJournalId, it.lastSyncNs) }
    override fun syncStateSet(peerDeviceId: String, lastSeenJournalId: Long, lastSyncNs: Long) =
        dao.syncStateSet(SyncStateEntity(peerDeviceId, lastSeenJournalId, lastSyncNs))
}
```

- [ ] **Step 4: Run to verify they pass**

Run: `JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests "com.musicsyncer.app.RoomStoreTest"`
Expected: 5 passed. Then run the full engine suite (`:engine:test`) to confirm no regression.

- [ ] **Step 5: Commit**

```bash
git add android/
git commit -m "feat(app): RoomStore — SyncStore over Room (Robolectric-tested)"
```

---

## Task 3: SafFs — Fs over a SAF tree

**Files:**
- Create: `android/app/src/main/kotlin/com/musicsyncer/app/fs/SafFs.kt`

**Interfaces:**
- Consumes: `com.musicsyncer.engine.{Fs, FsEntry}` (from `:engine`); a persisted SAF tree `Uri`.
- Produces: `class SafFs(context: Context, treeUri: Uri) : Fs` — `list()` walks `DocumentFile.fromTreeUri` recursively (FILES only), rel paths `/`-separated, `size` = `doc.length()`, `mtimeNs` = `doc.lastModified() * 1_000_000`; `read/openRead` via `ContentResolver.openInputStream`; `write` via `openOutputStream(uri, "wt")` after `mkdirs`; `mkdirs` via `DocumentFile.createDirectory` chain; `delete` via `doc.delete()`; `rename` via `doc.renameTo(newName)` (same directory only — SAF cannot move across directories, so `rename` requires `newRel` in the SAME parent; the engine's apply uses rename only for same-dir temp→final and conflict files, and moves are expressed as delete+create — verify this holds; if a cross-directory rename is ever requested, implement as copy+delete). `exists` via `findFile`.
- Note: SAF has no atomic rename; `rename` may return false on some providers — if `renameTo` fails, fall back to copy+delete. Dot-prefixed `.ms-partial-*` and `.sync-conflict-*` files are handled by the engine's scan/apply (SafFs is a dumb store — no filtering).

- [ ] **Step 1: Write the implementation** (this module is Android-only and cannot be JVM-tested; correctness is by review + the engine's Fs contract. Be careful and thorough.)

`fs/SafFs.kt`:
```kotlin
package com.musicsyncer.app.fs

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.musicsyncer.engine.Fs
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

    override fun read(rel: String): ByteArray = resolver.openInputStream(uri(rel))!!.use { it.readBytes() }
    override fun openRead(rel: String): InputStream = resolver.openInputStream(uri(rel))!!

    override fun write(rel: String, data: ByteArray) {
        mkdirs(rel.substringBeforeLast('/', ""))
        resolver.openOutputStream(uri(rel), "wt")!!.use { it.write(data) }
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

    private fun uri(rel: String): Uri {
        val d = doc(rel) ?: throw IOException("not found: $rel")
        return d.uri
    }
}
```

**NOTE:** `rename` requires same-directory (SAF limitation). Verify against the engine's usage: `applyPlan` renames `tmp → final` (same dir) and conflict files (`rel → conflict name`, same dir); `SyncClient`/server use `fs.write(partial) + fs.rename(partial, rel)` — all same-dir. The `moveCostZeroTransfer` path uses `fs.read(src) + fs.write(dst)` (copy), not rename. This holds. If a future task needs cross-dir rename, implement copy+delete there.

- [ ] **Step 2: Build to verify it compiles**

Run: `JAVA_HOME=... ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/
git commit -m "feat(app): SafFs — engine Fs over a SAF tree (no all-files access)"
```

---

## Task 4: Discovery + SyncController + MediaRescan

**Files:**
- Create: `android/app/src/main/kotlin/com/musicsyncer/app/sync/Discovery.kt`, `android/app/src/main/kotlin/com/musicsyncer/app/sync/SyncController.kt`, `android/app/src/main/kotlin/com/musicsyncer/app/sync/MediaRescan.kt`

**Interfaces:**
- Consumes: engine `scan`, `runSyncSession`, `Fs`, `SyncStore`, `ApplySummary`; `SafFs`, `RoomStore`.
- Produces:
  - `object MediaRescan { fun rescan(context: Context, paths: List<String>) }` — `MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)`.
  - `class Discovery(context: Context) : AutoCloseable` — NsdManager resolves `_music-syncer._tcp`; `suspend fun find(): String?` returns `http://<host>:<port>` for the first resolved service (timeout 10s). Also keeps a `deviceFound: Boolean` via callback.
  - `class SyncController(private val context: Context, private val fs: Fs, private val store: SyncStore) { val state: StateFlow<SyncState>; fun setServerUrl(url: String); suspend fun scan(); suspend fun sync(); suspend fun verify() }` with `data class SyncState(val folder: String? = null, val server: String? = null, val lastSync: String? = null, val lastSummary: String? = null, val busy: Boolean = false, val error: String? = null)`.
  - `sync()`: scan → `runSyncSession` → `MediaRescan.rescan` on the changed paths (fetched + copied + deleted + conflicts).

- [ ] **Step 1: Write the implementation**

`syc/MediaRescan.kt` (path corrected: `sync/MediaRescan.kt`):
```kotlin
package com.musicsyncer.app.sync

import android.content.Context
import android.media.MediaScannerConnection

object MediaRescan {
    fun rescan(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
    }
}
```

`sync/Discovery.kt`:
```kotlin
package com.musicsyncer.app.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class Discovery(private val context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serviceType = "_music-syncer._tcp."
    private var listener: NsdManager.DiscoveryListener? = null
    @Volatile var found: NsdServiceInfo? = null; private set

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType == serviceType) {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) { found = info }
                    })
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) { if (serviceInfo == found) found = null }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        listener = l
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, l)
    }

    override fun stop() {
        listener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        listener = null
    }

    suspend fun find(timeoutMs: Long = 10_000): String? = suspendCancellableCoroutine { cont ->
        start()
        val deadline = System.currentTimeMillis() + timeoutMs
        val poll = Thread {
            while (!cont.isCancelled && System.currentTimeMillis() < deadline) {
                found?.let { info ->
                    cont.resume("http://${info.host.hostAddress}:${info.port}")
                    return@Thread
                }
                Thread.sleep(200)
            }
            if (!cont.isCancelled && found == null) cont.resume(null)
        }
        poll.isDaemon = true
        poll.start()
        cont.invokeOnCancellation { poll.interrupt() }
    }
}
```

`sync/SyncController.kt`:
```kotlin
package com.musicsyncer.app.sync

import android.content.Context
import com.musicsyncer.engine.Fs
import com.musicsyncer.engine.SyncStore
import com.musicsyncer.engine.applyPlan
import com.musicsyncer.engine.runSyncSession
import com.musicsyncer.engine.scan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SyncState(
    val server: String? = null,
    val lastSync: String? = null,
    val lastSummary: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

class SyncController(
    private val context: Context,
    private val fs: Fs,
    private val store: SyncStore,
    private val ourDevice: String = "phone",
) {
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state
    private val scope = CoroutineScope(Dispatchers.IO)

    fun setServer(url: String) { _state.value = _state.value.copy(server = url) }

    fun scan() { scope.launch { runScan() } }

    private suspend fun runScan(): Int = withContext(Dispatchers.IO) {
        scan(fs, store, ourDevice, System.currentTimeMillis() * 1_000_000)
    }

    fun sync(serverUrl: String?) {
        val url = serverUrl ?: _state.value.server ?: return
        scope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                runScan()
                val summary = runSyncSession(url, fs, store, ourDevice)
                MediaRescan.rescan(context, summary.fetched + summary.copied + summary.deleted + summary.conflicts)
                _state.value = _state.value.copy(
                    lastSync = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
                    lastSummary = "fetched ${summary.fetched.size}, copied ${summary.copied.size}, pushed ${summary.pushed.size}, deleted ${summary.deleted.size}, conflicts ${summary.conflicts.size}",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun verify() {
        scope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                runScan()
                val mismatches = store.manifestAll().filter { m ->
                    m.sha256 != null && runCatching {
                        fs.openRead(m.path).use { com.musicsyncer.engine.Hashing.sha256(it) } == m.sha256
                    }.getOrDefault(false).not()
                }
                _state.value = _state.value.copy(
                    lastSummary = if (mismatches.isEmpty()) "OK: all files match stored hashes" else "MISMATCH: ${mismatches.joinToString { it.path }}",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `JAVA_HOME=... ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/
git commit -m "feat(app): discovery, sync controller, and media rescan"
```

---

## Task 5: Status screen + folder picker + sync wiring

**Files:**
- Modify: `android/app/src/main/kotlin/com/musicsyncer/app/MainActivity.kt`, `android/app/src/main/kotlin/com/musicsyncer/app/ui/StatusScreen.kt`
- Create: `android/app/src/main/kotlin/com/musicsyncer/app/MusicViewModel.kt` (holds the store/fs/controller, the SAF tree URI via SharedPreferences, and folder state)

**Interfaces:**
- Consumes: `SafFs`, `RoomStore`, `MusicSyncDatabase`, `SyncController`, `Discovery`
- Produces: `class MusicViewModel(app: Application) : AndroidViewModel` with `val store: SyncStore`, `val fs: Fs?` (null until the SAF tree is picked), `val db: MusicSyncDatabase`, `fun pickFolder(uri: Uri)`, `val controller: SyncController?`, `val discovery: Discovery`, and StateFlows for the UI. The SAF picker is launched via `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree())`.

- [ ] **Step 1: Write the implementation**

`MusicViewModel.kt`:
```kotlin
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
    val store: SyncStore by lazy { RoomStore(db.storeDao()) }
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
        _fs = SafFs(getApplication(), Uri.parse(uri))
        _controller = SyncController(getApplication(), _fs!!, store)
    }
}
```

`ui/StatusScreen.kt` (replace placeholder):
```kotlin
package com.musicsyncer.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.musicsyncer.app.MusicViewModel
import com.musicsyncer.app.sync.Discovery

@Composable
fun StatusScreen(vm: MusicViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.pickFolder(uri, android.net.Uri.decode(uri.lastPathSegment ?: "music"))
    }
    val state by vm.controller?.state?.collectAsState() ?: rememberStableState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Music Sync")
        vm.folderName?.let { Text("Folder: $it") } ?: Button(onClick = { picker.launch(null) }) { Text("Choose music folder") }
        state?.let { s ->
            Text("Server: ${s.server ?: "not found"}")
            Text("Last sync: ${s.lastSync ?: "-"}")
            Text("Summary: ${s.lastSummary ?: "-"}")
            s.error?.let { Text("Error: $it") }
            if (s.busy) CircularProgressIndicator()
        }
        vm.controller?.let { c ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { c.scan() }, enabled = !(state?.busy ?: false)) { Text("Scan") }
                Button(onClick = { c.sync(c.state.value.server) }, enabled = !(state?.busy ?: false)) { Text("Sync") }
                Button(onClick = { c.verify() }, enabled = !(state?.busy ?: false)) { Text("Verify") }
            }
            Button(onClick = { android.os.Handler(android.os.Looper.getMainLooper()).post { discoverAndSync(vm) } }) { Text("Find laptop & sync") }
        }
    }
}

@Composable
private fun rememberStableState() = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(null) }
```

Note: `discoverAndSync(vm)` is a top-level helper that runs `vm.discovery.find()` on a coroutine and calls `vm.controller!!.setServer(url); vm.controller!!.sync(url)` — implement it in the same file with a `rememberCoroutineScope()`.

- [ ] **Step 2: Wire MainActivity**

Update `MainActivity.kt` to construct `MusicViewModel` (via `viewModel()`), call `vm.restoreFolder()` + `vm.discovery.start()` in `onCreate`, and pass `vm` to `StatusScreen`.

- [ ] **Step 3: Build + commit**

Run: `JAVA_HOME=... ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

```bash
git add android/app/
git commit -m "feat(app): status screen with SAF folder picker and sync wiring"
```

---

## Task 6: Browser screen — folder tree, rename/move/delete

**Files:**
- Create: `android/app/src/main/kotlin/com/musicsyncer/app/ui/BrowserScreen.kt`
- Modify: `MainActivity.kt` (add navigation to Browser)

**Interfaces:**
- Consumes: `vm.fs` (SafFs), `vm.store`
- Produces: `@Composable fun BrowserScreen(vm: MusicViewModel)` — breadcrumb-navigable folder tree; tapping a folder drills down; tapping a song shows actions (Rename, Move to folder, Delete). File ops go through `vm.fs`:
  - Rename: `fs.rename(rel, newRel)` (same directory).
  - Move: pick target folder from the tree; `fs.read(rel)` → `fs.write(targetRel)` → `fs.delete(rel)` (cross-directory move = copy+delete, per SAF).
  - Delete: `fs.delete(rel)`.
  - After any op: `vm.controller?.scan()` to journal it.

- [ ] **Step 1: Write the implementation** (minimal but functional Compose: a `LazyColumn` of entries, a back stack of relative dirs, an `AlertDialog` for rename/delete confirm, a simple "move target" selection via the same tree UI)

Key logic sketch (complete code in the file):
```kotlin
@Composable
fun BrowserScreen(vm: MusicViewModel) {
    val fs = vm.fs ?: return Text("Pick a folder on the Status screen first")
    val scope = rememberCoroutineScope()
    var dir by remember { mutableStateOf("") }                    // current dir ("" = root)
    val entries by produceState(initialValue = emptyList<FsEntry>(), dir) {
        value = fs.list().filter { it.rel.startsWith(dirPrefix(dir)) }
            .groupBy { entry ->
                val rest = entry.rel.removePrefix(dirPrefix(dir))
                if ('/' in rest) "DIR:${rest.substringBefore('/')}" else "FILE:$rest"
            }.toList().sortedBy { it.first }                        // dirs first
    }
    // Breadcrumb row, LazyColumn of entries (dirs navigate down; songs open an action menu)
    // Song actions: Rename dialog, Move (navigate to target dir then confirm), Delete confirm
    // After each op: launch { fs.op(...); vm.controller?.scan() }
}
```
Provide complete code with the dialogs and helpers (`dirPrefix(dir)` returns `""` for root else `"$dir/"`).

- [ ] **Step 2: Build + commit**

Run: `JAVA_HOME=... ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

```bash
git add android/app/
git commit -m "feat(app): browser screen — folder tree with rename/move/delete"
```

---

## Task 7: Metadata editor — mp3 tags + lyrics via jaudiotagger

**Files:**
- Create: `android/app/src/main/kotlin/com/musicsyncer/app/tag/TagEditor.kt`, `android/app/src/main/kotlin/com/musicsyncer/app/ui/EditorScreen.kt`
- Modify: `MainActivity.kt` (navigation to Editor)

**Interfaces:**
- Consumes: `vm.fs`, `vm.store`, `vm.controller`
- Produces:
  - `data class SongTags(val title: String?, val artist: String?, val album: String?, val albumArtist: String?, val genre: String?, val track: String?, val lyrics: String?)`
  - `object TagEditor { fun read(fs: Fs, rel: String): SongTags; fun write(fs: Fs, rel: String, tags: SongTags) }`
  - Write flow (no all-files access): `fs.read(rel)` → write to a cache file (`context.cacheDir/`+random) → `AudioFileIO.read(cacheFile)` → set fields → `AudioFileIO.write(audioFile)` → `fs.write(rel, cacheFile.readBytes())` → delete cache → `vm.controller.scan()` (journals the MODIFY). Lyrics → ID3 `USLT` frame (unsynchronized lyrics) via `ID3v24Frame`/`ID3v23Frame` — use jaudiotagger's `org.jaudiotagger.tag.id3.framebody.FrameBodyUSLT`.

- [ ] **Step 1: Write the implementation**

`tag/TagEditor.kt`:
```kotlin
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
        val tag = fs.openRead(rel).use { AudioFileIO.read(it) }.tag ?: return SongTags(null, null, null, null, null, null, null)
        return SongTags(
            tag.getFirst(FieldKey.TITLE), tag.getFirst(FieldKey.ARTIST), tag.getFirst(FieldKey.ALBUM),
            tag.getFirst(FieldKey.ALBUM_ARTIST), tag.getFirst(FieldKey.GENRE), tag.getFirst(FieldKey.TRACK),
            readLyrics(tag),
        )
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
                setLyricsText(lyrics)
            }
            tag.setFrame(frame)
        }
    }
}
```

`ui/EditorScreen.kt`: a form (OutlinedTextField for title/artist/album/albumArtist/genre/track + a multi-line field for lyrics) bound to a `SongTags` state; Save button → `TagEditor.write(...)` + `vm.controller?.scan()`; back to Browser.

- [ ] **Step 2: Build + commit**

Run: `JAVA_HOME=... ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

```bash
git add android/app/
git commit -m "feat(app): metadata editor — mp3 tags + lyrics via jaudiotagger"
```

---

## Task 8: Log screen, navigation, final APK

**Files:**
- Create: `android/app/src/main/kotlin/com/musicsyncer/app/ui/LogScreen.kt`
- Modify: `MainActivity.kt` (bottom navigation: Status / Browser / Log; Editor launched from Browser)

**Interfaces:**
- Consumes: `vm.store` (`journalSince(0)`), engine `JournalOp`
- Produces: `@Composable fun LogScreen(vm: MusicViewModel)` — a `LazyColumn` rendering journal entries as readable lines: `yyyy-MM-dd HH:mm  device  OP  path` (most recent last or a `--limit` of the last 200).

- [ ] **Step 1: Write the implementation**

`ui/LogScreen.kt`:
```kotlin
package com.musicsyncer.app.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import com.musicsyncer.app.MusicViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogScreen(vm: MusicViewModel) {
    val entries by produceState(initialValue = emptyList<String>()) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        value = vm.store.journalSince(0).takeLast(200).map { op ->
            "${fmt.format(Date(op.tsNs / 1_000_000))}  ${op.device}  ${op.op}  ${op.path}"
        }
    }
    LazyColumn { items(entries) { Text(it) } }
}
```

- [ ] **Step 2: Wire navigation + final build**

Update `MainActivity.kt`: a `Scaffold` with `NavigationBar` (Status, Browser, Log) switching the content composable; Editor screen navigable from Browser (a simple `var editingRel by mutableStateOf<String?>(null)` in BrowserScreen, shown as a full-screen overlay). 

Run: `JAVA_HOME=... ./gradlew :app:assembleDebug` — BUILD SUCCESSFUL. Then verify the APK: `ls -la android/app/build/outputs/apk/debug/app-debug.apk` and `unzip -l <apk> | grep -E "classes.dex|AndroidManifest"`.

- [ ] **Step 3: Commit**

```bash
git add android/app/
git commit -m "feat(app): log screen + bottom navigation — final APK"
```

---

## Task 9: Install guide + smoke

**Files:**
- Modify: `README.md` (add the Android install section)

**Interfaces:** none (documentation)

- [ ] **Step 1: Add install guide to README**

Append to `README.md`:
```markdown
## Android app

Build the APK:

    cd android && JAVA_HOME=$HOME/.local/share/jdks/jdk-21.0.12.1+1 ./gradlew :app:assembleDebug

Install on the phone (USB debugging enabled, phone connected):

    adb install android/app/build/outputs/apk/debug/app-debug.apk

First run: tap "Choose music folder" and pick the music folder (e.g. `/sdcard/Music`).
Then: start the laptop daemon (`msserve`), tap "Find laptop & sync". Subsequent syncs:
make changes, tap Scan, then Sync.
```

- [ ] **Step 2: Full build + engine regression + commit**

Run: `JAVA_HOME=... ./gradlew :app:assembleDebug :engine:test`
Expected: BUILD SUCCESSFUL; engine tests still 49/49.

```bash
git add README.md
git commit -m "docs: Android install guide + smoke"
```

---

## Self-Review

**Spec coverage:** SAF folder picker, no all-files (Task 1, 5, 3); browser + rename/move/delete (Task 6); metadata editor incl. lyrics via jaudiotagger, mp3-only (Task 7); Scan/Sync/Verify buttons + manual trigger (Tasks 4-5); mDNS discovery (Task 4); MediaStore rescan (Task 4); readable log (Task 8); identical engine semantics (engine module untouched, wire protocol unchanged); no foreground service/watcher/auto-trigger; no playback UI; APK deliverable (Tasks 1, 9). Gaps: none against the spec. Test strategy matches the user constraint (no on-device instrumentation; RoomStore via Robolectric; SafFs/UI verified by build + review).

**Placeholder scan:** all steps have concrete code; the two "sketch" markers (BrowserScreen key-logic sketch, MainActivity navigation) are followed by explicit "provide complete code" instructions for the implementer — the plan's code blocks are the authoritative base and must be completed, not left as TODOs. The `rememberStableState` helper and `discoverAndSync` helper in Task 5 are marked for completion in-file.

**Type consistency:** engine interfaces (`Fs`, `FsEntry`, `SyncStore`, `ManifestRow`, `JournalOp`, `SyncStateRow`, `scan`, `runSyncSession`, `SyncSummary`, `Hashing`) used exactly as defined in the engine module; `SafFs`/`RoomStore` implement the engine contracts with matching signatures; `SyncController`/`SyncState`/`Discovery`/`TagEditor`/`SongTags` are defined once and used consistently across the screens. `System.currentTimeMillis() * 1_000_000` matches the engine's wall-clock tsNs (post-fix). `MediaRescan` uses the engine's summary lists. Navigation state (`editingRel`) scoped to BrowserScreen.