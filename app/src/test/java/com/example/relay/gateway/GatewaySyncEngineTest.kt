package com.example.relay.gateway

import com.example.relay.NOW
import com.example.relay.domain.DeviceRole
import com.example.relay.domain.InMemoryMessageRepository
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MutableClock
import com.example.relay.domain.OperatingMode
import com.example.relay.domain.ReceiptType
import com.example.relay.domain.MessagePriority
import com.example.relay.domain.RelayRecordType
import com.example.relay.domain.ReportStatus
import com.example.relay.domain.RelayMessage
import com.example.relay.domain.RelayRuntimeSettings
import com.example.relay.domain.StatusChangePayload
import com.example.relay.gateway.protocol.GatewayReceipt
import com.example.relay.gateway.protocol.GatewayRejection
import com.example.relay.gateway.protocol.SyncMessagesResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TEST_GATEWAY_TOKEN = "token"

class GatewaySyncEngineTest {
    @Test fun `successful PC save response creates local Gateway Receipt and suppresses future uploads`() = runTest {
        val repository = InMemoryMessageRepository()
        val clock = MutableClock(NOW)
        repository.insert(com.example.relay.message())
        val settings = FakeSettings(GatewaySettings("127.0.0.1", 8080, "gateway", "bridge", enabled = true, automaticSync = true))
        val pending = FakePending()
        val client = FakeClient()
        val engine = GatewaySyncEngine(repository, settings, FakeCredentials(TEST_GATEWAY_TOKEN), client, MessagePolicy(clock), backgroundScope, pending)

        assertTrue(engine.start(RelayRuntimeSettings(OperatingMode.DRILL, DeviceRole.GATEWAY)))
        assertEquals(GatewaySyncResult.Completed(1, 1), engine.syncOnce())
        assertEquals(emptySet<String>(), pending.pendingIds(setOf("message-1")))
        assertEquals(1, repository.allReceipts().size)
        assertEquals("GATEWAY_RECEIVED", repository.allReceipts().single().receiptType.name)
    }

    @Test fun `missing token never calls PC client`() = runTest {
        val client = FakeClient()
        val engine = GatewaySyncEngine(
            InMemoryMessageRepository(),
            FakeSettings(GatewaySettings("host", 8080, "gateway", "bridge", enabled = true)),
            FakeCredentials(null),
            client,
            MessagePolicy(MutableClock(NOW)),
            backgroundScope,
            FakePending(),
        )
        assertEquals(GatewaySyncResult.Deferred("gateway_token_missing"), engine.syncOnce())
        assertEquals(0, client.pushes)
    }

    @Test fun `unconfigured bridge uses discovered public gateway without token`() = runTest {
        val repository = InMemoryMessageRepository().also { it.insert(com.example.relay.message()) }
        val client = FakeClient(publicReceipts = listOf(
            GatewayReceipt("r-unverified", "message-1", "GATEWAY_RECEIVED_UNVERIFIED", "pc-gateway", NOW),
        ))
        val engine = GatewaySyncEngine(
            repository,
            FakeSettings(GatewaySettings()),
            FakeCredentials(null),
            client,
            MessagePolicy(MutableClock(NOW)),
            backgroundScope,
            FakePending(),
            discovery = object : GatewayDiscovery {
                override suspend fun discover(timeoutMs: Int) = DiscoveredGateway("127.0.0.1", 8080, "gateway")
            },
            localBridgeId = "bridge-local",
        )
        assertEquals(GatewaySyncResult.Completed(1, 1), engine.syncOnce())
        assertEquals(1, client.publicPushes)
        assertEquals(ReceiptType.GATEWAY_RECEIVED_UNVERIFIED, repository.allReceipts().single().receiptType)
    }

