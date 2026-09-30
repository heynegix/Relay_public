package com.example.relay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyConnectionPolicyTest {
    @Test
    fun `open mode admits every peer`() {
        val policy = NearbyConnectionPolicy(NearbyConnectionMode.OPEN)
        assertTrue(policy.allowsConnection("anyone"))
    }

    @Test
    fun `trusted mode admits only allow-listed peers`() {
        val policy = NearbyConnectionPolicy(NearbyConnectionMode.TRUSTED, trustedPeers = setOf("peer-1"))
        assertTrue(policy.allowsConnection("peer-1"))
        assertFalse(policy.allowsConnection("peer-2"))
    }

    @Test
    fun `trust and revoke update admission under trusted mode`() {
        val policy = NearbyConnectionPolicy(NearbyConnectionMode.TRUSTED)
        assertFalse(policy.allowsConnection("peer-1"))
        policy.trust("peer-1")
        assertTrue(policy.allowsConnection("peer-1"))
        policy.revoke("peer-1")
        assertFalse(policy.allowsConnection("peer-1"))
    }

    @Test
    fun `switching to trusted does not retroactively trust nearby peers`() {
        val policy = NearbyConnectionPolicy(NearbyConnectionMode.OPEN)
        assertTrue(policy.allowsConnection("stranger"))
        policy.setMode(NearbyConnectionMode.TRUSTED)
        assertFalse(policy.allowsConnection("stranger"))
        assertEquals(NearbyConnectionMode.TRUSTED, policy.mode.value)
    }

    @Test
    fun `blank peers are never trusted`() {
        val policy = NearbyConnectionPolicy(NearbyConnectionMode.TRUSTED, trustedPeers = setOf("", "peer-1"))
        policy.trust("")
        assertEquals(setOf("peer-1"), policy.trustedPeers)
    }
}
