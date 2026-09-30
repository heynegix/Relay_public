package com.example.relay.sync

import com.example.relay.NOW
import com.example.relay.domain.InMemoryMessageRepository
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MutableClock
import com.example.relay.domain.OperatingMode
import com.example.relay.domain.RelayRuntimeSettings
import com.example.relay.domain.DeviceRole
import com.example.relay.domain.DeliveryReceipt
import com.example.relay.domain.ReceiptType
import com.example.relay.domain.CreateSafetyMessageUseCase
import com.example.relay.domain.MessageIdGenerator
import com.example.relay.domain.ResourcePolicy
import com.example.relay.domain.SafetyState
import com.example.relay.message
import com.example.relay.protocol.PacketCodec
import com.example.relay.protocol.ReceiptDataBody
import com.example.relay.transport.FakeNetwork
import com.example.relay.transport.FakeOfflineTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiHopSyncTest {
    @Test
    fun `report created after peers connect is synchronized without reconnecting`() = runBlocking {
        val network = FakeNetwork()
        val clock = MutableClock(NOW)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val aRepo = InMemoryMessageRepository().also { it.insert(message(id = "handshake")) }
        val bRepo = InMemoryMessageRepository()
        val aTransport = FakeOfflineTransport("device-A", network)
        val bTransport = FakeOfflineTransport("device-B", network)
        val aPolicy = MessagePolicy(clock)
        val bPolicy = MessagePolicy(clock)
        val a = SyncCoordinator(
            "device-A", aTransport, aRepo, SyncPlanner(aRepo, aPolicy), aPolicy,
            PacketCodec(aPolicy), clock, scope,
        )
        val b = SyncCoordinator(
            "device-B", bTransport, bRepo, SyncPlanner(bRepo, bPolicy), bPolicy,
            PacketCodec(bPolicy), clock, scope,
        )

        a.start(RelayRuntimeSettings(OperatingMode.DRILL))
        b.start(RelayRuntimeSettings(OperatingMode.DRILL))
        aTransport.connect("device-B")
        await { bRepo.find("handshake") != null }
        aRepo.insert(message(id = "message-late"))

        await { bRepo.find("message-late") != null }
        assertEquals(1, bRepo.find("message-late")!!.hopCount)
        scope.cancel()
    }

    @Test
    fun `receipt created after peers connect propagates without reconnecting`() = runBlocking {
        val network = FakeNetwork()
        val clock = MutableClock(NOW)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val aRepo = InMemoryMessageRepository().also {
            it.insert(message(id = "message-1"))
            it.insert(message(id = "handshake-A"))
        }
        val bRepo = InMemoryMessageRepository().also {
            it.insert(message(id = "message-1"))
            it.insert(message(id = "handshake-B"))
        }
        val aTransport = FakeOfflineTransport("device-A", network)
        val bTransport = FakeOfflineTransport("device-B", network)
        val aPolicy = MessagePolicy(clock)
        val bPolicy = MessagePolicy(clock)
        val a = SyncCoordinator(
            "device-A", aTransport, aRepo, SyncPlanner(aRepo, aPolicy), aPolicy,
            PacketCodec(aPolicy), clock, scope,
        )
        val b = SyncCoordinator(
            "device-B", bTransport, bRepo, SyncPlanner(bRepo, bPolicy), bPolicy,
            PacketCodec(bPolicy), clock, scope,
        )

        a.start(RelayRuntimeSettings(OperatingMode.DRILL))
        b.start(RelayRuntimeSettings(OperatingMode.DRILL))
        aTransport.connect("device-B")
        await { aRepo.find("handshake-B") != null && bRepo.find("handshake-A") != null }
        bRepo.insertReceipt(
            DeliveryReceipt("receipt-gateway", "message-1", ReceiptType.GATEWAY_RECEIVED_UNVERIFIED, "gateway-1", NOW),
        )

        await { aRepo.receiptsFor("message-1").any { it.receiptId == "receipt-gateway" } }
        assertEquals(1, aRepo.receiptsFor("message-1").count { it.receiptId == "receipt-gateway" })
        scope.cancel()
    }

    @Test
    fun `repository changes do not send after peer disconnects`() = runBlocking {
        val network = FakeNetwork()
        val clock = MutableClock(NOW)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val aRepo = InMemoryMessageRepository().also { it.insert(message(id = "handshake")) }
        val bRepo = InMemoryMessageRepository()
        val aTransport = FakeOfflineTransport("device-A", network)
        val bTransport = FakeOfflineTransport("device-B", network)
        val aPolicy = MessagePolicy(clock)
        val bPolicy = MessagePolicy(clock)
        val a = SyncCoordinator(
            "device-A", aTransport, aRepo, SyncPlanner(aRepo, aPolicy), aPolicy,
            PacketCodec(aPolicy), clock, scope,
        )
        val b = SyncCoordinator(
            "device-B", bTransport, bRepo, SyncPlanner(bRepo, bPolicy), bPolicy,
            PacketCodec(bPolicy), clock, scope,
        )

        a.start(RelayRuntimeSettings(OperatingMode.DRILL))
        b.start(RelayRuntimeSettings(OperatingMode.DRILL))
        aTransport.connect("device-B")
        await { bRepo.find("handshake") != null }
        aTransport.disconnect("device-B")
        await { "device-B" !in aTransport.state.value.connectedPeerIds }

        aRepo.insert(message(id = "after-disconnect"))
        kotlinx.coroutines.delay(300)

        assertTrue(bRepo.find("after-disconnect") == null)
        scope.cancel()
    }

    @Test
    fun `verified gateway receipt received over Nearby is downgraded deterministically`() = runBlocking {
        val network = FakeNetwork()
        val clock = MutableClock(NOW)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val sender = FakeOfflineTransport("device-A", network)
        val receiverTransport = FakeOfflineTransport("device-B", network)
        val receiverRepository = InMemoryMessageRepository().also { it.insert(message()) }
        val policy = MessagePolicy(clock)
        val receiver = SyncCoordinator(
            "device-B",
            receiverTransport,
            receiverRepository,
            SyncPlanner(receiverRepository, policy),
            policy,
            PacketCodec(policy),
            clock,
            scope,
        )
        val verified = DeliveryReceipt(
            "verified-receipt",
            "message-1",
            ReceiptType.GATEWAY_RECEIVED,
            "gateway-1",
            NOW,
        )
        val codec = PacketCodec(policy)

        sender.start()
        receiver.start(RelayRuntimeSettings(OperatingMode.DRILL))
        sender.connect("device-B")
        sender.send(
            "device-B",
            codec.encode("device-A", NOW, ReceiptDataBody(verified), "receipt-packet-1"),
        )

        await {
            receiverRepository.receiptsFor("message-1")
                .any { it.receiptType == ReceiptType.GATEWAY_RECEIVED_UNVERIFIED }
        }
        val downgraded = receiverRepository.receiptsFor("message-1").single()
        assertTrue(downgraded.receiptId != verified.receiptId)
        assertEquals(ReceiptType.GATEWAY_RECEIVED_UNVERIFIED, downgraded.receiptType)
        assertTrue(receiverRepository.receiptsFor("message-1").none { it.receiptType == ReceiptType.GATEWAY_RECEIVED })

        sender.send(
            "device-B",
            codec.encode("device-A", NOW, ReceiptDataBody(verified), "receipt-packet-2"),
        )
        kotlinx.coroutines.delay(50)
        assertEquals(1, receiverRepository.receiptsFor("message-1").size)
        scope.cancel()
    }

    @Test
    fun `report reaches gateway and receipt returns to origin exactly once`() = runBlocking {
        val network = FakeNetwork()
        val clock = MutableClock(NOW)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        fun node(id: String): Node {
            val repository = InMemoryMessageRepository()
            val policy = MessagePolicy(clock)
            val transport = FakeOfflineTransport(id, network)
            return Node(repository, transport, SyncCoordinator(id, transport, repository, SyncPlanner(repository, policy), policy, PacketCodec(policy), clock, scope))
        }
        val a = node("device-A")
        val b = node("device-B")
        val c = node("device-C")
        CreateSafetyMessageUseCase(
            a.repository,
            MessagePolicy(clock),
            clock,
            "device-A",
            MessageIdGenerator { "message-1" },
        )(SafetyState.SAFE, 1, "north area", "created by A")
        val drill = RelayRuntimeSettings(OperatingMode.DRILL)
        a.coordinator.start(drill); b.coordinator.start(drill)
        c.coordinator.start(RelayRuntimeSettings(OperatingMode.DRILL, DeviceRole.GATEWAY))

        a.transport.connect("device-B")
        await { b.repository.find("message-1") != null }
        a.transport.disconnect("device-B")
        b.transport.connect("device-C")
        await { c.repository.find("message-1") != null }
        assertEquals(1, c.repository.all().size)
        await { c.repository.receiptsFor("message-1").any { it.receiptType == ReceiptType.GATEWAY_RECEIVED_UNVERIFIED } }
        assertEquals(1, c.repository.receiptsFor("message-1").count { it.receiptType == ReceiptType.GATEWAY_RECEIVED_UNVERIFIED })

        b.transport.disconnect("device-C")
        c.transport.connect("device-B")
        await { b.repository.receiptsFor("message-1").any { it.receiptType == ReceiptType.GATEWAY_RECEIVED_UNVERIFIED } }
        c.transport.disconnect("device-B")
        b.transport.connect("device-A")
        await { a.repository.receiptsFor("message-1").any { it.receiptType == ReceiptType.GATEWAY_RECEIVED_UNVERIFIED } }

        assertEquals(1, a.repository.all().size)
        assertEquals(1, a.repository.receiptsFor("message-1").count { it.receiptType == ReceiptType.GATEWAY_RECEIVED_UNVERIFIED })
        b.transport.disconnect("device-A")
        b.transport.connect("device-A")
        await { a.repository.receiptsFor("message-1").size >= 1 }
        assertEquals(1, a.repository.receiptsFor("message-1").count { it.receiptType == ReceiptType.GATEWAY_RECEIVED_UNVERIFIED })
        scope.cancel()
    }

    @Test
    fun `expired report is not transferred`() = runBlocking {
        val network = FakeNetwork()
        val clock = MutableClock(NOW)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val aRepo = InMemoryMessageRepository()
        val bRepo = InMemoryMessageRepository()
        val aPolicy = MessagePolicy(clock)
        val bPolicy = MessagePolicy(clock)
        val aTransport = FakeOfflineTransport("device-A", network)
        val bTransport = FakeOfflineTransport("device-B", network)
        val a = SyncCoordinator("device-A", aTransport, aRepo, SyncPlanner(aRepo, aPolicy), aPolicy, PacketCodec(aPolicy), clock, scope)
        val b = SyncCoordinator("device-B", bTransport, bRepo, SyncPlanner(bRepo, bPolicy), bPolicy, PacketCodec(bPolicy), clock, scope)
        aRepo.insert(message(expiresAt = NOW + 1_000))
        clock.currentMillis = NOW + 2_000
        clock.currentElapsedRealtimeMillis = NOW + 2_000
        val drill = RelayRuntimeSettings(OperatingMode.DRILL)
        a.start(drill); b.start(drill)
        aTransport.connect("device-B")
        kotlinx.coroutines.delay(20)
        assertTrue(bRepo.all().isEmpty())
        scope.cancel()
    }
    @Test
    fun `message travels A to B then B to C exactly once`() = runBlocking {
        val network = FakeNetwork()
        val clock = MutableClock(NOW)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())

        fun node(id: String): Node {
            val repository = InMemoryMessageRepository()
            val policy = MessagePolicy(clock)
            val transport = FakeOfflineTransport(id, network)
            return Node(
                repository,
                transport,
                SyncCoordinator(
                    id, transport, repository, SyncPlanner(repository, policy), policy,
                    PacketCodec(policy), clock, scope,
                ),
            )
        }

        val a = node("device-A")
        val b = node("device-B")
        val c = node("device-C")
        a.repository.insert(message())
        a.coordinator.start(RelayRuntimeSettings(OperatingMode.DRILL))
        b.coordinator.start(RelayRuntimeSettings(OperatingMode.DRILL))
        c.coordinator.start(RelayRuntimeSettings(OperatingMode.DRILL))

        a.transport.connect("device-B")
        await { b.repository.find("message-1") != null && a.repository.wasAcknowledged("message-1", "device-B") }
        assertEquals(1, b.repository.all().size)
        assertEquals(1, b.repository.find("message-1")!!.hopCount)
        assertTrue(a.repository.wasAcknowledged("message-1", "device-B"))
        assertEquals(1, b.repository.receiptsFor("message-1").count { it.receiptType == ReceiptType.PEER_RECEIVED })
        assertEquals(0, b.repository.receiptsFor("message-1").count { it.receiptType == ReceiptType.GATEWAY_RECEIVED })

        a.transport.disconnect("device-B")
        b.transport.connect("device-C")
        await { c.repository.find("message-1") != null && b.repository.wasAcknowledged("message-1", "device-C") }
        assertEquals(1, c.repository.all().size)
        assertEquals(2, c.repository.find("message-1")!!.hopCount)
        assertTrue(b.repository.wasAcknowledged("message-1", "device-C"))

        b.transport.disconnect("device-C")
        b.transport.connect("device-C")
        await { c.repository.all().size == 1 }
        assertEquals(1, c.repository.all().size)
        assertEquals(1, b.repository.all().size)
        scope.cancel()
    }

    @Test
    fun `gateway storage rejection creates neither ACK nor gateway receipt`() = runBlocking {
        val network = FakeNetwork()
        val clock = MutableClock(NOW)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val aRepo = InMemoryMessageRepository()
        val gatewayRepo = InMemoryMessageRepository(ResourcePolicy(maxStoredMessages = 0))
        val aPolicy = MessagePolicy(clock)
        val gatewayPolicy = MessagePolicy(clock)
        val aTransport = FakeOfflineTransport("device-A", network)
        val gatewayTransport = FakeOfflineTransport("device-G", network)
        val a = SyncCoordinator("device-A", aTransport, aRepo, SyncPlanner(aRepo, aPolicy), aPolicy, PacketCodec(aPolicy), clock, scope)
        val gateway = SyncCoordinator("device-G", gatewayTransport, gatewayRepo, SyncPlanner(gatewayRepo, gatewayPolicy), gatewayPolicy, PacketCodec(gatewayPolicy), clock, scope)
        aRepo.insert(message())
        a.start(RelayRuntimeSettings(OperatingMode.DRILL, DeviceRole.MEMBER))
        gateway.start(RelayRuntimeSettings(OperatingMode.DRILL, DeviceRole.GATEWAY))

        aTransport.connect("device-G")
        kotlinx.coroutines.delay(100)

        assertTrue(gatewayRepo.all().isEmpty())
        assertTrue(gatewayRepo.allReceipts().isEmpty())
        assertTrue(!aRepo.wasAcknowledged("message-1", "device-G"))
        scope.cancel()
    }

    @Test
    fun `malformed and oversized payloads are rejected through sync ingress`() = runBlocking {
        val network = FakeNetwork()
        val clock = MutableClock(NOW)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val repository = InMemoryMessageRepository()
        val policy = MessagePolicy(clock)
        val sender = FakeOfflineTransport("device-A", network)
        val receiverTransport = FakeOfflineTransport("device-B", network)
        val receiver = SyncCoordinator(
            "device-B",
            receiverTransport,
            repository,
            SyncPlanner(repository, policy),
            policy,
            PacketCodec(policy),
            clock,
            scope,
        )
        sender.start()
        receiver.start(RelayRuntimeSettings(OperatingMode.DRILL))
        sender.connect("device-B")

        sender.send("device-B", "not-json".encodeToByteArray())
        sender.send("device-B", ByteArray(64 * 1024 + 1))
        kotlinx.coroutines.delay(100)

        assertTrue(repository.all().isEmpty())
        scope.cancel()
    }

    @Test
    fun `more than one request page is synchronized`() = runBlocking {
        val network = FakeNetwork()
        val clock = MutableClock(NOW)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())

        fun node(id: String): Node {
            val repository = InMemoryMessageRepository()
            val policy = MessagePolicy(clock)
            val transport = FakeOfflineTransport(id, network)
            return Node(repository, transport, SyncCoordinator(
                id, transport, repository, SyncPlanner(repository, policy), policy,
                PacketCodec(policy), clock, scope,
            ))
        }

        val a = node("device-A")
        val b = node("device-B")
        repeat(129) { index -> a.repository.insert(message().copy(messageId = "message-$index")) }
        a.coordinator.start(RelayRuntimeSettings(OperatingMode.DRILL))
        b.coordinator.start(RelayRuntimeSettings(OperatingMode.DRILL))
        a.transport.connect("device-B")

        withTimeout(5_000) {
            while (b.repository.all().size != 129) yield()
        }
        assertEquals(129, b.repository.all().size)
        scope.cancel()
    }

    private data class Node(
        val repository: InMemoryMessageRepository,
        val transport: FakeOfflineTransport,
        val coordinator: SyncCoordinator,
    )

    private suspend fun await(condition: suspend () -> Boolean) {
        withTimeout(1_000) {
            while (!condition()) yield()
        }
    }
}
