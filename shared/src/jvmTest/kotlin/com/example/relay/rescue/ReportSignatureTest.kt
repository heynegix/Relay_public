package com.example.relay.rescue

import com.example.relay.domain.MessagePriority
import com.example.relay.domain.MessageStatus
import com.example.relay.domain.MessageType
import com.example.relay.domain.RelayMessage
import com.example.relay.domain.SafetyPayload
import com.example.relay.domain.SafetyState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportSignatureTest {
    @Test
    fun reportSignatureRoundTripAndTamperReject() {
        val message = RelayMessage(
            messageId = "report-1", messageType = MessageType.SAFETY,
            createdAt = 1_700_000_000_000, expiresAt = 1_700_000_060_000,
            priority = MessagePriority.HIGH, originDeviceId = "device-a",
            payload = SafetyPayload(SafetyState.SAFE, 1, "north", "ok"),
            status = MessageStatus.CREATED, receivedAt = 1_700_000_000_000,
            lifetimeMs = 60_000,
        )
        val keys = RescueCryptography.generateReportSigningKeyPair()
        val signed = RescueCryptography.signReport(message, keys)

        assertTrue(RescueCryptography.verifyReport(signed))
        assertTrue(
            RescueCryptography.verifyReport(
                signed.copy(hopCount = 2, status = MessageStatus.RECEIVED, receivedAt = 1_700_000_000_123),
            ),
            "transport metadata must remain mutable while immutable report content stays signed",
        )
        assertFalse(RescueCryptography.verifyReport(signed.copy(payload = SafetyPayload(SafetyState.INJURED, 1, "north", "ok"))))
    }
}
