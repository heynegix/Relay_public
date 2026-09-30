package com.example.relay.sync

import com.example.relay.domain.Clock
import com.example.relay.domain.InsertResult
import com.example.relay.domain.MessageDelivery
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MessageRepository
import com.example.relay.domain.DeliveryReceipt
import com.example.relay.domain.DeviceRole
import com.example.relay.domain.OperatingMode
import com.example.relay.domain.RelayRuntimeSettings
import com.example.relay.domain.ReceiptType
import com.example.relay.domain.ResourcePolicy
import com.example.relay.domain.PayloadTransfer
import com.example.relay.protocol.AckBody
import com.example.relay.protocol.DecodeResult
import com.example.relay.protocol.DecodedPacket
import com.example.relay.protocol.ErrorBody
import com.example.relay.protocol.HelloBody
import com.example.relay.protocol.AllowAllIncomingPayloads
import com.example.relay.protocol.IncomingPayloadPolicy
import com.example.relay.protocol.ManifestBody
import com.example.relay.protocol.MessageDataBody
import com.example.relay.protocol.MessageRequestBody
import com.example.relay.protocol.ReceiptDataBody
import com.example.relay.protocol.PacketCodec
import com.example.relay.rescue.nearby.RescueNearbyCoordinator
import com.example.relay.rescue.nearby.RescueNearbyDebugEvent
import com.example.relay.transport.ConnectionEvent
import com.example.relay.transport.OfflineTransport
import com.example.relay.transport.SendResult
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SyncDebugEvent {
    data class Rejected(val peerId: String, val reason: String) : SyncDebugEvent
    /** Transport-level send failure (payload not delivered). Distinct from validation Rejected. */
    data class SendFailed(val peerId: String, val reason: String, val itemId: String) : SyncDebugEvent
    data class PayloadTransferCompleted(val peerId: String, val itemId: String, val byteCount: Int) : SyncDebugEvent
    data class PeerAcknowledged(val peerId: String, val messageId: String) : SyncDebugEvent
    data class GatewayReceiptRecorded(val messageId: String, val actorId: String) : SyncDebugEvent
    data class MessageStored(val messageId: String, val duplicate: Boolean) : SyncDebugEvent
    data class RescueTransferCompleted(val peerId: String, val packetType: String) : SyncDebugEvent
    data class RescueTransferFailed(val peerId: String, val packetType: String, val reason: String) : SyncDebugEvent
}

// Nearby BYTES payloads are capped at 32 KiB. Keep manifest/request pages below that transport
// limit even when both lists contain maximum-length identifiers.
private const val NEARBY_PAGE_SIZE = 64

