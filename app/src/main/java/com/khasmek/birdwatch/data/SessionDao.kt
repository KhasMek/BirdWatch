package com.khasmek.birdwatch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: ScanSession)

    @Update
    suspend fun update(session: ScanSession)

    @Query("SELECT * FROM scan_sessions WHERE id = :id")
    suspend fun getById(id: String): ScanSession?

    @Query("SELECT * FROM scan_sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActive(): ScanSession?

    /** Crash recovery: any session left open by a previous process gets closed at app start. */
    @Query("UPDATE scan_sessions SET endedAt = :endedAt WHERE endedAt IS NULL")
    suspend fun closeOpenSessions(endedAt: Long)

    @Query(SUMMARY_SELECT + " ORDER BY s.startedAt DESC")
    fun observeSummaries(): Flow<List<SessionSummary>>

    @Query("$SUMMARY_SELECT WHERE s.id = :id")
    fun observeSummary(id: String): Flow<SessionSummary?>

    /** The most recent session that has ended: the "previous session" view. */
    @Query("$SUMMARY_SELECT WHERE s.endedAt IS NOT NULL ORDER BY s.startedAt DESC LIMIT 1")
    fun observePrevious(): Flow<SessionSummary?>

    @Query("DELETE FROM scan_sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM scan_sessions")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM scan_sessions")
    suspend fun count(): Int

    @Query("SELECT * FROM scan_sessions ORDER BY startedAt DESC")
    suspend fun getAll(): List<ScanSession>

    companion object {
        private const val SUMMARY_SELECT = """
            SELECT s.*,
              (SELECT COUNT(*) FROM detected_devices d WHERE d.sessionId = s.id) AS deviceCount,
              (SELECT COUNT(*) FROM detected_devices d WHERE d.sessionId = s.id AND d.deviceType = 'FLOCK') AS flockCount,
              (SELECT COUNT(*) FROM detected_devices d WHERE d.sessionId = s.id AND d.deviceType IN ('RAVEN', 'SOUNDTHINKING')) AS ravenCount
            FROM scan_sessions s
        """
    }
}
