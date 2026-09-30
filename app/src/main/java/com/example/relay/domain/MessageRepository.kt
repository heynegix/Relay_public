package com.example.relay.domain

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface InsertResult {
    data object Inserted : InsertResult
    data object Duplicate : InsertResult
    data object Collision : InsertResult
    data class Rejected(val reason: String) : InsertResult
}

data class MessageDelivery(
    val messageId: String,
    val peerDeviceId: String,
    val acknowledgedAt: Long,
    val packetId: String,
)

sealed interface MessageRepositoryChange {
    data class MessageStored(val messageId: String) : MessageRepositoryChange
    data class ReceiptStored(val receiptId: String) : MessageRepositoryChange
    data object MessagesRemoved : MessageRepositoryChange
}

interface MessageRepository {
    /**
     * Hot, non-replaying invalidation stream. A connection always sends an initial
     * manifest, so subscribers only need changes that happen after they start.
     */
    val changes: Flow<MessageRepositoryChange>
    suspend fun insert(message: RelayMessage): InsertResult
    suspend fun find(messageId: String): RelayMessage?
    suspend fun all(): List<RelayMessage>
    suspend fun markAcknowledged(delivery: MessageDelivery)
    suspend fun wasAcknowledged(messageId: String, peerDeviceId: String): Boolean
    suspend fun deliveries(): List<MessageDelivery>
    suspend fun insertReceipt(receipt: DeliveryReceipt): InsertResult
    suspend fun allReceipts(): List<DeliveryReceipt>
    suspend fun receiptsFor(messageId: String): List<DeliveryReceipt>
    /**
     * Removes every message that [policy] conservatively considers inactive, plus
     * delivery and receipt records that refer to those messages.
     *
     * Implementations must apply the three removals atomically so capacity cannot
     * be reclaimed while orphaned delivery state remains visible.
     */
    suspend fun pruneExpired(policy: MessagePolicy): Int
}

class InMemoryMessageRepository(private val resourcePolicy: ResourcePolicy = ResourcePolicy()) : MessageRepository {
    private val mutex = Mutex()
    private val messages = linkedMapOf<String, RelayMessage>()
    private val deliveryMap = linkedMapOf<Pair<String, String>, MessageDelivery>()
    private val receipts = linkedMapOf<String, DeliveryReceipt>()
    private val _changes = MutableSharedFlow<MessageRepositoryChange>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val changes: Flow<MessageRepositoryChange> = _changes.asSharedFlow()

    override suspend fun insert(message: RelayMessage): InsertResult {
        var stored = false
        val result = mutex.withLock {
            val existing = messages[message.messageId]
            if (existing == null) {
                if (messages.size >= resourcePolicy.maxStoredMessages) return@withLock InsertResult.Rejected("max stored messages")
                if (messages.values.count { it.originDeviceId == message.originDeviceId } >= resourcePolicy.maxStoredMessagesPerOrigin) {
                    return@withLock InsertResult.Rejected("max messages per origin")
                }
                messages[message.messageId] = message
                stored = true
                InsertResult.Inserted
            } else if (!sameCanonicalMessage(existing, message)) {
                InsertResult.Collision
            } else {
                if (message.hopCount < existing.hopCount) messages[message.messageId] = existing.copy(hopCount = message.hopCount)
                InsertResult.Duplicate
            }
        }
        if (stored) _changes.tryEmit(MessageRepositoryChange.MessageStored(message.messageId))
        return result
    }

    override suspend fun find(messageId: String): RelayMessage? = mutex.withLock { messages[messageId] }
    override suspend fun all(): List<RelayMessage> = mutex.withLock { messages.values.toList() }

    override suspend fun markAcknowledged(delivery: MessageDelivery) = mutex.withLock {
        if (messages.containsKey(delivery.messageId)) {
            val key = delivery.messageId to delivery.peerDeviceId
            if (key !in deliveryMap) deliveryMap[key] = delivery
        }
    }

    override suspend fun wasAcknowledged(messageId: String, peerDeviceId: String): Boolean = mutex.withLock {
        deliveryMap.containsKey(messageId to peerDeviceId)
    }

