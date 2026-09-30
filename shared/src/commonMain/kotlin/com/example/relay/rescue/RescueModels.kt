package com.example.relay.rescue

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.example.relay.domain.MessagePayload

const val RESCUE_PROTOCOL_VERSION: Int = 1
const val RESCUE_MAX_CIPHERTEXT_BYTES: Int = 1_048_576
const val RESCUE_MAX_SENDER_PUBLIC_KEY_BYTES: Int = 1_024

@Serializable
enum class RescueUrgency { ROUTINE, URGENT, IMMEDIATE }

@Serializable
enum class RescueSupportNeed { WATER, FOOD, MEDICINE, RESCUE_TEAM, TRANSPORT }

/** The four plain-language conditions shown in the v1 request flow. */
@Serializable
enum class RescueCondition {
    LIFE_THREATENING,
    INJURED_OR_UNWELL,
    MOBILITY_IMPAIRED,
    SUPPORT_NEEDED,
}

/** A later request version can update or cancel an earlier version. */
@Serializable
enum class RescueRequestAction { ACTIVE, CANCELLED }

@Serializable
data class RescueLocation(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val description: String = "",
    /** Time of the GPS fix. Allows receivers to distinguish a live fix from a fallback. */
    val capturedAtEpochMillis: Long? = null,
)

@Serializable
data class RescuePayload(
    val protocolVersion: Int = RESCUE_PROTOCOL_VERSION,
    val requestId: String,
    val requestVersion: Int = 1,
    val senderDeviceId: String,
    val destinationShelterId: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val urgency: RescueUrgency,
    /** Zero means unknown. This keeps v1 wire compatibility with the existing integer field. */
    val personCount: Int = 1,
    val conditions: Set<RescueCondition> = emptySet(),
    val action: RescueRequestAction = RescueRequestAction.ACTIVE,
    val injured: Boolean = false,
    val seriouslyInjured: Boolean = false,
    val mobilityImpaired: Boolean = false,
    val elderlyPresent: Boolean = false,
    val childrenPresent: Boolean = false,
    val pregnantPresent: Boolean = false,
    val medicalSupportRequired: Boolean = false,
    val trapped: Boolean = false,
    val fireOrCollapseRisk: Boolean = false,
    val supportNeeds: Set<RescueSupportNeed> = emptySet(),
    val location: RescueLocation? = null,
    val freeText: String = "",
)

/**
 * Store-carry-forward envelope. [hopCount] is intentionally mutable routing metadata and is not
 * authenticated as AAD. All other routing header fields are immutable and are bound to the
 * ciphertext by AES-GCM.
 */
@Serializable
data class EncryptedRescueEnvelope(
    val protocolVersion: Int = RESCUE_PROTOCOL_VERSION,
    val envelopeId: String,
    val requestId: String,
    val requestVersion: Int,
    val senderDeviceId: String,
    val destinationShelterId: String,
    val routingUrgency: RescueUrgency,
    val recipientKeyId: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val maxHopCount: Int = 8,
    val hopCount: Int = 0,
    val keyWrapAlgorithm: String = RescueAlgorithms.RSA_OAEP_SHA256,
    val contentEncryptionAlgorithm: String = RescueAlgorithms.AES_256_GCM,
    val ciphertextSizeBytes: Int,
    val wrappedContentKeyBase64: String,
    val nonceBase64: String,
    val ciphertextBase64: String,
    val ciphertextSha256Hex: String,
    /** Optional sender authorization. New Android envelopes populate this; legacy v1 envelopes
     * remain readable during migration, but a signed request cannot be downgraded to unsigned. */
    val senderKeyId: String = "",
    val senderPublicKeyBase64: String = "",
    val senderSignatureBase64: String = "",
)

fun EncryptedRescueEnvelope.hasSenderAuthorization(): Boolean =
    senderKeyId.isNotBlank() || senderPublicKeyBase64.isNotBlank() || senderSignatureBase64.isNotBlank()

