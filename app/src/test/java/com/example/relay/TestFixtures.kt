package com.example.relay

import com.example.relay.domain.MessagePriority
import com.example.relay.domain.MessageStatus
import com.example.relay.domain.MessageType
import com.example.relay.domain.RelayMessage
import com.example.relay.domain.SafetyPayload
import com.example.relay.domain.SafetyState

const val NOW = 1_700_000_000_000L

fun message(
    id: String = "message-1",
    priority: MessagePriority = MessagePriority.NORMAL,
    createdAt: Long = NOW,
    expiresAt: Long = NOW + 60_000,
    hopCount: Int = 0,
    maxHopCount: Int = 8,
    note: String = "ok",
): RelayMessage = RelayMessage(
    messageId = id,
    messageType = MessageType.SAFETY,
    createdAt = createdAt,
    expiresAt = expiresAt,
    priority = priority,
    originDeviceId = "device-A",
    payload = SafetyPayload(SafetyState.SAFE, 1, "north area", note),
    hopCount = hopCount,
    maxHopCount = maxHopCount,
    status = MessageStatus.CREATED,
    receivedAt = createdAt,
    lifetimeMs = (expiresAt - createdAt).coerceAtLeast(1),
    accumulatedAgeMs = 0,
    receivedElapsedRealtimeMs = createdAt,
    persistedAtWallClockMs = createdAt,
    elapsedRealtimeSessionId = "test-session",
)
