package com.example.relay.rescue.ble

import com.example.relay.rescue.DirectoryAcceptance
import com.example.relay.rescue.InMemoryRescueEnvelopeRepository
import com.example.relay.rescue.RegionalRootBundle
import com.example.relay.rescue.RegionalShelterDirectoryResolver
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueRequestCreator
import com.example.relay.rescue.RescueRequestDraft
import com.example.relay.rescue.RescueRequestKey
import com.example.relay.rescue.RescueSupportNeed
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.RescueCreationResult
import com.example.relay.rescue.ReceiptApplicationResult
import com.example.relay.rescue.ShelterPublicKeyManifest
import com.example.relay.rescue.SignedShelterReceipt
import com.example.relay.rescue.ShelterReceiptStatus
import com.example.relay.rescue.UnsignedRegionalShelterDirectory
import com.example.relay.rescue.UnsignedShelterReceipt
import com.example.relay.rescue.beaconFingerprintBytes
import com.example.relay.rescue.signRegionalShelterDirectory
import com.example.relay.rescue.signShelterManifest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShelterDeliveryFairnessTest {
    @Test
    fun `unchanged receipt refresh yields to another pending receipt`() = runTest {
        val now = System.currentTimeMillis()
        val rootKeys = RescueCryptography.generateShelterSigningKeyPair()
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val root = RegionalRootBundle(regionId = "test-region", rootSigningPublicKey = rootKeys.publicKey)
        val manifest = signShelterManifest(
            root.regionId,
            ShelterPublicKeyManifest(
                shelterId = "test-shelter", recipientPublicKey = recipient.publicKey,
                receiptSigningPublicKey = signer.publicKey, validFromEpochMillis = now - 1000,
                validUntilEpochMillis = now + 3_600_000, generation = 1,
            ),
            rootKeys.privateKey,
        )
        val resolver = RegionalShelterDirectoryResolver(listOf(root))
        assertTrue(resolver.accept(signRegionalShelterDirectory(
            UnsignedRegionalShelterDirectory(
                regionId = root.regionId, generation = 1, issuedAtEpochMillis = now - 1000,
                validUntilEpochMillis = now + 3_600_000, shelters = listOf(manifest),
            ),
            rootKeys.privateKey,
        ), now) is DirectoryAcceptance.Accepted)
        val repository = InMemoryRescueEnvelopeRepository()
        val receipts = (1..2).associate { index ->
            val created = RescueRequestCreator(repository).create(
                RescueRequestDraft(
                    requestId = "request-$index", senderDeviceId = "sender", destinationShelterId = "test-shelter",
                    createdAtEpochMillis = now - 100 + index, expiresAtEpochMillis = now + 3_600_000,
                    urgency = RescueUrgency.URGENT, personCount = 1,
                    supportNeeds = setOf(RescueSupportNeed.WATER), freeText = "",
                ), recipient.publicKey, envelopeId = "envelope-$index",
            ) as RescueCreationResult.Stored
            val envelope = created.record.envelope
            val receipt = RescueCryptography.signReceipt(UnsignedShelterReceipt(
                receiptId = "receipt-$index", envelopeId = envelope.envelopeId, requestId = envelope.requestId,
                requestVersion = 1, ciphertextSha256Hex = envelope.ciphertextSha256Hex, shelterId = "test-shelter",
                receivedAtEpochMillis = now, status = ShelterReceiptStatus.STORED,
            ), signer.privateKey)
            assertEquals(
                ReceiptApplicationResult.APPLIED,
                repository.applyReceipt(created.record.key, receipt, signer.publicKey),
            )
            envelope.requestId to receipt
        }
        val identity = ShelterBleIdentity(2, manifest.beaconFingerprintBytes().copyOf(9))
        val advertisements = MutableSharedFlow<ShelterAdvertisement>()
        val submitted = mutableListOf<String>()
        val client = receiptClient(advertisements, identity, receipts, submitted)
        val coordinator = ShelterDeliveryCoordinator(client, repository, resolver, "carrier", ids(), clock = { now })
        coordinator.start(backgroundScope)
        runCurrent()
        repeat(3) {
            advertisements.emit(ShelterAdvertisement("test-shelter", "peer", identity))
            runCurrent()
        }
        assertEquals(listOf("request-1", "request-2"), submitted)
        coordinator.stop()
    }

    @Test
    fun `stopping a stalled BLE connect keeps the state idle`() = runTest {
        val advertisements = MutableSharedFlow<ShelterAdvertisement>()
        val client = FakeShelterBleClient(advertisements) { awaitCancellation() }
        val coordinator = ShelterDeliveryCoordinator(
            client, InMemoryRescueEnvelopeRepository(), RegionalShelterDirectoryResolver(emptyList()), "carrier", ids(),
        )
        coordinator.start(backgroundScope)
        runCurrent()
        advertisements.emit(ShelterAdvertisement("shelter", "peer"))
        runCurrent()
        coordinator.stop()
        runCurrent()
        assertEquals(ShelterDeliveryState.Idle, coordinator.state.value)
    }

    private fun receiptClient(
        advertisements: MutableSharedFlow<ShelterAdvertisement>,
        identity: ShelterBleIdentity,
        receipts: Map<String, SignedShelterReceipt>,
        submitted: MutableList<String>,
    ) = FakeShelterBleClient(advertisements) {
        object : ShelterBleSession {
            override suspend fun readIdentity() = identity
            override suspend fun submit(submission: RescueBleSubmission): RescueBleSubmissionResult {
                submitted += submission.courierDeliveryId
                return RescueBleSubmissionResult.Duplicate(receipts.getValue(submission.courierDeliveryId))
            }
            override fun close() = Unit
        }
    }

    private fun ids() = object : CourierDeliveryIdStore {
        override fun idFor(key: RescueRequestKey) = key.requestId
        override fun remove(key: RescueRequestKey) = Unit
    }
}
