package com.example.relay.pcgateway.rescue

import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescuePayload
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.RescueSupportNeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerPullAgentTest {
    @Test
    fun `ingest via broker carrier marks carrier as broker`() {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val service = RescueIntakeService(
            shelterId = "shelter-1",
            recipientPrivateKey = recipient.privateKey,
            shelterSigningPrivateKey = signer.privateKey,
            clock = RescueClock { 2_000 },
        )
        val envelope = RescueCryptography.encrypt(payload(1), recipient.publicKey, "envelope-1")

        val result = service.ingest(
            envelope = envelope,
            carrierId = "broker",
            courierDeliveryId = "broker:${envelope.envelopeId}",
        )

        assertTrue(result is RescueIngestResult.Accepted)
        val accepted = result as RescueIngestResult.Accepted
        assertTrue("broker" in accepted.request.carrierIds)
        assertTrue("broker:${envelope.envelopeId}" in accepted.request.deliveryIds)
    }

    @Test
    fun `duplicate broker delivery is idempotent`() {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val service = RescueIntakeService(
            shelterId = "shelter-1",
            recipientPrivateKey = recipient.privateKey,
            shelterSigningPrivateKey = signer.privateKey,
            clock = RescueClock { 2_000 },
        )
        val envelope = RescueCryptography.encrypt(payload(1), recipient.publicKey, "envelope-1")

        service.ingest(envelope, "broker", "broker:${envelope.envelopeId}")
        val duplicate = service.ingest(envelope, "broker", "broker:${envelope.envelopeId}")

        assertTrue(duplicate is RescueIngestResult.Duplicate)
        val dup = duplicate as RescueIngestResult.Duplicate
        // Same carrier and delivery ID: no new info
        assertEquals(false, dup.carrierWasNew)
        assertEquals(false, dup.deliveryWasNew)
    }

    @Test
    fun `broker and BLE carrier can both deliver same envelope`() {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val service = RescueIntakeService(
            shelterId = "shelter-1",
            recipientPrivateKey = recipient.privateKey,
            shelterSigningPrivateKey = signer.privateKey,
            clock = RescueClock { 2_000 },
        )
        val envelope = RescueCryptography.encrypt(payload(1), recipient.publicKey, "envelope-1")

        service.ingest(envelope, "broker", "broker:${envelope.envelopeId}")
        val bleDuplicate = service.ingest(envelope, "ble-courier-1", "ble:${envelope.envelopeId}")

        assertTrue(bleDuplicate is RescueIngestResult.Duplicate)
        val dup = bleDuplicate as RescueIngestResult.Duplicate
        assertEquals(true, dup.carrierWasNew)
        assertEquals(true, dup.deliveryWasNew)
        assertEquals(2, dup.request.uniqueCarrierCount)
    }

    @Test
    fun `expired envelope from broker is rejected`() {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val service = RescueIntakeService(
            shelterId = "shelter-1",
            recipientPrivateKey = recipient.privateKey,
            shelterSigningPrivateKey = signer.privateKey,
            clock = RescueClock { 200_000 }, // past expiry
        )
        val envelope = RescueCryptography.encrypt(payload(1), recipient.publicKey, "envelope-1")

        val result = service.ingest(envelope, "broker", "broker:${envelope.envelopeId}")

        assertTrue(result is RescueIngestResult.Rejected)
        assertEquals(
            RescueRejectionCode.EXPIRED,
            (result as RescueIngestResult.Rejected).code,
        )
    }

    private fun payload(version: Int) = RescuePayload(
        requestId = "request-1",
        requestVersion = version,
        senderDeviceId = "member-1",
        destinationShelterId = "shelter-1",
        createdAtEpochMillis = 1_000,
        expiresAtEpochMillis = 100_000,
        urgency = RescueUrgency.IMMEDIATE,
        personCount = 2,
        injured = true,
        supportNeeds = setOf(RescueSupportNeed.RESCUE_TEAM),
        freeText = "help",
    )
}
