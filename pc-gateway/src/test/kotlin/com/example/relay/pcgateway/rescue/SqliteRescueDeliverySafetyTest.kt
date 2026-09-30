package com.example.relay.pcgateway.rescue

import com.example.relay.pcgateway.GatewayJson
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescuePayload
import com.example.relay.rescue.RescueSupportNeed
import com.example.relay.rescue.RescueUrgency
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the properties that make an automatic courier retry safe: a receipt only
 * represents durable intake, and replaying the same courier delivery never counts twice.
 */
class SqliteRescueDeliverySafetyTest {
    @Test
    fun acceptedReceiptAndDeliveryIdSurviveGatewayRestart() {
        val database = Files.createTempFile("relay-rescue-", ".db")
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val envelope = RescueCryptography.encrypt(payload(), recipient.publicKey, "envelope-restart")

        try {
            SqliteRescuePersistence(database.toString(), GatewayJson).use { persistence ->
                val service = service(recipient.privateKey, signer.privateKey, persistence)
                val accepted = service.ingest(envelope, "courier-one", "delivery-stable")
                    as RescueIngestResult.Accepted
                assertTrue(RescueCryptography.verifyReceipt(accepted.request.receipt, signer.publicKey))

                val replay = service.ingest(envelope, "courier-one", "delivery-stable")
                    as RescueIngestResult.Duplicate
                assertEquals(false, replay.carrierWasNew)
                assertEquals(false, replay.deliveryWasNew)
                assertEquals(1, replay.request.uniqueCarrierCount)
                assertEquals(1, replay.request.uniqueDeliveryCount)
            }

            SqliteRescuePersistence(database.toString(), GatewayJson).use { persistence ->
                val restarted = service(recipient.privateKey, signer.privateKey, persistence)
                val restored = restarted.detail("request-restart")
                assertNotNull(restored)
                assertEquals(1, restored!!.uniqueCarrierCount)
                assertEquals(1, restored.uniqueDeliveryCount)
                assertTrue(RescueCryptography.verifyReceipt(restored.receipt, signer.publicKey))

                val sameDeliveryNewCarrier = restarted.ingest(envelope, "courier-two", "delivery-stable")
                    as RescueIngestResult.Duplicate
                assertTrue(sameDeliveryNewCarrier.carrierWasNew)
                assertEquals(false, sameDeliveryNewCarrier.deliveryWasNew)
                assertEquals(2, sameDeliveryNewCarrier.request.uniqueCarrierCount)
                assertEquals(1, sameDeliveryNewCarrier.request.uniqueDeliveryCount)
            }
        } finally {
            database.deleteIfExists()
            database.resolveSibling(database.fileName.toString() + "-wal").deleteIfExists()
            database.resolveSibling(database.fileName.toString() + "-shm").deleteIfExists()
        }
    }

    @Test
    fun ingressRejectsOversizeAndMalformedBytesBeforeIntake() {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val service = service(recipient.privateKey, signer.privateKey, InMemoryRescuePersistence())
        val ingress = RescueDeliveryIngress(service, maxEnvelopeBytes = 128)

        val oversize = ingress.ingest(ByteArray(129), "courier-one", "delivery-one")
        assertEquals(RescueRejectionCode.PAYLOAD_TOO_LARGE, (oversize as RescueIngestResult.Rejected).code)

        val malformed = ingress.ingest("not-a-rescue-envelope".encodeToByteArray(), "courier-one", "delivery-one")
        assertEquals(RescueRejectionCode.MALFORMED_SERIALIZATION, (malformed as RescueIngestResult.Rejected).code)
        assertTrue(service.list().isEmpty())
    }

    private fun service(
        recipient: com.example.relay.rescue.RescuePrivateKey,
        signer: com.example.relay.rescue.RescuePrivateKey,
        persistence: RescuePersistence,
    ) = RescueIntakeService(
        shelterId = "shelter-1",
        recipientPrivateKey = recipient,
        shelterSigningPrivateKey = signer,
        persistence = persistence,
        clock = RescueClock { 2_000L },
        idGenerator = RescueIdGenerator { "receipt-durable" },
    )

    private fun payload() = RescuePayload(
        requestId = "request-restart",
        requestVersion = 1,
        senderDeviceId = "member-1",
        destinationShelterId = "shelter-1",
        createdAtEpochMillis = 1_000L,
        expiresAtEpochMillis = 100_000L,
        urgency = RescueUrgency.IMMEDIATE,
        personCount = 2,
        injured = true,
        supportNeeds = setOf(RescueSupportNeed.RESCUE_TEAM),
    )
}
