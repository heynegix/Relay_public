package com.example.relay.domain

interface RelayClock {
    fun nowMillis(): Long
    fun elapsedRealtimeMillis(): Long
    fun sessionId(): String
}

typealias Clock = RelayClock

expect fun defaultRelayClock(): RelayClock

class SystemRelayClock(
    private val session: String = randomUuid(),
) : RelayClock {
    override fun nowMillis(): Long = currentTimeMillis()
    override fun elapsedRealtimeMillis(): Long = monotonicMillis()
    override fun sessionId(): String = session
}

object SystemClock : RelayClock {
    private val delegate = SystemRelayClock()
    override fun nowMillis(): Long = delegate.nowMillis()
    override fun elapsedRealtimeMillis(): Long = delegate.elapsedRealtimeMillis()
    override fun sessionId(): String = delegate.sessionId()
}

class MutableClock(initialMillis: Long) : RelayClock {
    var currentMillis: Long = initialMillis
    var currentElapsedRealtimeMillis: Long = initialMillis
    var currentSessionId: String = "test-session"

    override fun nowMillis(): Long = currentMillis
    override fun elapsedRealtimeMillis(): Long = currentElapsedRealtimeMillis
    override fun sessionId(): String = currentSessionId
}

class MutableRelayClock(
    var currentMillis: Long,
    var currentElapsed: Long = currentMillis,
    var session: String = "test-session",
) : RelayClock {
    override fun nowMillis(): Long = currentMillis
    override fun elapsedRealtimeMillis(): Long = currentElapsed
    override fun sessionId(): String = session
}

expect fun currentTimeMillis(): Long
expect fun monotonicMillis(): Long
expect fun randomUuid(): String

data class MessageLimits(
    val maxTtlMillis: Long = 7L * 24 * 60 * 60 * 1_000,
    val clockSkewToleranceMillis: Long = 5L * 60 * 1_000,
    val maxFutureCreatedAtMillis: Long = 24L * 60 * 60 * 1_000,
    val maxHopCount: Int = 16,
    val maxLocationChars: Int = 100,
    val maxNoteChars: Int = 280,
    val maxOtherLabelChars: Int = 50,
)

sealed interface MessageValidation {
    data object Valid : MessageValidation
    data class Invalid(val reason: String) : MessageValidation
}

class MessagePolicy(
    private val clock: RelayClock,
    val limits: MessageLimits = MessageLimits(),
) {
    fun validate(message: RelayMessage): MessageValidation {
        if (message.messageId.isEmpty() || message.messageId.length > 64 ||
            message.originDeviceId.isEmpty() || message.originDeviceId.length > 64
        ) {
            return MessageValidation.Invalid("invalid identifier length")
        }
        if (message.createdAt < 0 || message.lifetimeMs !in 1..limits.maxTtlMillis) {
            return MessageValidation.Invalid("invalid lifetime")
        }
        if (message.accumulatedAgeMs !in 0..message.lifetimeMs) {
            return MessageValidation.Invalid("invalid accumulated age")
        }
        if (message.expiresAt < message.createdAt) {
            return MessageValidation.Invalid("legacy expiry precedes creation")
        }
        if (message.createdAt > clock.nowMillis() + limits.maxFutureCreatedAtMillis) {
            return MessageValidation.Invalid("creation time is too far in the future")
        }
        if (message.hopCount < 0 || message.maxHopCount !in 1..limits.maxHopCount ||
            message.hopCount > message.maxHopCount
        ) {
            return MessageValidation.Invalid("invalid hop count")
        }
        return validatePayload(message)
    }

    fun effectiveAgeMs(message: RelayMessage): Long {
        val base = message.accumulatedAgeMs.coerceIn(0, message.lifetimeMs.coerceAtLeast(0))
        val residence = when {
            message.elapsedRealtimeSessionId == clock.sessionId() &&
                clock.elapsedRealtimeMillis() >= message.receivedElapsedRealtimeMs &&
                message.receivedElapsedRealtimeMs > 0 ->
                clock.elapsedRealtimeMillis() - message.receivedElapsedRealtimeMs
            clock.nowMillis() >= message.persistedAtWallClockMs && message.persistedAtWallClockMs > 0 ->
                clock.nowMillis() - message.persistedAtWallClockMs
            else -> message.lifetimeMs
        }
        return (base + residence.coerceAtLeast(0)).coerceAtMost(message.lifetimeMs)
    }

    fun isActive(message: RelayMessage): Boolean =
        validate(message) is MessageValidation.Valid && effectiveAgeMs(message) < message.lifetimeMs

    fun canForward(message: RelayMessage): Boolean =
        isActive(message) && message.hopCount < message.maxHopCount

    fun prepareForTransfer(message: RelayMessage): RelayMessage? {
        if (!canForward(message)) return null
        return message.copy(
            accumulatedAgeMs = effectiveAgeMs(message),
            receivedElapsedRealtimeMs = clock.elapsedRealtimeMillis(),
            persistedAtWallClockMs = clock.nowMillis(),
            elapsedRealtimeSessionId = clock.sessionId(),
        )
    }

    fun prepareForGatewayUpload(message: RelayMessage): RelayMessage? {
        if (!isActive(message)) return null
        return message.copy(
            accumulatedAgeMs = effectiveAgeMs(message),
            receivedElapsedRealtimeMs = clock.elapsedRealtimeMillis(),
            persistedAtWallClockMs = clock.nowMillis(),
            elapsedRealtimeSessionId = clock.sessionId(),
        )
    }

    fun receive(message: RelayMessage): RelayMessage? {
        if (!canForward(message)) return null
        val next = message.copy(
            hopCount = (message.hopCount + 1).coerceAtMost(message.maxHopCount),
            status = MessageStatus.RECEIVED,
            receivedAt = clock.nowMillis(),
            receivedElapsedRealtimeMs = clock.elapsedRealtimeMillis(),
            persistedAtWallClockMs = clock.nowMillis(),
            elapsedRealtimeSessionId = clock.sessionId(),
        )
        return if (isActive(next)) next else null
    }

    private fun validatePayload(message: RelayMessage): MessageValidation {
        val p = message.payload
        if (message.recordType == RelayRecordType.REPORT && message.messageType == MessageType.SAFETY && p !is SafetyPayload) {
            return MessageValidation.Invalid("message type and payload disagree")
        }
        if (message.recordType == RelayRecordType.REPORT && message.messageType == MessageType.SUPPLY && p !is SupplyPayload) {
            return MessageValidation.Invalid("message type and payload disagree")
        }
        return when (p) {
        is SafetyPayload -> {
            if (p.companionCount !in 0..99) MessageValidation.Invalid("invalid companion count")
            else if (p.approximateLocation.length > limits.maxLocationChars || p.note.length > limits.maxNoteChars) {
                MessageValidation.Invalid("text too long")
            } else {
                MessageValidation.Valid
            }
        }
        is SupplyPayload -> {
            if (p.requiredCount !in 1..9999) MessageValidation.Invalid("invalid required count")
            else if (p.approximateLocation.length > limits.maxLocationChars || p.note.length > limits.maxNoteChars) {
                MessageValidation.Invalid("text too long")
            } else if (p.otherLabel != null && p.otherLabel.length > limits.maxOtherLabelChars) {
                MessageValidation.Invalid("other label too long")
            } else if (p.kind == SupplyKind.OTHER && p.otherLabel.isNullOrBlank()) {
                MessageValidation.Invalid("other label required")
            } else {
                MessageValidation.Valid
            }
        }
        is StatusChangePayload -> {
            if (message.recordType != RelayRecordType.STATUS_CHANGE) {
                MessageValidation.Invalid("status change record type required")
            } else if (
                p.eventId.length !in 1..64 ||
                p.targetMessageId.length !in 1..64 ||
                p.createdBy.length !in 1..64 ||
                p.reason.length > limits.maxNoteChars ||
                p.createdAt < 0
            ) {
                MessageValidation.Invalid("invalid status change")
            } else {
                MessageValidation.Valid
            }
        }
        }
    }
}

