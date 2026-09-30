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
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Load and fault-injection coverage for the Broker -> Gateway -> receipt path.
 *
 * Unlike the deterministic Python oracle in test-lab/fault_injection, this drives the REAL
 * components over real HTTP + SQLite + crypto:
 *   - load: many devices concurrently register and upload distinct signed envelopes; the Gateway
 *     drains the Broker (exercising cursor pagination) and every device must receive exactly one
 *     correctly-routed, signature-verified receipt (no loss, no duplication, no cross-talk).
 *   - fault injection: a client plugin injects a transport reset on the first pull and the first
 *     receipt POST; the Gateway's at-least-once retry paths (BrokerPullAgent re-pull without
 *     advancing the cursor, ReceiptOutbox incrementRetry) must recover and still deliver each
 *     receipt exactly once (Broker dedup by receipt_id / envelope hash).
 */
class BrokerGatewayLoadAndFaultInjectionTest {
    private val wire = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val gatewayJson = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    private val shelterId = "example-01"
    private val gatewayId = "gateway-regional"

    private lateinit var brokerDbFile: File
    private lateinit var brokerStore: BrokerStore
    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var deviceClient: HttpClient
    private lateinit var brokerUrl: String
    private lateinit var credential: String

    private lateinit var recipient: RescueKeyPair
    private lateinit var signer: RescueKeyPair

