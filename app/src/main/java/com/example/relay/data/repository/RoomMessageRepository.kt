package com.example.relay.data.repository

import androidx.room.withTransaction
import com.example.relay.data.local.MessageDeliveryEntity
import com.example.relay.data.local.MessageEntity
import com.example.relay.data.local.DeliveryReceiptEntity
import com.example.relay.data.local.RelayDatabase
import com.example.relay.domain.InsertResult
import com.example.relay.domain.MessageDelivery
import com.example.relay.domain.MessagePayload
import com.example.relay.domain.MessageRepository
import com.example.relay.domain.MessageRepositoryChange
import com.example.relay.domain.RelayMessage
import com.example.relay.domain.DeliveryReceipt
import com.example.relay.domain.ResourcePolicy
import com.example.relay.domain.DeliveryPresentation
import com.example.relay.domain.ReceiptType
import com.example.relay.domain.deriveDeliveryPresentation
import com.example.relay.domain.MessagePolicy
import com.example.relay.rescue.ReportSignature
import com.example.relay.domain.receiptEvictionCandidate
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RoomMessageRepository(
    private val database: RelayDatabase,
    private val resourcePolicy: ResourcePolicy = ResourcePolicy(),
    private val json: Json = Json { classDiscriminator = "payloadType"; ignoreUnknownKeys = false },
) : MessageRepository {
    private val dao = database.relayDao()
    private val _changes = MutableSharedFlow<MessageRepositoryChange>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val changes: Flow<MessageRepositoryChange> = _changes.asSharedFlow()

    override suspend fun insert(message: RelayMessage): InsertResult {
        val result = database.withTransaction {
            val existing = dao.findMessage(message.messageId)
            if (existing == null) {
                if (dao.messageCount() >= resourcePolicy.maxStoredMessages) return@withTransaction InsertResult.Rejected("max stored messages")
                if (dao.messageCountForOrigin(message.originDeviceId) >= resourcePolicy.maxStoredMessagesPerOrigin) return@withTransaction InsertResult.Rejected("max messages per origin")
                dao.insertMessage(message.toEntity())
                InsertResult.Inserted
            } else if (!sameCanonical(existing.toDomain(), message)) {
                InsertResult.Collision
            } else {
                dao.lowerHopCount(message.messageId, message.hopCount)
                InsertResult.Duplicate
            }
        }
        if (result == InsertResult.Inserted) {
            _changes.tryEmit(MessageRepositoryChange.MessageStored(message.messageId))
        }
        return result
    }

    override suspend fun find(messageId: String): RelayMessage? = dao.findMessage(messageId)?.toDomain()
    override suspend fun all(): List<RelayMessage> = dao.allMessages().map { it.toDomain() }
    fun observeAll(): Flow<List<RelayMessage>> = dao.observeMessages().map { rows -> rows.map { it.toDomain() } }
    fun observeReceipts(): Flow<List<DeliveryReceipt>> = dao.observeReceipts().map { rows -> rows.map { it.toDomain() } }

    override suspend fun markAcknowledged(delivery: MessageDelivery) {
        if (dao.findMessage(delivery.messageId) != null) {
            dao.insertDelivery(delivery.toEntity())
        }
    }

    override suspend fun wasAcknowledged(messageId: String, peerDeviceId: String): Boolean =
        dao.wasAcknowledged(messageId, peerDeviceId)

    override suspend fun deliveries(): List<MessageDelivery> = dao.allDeliveries().map { it.toDomain() }

    override suspend fun insertReceipt(receipt: DeliveryReceipt): InsertResult {
        val result = database.withTransaction {
            if (dao.findMessage(receipt.messageId) == null) {
                return@withTransaction InsertResult.Rejected("unknown message")
            }
            val existing = dao.findReceipt(receipt.receiptId)?.toDomain()
            when {
                existing == receipt -> InsertResult.Duplicate
                existing != null -> InsertResult.Collision
                dao.findSemanticReceipt(receipt.messageId, receipt.receiptType, receipt.actorId) != null ->
                    InsertResult.Duplicate
                else -> {
                    val globalFull = dao.receiptCount() >= resourcePolicy.maxStoredReceipts
                    val messageFull = dao.receiptCountForMessage(receipt.messageId) >=
                        resourcePolicy.maxStoredReceiptsPerMessage
                    if (globalFull || messageFull) {
                        val evicted = receiptEvictionCandidate(
                            incoming = receipt,
                            existing = dao.allReceipts().map { it.toDomain() },
                            requireSameMessage = messageFull,
                        )
                        if (evicted == null) {
                            return@withTransaction InsertResult.Rejected(
                                if (messageFull) "max receipts per message" else "max stored receipts",
                            )
                        }
                        dao.deleteReceiptById(evicted.receiptId)
                    }
                    if (dao.insertReceipt(receipt.toEntity()) != -1L) {
                        InsertResult.Inserted
                    } else {
                        InsertResult.Rejected("receipt insert conflict")
                    }
                }
            }
        }
        if (result == InsertResult.Inserted) {
            _changes.tryEmit(MessageRepositoryChange.ReceiptStored(receipt.receiptId))
        }
        return result
    }
    override suspend fun allReceipts(): List<DeliveryReceipt> = dao.allReceipts().map { it.toDomain() }
    override suspend fun receiptsFor(messageId: String): List<DeliveryReceipt> = dao.receiptsFor(messageId).map { it.toDomain() }

    suspend fun deliveryPresentation(messageId: String): DeliveryPresentation = deliveryPresentation(receiptsFor(messageId))
    fun deliveryPresentation(receipts: List<DeliveryReceipt>): DeliveryPresentation = deriveDeliveryPresentation(receipts)

    override suspend fun pruneExpired(policy: MessagePolicy): Int {
        val deleted = database.withTransaction {
            val ids = dao.allMessages()
                .map { it.toDomain() }
                .filterNot(policy::isActive)
                .map { it.messageId }
            if (ids.isEmpty()) {
                0
            } else {
                dao.deleteDeliveriesByMessageId(ids)
                dao.deleteReceiptsByMessageId(ids)
                dao.deleteMessagesById(ids)
            }
        }
        if (deleted > 0) _changes.tryEmit(MessageRepositoryChange.MessagesRemoved)
        return deleted
    }

    @Deprecated("Use pruneExpired; all expired priorities must be reclaimed conservatively")
    suspend fun deleteExpiredLowPriority(policy: MessagePolicy): Int = pruneExpired(policy)

    suspend fun clearAll() {
        database.withTransaction {
            dao.deleteDeliveries()
            dao.deleteReceipts()
            dao.deleteMessages()
        }
        _changes.tryEmit(MessageRepositoryChange.MessagesRemoved)
    }

    private fun RelayMessage.toEntity() = MessageEntity(
        messageId, messageType, createdAt, expiresAt, priority, originDeviceId,
        json.encodeToString<MessagePayload>(payload), hopCount, maxHopCount, status, receivedAt,
        recordType, lifetimeMs, accumulatedAgeMs, receivedElapsedRealtimeMs, persistedAtWallClockMs, elapsedRealtimeSessionId,
        reportSignature?.let { json.encodeToString(ReportSignature.serializer(), it) },
    )

    private fun MessageEntity.toDomain() = RelayMessage(
        messageId, messageType, createdAt, expiresAt, priority, originDeviceId,
        json.decodeFromString<MessagePayload>(payloadJson), hopCount, maxHopCount, status, receivedAt,
        recordType, lifetimeMs, accumulatedAgeMs, receivedElapsedRealtimeMs, persistedAtWallClockMs, elapsedRealtimeSessionId,
        reportSignatureJson?.let { json.decodeFromString(ReportSignature.serializer(), it) },
    )

    private fun MessageDelivery.toEntity() = MessageDeliveryEntity(messageId, peerDeviceId, acknowledgedAt, packetId)
    private fun MessageDeliveryEntity.toDomain() = MessageDelivery(messageId, peerDeviceId, acknowledgedAt, packetId)
    private fun DeliveryReceipt.toEntity() = DeliveryReceiptEntity(receiptId, messageId, receiptType, actorId, recordedAt)
    private fun DeliveryReceiptEntity.toDomain() = DeliveryReceipt(receiptId, messageId, receiptType, actorId, recordedAt)

    private fun sameCanonical(a: RelayMessage, b: RelayMessage): Boolean =
        a.messageId == b.messageId && a.messageType == b.messageType && a.createdAt == b.createdAt &&
            a.expiresAt == b.expiresAt && a.priority == b.priority && a.originDeviceId == b.originDeviceId &&
            a.payload == b.payload && a.maxHopCount == b.maxHopCount && a.recordType == b.recordType &&
            a.lifetimeMs == b.lifetimeMs
}
