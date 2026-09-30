package com.example.relay.rescue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RescueCryptographyTest {
    @Test
    fun payloadIsEncryptedAndOnlyRecipientCanDecryptIt() {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val otherRecipient = RescueCryptography.generateRecipientKeyPair()
        val payload = payload()

        val envelope = RescueCryptography.encrypt(payload, recipient.publicKey, "envelope-1")

        assertFalse(envelope.ciphertextBase64.contains("secret-location"))
        assertEquals(payload, RescueCryptography.decrypt(envelope, recipient.privateKey))
        assertFailsWith<RescueCryptoException> {
            RescueCryptography.decrypt(envelope, otherRecipient.privateKey)
        }
    }

    @Test
    fun encryptedKeyAndNonceAreBoundToCiphertextAuthentication() {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val envelope = RescueCryptography.encrypt(payload(), recipient.publicKey, "envelope-1")

        assertFailsWith<RescueCryptoException> {
            RescueCryptography.decrypt(
                envelope.copy(
                    nonceBase64 = envelope.nonceBase64.first().let { first ->
                        (if (first == 'A') 'B' else 'A') + envelope.nonceBase64.drop(1)
                    },
                ),
                recipient.privateKey,
            )
        }
    }

    @Test
    fun receiptSignatureBindsRequestVersionAndCiphertextHash() {
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val receipt = UnsignedShelterReceipt(
            receiptId = "receipt-1",
            envelopeId = "envelope-1",
            requestId = "request-1",
            requestVersion = 2,
            ciphertextSha256Hex = "a".repeat(64),
            shelterId = "shelter-1",
            receivedAtEpochMillis = 2_000,
            status = ShelterReceiptStatus.ACCEPTED,
        )
        val signed = RescueCryptography.signReceipt(receipt, signer.privateKey)

        assertTrue(RescueCryptography.verifyReceipt(signed, signer.publicKey))
        assertFalse(
            RescueCryptography.verifyReceipt(
                signed.copy(receipt = signed.receipt.copy(requestVersion = 1)),
                signer.publicKey,
            ),
        )
    }

    @Test
    fun malformedSenderAuthorizationCannotBeAcceptedAsAValidEnvelope() {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val envelope = RescueCryptography.encrypt(payload(), recipient.publicKey, "envelope-1")
            .copy(
                senderKeyId = "sender-key",
                senderPublicKeyBase64 = "A".repeat(64),
                senderSignatureBase64 = "B".repeat(64),
            )

        assertEquals(RescueValidationResult.Valid, envelope.validate())
        assertFalse(RescueCryptography.verifySenderAuthorization(envelope))
    }

    private fun payload() = RescuePayload(
        requestId = "request-1",
        requestVersion = 1,
        senderDeviceId = "member-1",
        destinationShelterId = "shelter-1",
        createdAtEpochMillis = 1_000,
        expiresAtEpochMillis = 86_401_000,
        urgency = RescueUrgency.IMMEDIATE,
        personCount = 3,
        injured = true,
        supportNeeds = setOf(RescueSupportNeed.WATER, RescueSupportNeed.RESCUE_TEAM),
        location = RescueLocation(0.0, 0.0, 12f, "secret-location"),
        freeText = "secret-note",
    )
}
