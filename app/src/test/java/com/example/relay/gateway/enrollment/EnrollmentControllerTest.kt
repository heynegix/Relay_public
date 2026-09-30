package com.example.relay.gateway.enrollment

import com.example.relay.gateway.EnrollmentPayloadStorage
import com.example.relay.gateway.GatewayEnrollmentCodec
import com.example.relay.gateway.GatewayEnrollmentToken
import com.example.relay.gateway.PersistentGatewayEnrollmentStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentControllerTest {
    private val fingerprint = "a".repeat(64)
    private val otherFingerprint = "b".repeat(64)

    private fun token(
        gatewayId: String = "pc-gateway-ctrl-01",
        shelterId: String = "shelter-ctrl-01",
        host: String = "192.168.1.50",
        port: Int = 8443,
        scheme: String = "https",
        manifestFingerprint: String = fingerprint,
    ) = GatewayEnrollmentToken(gatewayId, shelterId, host, port, scheme, manifestFingerprint)

    private class FakeStorage(initial: Set<String> = emptySet()) : EnrollmentPayloadStorage {
        var payloads: Set<String> = initial
        override fun read(): Set<String> = payloads
        override fun write(payloads: Set<String>) { this.payloads = payloads }
    }

    private fun createController(storage: FakeStorage = FakeStorage()): Pair<EnrollmentController, PersistentGatewayEnrollmentStore> {
        val store = PersistentGatewayEnrollmentStore(storage)
        return EnrollmentController(store) to store
    }

    @Test
    fun `processPayload with valid QR returns PendingConfirmation`() {
        val (controller, _) = createController()
        val payload = GatewayEnrollmentCodec.encodeQrPayload(token())

        val result = controller.processPayload(payload)

        assertTrue(result is EnrollmentUiState.PendingConfirmation)
        val pending = result as EnrollmentUiState.PendingConfirmation
        assertEquals("pc-gateway-ctrl-01", pending.token.gatewayId)
        assertFalse(pending.isConflict)
        assertNull(pending.existingToken)
    }

    @Test
    fun `processPayload with empty input returns Error`() {
        val (controller, _) = createController()
        val result = controller.processPayload("")
        assertTrue(result is EnrollmentUiState.Error)
        assertEquals("Empty input", (result as EnrollmentUiState.Error).message)
    }

    @Test
    fun `processPayload with too long input returns Error`() {
        val (controller, _) = createController()
        val result = controller.processPayload("x".repeat(601))
        assertTrue(result is EnrollmentUiState.Error)
        assertEquals("Input too long", (result as EnrollmentUiState.Error).message)
    }

    @Test
    fun `processPayload with control characters returns Error`() {
        val (controller, _) = createController()
        val result = controller.processPayload("test\u0001payload")
        assertTrue(result is EnrollmentUiState.Error)
        assertEquals("Invalid characters in input", (result as EnrollmentUiState.Error).message)
    }

    @Test
    fun `processPayload with invalid format returns Error`() {
        val (controller, _) = createController()
        val result = controller.processPayload("not-a-relay-enrollment-payload")
        assertTrue(result is EnrollmentUiState.Error)
    }

    @Test
    fun `confirmEnrollment without prior processPayload returns Error`() {
        val (controller, _) = createController()
        val result = controller.confirmEnrollment()
        assertTrue(result is EnrollmentUiState.Error)
        assertEquals("No pending enrollment", (result as EnrollmentUiState.Error).message)
    }

    @Test
    fun `confirmEnrollment after processPayload persists and returns Success`() {
        val (controller, store) = createController()
        val payload = GatewayEnrollmentCodec.encodeQrPayload(token())

        controller.processPayload(payload)
        val result = controller.confirmEnrollment()

        assertTrue(result is EnrollmentUiState.Success)
        assertFalse((result as EnrollmentUiState.Success).wasRotation)
        assertEquals(1, store.enrolledTokens().size)
    }

    @Test
    fun `cancel clears pending state`() {
        val (controller, store) = createController()
        val payload = GatewayEnrollmentCodec.encodeQrPayload(token())

        controller.processPayload(payload)
        controller.cancel()

        val result = controller.confirmEnrollment()
        assertTrue("Should error after cancel", result is EnrollmentUiState.Error)
        assertTrue(store.enrolledTokens().isEmpty())
    }

    @Test
    fun `conflict detected for different identity of same gatewayId`() {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        store.enroll(token())
        val controller = EnrollmentController(store)

        val conflicting = token(manifestFingerprint = otherFingerprint)
        val payload = GatewayEnrollmentCodec.encodeQrPayload(conflicting)

        val result = controller.processPayload(payload)

        assertTrue(result is EnrollmentUiState.PendingConfirmation)
        assertTrue((result as EnrollmentUiState.PendingConfirmation).isConflict)
        assertEquals(token(), result.existingToken)
    }

    @Test
    fun `confirmEnrollment without rotation on conflict returns PendingConfirmation with conflict`() {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        store.enroll(token())
        val controller = EnrollmentController(store)

        val conflicting = token(manifestFingerprint = otherFingerprint)
        val payload = GatewayEnrollmentCodec.encodeQrPayload(conflicting)
        controller.processPayload(payload)

        val result = controller.confirmEnrollment(allowRotation = false)

        assertTrue(result is EnrollmentUiState.PendingConfirmation)
        assertTrue((result as EnrollmentUiState.PendingConfirmation).isConflict)
        // Original should be untouched
        assertEquals(fingerprint, store.enrolledTokens().single().manifestFingerprint)
    }

    @Test
    fun `confirmEnrollment with rotation replaces identity`() {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        store.enroll(token())
        val controller = EnrollmentController(store)

        val rotated = token(manifestFingerprint = otherFingerprint)
        val payload = GatewayEnrollmentCodec.encodeQrPayload(rotated)
        controller.processPayload(payload)

        val result = controller.confirmEnrollment(allowRotation = true)

        assertTrue(result is EnrollmentUiState.Success)
        assertTrue((result as EnrollmentUiState.Success).wasRotation)
        assertEquals(otherFingerprint, store.enrolledTokens().single().manifestFingerprint)
    }

    @Test
    fun `conflict then explicit rotation completes without re-scanning`() {
        // Regression: the first confirmEnrollment(allowRotation = false) used to clear the
        // pending token, so the conflict dialog's "replace" action failed with
        // "No pending enrollment" and a gateway key rotation could never complete.
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        store.enroll(token())
        val controller = EnrollmentController(store)

        val rotated = token(manifestFingerprint = otherFingerprint)
        val payload = GatewayEnrollmentCodec.encodeQrPayload(rotated)
        controller.processPayload(payload)

        val first = controller.confirmEnrollment(allowRotation = false)
        assertTrue(first is EnrollmentUiState.PendingConfirmation)
        assertTrue((first as EnrollmentUiState.PendingConfirmation).isConflict)
        assertEquals(fingerprint, store.enrolledTokens().single().manifestFingerprint)

        val second = controller.confirmEnrollment(allowRotation = true)
        assertTrue(second is EnrollmentUiState.Success)
        assertTrue((second as EnrollmentUiState.Success).wasRotation)
        assertEquals(otherFingerprint, store.enrolledTokens().single().manifestFingerprint)
    }

    @Test
    fun `conflict then cancel keeps original identity`() {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        store.enroll(token())
        val controller = EnrollmentController(store)

        val rotated = token(manifestFingerprint = otherFingerprint)
        controller.processPayload(GatewayEnrollmentCodec.encodeQrPayload(rotated))

        val conflict = controller.confirmEnrollment(allowRotation = false)
        assertTrue(conflict is EnrollmentUiState.PendingConfirmation)

        controller.cancel()
        assertTrue(controller.confirmEnrollment(allowRotation = true) is EnrollmentUiState.Error)
        assertEquals(fingerprint, store.enrolledTokens().single().manifestFingerprint)
    }

    @Test
    fun `enrolledGateways returns current list`() {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        store.enroll(token())
        store.enroll(token(gatewayId = "pc-gateway-ctrl-02", manifestFingerprint = otherFingerprint))
        val controller = EnrollmentController(store)

        val gateways = controller.enrolledGateways()
        assertEquals(2, gateways.size)
    }

    @Test
    fun `removeGateway removes from store and returns true`() {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        store.enroll(token())
        val controller = EnrollmentController(store)

        assertTrue(controller.removeGateway("pc-gateway-ctrl-01"))
        assertTrue(controller.enrolledGateways().isEmpty())
    }

    @Test
    fun `removeGateway for unknown returns false`() {
        val (controller, _) = createController()
        assertFalse(controller.removeGateway("nonexistent"))
    }

    @Test
    fun `re-reading same QR (idempotent) shows AlreadyEnrolled as Success`() {
        val (controller, _) = createController()
        val payload = GatewayEnrollmentCodec.encodeQrPayload(token())

        // First enrollment
        controller.processPayload(payload)
        controller.confirmEnrollment()

        // Second scan of same payload
        controller.processPayload(payload)
        val result = controller.confirmEnrollment()

        assertTrue(result is EnrollmentUiState.Success)
        assertFalse((result as EnrollmentUiState.Success).wasRotation)
    }

    @Test
    fun `formatted fingerprint is readable with dashes`() {
        val (controller, _) = createController()
        val payload = GatewayEnrollmentCodec.encodeQrPayload(token())

        val result = controller.processPayload(payload) as EnrollmentUiState.PendingConfirmation

        // 64 hex chars / 4 = 16 groups, 15 dashes
        assertTrue(result.formattedFingerprint.contains("-"))
        assertEquals(64 + 15, result.formattedFingerprint.length)
    }
}
