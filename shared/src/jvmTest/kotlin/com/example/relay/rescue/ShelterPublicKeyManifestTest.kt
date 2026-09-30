package com.example.relay.rescue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ShelterPublicKeyManifestTest {
    @Test
    fun fingerprintIsDeterministicAndBindsManifestFields() {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val manifest = ShelterPublicKeyManifest(
            shelterId = "shelter-1",
            recipientPublicKey = recipient.publicKey,
            receiptSigningPublicKey = signer.publicKey,
            validFromEpochMillis = 1_000,
            validUntilEpochMillis = 2_000,
        )

        assertEquals(RescueValidationResult.Valid, manifest.validate(1_500))
        assertEquals(manifest.fingerprint(), manifest.copy().fingerprint())
        assertNotEquals(manifest.fingerprint(), manifest.copy(generation = 2).fingerprint())
    }
}
