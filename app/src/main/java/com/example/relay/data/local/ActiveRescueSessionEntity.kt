package com.example.relay.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Durable sender-owned rescue state. Rescue contents, GPS, free text, and sender identity live
 * only in [sealedRecoveryPayload], protected by a distinct Android Keystore AES-GCM key.
 */
@Entity(
    tableName = "active_rescue_sessions",
    primaryKeys = ["requestId"],
    indices = [Index("updatedAtEpochMillis"), Index("expiresAtEpochMillis"), Index("terminalStatus")],
)
data class ActiveRescueSessionEntity(
    val requestId: String,
    val latestVersion: Int,
    val sealedRecoveryPayload: ByteArray,
    val recoveryNonce: ByteArray,
    val trackingMode: String,
    val latestSubmissionStatus: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val terminalStatus: String?,
)

data class ActiveRescueSessionKey(
    val requestId: String,
    val latestVersion: Int,
)

@Dao
interface ActiveRescueSessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(entity: ActiveRescueSessionEntity): Long

    @Query("SELECT * FROM active_rescue_sessions WHERE requestId = :requestId")
    fun find(requestId: String): ActiveRescueSessionEntity?

    @Query("SELECT * FROM active_rescue_sessions ORDER BY updatedAtEpochMillis DESC, requestId")
    fun all(): List<ActiveRescueSessionEntity>

    /** Guards against creating a second live sender-owned request after process restoration. */
    @Query("SELECT EXISTS(SELECT 1 FROM active_rescue_sessions WHERE terminalStatus IS NULL AND expiresAtEpochMillis > :nowEpochMillis)")
    fun hasLiveActiveSession(nowEpochMillis: Long): Boolean

    /** Current active/cancellation-in-flight requests must never be capacity-pruned as couriers. */
    @Query("SELECT requestId, latestVersion FROM active_rescue_sessions WHERE terminalStatus IS NULL AND expiresAtEpochMillis > :nowEpochMillis")
    fun activeEnvelopeKeys(nowEpochMillis: Long): List<ActiveRescueSessionKey>

    /** Optimistic CAS prevents two processes from committing different payloads at one version. */
    @Query(
        """UPDATE active_rescue_sessions
            SET latestVersion = :nextVersion,
                sealedRecoveryPayload = :sealedRecoveryPayload,
                recoveryNonce = :recoveryNonce,
                trackingMode = :trackingMode,
                latestSubmissionStatus = :latestSubmissionStatus,
                updatedAtEpochMillis = :updatedAtEpochMillis,
                expiresAtEpochMillis = :expiresAtEpochMillis,
                terminalStatus = :terminalStatus
            WHERE requestId = :requestId AND latestVersion = :expectedVersion""",
    )
    fun updateIfVersion(
        requestId: String,
        expectedVersion: Int,
        nextVersion: Int,
        sealedRecoveryPayload: ByteArray,
        recoveryNonce: ByteArray,
        trackingMode: String,
        latestSubmissionStatus: String,
        updatedAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
        terminalStatus: String?,
    ): Int

    /**
     * Converts an on-device-only SOS into a transferable encrypted envelope without consuming a
     * new request version. The status predicate prevents a stale resolver from replacing an
     * already-materialized session.
     */
    @Query(
        """UPDATE active_rescue_sessions
            SET sealedRecoveryPayload = :sealedRecoveryPayload,
                recoveryNonce = :recoveryNonce,
                trackingMode = :trackingMode,
                latestSubmissionStatus = :latestSubmissionStatus,
                updatedAtEpochMillis = :updatedAtEpochMillis,
                expiresAtEpochMillis = :expiresAtEpochMillis
            WHERE requestId = :requestId
              AND latestVersion = :expectedVersion
              AND latestSubmissionStatus = 'PENDING_DESTINATION'
              AND terminalStatus IS NULL""",
    )
    fun materializePendingDestination(
        requestId: String,
        expectedVersion: Int,
        sealedRecoveryPayload: ByteArray,
        recoveryNonce: ByteArray,
        trackingMode: String,
        latestSubmissionStatus: String,
        updatedAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
    ): Int

    /** A pending-destination SOS has no envelope, so local cancellation safely deletes only it. */
    @Query(
        """DELETE FROM active_rescue_sessions
            WHERE requestId = :requestId
              AND latestVersion = :expectedVersion
              AND latestSubmissionStatus = 'PENDING_DESTINATION'
              AND terminalStatus IS NULL""",
    )
    fun deletePendingDestination(requestId: String, expectedVersion: Int): Int

    /** Only a verified shelter receipt is allowed to advance this public status field. */
    @Query(
        """UPDATE active_rescue_sessions
            SET latestSubmissionStatus = :latestSubmissionStatus,
                terminalStatus = :terminalStatus,
                updatedAtEpochMillis = :updatedAtEpochMillis
            WHERE requestId = :requestId AND latestVersion = :requestVersion""",
    )
    fun updateReceiptStatus(
        requestId: String,
        requestVersion: Int,
        latestSubmissionStatus: String,
        terminalStatus: String?,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """UPDATE active_rescue_sessions
            SET terminalStatus = 'EXPIRED', updatedAtEpochMillis = :updatedAtEpochMillis
            WHERE requestId = :requestId AND latestVersion = :requestVersion AND terminalStatus IS NULL""",
    )
    fun markExpired(requestId: String, requestVersion: Int, updatedAtEpochMillis: Long): Int

    /** A user may remove recovery material only after they have seen a terminal result. */
    @Query("DELETE FROM active_rescue_sessions WHERE requestId = :requestId AND terminalStatus IS NOT NULL")
    fun deleteTerminal(requestId: String): Int
}