    @Test fun `start accepts zero-op relay when automaticSync is false if discovery is present`() = runTest {
        val repository = InMemoryMessageRepository().also { it.insert(com.example.relay.message()) }
        val client = FakeClient(publicReceipts = listOf(
            GatewayReceipt("r-unverified", "message-1", "GATEWAY_RECEIVED_UNVERIFIED", "pc-gateway", NOW),
        ))
        val engine = GatewaySyncEngine(
            repository,
            FakeSettings(GatewaySettings(automaticSync = false)),
            FakeCredentials(null),
            client,
            MessagePolicy(MutableClock(NOW)),
            backgroundScope,
            FakePending(),
            discovery = object : GatewayDiscovery {
                override suspend fun discover(timeoutMs: Int) = DiscoveredGateway("127.0.0.1", 8080, "gateway")
            },
            localBridgeId = "bridge-local",
        )
        assertTrue(engine.start(RelayRuntimeSettings(OperatingMode.RELAY, DeviceRole.MEMBER)))
        // Loop scheduling is environment-specific; public path must still work on demand.
        assertEquals(GatewaySyncResult.Completed(1, 1), engine.syncOnce())
        assertEquals(1, client.publicPushes)
        engine.stop()
    }

    @Test fun `hop exhausted report still uploads to gateway`() = runTest {
        val repository = InMemoryMessageRepository().also {
            it.insert(com.example.relay.message(hopCount = 8, maxHopCount = 8))
        }
        val client = FakeClient(publicReceipts = listOf(
            GatewayReceipt("r-unverified", "message-1", "GATEWAY_RECEIVED_UNVERIFIED", "pc-gateway", NOW),
        ))
        val engine = GatewaySyncEngine(
            repository,
            FakeSettings(GatewaySettings()),
            FakeCredentials(null),
            client,
            MessagePolicy(MutableClock(NOW)),
            backgroundScope,
            FakePending(),
            discovery = object : GatewayDiscovery {
                override suspend fun discover(timeoutMs: Int) = DiscoveredGateway("127.0.0.1", 8080, "gateway")
            },
            localBridgeId = "bridge-local",
        )
        assertEquals(GatewaySyncResult.Completed(1, 1), engine.syncOnce())
        assertEquals(1, client.publicPushes)
    }

    @Test fun `gateway receipt prevents successful report from being resent every loop`() = runTest {
        val repository = InMemoryMessageRepository().also { it.insert(com.example.relay.message()) }
        val client = FakeClient(publicReceipts = listOf(
            GatewayReceipt("r-unverified", "message-1", "GATEWAY_RECEIVED_UNVERIFIED", "pc-gateway", NOW),
        ))
        val engine = GatewaySyncEngine(
            repository,
            FakeSettings(GatewaySettings()),
            FakeCredentials(null),
            client,
            MessagePolicy(MutableClock(NOW)),
            backgroundScope,
            FakePending(),
            discovery = object : GatewayDiscovery {
                override suspend fun discover(timeoutMs: Int) = DiscoveredGateway("127.0.0.1", 8080, "gateway")
            },
            localBridgeId = "bridge-local",
        )

        assertEquals(GatewaySyncResult.Completed(1, 1), engine.syncOnce())
        assertEquals(GatewaySyncResult.Completed(0, 0), engine.syncOnce())
        assertEquals(1, client.publicPushes)
    }

    @Test fun `authenticated receipt polling continues when there are no pending uploads`() = runTest {
        val repository = InMemoryMessageRepository().also { it.insert(com.example.relay.message()) }
        val pending = FakePending().also { it.markCompleted(setOf("message-1")) }
        val client = FakeClient()
        val engine = GatewaySyncEngine(
            repository,
            FakeSettings(GatewaySettings("127.0.0.1", 8080, "gateway", "bridge", enabled = true)),
            FakeCredentials(TEST_GATEWAY_TOKEN),
            client,
            MessagePolicy(MutableClock(NOW)),
            backgroundScope,
            pending,
        )

        assertEquals(GatewaySyncResult.Completed(0, 1), engine.syncOnce())
        assertEquals(0, client.pushes)
        assertEquals(1, client.pulls)
        assertEquals(ReceiptType.GATEWAY_RECEIVED, repository.allReceipts().single().receiptType)
    }

