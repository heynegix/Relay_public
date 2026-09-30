package com.example.relay.gateway.protocol

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic edge-case tests derived from PIT mutation-survivor analysis.
 * Each test exists to kill a concrete surviving mutant class: hex-alphabet
 * boundaries, keyId/signature size limits, protocol-version dispatch, the
 * canonical byte layout, and DTO field serialization.
 */
class GatewayProtocolEdgeCaseTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private val baseMessage = GatewayMessage(
        messageId = "m", messageType = "SAFETY", recordType = "REPORT",
        priority = "HIGH", status = "RECEIVED", createdAt = 1, expiresAt = 10,
        lifetimeMs = 9, accumulatedAgeMs = 0, hopCount = 0, hopLimit = 8,
        originDeviceId = "origin", payload = JsonPrimitive("payload"), receivedAt = 1,
    )

    private fun verifierFor(keyId: String) =
        EcdsaP256GatewayMessageVerifier { candidate -> if (candidate == keyId) keys.public else null }

    private fun integrityWithSignature(hex: String) =
        baseMessage.copy(integrity = GatewayIntegrity(GATEWAY_SIGNATURE_ALGORITHM, "k", hex))

    @Test
    fun `canonical bytes match an independent reimplementation`() {
        val payload = buildJsonObject {
            put(
                "b",
                buildJsonArray {
                    add(JsonPrimitive(1))
                    add(kotlinx.serialization.json.JsonNull)
                    add(buildJsonObject { put("a", "x") })
                },
            )
            put("a", true)
        }
        val message = baseMessage.copy(payload = payload)
        val expected = listOf(
            "RelayGatewayMessage/v2",
            "m", "SAFETY", "REPORT", "HIGH", "RECEIVED",
            "1", "10", "9", "0", "0", "8", "origin",
            """{"a":true,"b":[1,null,{"a":"x"}]}""",
            "", // reportSignature absent
            "1",
        ).joinToString("") { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            "${bytes.size}:$field;"
        }.toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, message.canonicalBytes())
    }

    @Test
    fun `hex alphabet boundary characters are classified exactly`() {
        val verifier = verifierFor("k")
        // Chars one step outside each accepted range must be MALFORMED_SIGNATURE.
        for (bad in listOf("/0", ":0", "`0", "g0", "@0", "G0", "0")) {
            assertEquals(
                bad,
                GatewayVerificationResult.MALFORMED_SIGNATURE,
                integrityWithSignature(bad).verifyWith(verifier),
            )
        }
        // Boundary chars of each range must parse as hex: result is a signature
        // failure, never a malformed-classification failure.
        assertEquals(GatewayVerificationResult.INVALID_SIGNATURE, integrityWithSignature("09afAF").verifyWith(verifier))
    }

    @Test
    fun `signature size limits are enforced at exactly 512 bytes`() {
        val verifier = verifierFor("k")
        assertEquals(GatewayVerificationResult.MALFORMED_SIGNATURE, integrityWithSignature("").verifyWith(verifier))
        assertEquals(
            GatewayVerificationResult.INVALID_SIGNATURE,
            integrityWithSignature("AB".repeat(512)).verifyWith(verifier),
        )
        assertEquals(
            GatewayVerificationResult.MALFORMED_SIGNATURE,
            integrityWithSignature("AB".repeat(513)).verifyWith(verifier),
        )
    }

    @Test
    fun `keyId length limit is enforced at exactly 128 characters`() {
        val longKeyId = "k".repeat(128)
        val signed = baseMessage.signWith(EcdsaP256GatewayMessageSigner(longKeyId, keys.private))
        assertEquals(GatewayVerificationResult.VERIFIED, signed.verifyWith(verifierFor(longKeyId)))

        val tooLongKeyId = "k".repeat(129)
        val overSigned = baseMessage.signWith(EcdsaP256GatewayMessageSigner(tooLongKeyId, keys.private))
        assertEquals(GatewayVerificationResult.UNKNOWN_KEY, overSigned.verifyWith(verifierFor(tooLongKeyId)))
    }

    @Test
    fun `unsupported algorithm and blank keyId are rejected before key lookup`() {
        val verifier = verifierFor("k")
        val wrongAlgorithm = baseMessage.copy(integrity = GatewayIntegrity("SHA1withRSA", "k", "AB"))
        assertEquals(GatewayVerificationResult.UNSUPPORTED_ALGORITHM, wrongAlgorithm.verifyWith(verifier))
        val blankKey = baseMessage.copy(integrity = GatewayIntegrity(GATEWAY_SIGNATURE_ALGORITHM, "", "AB"))
        assertEquals(GatewayVerificationResult.UNKNOWN_KEY, blankKey.verifyWith(verifier))
    }

    @Test
    fun `verifyForProtocol dispatches on version and integrity presence`() {
        val signed = baseMessage.signWith(EcdsaP256GatewayMessageSigner("k", keys.private))
        val verifier = verifierFor("k")
        // v1 with a signature present must actually verify it, not skip it.
        assertEquals(GatewayVerificationResult.VERIFIED, signed.verifyForProtocol(GATEWAY_PROTOCOL_V1, verifier))
        assertEquals(GatewayVerificationResult.UNSIGNED, baseMessage.verifyForProtocol(GATEWAY_PROTOCOL_V1, verifier))
        assertEquals(GatewayVerificationResult.UNSIGNED, baseMessage.verifyForProtocol(GATEWAY_PROTOCOL_V2, verifier))
        assertEquals(GatewayVerificationResult.UNSUPPORTED_ALGORITHM, signed.verifyForProtocol(99, verifier))
    }

    @Test
    fun `rejecting verifier never verifies and noop signer never signs`() {
        assertEquals(GatewayVerificationResult.UNSIGNED, baseMessage.verifyWith(RejectingGatewayMessageVerifier))
        val withIntegrity = baseMessage.copy(integrity = GatewayIntegrity(GATEWAY_SIGNATURE_ALGORITHM, "k", "AB"))
        assertEquals(GatewayVerificationResult.UNKNOWN_KEY, withIntegrity.verifyWith(RejectingGatewayMessageVerifier))
        assertEquals("NONE", NoOpGatewayMessageSigner.algorithm)
        assertEquals("", NoOpGatewayMessageSigner.keyId)
        assertEquals(null, NoOpGatewayMessageSigner.sign(ByteArray(1)))
    }

    @Test
    fun `supported protocol versions are exactly v1 and v2`() {
        assertEquals(setOf(GATEWAY_PROTOCOL_V1, GATEWAY_PROTOCOL_V2), SUPPORTED_GATEWAY_PROTOCOL_VERSIONS)
        assertTrue(isSupportedGatewayProtocolVersion(GATEWAY_PROTOCOL_V1))
        assertTrue(isSupportedGatewayProtocolVersion(GATEWAY_PROTOCOL_V2))
        assertFalse(isSupportedGatewayProtocolVersion(0))
        assertFalse(isSupportedGatewayProtocolVersion(3))
    }

    @Test
    fun `response and rejection DTOs round trip with non-default values`() {
        val receipt = GatewayReceipt(
            "r", "m", VERIFIED_GATEWAY_RECEIPT_TYPE, "actor", 42,
            GatewayIntegrity(GATEWAY_SIGNATURE_ALGORITHM, "k", "AB"),
        )
        val response = SyncMessagesResponse(
            protocolVersion = GATEWAY_PROTOCOL_V2,
            acceptedMessageIds = listOf("a1"),
            duplicateMessageIds = listOf("d1"),
            rejected = listOf(GatewayRejection("m1", "expired")),
            receipts = listOf(receipt),
        )
        assertEquals(response, roundTrip(SyncMessagesResponse.serializer(), response))

        val receiptResponse = ReceiptResponse(GATEWAY_PROTOCOL_V2, listOf(receipt))
        assertEquals(receiptResponse, roundTrip(ReceiptResponse.serializer(), receiptResponse))

        val request = SyncMessagesRequest(GATEWAY_PROTOCOL_V2, "bridge", "Android", listOf(baseMessage))
        assertEquals(request, roundTrip(SyncMessagesRequest.serializer(), request))
    }

    @Test
    fun `DTO properties expose exactly the constructed values`() {
        // data-class equals() reads backing fields, so property getters need
        // explicit reads or getter mutations go undetected.
        val receipt = GatewayReceipt(
            "r", "m", VERIFIED_GATEWAY_RECEIPT_TYPE, "actor", 42,
            GatewayIntegrity(GATEWAY_SIGNATURE_ALGORITHM, "k", "AB"),
        )
        assertEquals("r", receipt.receiptId)
        assertEquals("m", receipt.messageId)
        assertEquals(VERIFIED_GATEWAY_RECEIPT_TYPE, receipt.receiptType)
        assertEquals("actor", receipt.actorId)
        assertEquals(42L, receipt.recordedAt)
        assertEquals(GatewayIntegrity(GATEWAY_SIGNATURE_ALGORITHM, "k", "AB"), receipt.integrity)

        val response = SyncMessagesResponse(
            protocolVersion = GATEWAY_PROTOCOL_V2,
            acceptedMessageIds = listOf("a1"),
            duplicateMessageIds = listOf("d1"),
            rejected = listOf(GatewayRejection("m1", "expired")),
            receipts = listOf(receipt),
        )
        assertEquals(GATEWAY_PROTOCOL_V2, response.protocolVersion)
        assertEquals(listOf("a1"), response.acceptedMessageIds)
        assertEquals(listOf("d1"), response.duplicateMessageIds)
        assertEquals("m1", response.rejected.single().messageId)
        assertEquals("expired", response.rejected.single().reason)
        assertEquals(listOf(receipt), response.receipts)

        val request = SyncMessagesRequest(GATEWAY_PROTOCOL_V2, "bridge", "Android", listOf(baseMessage))
        assertEquals(GATEWAY_PROTOCOL_V2, request.protocolVersion)
        assertEquals("bridge", request.bridgeId)
        assertEquals("Android", request.bridgeName)
        assertEquals(listOf(baseMessage), request.messages)

        val receiptResponse = ReceiptResponse(GATEWAY_PROTOCOL_V2, listOf(receipt))
        assertEquals(GATEWAY_PROTOCOL_V2, receiptResponse.protocolVersion)
        assertEquals(listOf(receipt), receiptResponse.receipts)
    }

    private fun <T> roundTrip(serializer: kotlinx.serialization.KSerializer<T>, value: T): T =
        json.decodeFromString(serializer, json.encodeToString(serializer, value))
}
