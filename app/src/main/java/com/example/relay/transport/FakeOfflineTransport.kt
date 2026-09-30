package com.example.relay.transport

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeNetwork {
    private val transports = linkedMapOf<String, FakeOfflineTransport>()

    suspend fun register(transport: FakeOfflineTransport) {
        synchronized(this) { transports[transport.deviceId] = transport }
        publishDiscovery()
    }

    suspend fun unregister(transport: FakeOfflineTransport) {
        val peers = synchronized(this) {
            transports.remove(transport.deviceId)
            transports.values.toList()
        }
        peers.forEach { it.remoteDisconnected(transport.deviceId) }
        publishDiscovery()
    }

    suspend fun connect(fromId: String, toId: String) {
        val pair = synchronized(this) { transports[fromId] to transports[toId] }
        val from = pair.first ?: throw IllegalStateException("source is not started")
        val to = pair.second ?: throw IllegalArgumentException("unknown peer: $toId")
        val fromAdded = from.markConnected(toId)
        val toAdded = to.markConnected(fromId)
        if (fromAdded) from.emitConnected(toId)
        if (toAdded) to.emitConnected(fromId)
    }

    suspend fun disconnect(fromId: String, toId: String) {
        val pair = synchronized(this) { transports[fromId] to transports[toId] }
        pair.first?.remoteDisconnected(toId)
        pair.second?.remoteDisconnected(fromId)
    }

    suspend fun deliver(fromId: String, toId: String, bytes: ByteArray) {
        val target = synchronized(this) { transports[toId] } ?: throw IllegalStateException("peer is offline")
        target.receive(fromId, bytes.copyOf())
    }

    private fun publishDiscovery() {
        val snapshot = synchronized(this) { transports.values.toList() }
        snapshot.forEach { current ->
            current.updatePeers(snapshot.filter { it !== current }.map { Peer(it.deviceId) })
        }
    }
}

class FakeOfflineTransport(
    val deviceId: String,
    private val network: FakeNetwork,
) : OfflineTransport {
    private val transportState = MutableStateFlow(OfflineTransportState())
    private val peers = MutableStateFlow<List<Peer>>(emptyList())
    private val events = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 16)
    private val payloads = MutableSharedFlow<ReceivedPayload>(extraBufferCapacity = 64)
    private val transportEventFlow = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    private val connected = linkedSetOf<String>()
    private var started = false
    /**
     * When non-null, [send] returns [SendResult.Failed] with this reason.
     * Cleared only via [clearSendFailure] so handshake/MANIFEST retries can be forced to fail.
     */
    @Volatile
    var sendFailureReason: String? = null

    fun clearSendFailure() {
        sendFailureReason = null
    }

    override val state: StateFlow<OfflineTransportState> = transportState
    override val discoveredPeers: StateFlow<List<Peer>> = peers
    override val connectionEvents = events
    override val receivedPayloads = payloads
    override val transportEvents = transportEventFlow

    override suspend fun start() {
        if (!started) {
            started = true
            network.register(this)
            transportState.value = transportState.value.copy(started = true, advertising = true, discovering = true)
        }
    }

    override suspend fun stop() {
        if (started) {
            started = false
            network.unregister(this)
            connected.clear()
            transportState.value = OfflineTransportState()
        }
    }

    override suspend fun connect(peerId: String) {
        check(started) { "transport is not started" }
        network.connect(deviceId, peerId)
    }

    override suspend fun acceptConnection(peerId: String) = Unit

    override suspend fun rejectConnection(peerId: String) {
        disconnect(peerId)
    }

    override suspend fun disconnect(peerId: String) = network.disconnect(deviceId, peerId)

    override suspend fun send(peerId: String, payload: ByteArray): SendResult {
        check(peerId in connected) { "peer is not connected" }
        val forced = sendFailureReason
        if (forced != null) {
            return SendResult.Failed(forced)
        }
        network.deliver(deviceId, peerId, payload)
        return SendResult.PayloadTransferCompleted
    }

    internal fun updatePeers(value: List<Peer>) { peers.value = value }

    internal fun markConnected(peerId: String): Boolean = connected.add(peerId).also {
        transportState.value = transportState.value.copy(connectedPeerIds = connected.toSet())
    }

    internal suspend fun emitConnected(peerId: String) {
        events.emit(ConnectionEvent.Connected(Peer(peerId)))
    }

    internal suspend fun remoteDisconnected(peerId: String) {
        if (connected.remove(peerId)) {
            transportState.value = transportState.value.copy(connectedPeerIds = connected.toSet())
            events.emit(ConnectionEvent.Disconnected(peerId))
        }
    }

    internal suspend fun receive(peerId: String, bytes: ByteArray) {
        if (peerId in connected) payloads.emit(ReceivedPayload(peerId, bytes))
    }
}