    override suspend fun deliveries(): List<MessageDelivery> = mutex.withLock { deliveryMap.values.toList() }

    override suspend fun insertReceipt(receipt: DeliveryReceipt): InsertResult {
        var stored = false
        val result = mutex.withLock {
            if (receipt.messageId !in messages) return@withLock InsertResult.Rejected("unknown message")
            val existing = receipts[receipt.receiptId]
            when {
                existing == receipt -> InsertResult.Duplicate
                existing != null -> InsertResult.Collision
                receipts.values.any {
                    it.messageId == receipt.messageId &&
                        it.receiptType == receipt.receiptType &&
                        it.actorId == receipt.actorId
                } -> InsertResult.Duplicate
                else -> {
                    val globalFull = receipts.size >= resourcePolicy.maxStoredReceipts
                    val messageFull = receipts.values.count { it.messageId == receipt.messageId } >=
                        resourcePolicy.maxStoredReceiptsPerMessage
                    if (globalFull || messageFull) {
                        val evicted = receiptEvictionCandidate(
                            incoming = receipt,
                            existing = receipts.values,
                            requireSameMessage = messageFull,
                        )
                        if (evicted == null) {
                            return@withLock InsertResult.Rejected(
                                if (messageFull) "max receipts per message" else "max stored receipts",
                            )
                        }
                        receipts.remove(evicted.receiptId)
                    }
                    receipts[receipt.receiptId] = receipt
                    stored = true
                    InsertResult.Inserted
                }
            }
        }
        if (stored) _changes.tryEmit(MessageRepositoryChange.ReceiptStored(receipt.receiptId))
        return result
    }

    override suspend fun allReceipts(): List<DeliveryReceipt> = mutex.withLock { receipts.values.toList() }
    override suspend fun receiptsFor(messageId: String): List<DeliveryReceipt> = mutex.withLock { receipts.values.filter { it.messageId == messageId } }

    override suspend fun pruneExpired(policy: MessagePolicy): Int {
        val removedIds = mutex.withLock {
            val ids = messages.values
                .filterNot(policy::isActive)
                .mapTo(linkedSetOf()) { it.messageId }
            if (ids.isNotEmpty()) {
                messages.entries.removeAll { it.key in ids }
                deliveryMap.entries.removeAll { it.value.messageId in ids }
                receipts.entries.removeAll { it.value.messageId in ids }
            }
            ids
        }
        if (removedIds.isNotEmpty()) _changes.tryEmit(MessageRepositoryChange.MessagesRemoved)
        return removedIds.size
    }

    private fun sameCanonicalMessage(a: RelayMessage, b: RelayMessage): Boolean =
        a.messageId == b.messageId && a.messageType == b.messageType && a.createdAt == b.createdAt &&
            a.expiresAt == b.expiresAt && a.priority == b.priority && a.originDeviceId == b.originDeviceId &&
            a.payload == b.payload && a.maxHopCount == b.maxHopCount && a.recordType == b.recordType &&
            a.lifetimeMs == b.lifetimeMs
}

/**
 * Receipt capacity is a trust boundary. A flood of peer acknowledgements must not
 * prevent a later authenticated PC Gateway receipt from being persisted.
 */
internal fun receiptEvictionCandidate(
    incoming: DeliveryReceipt,
    existing: Collection<DeliveryReceipt>,
    requireSameMessage: Boolean,
): DeliveryReceipt? {
    val incomingRank = receiptTrustRank(incoming.receiptType)
    return existing.asSequence()
        .filter { !requireSameMessage || it.messageId == incoming.messageId }
        .filter { receiptTrustRank(it.receiptType) < incomingRank }
        .sortedWith(
            compareBy<DeliveryReceipt> { receiptTrustRank(it.receiptType) }
                .thenBy { it.recordedAt }
                .thenBy { it.receiptId },
        )
        .firstOrNull()
}

private fun receiptTrustRank(type: ReceiptType): Int = when (type) {
    ReceiptType.PEER_RECEIVED -> 0
    ReceiptType.GATEWAY_RECEIVED_UNVERIFIED -> 1
    ReceiptType.GATEWAY_RECEIVED -> 2
}
