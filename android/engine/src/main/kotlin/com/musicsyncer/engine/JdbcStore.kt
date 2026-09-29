package com.musicsyncer.engine

import java.sql.Connection
import java.sql.DriverManager

class JdbcStore(url: String) : SyncStore {
    private val conn: Connection = DriverManager.getConnection(url)

    init {
        // sqlite-jdbc's Statement.execute() runs only the first of multiple
        // semicolon-separated statements, so each CREATE TABLE is executed
        // separately (equivalent to Python's executescript(SCHEMA)).
        conn.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS manifest (
                    path TEXT PRIMARY KEY, size INTEGER NOT NULL, mtime_ns INTEGER NOT NULL,
                    sha256 TEXT, last_seen_ns INTEGER NOT NULL);
                """.trimIndent()
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS journal (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    op TEXT NOT NULL CHECK (op IN ('CREATE','MODIFY','DELETE')),
                    path TEXT NOT NULL, size INTEGER, sha256 TEXT, ts_ns INTEGER NOT NULL, device TEXT NOT NULL);
                """.trimIndent()
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS sync_state (
                    peer_device_id TEXT PRIMARY KEY, last_seen_journal_id INTEGER NOT NULL DEFAULT 0, last_sync_ns INTEGER);
                """.trimIndent()
            )
        }
    }

    override fun manifestGet(path: String): ManifestRow? =
        conn.prepareStatement("SELECT size, mtime_ns, sha256, last_seen_ns FROM manifest WHERE path=?").use { ps ->
            ps.setString(1, path)
            ps.executeQuery().let { rs -> if (rs.next()) ManifestRow(path, rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4)) else null }
        }

    override fun manifestUpsert(path: String, size: Long, mtimeNs: Long, sha256: String?, nowNs: Long) {
        conn.prepareStatement(
            "INSERT INTO manifest (path,size,mtime_ns,sha256,last_seen_ns) VALUES (?,?,?,?,?) " +
            "ON CONFLICT(path) DO UPDATE SET size=excluded.size, mtime_ns=excluded.mtime_ns, sha256=excluded.sha256, last_seen_ns=excluded.last_seen_ns"
        ).use { ps -> ps.setString(1, path); ps.setLong(2, size); ps.setLong(3, mtimeNs); ps.setString(4, sha256); ps.setLong(5, nowNs); ps.executeUpdate() }
    }

    override fun manifestDelete(path: String) {
        conn.prepareStatement("DELETE FROM manifest WHERE path=?").use { ps -> ps.setString(1, path); ps.executeUpdate() }
    }

    override fun manifestAll(): List<ManifestRow> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT path,size,mtime_ns,sha256,last_seen_ns FROM manifest ORDER BY path").let { rs ->
                buildList { while (rs.next()) add(ManifestRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getLong(5))) }
            }
        }

    override fun journalAppend(op: String, path: String, size: Long?, sha256: String?, tsNs: Long, device: String): Long {
        conn.prepareStatement("INSERT INTO journal (op,path,size,sha256,ts_ns,device) VALUES (?,?,?,?,?,?)",
            java.sql.Statement.RETURN_GENERATED_KEYS).use { ps ->
            ps.setString(1, op); ps.setString(2, path); ps.setObject(3, size); ps.setString(4, sha256)
            ps.setLong(5, tsNs); ps.setString(6, device); ps.executeUpdate()
            ps.generatedKeys.use { g -> g.next(); return g.getLong(1) }
        }
    }

    override fun journalHead(): Long =
        conn.createStatement().use { st -> st.executeQuery("SELECT COALESCE(MAX(id),0) FROM journal").let { rs -> rs.next(); rs.getLong(1) } }

    override fun journalSince(afterId: Long): List<JournalOp> =
        conn.prepareStatement("SELECT id,op,path,size,sha256,ts_ns,device FROM journal WHERE id > ? ORDER BY id").use { ps ->
            ps.setLong(1, afterId)
            ps.executeQuery().let { rs ->
                buildList { while (rs.next()) add(JournalOp(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4)?.let { (it as Number).toLong() }, rs.getString(5), rs.getLong(6), rs.getString(7))) }
            }
        }

    override fun syncStateGet(peerDeviceId: String): SyncStateRow? =
        conn.prepareStatement("SELECT last_seen_journal_id, last_sync_ns FROM sync_state WHERE peer_device_id=?").use { ps ->
            ps.setString(1, peerDeviceId)
            ps.executeQuery().let { rs -> if (rs.next()) SyncStateRow(peerDeviceId, rs.getLong(1), rs.getObject(2)?.let { (it as Number).toLong() }) else null }
        }

    override fun syncStateSet(peerDeviceId: String, lastSeenJournalId: Long, lastSyncNs: Long) {
        conn.prepareStatement(
            "INSERT INTO sync_state (peer_device_id,last_seen_journal_id,last_sync_ns) VALUES (?,?,?) " +
            "ON CONFLICT(peer_device_id) DO UPDATE SET last_seen_journal_id=excluded.last_seen_journal_id, last_sync_ns=excluded.last_sync_ns"
        ).use { ps -> ps.setString(1, peerDeviceId); ps.setLong(2, lastSeenJournalId); ps.setLong(3, lastSyncNs); ps.executeUpdate() }
    }
}