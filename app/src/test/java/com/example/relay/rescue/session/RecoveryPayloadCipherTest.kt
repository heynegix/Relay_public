package com.example.relay.rescue.session

import com.example.relay.rescue.RescueRequestDraft
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueUrgency
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecoveryPayloadCipherTest {
    @Test
    fun `recovery payload round trips with unique nonces`() {
        val cipher = AesGcmRecoveryPayloadCipher(InMemorySecretKeyProvider(), "TEST ONLY alias-a")
        val payload = payload()

        val first = cipher.seal(payload)
        val second = cipher.seal(payload)

        assertFalse(first.nonce.contentEquals(second.nonce))
        assertNotEquals(payload.draft.freeText, first.ciphertext.decodeToString())
        assertEquals(payload, cipher.open(first))
        assertEquals(payload, cipher.open(second))
    }

    @Test
    fun `tampering or a different alias fails closed`() {
        val provider = InMemorySecretKeyProvider()
        val source = AesGcmRecoveryPayloadCipher(provider, "TEST ONLY alias-a")
        val sealed = source.seal(payload())
        val tampered = sealed.copy(ciphertext = sealed.ciphertext.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })

        assertThrows(RecoveryPayloadDecryptionException::class.java) { source.open(tampered) }
        assertThrows(RecoveryPayloadDecryptionException::class.java) {
            AesGcmRecoveryPayloadCipher(provider, "TEST ONLY alias-b").open(sealed)
        }
    }

    private fun payload(): RescueSessionRecoveryPayload {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val receipt = RescueCryptography.generateShelterSigningKeyPair()
        return RescueSessionRecoveryPayload(
            draft = RescueRequestDraft(
            requestId = "request-1",
            senderDeviceId = "device-1",
            destinationShelterId = "shelter-1",
            createdAtEpochMillis = 1_700_000_000_000L,
            expiresAtEpochMillis = 1_700_000_060_000L,
            urgency = RescueUrgency.URGENT,
            personCount = 2,
            freeText = "private rescue details",
            ),
            recipientPublicKey = recipient.publicKey,
            receiptSigningPublicKey = receipt.publicKey,
            trackingEnabled = false,
        )
    }

    private class InMemorySecretKeyProvider : SessionSecretKeyProvider {
        private val keys = mutableMapOf<String, SecretKey>()
        override fun key(alias: String): SecretKey = keys.getOrPut(alias) {
            KeyGenerator.getInstance("AES").apply { init(256, SecureRandom()) }.generateKey()
        }
    }
}
