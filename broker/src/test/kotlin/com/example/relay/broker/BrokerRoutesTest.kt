@file:Suppress("MaxLineLength")
package com.example.relay.broker

import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.authenticatedHeaderBytes
import com.example.relay.rescue.brokerDeviceRegistrationBytes
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BrokerRoutesTest {
    private lateinit var store: BrokerStore
    private lateinit var dbFile: File
    private lateinit var deviceKeyPair: java.security.KeyPair
    private val deviceKeyId = "test-device-uuid"

    @Before
    fun setup() {
        dbFile = File.createTempFile("broker-routes-test", ".db")
        dbFile.deleteOnExit()
        store = BrokerStore(dbFile.absolutePath)
        // Generate a real EC P-256 key pair for signing
        deviceKeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
    }

    @After
    fun teardown() {
        store.close()
        dbFile.delete()
    }

    private fun registerDevice() {
        val publicKeyBase64 = Base64.getEncoder().encodeToString(deviceKeyPair.public.encoded)
        store.registerDevice(deviceKeyId, publicKeyBase64, System.currentTimeMillis())
    }

    private fun signEnvelope(envelope: EncryptedRescueEnvelope): String {
        val dataToSign = envelope.authenticatedHeaderBytes() + envelope.ciphertextSha256Hex.encodeToByteArray()
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(deviceKeyPair.private)
        sig.update(dataToSign)
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    private fun registrationSignature(
        keyPair: java.security.KeyPair,
        keyId: String,
        publicKeyBase64: String,
    ): String {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(brokerDeviceRegistrationBytes(keyId, publicKeyBase64))
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    private fun registrationJson(
        keyPair: java.security.KeyPair = deviceKeyPair,
        keyId: String = deviceKeyId,
    ): String {
        val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        return brokerJson.encodeToString(
            BrokerDeviceRegisterRequest.serializer(),
            BrokerDeviceRegisterRequest(
                keyId,
                publicKeyBase64,
                registrationSignature(keyPair, keyId, publicKeyBase64),
            ),
        )
    }

    private fun testEnvelope(
        envelopeId: String = "env-001",
        requestId: String = "req-001",
        requestVersion: Int = 1,
        shelterId: String = "example-01",
        ciphertextHash: String = "a".repeat(64),
        createdAt: Long = System.currentTimeMillis(),
        expiresAt: Long = System.currentTimeMillis() + 3_600_000,
    ) = EncryptedRescueEnvelope(
        envelopeId = envelopeId,
        requestId = requestId,
        requestVersion = requestVersion,
        senderDeviceId = "device-001",
        destinationShelterId = shelterId,
        routingUrgency = RescueUrgency.IMMEDIATE,
        recipientKeyId = "key-001",
        createdAtEpochMillis = createdAt,
        expiresAtEpochMillis = expiresAt,
        ciphertextSizeBytes = 256,
        wrappedContentKeyBase64 = "A".repeat(172),
        nonceBase64 = "B".repeat(24),
        ciphertextBase64 = "C".repeat(344),
        ciphertextSha256Hex = ciphertextHash,
    )

    private fun uploadJson(envelope: EncryptedRescueEnvelope): String {
        val request = BrokerUploadRequest(envelope, deviceKeyId, signEnvelope(envelope))
        return brokerJson.encodeToString(BrokerUploadRequest.serializer(), request)
    }

    @Test
    fun `upload returns 201 for valid envelope`() = testApplication {
        application { brokerModule(store) }
        registerDevice()
        val response = client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody(uploadJson(testEnvelope()))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("BROKER_STORED"))
        assertTrue(body.contains("env-001"))
    }

    @Test
    fun `upload returns 401 for unregistered device`() = testApplication {
        application { brokerModule(store) }
        // Do NOT register device
        val response = client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody(uploadJson(testEnvelope()))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("device_not_registered"))
    }

    @Test
    fun `upload returns 200 for duplicate envelope`() = testApplication {
        application { brokerModule(store) }
        registerDevice()
        val body = uploadJson(testEnvelope())
        val first = client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val response = client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(first.bodyAsText(), response.bodyAsText())
    }

    @Test
    fun `upload returns 409 for collision`() = testApplication {
        application { brokerModule(store) }
        registerDevice()
        client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody(uploadJson(testEnvelope(ciphertextHash = "a".repeat(64))))
        }
        val response = client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody(uploadJson(testEnvelope(envelopeId = "env-002", ciphertextHash = "b".repeat(64))))
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("ciphertext_collision"))
    }

    @Test
    fun `upload returns 400 for expired envelope`() = testApplication {
        application { brokerModule(store) }
        registerDevice()
        // Both createdAt and expiresAt in the past, but expiresAt > createdAt (valid lifetime)
        val pastCreated = System.currentTimeMillis() - 7_200_000 // 2 hours ago
        val pastExpired = System.currentTimeMillis() - 1_000 // 1 second ago
        val response = client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody(uploadJson(testEnvelope(createdAt = pastCreated, expiresAt = pastExpired)))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("envelope_expired"))
    }

    @Test
    fun `upload returns 400 for malformed body`() = testApplication {
        application { brokerModule(store) }
        val response = client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody("{invalid json")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `pull returns envelopes for shelter`() = testApplication {
        application { brokerModule(store, BrokerConfig(profile = BrokerProfile.PRODUCTION)) }
        registerDevice()
        client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody(uploadJson(testEnvelope()))
        }
        val credential = store.issueGatewayCredential("gateway-regional", "example-01", System.currentTimeMillis() + 60_000)
        val response = client.get("/v1/gateways/example-01/pull") {
            header("Authorization", "Bearer ${credential.token}")
            header("X-Gateway-Id", "gateway-regional")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("env-001"))
    }

    @Test
    fun `pull returns empty for unknown shelter`() = testApplication {
        application { brokerModule(store, BrokerConfig(profile = BrokerProfile.PRODUCTION)) }
        val credential = store.issueGatewayCredential("gateway-unknown", "unknown-shelter", System.currentTimeMillis() + 60_000)
        val response = client.get("/v1/gateways/unknown-shelter/pull") {
            header("Authorization", "Bearer ${credential.token}")
            header("X-Gateway-Id", "gateway-unknown")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"envelopes\":[]"))
    }

    @Test
    fun `pull requires scoped credential`() = testApplication {
        application { brokerModule(store, BrokerConfig(profile = BrokerProfile.PRODUCTION)) }
        val response = client.get("/v1/gateways/example-01/pull")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `credential cannot pull another shelter queue`() = testApplication {
        application { brokerModule(store, BrokerConfig(profile = BrokerProfile.PRODUCTION)) }
        val credential = store.issueGatewayCredential("gateway-a", "example-01", System.currentTimeMillis() + 60_000)
        val response = client.get("/v1/gateways/other-shelter/pull") {
            header("Authorization", "Bearer ${credential.token}")
            header("X-Gateway-Id", "gateway-a")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("gateway_scope_mismatch"))
    }

    @Test
    fun `credential cannot upload receipt for another shelter`() = testApplication {
        application { brokerModule(store, BrokerConfig(profile = BrokerProfile.PRODUCTION)) }
        val credential = store.issueGatewayCredential("gateway-a", "example-01", System.currentTimeMillis() + 60_000)
        val receipt = BrokerReceiptUpload(
            receipt = com.example.relay.rescue.SignedShelterReceipt(
                receipt = com.example.relay.rescue.UnsignedShelterReceipt(
                    receiptId = "receipt-cross-shelter",
                    envelopeId = "unknown-envelope",
                    requestId = "request-cross-shelter",
                    requestVersion = 1,
                    ciphertextSha256Hex = "a".repeat(64),
                    shelterId = "other-shelter",
                    receivedAtEpochMillis = System.currentTimeMillis(),
                    status = com.example.relay.rescue.ShelterReceiptStatus.ACCEPTED,
                ),
                signerKeyId = "signer",
                signatureBase64 = "D".repeat(88),
            ),
            gatewayId = "gateway-a",
        )
        val response = client.post("/v1/gateways/other-shelter/receipts") {
            header("Authorization", "Bearer ${credential.token}")
            header("X-Gateway-Id", "gateway-a")
            contentType(ContentType.Application.Json)
            setBody(brokerJson.encodeToString(BrokerReceiptUpload.serializer(), receipt))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `health returns status`() = testApplication {
        application { brokerModule(store) }
        val credential = store.issueGatewayCredential("health-gateway", "health-shelter", System.currentTimeMillis() + 60_000)
        val response = client.get("/v1/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"status\":\"ok\""))
        assertFalse(body.contains(credential.token))
        assertFalse(body.contains("health-gateway"))
    }

    @Test
    fun `receipts returns 401 for invalid token so Android can re-register`() = testApplication {
        application { brokerModule(store) }
        val response = client.get("/v1/receipts?sinceSeq=0") {
            header("Authorization", "Bearer invalid-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("invalid_capability_token"))
    }

    @Test
    fun `device registration returns capability token`() = testApplication {
        application { brokerModule(store) }
        val response = client.post("/v1/devices/register") {
            contentType(ContentType.Application.Json)
            setBody(registrationJson())
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("capabilityToken"))
        assertTrue(body.contains(deviceKeyId))
    }

    @Test
    fun `device registration rejects malformed EC public key`() = testApplication {
        application { brokerModule(store) }
        val response = client.post("/v1/devices/register") {
            contentType(ContentType.Application.Json)
            setBody(
                brokerJson.encodeToString(
                    BrokerDeviceRegisterRequest.serializer(),
                    BrokerDeviceRegisterRequest(deviceKeyId, "not-base64", "not-a-signature"),
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("invalid_public_key"))
    }

    @Test
    fun `device token recovery requires proof from the registered private key`() = testApplication {
        application { brokerModule(store) }
        val validBody = registrationJson()
        assertEquals(
            HttpStatusCode.OK,
            client.post("/v1/devices/register") {
                contentType(ContentType.Application.Json)
                setBody(validBody)
            }.status,
        )
        val publicKeyBase64 = Base64.getEncoder().encodeToString(deviceKeyPair.public.encoded)
        val attacker = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val forged = BrokerDeviceRegisterRequest(
            deviceKeyId,
            publicKeyBase64,
            registrationSignature(attacker, deviceKeyId, publicKeyBase64),
        )
        val response = client.post("/v1/devices/register") {
            contentType(ContentType.Application.Json)
            setBody(brokerJson.encodeToString(BrokerDeviceRegisterRequest.serializer(), forged))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("invalid_registration_proof"))
    }

    @Test
    fun `device registration rejects a different key for an existing identity`() = testApplication {
        application { brokerModule(store) }
        assertEquals(
            HttpStatusCode.OK,
            client.post("/v1/devices/register") {
                contentType(ContentType.Application.Json)
                setBody(registrationJson())
            }.status,
        )
        val replacement = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val response = client.post("/v1/devices/register") {
            contentType(ContentType.Application.Json)
            setBody(registrationJson(replacement, deviceKeyId))
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("device_key_conflict"))
    }
}
