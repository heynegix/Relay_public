package com.example.relay.broker

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Proves the broker's security boundaries emit observable, secret-free signals. Each test drives a
 * rejection and asserts the matching [BrokerSecurityEvent] counter incremented; the in-memory sink
 * only ever receives coarse categories, so no test can observe a token, key, or payload.
 */
class BrokerObservabilityTest {
    private lateinit var store: BrokerStore
    private lateinit var dbFile: File

    @Before
    fun setup() {
        dbFile = File.createTempFile("broker-observability-test", ".db")
        dbFile.deleteOnExit()
        store = BrokerStore(dbFile.absolutePath)
    }

    @After
    fun teardown() {
        store.close()
        dbFile.delete()
    }

    @Test
    fun `an unauthenticated gateway pull records AUTH_FAILED`() = testApplication {
        val observability = InMemoryBrokerObservability()
        application { brokerModule(store, BrokerConfig(profile = BrokerProfile.PRODUCTION), observability = observability) }

        // No X-Gateway-Id header and no Bearer credential: authentication must fail closed.
        client.get("/v1/gateways/shelter-1/pull")

        assertEquals(1L, observability.count(BrokerSecurityEvent.AUTH_FAILED))
        assertEquals(0L, observability.count(BrokerSecurityEvent.GATEWAY_SCOPE_MISMATCH))
    }

    @Test
    fun `a registration with a bad proof records INVALID_REGISTRATION_PROOF`() = testApplication {
        val observability = InMemoryBrokerObservability()
        application { brokerModule(store, observability = observability) }

        // A well-formed P-256 public key but a signature that cannot verify: fail closed.
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val bogusSignature = Base64.getEncoder().encodeToString(ByteArray(64) { 0 })
        val body = brokerJson.encodeToString(
            BrokerDeviceRegisterRequest.serializer(),
            BrokerDeviceRegisterRequest("device-obs-01", publicKeyBase64, bogusSignature),
        )

        client.post("/v1/devices/register") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(1L, observability.count(BrokerSecurityEvent.INVALID_REGISTRATION_PROOF))
    }

    @Test
    fun `repeated manifest fetches beyond the window record RATE_LIMITED`() = testApplication {
        val observability = InMemoryBrokerObservability()
        val limiter = SlidingWindowRateLimiter(maxRequests = 1, windowMillis = 60_000)
        application { brokerModule(store, pullRateLimiter = limiter, observability = observability) }

        client.get("/v1/shelters/shelter-1/manifest") // consumes the single allowed request
        client.get("/v1/shelters/shelter-1/manifest") // exceeds the window

        assertTrue(observability.count(BrokerSecurityEvent.RATE_LIMITED) >= 1L)
    }

    @Test
    fun `the default sink is a no-op and a fresh in-memory sink has no counts`() {
        // A server constructed without a sink must behave identically; recording is a pure discard.
        BrokerObservability.None.record(BrokerSecurityEvent.AUTH_FAILED)
        assertTrue("a fresh sink has no counts", InMemoryBrokerObservability().snapshot().isEmpty())
    }
}
