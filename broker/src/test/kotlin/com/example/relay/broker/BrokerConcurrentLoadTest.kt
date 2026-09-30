package com.example.relay.broker

import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescuePayload
import com.example.relay.rescue.RescueSupportNeed
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.RescueValidationResult
import com.example.relay.rescue.authenticatedHeaderBytes
import com.example.relay.rescue.validate
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Load / concurrency proof against the REAL Netty Broker (no Docker needed):
 * a fleet of registered devices firing signed uploads at once, including
 * deliberately contended duplicate submissions (retry storms) racing from many
 * threads simultaneously. Every device stays under the production per-device
 * rate limit (30 uploads/min) so the run models legitimate disaster traffic;
 * the production limiter stays ACTIVE - 429s are treated as failures.
 *
 * What is actually gated (honest scope):
 * - zero transport or 5xx errors under concurrent load on loopback;
 * - exactly-once storage: each contended envelope yields exactly ONE 201
 *   (Stored) across all racing threads - the rest must observe 200 (Duplicate);
 * - the store ends up with exactly the number of unique envelopes sent.
 * Latency percentiles are printed for observability but only sanity-gated
 * with a deliberately generous bound; this is a correctness-under-load gate,
 * NOT a calibrated capacity/SLA benchmark (that would need dedicated hardware).
 */
class BrokerConcurrentLoadTest {

    private companion object {
        const val WORKER_THREADS = 16
        const val UNIQUE_DEVICES = 12
        const val UPLOADS_PER_UNIQUE_DEVICE = 8 // well under the 30/min device limit
        const val UNIQUE_ENVELOPES = UNIQUE_DEVICES * UPLOADS_PER_UNIQUE_DEVICE
        const val CONTENDED_ENVELOPES = 4 // one retry-storm device each
        const val RACERS_PER_CONTENDED = 12 // 12 identical retries, still under 30/min
        // Generous sanity bound on loopback; catches pathological serialization
        // (e.g. a lock held across the whole request) without being flaky.
        // Measured locally: p95 ~3.5s with 16 threads against the synchronized
        // SQLite store; 10s leaves headroom for slower CI runners.
        const val P95_SANITY_MILLIS = 10_000L
    }

    private lateinit var store: BrokerStore
    private lateinit var dbFile: File
    private lateinit var server: EmbeddedServer<*, *>
    private var port = 0

    private class LoadDevice(val deviceKeyId: String) {
        val keyPair: java.security.KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
    }

    private val uniqueDevices = (1..UNIQUE_DEVICES).map { LoadDevice("load-device-%02d".format(it)) }
    private val stormDevices = (1..CONTENDED_ENVELOPES).map { LoadDevice("load-storm-%02d".format(it)) }
    private val shelterId = "load-shelter"
    private val recipient = RescueCryptography.generateRecipientKeyPair()

