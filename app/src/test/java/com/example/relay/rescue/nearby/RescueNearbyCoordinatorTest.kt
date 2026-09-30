package com.example.relay.rescue.nearby

import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.InMemoryRescueEnvelopeRepository
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueEnvelopeRepository
import com.example.relay.rescue.RescueRequestCreator
import com.example.relay.rescue.RescueRequestDraft
import com.example.relay.rescue.RescueRequestKey
import com.example.relay.rescue.RescueStoreResult
import com.example.relay.rescue.RescueSubmissionStatus
import com.example.relay.rescue.ShelterPublicKeyProvider
import com.example.relay.rescue.ShelterPublicKeys
import com.example.relay.rescue.ShelterReceiptStatus
import com.example.relay.rescue.UnsignedShelterReceipt
import com.example.relay.rescue.RescueSupportNeed
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.StoredRescueRecord
import com.example.relay.rescue.forwardRescueEnvelope
import com.example.relay.transport.ConnectionEvent
import com.example.relay.transport.OfflineTransport
import com.example.relay.transport.OfflineTransportState
import com.example.relay.transport.Peer
import com.example.relay.transport.ReceivedPayload
import com.example.relay.transport.SendResult
import com.example.relay.transport.TransportEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueNearbyCoordinatorTest {
    @Test
    fun `inventory is metadata only and never exposes encrypted rescue plaintext`() = runTest {
        val store = InMemoryRescueEnvelopeRepository()
        createAndStore(store, freeText = "secret location and injury details")
        val transport = RecordingTransport()
        val coordinator = RescueNearbyCoordinator(store, transport, nowEpochMillis = { NOW })

        coordinator.onPeerConnected("courier-b")

        val sent = transport.sent.single()
        val packet = RescueNearbyPacketCodec().decode(sent.second)
        val inventory = packet as RescueNearbyPacket.Inventory
        assertEquals("courier-b", sent.first)
        assertEquals(1, inventory.entries.size)
        assertEquals("request-1", inventory.entries.single().requestId)
        assertFalse(sent.second.decodeToString().contains("secret location"))
        assertFalse(sent.second.decodeToString().contains("injury details"))
        assertFalse(sent.second.decodeToString().contains("ciphertextBase64"))
    }

    @Test
    fun `incoming encrypted envelope is durably stored before acknowledgement`() = runTest {
        val source = InMemoryRescueEnvelopeRepository()
        val envelope = createAndStore(source, freeText = "do not reveal")
        val transferredEnvelope = requireNotNull(forwardRescueEnvelope(envelope))
        require(envelope.expiresAtEpochMillis > System.currentTimeMillis())
        val durableStore = InMemoryRescueEnvelopeRepository()
        var ackWasSentAfterStore = false
        val transport = RecordingTransport { _, bytes ->
            val packet = RescueNearbyPacketCodec().decode(bytes)
            if (packet is RescueNearbyPacket.Ack) {
                assertNotNull(durableStore.get(packet.key))
                ackWasSentAfterStore = true
            }
            SendResult.PayloadTransferCompleted
        }
        val coordinator = RescueNearbyCoordinator(durableStore, transport, nowEpochMillis = System::currentTimeMillis)

        coordinator.handlePayload(
            "member-a",
            RescueNearbyPacketCodec().encode(RescueNearbyPacket.Envelope(transferredEnvelope)),
        )

        assertTrue(ackWasSentAfterStore)
        assertEquals(RescueSubmissionStatus.PENDING, durableStore.get(RescueRequestKey("request-1", 1))!!.state.submissionStatus)
    }

    @Test
    fun `incoming stored envelope starts onward courier delivery`() = runTest {
        val source = InMemoryRescueEnvelopeRepository()
        val transferredEnvelope = requireNotNull(forwardRescueEnvelope(createAndStore(source, CONFIDENTIAL_TEXT)))
        var onwardDeliveryStarts = 0
        val coordinator = RescueNearbyCoordinator(
            InMemoryRescueEnvelopeRepository(),
            RecordingTransport(),
            nowEpochMillis = { NOW },
            onEnvelopeStored = { onwardDeliveryStarts++ },
        )

        coordinator.handlePayload(
            SOURCE_PEER_ID,
            RescueNearbyPacketCodec().encode(RescueNearbyPacket.Envelope(transferredEnvelope)),
        )

        assertEquals(1, onwardDeliveryStarts)
    }

    @Test
    fun `incoming envelope is advertised to peers that are already connected`() = runTest {
        val source = InMemoryRescueEnvelopeRepository()
        val transferredEnvelope = requireNotNull(forwardRescueEnvelope(createAndStore(source, CONFIDENTIAL_TEXT)))
        val target = InMemoryRescueEnvelopeRepository()
        val transport = RecordingTransport().also {
            it.state.value = OfflineTransportState(connectedPeerIds = setOf(SOURCE_PEER_ID, NEXT_HOP_PEER_ID))
        }
        val coordinator = RescueNearbyCoordinator(target, transport, nowEpochMillis = { NOW })

        coordinator.handlePayload(
            SOURCE_PEER_ID,
            RescueNearbyPacketCodec().encode(RescueNearbyPacket.Envelope(transferredEnvelope)),
        )

        val nextHopPackets = transport.sent
            .filter { it.first == NEXT_HOP_PEER_ID }
            .map { RescueNearbyPacketCodec().decode(it.second) }
        assertEquals(1, nextHopPackets.size)
        assertTrue(nextHopPackets.single() is RescueNearbyPacket.Inventory)
    }

    @Test
    fun `external receipt changes are advertised without reconnecting peers`() = runTest {
        val store = InMemoryRescueEnvelopeRepository()
        createAndStore(store, CONFIDENTIAL_TEXT)
        val transport = RecordingTransport().also {
            it.state.value = OfflineTransportState(connectedPeerIds = setOf("peer-b", "peer-c"))
        }
        val coordinator = RescueNearbyCoordinator(store, transport, nowEpochMillis = { NOW })

        coordinator.onLocalStoreChanged()

        assertEquals(listOf("peer-b", "peer-c"), transport.sent.map { it.first })
        assertTrue(transport.sent.all { RescueNearbyPacketCodec().decode(it.second) is RescueNearbyPacket.Inventory })
    }

    @Test
    fun `inventory pages include records beyond the first sixty four`() = runTest {
        val now = NOW
        val store = InMemoryRescueEnvelopeRepository()
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val creator = RescueRequestCreator(store)
        repeat(65) { index ->
            val result = creator.create(
                RescueRequestDraft(
                    requestId = "request-${index.toString().padStart(3, '0')}",
                    senderDeviceId = "member-a",
                    destinationShelterId = "shelter-1",
                    createdAtEpochMillis = now - 100,
                    expiresAtEpochMillis = now + 3_600_000,
                    urgency = RescueUrgency.URGENT,
                    personCount = 1,
                ),
                recipient.publicKey,
                envelopeId = "envelope-${index.toString().padStart(3, '0')}",
            )
            assertTrue(result is com.example.relay.rescue.RescueCreationResult.Stored)
        }
        val transport = RecordingTransport()
        val coordinator = RescueNearbyCoordinator(store, transport, nowEpochMillis = { now })

        coordinator.onPeerConnected("courier-b")

        val inventories = transport.sent.map {
            RescueNearbyPacketCodec().decode(it.second) as RescueNearbyPacket.Inventory
        }
        assertEquals(listOf(64, 1), inventories.map { it.entries.size })
        assertEquals(65, inventories.flatMap { it.entries }.map { it.requestId }.distinct().size)
    }

    @Test
    fun `missing inventory entries are requested in multiple bounded pages`() = runTest {
        val now = NOW
        val transport = RecordingTransport()
        val coordinator = RescueNearbyCoordinator(
            InMemoryRescueEnvelopeRepository(),
            transport,
            nowEpochMillis = { now },
        )
        val entries = List(64) { index ->
            RescueInventoryEntry(
                requestId = "request-$index",
                requestVersion = 1,
                ciphertextSha256Hex = index.toString().padStart(64, '0'),
                expiresAtEpochMillis = now + 3_600_000,
            )
        }

        coordinator.handlePayload(
            "member-a",
            RescueNearbyPacketCodec().encode(RescueNearbyPacket.Inventory(entries)),
        )

        val requests = transport.sent.map {
            RescueNearbyPacketCodec().decode(it.second) as RescueNearbyPacket.Request
        }
        assertEquals(listOf(32, 32), requests.map { it.keys.size })
        assertEquals(64, requests.flatMap { it.keys }.distinct().size)
    }

    @Test
    fun `mesh hop advances only after matching durable peer acknowledgement`() = runTest {
        val store = InMemoryRescueEnvelopeRepository()
        val envelope = createAndStore(store, freeText = CONFIDENTIAL_TEXT)
        val transport = RecordingTransport()
        val coordinator = RescueNearbyCoordinator(store, transport, nowEpochMillis = { NOW })
        val key = RescueRequestKey("request-1", 1)

        coordinator.handlePayload(
            "courier-b",
            RescueNearbyPacketCodec().encode(RescueNearbyPacket.Request(listOf(RescueRequestKeyWire(key.requestId, key.requestVersion)))),
        )

        assertEquals(0, store.get(key)!!.envelope.hopCount)
        val sentEnvelope = RescueNearbyPacketCodec().decode(transport.sent.single().second) as RescueNearbyPacket.Envelope
        assertEquals(1, sentEnvelope.envelope.hopCount)

        coordinator.handlePayload(
            "courier-b",
            RescueNearbyPacketCodec().encode(
                RescueNearbyPacket.Ack(key, envelope.envelopeId, envelope.ciphertextSha256Hex, exportedHopCount = 1),
            ),
        )

        assertEquals(0, store.get(key)!!.envelope.hopCount)
        assertEquals(RescueSubmissionStatus.IN_TRANSIT, store.get(key)!!.state.submissionStatus)
    }

    @Test
    fun `signed shelter status returns through another nearby device`() = runTest {
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val source = InMemoryRescueEnvelopeRepository()
        val target = InMemoryRescueEnvelopeRepository()
        val envelope = (RescueRequestCreator(source).create(
            RescueRequestDraft(
                requestId = "request-status",
                senderDeviceId = "member-a",
                destinationShelterId = "shelter-1",
                createdAtEpochMillis = NOW - 100,
                expiresAtEpochMillis = NOW + 3_600_000,
                urgency = RescueUrgency.URGENT,
                personCount = 1,
                supportNeeds = setOf(RescueSupportNeed.WATER),
            ),
            recipient.publicKey,
            envelopeId = "envelope-status",
        ) as com.example.relay.rescue.RescueCreationResult.Stored).record.envelope
        assertTrue(target.store(envelope, NOW) is RescueStoreResult.Stored)
        val key = RescueRequestKey(envelope.requestId, envelope.requestVersion)
        val signed = RescueCryptography.signReceipt(
            UnsignedShelterReceipt(
                receiptId = "receipt-responding",
                envelopeId = envelope.envelopeId,
                requestId = envelope.requestId,
                requestVersion = envelope.requestVersion,
                ciphertextSha256Hex = envelope.ciphertextSha256Hex,
                shelterId = envelope.destinationShelterId,
                receivedAtEpochMillis = NOW,
                status = ShelterReceiptStatus.RESPONDING,
            ),
            signer.privateKey,
        )
        assertEquals(
            com.example.relay.rescue.ReceiptApplicationResult.APPLIED,
            source.applyReceipt(key, signed, signer.publicKey),
        )
        val sourceTransport = RecordingTransport()
        val targetTransport = RecordingTransport()
        val sourceCoordinator = RescueNearbyCoordinator(source, sourceTransport, nowEpochMillis = { NOW })
        val targetCoordinator = RescueNearbyCoordinator(
            target,
            targetTransport,
            nowEpochMillis = { NOW },
            shelterKeyProvider = ShelterPublicKeyProvider {
                ShelterPublicKeys("shelter-1", recipient.publicKey, signer.publicKey)
            },
        )

        sourceCoordinator.onPeerConnected("target")
        targetCoordinator.handlePayload(SOURCE_PEER_ID, sourceTransport.sent.single().second)
        sourceCoordinator.handlePayload("target", targetTransport.sent.single().second)
        targetCoordinator.handlePayload(SOURCE_PEER_ID, sourceTransport.sent.last().second)

        assertEquals(RescueSubmissionStatus.SHELTER_RESPONDING, target.get(key)!!.state.submissionStatus)
    }

    private fun createAndStore(store: InMemoryRescueEnvelopeRepository, freeText: String): EncryptedRescueEnvelope {
        val keyPair = RescueCryptography.generateRecipientKeyPair()
        val result = RescueRequestCreator(store).create(
            RescueRequestDraft(
                requestId = "request-1",
                senderDeviceId = "member-a",
                destinationShelterId = "shelter-1",
                createdAtEpochMillis = NOW - 100,
                expiresAtEpochMillis = NOW + 3_600_000,
                urgency = RescueUrgency.URGENT,
                personCount = 2,
                supportNeeds = setOf(RescueSupportNeed.WATER),
                freeText = freeText,
            ),
            keyPair.publicKey,
            envelopeId = "envelope-1",
        ) as com.example.relay.rescue.RescueCreationResult.Stored
        return result.record.envelope
    }

    private companion object {
        const val CONFIDENTIAL_TEXT = "confidential"
        const val SOURCE_PEER_ID = "source"
        const val NEXT_HOP_PEER_ID = "next-hop"
        val NOW: Long
            get() = System.currentTimeMillis()
    }
}

private class RecordingTransport(
    private val sendResult: suspend (String, ByteArray) -> SendResult = { _, _ -> SendResult.PayloadTransferCompleted },
) : OfflineTransport {
    override val state = MutableStateFlow(OfflineTransportState())
    override val discoveredPeers: Flow<List<Peer>> = emptyFlow()
    override val connectionEvents: Flow<ConnectionEvent> = emptyFlow()
    override val receivedPayloads: Flow<ReceivedPayload> = emptyFlow()
    override val transportEvents: Flow<TransportEvent> = emptyFlow()
    val sent = mutableListOf<Pair<String, ByteArray>>()

    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override suspend fun connect(peerId: String) = Unit
    override suspend fun acceptConnection(peerId: String) = Unit
    override suspend fun rejectConnection(peerId: String) = Unit
    override suspend fun disconnect(peerId: String) = Unit
    override suspend fun send(peerId: String, payload: ByteArray): SendResult {
        sent += peerId to payload
        return sendResult(peerId, payload)
    }
}
