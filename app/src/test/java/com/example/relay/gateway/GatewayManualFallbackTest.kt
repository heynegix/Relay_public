package com.example.relay.gateway

import com.example.relay.NOW
import com.example.relay.domain.InMemoryMessageRepository
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MutableClock
import com.example.relay.message
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayManualFallbackTest {
    @Test
    fun `udp discovery failure falls back to saved LAN IPv4`() = runTest {
        val repository = InMemoryMessageRepository().also { it.insert(message(id = "fallback-message")) }
        val settings = RecordingSettings(GatewaySettings(host = "192.168.50.20", port = 8080))
        val client = RecordingClient()
        val engine = GatewaySyncEngine(
            repository = repository,
            settingsStore = settings,
            credentialStore = EmptyCredentials(),
            client = client,
            policy = MessagePolicy(MutableClock(NOW)),
            scope = backgroundScope,
            deliveryLedger = InMemoryGatewayDeliveryLedger(),
            discovery = object : GatewayDiscovery {
                override suspend fun discover(timeoutMs: Int): DiscoveredGateway? = null
            },
            localBridgeId = "bridge-test",
        )

        engine.syncOnce()

        assertEquals("192.168.50.20", client.host)
        assertEquals("manual_fallback", settings.discoveryResult)
        assertEquals("success:sent=1, duplicate=0, receipts=1", settings.deliveryResult)
    }

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