    @Before
    fun startBroker() {
        dbFile = File.createTempFile("broker-load-test", ".db").apply { deleteOnExit() }
        store = BrokerStore(dbFile.absolutePath)
        (uniqueDevices + stormDevices).forEach { device ->
            store.registerDevice(
                device.deviceKeyId,
                Base64.getEncoder().encodeToString(device.keyPair.public.encoded),
                System.currentTimeMillis(),
            )
        }
        port = ServerSocket(0).use { it.localPort }
        server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            brokerModule(
                store,
                BrokerConfig(
                    profile = BrokerProfile.PRODUCTION,
                    host = "127.0.0.1",
                    port = port,
                    dbPath = dbFile.absolutePath,
                    legacyGatewayApiKey = null,
                ),
            )
        }.start(wait = false)
    }

    @After
    fun stopBroker() {
        server.stop(500, 2_000)
        store.close()
        dbFile.delete()
    }

    private fun signedUploadBody(device: LoadDevice, envelopeId: String): String {
        val now = System.currentTimeMillis()
        val payload = RescuePayload(
            requestId = "req-$envelopeId",
            requestVersion = 1,
            senderDeviceId = device.deviceKeyId,
            destinationShelterId = shelterId,
            createdAtEpochMillis = now,
            expiresAtEpochMillis = now + 3_600_000,
            urgency = RescueUrgency.IMMEDIATE,
            personCount = 1,
            injured = false,
            supportNeeds = setOf(RescueSupportNeed.RESCUE_TEAM),
            freeText = "load proof",
        )
        require(payload.validate() == RescueValidationResult.Valid)
        val envelope = RescueCryptography.encrypt(
            payload = payload,
            recipientPublicKey = recipient.publicKey,
            envelopeId = envelopeId,
        )
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(device.keyPair.private)
            update(envelope.authenticatedHeaderBytes() + envelope.ciphertextSha256Hex.encodeToByteArray())
        }
        return brokerJson.encodeToString(
            BrokerUploadRequest.serializer(),
            BrokerUploadRequest(envelope, device.deviceKeyId, Base64.getEncoder().encodeToString(signature.sign())),
        )
    }

    private data class UploadOutcome(val envelopeId: String, val status: Int, val millis: Long)

    @Test
    fun concurrentUploadsAreExactlyOnceWithZeroErrors() {
        // Pre-build all signed bodies so the measured section is pure HTTP+server work.
        // Unique envelopes are spread across the device fleet (8 per device).
        val uniqueBodies = (0 until UNIQUE_ENVELOPES).map { i ->
            val id = "load-unique-%03d".format(i + 1)
            id to signedUploadBody(uniqueDevices[i / UPLOADS_PER_UNIQUE_DEVICE], id)
        }
        // Each contended envelope is one device's retry storm: identical signed body.
        val contendedBodies = stormDevices.mapIndexed { i, device ->
            val id = "load-contended-%03d".format(i + 1)
            id to signedUploadBody(device, id)
        }

        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val outcomes = ConcurrentLinkedQueue<UploadOutcome>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val startGate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(WORKER_THREADS)

        fun submit(envelopeId: String, body: String) {
            pool.execute {
                try {
                    startGate.await()
                    val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/v1/rescue/upload"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build()
                    val startedAt = System.nanoTime()
                    val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                    val millis = (System.nanoTime() - startedAt) / 1_000_000
                    outcomes.add(UploadOutcome(envelopeId, response.statusCode(), millis))
                } catch (t: Throwable) {
                    failures.add(t)
                }
            }
        }

        uniqueBodies.forEach { (id, body) -> submit(id, body) }
        contendedBodies.forEach { (id, body) -> repeat(RACERS_PER_CONTENDED) { submit(id, body) } }
        val totalRequests = UNIQUE_ENVELOPES + CONTENDED_ENVELOPES * RACERS_PER_CONTENDED

        val wallStart = System.nanoTime()
        startGate.countDown()
        pool.shutdown()
        assertTrue("load run must finish within 120s", pool.awaitTermination(120, TimeUnit.SECONDS))
        val wallMillis = (System.nanoTime() - wallStart) / 1_000_000

        // 1. Zero transport errors and zero unexpected statuses.
        assertEquals("no request may fail at transport level: $failures", 0, failures.size)
        assertEquals("every request must produce an outcome", totalRequests, outcomes.size)
        val badStatuses = outcomes.filter { it.status !in setOf(200, 201) }
        assertEquals("only 201 Stored / 200 Duplicate are acceptable under load: $badStatuses", 0, badStatuses.size)

        // 2. Exactly-once per contended envelope: one 201 across all racers.
        for ((id, _) in contendedBodies) {
            val stored = outcomes.count { it.envelopeId == id && it.status == 201 }
            val duplicate = outcomes.count { it.envelopeId == id && it.status == 200 }
            assertEquals("exactly one racer may win Stored for $id", 1, stored)
            assertEquals("all other racers must observe Duplicate for $id", RACERS_PER_CONTENDED - 1, duplicate)
        }
        // Unique envelopes must all be freshly stored.
        assertEquals(
            "every unique envelope must be Stored exactly once",
            UNIQUE_ENVELOPES,
            outcomes.count { it.envelopeId.startsWith("load-unique-") && it.status == 201 },
        )

        // 3. Store-level ground truth: unique + contended, nothing more.
        val batch = store.pendingForShelter(shelterId, System.currentTimeMillis(), null, 1_000, gatewayId = "load-gw")
        assertEquals(
            "store must hold exactly the unique envelope set",
            UNIQUE_ENVELOPES + CONTENDED_ENVELOPES,
            batch.envelopes.count { it.envelopeId.startsWith("load-") },
        )

        // 4. Observability + generous sanity bound (not an SLA claim).
        val sorted = outcomes.map { it.millis }.sorted()
        val p50 = sorted[sorted.size / 2]
        val p95 = sorted[(sorted.size * 95) / 100]
        val throughput = totalRequests * 1000.0 / wallMillis.coerceAtLeast(1)
        println(
            "broker-load: requests=$totalRequests wall=${wallMillis}ms " +
                "throughput=${"%.1f".format(throughput)}req/s p50=${p50}ms p95=${p95}ms max=${sorted.last()}ms",
        )
        assertTrue("p95 ${p95}ms exceeded sanity bound ${P95_SANITY_MILLIS}ms", p95 < P95_SANITY_MILLIS)
    }
}
