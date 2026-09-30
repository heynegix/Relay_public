package com.example.relay.ui.enrollment

import com.example.relay.gateway.EnrollmentPayloadStorage
import com.example.relay.gateway.GatewayEnrollmentCodec
import com.example.relay.gateway.GatewayEnrollmentToken
import com.example.relay.gateway.PersistentGatewayEnrollmentStore
import com.example.relay.gateway.enrollment.EnrollmentController
import com.example.relay.gateway.enrollment.EnrollmentUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EnrollmentViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private val fingerprint = "a".repeat(64)
    private val otherFingerprint = "b".repeat(64)

    private fun token(
        gatewayId: String = "pc-gateway-test-01",
        shelterId: String = "shelter-test-01",
        host: String = "192.168.1.100",
        port: Int = 8443,
        scheme: String = "https",
        manifestFingerprint: String = fingerprint,
    ) = GatewayEnrollmentToken(gatewayId, shelterId, host, port, scheme, manifestFingerprint)

    private class FakeStorage(initial: Set<String> = emptySet()) : EnrollmentPayloadStorage {
        var payloads: Set<String> = initial
        override fun read(): Set<String> = payloads
        override fun write(payloads: Set<String>) { this.payloads = payloads }
    }

    private fun createViewModel(storage: FakeStorage = FakeStorage()): EnrollmentViewModel {
        val store = PersistentGatewayEnrollmentStore(storage)
        val controller = EnrollmentController(store)
        return EnrollmentViewModel(controller)
    }

    @Before
    fun setup() { Dispatchers.setMain(testDispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `initial state shows empty gateway list`() = runTest {
        val vm = createViewModel()
        val state = vm.state.value
        assertEquals(EnrollmentFlowScreen.GATEWAY_LIST, state.screen)
        assertTrue(state.enrolledGateways.isEmpty())
        assertTrue(state.enrollmentState is EnrollmentUiState.Idle)
    }

    @Test
    fun `navigateToInput changes screen to INPUT`() = runTest {
        val vm = createViewModel()
        vm.navigateToInput()
        assertEquals(EnrollmentFlowScreen.INPUT, vm.state.value.screen)
    }

    @Test
    fun `navigateToList returns to GATEWAY_LIST and cancels pending`() = runTest {
        val vm = createViewModel()
        vm.navigateToInput()
        vm.navigateToList()
        assertEquals(EnrollmentFlowScreen.GATEWAY_LIST, vm.state.value.screen)
        assertTrue(vm.state.value.enrollmentState is EnrollmentUiState.Idle)
    }

    @Test
    fun `processPayload with valid QR shows PendingConfirmation`() = runTest {
        val vm = createViewModel()
        val payload = GatewayEnrollmentCodec.encodeQrPayload(token())

        vm.processPayload(payload)
        testDispatcher.scheduler.advanceUntilIdle()

        val enrollment = vm.state.value.enrollmentState
        assertTrue("Expected PendingConfirmation but got $enrollment", enrollment is EnrollmentUiState.PendingConfirmation)
        val pending = enrollment as EnrollmentUiState.PendingConfirmation
        assertEquals("pc-gateway-test-01", pending.token.gatewayId)
        assertFalse(pending.isConflict)
    }

    @Test
    fun `processPayload with invalid data shows Error`() = runTest {
        val vm = createViewModel()

        vm.processPayload("not-a-valid-payload")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.value.enrollmentState is EnrollmentUiState.Error)
    }

    @Test
    fun `processPayload with empty input is ignored`() = runTest {
        val vm = createViewModel()

        vm.processPayload("   ")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.value.enrollmentState is EnrollmentUiState.Idle)
    }

    @Test
    fun `confirmEnrollment persists token and shows Success`() = runTest {
        val vm = createViewModel()
        val payload = GatewayEnrollmentCodec.encodeQrPayload(token())

        vm.processPayload(payload)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.confirmEnrollment()
        testDispatcher.scheduler.advanceUntilIdle()

        val enrollment = vm.state.value.enrollmentState
        assertTrue("Expected Success but got $enrollment", enrollment is EnrollmentUiState.Success)
        assertEquals(1, vm.state.value.enrolledGateways.size)
        assertEquals("pc-gateway-test-01", vm.state.value.enrolledGateways[0].gatewayId)
    }

    @Test
    fun `cancelEnrollment resets to Idle without persisting`() = runTest {
        val vm = createViewModel()
        val payload = GatewayEnrollmentCodec.encodeQrPayload(token())

        vm.processPayload(payload)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.cancelEnrollment()

        assertTrue(vm.state.value.enrollmentState is EnrollmentUiState.Idle)
        assertTrue(vm.state.value.enrolledGateways.isEmpty())
        assertNull(vm.state.value.lastProcessedPayload)
    }

    @Test
    fun `conflict detected when different identity for same gatewayId`() = runTest {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        store.enroll(token()) // Pre-enroll
        val controller = EnrollmentController(store)
        val vm = EnrollmentViewModel(controller)

        // Process a different fingerprint for same gatewayId
        val conflicting = token(manifestFingerprint = otherFingerprint)
        val payload = GatewayEnrollmentCodec.encodeQrPayload(conflicting)

        vm.processPayload(payload)
        testDispatcher.scheduler.advanceUntilIdle()

        val enrollment = vm.state.value.enrollmentState
        assertTrue("Expected PendingConfirmation but got $enrollment", enrollment is EnrollmentUiState.PendingConfirmation)
        assertTrue((enrollment as EnrollmentUiState.PendingConfirmation).isConflict)
    }

    @Test
    fun `rotation requires explicit allowRotation=true`() = runTest {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        store.enroll(token())
        val controller = EnrollmentController(store)
        val vm = EnrollmentViewModel(controller)

        val conflicting = token(manifestFingerprint = otherFingerprint)
        val payload = GatewayEnrollmentCodec.encodeQrPayload(conflicting)

        vm.processPayload(payload)
        testDispatcher.scheduler.advanceUntilIdle()

        // Without rotation flag, it should remain in conflict
        vm.confirmEnrollment(allowRotation = false)
        testDispatcher.scheduler.advanceUntilIdle()

        val enrollment = vm.state.value.enrollmentState
        assertTrue("Expected PendingConfirmation (conflict) but got $enrollment",
            enrollment is EnrollmentUiState.PendingConfirmation && enrollment.isConflict)
    }

    @Test
    fun `rotation succeeds with explicit allowRotation=true`() = runTest {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        store.enroll(token())
        val controller = EnrollmentController(store)
        val vm = EnrollmentViewModel(controller)

        val conflicting = token(manifestFingerprint = otherFingerprint)
        val payload = GatewayEnrollmentCodec.encodeQrPayload(conflicting)

        vm.processPayload(payload)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.confirmEnrollment(allowRotation = true)
        testDispatcher.scheduler.advanceUntilIdle()

        val enrollment = vm.state.value.enrollmentState
        assertTrue("Expected Success but got $enrollment", enrollment is EnrollmentUiState.Success)
        assertTrue((enrollment as EnrollmentUiState.Success).wasRotation)
        assertEquals(otherFingerprint, vm.state.value.enrolledGateways[0].manifestFingerprint)
    }

    @Test
    fun `removeGateway removes from list`() = runTest {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        store.enroll(token())
        val controller = EnrollmentController(store)
        val vm = EnrollmentViewModel(controller)

        assertEquals(1, vm.state.value.enrolledGateways.size)

        vm.removeGateway("pc-gateway-test-01")

        assertTrue(vm.state.value.enrolledGateways.isEmpty())
        assertEquals("Gateway登録を解除しました", vm.state.value.userMessage)
    }

    @Test
    fun `duplicate scan of same payload is suppressed`() = runTest {
        val vm = createViewModel()
        val payload = GatewayEnrollmentCodec.encodeQrPayload(token())

        vm.processPayload(payload)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.value.enrollmentState is EnrollmentUiState.PendingConfirmation)

        // Second scan of same payload should not re-process
        vm.processPayload(payload)
        testDispatcher.scheduler.advanceUntilIdle()

        // Should still be the same PendingConfirmation (not reset or duplicated)
        assertTrue(vm.state.value.enrollmentState is EnrollmentUiState.PendingConfirmation)
    }

    @Test
    fun `onCameraPermissionDenied sets flag`() = runTest {
        val vm = createViewModel()
        assertFalse(vm.state.value.cameraPermissionDenied)

        vm.onCameraPermissionDenied()

        assertTrue(vm.state.value.cameraPermissionDenied)
    }

    @Test
    fun `acknowledgeSuccess returns to list`() = runTest {
        val vm = createViewModel()
        val payload = GatewayEnrollmentCodec.encodeQrPayload(token())

        vm.processPayload(payload)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.confirmEnrollment()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.acknowledgeSuccess()

        assertEquals(EnrollmentFlowScreen.GATEWAY_LIST, vm.state.value.screen)
        assertTrue(vm.state.value.enrollmentState is EnrollmentUiState.Idle)
    }

    @Test
    fun `process recreation restores to list with enrolled gateways`() = runTest {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        store.enroll(token())
        store.enroll(token(gatewayId = "pc-gateway-test-02", manifestFingerprint = otherFingerprint))

        // Simulate process recreation by creating a new ViewModel from same storage
        val controller = EnrollmentController(store)
        val vm = EnrollmentViewModel(controller)

        assertEquals(EnrollmentFlowScreen.GATEWAY_LIST, vm.state.value.screen)
        assertEquals(2, vm.state.value.enrolledGateways.size)
    }

    @Test
    fun `clearMessage resets userMessage to null`() = runTest {
        val storage = FakeStorage()
        val store = PersistentGatewayEnrollmentStore(storage)
        store.enroll(token())
        val controller = EnrollmentController(store)
        val vm = EnrollmentViewModel(controller)

        vm.removeGateway("pc-gateway-test-01")
        assertEquals("Gateway登録を解除しました", vm.state.value.userMessage)

        vm.clearMessage()
        assertNull(vm.state.value.userMessage)
    }
}