    @Test fun `status change and its target report are both uploaded with status change first`() = runTest {
        val repository = InMemoryMessageRepository().also {
            it.insert(com.example.relay.message(id = "message-1"))
            it.insert(
                com.example.relay.message(id = "status-1").copy(
                    recordType = RelayRecordType.STATUS_CHANGE,
                    priority = MessagePriority.CRITICAL,
                    payload = StatusChangePayload(
                        eventId = "status-1",
                        targetMessageId = "message-1",
                        newStatus = ReportStatus.RESOLVED,
                        reason = "resolved",
                        createdAt = NOW,
                        createdBy = "device-A",
                    ),
                ),
            )
        }
        val client = FakeClient(
            publicReceipts = listOf(
                GatewayReceipt("receipt-report", "message-1", "GATEWAY_RECEIVED_UNVERIFIED", "pc-gateway", NOW),
                GatewayReceipt("receipt-status", "status-1", "GATEWAY_RECEIVED_UNVERIFIED", "pc-gateway", NOW),
            ),
        )
        val engine = GatewaySyncEngine(
            repository,
            FakeSettings(GatewaySettings()),
            FakeCredentials(null),
            client,
            MessagePolicy(MutableClock(NOW)),
            backgroundScope,
            FakePending(),
            discovery = object : GatewayDiscovery {
                override suspend fun discover(timeoutMs: Int) = DiscoveredGateway("127.0.0.1", 8080, "gateway")
            },
            localBridgeId = "bridge-local",
        )

        assertEquals(GatewaySyncResult.Completed(2, 2), engine.syncOnce())
        assertEquals(
            listOf(RelayRecordType.STATUS_CHANGE, RelayRecordType.REPORT),
            client.lastPublicMessages.map { it.recordType },
        )
    }

    @Test fun `terminal gateway rejection is persisted and is not retried every loop`() = runTest {
        val repository = InMemoryMessageRepository().also { it.insert(com.example.relay.message()) }
        val pending = FakePending()
        val client = FakeClient(
            publicResponse = SyncMessagesResponse(
                rejected = listOf(GatewayRejection("message-1", "invalid_type")),
            ),
        )
        val engine = GatewaySyncEngine(
            repository,
            FakeSettings(GatewaySettings()),
            FakeCredentials(null),
            client,
            MessagePolicy(MutableClock(NOW)),
            backgroundScope,
            pending,
            discovery = object : GatewayDiscovery {
                override suspend fun discover(timeoutMs: Int) = DiscoveredGateway("127.0.0.1", 8080, "gateway")
            },
            localBridgeId = "bridge-local",
        )

        assertEquals(GatewaySyncResult.Completed(0, 0), engine.syncOnce())
        assertEquals(GatewaySyncResult.Completed(0, 0), engine.syncOnce())
        assertEquals(1, client.publicPushes)
        assertEquals(setOf("message-1"), pending.terminalIds())
    }

    @Test fun `temporary target missing rejection remains retryable`() = runTest {
        val repository = InMemoryMessageRepository().also { it.insert(com.example.relay.message()) }
        val client = FakeClient(
            publicResponse = SyncMessagesResponse(
                rejected = listOf(GatewayRejection("message-1", "target_report_not_found")),
            ),
        )
        val engine = GatewaySyncEngine(
            repository,
            FakeSettings(GatewaySettings()),
            FakeCredentials(null),
            client,
            MessagePolicy(MutableClock(NOW)),
            backgroundScope,
            FakePending(),
            discovery = object : GatewayDiscovery {
                override suspend fun discover(timeoutMs: Int) = DiscoveredGateway("127.0.0.1", 8080, "gateway")
            },
            localBridgeId = "bridge-local",
        )

        engine.syncOnce()
        engine.syncOnce()
        assertEquals(2, client.publicPushes)
    }

    @Test fun `stop does not return before the active gateway request is cleaned up`() = runTest {
        val repository = InMemoryMessageRepository().also { it.insert(com.example.relay.message()) }
        val client = SuspendingClient()
        val engine = GatewaySyncEngine(
            repository,
            FakeSettings(GatewaySettings("127.0.0.1", 8080, "gateway", "bridge", enabled = true, automaticSync = true)),
            FakeCredentials(TEST_GATEWAY_TOKEN),
            client,
            MessagePolicy(MutableClock(NOW)),
            backgroundScope,
            FakePending(),
        )
        engine.start(RelayRuntimeSettings(OperatingMode.DRILL, DeviceRole.GATEWAY))
        runCurrent()
        assertTrue(client.requestStarted.isCompleted)

        engine.stop()

        assertTrue(client.requestCleanedUp.isCompleted)
    }

