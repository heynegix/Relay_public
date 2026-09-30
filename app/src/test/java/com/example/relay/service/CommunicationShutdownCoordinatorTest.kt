package com.example.relay.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommunicationShutdownCoordinatorTest {
    @Test
    fun `shutdown waits for gateway and then stops communication even when service scope is cancelled`() = runTest {
        val allowGatewayStopToFinish = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val coordinator = CommunicationShutdownCoordinator(
            scope = backgroundScope,
            stopGateway = {
                calls += "gateway-start"
                allowGatewayStopToFinish.await()
                calls += "gateway-finished"
            },
            stopCommunication = { calls += "communication" },
        )

        val shutdown = coordinator.requestStop()
        runCurrent()
        assertEquals(listOf("gateway-start"), calls)

        backgroundScope.cancel()
        allowGatewayStopToFinish.complete(Unit)
        runCurrent()

        assertTrue(shutdown.isCompleted)
        assertEquals(listOf("gateway-start", "gateway-finished", "communication"), calls)
    }

    @Test
    fun `concurrent shutdown requests share one cleanup sequence`() = runTest {
        val allowStopToFinish = CompletableDeferred<Unit>()
        var gatewayStops = 0
        var communicationStops = 0
        val coordinator = CommunicationShutdownCoordinator(
            scope = backgroundScope,
            stopGateway = {
                gatewayStops++
                allowStopToFinish.await()
            },
            stopCommunication = { communicationStops++ },
        )

        val first = coordinator.requestStop()
        val second = coordinator.requestStop()
        runCurrent()

        assertSame(first, second)
        assertEquals(1, gatewayStops)
        allowStopToFinish.complete(Unit)
        runCurrent()
        assertEquals(1, communicationStops)
        assertSame(first, coordinator.requestStop())
        assertEquals(1, gatewayStops)
        assertEquals(1, communicationStops)
    }

    @Test
    fun `gateway stop failure cannot skip communication stop and is reported safely`() = runTest {
        val failures = mutableListOf<String>()
        var communicationStops = 0
        val coordinator = CommunicationShutdownCoordinator(
            scope = backgroundScope,
            stopGateway = { error("gateway secret details") },
            stopCommunication = { communicationStops++ },
            onFailure = failures::add,
        )

        coordinator.requestStop()
        runCurrent()

        assertEquals(1, communicationStops)
        assertEquals(1, failures.size)
        assertTrue(failures.single().startsWith("gateway stop failed:"))
        assertTrue(failures.single().length <= 180)
    }
}
