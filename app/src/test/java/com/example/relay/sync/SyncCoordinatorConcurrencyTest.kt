package com.example.relay.sync

import com.example.relay.NOW
import com.example.relay.domain.DeliveryReceipt
import com.example.relay.domain.InMemoryMessageRepository
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MutableClock
import com.example.relay.domain.OperatingMode
import com.example.relay.domain.ReceiptType
import com.example.relay.domain.RelayRuntimeSettings
import com.example.relay.domain.ResourcePolicy
import com.example.relay.message
import com.example.relay.protocol.HelloBody
import com.example.relay.protocol.ManifestBody
import com.example.relay.protocol.PacketCodec
import com.example.relay.transport.ConnectionEvent
import com.example.relay.transport.OfflineTransport
import com.example.relay.transport.OfflineTransportState
import com.example.relay.transport.Peer
import com.example.relay.transport.ReceivedPayload
import com.example.relay.transport.SendResult
import com.example.relay.transport.TransportEvent
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCoordinatorConcurrencyTest {
    @Test
    fun `concurrent starts invoke transport start exactly once`() = runBlocking {
        val transport = BlockingStartTransport()
        val repository = InMemoryMessageRepository()
        val clock = MutableClock(NOW)
        val policy = MessagePolicy(clock)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val coordinator = SyncCoordinator(
            "device-A",
            transport,
            repository,
            SyncPlanner(repository, policy),
            policy,
            PacketCodec(policy),
            clock,
            scope,
        )

        val first = scope.async { coordinator.start(RelayRuntimeSettings(OperatingMode.DRILL)) }
        withTimeout(1_000) { transport.firstStartEntered.await() }
        val second = scope.async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.start(RelayRuntimeSettings(OperatingMode.DRILL))
        }
        val observedStartCalls = transport.startCalls.get()
        transport.releaseStart.complete(Unit)
        assertTrue(first.await())
        assertTrue(second.await())
        coordinator.stop()
        scope.cancel()

        assertEquals(1, observedStartCalls)
    }

    @Test
    fun `parallel manifest refresh cannot exceed per connection send byte budget`() = runBlocking {
        val repository = InMemoryMessageRepository().also { it.insert(message()) }
        val clock = MutableClock(NOW)
        val policy = MessagePolicy(clock)
        val codec = PacketCodec(policy)
        val packetId = "00000000-0000-0000-0000-000000000000"
        val helloBytes = codec.encode("device-A", NOW, HelloBody(), packetId).size
        val manifestBytes = codec.encode(
            "device-A",
            NOW,
            ManifestBody(SyncPlanner(repository, policy).manifest(), listOf("receipt-1")),
            packetId,
        ).size
        val byteBudget = maxOf(helloBytes, manifestBytes).toLong()
        val transport = BlockingSendTransport()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val coordinator = SyncCoordinator(
            "device-A",
            transport,
            repository,
            SyncPlanner(repository, policy),
            policy,
            codec,
            clock,
            scope,
            resourcePolicy = ResourcePolicy(maxSentBytesPerConnection = byteBudget),
            manifestRefreshDebounceMs = 0,
        )
        assertTrue(coordinator.start(RelayRuntimeSettings(OperatingMode.DRILL)))
        val rejected = scope.async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.debugEvents.first {
                it is SyncDebugEvent.Rejected && it.reason == "sent byte limit"
            }
        }

        transport.emitConnected()
        withTimeout(1_000) { transport.firstSendEntered.await() }
        repository.insertReceipt(
            DeliveryReceipt("receipt-1", "message-1", ReceiptType.PEER_RECEIVED, "device-B", NOW),
        )
        withTimeout(1_000) {
            while (transport.sentPayloadCount() < 2 && !rejected.isCompleted) yield()
        }
        transport.releaseSends.complete(Unit)
        withTimeout(1_000) {
            while (transport.inFlightSends.get() != 0) yield()
        }

        val observedBytes = transport.totalSentBytes()
        rejected.cancel()
        coordinator.stop()
        scope.cancel()

        assertTrue(
            "transport observed $observedBytes bytes with a $byteBudget-byte budget",
            observedBytes <= byteBudget,
        )
    }

    @Test
    fun `stop waits for active sync collector cleanup before returning`() = runBlocking {
        val repository = InMemoryMessageRepository()
        val clock = MutableClock(NOW)
        val policy = MessagePolicy(clock)
        val transport = CleanupAwareTransport()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val coordinator = SyncCoordinator(
            "device-A",
            transport,
            repository,
            SyncPlanner(repository, policy),
            policy,
            PacketCodec(policy),
            clock,
            scope,
        )
        assertTrue(coordinator.start(RelayRuntimeSettings(OperatingMode.DRILL)))
        transport.emitConnected()
        withTimeout(1_000) { transport.sendEntered.await() }

        val stopping = scope.async { coordinator.stop() }
        withTimeout(1_000) { transport.cleanupEntered.await() }
        val returnedBeforeCleanup = withTimeoutOrNull(200) {
            stopping.await()
            true
        } ?: false
        transport.releaseCleanup.complete(Unit)
        if (!stopping.isCompleted) stopping.await()
        scope.cancel()

        assertFalse("stop returned while an old collector was still cleaning up", returnedBeforeCleanup)
    }
}