    @Test fun `cancelling a manual sync is not converted into a network failure`() = runTest {
        val repository = InMemoryMessageRepository().also { it.insert(com.example.relay.message()) }
        val client = SuspendingClient()
        val settings = FakeSettings(
            GatewaySettings(
                "127.0.0.1",
                8080,
                "gateway",
                "bridge",
                enabled = true,
                lastSyncResult = "never",
            ),
        )
        val engine = GatewaySyncEngine(
            repository,
            settings,
            FakeCredentials(TEST_GATEWAY_TOKEN),
            client,
            MessagePolicy(MutableClock(NOW)),
            backgroundScope,
            FakePending(),
        )

        val sync = async { engine.syncOnce() }
        client.requestStarted.await()
        sync.cancel()
        runCurrent()

        assertTrue(sync.isCancelled)
        assertTrue(client.requestCleanedUp.isCompleted)
        assertEquals("never", settings.load().lastSyncResult)
    }

    @Test fun `cancelling a pairing request is propagated to its caller`() = runTest {
        val client = SuspendingClient()
        val engine = GatewaySyncEngine(
            InMemoryMessageRepository(),
            FakeSettings(GatewaySettings()),
            FakeCredentials(null),
            client,
            MessagePolicy(MutableClock(NOW)),
            backgroundScope,
            FakePending(),
        )

        val pairing = async { engine.requestPair("123456") }
        client.pairStarted.await()
        pairing.cancel()
        runCurrent()

        assertTrue(pairing.isCancelled)
        assertTrue(client.pairCleanedUp.isCompleted)
    }

    @Test fun `a discovered beacon contradicting an enrolled gateway is refused and never delivered to`() = runTest {
        val repository = InMemoryMessageRepository().also { it.insert(com.example.relay.message()) }
        val client = FakeClient()
        val enrolled = GatewayEnrollmentToken(
            gatewayId = "pc-gateway-1",
            shelterId = "shelter-1",
            host = "10.0.0.5",
            port = 8080,
            scheme = "https",
            manifestFingerprint = "fingerprint-1",
        )
        val engine = GatewaySyncEngine(
            repository,
            FakeSettings(GatewaySettings()),
            FakeCredentials(null),
            client,
            MessagePolicy(MutableClock(NOW)),
            backgroundScope,
            FakePending(),
            // Same gatewayId as the enrolled identity but a contradicting port: a spoofing attempt.
            discovery = object : GatewayDiscovery {
                override suspend fun discover(timeoutMs: Int) =
                    DiscoveredGateway("10.0.0.9", 9999, "pc-gateway-1", "https", "shelter-1")
            },
            localBridgeId = "bridge-local",
            enrollmentStore = GatewayEnrollmentStore(listOf(enrolled)),
        )

        assertEquals(GatewaySyncResult.Deferred("gateway_trust_rejected"), engine.syncOnce())
        // A trust refusal is a policy decision: it must never fall through to delivery, and repeating
        // the loop must keep refusing rather than eventually leaking the report to the spoofed host.
        assertEquals(GatewaySyncResult.Deferred("gateway_trust_rejected"), engine.syncOnce())
        assertEquals(0, client.publicPushes)
        assertTrue(repository.allReceipts().isEmpty())
    }

    @Test fun `a discovered beacon matching an enrolled gateway is trusted and delivered to`() = runTest {
        val repository = InMemoryMessageRepository().also { it.insert(com.example.relay.message()) }
        val client = FakeClient(publicReceipts = listOf(
            GatewayReceipt("r-verified", "message-1", "GATEWAY_RECEIVED_UNVERIFIED", "pc-gateway", NOW),
        ))
        val enrolled = GatewayEnrollmentToken(
            gatewayId = "pc-gateway-1",
            shelterId = "shelter-1",
            host = "10.0.0.5",
            port = 8080,
            scheme = "https",
            manifestFingerprint = "fingerprint-1",
        )
        val engine = GatewaySyncEngine(
            repository,
            FakeSettings(GatewaySettings()),
            FakeCredentials(null),
            client,
            MessagePolicy(MutableClock(NOW)),
            backgroundScope,
            FakePending(),
            // Host may differ (DHCP) but gatewayId/port/scheme/shelter all match the enrolled identity.
            discovery = object : GatewayDiscovery {
                override suspend fun discover(timeoutMs: Int) =
                    DiscoveredGateway("10.0.0.42", 8080, "pc-gateway-1", "https", "shelter-1")
            },
            localBridgeId = "bridge-local",
            enrollmentStore = GatewayEnrollmentStore(listOf(enrolled)),
        )

        assertEquals(GatewaySyncResult.Completed(1, 1), engine.syncOnce())
        assertEquals(1, client.publicPushes)
    }
}

