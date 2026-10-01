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
    @Query("DELETE FROM journal WHERE tsNs < :cutoffNs AND id <= :minCursorId") fun pruneJournal(cutoffNs: Long, minCursorId: Long): Int
}

@Database(entities = [ManifestEntity::class, JournalEntity::class, SyncStateEntity::class], version = 1)
abstract class MusicSyncDatabase : RoomDatabase() {
    abstract fun storeDao(): StoreDao
}

class RoomStore(private val db: MusicSyncDatabase) : SyncStore {
    private val dao = db.storeDao()

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

    // Room transactions are thread-confined: the block's DAO calls run on the
    // same thread that began the transaction (apply's commit loop is single-threaded).
    override fun withBatch(block: () -> Unit) {
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun pruneJournal(cutoffNs: Long, minCursorId: Long): Int {
        db.beginTransaction()
        return try {
            val n = dao.pruneJournal(cutoffNs, minCursorId)
            db.setTransactionSuccessful()
            n
        } finally {
            db.endTransaction()
        }
    }
}