@OptIn(FlowPreview::class)
@Suppress("LongParameterList")
class SyncCoordinator(
    private val deviceId: String,
    private val transport: OfflineTransport,
    private val repository: MessageRepository,
    private val planner: SyncPlanner,
    private val policy: MessagePolicy,
    private val codec: PacketCodec,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val maxReplayEntries: Int = 1_024,
    private val incomingPayloadPolicy: IncomingPayloadPolicy = AllowAllIncomingPayloads,
    private val resourcePolicy: ResourcePolicy = ResourcePolicy(),
    private val manifestRefreshDebounceMs: Long = 200,
    /** Optional encrypted rescue lane; non-rescue relay packets retain their existing codec path. */
    private val rescueNearbyCoordinator: RescueNearbyCoordinator? = null,
    /** Periodic reconciliation bounds the time a dropped manifest/request can leave peers stale. */
    private val reconciliationIntervalMs: Long = 30_000,
    /** Per-connection resource counters recover after this accounting window. */
    private val accountingWindowMs: Long = 60_000,
) : SyncSession {
    init {
        require(manifestRefreshDebounceMs >= 0) { "manifest refresh debounce must not be negative" }
        require(reconciliationIntervalMs > 0) { "reconciliation interval must be positive" }
        require(accountingWindowMs > 0) { "accounting window must be positive" }
    }

    private val lifecycleMutex = Mutex()
    private val jobs = mutableListOf<Job>()
    private val connectedPeersMutex = Mutex()
    private val connectedPeers = linkedSetOf<String>()
    private val replayCacheLock = Any()
    private val replayCache = object : LinkedHashMap<String, Unit>(maxReplayEntries + 1, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > maxReplayEntries
    }
    private val dataPackets = ConcurrentHashMap<Pair<String, String>, String>()
    private val payloadTransfers = ConcurrentHashMap<Pair<String, String>, PayloadTransfer>()
    private val accountingMutex = Mutex()
    private val receivedItems = mutableMapOf<String, Int>()
    private val sentBytes = mutableMapOf<String, Long>()
    private val accountingWindowStartedAt = mutableMapOf<String, Long>()
    private val peerPayloadMutexes = ConcurrentHashMap<String, Mutex>()
    private val peerSyncMutexes = ConcurrentHashMap<String, Mutex>()
    private val _debugEvents = kotlinx.coroutines.flow.MutableSharedFlow<SyncDebugEvent>(extraBufferCapacity = 64)
    override val debugEvents: kotlinx.coroutines.flow.SharedFlow<SyncDebugEvent> = _debugEvents
    private var started = false
    @Volatile
    private var activeSettings = RelayRuntimeSettings()
    fun payloadTransfer(messageId: String, peerId: String): PayloadTransfer? = payloadTransfers[messageId to peerId]

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    override suspend fun start(settings: RelayRuntimeSettings): Boolean = lifecycleMutex.withLock {
        if (settings.mode == OperatingMode.NORMAL) {
            _debugEvents.tryEmit(SyncDebugEvent.Rejected("local", "communication requires DRILL or RELAY mode"))
            return@withLock false
        }
        if (started) return@withLock true
        activeSettings = settings
        jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.connectionEvents.collect { event ->
                if (event is ConnectionEvent.Connected) {
                    connectedPeersMutex.withLock { connectedPeers += event.peer.peerId }
                    accountingMutex.withLock {
                        receivedItems[event.peer.peerId] = 0
                        sentBytes[event.peer.peerId] = 0
                        accountingWindowStartedAt[event.peer.peerId] = clock.nowMillis()
                    }
                    withPeerSync(event.peer.peerId) {
                        send(event.peer.peerId, HelloBody())
                        sendManifest(event.peer.peerId)
                        rescueNearbyCoordinator?.onPeerConnected(event.peer.peerId)
                    }
                } else if (event is ConnectionEvent.Disconnected) {
                    connectedPeersMutex.withLock { connectedPeers -= event.peerId }
                    incomingPayloadPolicy.onPeerDisconnected(event.peerId)
                    accountingMutex.withLock {
                        receivedItems.remove(event.peerId)
                        sentBytes.remove(event.peerId)
                        accountingWindowStartedAt.remove(event.peerId)
                    }
                    peerPayloadMutexes.remove(event.peerId)
                    peerSyncMutexes.remove(event.peerId)
                    rescueNearbyCoordinator?.onPeerDisconnected(event.peerId)
                }
            }
        }
        rescueNearbyCoordinator?.let { rescue ->
            jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
                rescue.debugEvents.collect { event ->
                    when (event) {
                        is RescueNearbyDebugEvent.TransferCompleted ->
                            _debugEvents.emit(SyncDebugEvent.RescueTransferCompleted(event.peerId, event.packetType))
                        is RescueNearbyDebugEvent.TransferFailed ->
                            _debugEvents.emit(
                                SyncDebugEvent.RescueTransferFailed(event.peerId, event.packetType, event.reason),
                            )
                    }
                }
            }
        }
        jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.receivedPayloads.collect { incoming ->
                // The bounded transport queue provides admission control. Process one payload at
                // a time here so a slow store cannot create an unbounded child-job backlog.
                val peerMutex = peerPayloadMutexes.computeIfAbsent(incoming.peerId) { Mutex() }
                peerMutex.withLock { handle(incoming.peerId, incoming.bytes) }
            }
        }
        jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.changes
                .conflate()
                .debounce(manifestRefreshDebounceMs)
                .collect {
                    val peers = connectedPeersMutex.withLock { connectedPeers.toList() }
                    peers
                        .filter { it in transport.state.value.connectedPeerIds }
                        // Do not let a blocked initial handshake prevent a later repository
                        // change from reaching the accounting gate. `send` reserves bytes under
                        // its own mutex, so concurrent refreshes remain bounded and a refresh
                        // that exceeds the budget is rejected instead of waiting indefinitely.
                        .forEach { peerId -> sendManifest(peerId) }
                }
        }
        jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (isActive) {
                delay(reconciliationIntervalMs)
                val peers = connectedPeersMutex.withLock { connectedPeers.toList() }
                    .filter { it in transport.state.value.connectedPeerIds }
                peers.forEach { peerId ->
                    launch { withPeerSync(peerId) {
                        sendManifest(peerId)
                        rescueNearbyCoordinator?.onPeerConnected(peerId)
                    } }
                }
            }
        }
        try {
            transport.start()
            if (!transport.state.value.started) {
                cancelCollectorJobs()
                reject("local", transport.state.value.lastError ?: "transport did not start")
                return@withLock false
            }
            started = true
            true
        } catch (error: Exception) {
            cancelCollectorJobs()
            reject("local", error.message ?: error.javaClass.simpleName)
            false
        }
    }

    override suspend fun stop() = lifecycleMutex.withLock {
        transport.stop()
        cancelCollectorJobs()
        connectedPeersMutex.withLock { connectedPeers.clear() }
        accountingMutex.withLock {
            receivedItems.clear()
            sentBytes.clear()
            accountingWindowStartedAt.clear()
        }
        peerPayloadMutexes.clear()
        started = false
    }

    private suspend fun cancelCollectorJobs() {
        val collectors = jobs.toList()
        jobs.clear()
        collectors.forEach { it.cancel() }
        collectors.joinAll()
    }

    private suspend fun sendManifest(peerId: String) {
        if (peerId !in transport.state.value.connectedPeerIds) return
        val messagePages = planner.manifest().chunked(NEARBY_PAGE_SIZE).ifEmpty { listOf(emptyList()) }
        val receiptPages = repository.allReceipts().map { it.receiptId }
            .chunked(NEARBY_PAGE_SIZE)
            .ifEmpty { listOf(emptyList()) }
        repeat(maxOf(messagePages.size, receiptPages.size)) { index ->
            if (peerId !in transport.state.value.connectedPeerIds) return
            send(
                peerId,
                ManifestBody(
                    messagePages.getOrElse(index) { emptyList() },
                    receiptPages.getOrElse(index) { emptyList() },
                ),
            )
        }
    }

    private suspend fun handle(peerId: String, bytes: ByteArray) {
        // Rescue packets have a dedicated codec, but they still consume the same radio, CPU, and
        // database resources as ordinary relay packets. Apply the common size/admission budget
        // before dispatching by protocol so an OPEN peer cannot bypass resource accounting with
        // an endless stream of otherwise-valid rescue frames.
        if (bytes.size > resourcePolicy.maxPayloadBytes || !incomingPayloadPolicy.allow(peerId, bytes.size)) {
            reject(peerId, "payload limit")
            return
        }
        val now = clock.nowMillis()
        val withinReceiveLimit = accountingMutex.withLock {
            resetAccountingWindowIfNeeded(peerId, now)
            val count = (receivedItems[peerId] ?: 0) + 1
            if (count > resourcePolicy.maxReceivedItemsPerConnection) false
            else {
                receivedItems[peerId] = count
                true
            }
        }
        if (!withinReceiveLimit) {
            reject(peerId, "received item limit")
            return
        }
        if (rescueNearbyCoordinator?.isRescuePayload(bytes) == true) {
            rescueNearbyCoordinator.handlePayload(peerId, bytes)
            return
        }
        val decoded = codec.decode(bytes)
        if (decoded !is DecodeResult.Success) {
            val reason = (decoded as? DecodeResult.Failure)?.error?.name ?: "decode failure"
            reject(peerId, reason)
            return
        }
        val packet = decoded.packet
        if (packet.envelope.senderDeviceId != peerId) return
        val replayKey = "$peerId:${packet.envelope.packetId}"
        val isNewPacket = synchronized(replayCacheLock) { replayCache.put(replayKey, Unit) == null }
        if (!isNewPacket) return
        when (val body = packet.body) {
            is HelloBody -> Unit
            is ManifestBody -> {
                val missing = planner.missingFromLocal(body.entries)
                val missingReceipts = planner.missingReceipts(body.receiptIds)
                val pages = maxOf(
                    (missing.size + NEARBY_PAGE_SIZE - 1) / NEARBY_PAGE_SIZE,
                    (missingReceipts.size + NEARBY_PAGE_SIZE - 1) / NEARBY_PAGE_SIZE,
                    1,
                )
                repeat(pages) { page ->
                    val offset = page * NEARBY_PAGE_SIZE
                    send(
                        peerId,
                        MessageRequestBody(
                            missing.drop(offset).take(NEARBY_PAGE_SIZE),
                            missingReceipts.drop(offset).take(NEARBY_PAGE_SIZE),
                        ),
                    )
                }
            }
            is MessageRequestBody -> {
                planner.messagesToSend(peerId, body.messageIds).forEach { message ->
                val dataPacketId = java.util.UUID.randomUUID().toString()
                dataPackets[peerId to message.messageId] = dataPacketId
                send(peerId, MessageDataBody(message), dataPacketId)
                }
                planner.receiptsToSend(body.receiptIds).forEach { receipt -> send(peerId, ReceiptDataBody(receipt)) }
            }
            is MessageDataBody -> receiveData(peerId, packet)
            is ReceiptDataBody -> repository.insertReceipt(body.receipt.forNearbyStorage())
            is AckBody -> receiveAck(peerId, body)
            is ErrorBody -> Unit
        }
    }

    private suspend fun receiveData(peerId: String, packet: DecodedPacket) {
        val body = packet.body as MessageDataBody
        val received = policy.receive(body.message) ?: return
        when (val insert = repository.insert(received)) {
            InsertResult.Inserted, InsertResult.Duplicate -> {
                _debugEvents.tryEmit(SyncDebugEvent.MessageStored(received.messageId, insert == InsertResult.Duplicate))
                val receipt = DeliveryReceipt(java.util.UUID.randomUUID().toString(), received.messageId, ReceiptType.PEER_RECEIVED, deviceId, clock.nowMillis())
                repository.insertReceipt(receipt)
                send(peerId, AckBody(received.messageId, packet.envelope.packetId, receipt))
                if (activeSettings.role == DeviceRole.GATEWAY) {
                    val gatewayReceipt = repository.receiptsFor(received.messageId).firstOrNull {
                        it.receiptType == ReceiptType.GATEWAY_RECEIVED_UNVERIFIED && it.actorId == deviceId
                    } ?: DeliveryReceipt(
                        java.util.UUID.randomUUID().toString(),
                        received.messageId,
                        ReceiptType.GATEWAY_RECEIVED_UNVERIFIED,
                        deviceId,
                        clock.nowMillis(),
                    )
                    when (repository.insertReceipt(gatewayReceipt)) {
                        InsertResult.Inserted, InsertResult.Duplicate -> {
                            _debugEvents.tryEmit(SyncDebugEvent.GatewayReceiptRecorded(received.messageId, deviceId))
                            send(peerId, ReceiptDataBody(gatewayReceipt))
                        }
                        InsertResult.Collision, is InsertResult.Rejected -> Unit
                    }
                }
            }
            InsertResult.Collision -> Unit
            is InsertResult.Rejected -> reject(peerId, "storage limit")
        }
    }

    private suspend fun receiveAck(peerId: String, body: AckBody) {
        if (!dataPackets.remove(peerId to body.messageId, body.dataPacketId)) return
        body.peerReceipt?.let { repository.insertReceipt(it) }
        if (repository.find(body.messageId) != null) {
            repository.markAcknowledged(MessageDelivery(body.messageId, peerId, clock.nowMillis(), body.dataPacketId))
            _debugEvents.tryEmit(SyncDebugEvent.PeerAcknowledged(peerId, body.messageId))
        }
    }

    private suspend fun send(
        peerId: String,
        body: com.example.relay.protocol.PacketBody,
        packetId: String = java.util.UUID.randomUUID().toString(),
    ): String {
        val encoded = codec.encode(deviceId, clock.nowMillis(), body, packetId)
        if (encoded.size > resourcePolicy.maxPayloadBytes) { reject(peerId, "outbound payload limit"); return packetId }
        val now = clock.nowMillis()
        var reservationWindow: Long? = null
        val reserved = accountingMutex.withLock {
            resetAccountingWindowIfNeeded(peerId, now)
            reservationWindow = accountingWindowStartedAt[peerId]
            val currentBytes = sentBytes[peerId] ?: 0L
            val maximum = resourcePolicy.maxSentBytesPerConnection
            if (currentBytes > maximum || encoded.size.toLong() > maximum - currentBytes) false
            else {
                sentBytes[peerId] = currentBytes + encoded.size
                true
            }
        }
        if (!reserved) { reject(peerId, "sent byte limit"); return packetId }
        val itemId = when (body) {
            is MessageDataBody -> body.message.messageId
            is ReceiptDataBody -> body.receipt.receiptId
            else -> packetId
        }
        val transfer = try {
            transport.send(peerId, encoded)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SendResult.Failed(error.message ?: error.javaClass.simpleName)
        }
        if (transfer is SendResult.PayloadTransferCompleted) {
            if (body is MessageDataBody) {
                payloadTransfers[body.message.messageId to peerId] = PayloadTransfer(body.message.messageId, peerId, packetId, clock.nowMillis())
            }
            _debugEvents.tryEmit(SyncDebugEvent.PayloadTransferCompleted(peerId, itemId, encoded.size))
        } else if (transfer is SendResult.Failed) {
            // A failed transfer consumed no link capacity. Release its reservation so a transient
            // radio failure cannot permanently exhaust the connection's send budget.
            accountingMutex.withLock {
                val failureNow = clock.nowMillis()
                resetAccountingWindowIfNeeded(peerId, failureNow)
                if (accountingWindowStartedAt[peerId] == reservationWindow) {
                    sentBytes[peerId]?.let { currentBytes ->
                        sentBytes[peerId] = (currentBytes - encoded.size).coerceAtLeast(0L)
                    }
                }
            }
            // Do not record payloadTransfers or emit completed — operator path must see a true failure.
            val reason = transfer.reason.take(160).ifBlank { "transport transfer failed" }
            _debugEvents.tryEmit(SyncDebugEvent.SendFailed(peerId, reason, itemId))
            reject(peerId, "transport transfer failed: $reason")
        }
        return packetId
    }

    suspend fun recordGatewayReceipt(messageId: String, settings: RelayRuntimeSettings): InsertResult {
        if (settings.role != DeviceRole.GATEWAY) return InsertResult.Rejected("gateway role required")
        return repository.insertReceipt(DeliveryReceipt(java.util.UUID.randomUUID().toString(), messageId, ReceiptType.GATEWAY_RECEIVED_UNVERIFIED, deviceId, clock.nowMillis()))
    }

    private fun DeliveryReceipt.forNearbyStorage(): DeliveryReceipt {
        if (receiptType != ReceiptType.GATEWAY_RECEIVED) return this
        val derived = java.util.UUID.nameUUIDFromBytes(
            "relay-nearby-unverified:$receiptId".encodeToByteArray(),
        )
        val primaryId = "nearby-unverified-$derived"
        val downgradedId = if (primaryId != receiptId) {
            primaryId
        } else {
            val alternate = java.util.UUID.nameUUIDFromBytes(
                "relay-nearby-unverified-alternate:$receiptId".encodeToByteArray(),
            )
            "nearby-unverified-alt-$alternate"
        }
        return copy(
            receiptId = downgradedId,
            receiptType = ReceiptType.GATEWAY_RECEIVED_UNVERIFIED,
        )
    }

    private fun reject(peerId: String, reason: String) { _debugEvents.tryEmit(SyncDebugEvent.Rejected(peerId, reason)) }

    private fun resetAccountingWindowIfNeeded(peerId: String, nowEpochMillis: Long) {
        val startedAt = accountingWindowStartedAt[peerId]
        if (startedAt == null || nowEpochMillis < startedAt || nowEpochMillis - startedAt >= accountingWindowMs) {
            accountingWindowStartedAt[peerId] = nowEpochMillis
            receivedItems[peerId] = 0
            sentBytes[peerId] = 0L
        }
    }

    private suspend inline fun withPeerSync(peerId: String, crossinline block: suspend () -> Unit) {
        peerSyncMutexes.computeIfAbsent(peerId) { Mutex() }.withLock { block() }
    }
}
