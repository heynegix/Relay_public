package com.example.relay.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedStatusChangePolicyTest {
    @Test
    fun `status change remains a valid relay record`() {
        val clock = MutableClock(1_000)
        val policy = MessagePolicy(clock)
        val message = RelayMessage(
            messageId = "event-1",
            messageType = MessageType.SAFETY,
            createdAt = 1_000,
            expiresAt = 61_000,
            priority = MessagePriority.CRITICAL,
            originDeviceId = "origin-1",
            payload = StatusChangePayload(
                eventId = "event-1",
                targetMessageId = "report-1",
                newStatus = ReportStatus.RESOLVED,
                reason = "resolved",
                createdAt = 1_000,
                createdBy = "origin-1",
            ),
            status = MessageStatus.CREATED,
            receivedAt = 1_000,
            recordType = RelayRecordType.STATUS_CHANGE,
            lifetimeMs = 60_000,
        )

        assertEquals(MessageValidation.Valid, policy.validate(message))
    }
}
