package com.example.relay.runtime

import com.example.relay.domain.DeviceRole
import com.example.relay.domain.OperatingMode
import com.example.relay.domain.RelayRuntimeSettings
import com.example.relay.sync.SyncDebugEvent
import com.example.relay.sync.SyncSession
import com.example.relay.transport.ConnectionEvent
import com.example.relay.transport.OfflineTransport
import com.example.relay.transport.OfflineTransportState
import com.example.relay.transport.Peer
import com.example.relay.transport.TransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RuntimePeerStatus { DISCOVERED, AWAITING_VERIFICATION, CONNECTED, FAILED }

data class RuntimePeer(
    val peer: Peer,
    val status: RuntimePeerStatus,
    val authenticationDigits: String? = null,
    val failureReason: String? = null,
)

data class CommunicationRuntimeState(
    val mode: OperatingMode = OperatingMode.NORMAL,
    val role: DeviceRole = DeviceRole.MEMBER,
    val running: Boolean = false,
    val transportName: String = "Unknown",
    val transport: OfflineTransportState = OfflineTransportState(),
    val peers: Map<String, RuntimePeer> = emptyMap(),
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    val debugEvents: List<String> = emptyList(),
)

class RelayCommunicationRuntime(
    private val transport: OfflineTransport,
    private val syncSession: SyncSession,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(CommunicationRuntimeState(transportName = transport.javaClass.simpleName))
    val state: StateFlow<CommunicationRuntimeState> = _state

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.state.collect { value -> _state.value = _state.value.copy(transport = value, lastError = value.lastError ?: _state.value.lastError) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.discoveredPeers.collect { peers ->
                val existing = _state.value.peers
                val updated = peers.associate { peer ->
                    val previous = existing[peer.peerId]
                    peer.peerId to when (previous?.status) {
                        null, RuntimePeerStatus.FAILED -> RuntimePeer(peer, RuntimePeerStatus.DISCOVERED)
                        else -> previous
                    }
                } + existing.filterValues { it.status != RuntimePeerStatus.DISCOVERED }
                _state.value = _state.value.copy(peers = updated)
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.connectionEvents.collect(::handleConnectionEvent)
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.transportEvents.collect { appendDebug(it.toSafeText()) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            syncSession.debugEvents.collect { event ->
                appendDebug(event.toSafeText())
                when (event) {
                    is SyncDebugEvent.PeerAcknowledged,
                    is SyncDebugEvent.GatewayReceiptRecorded,
                    is SyncDebugEvent.RescueTransferCompleted,
                    -> {
                        _state.value = _state.value.copy(lastSyncAt = System.currentTimeMillis())
                    }
                    is SyncDebugEvent.SendFailed -> {
                        // Surface send failures on the operator-visible lastError path (not as success).
                        _state.value = _state.value.copy(
                            lastError = "送信失敗: ${event.reason.take(120)}",
                        )
                    }
                    is SyncDebugEvent.RescueTransferFailed -> {
                        _state.value = _state.value.copy(
                            lastError = "救助データ送信失敗: ${event.reason.take(120)}",
                        )
                    }
                    is SyncDebugEvent.Rejected -> {
                        if (event.reason.startsWith("transport transfer failed")) {
                            _state.value = _state.value.copy(
                                lastError = "送信拒否: ${event.reason.take(120)}",
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    suspend fun start(settings: RelayRuntimeSettings): Boolean = mutex.withLock {
        if (settings.mode == OperatingMode.NORMAL) {
            _state.value = _state.value.copy(lastError = "通信はDRILLまたはRELAYモードで開始してください")
            return false
        }
        if (_state.value.running) return true
        _state.value = _state.value.copy(mode = settings.mode, role = settings.role, lastError = null)
        val started = syncSession.start(settings)
        _state.value = _state.value.copy(running = started, lastError = if (started) null else "通信を開始できませんでした")
        started
    }

    suspend fun stop() = mutex.withLock {
        if (!_state.value.running) return
        try {
            syncSession.stop()
        } finally {
            _state.value = _state.value.copy(running = false, mode = OperatingMode.NORMAL, peers = emptyMap())
        }
    }

    fun stopAsync() {
        scope.launch { stop() }
    }

    suspend fun connect(peerId: String) {
        appendDebug("connection requested: peer=${peerId.take(12)}")
        transport.connect(peerId)
    }
    suspend fun accept(peerId: String) {
        appendDebug("connection approved by user: peer=${peerId.take(12)}")
        transport.acceptConnection(peerId)
    }
    suspend fun reject(peerId: String) {
        appendDebug("connection rejected by user: peer=${peerId.take(12)}")
        transport.rejectConnection(peerId)
    }
    suspend fun disconnect(peerId: String) {
        appendDebug("disconnect requested: peer=${peerId.take(12)}")
        transport.disconnect(peerId)
    }

    fun reportStartFailure(reason: String) {
        _state.value = _state.value.copy(running = false, lastError = reason.take(160))
        appendDebug("start failed: ${reason.take(160)}")
    }

    private fun handleConnectionEvent(event: ConnectionEvent) {
        val peers = _state.value.peers.toMutableMap()
        when (event) {
            is ConnectionEvent.AuthenticationRequired -> {
                peers[event.peer.peerId] = RuntimePeer(event.peer, RuntimePeerStatus.AWAITING_VERIFICATION, event.authenticationDigits)
                appendDebug("authentication required: peer=${event.peer.peerId.take(12)}")
            }
            is ConnectionEvent.Connected -> {
                peers[event.peer.peerId] = RuntimePeer(event.peer, RuntimePeerStatus.CONNECTED)
                appendDebug("connected: peer=${event.peer.peerId.take(12)}")
            }
            is ConnectionEvent.Disconnected -> {
                peers[event.peerId]?.let { peers[event.peerId] = it.copy(status = RuntimePeerStatus.DISCOVERED, authenticationDigits = null) }
                appendDebug("disconnected: peer=${event.peerId.take(12)}")
            }
            is ConnectionEvent.Failed -> {
                val peerId = event.peerId ?: return
                peers[peerId] = RuntimePeer(Peer(peerId), RuntimePeerStatus.FAILED, failureReason = event.reason.take(160))
                _state.value = _state.value.copy(lastError = event.reason.take(160))
                appendDebug("connection failed: peer=${peerId.take(12)} reason=${event.reason.take(80)}")
            }
        }
        _state.value = _state.value.copy(peers = peers)
    }

    private fun appendDebug(text: String) {
        _state.value = _state.value.copy(debugEvents = (_state.value.debugEvents + text).takeLast(100))
    }

    private fun TransportEvent.toSafeText(): String = when (this) {
        TransportEvent.AdvertisingStarted -> "advertising started"
        TransportEvent.DiscoveryStarted -> "discovery started"
        is TransportEvent.PeerFound -> "peer found: ${peerId.take(12)}"
        is TransportEvent.PeerLost -> "peer lost: ${peerId.take(12)}"
        is TransportEvent.PayloadSendRequested -> "payload send requested: peer=${peerId.take(12)} bytes=$byteCount"
        is TransportEvent.PayloadTransferCompleted -> "payload transfer completed: peer=${peerId.take(12)}"
        is TransportEvent.PayloadTransferFailed -> "payload transfer failed: peer=${peerId.take(12)} reason=${reason.take(80)}"
        is TransportEvent.PayloadReceived -> "payload received: peer=${peerId.take(12)} bytes=$byteCount"
        is TransportEvent.Error -> "$operation error: ${reason.take(100)}"
    }

    @Suppress("MagicNumber")
    private fun SyncDebugEvent.toSafeText(): String = when (this) {
        is SyncDebugEvent.Rejected -> "validation rejected: peer=${peerId.take(12)} reason=${reason.take(100)}"
        is SyncDebugEvent.SendFailed -> "send failed: peer=${peerId.take(12)} item=${itemId.take(12)} reason=${reason.take(80)}"
        is SyncDebugEvent.PayloadTransferCompleted -> "protocol payload transferred: peer=${peerId.take(12)} bytes=$byteCount"
        is SyncDebugEvent.PeerAcknowledged -> "peer ACK: peer=${peerId.take(12)} message=${messageId.take(12)}"
        is SyncDebugEvent.GatewayReceiptRecorded -> "gateway receipt: message=${messageId.take(12)} actor=${actorId.take(12)}"
        is SyncDebugEvent.MessageStored -> "DB save: message=${messageId.take(12)} duplicate=$duplicate"
        is SyncDebugEvent.RescueTransferCompleted ->
            "rescue ${packetType.take(24)} transferred: peer=${peerId.take(12)}"
        is SyncDebugEvent.RescueTransferFailed ->
            "rescue ${packetType.take(24)} failed: peer=${peerId.take(12)} reason=${reason.take(80)}"
    }
}