    @Before
    fun setup() {
        brokerDbFile = File.createTempFile("loadfault-broker", ".db").apply { deleteOnExit() }
        brokerStore = BrokerStore(brokerDbFile.absolutePath)
        recipient = RescueCryptography.generateRecipientKeyPair()
        signer = RescueCryptography.generateShelterSigningKeyPair()

        val port = ServerSocket(0).use { it.localPort }
        brokerUrl = "http://127.0.0.1:$port"
        server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            brokerModule(brokerStore, BrokerConfig(profile = BrokerProfile.PRODUCTION))
        }
        server.start(wait = false)
        deviceClient = HttpClient(CIO)
        credential = brokerStore.issueGatewayCredential(
            gatewayId,
            shelterId,
            System.currentTimeMillis() + 3_600_000,
        ).token
    }

    @After
    fun teardown() {
        deviceClient.close()
        server.stop(500, 1_000)
        brokerStore.close()
        brokerDbFile.delete()
    }

    @Test
    fun `concurrent load delivers exactly one routed receipt per device`() = runBlocking {
        val memberCount = 60 // > pull page size (50) so cursor pagination is exercised

        val members = (1..memberCount).map { i ->
            async(Dispatchers.IO) {
                val keyPair = newDeviceKey()
                val deviceKeyId = "load-device-$i"
                val token = registerDevice(deviceKeyId, keyPair, deviceClient)
                val envelopeId = "env-load-$i"
                val requestId = "req-load-$i"
                val envelope = RescueCryptography.encrypt(
                    payload = payload(requestId),
                    recipientPublicKey = recipient.publicKey,
                    envelopeId = envelopeId,
                )
                uploadEnvelope(envelope, deviceKeyId, keyPair, deviceClient)
                Member(token, envelopeId, requestId)
            }
        }.awaitAll()

        val (intake, outbox, pullAgent) = newGateway(deviceClient)

        // Drain the Broker: pull until empty (pages of <=50), counting accepted ingests.
        var totalIngested = 0
        while (true) {
            val n = pullAgent.pullOnce()
            totalIngested += n
            if (n == 0) break
        }
        assertEquals("every uploaded envelope must be ingested exactly once", memberCount, totalIngested)

        // Flush all receipts (outbox sends <=20 per call).
        var totalSent = 0
        while (true) {
            val n = outbox.flushPending()
            totalSent += n
            if (n == 0) break
        }
        assertEquals("every receipt must be delivered to the Broker", memberCount, totalSent)

        // Each device must see exactly its own receipt, verifiable and correctly routed.
        for (member in members) {
            val batch = pollReceipts(member.token, deviceClient)
            assertEquals("device ${member.envelopeId} must see exactly one receipt", 1, batch.receipts.size)
            val receipt = batch.receipts.single()
            assertEquals(member.envelopeId, receipt.receipt.envelopeId)
            assertEquals(member.requestId, receipt.receipt.requestId)
            assertEquals(ShelterReceiptStatus.STORED, receipt.receipt.status)
            assertTrue(RescueCryptography.verifyReceipt(receipt, signer.publicKey))
        }
    }

    @Test
    fun `transient transport faults are retried without loss or duplication`() = runBlocking {
        val keyPair = newDeviceKey()
        val deviceKeyId = "fault-device"
        val token = registerDevice(deviceKeyId, keyPair, deviceClient)
        val envelopeId = "env-fault"
        val requestId = "req-fault"
        val envelope = RescueCryptography.encrypt(
            payload = payload(requestId),
            recipientPublicKey = recipient.publicKey,
            envelopeId = envelopeId,
        )
        uploadEnvelope(envelope, deviceKeyId, keyPair, deviceClient)

        // Inject a one-shot transport reset on the first pull and the first receipt POST.
        val failFirstPull = AtomicBoolean(true)
        val failFirstReceipt = AtomicBoolean(true)
        val faultPlugin = createClientPlugin("OneShotFaults") {
            onRequest { request, _ ->
                val url = request.url.toString()
                if (url.contains("/pull") && failFirstPull.getAndSet(false)) {
                    throw IOException("injected pull reset")
                }
                if (url.contains("/receipts") && request.method == HttpMethod.Post &&
                    failFirstReceipt.getAndSet(false)
                ) {
                    throw IOException("injected receipt reset")
                }
            }
        }
        val faultyClient = HttpClient(CIO) { install(faultPlugin) }
        try {
            val (_, outbox, pullAgent) = newGateway(faultyClient)

            // First pull hits the injected reset and surfaces the failure (start() would back off).
            val firstPull = runCatching { pullAgent.pullOnce() }
            assertTrue("first pull must fail from injected fault", firstPull.isFailure)

            // Cursor was not advanced, so a retry re-pulls and ingests the envelope once.
            assertEquals(1, pullAgent.pullOnce())

            // First flush hits the injected receipt fault: nothing marked sent, stays PENDING.
            assertEquals("first receipt POST is dropped by the fault", 0, outbox.flushPending())

            // Retry succeeds and delivers the receipt.
            assertEquals(1, outbox.flushPending())

            // Device sees exactly one receipt despite the retries (Broker dedup by receipt_id).
            val batch = pollReceipts(token, deviceClient)
            assertEquals(1, batch.receipts.size)
            val receipt = batch.receipts.single()
            assertEquals(envelopeId, receipt.receipt.envelopeId)
            assertEquals(ShelterReceiptStatus.STORED, receipt.receipt.status)
            assertTrue(RescueCryptography.verifyReceipt(receipt, signer.publicKey))

            // Idempotency: a further flush sends nothing (already SENT) and no duplicate appears.
            assertEquals(0, outbox.flushPending())
            assertEquals(1, pollReceipts(token, deviceClient).receipts.size)
            assertFalse("both one-shot faults must have fired", failFirstPull.get() || failFirstReceipt.get())
        } finally {
            faultyClient.close()
        }
    }

    private data class Member(val token: String, val envelopeId: String, val requestId: String)

    /** Builds an independent Gateway wired exactly as MainKt does (intake -> outbox), on its own DB. */
    private fun newGateway(client: HttpClient): Triple<RescueIntakeService, ReceiptOutbox, BrokerPullAgent> {
        val dbFile = File.createTempFile("loadfault-gateway", ".db").apply { deleteOnExit() }
        val persistence = SqliteRescuePersistence(dbPath = dbFile.absolutePath, json = gatewayJson)
        val outbox = ReceiptOutbox(
            persistence = persistence,
            brokerUrl = brokerUrl,
            shelterId = shelterId,
            gatewayId = gatewayId,
            httpClient = client,
            gatewayCredential = credential,
        )
        val intake = RescueIntakeService(
            shelterId = shelterId,
            recipientPrivateKey = recipient.privateKey,
            shelterSigningPrivateKey = signer.privateKey,
            persistence = persistence,
            onReceiptIssued = { receipt -> outbox.enqueue(receipt) },
        )
        val pullAgent = BrokerPullAgent(
            brokerUrl = brokerUrl,
            shelterId = shelterId,
            gatewayId = gatewayId,
            intakeService = intake,
            httpClient = client,
            gatewayCredential = credential,
        )
        return Triple(intake, outbox, pullAgent)
    }

    private fun newDeviceKey(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private suspend fun registerDevice(deviceKeyId: String, keyPair: KeyPair, client: HttpClient): String {
        val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val request = BrokerDeviceRegisterRequest(
            deviceKeyId,
            publicKeyBase64,
            ecdsaSign(keyPair, brokerDeviceRegistrationBytes(deviceKeyId, publicKeyBase64)),
        )
        val response = client.post("$brokerUrl/v1/devices/register") {
            contentType(ContentType.Application.Json)
            setBody(wire.encodeToString(BrokerDeviceRegisterRequest.serializer(), request))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return wire.decodeFromString(
            BrokerDeviceRegisterResponse.serializer(),
            response.bodyAsText(),
        ).capabilityToken
    }

    private suspend fun uploadEnvelope(
        envelope: EncryptedRescueEnvelope,
        deviceKeyId: String,
        keyPair: KeyPair,
        client: HttpClient,
    ) {
        val signature = ecdsaSign(
            keyPair,
            envelope.authenticatedHeaderBytes() + envelope.ciphertextSha256Hex.encodeToByteArray(),
        )
        val request = BrokerUploadRequest(envelope, deviceKeyId, signature)
        val response = client.post("$brokerUrl/v1/rescue/upload") {
            contentType(ContentType.Application.Json)
            setBody(wire.encodeToString(BrokerUploadRequest.serializer(), request))
        }
        assertEquals(HttpStatusCode.Created, response.status)
    }

    private suspend fun pollReceipts(capabilityToken: String, client: HttpClient): BrokerReceiptBatch {
        val response = client.get("$brokerUrl/v1/receipts?sinceSeq=0") {
            header("Authorization", "Bearer $capabilityToken")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return wire.decodeFromString(BrokerReceiptBatch.serializer(), response.bodyAsText())
    }

    private fun ecdsaSign(keyPair: KeyPair, data: ByteArray): String {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(data)
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    private fun payload(requestId: String) = RescuePayload(
        requestId = requestId,
        requestVersion = 1,
        senderDeviceId = "member-$requestId",
        destinationShelterId = shelterId,
        createdAtEpochMillis = System.currentTimeMillis(),
        expiresAtEpochMillis = System.currentTimeMillis() + 3_600_000,
        urgency = RescueUrgency.IMMEDIATE,
        personCount = 2,
        injured = true,
        supportNeeds = setOf(RescueSupportNeed.RESCUE_TEAM),
        freeText = "load-fault",
    )
}