@Serializable
enum class ShelterReceiptStatus { STORED, ACCEPTED, RESPONDING, COMPLETED, CANCELLED, REJECTED }

@Serializable
data class UnsignedShelterReceipt(
    val protocolVersion: Int = RESCUE_PROTOCOL_VERSION,
    val receiptId: String,
    val envelopeId: String,
    val requestId: String,
    val requestVersion: Int,
    val ciphertextSha256Hex: String,
    val shelterId: String,
    val receivedAtEpochMillis: Long,
    val status: ShelterReceiptStatus,
)

@Serializable
data class SignedShelterReceipt(
    val receipt: UnsignedShelterReceipt,
    val signerKeyId: String,
    val signatureAlgorithm: String = RescueAlgorithms.ECDSA_P256_SHA256,
    val signatureBase64: String,
)

/** Detached signature used exclusively by regional shelter trust documents. */
@Serializable
data class TrustDocumentSignature(
    val signerKeyId: String,
    val signatureAlgorithm: String = RescueAlgorithms.ECDSA_P256_SHA256,
    val signatureBase64: String,
)

/** Detached signature carried by an ordinary REPORT for tamper detection. */
@Serializable
data class ReportSignature(
    val signerKeyId: String,
    val signatureAlgorithm: String = RescueAlgorithms.ECDSA_P256_SHA256,
    val publicKey: RescuePublicKey,
    val signatureBase64: String,
)

object RescueAlgorithms {
    const val AES_256_GCM = "AES-256-GCM"
    const val RSA_OAEP_SHA256 = "RSA-OAEP-256"
    const val ECDSA_P256_SHA256 = "ECDSA-P256-SHA256"
}

@Serializable
enum class RescueKeyAlgorithm { RSA_OAEP_SHA256, ECDSA_P256_SHA256 }

/** X.509 SubjectPublicKeyInfo, Base64 encoded. */
@Serializable
data class RescuePublicKey(
    val keyId: String,
    val algorithm: RescueKeyAlgorithm,
    val encodedBase64: String,
)

/** PKCS#8 private key material. Never place this type in a courier workflow or wire model. */
data class RescuePrivateKey(
    val keyId: String,
    val algorithm: RescueKeyAlgorithm,
    val encodedBase64: String,
)

data class RescueKeyPair(
    val publicKey: RescuePublicKey,
    val privateKey: RescuePrivateKey,
)

sealed interface RescueValidationResult {
    data object Valid : RescueValidationResult
    data class Invalid(val reasons: List<String>) : RescueValidationResult
}

fun RescuePayload.validate(): RescueValidationResult = validationResult {
    require(protocolVersion == RESCUE_PROTOCOL_VERSION, "unsupported_protocol")
    requireIdentifier(requestId, "invalid_request_id")
    require(requestVersion in 1..1_000_000, "invalid_request_version")
    requireIdentifier(senderDeviceId, "invalid_sender_id")
    requireIdentifier(destinationShelterId, "invalid_shelter_id")
    require(createdAtEpochMillis > 0, "invalid_created_at")
    require(expiresAtEpochMillis > createdAtEpochMillis, "invalid_expiry")
    require(expiresAtEpochMillis - createdAtEpochMillis <= 7L * 24 * 60 * 60 * 1_000, "lifetime_too_long")
    require(personCount in 0..1_000, "invalid_person_count")
    require(freeText.length <= 2_000, "message_too_long")
    location?.let {
        require(it.description.length <= 256, "location_too_long")
        require(it.latitude == null || it.latitude in -90.0..90.0, "invalid_latitude")
        require(it.longitude == null || it.longitude in -180.0..180.0, "invalid_longitude")
        require(it.accuracyMeters == null || it.accuracyMeters in 0f..100_000f, "invalid_accuracy")
        require(it.capturedAtEpochMillis == null || it.capturedAtEpochMillis > 0, "invalid_location_time")
    }
}

