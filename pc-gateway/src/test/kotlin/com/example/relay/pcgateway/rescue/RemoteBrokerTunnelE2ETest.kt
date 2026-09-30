package com.example.relay.pcgateway.rescue

import com.example.relay.broker.BrokerDeviceRegisterRequest
import com.example.relay.broker.BrokerDeviceRegisterResponse
import com.example.relay.broker.BrokerReceiptBatch
import com.example.relay.broker.BrokerUploadRequest
import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueKeyPair
import com.example.relay.rescue.RescuePayload
import com.example.relay.rescue.RescueSupportNeed
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.ShelterReceiptStatus
import com.example.relay.rescue.authenticatedHeaderBytes
import com.example.relay.rescue.brokerDeviceRegistrationBytes
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test

/**
 * LIVE end-to-end over a real ngrok tunnel (manual/ops verification, NOT a CI gate).
 *
 * Skips unless RELAY_E2E_BROKER_URL is set, so it never runs in CI. When the env is present it
 * drives the exact mobile-data path against the remote Broker:
 *   device register -> signed upload -> Gateway pull+decrypt -> signed receipt -> Broker -> device poll,
 * all over HTTPS through the public ngrok domain, with the same ngrok interstitial-skip header the
 * Android client sends.
 *
 * Required env:
 *   RELAY_E2E_BROKER_URL   e.g. https://broker.example.invalid
 *   RELAY_E2E_CREDENTIAL   a Gateway Bearer credential issued for the shelter/gateway below
 *   RELAY_E2E_SHELTER_ID   shelter id the credential is scoped to (default verify-01)
 *   RELAY_E2E_GATEWAY_ID   gateway id the credential is scoped to (default verify-gw)
 */
class RemoteBrokerTunnelE2ETest {
    private val wire = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val brokerUrl = (System.getenv("RELAY_E2E_BROKER_URL") ?: "").trim().trimEnd('/')
    private val credential = (System.getenv("RELAY_E2E_CREDENTIAL") ?: "").trim()
    private val shelterId = (System.getenv("RELAY_E2E_SHELTER_ID") ?: "verify-01").trim()
    private val gatewayId = (System.getenv("RELAY_E2E_GATEWAY_ID") ?: "verify-gw").trim()

    // Unique per run so re-runs against the shared remote Broker never collide on dedupe.
    private val stamp = System.currentTimeMillis()
    private val deviceKeyId = "e2e-device-$stamp"
    private val envelopeId = "e2e-env-$stamp"
    private val requestId = "e2e-req-$stamp"

    private lateinit var gatewayDbFile: File
    private lateinit var gatewayPersistence: SqliteRescuePersistence
    private lateinit var httpClient: HttpClient
    private lateinit var recipient: RescueKeyPair
    private lateinit var signer: RescueKeyPair
    private lateinit var deviceKeyPair: java.security.KeyPair

    @Before
    fun setup() {
        assumeFalse("RELAY_E2E_BROKER_URL not set; skipping live tunnel E2E", brokerUrl.isBlank())
        gatewayDbFile = File.createTempFile("remote-e2e-gateway", ".db").apply { deleteOnExit() }
        gatewayPersistence = SqliteRescuePersistence(
            dbPath = gatewayDbFile.absolutePath,
            json = Json { ignoreUnknownKeys = false; encodeDefaults = true },
        )
        recipient = RescueCryptography.generateRecipientKeyPair()
        signer = RescueCryptography.generateShelterSigningKeyPair()
        deviceKeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        httpClient = HttpClient(CIO)
    }

    @After
    fun teardown() {
        if (::httpClient.isInitialized) httpClient.close()
        if (::gatewayPersistence.isInitialized) gatewayPersistence.close()
        if (::gatewayDbFile.isInitialized) gatewayDbFile.delete()
    }

