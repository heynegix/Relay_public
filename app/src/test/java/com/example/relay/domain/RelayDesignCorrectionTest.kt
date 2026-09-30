package com.example.relay.domain

import com.example.relay.NOW
import com.example.relay.message
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayDesignCorrectionTest {
    @Test
    fun `device roles retain existing roles and allow planned relay roles`() {
        val roles = DeviceRole.entries.toSet()
        assertTrue(DeviceRole.MEMBER in roles)
        assertTrue(DeviceRole.GATEWAY in roles)
        assertTrue(DeviceRole.COURIER in roles)
        assertTrue(DeviceRole.RELAY in roles)
        assertTrue(DeviceRole.ADMIN in roles)
    }

    @Test
    fun `age is accumulated before transfer and reboot fallback is conservative`() {
        val clock = MutableClock(NOW)
        val policy = MessagePolicy(clock)
        val local = message(expiresAt = NOW + 1_000).copy(
            lifetimeMs = 1_000,
            receivedElapsedRealtimeMs = NOW,
            persistedAtWallClockMs = NOW,
        )
        clock.currentElapsedRealtimeMillis = NOW + 400
        clock.currentMillis = NOW + 400
        assertEquals(400, policy.prepareForTransfer(local)!!.accumulatedAgeMs)

        val rebooted = local.copy(receivedElapsedRealtimeMs = NOW + 9_999, persistedAtWallClockMs = NOW + 500)
        clock.currentElapsedRealtimeMillis = NOW + 20_000 // a later reboot may still exceed an old elapsed baseline
        clock.currentSessionId = "new-boot-session"
        clock.currentMillis = NOW + 400 // wall clock moved backwards relative to persisted marker
        assertFalse(policy.isActive(rebooted))
    }

    @Test
    fun `receipts derive gateway state and are deduplicated`() = runBlocking {
        val repository = InMemoryMessageRepository()
        repository.insert(message())
        val peer = DeliveryReceipt("receipt-peer", "message-1", ReceiptType.PEER_RECEIVED, "device-B", NOW)
        assertEquals(InsertResult.Inserted, repository.insertReceipt(peer))
        assertEquals(InsertResult.Duplicate, repository.insertReceipt(peer))
        assertEquals(DeliveryPresentation.PEER_RECEIVED, deriveDeliveryPresentation(repository.receiptsFor("message-1")))
        repository.insertReceipt(DeliveryReceipt("receipt-gateway", "message-1", ReceiptType.GATEWAY_RECEIVED, "device-C", NOW))
        assertEquals(DeliveryPresentation.GATEWAY_RECEIVED, deriveDeliveryPresentation(repository.receiptsFor("message-1")))
    }

    @Test
    fun `payload transfer completion is not peer acknowledgement or gateway receipt`() {
        assertEquals(DeliveryPresentation.NEARBY_PAYLOAD_COMPLETE, deriveDeliveryPresentation(emptyList(), payloadTransferred = true))
        val peer = DeliveryReceipt("peer", "message-1", ReceiptType.PEER_RECEIVED, "device-B", NOW)
        assertEquals(DeliveryPresentation.PEER_RECEIVED, deriveDeliveryPresentation(listOf(peer), payloadTransferred = true))
        val gateway = DeliveryReceipt("gateway", "message-1", ReceiptType.GATEWAY_RECEIVED, "device-C", NOW)
        assertEquals(DeliveryPresentation.GATEWAY_RECEIVED, deriveDeliveryPresentation(listOf(peer, gateway), payloadTransferred = true))
    }

    @Test
    fun `unverified gateway ranks above peer and below verified gateway`() {
        val peer = DeliveryReceipt("peer", "message-1", ReceiptType.PEER_RECEIVED, "device-B", NOW)
        val unverified = DeliveryReceipt(
            "unverified",
            "message-1",
            ReceiptType.GATEWAY_RECEIVED_UNVERIFIED,
            "pc-hub",
            NOW,
        )
        val verified = DeliveryReceipt("verified", "message-1", ReceiptType.GATEWAY_RECEIVED, "pc-hub", NOW)
        assertEquals(
            DeliveryPresentation.GATEWAY_RECEIVED_UNVERIFIED,
            deriveDeliveryPresentation(listOf(peer, unverified)),
        )
        assertEquals(
            DeliveryPresentation.GATEWAY_RECEIVED,
            deriveDeliveryPresentation(listOf(peer, unverified, verified)),
        )
    }

    @Test
    fun `delivery labels never claim official final delivery or verified content`() {
        val unverified = deliveryPresentationLabel(DeliveryPresentation.GATEWAY_RECEIVED_UNVERIFIED)
        val authenticatedRoute = deliveryPresentationLabel(DeliveryPresentation.GATEWAY_RECEIVED)
        val peer = deliveryPresentationLabel(DeliveryPresentation.PEER_RECEIVED)
        assertTrue(unverified.contains("未認証") || unverified.contains("未検証"))
        assertTrue(authenticatedRoute.contains("未検証") || authenticatedRoute.contains("認証経路"))
        assertFalse(unverified.contains("公式"))
        assertFalse(authenticatedRoute.contains("最終配信完了"))
        assertTrue(peer.contains("最終配信ではありません"))
        assertEquals("転送待ち", deliveryPresentationLabel(DeliveryPresentation.NOT_CONFIRMED))
    }

    @Test
    fun `prepareForGatewayUpload allows hop exhausted active reports`() {
        val clock = MutableClock(NOW)
        val policy = MessagePolicy(clock)
        val exhausted = message(hopCount = 8, maxHopCount = 8).copy(
            lifetimeMs = 60_000,
            receivedElapsedRealtimeMs = NOW,
            persistedAtWallClockMs = NOW,
        )
        assertTrue(policy.prepareForTransfer(exhausted) == null)
        assertTrue(policy.prepareForGatewayUpload(exhausted) != null)
    }

    @Test
    fun `resource policy rejects new records without removing existing records`() = runBlocking {
        val repository = InMemoryMessageRepository(ResourcePolicy(maxStoredMessages = 1, maxStoredMessagesPerOrigin = 1))
        assertEquals(InsertResult.Inserted, repository.insert(message(id = "one")))
        assertTrue(repository.insert(message(id = "two")) is InsertResult.Rejected)
        assertEquals(1, repository.all().size)
    }
}
