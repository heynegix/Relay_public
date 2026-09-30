package com.example.relay.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.relay.domain.ReceiptType
import kotlinx.coroutines.flow.Flow

@Dao
interface RelayDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    suspend fun findMessage(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages ORDER BY priority DESC, createdAt DESC, expiresAt ASC")
    suspend fun allMessages(): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun messageCount(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE originDeviceId = :originDeviceId")
    suspend fun messageCountForOrigin(originDeviceId: String): Int

    @Query("SELECT * FROM messages ORDER BY priority DESC, createdAt DESC, expiresAt ASC")
    fun observeMessages(): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET hopCount = :hopCount WHERE messageId = :messageId AND hopCount > :hopCount")
    suspend fun lowerHopCount(messageId: String, hopCount: Int)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDelivery(delivery: MessageDeliveryEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM message_deliveries WHERE messageId = :messageId AND peerDeviceId = :peerDeviceId)")
    suspend fun wasAcknowledged(messageId: String, peerDeviceId: String): Boolean

    @Query("SELECT * FROM message_deliveries")
    suspend fun allDeliveries(): List<MessageDeliveryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReceipt(receipt: DeliveryReceiptEntity): Long

    @Query("SELECT * FROM delivery_receipts WHERE receiptId = :receiptId")
    suspend fun findReceipt(receiptId: String): DeliveryReceiptEntity?

    @Query("SELECT * FROM delivery_receipts WHERE messageId = :messageId AND receiptType = :receiptType AND actorId = :actorId LIMIT 1")
    suspend fun findSemanticReceipt(
        messageId: String,
        receiptType: ReceiptType,
        actorId: String,
    ): DeliveryReceiptEntity?

    @Query("SELECT COUNT(*) FROM delivery_receipts")
    suspend fun receiptCount(): Int

    @Query("SELECT COUNT(*) FROM delivery_receipts WHERE messageId = :messageId")
    suspend fun receiptCountForMessage(messageId: String): Int

    @Query("SELECT * FROM delivery_receipts")
    suspend fun allReceipts(): List<DeliveryReceiptEntity>

    @Query("SELECT * FROM delivery_receipts")
    fun observeReceipts(): Flow<List<DeliveryReceiptEntity>>

    @Query("DELETE FROM delivery_receipts WHERE receiptId = :receiptId")
    suspend fun deleteReceiptById(receiptId: String): Int

    @Query("SELECT * FROM delivery_receipts WHERE messageId = :messageId")
    suspend fun receiptsFor(messageId: String): List<DeliveryReceiptEntity>

    @Query("DELETE FROM messages WHERE messageId IN (:messageIds)")
    suspend fun deleteMessagesById(messageIds: List<String>): Int

    @Query("DELETE FROM message_deliveries WHERE messageId IN (:messageIds)")
    suspend fun deleteDeliveriesByMessageId(messageIds: List<String>): Int

    @Query("DELETE FROM delivery_receipts WHERE messageId IN (:messageIds)")
    suspend fun deleteReceiptsByMessageId(messageIds: List<String>): Int

    @Query("DELETE FROM message_deliveries")
    suspend fun deleteDeliveries()

    @Query("DELETE FROM delivery_receipts")
    suspend fun deleteReceipts()

    @Query("DELETE FROM messages")
    suspend fun deleteMessages()
}
