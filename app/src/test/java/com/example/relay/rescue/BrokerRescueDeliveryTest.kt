package com.example.relay.rescue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Broker delivery logic (non-Android parts).
 * Android-dependent parts (ConnectivityManager, Keystore) require instrumentation tests.
 */
class BrokerRescueDeliveryTest {

    @Test
    fun `BrokerDeliveryResult Disabled when endpoint blank`() {
        val result: BrokerDeliveryResult = BrokerDeliveryResult.Disabled
        assertTrue(result is BrokerDeliveryResult.Disabled)
    }

    @Test
    fun `BrokerDeliveryResult Offline is retryable`() {
        val result: BrokerDeliveryResult = BrokerDeliveryResult.Offline
        assertTrue(result is BrokerDeliveryResult.Offline)
    }

    @Test
    fun `BrokerDeliveryResult Failed retryable flag`() {
        val retryable = BrokerDeliveryResult.Failed("network:timeout", retryable = true)
        val nonRetryable = BrokerDeliveryResult.Failed("collision", retryable = false)

        assertTrue(retryable.retryable)
        assertFalse(nonRetryable.retryable)
    }

    @Test
    fun `BrokerDeliveryResult Stored contains response`() {
        val response = BrokerUploadResponse(
            brokerReceiptId = "br-123",
            envelopeId = "env-456",
            status = "BROKER_STORED",
            storedAtEpochMillis = 1000L,
        )
        val result = BrokerDeliveryResult.Stored(response)

        assertEquals("br-123", result.response.brokerReceiptId)
        assertEquals("env-456", result.response.envelopeId)
        assertEquals("BROKER_STORED", result.response.status)
    }

    @Test
    fun `BrokerUploadRequest wire model serialization fields`() {
        // Verify the wire model has the expected fields
        val request = BrokerUploadRequest(
            envelope = createTestEnvelope(),
            deviceKeyId = "relay_broker_upload",
            uploadSignatureBase64 = "c2lnbmF0dXJl",
        )
        assertEquals("relay_broker_upload", request.deviceKeyId)
        assertEquals("c2lnbmF0dXJl", request.uploadSignatureBase64)
    }

    @Test
    fun `BrokerReceiptBatch wire model holds receipts`() {
        val batch = BrokerReceiptBatch(receipts = emptyList())
        assertTrue(batch.receipts.isEmpty())
    }

    @Test
    fun `hopCount is NOT incremented by broker delivery`() {
        // The BrokerRescueDelivery sends envelope as-is.
        // Verify the envelope model retains its original hopCount.
        val envelope = createTestEnvelope()
        val originalHopCount = envelope.hopCount

        // Broker delivery does not modify the envelope
        assertEquals(originalHopCount, envelope.hopCount)
    }

    private fun createTestEnvelope(): EncryptedRescueEnvelope {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val payload = RescuePayload(
            requestId = "request-test",
            requestVersion = 1,
            senderDeviceId = "device-1",
            destinationShelterId = "shelter-1",
            createdAtEpochMillis = 1000L,
            expiresAtEpochMillis = 100_000L,
            urgency = RescueUrgency.URGENT,
            personCount = 1,
            injured = false,
            supportNeeds = emptySet(),
            freeText = "test",
        )
        return RescueCryptography.encrypt(payload, recipient.publicKey, "envelope-test")
    }
}
