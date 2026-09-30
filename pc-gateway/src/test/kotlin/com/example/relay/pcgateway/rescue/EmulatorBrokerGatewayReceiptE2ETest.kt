package com.example.relay.pcgateway.rescue

import com.example.relay.broker.BrokerConfig
import com.example.relay.broker.BrokerDeviceRegisterRequest
import com.example.relay.broker.BrokerDeviceRegisterResponse
import com.example.relay.broker.BrokerProfile
import com.example.relay.broker.BrokerReceiptBatch
import com.example.relay.broker.BrokerStore
import com.example.relay.broker.BrokerUploadRequest
import com.example.relay.broker.brokerModule
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
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end: member device (emulator) -> Broker -> PC Gateway -> signed receipt -> device.
 *
 * This exercises the full production path across real process boundaries:
 *   - the real Broker HTTP server (embeddedServer + Netty on a loopback port),
 *   - a persistent SQLite BrokerStore,
 *   - the exact device-side HTTP calls the Android client makes (register + signed upload),
 *   - the real Gateway components (RescueIntakeService + SqliteRescuePersistence,
 *     BrokerPullAgent.pullOnce(), ReceiptOutbox.flushPending()) wired exactly as MainKt does,
 *   - real RSA-OAEP-SHA256 recipient crypto and ECDSA-P256-SHA256 signing/verification.
 *
 * No mocks: every hop is the production code talking over HTTP and SQLite.
 */
class EmulatorBrokerGatewayReceiptE2ETest {
    private val wire = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val shelterId = "example-01"
    private val gatewayId = "gateway-regional"
    private val deviceKeyId = "member-device-uuid"
    private val envelopeId = "env-e2e-1"
    private val requestId = "req-e2e-1"

    private lateinit var brokerDbFile: File
    private lateinit var gatewayDbFile: File
    private lateinit var brokerStore: BrokerStore
    private lateinit var gatewayPersistence: SqliteRescuePersistence
    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var httpClient: HttpClient
    private lateinit var brokerUrl: String

    /** Shelter/recipient key material provisioned to the Gateway. */
    private lateinit var recipient: RescueKeyPair
    private lateinit var signer: RescueKeyPair

    /** Member device signing key (registered with the Broker). */
    private lateinit var deviceKeyPair: java.security.KeyPair

    @Before
    fun setup() {
        brokerDbFile = File.createTempFile("e2e-broker", ".db").apply { deleteOnExit() }
        gatewayDbFile = File.createTempFile("e2e-gateway", ".db").apply { deleteOnExit() }
        brokerStore = BrokerStore(brokerDbFile.absolutePath)
        gatewayPersistence = SqliteRescuePersistence(
            dbPath = gatewayDbFile.absolutePath,
            json = Json { ignoreUnknownKeys = false; encodeDefaults = true },
        )

        recipient = RescueCryptography.generateRecipientKeyPair()
        signer = RescueCryptography.generateShelterSigningKeyPair()
        deviceKeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        val port = ServerSocket(0).use { it.localPort }
        brokerUrl = "http://127.0.0.1:$port"
        server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            brokerModule(brokerStore, BrokerConfig(profile = BrokerProfile.PRODUCTION))
        }
        server.start(wait = false)
        httpClient = HttpClient(CIO)
    }

    @After
    fun teardown() {
        httpClient.close()
        server.stop(500, 1_000)
        gatewayPersistence.close()
        brokerStore.close()
        brokerDbFile.delete()
        gatewayDbFile.delete()
    }

    @Test
    fun `member upload flows through broker and gateway to a verifiable signed receipt`() = runBlocking {
        // 1. Device (emulator) registers with the Broker and obtains its receipt-polling token.
        val capabilityToken = registerDevice()

        // 2. Device encrypts a rescue request and uploads the signed ciphertext envelope.
        val envelope = RescueCryptography.encrypt(
            payload = memberPayload(),
            recipientPublicKey = recipient.publicKey,
            envelopeId = envelopeId,
        )
        uploadEnvelope(envelope)

        // 3. Operator issues a scoped Gateway credential; the Gateway wires intake -> outbox.
        val credential = brokerStore.issueGatewayCredential(
            gatewayId,
            shelterId,
            System.currentTimeMillis() + 3_600_000,
        ).token

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

        // 4. Gateway pulls the envelope from the Broker, decrypts it, and signs a STORED receipt.
        val ingested = pullAgent.pullOnce()
        assertEquals("gateway should ingest exactly the uploaded envelope", 1, ingested)

        val storedReceipt = intake.receipt(requestId)
            ?: error("gateway did not issue a receipt for the ingested request")
        assertEquals(ShelterReceiptStatus.STORED, storedReceipt.receipt.status)
        assertTrue(
            "receipt must verify against the shelter signing public key",
            RescueCryptography.verifyReceipt(storedReceipt, signer.publicKey),
        )

        // 5. Gateway flushes the receipt back to the Broker.
        val sent = outbox.flushPending()
        assertEquals("outbox should deliver exactly one receipt", 1, sent)

        // 6. Device polls the Broker and receives the same signed receipt end-to-end.
        val batch = pollReceipts(capabilityToken)
        assertEquals("device should see exactly one receipt", 1, batch.receipts.size)
        val delivered = batch.receipts.single()
        assertEquals(envelopeId, delivered.receipt.envelopeId)
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
            setBody(wire.encodeToString(BrokerUploadRequest.serializer(), request))
        }
        assertEquals(HttpStatusCode.Created, response.status)
    }

    private suspend fun pollReceipts(capabilityToken: String): BrokerReceiptBatch {
        val response = httpClient.get("$brokerUrl/v1/receipts?sinceSeq=0") {
            header("Authorization", "Bearer $capabilityToken")
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
        senderDeviceId = "member-1",
        destinationShelterId = shelterId,
        createdAtEpochMillis = System.currentTimeMillis(),
        expiresAtEpochMillis = System.currentTimeMillis() + 3_600_000,
        urgency = RescueUrgency.IMMEDIATE,
        personCount = 3,
        injured = true,
        supportNeeds = setOf(RescueSupportNeed.RESCUE_TEAM),
        freeText = "e2e help",
    )
}
