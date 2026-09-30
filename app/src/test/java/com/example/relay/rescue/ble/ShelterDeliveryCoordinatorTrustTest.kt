package com.example.relay.rescue.ble

import com.example.relay.rescue.DirectoryAcceptance
import com.example.relay.rescue.InMemoryRescueEnvelopeRepository
import com.example.relay.rescue.RegionalRootBundle
import com.example.relay.rescue.RegionalShelterDirectoryResolver
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.ShelterPublicKeyManifest
import com.example.relay.rescue.UnsignedRegionalShelterDirectory
import com.example.relay.rescue.beaconFingerprintBytes
import com.example.relay.rescue.signRegionalShelterDirectory
import com.example.relay.rescue.signShelterManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TEST ONLY: trust keys are generated in memory and are never written to an APK asset. */
class ShelterDeliveryCoordinatorTrustTest {
    @Test
    fun `BLE advertisement and GATT fingerprint mismatch is rejected before submission`() = runBlocking {
        val rootPair = RescueCryptography.generateShelterSigningKeyPair()
        val root = RegionalRootBundle(regionId = "test-region", rootSigningPublicKey = rootPair.publicKey)
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val receipt = RescueCryptography.generateShelterSigningKeyPair()
        val manifest = signShelterManifest(
            root.regionId,
            ShelterPublicKeyManifest(
                shelterId = "test-shelter",
                recipientPublicKey = recipient.publicKey,
                receiptSigningPublicKey = receipt.publicKey,
                validFromEpochMillis = 1_000,
                validUntilEpochMillis = 2_000,
                generation = 1,
            ),
            rootPair.privateKey,
        )
        val directory = signRegionalShelterDirectory(
            UnsignedRegionalShelterDirectory(
                regionId = root.regionId,
                generation = 1,
                issuedAtEpochMillis = 1_000,
                validUntilEpochMillis = 2_000,
                shelters = listOf(manifest),
            ),
            rootPair.privateKey,
        )
        val resolver = RegionalShelterDirectoryResolver(listOf(root))
        assertTrue(resolver.accept(directory, NOW) is DirectoryAcceptance.Accepted)

        val advertised = ShelterBleIdentity(
            protocolVersion = ShelterBleIdentity.PROTOCOL_VERSION,
            signedManifestFingerprint = manifest.beaconFingerprintBytes()
                .copyOf(ShelterBleIdentity.MANIFEST_FINGERPRINT_BYTES),
        )
        val changedAfterConnection = ShelterBleIdentity(
            protocolVersion = ShelterBleIdentity.PROTOCOL_VERSION,
            signedManifestFingerprint = ByteArray(ShelterBleIdentity.MANIFEST_FINGERPRINT_BYTES) { 0x5a.toByte() },
        )
        assertFalse(advertised.sameWireIdentity(changedAfterConnection))
        val session = MismatchedIdentitySession(changedAfterConnection)
        val client = FakeShelterBleClient(
            advertisements = flowOf(ShelterAdvertisement(shelterId = "", peerId = "test-peer", identity = advertised)),
            sessionFactory = { session },
        )
        val coordinator = ShelterDeliveryCoordinator(
            client = client,
            repository = InMemoryRescueEnvelopeRepository(),
            directoryResolver = resolver,
            carrierId = "test-carrier",
            deliveryIds = object : CourierDeliveryIdStore {
                override fun idFor(key: com.example.relay.rescue.RescueRequestKey): String = error("must not submit")
                override fun remove(key: com.example.relay.rescue.RescueRequestKey) = Unit
            },
            clock = { NOW },
            sessionDeadlineMillis = 1_000,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            coordinator.start(scope)
            val state = withTimeout(1_000) {
                coordinator.state.first { it is ShelterDeliveryState.WaitingToRetry }
            }

            assertEquals(
                ShelterDeliveryState.WaitingToRetry("BLE identity changed after advertisement"),
                state,
            )
            assertEquals(1, session.identityReads)
            assertFalse(session.submitted)
        } finally {
            coordinator.stop()
            scope.cancel()
        }
    }

    private class MismatchedIdentitySession(
        private val identity: ShelterBleIdentity,
    ) : ShelterBleSession {
        var identityReads: Int = 0
        var submitted: Boolean = false

        override suspend fun readIdentity(): ShelterBleIdentity = identity.also { identityReads++ }

        override suspend fun submit(submission: RescueBleSubmission): RescueBleSubmissionResult {
            submitted = true
            error("submission must be blocked by the fingerprint mismatch")
        }

        override fun close() = Unit
    }

    private companion object {
        const val NOW = 1_500L
    }
}
