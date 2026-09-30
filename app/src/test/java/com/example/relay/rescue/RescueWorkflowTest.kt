package com.example.relay.rescue

import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueSupportNeed
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.ShelterReceiptStatus
import com.example.relay.rescue.UnsignedShelterReceipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RescueWorkflowTest {
    @Test
    fun creatorCourierAndSignedReceiptCompleteEncryptedWorkflow() {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val memberStore = InMemoryRescueEnvelopeRepository()
        val courierStore = InMemoryRescueEnvelopeRepository()
        val draft = draft(version = 1)
        val created = RescueRequestCreator(memberStore).create(draft, recipient.publicKey, "envelope-1")
            as RescueCreationResult.Stored

        val memberTransfer = FakeRescueTransferService(memberStore)
        val packet = memberTransfer.export(RescueRequestKey("request-1", 1), 2_000)!!
        assertEquals(0, memberStore.get(RescueRequestKey("request-1", 1))!!.envelope.hopCount)
        assertEquals(true, memberTransfer.confirmExport(RescueRequestKey("request-1", 1), packet))
        assertEquals(0, memberStore.get(RescueRequestKey("request-1", 1))!!.envelope.hopCount)
        FakeRescueTransferService(courierStore).import(packet, 2_100)

        val courierItem = CourierRescuePresenter(courierStore).items().single()
        assertEquals("shelter-1", courierItem.destinationShelterId)
        assertFalse(packet.decodeToString().contains("private-note"))
        assertNotNull(created.record.envelope.ciphertextBase64)

        val envelope = courierStore.get(RescueRequestKey("request-1", 1))!!.envelope
        val receipt = RescueCryptography.signReceipt(
            UnsignedShelterReceipt(
                receiptId = "receipt-1",
                envelopeId = envelope.envelopeId,
                requestId = envelope.requestId,
                requestVersion = envelope.requestVersion,
                ciphertextSha256Hex = envelope.ciphertextSha256Hex,
                shelterId = envelope.destinationShelterId,
                receivedAtEpochMillis = 3_000,
                status = ShelterReceiptStatus.ACCEPTED,
            ),
            signer.privateKey,
        )
        val forgedReceipt = RescueCryptography.signReceipt(
            receipt.receipt,
            RescueCryptography.generateShelterSigningKeyPair().privateKey,
        )
        assertEquals(
            ReceiptApplicationResult.INVALID_SIGNATURE,
            courierStore.applyReceipt(RescueRequestKey("request-1", 1), forgedReceipt, signer.publicKey),
        )
        assertEquals(
            ReceiptApplicationResult.APPLIED,
            courierStore.applyReceipt(RescueRequestKey("request-1", 1), receipt, signer.publicKey),
        )
        assertEquals(RescueSubmissionStatus.SHELTER_ACCEPTED, CourierRescuePresenter(courierStore).items().single().submissionStatus)
        // A delayed peer ACK must not downgrade a verified shelter receipt.
        assertEquals(true, courierStore.recordSuccessfulExport(RescueRequestKey("request-1", 1), 2))
        val afterLateAck = courierStore.get(RescueRequestKey("request-1", 1))!!
        assertEquals(RescueSubmissionStatus.SHELTER_ACCEPTED, afterLateAck.state.submissionStatus)
        assertEquals(receipt, afterLateAck.state.signedReceipt)
        assertEquals(1, afterLateAck.state.submissionCount)
    }

    @Test
    fun `newer signed shelter status replaces an older receipt but cannot move backwards`() {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val store = InMemoryRescueEnvelopeRepository()
        RescueRequestCreator(store).create(draft(1), recipient.publicKey, "envelope-1")
        val envelope = store.get(RescueRequestKey("request-1", 1))!!.envelope
        val key = RescueRequestKey(envelope.requestId, envelope.requestVersion)

        fun receipt(id: String, status: ShelterReceiptStatus, receivedAt: Long) = RescueCryptography.signReceipt(
            UnsignedShelterReceipt(
                receiptId = id,
                envelopeId = envelope.envelopeId,
                requestId = envelope.requestId,
                requestVersion = envelope.requestVersion,
                ciphertextSha256Hex = envelope.ciphertextSha256Hex,
                shelterId = envelope.destinationShelterId,
                receivedAtEpochMillis = receivedAt,
                status = status,
            ),
            signer.privateKey,
        )

        assertEquals(
            ReceiptApplicationResult.APPLIED,
            store.applyReceipt(key, receipt("accepted", ShelterReceiptStatus.ACCEPTED, 3_000), signer.publicKey),
        )
        assertEquals(
            ReceiptApplicationResult.APPLIED,
            store.applyReceipt(key, receipt("responding", ShelterReceiptStatus.RESPONDING, 4_000), signer.publicKey),
        )
        assertEquals(RescueSubmissionStatus.SHELTER_RESPONDING, store.get(key)!!.state.submissionStatus)
        assertEquals(
            ReceiptApplicationResult.ALREADY_APPLIED,
            store.applyReceipt(key, receipt("late-stored", ShelterReceiptStatus.STORED, 5_000), signer.publicKey),
        )
        assertEquals(RescueSubmissionStatus.SHELTER_RESPONDING, store.get(key)!!.state.submissionStatus)
        assertEquals(
            ReceiptApplicationResult.APPLIED,
            store.applyReceipt(key, receipt("completed", ShelterReceiptStatus.COMPLETED, 6_000), signer.publicKey),
        )
        assertEquals(RescueSubmissionStatus.SHELTER_COMPLETED, store.get(key)!!.state.submissionStatus)
    }

    @Test
    fun newerVersionSupersedesOldAndExpiredDataIsNotExported() {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val store = InMemoryRescueEnvelopeRepository()
        val creator = RescueRequestCreator(store)
        creator.create(draft(1), recipient.publicKey, "envelope-1")
        creator.create(draft(2), recipient.publicKey, "envelope-2")

        assertNull(store.get(RescueRequestKey("request-1", 1)))
        assertNotNull(store.get(RescueRequestKey("request-1", 2)))
        assertNull(FakeRescueTransferService(store).export(RescueRequestKey("request-1", 2), 100_000))
    }

    @Test
    fun malformedExpiredAndFalseFramingAreRejectedWithoutEvictingValidData() {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val store = InMemoryRescueEnvelopeRepository()
        val creator = RescueRequestCreator(store)
        creator.create(draft(1), recipient.publicKey, "envelope-1")
        val transfer = FakeRescueTransferService(store)

        assertEquals(
            RescueStoreRejection.MALFORMED_PACKET,
            (transfer.import(byteArrayOf(1, 2, 3), 2_000) as RescueTransferImportResult.Rejected).reason,
        )
        val valid = store.get(RescueRequestKey("request-1", 1))!!.envelope
        assertEquals(
            RescueStoreRejection.INVALID_ENVELOPE,
            store.store(valid.copy(ciphertextSizeBytes = 16, envelopeId = "forged-envelope"), 2_000)
                .let { (it as RescueStoreResult.Rejected).reason },
        )
        assertEquals(
            RescueStoreRejection.EXPIRED,
            store.store(valid.copy(requestId = "expired-request", envelopeId = "expired-envelope"), 20_000)
                .let { (it as RescueStoreResult.Rejected).reason },
        )
        assertNotNull(store.get(RescueRequestKey("request-1", 1)))
    }

    @Test
    fun exportConfirmationRejectsPacketFromAnotherRequest() {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val store = InMemoryRescueEnvelopeRepository()
        val creator = RescueRequestCreator(store)
        creator.create(draft(1), recipient.publicKey, "envelope-1")
        creator.create(
            draft(1).copy(requestId = "request-2"),
            recipient.publicKey,
            "envelope-2",
        )
        val transfer = FakeRescueTransferService(store)
        val otherPacket = transfer.export(RescueRequestKey("request-2", 1), 2_000)!!

        assertFalse(transfer.confirmExport(RescueRequestKey("request-1", 1), otherPacket))
        assertEquals(0, store.get(RescueRequestKey("request-1", 1))!!.envelope.hopCount)
    }

    private fun draft(version: Int) = RescueRequestDraft(
        requestId = "request-1",
        requestVersion = version,
        senderDeviceId = "member-1",
        destinationShelterId = "shelter-1",
        createdAtEpochMillis = 1_000,
        expiresAtEpochMillis = 10_000,
        urgency = RescueUrgency.URGENT,
        personCount = 2,
        injured = true,
        supportNeeds = setOf(RescueSupportNeed.WATER),
        freeText = "private-note",
    )
}
