package com.example.relay.protocol

import com.example.relay.NOW
import com.example.relay.domain.MutableClock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingPayloadPolicyTest {
    @Test
    fun `limits packets and bytes per peer and resets after window`() {
        val clock = MutableClock(NOW)
        val policy = FixedWindowIncomingPayloadPolicy(clock, maxPacketsPerWindow = 2, maxBytesPerWindow = 10, windowMillis = 1_000)
        assertTrue(policy.allow("peer-A", 4))
        assertTrue(policy.allow("peer-A", 6))
        assertFalse(policy.allow("peer-A", 1))
        assertTrue(policy.allow("peer-B", 10))
        clock.currentMillis += 1_000
        assertTrue(policy.allow("peer-A", 10))
    }
}