private class BlockingStartTransport : OfflineTransport {
    private val mutableState = MutableStateFlow(OfflineTransportState())
    val startCalls = AtomicInteger()
    val firstStartEntered = CompletableDeferred<Unit>()
    val releaseStart = CompletableDeferred<Unit>()

    override val state: StateFlow<OfflineTransportState> = mutableState
    override val discoveredPeers: Flow<List<Peer>> = emptyFlow()
    override val connectionEvents: Flow<ConnectionEvent> = emptyFlow()
    override val receivedPayloads: Flow<ReceivedPayload> = emptyFlow()
    override val transportEvents: Flow<TransportEvent> = emptyFlow()

    override suspend fun start() {
        startCalls.incrementAndGet()
        firstStartEntered.complete(Unit)
        releaseStart.await()
        mutableState.value = mutableState.value.copy(started = true)
    }

    override suspend fun stop() {
        mutableState.value = OfflineTransportState()
    }

    override suspend fun connect(peerId: String) = Unit
    override suspend fun acceptConnection(peerId: String) = Unit
    override suspend fun rejectConnection(peerId: String) = Unit
    override suspend fun disconnect(peerId: String) = Unit
    override suspend fun send(peerId: String, payload: ByteArray): SendResult = SendResult.PayloadTransferCompleted
}

private class BlockingSendTransport : OfflineTransport {
    private val mutableState = MutableStateFlow(OfflineTransportState())
    private val mutableConnectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 4)
    private val sentPayloadSizes = Collections.synchronizedList(mutableListOf<Int>())
    val inFlightSends = AtomicInteger()
    val firstSendEntered = CompletableDeferred<Unit>()
    val releaseSends = CompletableDeferred<Unit>()

    override val state: StateFlow<OfflineTransportState> = mutableState
    override val discoveredPeers: Flow<List<Peer>> = emptyFlow()
    override val connectionEvents: Flow<ConnectionEvent> = mutableConnectionEvents
    override val receivedPayloads: Flow<ReceivedPayload> = emptyFlow()
    override val transportEvents: Flow<TransportEvent> = emptyFlow()

    override suspend fun start() {
        mutableState.value = OfflineTransportState(
            started = true,
            advertising = true,
            discovering = true,
            connectedPeerIds = setOf("device-B"),
        )
    }

    suspend fun emitConnected() {
        mutableConnectionEvents.emit(ConnectionEvent.Connected(Peer("device-B")))
    }

    fun sentPayloadCount(): Int = sentPayloadSizes.size

    fun totalSentBytes(): Long = synchronized(sentPayloadSizes) {
        sentPayloadSizes.sumOf { it.toLong() }
    }

    override suspend fun stop() {
        mutableState.value = OfflineTransportState()
    }

    override suspend fun connect(peerId: String) = Unit
    override suspend fun acceptConnection(peerId: String) = Unit
    override suspend fun rejectConnection(peerId: String) = Unit
    override suspend fun disconnect(peerId: String) = Unit

    override suspend fun send(peerId: String, payload: ByteArray): SendResult {
        sentPayloadSizes += payload.size
        inFlightSends.incrementAndGet()
        firstSendEntered.complete(Unit)
        return try {
            releaseSends.await()
            SendResult.PayloadTransferCompleted
        } finally {
            inFlightSends.decrementAndGet()
        }
    }
}

private class CleanupAwareTransport : OfflineTransport {
    private val mutableState = MutableStateFlow(OfflineTransportState())
    private val mutableConnectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 1)
    val sendEntered = CompletableDeferred<Unit>()
    val cleanupEntered = CompletableDeferred<Unit>()
    val releaseCleanup = CompletableDeferred<Unit>()

    override val state: StateFlow<OfflineTransportState> = mutableState
    override val discoveredPeers: Flow<List<Peer>> = emptyFlow()
    override val connectionEvents: Flow<ConnectionEvent> = mutableConnectionEvents
    override val receivedPayloads: Flow<ReceivedPayload> = emptyFlow()
    override val transportEvents: Flow<TransportEvent> = emptyFlow()

    override suspend fun start() {
        mutableState.value = OfflineTransportState(started = true, connectedPeerIds = setOf("device-B"))
    }

    suspend fun emitConnected() {
        mutableConnectionEvents.emit(ConnectionEvent.Connected(Peer("device-B")))
    }

    override suspend fun stop() {
        mutableState.value = OfflineTransportState()
    }

    override suspend fun connect(peerId: String) = Unit
    override suspend fun acceptConnection(peerId: String) = Unit
    override suspend fun rejectConnection(peerId: String) = Unit
    override suspend fun disconnect(peerId: String) = Unit

    override suspend fun send(peerId: String, payload: ByteArray): SendResult {
        sendEntered.complete(Unit)
        try {
            while (kotlin.coroutines.coroutineContext.isActive) yield()
            return SendResult.PayloadTransferCompleted
        } finally {
            withContext(NonCancellable) {
                cleanupEntered.complete(Unit)
                releaseCleanup.await()
            }
        }
    }
}