    @Test
    fun `mobile-data path delivers a verifiable signed receipt through the live ngrok tunnel`() = runBlocking {
        // 1. Device registers with the remote Broker over HTTPS and obtains its receipt token.
        val capabilityToken = registerDevice()

        // 2. Device encrypts and uploads a signed rescue envelope destined for this shelter.
        val envelope = RescueCryptography.encrypt(
            payload = memberPayload(),
            recipientPublicKey = recipient.publicKey,
            envelopeId = envelopeId,
        )
        uploadEnvelope(envelope)

        // 3. Gateway components (real production classes) pull from the remote Broker and decrypt.
        val outbox = ReceiptOutbox(
            persistence = gatewayPersistence,
            brokerUrl = brokerUrl,
            shelterId = shelterId,
            gatewayId = gatewayId,
            httpClient = httpClient,
            gatewayCredential = credential,
        )
        val intake = RescueIntakeService(
            shelterId = shelterId,
            recipientPrivateKey = recipient.privateKey,
            shelterSigningPrivateKey = signer.privateKey,
            persistence = gatewayPersistence,
            onReceiptIssued = { receipt -> outbox.enqueue(receipt) },
        )
        val pullAgent = BrokerPullAgent(
            brokerUrl = brokerUrl,
            shelterId = shelterId,
            gatewayId = gatewayId,
            intakeService = intake,
            httpClient = httpClient,
            gatewayCredential = credential,
        )

        // Pull may need a couple of attempts if the Broker has other pending traffic ahead of ours.
        var ingested = 0
        repeat(10) {
            ingested += pullAgent.pullOnce()
            if (intake.receipt(requestId) != null) return@repeat
        }
        val storedReceipt = intake.receipt(requestId)
            ?: error("gateway did not ingest the uploaded envelope from the remote Broker")
        assertEquals(ShelterReceiptStatus.STORED, storedReceipt.receipt.status)
        assertTrue(
            "receipt must verify against the shelter signing public key",
            RescueCryptography.verifyReceipt(storedReceipt, signer.publicKey),
        )

        // 4. Gateway flushes the receipt back to the remote Broker.
        val sent = outbox.flushPending()
        assertTrue("outbox should deliver at least our receipt", sent >= 1)

        // 5. Device polls the remote Broker and receives the same signed receipt end-to-end.
        val batch = pollReceipts(capabilityToken)
        val delivered = batch.receipts.firstOrNull { it.receipt.envelopeId == envelopeId }
            ?: error("device did not receive its receipt from the remote Broker")
        assertEquals(requestId, delivered.receipt.requestId)
        assertEquals(shelterId, delivered.receipt.shelterId)
        assertEquals(ShelterReceiptStatus.STORED, delivered.receipt.status)
        assertTrue(
            "receipt returned to the device must verify against the shelter public key",
            RescueCryptography.verifyReceipt(delivered, signer.publicKey),
        )
    }

    private suspend fun registerDevice(): String {
        val publicKeyBase64 = Base64.getEncoder().encodeToString(deviceKeyPair.public.encoded)
        val request = BrokerDeviceRegisterRequest(
            deviceKeyId,
            publicKeyBase64,
            ecdsaSign(brokerDeviceRegistrationBytes(deviceKeyId, publicKeyBase64)),
        )
        val response = httpClient.post("$brokerUrl/v1/devices/register") {
            contentType(ContentType.Application.Json)
            header("ngrok-skip-browser-warning", "true")
            setBody(wire.encodeToString(BrokerDeviceRegisterRequest.serializer(), request))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return wire.decodeFromString(
            BrokerDeviceRegisterResponse.serializer(),
            response.bodyAsText(),
        ).capabilityToken
    }

    private suspend fun uploadEnvelope(envelope: EncryptedRescueEnvelope) {
        val signature = ecdsaSign(
            envelope.authenticatedHeaderBytes() + envelope.ciphertextSha256Hex.encodeToByteArray(),
        )
        val request = BrokerUploadRequest(envelope, deviceKeyId, signature)
        val response = httpClient.post("$brokerUrl/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            header("ngrok-skip-browser-warning", "true")
            setBody(wire.encodeToString(BrokerUploadRequest.serializer(), request))
        }
        assertTrue(
            "upload should be accepted (201 Created or 200 duplicate), got ${response.status}",
            response.status == HttpStatusCode.Created || response.status == HttpStatusCode.OK,
        )
    }

    private suspend fun pollReceipts(capabilityToken: String): BrokerReceiptBatch {
        val response = httpClient.get("$brokerUrl/v1/receipts?sinceSeq=0") {
            header("Authorization", "Bearer $capabilityToken")
            header("ngrok-skip-browser-warning", "true")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return wire.decodeFromString(BrokerReceiptBatch.serializer(), response.bodyAsText())
    }

    private fun ecdsaSign(data: ByteArray): String {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(deviceKeyPair.private)
        sig.update(data)
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    private fun memberPayload() = RescuePayload(
        requestId = requestId,
        requestVersion = 1,
        senderDeviceId = "member-$stamp",
        destinationShelterId = shelterId,
        createdAtEpochMillis = System.currentTimeMillis(),
        expiresAtEpochMillis = System.currentTimeMillis() + 3_600_000,
        urgency = RescueUrgency.IMMEDIATE,
        personCount = 3,
        injured = true,
        supportNeeds = setOf(RescueSupportNeed.RESCUE_TEAM),
        freeText = "live tunnel e2e",
    )
}
