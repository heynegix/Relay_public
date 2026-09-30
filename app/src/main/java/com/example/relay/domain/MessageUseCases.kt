package com.example.relay.domain

import java.util.UUID

fun interface MessageIdGenerator {
    fun newId(): String
}

object UuidMessageIdGenerator : MessageIdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}

/** Signs locally-created REPORT records before they enter the durable repository. */
fun interface ReportSigner {
    fun sign(message: RelayMessage): RelayMessage
}

/** A classified creation failure that UI code can handle without relying on `check`. */
sealed class LegacyMessageCreationException(val reason: String) : RuntimeException(reason) {
    class InvalidMessage(reason: String) : LegacyMessageCreationException(reason)
    class DuplicateMessageId(messageId: String) : LegacyMessageCreationException("duplicate message id: $messageId")
    class MessageIdCollision(messageId: String) : LegacyMessageCreationException("message id collision: $messageId")
    class StorageRejected(reason: String) : LegacyMessageCreationException(reason)
}

private suspend fun persistCreatedMessage(
    repository: MessageRepository,
    policy: MessagePolicy,
    message: RelayMessage,
): RelayMessage {
    when (val validation = policy.validate(message)) {
        MessageValidation.Valid -> Unit
        is MessageValidation.Invalid -> throw LegacyMessageCreationException.InvalidMessage(validation.reason)
    }

    // Reclaim all conservatively expired records immediately before enforcing
    // repository capacity. This also removes their delivery/receipt state.
    repository.pruneExpired(policy)
    when (val result = repository.insert(message)) {
        InsertResult.Inserted -> return message
        InsertResult.Duplicate -> throw LegacyMessageCreationException.DuplicateMessageId(message.messageId)
        InsertResult.Collision -> throw LegacyMessageCreationException.MessageIdCollision(message.messageId)
        is InsertResult.Rejected -> throw LegacyMessageCreationException.StorageRejected(result.reason)
    }
}

class CreateSafetyMessageUseCase(
    private val repository: MessageRepository,
    private val policy: MessagePolicy,
    private val clock: Clock,
    private val originDeviceId: String,
    private val idGenerator: MessageIdGenerator = UuidMessageIdGenerator,
    private val defaultTtlMillis: Long = 24L * 60 * 60 * 1_000,
    private val defaultMaxHopCount: Int = 8,
    private val reportSigner: ReportSigner? = null,
) {
    suspend operator fun invoke(
        state: SafetyState,
        companionCount: Int,
        approximateLocation: String,
        note: String,
        priority: MessagePriority = MessagePriority.HIGH,
        ttlMillis: Long = defaultTtlMillis,
    ): RelayMessage = create(
        MessageType.SAFETY,
        SafetyPayload(state, companionCount, approximateLocation.trim(), note.trim()),
        priority,
        ttlMillis,
    )

    private suspend fun create(
        type: MessageType,
        payload: MessagePayload,
        priority: MessagePriority,
        ttlMillis: Long,
    ): RelayMessage {
        val now = clock.nowMillis()
        val message = RelayMessage(
            idGenerator.newId(), type, now, now + ttlMillis, priority, originDeviceId, payload,
            hopCount = 0, maxHopCount = defaultMaxHopCount, status = MessageStatus.CREATED, receivedAt = now,
            recordType = RelayRecordType.REPORT, lifetimeMs = ttlMillis, accumulatedAgeMs = 0,
            receivedElapsedRealtimeMs = clock.elapsedRealtimeMillis(), persistedAtWallClockMs = now,
            elapsedRealtimeSessionId = clock.sessionId(),
        )
        return persistCreatedMessage(repository, policy, reportSigner?.sign(message) ?: message)
    }
}

class CreateSupplyMessageUseCase(
    private val repository: MessageRepository,
    private val policy: MessagePolicy,
    private val clock: Clock,
    private val originDeviceId: String,
    private val idGenerator: MessageIdGenerator = UuidMessageIdGenerator,
    private val defaultTtlMillis: Long = 24L * 60 * 60 * 1_000,
    private val defaultMaxHopCount: Int = 8,
    private val reportSigner: ReportSigner? = null,
) {
    suspend operator fun invoke(
        kind: SupplyKind,
        requiredCount: Int,
        approximateLocation: String,
        note: String,
        otherLabel: String? = null,
        priority: MessagePriority = MessagePriority.HIGH,
        ttlMillis: Long = defaultTtlMillis,
    ): RelayMessage {
        val now = clock.nowMillis()
        val message = RelayMessage(
            idGenerator.newId(), MessageType.SUPPLY, now, now + ttlMillis, priority, originDeviceId,
            SupplyPayload(kind, requiredCount, approximateLocation.trim(), note.trim(), otherLabel?.trim()),
            hopCount = 0, maxHopCount = defaultMaxHopCount, status = MessageStatus.CREATED, receivedAt = now,
            recordType = RelayRecordType.REPORT, lifetimeMs = ttlMillis, accumulatedAgeMs = 0,
            receivedElapsedRealtimeMs = clock.elapsedRealtimeMillis(), persistedAtWallClockMs = now,
            elapsedRealtimeSessionId = clock.sessionId(),
        )
        return persistCreatedMessage(repository, policy, reportSigner?.sign(message) ?: message)
    }
}

class CreateStatusChangeUseCase(
    private val repository: MessageRepository,
    private val policy: MessagePolicy,
    private val clock: Clock,
    private val originDeviceId: String,
    private val idGenerator: MessageIdGenerator = UuidMessageIdGenerator,
) {
    suspend operator fun invoke(targetMessageId: String, newStatus: ReportStatus, reason: String): RelayMessage {
        val now = clock.nowMillis()
        val eventId = idGenerator.newId()
        val message = RelayMessage(
            messageId = eventId, messageType = MessageType.SAFETY, createdAt = now, expiresAt = now + 24L * 60 * 60 * 1_000,
            priority = MessagePriority.CRITICAL, originDeviceId = originDeviceId,
            payload = StatusChangePayload(eventId, targetMessageId, newStatus, reason.trim(), now, originDeviceId),
            hopCount = 0, maxHopCount = 8, status = MessageStatus.CREATED, receivedAt = now,
            recordType = RelayRecordType.STATUS_CHANGE, lifetimeMs = 24L * 60 * 60 * 1_000,
            receivedElapsedRealtimeMs = clock.elapsedRealtimeMillis(), persistedAtWallClockMs = now,
            elapsedRealtimeSessionId = clock.sessionId(),
        )
        return persistCreatedMessage(repository, policy, message)
    }
}

class GetRegionalMessagesUseCase(
    private val repository: MessageRepository,
    private val policy: MessagePolicy,
) {
    suspend operator fun invoke(): List<RelayMessage> = repository.all()
        .filter(policy::isActive)
        .sortedWith(
            compareByDescending<RelayMessage> { it.priority.ordinal }
                .thenByDescending { it.createdAt }
                .thenBy { it.expiresAt }
                .thenBy { it.messageId },
        )
}