fun EncryptedRescueEnvelope.validate(): RescueValidationResult = validationResult {
    require(protocolVersion == RESCUE_PROTOCOL_VERSION, "unsupported_protocol")
    requireIdentifier(envelopeId, "invalid_envelope_id")
    requireIdentifier(requestId, "invalid_request_id")
    require(requestVersion in 1..1_000_000, "invalid_request_version")
    requireIdentifier(senderDeviceId, "invalid_sender_id")
    requireIdentifier(destinationShelterId, "invalid_shelter_id")
    requireIdentifier(recipientKeyId, "invalid_recipient_key_id")
    require(createdAtEpochMillis > 0 && expiresAtEpochMillis > createdAtEpochMillis, "invalid_lifetime")
    require(expiresAtEpochMillis - createdAtEpochMillis <= 7L * 24 * 60 * 60 * 1_000, "lifetime_too_long")
    require(maxHopCount in 1..32 && hopCount in 0..maxHopCount, "invalid_hop")
    require(keyWrapAlgorithm == RescueAlgorithms.RSA_OAEP_SHA256, "unsupported_key_wrap")
    require(contentEncryptionAlgorithm == RescueAlgorithms.AES_256_GCM, "unsupported_content_encryption")
    require(ciphertextSizeBytes in 16..RESCUE_MAX_CIPHERTEXT_BYTES, "invalid_ciphertext_size")
    require(ciphertextSha256Hex.length == 64 && ciphertextSha256Hex.all(::isLowerHex), "invalid_ciphertext_hash")
    require(wrappedContentKeyBase64.length in 128..2_048, "invalid_wrapped_key")
    require(nonceBase64.length in 16..32, "invalid_nonce")
    require(ciphertextBase64.length in 24..((RESCUE_MAX_CIPHERTEXT_BYTES * 4 / 3) + 8), "invalid_ciphertext")
    val senderAuthorizationPresent = hasSenderAuthorization()
    if (senderAuthorizationPresent) {
        requireIdentifier(senderKeyId, "invalid_sender_key_id")
        require(senderPublicKeyBase64.length in 64..RESCUE_MAX_SENDER_PUBLIC_KEY_BYTES, "invalid_sender_public_key")
        require(senderSignatureBase64.length in 64..256, "invalid_sender_signature")
    }
}

fun UnsignedShelterReceipt.validate(): RescueValidationResult = validationResult {
    require(protocolVersion == RESCUE_PROTOCOL_VERSION, "unsupported_protocol")
    requireIdentifier(receiptId, "invalid_receipt_id")
    requireIdentifier(envelopeId, "invalid_envelope_id")
    requireIdentifier(requestId, "invalid_request_id")
    requireIdentifier(shelterId, "invalid_shelter_id")
    require(receivedAtEpochMillis > 0, "invalid_received_at")
    require(ciphertextSha256Hex.length == 64 && ciphertextSha256Hex.all(::isLowerHex), "invalid_ciphertext_hash")
}

fun SignedShelterReceipt.validate(): RescueValidationResult = validationResult {
    require(receipt.validate() == RescueValidationResult.Valid, "invalid_receipt")
    requireIdentifier(signerKeyId, "invalid_signer_key_id")
    require(signatureAlgorithm == RescueAlgorithms.ECDSA_P256_SHA256, "unsupported_signature")
    require(signatureBase64.length in 64..256, "invalid_signature")
}

fun TrustDocumentSignature.validate(): RescueValidationResult = validationResult {
    requireIdentifier(signerKeyId, "invalid_signer_key_id")
    require(signatureAlgorithm == RescueAlgorithms.ECDSA_P256_SHA256, "unsupported_signature")
    require(signatureBase64.length in 64..256, "invalid_signature")
}

fun ReportSignature.validate(): RescueValidationResult = validationResult {
    requireIdentifier(signerKeyId, "invalid_signer_key_id")
    require(signatureAlgorithm == RescueAlgorithms.ECDSA_P256_SHA256, "unsupported_signature")
    require(publicKey.keyId == signerKeyId, "signer_key_mismatch")
    require(publicKey.algorithm == RescueKeyAlgorithm.ECDSA_P256_SHA256, "unsupported_key")
    require(signatureBase64.length in 64..256, "invalid_signature")
}

