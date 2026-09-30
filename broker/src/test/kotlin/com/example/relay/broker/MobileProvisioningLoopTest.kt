package com.example.relay.broker

import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueKeyAlgorithm
import com.example.relay.rescue.RescueKeyPair
import com.example.relay.rescue.RescuePayload
import com.example.relay.rescue.RescuePublicKey
import com.example.relay.rescue.RescueSupportNeed
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.RescueValidationResult
import com.example.relay.rescue.ShelterPublicKeyManifest
import com.example.relay.rescue.authenticatedHeaderBytes
import com.example.relay.rescue.brokerDeviceRegistrationBytes
import com.example.relay.rescue.validate
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CI-gated proof that a phone which has NEVER joined the shelter LAN can still deliver an SOS
 * end-to-end over the Broker alone ("絶対モバイル通信でもできる"). Runs fully in-process; no live
 * tunnel and no network, so it is a hard gate rather than an ops-only smoke test.
 *
 * The whole loop is exercised against the real Broker HTTP surface:
 *   1. Gateway generates its real recipient/signing keys and PUBLISHES a public manifest.
 *   2. Phone (mobile data only) FETCHES that manifest from the Broker — its sole source of the key.
 *   3. Phone encrypts with the FETCHED recipient key and uploads a signed envelope.
 *   4. Gateway pulls the envelope and DECRYPTS it with the matching private key.
 * Step 4 succeeding proves the key the phone obtained purely over the Broker is the gateway's real
 * key, closing the provisioning gap that previously required LAN enrollment.
 */
class MobileProvisioningLoopTest {
    private val shelterId = "development-pc-gateway"
    private val gatewayId = "dev-gw"

    private lateinit var store: BrokerStore
    private lateinit var dbFile: File

    // Gateway-owned keys. Only the public halves ever leave the gateway (inside the manifest).
    private lateinit var recipient: RescueKeyPair
    private lateinit var signer: RescueKeyPair

    // Phone-owned device key used to authenticate uploads to the Broker.
    private lateinit var deviceKeyPair: java.security.KeyPair
    private val deviceKeyId = "phone-device-uuid"

    @Before
    fun setup() {
        dbFile = File.createTempFile("broker-mobile-loop-test", ".db").apply { deleteOnExit() }
        store = BrokerStore(dbFile.absolutePath)
        recipient = RescueCryptography.generateRecipientKeyPair()
        signer = RescueCryptography.generateShelterSigningKeyPair()
        deviceKeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
    }

    @After
    fun teardown() {
        store.close()
        dbFile.delete()
    }