sealed class MessageCreationException(message: String) : Exception(message) {
    class InvalidMessage(reason: String) : MessageCreationException(reason)
}

object MessageFactory {
    fun createSafety(
        state: SafetyState,
        companionCount: Int,
        location: String,
        note: String,
        originDeviceId: String,
        clock: RelayClock,
        policy: MessagePolicy,
        priority: MessagePriority = MessagePriority.HIGH,
        ttlMillis: Long = 24L * 60 * 60 * 1_000,
        maxHopCount: Int = 8,
        messageId: String = randomUuid(),
    ): RelayMessage {
        val now = clock.nowMillis()
        val message = RelayMessage(
            messageId = messageId,
            messageType = MessageType.SAFETY,
            createdAt = now,
            expiresAt = now + ttlMillis,
            priority = priority,
            originDeviceId = originDeviceId,
            payload = SafetyPayload(state, companionCount, location.trim(), note.trim()),
            hopCount = 0,
            maxHopCount = maxHopCount,
            status = MessageStatus.CREATED,
            receivedAt = now,
            lifetimeMs = ttlMillis,
            accumulatedAgeMs = 0,
            receivedElapsedRealtimeMs = clock.elapsedRealtimeMillis(),
            persistedAtWallClockMs = now,
            elapsedRealtimeSessionId = clock.sessionId(),
        )
        when (val v = policy.validate(message)) {
            MessageValidation.Valid -> return message
            is MessageValidation.Invalid -> throw MessageCreationException.InvalidMessage(v.reason)
        }
    }

    fun createSupply(
        kind: SupplyKind,
        requiredCount: Int,
        location: String,
        note: String,
        otherLabel: String?,
        originDeviceId: String,
        clock: RelayClock,
        policy: MessagePolicy,
        priority: MessagePriority = MessagePriority.CRITICAL,
        ttlMillis: Long = 24L * 60 * 60 * 1_000,
        maxHopCount: Int = 8,
        messageId: String = randomUuid(),
    ): RelayMessage {
        val now = clock.nowMillis()
        val message = RelayMessage(
            messageId = messageId,
            messageType = MessageType.SUPPLY,
            createdAt = now,
            expiresAt = now + ttlMillis,
            priority = priority,
            originDeviceId = originDeviceId,
            payload = SupplyPayload(kind, requiredCount, location.trim(), note.trim(), otherLabel),
            hopCount = 0,
            maxHopCount = maxHopCount,
            status = MessageStatus.CREATED,
            receivedAt = now,
            lifetimeMs = ttlMillis,
            accumulatedAgeMs = 0,
            receivedElapsedRealtimeMs = clock.elapsedRealtimeMillis(),
            persistedAtWallClockMs = now,
            elapsedRealtimeSessionId = clock.sessionId(),
        )
        when (val v = policy.validate(message)) {
            MessageValidation.Valid -> return message
            is MessageValidation.Invalid -> throw MessageCreationException.InvalidMessage(v.reason)
        }
    }
}