/** Canonical, length-prefixed AAD. Only hopCount is mutable routing metadata. */
fun EncryptedRescueEnvelope.authenticatedHeaderBytes(): ByteArray = canonicalBytes(
    protocolVersion.toString(),
    envelopeId,
    requestId,
    requestVersion.toString(),
    senderDeviceId,
    destinationShelterId,
    routingUrgency.name,
    recipientKeyId,
    createdAtEpochMillis.toString(),
    expiresAtEpochMillis.toString(),
    maxHopCount.toString(),
    keyWrapAlgorithm,
    contentEncryptionAlgorithm,
    ciphertextSizeBytes.toString(),
    wrappedContentKeyBase64,
    nonceBase64,
)

/** v1 AAD retained solely to read envelopes written before key/nonce binding was added. */
fun EncryptedRescueEnvelope.legacyAuthenticatedHeaderBytes(): ByteArray = canonicalBytes(
    protocolVersion.toString(),
    envelopeId,
    requestId,
    requestVersion.toString(),
    senderDeviceId,
    destinationShelterId,
    routingUrgency.name,
    recipientKeyId,
    createdAtEpochMillis.toString(),
    expiresAtEpochMillis.toString(),
    maxHopCount.toString(),
    keyWrapAlgorithm,
    contentEncryptionAlgorithm,
    ciphertextSizeBytes.toString(),
)

/** Canonical bytes signed by the sender key after encryption. */
fun EncryptedRescueEnvelope.senderAuthorizationBytes(): ByteArray = canonicalBytes(
    "RelayRescueSender/v1",
    authenticatedHeaderBytes().decodeToString(),
    ciphertextSha256Hex,
)

internal fun EncryptedRescueEnvelope.legacySenderAuthorizationBytes(): ByteArray = canonicalBytes(
    "RelayRescueSender/v1",
    legacyAuthenticatedHeaderBytes().decodeToString(),
    ciphertextSha256Hex,
)

fun EncryptedRescueEnvelope.duplicateKey(): String = "$requestId:$requestVersion"

internal fun UnsignedShelterReceipt.signingBytes(): ByteArray = canonicalBytes(
    protocolVersion.toString(),
    receiptId,
    envelopeId,
    requestId,
    requestVersion.toString(),
    ciphertextSha256Hex,
    shelterId,
    receivedAtEpochMillis.toString(),
    status.name,
)

/** Immutable REPORT content only; hop/status/receipt timing are transport metadata. */
fun reportSigningBytes(message: com.example.relay.domain.RelayMessage): ByteArray {
    val payloadJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }
        .encodeToString(MessagePayload.serializer(), message.payload)
    val payloadHash = RescueCryptography.sha256Hex(payloadJson.encodeToByteArray())
    return canonicalBytes(
        "RelayReport/v1",
        message.messageId,
        message.originDeviceId,
        message.createdAt.toString(),
        message.expiresAt.toString(),
        message.messageType.name,
        message.priority.name,
        payloadHash,
        payloadJson,
    )
}

private fun canonicalBytes(vararg fields: String): ByteArray = buildString {
    fields.forEach { field -> append(field.encodeToByteArray().size).append(':').append(field) }
}.encodeToByteArray()

private fun isLowerHex(character: Char): Boolean = character in '0'..'9' || character in 'a'..'f'

internal class ValidationCollector {
    val reasons = mutableListOf<String>()
    fun require(condition: Boolean, reason: String) { if (!condition) reasons += reason }
    fun requireIdentifier(value: String, reason: String) {
        require(value.length in 1..128 && value.all { it.isLetterOrDigit() || it in "-_.:" }, reason)
    }
}

internal inline fun validationResult(block: ValidationCollector.() -> Unit): RescueValidationResult {
    val collector = ValidationCollector().apply(block)
    return if (collector.reasons.isEmpty()) RescueValidationResult.Valid else RescueValidationResult.Invalid(collector.reasons)
}
