package com.example.relay.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentGatewayEnrollmentStoreTest {
    private val fingerprint = "a".repeat(64)
    private val other = "b".repeat(64)

    private fun token(
        gatewayId: String = "pc-gateway-example-01",
        shelterId: String = "shelter-example-01",
        host: String = "192.168.50.20",
        port: Int = 8443,
        scheme: String = "https",
        manifestFingerprint: String = fingerprint,
    ) = GatewayEnrollmentToken(gatewayId, shelterId, host, port, scheme, manifestFingerprint)

    private class FakeStorage(initial: Set<String> = emptySet()) : EnrollmentPayloadStorage {
        var payloads: Set<String> = initial
        var writes = 0
        override fun read(): Set<String> = payloads
        override fun write(payloads: Set<String>) {
            this.payloads = payloads
            writes++
        }
    }

    @Test
    fun `enroll persists a canonical payload and seeds the trust catalog`() {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)

        val result = store.enroll(token())

        assertTrue(result is GatewayEnrollmentImport.Added)
        assertEquals(1, storage.payloads.size)
        // Persisted form must round-trip back to the same identity.
        val decoded = GatewayEnrollmentCodec.decodeQrPayload(storage.payloads.single())
        assertTrue(decoded is GatewayEnrollmentResult.Enrolled)
        assertEquals(token(), (decoded as GatewayEnrollmentResult.Enrolled).token)
    }

    @Test
    fun `enrollments survive a restart by reloading from storage`() {
        val storage = FakeStorage()
        PersistentGatewayEnrollmentStore(storage).enroll(token())

        val restarted = PersistentGatewayEnrollmentStore(storage)

        val trusted = DiscoveredGateway(
            host = "172.16.4.4",
            port = 8443,
            gatewayId = "pc-gateway-example-01",
            scheme = "https",
            shelterId = "shelter-example-01",
        )
        assertEquals(GatewayTrustDecision.TRUSTED, restarted.enrollmentStore().decisionFor(trusted))
    }

    @Test
    fun `re-enrolling the identical identity is idempotent and does not rewrite`() {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        store.enroll(token())
        val writesAfterFirst = storage.writes

        val result = store.enroll(token())

        assertTrue(result is GatewayEnrollmentImport.AlreadyEnrolled)
        assertEquals("must not rewrite storage on an idempotent enroll", writesAfterFirst, storage.writes)
    }

    @Test
    fun `a conflicting identity for the same gatewayId is refused without rotation`() {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        store.enroll(token())

        val result = store.enroll(token(shelterId = "shelter-evil", manifestFingerprint = other))

        assertTrue(result is GatewayEnrollmentImport.Conflict)
        // The original identity must be untouched: a spoofed re-enroll cannot displace it.
        assertEquals(fingerprint, store.enrolledTokens().single().manifestFingerprint)
        assertEquals("shelter-example-01", store.enrolledTokens().single().shelterId)
    }

    @Test
    fun `explicit rotation replaces the stored identity`() {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        store.enroll(token())

        val rotated = token(shelterId = "shelter-regional-02", manifestFingerprint = other)
        val result = store.enroll(rotated, allowRotation = true)

        assertTrue(result is GatewayEnrollmentImport.Rotated)
        assertEquals(rotated, store.enrolledTokens().single())
    }

    @Test
    fun `importPayload decodes a scanned payload and persists it`() {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        val payload = GatewayEnrollmentCodec.encodeQrPayload(token())

        val result = store.importPayload(payload)

        assertTrue(result is GatewayEnrollmentImport.Added)
        assertEquals(1, store.enrolledTokens().size)
    }

    @Test
    fun `importPayload rejects a tampered payload and stores nothing`() {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        val payload = GatewayEnrollmentCodec.encodeQrPayload(token())
        val tampered = payload.dropLast(1) + if (payload.last() == '0') '1' else '0'

        val result = store.importPayload(tampered)

        assertTrue(result is GatewayEnrollmentImport.Rejected)
        assertEquals(GatewayEnrollmentRejection.CHECKSUM_MISMATCH, (result as GatewayEnrollmentImport.Rejected).reason)
        assertTrue(store.enrolledTokens().isEmpty())
    }

    @Test
    fun `corrupted persisted entries are dropped fail-closed on load and quarantined`() {
        val good = GatewayEnrollmentCodec.encodeQrPayload(token())
        val storage = FakeStorage(setOf(good, "relay-gw:1:garbage", "not-a-payload"))

        val store = PersistentGatewayEnrollmentStore(storage)

        assertEquals(1, store.enrolledTokens().size)
        // The corrupt entries must be scrubbed from durable storage, not left to rot.
        assertEquals(setOf(good), storage.payloads)
    }

    @Test
    fun `forget removes an identity from memory and storage`() {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        store.enroll(token())

        assertTrue(store.forget("pc-gateway-example-01"))
        assertTrue(store.enrolledTokens().isEmpty())
        assertTrue(storage.payloads.isEmpty())
        assertFalse("forgetting an unknown gateway reports no change", store.forget("nope"))
    }

    @Test
    fun `a spoofed beacon reusing an enrolled gatewayId is rejected by the seeded store`() {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        store.enroll(token())

        val spoof = DiscoveredGateway(
            host = "10.0.0.9",
            port = 8080,
            gatewayId = "pc-gateway-example-01",
            scheme = "http",
            shelterId = "shelter-example-01",
        )
        assertEquals(GatewayTrustDecision.REJECTED, store.enrollmentStore().decisionFor(spoof))
        assertNull(store.enrollmentStore().trustedTokenFor(spoof))
    }
}
