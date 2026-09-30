package com.example.relay.gateway

import com.example.relay.NOW
import com.example.relay.domain.InMemoryMessageRepository
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MutableClock
import com.example.relay.message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayEnrollmentTrustTest {
    private val fingerprint = "c".repeat(64)
    private val enrolled = GatewayEnrollmentToken(
        gatewayId = "pc-gateway-example-01",
        shelterId = "shelter-example-01",
        host = "192.168.50.20",
        port = 8443,
        scheme = "https",
        manifestFingerprint = fingerprint,
    )

    @Test
    fun `spoofed beacon reusing an enrolled gatewayId is refused, never delivered`() = runTest {
        val settings = RecordingSettings(GatewaySettings())
        val client = RecordingClient()
        // A beacon claims the enrolled gatewayId but advertises a different scheme/port (spoof).
        val spoof = DiscoveredGateway(host = "10.0.0.9", port = 8080, gatewayId = enrolled.gatewayId, scheme = "http", shelterId = enrolled.shelterId)
        val engine = engineWith(settings, client, discovered = spoof, scope = backgroundScope)

        val result = engine.syncOnce()

        assertTrue(result is GatewaySyncResult.Deferred)
        assertEquals("gateway_trust_rejected", (result as GatewaySyncResult.Deferred).reason)
        assertEquals("trust_rejected", settings.discoveryResult)
        assertEquals("not_sent:gateway_trust_rejected", settings.deliveryResult)
        assertNull("must not deliver to a spoofed gateway", client.host)
    }

    @Test
    fun `beacon matching the enrolled identity is trusted and delivered`() = runTest {
        val settings = RecordingSettings(GatewaySettings())
        val client = RecordingClient()
        // Same gatewayId/scheme/port/shelter as enrolled, only the DHCP host changed.
        val trusted = DiscoveredGateway(host = "172.16.4.4", port = 8443, gatewayId = enrolled.gatewayId, scheme = "https", shelterId = enrolled.shelterId)
        val engine = engineWith(settings, client, discovered = trusted, scope = backgroundScope)

        val result = engine.syncOnce()

        assertTrue(result is GatewaySyncResult.Completed)
        assertEquals("172.16.4.4", client.host)
        assertEquals("beacon_received", settings.discoveryResult)
    }

    private suspend fun engineWith(
        settings: RecordingSettings,
        client: RecordingClient,
        discovered: DiscoveredGateway,
        scope: CoroutineScope,
    ) = GatewaySyncEngine(
        repository = InMemoryMessageRepository().also { it.insert(message(id = "trust-message")) },
        settingsStore = settings,
        credentialStore = EmptyCredentials(),
        client = client,
        policy = MessagePolicy(MutableClock(NOW)),
        scope = scope,
        deliveryLedger = InMemoryGatewayDeliveryLedger(),
        discovery = object : GatewayDiscovery {
            override suspend fun discover(timeoutMs: Int): DiscoveredGateway = discovered
        },
        localBridgeId = "bridge-test",
        enrollmentStore = GatewayEnrollmentStore(listOf(enrolled)),
    )

    private class RecordingSettings(private var value: GatewaySettings) : GatewaySettingsStoreContract {
        var discoveryResult: String? = null
        var deliveryResult: String? = null
        override fun load() = value
        override fun save(settings: GatewaySettings) { value = settings }
        override fun record(result: String, connectedAt: Long) { value = value.copy(lastSyncResult = result) }
        override fun recordDiscovery(ip: String?, result: String) { discoveryResult = result }
        override fun recordDelivery(result: String) { deliveryResult = result }
    }

    private class EmptyCredentials : GatewayCredentialStoreContract {
        override fun save(token: String) = Unit
        override fun load(): String? = null
        override fun clear() = Unit
        override fun hasToken() = false
    }

    private class RecordingClient : GatewayBridgeClient {
        var host: String? = null
        override suspend fun requestPair(settings: GatewaySettings, code: String) = false
        override suspend fun push(settings: GatewaySettings, token: String, messages: List<com.example.relay.domain.RelayMessage>) = error("unused")
        override suspend fun pullReceipts(settings: GatewaySettings, token: String) = emptyList<com.example.relay.domain.DeliveryReceipt>()
        override suspend fun pushPublic(gateway: DiscoveredGateway, bridgeId: String, bridgeName: String, messages: List<com.example.relay.domain.RelayMessage>): GatewayPushResult {
            host = gateway.host
            return GatewayPushResult(
                com.example.relay.gateway.protocol.SyncMessagesResponse(
                    acceptedMessageIds = messages.map { it.messageId },
                    receipts = listOf(com.example.relay.gateway.protocol.GatewayReceipt("receipt", messages.single().messageId, "GATEWAY_RECEIVED_UNVERIFIED", "gateway", NOW)),
                ),
            )
        }
    }
}
