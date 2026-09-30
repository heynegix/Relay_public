package com.example.relay.pcgateway

import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayLanBeaconTest {
    @Test
    fun `always includes limited broadcast`() {
        val targets = GatewayLanBeacon(
            GatewayConfig(profile = GatewayProfile.DEVELOPMENT, lanDiscoveryEnabled = true),
            rescueTrustReady = false,
        ).broadcastTargets()
        assertTrue(targets.any { it.hostAddress == "255.255.255.255" })
    }

    @Test
    fun `enumerates usable interface broadcasts without duplicate targets`() {
        val targets = GatewayLanBeacon(
            GatewayConfig(profile = GatewayProfile.DEVELOPMENT, lanDiscoveryEnabled = true),
            rescueTrustReady = false,
        ).broadcastTargets()
        assertTrue(targets.isNotEmpty())
        assertTrue(targets.size == targets.map { it.hostAddress }.toSet().size)
    }
}
