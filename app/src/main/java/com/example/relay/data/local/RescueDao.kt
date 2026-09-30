package com.example.relay.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Synchronous DAO. Callers must invoke it from a background thread. */
@Dao
interface RescueDao {
    /** Room invalidation stream covers writes made inside ActiveRescueSessionStore transactions. */
    @Query("SELECT * FROM rescue_envelopes ORDER BY requestId, requestVersion, envelopeId")
    fun observeAll(): Flow<List<RescueEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(entity: RescueEntity): Long

    @Update
    fun update(entity: RescueEntity): Int

    @Query("SELECT * FROM rescue_envelopes WHERE requestId = :requestId AND requestVersion = :requestVersion")
    fun find(requestId: String, requestVersion: Int): RescueEntity?

    @Query("SELECT * FROM rescue_envelopes ORDER BY requestId, requestVersion, envelopeId")
    fun all(): List<RescueEntity>

    @Query("DELETE FROM rescue_envelopes WHERE expiresAtEpochMillis <= :nowEpochMillis")
    fun deleteExpired(nowEpochMillis: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM rescue_envelopes WHERE requestId = :requestId AND requestVersion > :requestVersion)")
    fun hasNewerVersion(requestId: String, requestVersion: Int): Boolean

    @Query("SELECT * FROM rescue_envelopes WHERE requestId = :requestId AND requestVersion < :requestVersion ORDER BY requestId, requestVersion")
    fun olderVersions(requestId: String, requestVersion: Int): List<RescueEntity>

    @Query("DELETE FROM rescue_envelopes WHERE requestId = :requestId AND requestVersion < :requestVersion")
    fun deleteOlderVersions(requestId: String, requestVersion: Int): Int

    @Query("SELECT COUNT(*) FROM rescue_envelopes")
    fun count(): Int

    @Query("SELECT COALESCE(SUM(storageSizeBytes), 0) FROM rescue_envelopes")
    fun totalStorageSizeBytes(): Long

    @Query(
        """SELECT * FROM rescue_envelopes
            WHERE NOT (requestId = :protectedRequestId AND requestVersion = :protectedRequestVersion)
            ORDER BY expiresAtEpochMillis, receivedAtEpochMillis, createdAtEpochMillis,
                     requestId, requestVersion, envelopeId
            LIMIT 1""",
    )
    fun pruningCandidate(protectedRequestId: String, protectedRequestVersion: Int): RescueEntity?

    @Query(
        """SELECT * FROM rescue_envelopes
            ORDER BY expiresAtEpochMillis, receivedAtEpochMillis, createdAtEpochMillis,
                     requestId, requestVersion, envelopeId""",
    )
    fun pruningCandidates(): List<RescueEntity>

    @Query("DELETE FROM rescue_envelopes WHERE requestId = :requestId AND requestVersion = :requestVersion")
    fun delete(requestId: String, requestVersion: Int): Int
}
