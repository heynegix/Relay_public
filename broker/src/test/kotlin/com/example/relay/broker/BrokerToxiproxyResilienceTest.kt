package com.example.relay.broker

import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescuePayload
import com.example.relay.rescue.RescueSupportNeed
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.RescueValidationResult
import com.example.relay.rescue.authenticatedHeaderBytes
import com.example.relay.rescue.validate
import eu.rekawek.toxiproxy.Proxy
import eu.rekawek.toxiproxy.ToxiproxyClient
import eu.rekawek.toxiproxy.model.ToxicDirection
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import java.io.IOException
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
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.Testcontainers
import org.testcontainers.toxiproxy.ToxiproxyContainer

/**
 * Real-network resilience proof: a REAL Netty Broker behind a REAL Toxiproxy
 * (same image pinned in test-lab/toxiproxy/docker-compose.yml) under latency,
 * server-silence, and connection-reset faults.
 *
 * This closes the honesty gap left by test-lab/fault_injection/run_fault_matrix.py,
 * which asserts the retry/idempotency oracle only against an in-memory simulation:
 * here the "no duplicate after retry" invariant is enforced against the actual
 * HTTP stack, signature verification, and SQLite dedup path.
 *
 * Docker policy (no false green, no silent fail-open in CI):
 * - RELAY_REQUIRE_DOCKER=true (set by CI) turns "Docker unavailable" into a FAILURE.
 * - Locally without Docker the class is SKIPPED via JUnit assumption, which Gradle
 *   reports as skipped - never as passed.
 */
class BrokerToxiproxyResilienceTest {

    companion object {
        private const val TOXIPROXY_IMAGE = "ghcr.io/shopify/toxiproxy:2.12.0"
        private const val PROXY_LISTEN_PORT = 8666

        private lateinit var toxiproxy: ToxiproxyContainer
        private lateinit var proxy: Proxy
        private lateinit var server: EmbeddedServer<*, *>
        private lateinit var store: BrokerStore
        private lateinit var dbFile: File
        private var brokerPort: Int = 0
        private var started = false

        @JvmStatic
        @BeforeClass
        fun startBrokerAndProxy() {
            val dockerUp = runCatching {
                DockerClientFactory.instance().client().pingCmd().exec()
            }.isSuccess
            if (!dockerUp) {
                check(System.getenv("RELAY_REQUIRE_DOCKER") != "true") {
                    "RELAY_REQUIRE_DOCKER=true but the Docker daemon is unavailable - failing instead of skipping"
                }
                Assume.assumeTrue(
                    "Docker unavailable; Toxiproxy resilience tests skipped (reported as skipped, not passed)",
                    false,
                )
            }

            dbFile = File.createTempFile("broker-toxiproxy-test", ".db").apply { deleteOnExit() }
            store = BrokerStore(dbFile.absolutePath)
            brokerPort = ServerSocket(0).use { it.localPort }
            server = embeddedServer(Netty, host = "127.0.0.1", port = brokerPort) {
                brokerModule(
                    store,
                    BrokerConfig(
                        profile = BrokerProfile.PRODUCTION,
                        host = "127.0.0.1",
                        port = brokerPort,
                        dbPath = dbFile.absolutePath,
                        legacyGatewayApiKey = null,
                    ),
                )
            }.start(wait = false)

            // Let the toxiproxy container reach the host-side Broker.
            Testcontainers.exposeHostPorts(brokerPort)
            toxiproxy = ToxiproxyContainer(TOXIPROXY_IMAGE)
            toxiproxy.start()
            val client = ToxiproxyClient(toxiproxy.host, toxiproxy.controlPort)
            proxy = client.createProxy(
                "broker",
                "0.0.0.0:$PROXY_LISTEN_PORT",
                "host.testcontainers.internal:$brokerPort",
            )
            started = true
        }

        @JvmStatic
        @AfterClass
        fun stopBrokerAndProxy() {
            if (!started) return
            toxiproxy.stop()
            server.stop(500, 2_000)
            store.close()
            dbFile.delete()
        }
    }

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val deviceKeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val deviceKeyId = "resilience-device-${System.nanoTime()}"
    private val shelterId = "resilience-shelter"
    private val recipient = RescueCryptography.generateRecipientKeyPair()

    private fun proxiedBaseUrl(): String =
        "http://${toxiproxy.host}:${toxiproxy.getMappedPort(PROXY_LISTEN_PORT)}"

    @Before
    fun registerDevice() {
        // In-process registration: the invariant under test is upload idempotency
        // across network faults, not the registration handshake.
        store.registerDevice(
            deviceKeyId,
            Base64.getEncoder().encodeToString(deviceKeyPair.public.encoded),
            System.currentTimeMillis(),
        )
    }

