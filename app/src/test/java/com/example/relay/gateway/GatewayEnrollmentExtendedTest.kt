package com.example.relay.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extended enrollment tests verifying QR import security constraints:
 * - Empty input
 * - Excessively long input
 * - Unknown version
 * - Re-enrollment of same token
 * - Conflict with different identity
 * - Rotation refusal and acceptance
 * - Process re-creation persistence
 * - List and delete operations
 * - Control characters in input
 */
class GatewayEnrollmentExtendedTest {
    private val fingerprint = "a".repeat(64)

    private fun token(
        gatewayId: String = "gw-test-01",
        shelterId: String = "shelter-test-01",
        host: String = "192.168.1.1",
        port: Int = 8443,
        scheme: String = "https",
        manifestFingerprint: String = fingerprint,
    ) = GatewayEnrollmentToken(gatewayId, shelterId, host, port, scheme, manifestFingerprint)

    private class FakeStorage(initial: Set<String> = emptySet()) : EnrollmentPayloadStorage {
        var payloads: Set<String> = initial
        override fun read(): Set<String> = payloads
        override fun write(payloads: Set<String>) { this.payloads = payloads }
    }

    @Test
    fun `empty input is rejected`() {
        val store = PersistentGatewayEnrollmentStore(FakeStorage())
        val result = store.importPayload("")
        assertTrue(result is GatewayEnrollmentImport.Rejected)
        assertEquals(GatewayEnrollmentRejection.MALFORMED, (result as GatewayEnrollmentImport.Rejected).reason)
    }

    @Test
    fun `whitespace-only input is rejected`() {
        val store = PersistentGatewayEnrollmentStore(FakeStorage())
        val result = store.importPayload("   \t\n  ")
        assertTrue(result is GatewayEnrollmentImport.Rejected)
    }

    @Test
    fun `excessively long input is rejected before parsing`() {
        val store = PersistentGatewayEnrollmentStore(FakeStorage())
        val oversized = "relay-gw:1:" + "x".repeat(600)
        val result = store.importPayload(oversized)
        assertTrue(result is GatewayEnrollmentImport.Rejected)
        assertEquals(GatewayEnrollmentRejection.PAYLOAD_TOO_LARGE, (result as GatewayEnrollmentImport.Rejected).reason)
    }

    @Test
    fun `unknown version is rejected`() {
        val store = PersistentGatewayEnrollmentStore(FakeStorage())
        // Version 99 does not exist
        val payload = "relay-gw:99:gw|sh|host|443|https|$fingerprint:deadbeef"
        val result = store.importPayload(payload)
        assertTrue(result is GatewayEnrollmentImport.Rejected)
        assertEquals(GatewayEnrollmentRejection.UNSUPPORTED_VERSION, (result as GatewayEnrollmentImport.Rejected).reason)
    }

    @Test
    fun `re-enrollment of identical token returns AlreadyEnrolled`() {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        val payload = GatewayEnrollmentCodec.encodeQrPayload(token())
        store.importPayload(payload)
        val result = store.importPayload(payload)
        assertTrue(result is GatewayEnrollmentImport.AlreadyEnrolled)
    }

    @Test
    fun `conflict with different identity without rotation`() {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        store.enroll(token())
        val different = token(shelterId = "shelter-evil", manifestFingerprint = "b".repeat(64))
        val result = store.enroll(different, allowRotation = false)
        assertTrue(result is GatewayEnrollmentImport.Conflict)
        // Original must be preserved
        assertEquals("shelter-test-01", store.enrolledTokens().single().shelterId)
    }

    @Test
    fun `rotation refused then rotation allowed`() {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        store.enroll(token())
        val newToken = token(shelterId = "shelter-new", manifestFingerprint = "c".repeat(64))

        // Refuse
        val refused = store.enroll(newToken, allowRotation = false)
        assertTrue(refused is GatewayEnrollmentImport.Conflict)

        // Allow
        val rotated = store.enroll(newToken, allowRotation = true)
        assertTrue(rotated is GatewayEnrollmentImport.Rotated)
        assertEquals("shelter-new", store.enrolledTokens().single().shelterId)
    }

    @Test
    fun `process re-creation restores persisted enrollments`() {
        val storage = FakeStorage()
        val store1 = PersistentGatewayEnrollmentStore(storage)
        store1.enroll(token())
        store1.enroll(token(gatewayId = "gw-test-02"))

        // Simulate process restart
        val store2 = PersistentGatewayEnrollmentStore(storage)
        assertEquals(2, store2.enrolledTokens().size)
        assertEquals(setOf("gw-test-01", "gw-test-02"), store2.enrolledTokens().map { it.gatewayId }.toSet())
    }

    @Test
    fun `list and delete operations`() {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        store.enroll(token(gatewayId = "gw-a"))
        store.enroll(token(gatewayId = "gw-b"))
        assertEquals(2, store.enrolledTokens().size)

        assertTrue(store.forget("gw-a"))
        assertEquals(1, store.enrolledTokens().size)
        assertEquals("gw-b", store.enrolledTokens().single().gatewayId)
    }

    @Test
    fun `control characters in payload are rejected`() {
        val store = PersistentGatewayEnrollmentStore(FakeStorage())
        val result = store.importPayload("relay-gw:1:gw\u0000|sh|host|443|https|$fingerprint:deadbeef")
        assertTrue(result is GatewayEnrollmentImport.Rejected)
    }

    @Test
    fun `importPayload with valid payload routes through importPayload to store`() {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        val payload = GatewayEnrollmentCodec.encodeQrPayload(token())

        val result = store.importPayload(payload)

        assertTrue("Result should be Added for valid new payload", result is GatewayEnrollmentImport.Added)
        val added = result as GatewayEnrollmentImport.Added
        assertEquals(token(), added.token)
        // Verify the store is wired correctly
        val discovered = DiscoveredGateway(
            host = "10.0.0.1", port = 8443, gatewayId = "gw-test-01",
            scheme = "https", shelterId = "shelter-test-01"
        )
        assertEquals(GatewayTrustDecision.TRUSTED, store.enrollmentStore().decisionFor(discovered))
    }
}
