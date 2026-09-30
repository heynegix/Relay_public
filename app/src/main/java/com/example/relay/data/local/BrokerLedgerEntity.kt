package com.example.relay.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Broker delivery ledger. Tracks Broker upload state separately from shelter receipt status.
 * BROKER_STORED does NOT mean the shelter has received the request — that is SHELTER_* only.
 */
@Entity(
    tableName = "broker_ledger",
    primaryKeys = ["requestId", "requestVersion"],
)
data class BrokerLedgerEntity(
    val requestId: String,
    val requestVersion: Int,
    val brokerReceiptId: String?,
    val brokerStatus: String = "PENDING",
    val uploadedAtEpochMillis: Long?,
    val retryCount: Int = 0,
)

@Dao
interface BrokerLedgerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: BrokerLedgerEntity)

    @Query("SELECT * FROM broker_ledger WHERE requestId = :requestId AND requestVersion = :requestVersion")
    fun find(requestId: String, requestVersion: Int): BrokerLedgerEntity?

    @Query("SELECT * FROM broker_ledger WHERE brokerStatus = 'PENDING' OR brokerStatus = 'RETRYING'")
    fun pendingUploads(): List<BrokerLedgerEntity>

    @Query("UPDATE broker_ledger SET brokerStatus = 'UPLOADED', brokerReceiptId = :receiptId, uploadedAtEpochMillis = :uploadedAt WHERE requestId = :requestId AND requestVersion = :requestVersion")
    fun markUploaded(requestId: String, requestVersion: Int, receiptId: String, uploadedAt: Long): Int

    @Query("UPDATE broker_ledger SET brokerStatus = 'RETRYING', retryCount = retryCount + 1 WHERE requestId = :requestId AND requestVersion = :requestVersion")
    fun markRetrying(requestId: String, requestVersion: Int): Int

    /** Stops automatic retries for a deterministic client-side failure (for example a collision). */
    @Query("UPDATE broker_ledger SET brokerStatus = 'FAILED', retryCount = retryCount + 1 WHERE requestId = :requestId AND requestVersion = :requestVersion")
    fun markFailed(requestId: String, requestVersion: Int): Int

    @Query("DELETE FROM broker_ledger WHERE requestId = :requestId AND requestVersion = :requestVersion")
    fun delete(requestId: String, requestVersion: Int): Int

    @Query("DELETE FROM broker_ledger WHERE requestId IN (SELECT requestId FROM rescue_envelopes WHERE expiresAtEpochMillis <= :nowEpochMillis)")
    fun deleteExpired(nowEpochMillis: Long): Int
}
