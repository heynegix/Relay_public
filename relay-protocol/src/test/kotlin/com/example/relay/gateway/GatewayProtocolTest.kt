package com.example.relay.gateway

import com.example.relay.gateway.protocol.GatewayMessage
import com.example.relay.gateway.protocol.GatewayReceipt
import com.example.relay.gateway.protocol.GATEWAY_PROTOCOL_VERSION
import com.example.relay.gateway.protocol.GATEWAY_PROTOCOL_V1
import com.example.relay.gateway.protocol.GATEWAY_PROTOCOL_V2
import com.example.relay.gateway.protocol.EcdsaP256GatewayMessageSigner
import com.example.relay.gateway.protocol.EcdsaP256GatewayMessageVerifier
import com.example.relay.gateway.protocol.GatewayVerificationResult
import com.example.relay.gateway.protocol.NoOpGatewayMessageSigner
import com.example.relay.gateway.protocol.SyncMessagesRequest
import com.example.relay.gateway.protocol.canonicalBytes
import com.example.relay.gateway.protocol.signWith
import com.example.relay.gateway.protocol.verifyForProtocol
import com.example.relay.gateway.protocol.verifyWith
import java.security.KeyPairGenerator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayProtocolTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test fun `REPORT DTO round trips with protocol version`() {
        val message = GatewayMessage("m", "SAFETY", "REPORT", "HIGH", "RECEIVED", 1, 10, 9, 0, 0, 8, "origin", JsonPrimitive("payload"), 1)
        val encoded = json.encodeToString(GatewayMessage.serializer(), message)
        val decoded = json.decodeFromString(GatewayMessage.serializer(), encoded)
        assertEquals(message, decoded)
        assertEquals(1, GATEWAY_PROTOCOL_VERSION)
        assertEquals(GATEWAY_PROTOCOL_V1, GATEWAY_PROTOCOL_VERSION)
    }

    @Test fun `Receipt keeps gateway semantics and actor`() {
        val receipt = GatewayReceipt("r", "m", "GATEWAY_RECEIVED", "gateway", 10)
        assertEquals(receipt, json.decodeFromString(GatewayReceipt.serializer(), json.encodeToString(GatewayReceipt.serializer(), receipt)))
    }

    @Test fun `legacy v1 JSON without integrity remains readable`() {
        val legacy = """{"messageId":"m","messageType":"SAFETY","recordType":"REPORT","priority":"HIGH","status":"RECEIVED","createdAt":1,"expiresAt":10,"lifetimeMs":9,"accumulatedAgeMs":0,"hopCount":0,"hopLimit":8,"originDeviceId":"origin","payload":"payload","receivedAt":1}"""
        val decoded = json.decodeFromString<GatewayMessage>(legacy)
        assertEquals(null, decoded.integrity)
        assertEquals(GatewayVerificationResult.UNSIGNED, decoded.verifyForProtocol(GATEWAY_PROTOCOL_V1, EcdsaP256GatewayMessageVerifier { null }))
    }

    @Test fun `v2 signed message round trips and verifies on a separate verifier`() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val unsigned = GatewayMessage("m", "SAFETY", "REPORT", "HIGH", "RECEIVED", 1, 10, 9, 0, 0, 8, "origin", JsonPrimitive("payload"), 1)
        val signed = unsigned.signWith(EcdsaP256GatewayMessageSigner("device-key-1", keys.private))
        val request = SyncMessagesRequest(GATEWAY_PROTOCOL_V2, "bridge", "Android", listOf(signed))
        val decoded = json.decodeFromString<SyncMessagesRequest>(json.encodeToString(request))
        val verifier = EcdsaP256GatewayMessageVerifier { keyId -> if (keyId == "device-key-1") keys.public else null }
        assertEquals(GatewayVerificationResult.VERIFIED, decoded.messages.single().verifyForProtocol(decoded.protocolVersion, verifier))
    }

    @Test fun `canonical payload ignores JSON object insertion order but detects tampering`() {
        val first = GatewayMessage("m", "SAFETY", "REPORT", "HIGH", "RECEIVED", 1, 10, 9, 0, 0, 8, "origin", buildJsonObject { put("b", 2); put("a", 1) }, 1)
        val reordered = first.copy(payload = buildJsonObject { put("a", 1); put("b", 2) })
        assertEquals(first.canonicalBytes().toList(), reordered.canonicalBytes().toList())

        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signed = first.signWith(EcdsaP256GatewayMessageSigner("key", keys.private))
        val verifier = EcdsaP256GatewayMessageVerifier { keys.public }
        assertEquals(GatewayVerificationResult.VERIFIED, signed.verifyWith(verifier))
        assertEquals(GatewayVerificationResult.INVALID_SIGNATURE, signed.copy(hopCount = 1).verifyWith(verifier))
    }

    @Test fun `report signature is carried and bound to gateway v2 integrity`() {
        val signature = buildJsonObject { put("signerKeyId", "device-key"); put("signatureBase64", "A".repeat(64)) }
        val message = GatewayMessage("m", "SAFETY", "REPORT", "HIGH", "RECEIVED", 1, 10, 9, 0, 0, 8, "origin", JsonPrimitive("payload"), 1, reportSignature = signature)
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signed = message.signWith(EcdsaP256GatewayMessageSigner("gateway-key", keys.private))
        val verifier = EcdsaP256GatewayMessageVerifier { keys.public }
        assertEquals(GatewayVerificationResult.VERIFIED, signed.verifyWith(verifier))
        assertEquals(GatewayVerificationResult.INVALID_SIGNATURE, signed.copy(reportSignature = null).verifyWith(verifier))
    }

    @Test fun `NoOp signer never creates trusted integrity`() {
        val message = GatewayMessage("m", "SAFETY", "REPORT", "HIGH", "RECEIVED", 1, 10, 9, 0, 0, 8, "origin", JsonPrimitive("payload"), 1)
        assertEquals(null, message.signWith(NoOpGatewayMessageSigner).integrity)
    }
}