    @After
    fun clearToxics() {
        proxy.toxics().all.forEach { it.remove() }
    }

    private fun signedUploadBody(envelopeId: String): String {
        val now = System.currentTimeMillis()
        val payload = RescuePayload(
            requestId = "req-$envelopeId",
            requestVersion = 1,
            senderDeviceId = "resilience-phone",
            destinationShelterId = shelterId,
            createdAtEpochMillis = now,
            expiresAtEpochMillis = now + 3_600_000,
            urgency = RescueUrgency.IMMEDIATE,
            personCount = 1,
            injured = true,
            supportNeeds = setOf(RescueSupportNeed.RESCUE_TEAM),
            freeText = "resilience proof",
        )
        require(payload.validate() == RescueValidationResult.Valid)
        val envelope = RescueCryptography.encrypt(
            payload = payload,
            recipientPublicKey = recipient.publicKey,
            envelopeId = envelopeId,
        )
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(deviceKeyPair.private)
            update(envelope.authenticatedHeaderBytes() + envelope.ciphertextSha256Hex.encodeToByteArray())
        }
        val uploadSignature = Base64.getEncoder().encodeToString(signature.sign())
        return brokerJson.encodeToString(
            BrokerUploadRequest.serializer(),
            BrokerUploadRequest(envelope, deviceKeyId, uploadSignature),
        )
    }

    private fun upload(body: String, timeout: Duration = Duration.ofSeconds(4)): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("${proxiedBaseUrl()}/v1/rescue/upload"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun storedCount(envelopeId: String): Int {
        val batch = store.pendingForShelter(
            shelterId,
            System.currentTimeMillis(),
            null,
            100,
            gatewayId = "resilience-gw",
        )
        return batch.envelopes.count { envelope: EncryptedRescueEnvelope -> envelope.envelopeId == envelopeId }
    }

    /**
     * A degraded-but-alive link (800 ms added downstream latency) must not break
     * uploads. The elapsed-time assertion proves the toxic was really applied -
     * a misconfigured proxy would otherwise make this test pass vacuously.
     */
    @Test
    fun uploadSucceedsThroughHighLatencyLink() {
        proxy.toxics().latency("slow-response", ToxicDirection.DOWNSTREAM, 800)
        val body = signedUploadBody("resilience-latency-001")
        val startedAt = System.nanoTime()
        val response = upload(body, timeout = Duration.ofSeconds(10))
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals(201, response.statusCode())
        assertTrue("latency toxic must actually delay the response (took ${elapsedMillis}ms)", elapsedMillis >= 700)
        assertEquals(1, storedCount("resilience-latency-001"))
    }

    /**
     * Connection reset mid-upload, then retry: the fault_injection oracle's
     * "reset -> accepted -> duplicate" sequence, proven on the real server.
     */
    @Test
    fun connectionResetThenRetryStoresExactlyOnce() {
        val body = signedUploadBody("resilience-reset-001")

        proxy.toxics().resetPeer("reset-upstream", ToxicDirection.UPSTREAM, 0)
        val firstAttemptFailed = runCatching { upload(body) }.exceptionOrNull() is IOException
        assertTrue("reset_peer toxic must abort the first attempt", firstAttemptFailed)

        proxy.toxics().get("reset-upstream").remove()
        val retry = upload(body)
        assertTrue(
            "retry after reset must be accepted (was ${retry.statusCode()})",
            retry.statusCode() in setOf(200, 201),
        )
        val secondRetry = upload(body)
        assertEquals("second retry must be the idempotent duplicate", 200, secondRetry.statusCode())
        assertEquals("exactly one envelope stored despite fault + retries", 1, storedCount("resilience-reset-001"))
    }

    /**
     * Server silence (no bytes pass upstream) until the client times out, then
     * retry: "timeout -> accepted -> duplicate" on the real server.
     */
    @Test
    fun serverSilenceThenRetryStoresExactlyOnce() {
        val body = signedUploadBody("resilience-timeout-001")

        proxy.toxics().timeout("black-hole", ToxicDirection.UPSTREAM, 0)
        val firstAttemptFailed = runCatching {
            upload(body, timeout = Duration.ofSeconds(2))
        }.exceptionOrNull() is IOException
        assertTrue("timeout toxic must stall the first attempt until the client gives up", firstAttemptFailed)

        proxy.toxics().get("black-hole").remove()
        val retry = upload(body)
        assertTrue(
            "retry after timeout must be accepted (was ${retry.statusCode()})",
            retry.statusCode() in setOf(200, 201),
        )
        assertEquals("exactly one envelope stored despite fault + retry", 1, storedCount("resilience-timeout-001"))
    }
}
