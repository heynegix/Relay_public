package com.example.relay.rescue

import com.example.relay.gateway.GatewayEnrollmentToken
import com.example.relay.gateway.enrollShelterFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ShelterManifestEnrollmentTest {
    private val recipient = RescueCryptography.generateRecipientKeyPair()
    private val signer = RescueCryptography.generateShelterSigningKeyPair()

    private fun manifest(shelterId: String = "shelter-example-01") = ShelterPublicKeyManifest(
        shelterId = shelterId,
        recipientPublicKey = recipient.publicKey,
        receiptSigningPublicKey = signer.publicKey,
        validFromEpochMillis = 1_000,
        validUntilEpochMillis = Long.MAX_VALUE,
    )

    private class FakeStore : VerifiedManifestStore {
        var saved: ShelterPublicKeyManifest? = null
        var savedFingerprint: String? = null
        override fun saveVerifiedManifest(manifest: ShelterPublicKeyManifest, expectedFingerprint: String) {
            saved = manifest
            savedFingerprint = expectedFingerprint
        }
        override fun load(): ShelterPublicKeys? = saved?.let {
            ShelterPublicKeys(it.shelterId, it.recipientPublicKey, it.receiptSigningPublicKey, it.fingerprint(), it.generation)
        }
    }

    private fun enrollment(manifest: ShelterPublicKeyManifest, store: FakeStore) =
        ShelterManifestEnrollment(ShelterManifestClient { _, _ -> manifest }, store)

    @Test
    fun `enroll pins a manifest whose shelter identity matches the expectation`() {
        val m = manifest()
        val store = FakeStore()

        val keys = enrollment(m, store).enroll("192.168.50.20", 8443, m.fingerprint(), "shelter-example-01")

        assertEquals("shelter-example-01", keys.shelterId)
        assertEquals(m.fingerprint(), store.savedFingerprint)
    }

    @Test
    fun `enroll refuses a manifest advertising a different shelter before persisting`() {
        val m = manifest(shelterId = "shelter-somewhere-else")
        val store = FakeStore()

        assertThrows(IllegalArgumentException::class.java) {
            enrollment(m, store).enroll("192.168.50.20", 8443, m.fingerprint(), "shelter-example-01")
        }
        assertNull("a mismatched manifest must never be persisted", store.saved)
    }

    @Test
    fun `enrollShelterFor drives enrollment from a verified gateway token`() {
        val m = manifest()
        val store = FakeStore()
        val token = GatewayEnrollmentToken(
            gatewayId = "pc-gateway-example-01",
            shelterId = "shelter-example-01",
            host = "192.168.50.20",
            port = 8443,
            scheme = "https",
            manifestFingerprint = m.fingerprint(),
        )

        val keys = enrollment(m, store).enrollShelterFor(token)

        assertEquals("shelter-example-01", keys.shelterId)
        assertEquals(m.fingerprint(), store.savedFingerprint)
    }

    @Test
    fun `enrollShelterFor refuses a token whose shelter does not match the manifest`() {
        val m = manifest(shelterId = "shelter-somewhere-else")
        val store = FakeStore()
        val token = GatewayEnrollmentToken(
            gatewayId = "pc-gateway-example-01",
            shelterId = "shelter-example-01",
            host = "192.168.50.20",
            port = 8443,
            scheme = "https",
            manifestFingerprint = m.fingerprint(),
        )

        assertThrows(IllegalArgumentException::class.java) { enrollment(m, store).enrollShelterFor(token) }
        assertNull(store.saved)
    }
}
