package com.example.relay.rescue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RegionalShelterTrustTest {
    @Test
    fun rootSignedDirectorySelectsNewestCurrentRecipientAndReceiptKey() {
        val rootPair = RescueCryptography.generateShelterSigningKeyPair()
        val root = RegionalRootBundle(regionId = "region-1", rootSigningPublicKey = rootPair.publicKey)
        val oldRecipient = RescueCryptography.generateRecipientKeyPair()
        val oldReceipt = RescueCryptography.generateShelterSigningKeyPair()
        val newRecipient = RescueCryptography.generateRecipientKeyPair()
        val newReceipt = RescueCryptography.generateShelterSigningKeyPair()
        val oldManifest = signedManifest(root.regionId, "shelter-1", oldRecipient, oldReceipt, 1, rootPair.privateKey)
        val newManifest = signedManifest(root.regionId, "shelter-1", newRecipient, newReceipt, 2, rootPair.privateKey)
        val directory = UnsignedRegionalShelterDirectory(
            regionId = root.regionId,
            generation = 2,
            issuedAtEpochMillis = 1_000,
            validUntilEpochMillis = 2_000,
            shelters = listOf(oldManifest, newManifest),
        )
        val signedDirectory = signRegionalShelterDirectory(directory, rootPair.privateKey)
        val resolver = RegionalShelterDirectoryResolver(listOf(root))

        assertTrue(verifyRegionalShelterDirectory(signedDirectory, root, 1_500))
        assertIs<DirectoryAcceptance.Accepted>(resolver.accept(signedDirectory, 1_500))
        val newest = assertNotNull(resolver.resolveForNewRequest("region-1", "shelter-1", 1_500))
        assertEquals(newRecipient.publicKey, newest.recipientPublicKey)
        assertEquals(newReceipt.publicKey, newest.receiptSigningPublicKey)
        assertEquals(oldRecipient.publicKey, resolver.resolveForEnvelope("region-1", "shelter-1", oldRecipient.publicKey.keyId, 1_500)?.recipientPublicKey)
        assertTrue(resolver.verifyAdvertisedManifest(newManifest, 1_500))
    }

    @Test
    fun tamperedManifestOrWrongRegionIsNotTrusted() {
        val rootPair = RescueCryptography.generateShelterSigningKeyPair()
        val root = RegionalRootBundle(regionId = "region-1", rootSigningPublicKey = rootPair.publicKey)
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val receipt = RescueCryptography.generateShelterSigningKeyPair()
        val signed = signedManifest(root.regionId, "shelter-1", recipient, receipt, 1, rootPair.privateKey)

        assertTrue(verifyShelterManifest(signed, root, 1_500))
        assertFalse(verifyShelterManifest(signed.copy(regionId = "region-2"), root, 1_500))
        assertFalse(verifyShelterManifest(signed.copy(manifest = signed.manifest.copy(generation = 2)), root, 1_500))
    }

    @Test
    fun directoryRejectsAResignedContainerWithAnAlteredManifestKeyOrBleIdentity() {
        val rootPair = RescueCryptography.generateShelterSigningKeyPair()
        val root = RegionalRootBundle(regionId = "region-1", rootSigningPublicKey = rootPair.publicKey)
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val receipt = RescueCryptography.generateShelterSigningKeyPair()
        val manifest = signedManifest(root.regionId, "shelter-1", recipient, receipt, 1, rootPair.privateKey)
        val changedRecipient = RescueCryptography.generateRecipientKeyPair()
        val changedReceipt = RescueCryptography.generateShelterSigningKeyPair()
        val altered = manifest.copy(
            manifest = manifest.manifest.copy(
                recipientPublicKey = changedRecipient.publicKey,
                receiptSigningPublicKey = changedReceipt.publicKey,
            ),
        )
        val signedDirectory = signRegionalShelterDirectory(directory(root.regionId, 1, altered), rootPair.privateKey)
        val resolver = RegionalShelterDirectoryResolver(listOf(root))

        assertEquals(DirectoryAcceptance.Invalid, resolver.accept(signedDirectory, 1_500))
        assertFalse(resolver.verifyAdvertisedManifest(altered, 1_500))
        assertEquals(null, resolver.resolveBeaconIdentity(altered.beaconFingerprintBytes().copyOf(9), 1_500))
    }

    @Test
    fun directoryRejectsAFormallySignedManifestWhoseRecipientOrReceiptKeyIdDoesNotMatchItsKey() {
        val rootPair = RescueCryptography.generateShelterSigningKeyPair()
        val root = RegionalRootBundle(regionId = "region-1", rootSigningPublicKey = rootPair.publicKey)
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val receipt = RescueCryptography.generateShelterSigningKeyPair()
        val malformedKeyIdManifest = ShelterPublicKeyManifest(
            shelterId = "shelter-1",
            recipientPublicKey = recipient.publicKey.copy(keyId = "wrong-recipient-key-id"),
            receiptSigningPublicKey = receipt.publicKey.copy(keyId = "wrong-receipt-key-id"),
            validFromEpochMillis = 1_000,
            validUntilEpochMillis = 2_000,
            generation = 1,
        )
        val signedManifest = signShelterManifest(root.regionId, malformedKeyIdManifest, rootPair.privateKey)
        val signedDirectory = signRegionalShelterDirectory(directory(root.regionId, 1, signedManifest), rootPair.privateKey)

        assertEquals(DirectoryAcceptance.Invalid, RegionalShelterDirectoryResolver(listOf(root)).accept(signedDirectory, 1_500))
    }

    @Test
    fun resolverNeverReplacesAcceptedDirectoryWithOlderGeneration() {
        val rootPair = RescueCryptography.generateShelterSigningKeyPair()
        val root = RegionalRootBundle(regionId = "region-1", rootSigningPublicKey = rootPair.publicKey)
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val receipt = RescueCryptography.generateShelterSigningKeyPair()
        val signedManifest = signedManifest(root.regionId, "shelter-1", recipient, receipt, 1, rootPair.privateKey)
        val newer = signRegionalShelterDirectory(directory(root.regionId, 2, signedManifest), rootPair.privateKey)
        val older = signRegionalShelterDirectory(directory(root.regionId, 1, signedManifest), rootPair.privateKey)
        val resolver = RegionalShelterDirectoryResolver(listOf(root))

        assertIs<DirectoryAcceptance.Accepted>(resolver.accept(newer, 1_500))
        assertEquals(DirectoryAcceptance.Stale, resolver.accept(older, 1_500))
        assertEquals(2L, resolver.resolveForNewRequest("region-1", "shelter-1", 1_500)?.let { newer.directory.generation })
    }

    @Test
    fun sameGenerationIsIdempotentOnlyForTheExactSignedDirectory() {
        val rootPair = RescueCryptography.generateShelterSigningKeyPair()
        val root = RegionalRootBundle(regionId = "region-1", rootSigningPublicKey = rootPair.publicKey)
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val receipt = RescueCryptography.generateShelterSigningKeyPair()
        val manifest = signedManifest(root.regionId, "shelter-1", recipient, receipt, 1, rootPair.privateKey)
        val accepted = signRegionalShelterDirectory(directory(root.regionId, 1, manifest), rootPair.privateKey)
        val changed = signRegionalShelterDirectory(
            directory(root.regionId, 1, manifest).copy(validUntilEpochMillis = 2_100),
            rootPair.privateKey,
        )
        val resolver = RegionalShelterDirectoryResolver(listOf(root))

        assertIs<DirectoryAcceptance.Accepted>(resolver.accept(accepted, 1_500))
        assertEquals(DirectoryAcceptance.AlreadyAccepted, resolver.accept(accepted, 1_500))
        assertEquals(DirectoryAcceptance.Invalid, resolver.accept(changed, 1_500))
        assertEquals(accepted.signedDirectoryFingerprint(), resolver.acceptedDirectoryDigest("region-1"))
    }

    @Test
    fun duplicateRootIdentifiersAreRejectedBeforeAnyDirectoryCanBeTrusted() {
        val first = RescueCryptography.generateShelterSigningKeyPair()
        val second = RescueCryptography.generateShelterSigningKeyPair()

        assertFailsWith<IllegalArgumentException> {
            RegionalShelterDirectoryResolver(
                listOf(
                    RegionalRootBundle(regionId = "region-1", rootSigningPublicKey = first.publicKey),
                    RegionalRootBundle(regionId = "region-1", rootSigningPublicKey = second.publicKey),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RegionalShelterDirectoryResolver(
                listOf(
                    RegionalRootBundle(regionId = "region-1", rootSigningPublicKey = first.publicKey),
                    RegionalRootBundle(regionId = "region-2", rootSigningPublicKey = first.publicKey),
                ),
            )
        }
    }

    @Test
    fun legacySafeBeaconPrefixResolvesOnlyAnAcceptedManifest() {
        val rootPair = RescueCryptography.generateShelterSigningKeyPair()
        val root = RegionalRootBundle(regionId = "region-1", rootSigningPublicKey = rootPair.publicKey)
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val receipt = RescueCryptography.generateShelterSigningKeyPair()
        val manifest = signedManifest(root.regionId, "shelter-1", recipient, receipt, 1, rootPair.privateKey)
        val signedDirectory = signRegionalShelterDirectory(directory(root.regionId, 1, manifest), rootPair.privateKey)
        val resolver = RegionalShelterDirectoryResolver(listOf(root))
        assertIs<DirectoryAcceptance.Accepted>(resolver.accept(signedDirectory, 1_500))

        val prefix = manifest.beaconFingerprintBytes().copyOf(9)
        assertEquals(manifest, resolver.resolveBeaconIdentity(prefix, 1_500))
        assertEquals(null, resolver.resolveBeaconIdentity(prefix.copyOf(8), 1_500))
        assertEquals(null, resolver.resolveBeaconIdentity(prefix.apply { this[0] = (this[0].toInt() xor 1).toByte() }, 1_500))
    }

    private fun signedManifest(
        regionId: String,
        shelterId: String,
        recipient: RescueKeyPair,
        receipt: RescueKeyPair,
        generation: Int,
        rootKey: RescuePrivateKey,
    ): SignedShelterManifest = signShelterManifest(
        regionId,
        ShelterPublicKeyManifest(
            shelterId = shelterId,
            recipientPublicKey = recipient.publicKey,
            receiptSigningPublicKey = receipt.publicKey,
            validFromEpochMillis = 1_000,
            validUntilEpochMillis = 2_000,
            generation = generation,
        ),
        rootKey,
    )

    private fun directory(regionId: String, generation: Long, manifest: SignedShelterManifest) =
        UnsignedRegionalShelterDirectory(
            regionId = regionId,
            generation = generation,
            issuedAtEpochMillis = 1_000,
            validUntilEpochMillis = 2_000,
            shelters = listOf(manifest),
        )
}