private class FakeSettings(private var value: GatewaySettings) : GatewaySettingsStoreContract {
    override fun load() = value
    override fun save(settings: GatewaySettings) { value = settings }
    override fun record(result: String, connectedAt: Long) {
        value = value.copy(lastSyncResult = result, lastConnectedAt = connectedAt)
    }
}
private class FakeCredentials(private val value: String?) : GatewayCredentialStoreContract {
    override fun save(token: String) = Unit
    override fun load() = value
    override fun clear() = Unit
    override fun hasToken() = value != null
}
private class FakePending : GatewayDeliveryLedger {
    private val completed = linkedSetOf<String>()
    private val terminal = linkedSetOf<String>()
    override fun pendingIds(existingMessageIds: Set<String>): Set<String> {
        completed.retainAll(existingMessageIds)
        terminal.retainAll(existingMessageIds)
        return existingMessageIds - completed - terminal
    }
    override fun markCompleted(messageIds: Set<String>) { completed += messageIds }
    fun terminalIds() = terminal.toSet()
    override fun markTerminal(messageIds: Set<String>) { terminal += messageIds }
}
private class FakeClient(
    private val publicReceipts: List<GatewayReceipt> = emptyList(),
    private val publicResponse: SyncMessagesResponse? = null,
) : GatewayBridgeClient {
    var pushes = 0
    var pulls = 0
    var publicPushes = 0
    var lastPublicMessages: List<RelayMessage> = emptyList()
    override suspend fun requestPair(settings: GatewaySettings, code: String) = true
    override suspend fun push(settings: GatewaySettings, token: String, messages: List<RelayMessage>): GatewayPushResult {
        pushes++
        return GatewayPushResult(SyncMessagesResponse(acceptedMessageIds = messages.map { it.messageId }))
    }
    override suspend fun pullReceipts(settings: GatewaySettings, token: String) = listOf(
        com.example.relay.domain.DeliveryReceipt("receipt-1", "message-1", ReceiptType.GATEWAY_RECEIVED, "gateway", NOW),
    ).also { pulls++ }
    override suspend fun pushPublic(
        gateway: DiscoveredGateway,
        bridgeId: String,
        bridgeName: String,
        messages: List<RelayMessage>,
    ): GatewayPushResult {
        publicPushes++
        lastPublicMessages = messages
        return GatewayPushResult(
            publicResponse ?: SyncMessagesResponse(
                acceptedMessageIds = messages.map { it.messageId },
                receipts = publicReceipts,
            ),
        )
    }
}

private class SuspendingClient : GatewayBridgeClient {
    val requestStarted = CompletableDeferred<Unit>()
    val requestCleanedUp = CompletableDeferred<Unit>()
    val pairStarted = CompletableDeferred<Unit>()
    val pairCleanedUp = CompletableDeferred<Unit>()

    override suspend fun requestPair(settings: GatewaySettings, code: String): Boolean {
        pairStarted.complete(Unit)
        try {
            awaitCancellation()
        } finally {
            pairCleanedUp.complete(Unit)
        }
    }

    override suspend fun push(
        settings: GatewaySettings,
        token: String,
        messages: List<RelayMessage>,
    ): GatewayPushResult {
        requestStarted.complete(Unit)
        try {
            awaitCancellation()
        } finally {
            requestCleanedUp.complete(Unit)
        }
    }

    override suspend fun pullReceipts(settings: GatewaySettings, token: String) = emptyList<com.example.relay.domain.DeliveryReceipt>()

    override suspend fun pushPublic(
        gateway: DiscoveredGateway,
        bridgeId: String,
        bridgeName: String,
        messages: List<RelayMessage>,
    ) = error("not used")
}
