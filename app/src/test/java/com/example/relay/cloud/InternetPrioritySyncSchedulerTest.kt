package com.example.relay.cloud

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternetPrioritySyncSchedulerTest {
    @Test
    fun `start performs a bounded number of periodic attempts`() = runTest {
        var attempts = 0
        val scheduler = InternetPrioritySyncScheduler(
            gateway = ServerSyncGateway {
                attempts++
                ServerSyncResult.OfflineSkipped
            },
            scope = backgroundScope,
            intervalMillis = 1_000,
            maxAttempts = 3,
        )

        assertTrue(scheduler.start())
        runCurrent()
        assertEquals(1, attempts)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(3, attempts)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(3, attempts)
        assertFalse(scheduler.isRunning)
    }

    @Test
    fun `duplicate starts do not create a second loop`() = runTest {
        var attempts = 0
        val scheduler = InternetPrioritySyncScheduler(
            gateway = ServerSyncGateway {
                attempts++
                ServerSyncResult.OfflineSkipped
            },
            scope = backgroundScope,
            intervalMillis = 1_000,
            maxAttempts = 2,
        )

        assertTrue(scheduler.start())
        assertFalse(scheduler.start())
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(2, attempts)
    }

    @Test
    fun `stop cancels the periodic loop and its pending delay`() = runTest {
        var attempts = 0
        val scheduler = InternetPrioritySyncScheduler(
            gateway = ServerSyncGateway {
                attempts++
                ServerSyncResult.OfflineSkipped
            },
            scope = backgroundScope,
            intervalMillis = 1_000,
            maxAttempts = 10,
        )

        scheduler.start()
        runCurrent()
        scheduler.stop()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(1, attempts)
        assertFalse(scheduler.isRunning)
    }

    @Test
    fun `stopping internet sync does not cancel an independent nearby job`() = runTest {
        var nearbyCalls = 0
        backgroundScope.launch {
            kotlinx.coroutines.delay(1_000)
            nearbyCalls++
        }
        val scheduler = InternetPrioritySyncScheduler(
            gateway = ServerSyncGateway { ServerSyncResult.OfflineSkipped },
            scope = backgroundScope,
            intervalMillis = 1_000,
            maxAttempts = 10,
        )

        scheduler.start()
        runCurrent()
        scheduler.stop()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(1, nearbyCalls)
    }

    @Test
    fun `offline result is passed through without affecting nearby delivery`() = runTest {
        var gatewayCalls = 0
        val results = mutableListOf<ServerSyncResult>()
        val scheduler = InternetPrioritySyncScheduler(
            gateway = ServerSyncGateway {
                gatewayCalls++
                ServerSyncResult.OfflineSkipped
            },
            scope = backgroundScope,
            intervalMillis = 1_000,
            maxAttempts = 1,
            onResult = results::add,
        )

        scheduler.start()
        runCurrent()

        assertEquals(1, gatewayCalls)
        assertEquals(listOf(ServerSyncResult.OfflineSkipped), results)
    }
}
