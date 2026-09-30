package com.example.relay.rescue

import kotlinx.serialization.Serializable

const val RESCUE_KEY_MANIFEST_VERSION: Int = 1
private const val MAX_RESCUE_KEY_MANIFEST_LIFETIME_MS: Long = 10L * 366 * 24 * 60 * 60 * 1_000

/**
 * Public-only key distribution document. Possession of this document does not establish trust;
 * an Android client must compare [fingerprint] with an independently supplied value before saving.
 */
@Serializable
data class ShelterPublicKeyManifest(
    val protocolVersion: Int = RESCUE_KEY_MANIFEST_VERSION,
    val shelterId: String,
    val recipientPublicKey: RescuePublicKey,
    val receiptSigningPublicKey: RescuePublicKey,
    val validFromEpochMillis: Long,
    val validUntilEpochMillis: Long,
    val generation: Int = 1,
) {
    fun validate(nowEpochMillis: Long? = null): RescueValidationResult = validationResult {
        require(protocolVersion == RESCUE_KEY_MANIFEST_VERSION, "unsupported_manifest_protocol")
        requireIdentifier(shelterId, "invalid_shelter_id")
        require(recipientPublicKey.algorithm == RescueKeyAlgorithm.RSA_OAEP_SHA256, "invalid_recipient_key")
        require(receiptSigningPublicKey.algorithm == RescueKeyAlgorithm.ECDSA_P256_SHA256, "invalid_signing_key")
        requireIdentifier(recipientPublicKey.keyId, "invalid_recipient_key_id")
        requireIdentifier(receiptSigningPublicKey.keyId, "invalid_signing_key_id")
        require(recipientPublicKey.encodedBase64.length in 512..8_192, "invalid_recipient_key_size")
        require(receiptSigningPublicKey.encodedBase64.length in 64..2_048, "invalid_signing_key_size")
        require(generation in 1..1_000_000, "invalid_generation")
        require(validFromEpochMillis > 0, "invalid_valid_from")
        require(validUntilEpochMillis > validFromEpochMillis, "invalid_valid_until")
        require(
            validUntilEpochMillis - validFromEpochMillis <= MAX_RESCUE_KEY_MANIFEST_LIFETIME_MS,
            "manifest_lifetime_too_long",
        )
        nowEpochMillis?.let { now ->
            require(now in validFromEpochMillis..validUntilEpochMillis, "manifest_not_current")
        }
    }

    fun fingerprint(): String = RescueCryptography.sha256Hex(canonicalBytes())

    private fun canonicalBytes(): ByteArray = buildString {
        listOf(
            protocolVersion.toString(),
            shelterId,
            recipientPublicKey.keyId,
            recipientPublicKey.algorithm.name,
            recipientPublicKey.encodedBase64,
            receiptSigningPublicKey.keyId,
            receiptSigningPublicKey.algorithm.name,
            receiptSigningPublicKey.encodedBase64,
            validFromEpochMillis.toString(),
            validUntilEpochMillis.toString(),
            generation.toString(),
        ).forEach { value -> append(value.encodeToByteArray().size).append(':').append(value) }
    }.encodeToByteArray()
}
