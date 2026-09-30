package com.example.relay.pcgateway

import com.example.relay.gateway.DiscoveredGateway
import com.example.relay.gateway.GatewayEnrollmentCodec
import com.example.relay.gateway.GatewayEnrollmentResult
import com.example.relay.gateway.GatewayEnrollmentStore
import com.example.relay.gateway.GatewayTrustDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayEnrollmentAnnouncementTest {
    private val fingerprint = "b".repeat(64)
    private val config = GatewayConfig(
        profile = GatewayProfile.DEVELOPMENT,
        gatewayId = "pc-gateway-test-01",
        shelterId = "shelter-test-01",
        publicScheme = "https",
        publicPort = 8443,
    )

    @Test
    fun `emitted QR payload decodes back to the gateway identity`() {
        val token = GatewayEnrollmentAnnouncement.buildToken(config, fingerprint, host = "192.168.1.5")
        val payload = GatewayEnrollmentCodec.encodeQrPayload(token)

        val decoded = GatewayEnrollmentCodec.decodeQrPayload(payload)
        assertTrue(decoded is GatewayEnrollmentResult.Enrolled)
        assertEquals(token, (decoded as GatewayEnrollmentResult.Enrolled).token)
    }

    @Test
    fun `a device enrolled with the emitted token trusts this gateway's own beacon`() {
        val token = GatewayEnrollmentAnnouncement.buildToken(config, fingerprint, host = "192.168.1.5")
        val store = GatewayEnrollmentStore(listOf(token))

        // The device later discovers this gateway on a different LAN address.
        val discovered = DiscoveredGateway(
            host = "10.0.5.9",
            port = config.publicPort,
            gatewayId = config.gatewayId,
            scheme = config.publicScheme,
            shelterId = config.shelterId,
        )
        assertEquals(GatewayTrustDecision.TRUSTED, store.decisionFor(discovered))
    }

    @Test
    fun `console lines expose the payload without any secret material`() {
        val lines = GatewayEnrollmentAnnouncement.consoleLines(config, fingerprint)
        assertTrue(lines.any { it.contains("QR payload") })
        assertTrue(lines.any { it.contains(GatewayEnrollmentCodec.formatManualFingerprint(fingerprint)) })
    }
}
