package com.example.relay.rescue

import com.example.relay.domain.RelayMessage

class RescueCryptoException(val code: String, cause: Throwable? = null) : Exception(code, cause)

expect object RescueCryptography {
    fun generateRecipientKeyPair(): RescueKeyPair
    fun generateShelterSigningKeyPair(): RescueKeyPair
    fun generateReportSigningKeyPair(): RescueKeyPair

    fun importPublicKey(keyId: String, algorithm: RescueKeyAlgorithm, encodedBase64: String): RescuePublicKey
    fun importPrivateKey(keyId: String, algorithm: RescueKeyAlgorithm, encodedBase64: String): RescuePrivateKey

    fun encrypt(payload: RescuePayload, recipientPublicKey: RescuePublicKey, envelopeId: String, maxHopCount: Int = 8): EncryptedRescueEnvelope
    fun decrypt(envelope: EncryptedRescueEnvelope, recipientPrivateKey: RescuePrivateKey): RescuePayload
    /** Verifies untrusted courier framing without requiring a private key. */
    fun verifyEnvelopeFraming(envelope: EncryptedRescueEnvelope): Boolean
    fun verifySenderAuthorization(envelope: EncryptedRescueEnvelope): Boolean

    fun sha256Hex(bytes: ByteArray): String
    fun signReceipt(receipt: UnsignedShelterReceipt, shelterPrivateKey: RescuePrivateKey): SignedShelterReceipt
    fun verifyReceipt(receipt: SignedShelterReceipt, shelterPublicKey: RescuePublicKey): Boolean

    /** Detached ECDSA P-256 signature for a canonical, purpose-specific trust document. */
    fun signTrustDocument(canonicalBytes: ByteArray, regionalSigningPrivateKey: RescuePrivateKey): TrustDocumentSignature
    fun verifyTrustDocument(canonicalBytes: ByteArray, signature: TrustDocumentSignature, regionalSigningPublicKey: RescuePublicKey): Boolean
    fun signReport(message: RelayMessage, signingKeyPair: RescueKeyPair): RelayMessage
    fun verifyReport(message: RelayMessage): Boolean
}

/** Courier-safe operation: it can only advance routing metadata and never receives private keys. */
fun forwardRescueEnvelope(envelope: EncryptedRescueEnvelope): EncryptedRescueEnvelope? {
    if (envelope.validate() != RescueValidationResult.Valid || envelope.hopCount >= envelope.maxHopCount) return null
    return envelope.copy(hopCount = envelope.hopCount + 1)
}
