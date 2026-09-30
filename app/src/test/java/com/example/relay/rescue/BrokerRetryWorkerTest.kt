package com.example.relay.rescue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Unit tests for BrokerRetryWorker logic (non-Android parts).
 * WorkManager execution requires instrumentation tests.
 */
class BrokerRetryWorkerTest {

    @Test
    fun `unique work name format is deterministic`() {
        val name = BrokerRetryWorker.uniqueWorkName("request-abc", 3)
        assertEquals("broker_retry_request-abc_3", name)
    }

    @Test
    fun `unique work names differ per request`() {
        val name1 = BrokerRetryWorker.uniqueWorkName("request-1", 1)
        val name2 = BrokerRetryWorker.uniqueWorkName("request-2", 1)
        val name3 = BrokerRetryWorker.uniqueWorkName("request-1", 2)

        assertNotEquals(name1, name2)
        assertNotEquals(name1, name3)
    }

    @Test
    fun `unique work name handles special characters in requestId`() {
        val name = BrokerRetryWorker.uniqueWorkName("req-with-dashes_and_underscores", 10)
        assertEquals("broker_retry_req-with-dashes_and_underscores_10", name)
    }
}
