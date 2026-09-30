package com.example.relay.gateway.protocol

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val GATEWAY_SIGNATURE_ALGORITHM = "SHA256withECDSA"

/** Signing is deliberately independent of Android Keystore and HTTP implementations. */
interface GatewayMessageSigner {
    val algorithm: String
    val keyId: String
    fun sign(canonicalMessage: ByteArray): ByteArray?
}

/** A missing signer never manufactures an empty or successful signature. */
object NoOpGatewayMessageSigner : GatewayMessageSigner {
    override val algorithm: String = "NONE"
    override val keyId: String = ""
    override fun sign(canonicalMessage: ByteArray): ByteArray? = null
}

enum class GatewayVerificationResult {
    VERIFIED,
    UNSIGNED,
    UNSUPPORTED_ALGORITHM,
    UNKNOWN_KEY,
    MALFORMED_SIGNATURE,
    INVALID_SIGNATURE,
}

interface GatewayMessageVerifier {
    fun verify(canonicalMessage: ByteArray, integrity: GatewayIntegrity?): GatewayVerificationResult
}

/** Safe default for builds where a trusted-key registry has not been configured. */
object RejectingGatewayMessageVerifier : GatewayMessageVerifier {
    override fun verify(canonicalMessage: ByteArray, integrity: GatewayIntegrity?): GatewayVerificationResult =
        if (integrity == null) GatewayVerificationResult.UNSIGNED else GatewayVerificationResult.UNKNOWN_KEY
}

/**
 * JCA implementation shared by Android and PC. Android Keystore can later provide
 * the [PrivateKey] without changing the protocol model.
 */
class EcdsaP256GatewayMessageSigner(
    override val keyId: String,
    private val privateKey: PrivateKey,
) : GatewayMessageSigner {
    override val algorithm: String = GATEWAY_SIGNATURE_ALGORITHM

    override fun sign(canonicalMessage: ByteArray): ByteArray =
        Signature.getInstance(algorithm).run {
            initSign(privateKey)
            update(canonicalMessage)
            sign()
        }
}

class EcdsaP256GatewayMessageVerifier(
    private val resolvePublicKey: (keyId: String) -> PublicKey?,
) : GatewayMessageVerifier {
    override fun verify(
        canonicalMessage: ByteArray,
        integrity: GatewayIntegrity?,
    ): GatewayVerificationResult {
        integrity ?: return GatewayVerificationResult.UNSIGNED
        if (integrity.algorithm != GATEWAY_SIGNATURE_ALGORITHM) return GatewayVerificationResult.UNSUPPORTED_ALGORITHM
        if (integrity.keyId.isBlank() || integrity.keyId.length > 128) return GatewayVerificationResult.UNKNOWN_KEY
        val signatureBytes = integrity.signature.hexToBytesOrNull()
            ?: return GatewayVerificationResult.MALFORMED_SIGNATURE
        if (signatureBytes.isEmpty() || signatureBytes.size > 512) return GatewayVerificationResult.MALFORMED_SIGNATURE
        val publicKey = resolvePublicKey(integrity.keyId) ?: return GatewayVerificationResult.UNKNOWN_KEY
        return try {
            val verified = Signature.getInstance(GATEWAY_SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(canonicalMessage)
                verify(signatureBytes)
            }
            if (verified) GatewayVerificationResult.VERIFIED else GatewayVerificationResult.INVALID_SIGNATURE
        } catch (_: Exception) {
            GatewayVerificationResult.INVALID_SIGNATURE
        }
    }
}

fun GatewayMessage.signWith(signer: GatewayMessageSigner): GatewayMessage {
    val signature = signer.sign(canonicalBytes()) ?: return copy(integrity = null)
    if (signer.algorithm.isBlank() || signer.keyId.isBlank()) return copy(integrity = null)
    return copy(integrity = GatewayIntegrity(signer.algorithm, signer.keyId, signature.toHex()))
}

fun GatewayMessage.verifyWith(verifier: GatewayMessageVerifier): GatewayVerificationResult =
    verifier.verify(copy(integrity = null).canonicalBytes(), integrity)

/**
 * Deterministic v2 signing form. It excludes [GatewayMessage.integrity] and uses
 * length-prefixed UTF-8 fields plus recursively key-sorted JSON.
 */
fun GatewayMessage.canonicalBytes(): ByteArray = CanonicalFields("RelayGatewayMessage/v2").apply {
    add(messageId)
    add(messageType)
    add(recordType)
    add(priority)
    add(status)
    add(createdAt.toString())
    add(expiresAt.toString())
    add(lifetimeMs.toString())
    add(accumulatedAgeMs.toString())
    add(hopCount.toString())
    add(hopLimit.toString())
    add(originDeviceId)
    add(payload.canonicalJson())
    add(reportSignature?.canonicalJson().orEmpty())
    add(receivedAt.toString())
}.bytes()

/** Protocol-level rule; v1 remains readable while v2 messages must be signed. */
fun GatewayMessage.verifyForProtocol(
    protocolVersion: Int,
    verifier: GatewayMessageVerifier,
): GatewayVerificationResult = when (protocolVersion) {
    GATEWAY_PROTOCOL_V1 -> if (integrity == null) GatewayVerificationResult.UNSIGNED else verifyWith(verifier)
    GATEWAY_PROTOCOL_V2 -> verifyWith(verifier)
    else -> GatewayVerificationResult.UNSUPPORTED_ALGORITHM
}

private class CanonicalFields(domain: String) {
    private val output = ByteArrayOutputStream()

    init { add(domain) }

    fun add(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        output.write(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        output.write(':'.code)
        output.write(bytes)
        output.write(';'.code)
    }

    fun bytes(): ByteArray = output.toByteArray()
}

private fun JsonElement.canonicalJson(): String = when (this) {
    JsonNull -> "null"
    is JsonPrimitive -> toString()
    is JsonArray -> joinToString(prefix = "[", postfix = "]", separator = ",") { it.canonicalJson() }
    is JsonObject -> entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}", separator = ",") {
        JsonPrimitive(it.key).toString() + ":" + it.value.canonicalJson()
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02X".format(byte.toInt() and 0xff) }

private fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0 || any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