    @Test
    fun `phone with no LAN enrollment delivers an SOS using only the broker-relayed manifest`() = testApplication {
        application { brokerModule(store, BrokerConfig(profile = BrokerProfile.PRODUCTION)) }
        val credential = store.issueGatewayCredential(gatewayId, shelterId, System.currentTimeMillis() + 120_000)

        // 1. Gateway publishes its public manifest to the Broker.
        val gatewayManifest = gatewayManifest()
        val publish = client.post("/v1/gateways/$shelterId/manifest") {
            header("Authorization", "Bearer ${credential.token}")
            header("X-Gateway-Id", gatewayId)
            contentType(ContentType.Application.Json)
            setBody(brokerJson.encodeToString(ShelterPublicKeyManifest.serializer(), gatewayManifest))
        }
        assertEquals(HttpStatusCode.Accepted, publish.status)

        // 2. Phone fetches the manifest from the Broker over "mobile data" — its only key source.
        val fetchResponse = client.get("/v1/shelters/$shelterId/manifest")
        assertEquals(HttpStatusCode.OK, fetchResponse.status)
        val fetchedManifest =
            brokerJson.decodeFromString(ShelterPublicKeyManifest.serializer(), fetchResponse.bodyAsText())
        assertEquals(
            "fetched manifest must validate as a current key document",
            RescueValidationResult.Valid,
            fetchedManifest.validate(System.currentTimeMillis()),
        )
        val recipientKeyFromBroker = fetchedManifest.recipientPublicKey

        // 3a. Phone registers its device key with the Broker so uploads can be authenticated.
        val devicePublicKeyBase64 = Base64.getEncoder().encodeToString(deviceKeyPair.public.encoded)
        val register = client.post("/v1/devices/register") {
            contentType(ContentType.Application.Json)
            setBody(
                brokerJson.encodeToString(
                    BrokerDeviceRegisterRequest.serializer(),
                    BrokerDeviceRegisterRequest(
                        deviceKeyId,
                        devicePublicKeyBase64,
                        ecdsaSign(brokerDeviceRegistrationBytes(deviceKeyId, devicePublicKeyBase64)),
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, register.status)

        // 3b. Phone encrypts with the recipient key it just fetched from the Broker, then uploads.
        val envelope = RescueCryptography.encrypt(
            payload = sosPayload(),
            recipientPublicKey = recipientKeyFromBroker,
            envelopeId = "mobile-env-001",
        )
        val uploadSignature = ecdsaSign(
            envelope.authenticatedHeaderBytes() + envelope.ciphertextSha256Hex.encodeToByteArray(),
        )
        val upload = client.post("/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody(
                brokerJson.encodeToString(
                    BrokerUploadRequest.serializer(),
                    BrokerUploadRequest(envelope, deviceKeyId, uploadSignature),
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, upload.status)

        // 4. Gateway pulls the envelope and decrypts it with the matching private key.
        val pull = client.get("/v1/gateways/$shelterId/pull?limit=50") {
            header("Authorization", "Bearer ${credential.token}")
            header("X-Gateway-Id", gatewayId)
        }
        assertEquals(HttpStatusCode.OK, pull.status)
        val batch = brokerJson.decodeFromString(BrokerEnvelopeBatch.serializer(), pull.bodyAsText())
        val pulled: EncryptedRescueEnvelope = batch.envelopes.firstOrNull { it.envelopeId == "mobile-env-001" }
            ?: error("gateway did not pull the uploaded envelope from the broker")

        val decrypted = RescueCryptography.decrypt(pulled, recipient.privateKey)
        assertEquals("req-mobile-001", decrypted.requestId)
        assertEquals(shelterId, decrypted.destinationShelterId)
        assertEquals(RescueUrgency.IMMEDIATE, decrypted.urgency)
        assertTrue("payload must survive the round trip", decrypted.freeText.contains("mobile-only"))
        // The recipient key the phone used came only from the broker relay, yet the gateway's own
        // private key decrypts it: the mobile-only provisioning path is proven end to end.
        assertNotNull(decrypted)
    }

    private fun gatewayManifest(): ShelterPublicKeyManifest {
        val now = System.currentTimeMillis()
        return ShelterPublicKeyManifest(
            shelterId = shelterId,
            recipientPublicKey = RescuePublicKey(
                recipient.publicKey.keyId,
                RescueKeyAlgorithm.RSA_OAEP_SHA256,
                recipient.publicKey.encodedBase64,
            ),
            receiptSigningPublicKey = RescuePublicKey(
                signer.publicKey.keyId,
                RescueKeyAlgorithm.ECDSA_P256_SHA256,
                signer.publicKey.encodedBase64,
            ),
            validFromEpochMillis = now - 60_000,
            validUntilEpochMillis = now + 3_600_000,
            generation = 1,
        )
    }

    private fun sosPayload(): RescuePayload {
        val now = System.currentTimeMillis()
        val payload = RescuePayload(
            requestId = "req-mobile-001",
            requestVersion = 1,
            senderDeviceId = "member-phone-001",
            destinationShelterId = shelterId,
            createdAtEpochMillis = now,
            expiresAtEpochMillis = now + 3_600_000,
            urgency = RescueUrgency.IMMEDIATE,
            personCount = 2,
            injured = true,
            supportNeeds = setOf(RescueSupportNeed.RESCUE_TEAM),
            freeText = "mobile-only provisioning proof",
        )
        require(payload.validate() == RescueValidationResult.Valid) { "test payload must be valid" }
        return payload
    }

    private fun ecdsaSign(data: ByteArray): String {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(deviceKeyPair.private)
        sig.update(data)
        return Base64.getEncoder().encodeToString(sig.sign())
    }
}
