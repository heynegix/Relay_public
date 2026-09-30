package com.example.relay.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class Peer(val peerId: String, val displayName: String = peerId)

sealed interface ConnectionEvent {
    data class AuthenticationRequired(val peer: Peer, val authenticationDigits: String) : ConnectionEvent
    data class Connected(val peer: Peer) : ConnectionEvent
    data class Disconnected(val peerId: String) : ConnectionEvent
    data class Failed(val peerId: String?, val reason: String) : ConnectionEvent
}

data class OfflineTransportState(
    val started: Boolean = false,
    val advertising: Boolean = false,
    val discovering: Boolean = false,
    val connectedPeerIds: Set<String> = emptySet(),
    val pendingVerifications: Map<String, String> = emptyMap(),
    val lastError: String? = null,
)

sealed interface TransportEvent {
    data object AdvertisingStarted : TransportEvent
    data object DiscoveryStarted : TransportEvent
    data class PeerFound(val peerId: String) : TransportEvent
    data class PeerLost(val peerId: String) : TransportEvent
    data class PayloadSendRequested(val peerId: String, val payloadId: Long, val byteCount: Int) : TransportEvent
    data class PayloadTransferCompleted(val peerId: String, val payloadId: Long) : TransportEvent
    data class PayloadTransferFailed(val peerId: String, val payloadId: Long, val reason: String) : TransportEvent
    data class PayloadReceived(val peerId: String, val byteCount: Int) : TransportEvent
    data class Error(val operation: String, val reason: String) : TransportEvent
}

data class ReceivedPayload(val peerId: String, val bytes: ByteArray)

sealed interface SendResult {
    data object PayloadTransferCompleted : SendResult
    data class Failed(val reason: String) : SendResult
}

interface OfflineTransport {
    val state: StateFlow<OfflineTransportState>
    val discoveredPeers: Flow<List<Peer>>
    val connectionEvents: Flow<ConnectionEvent>
    val receivedPayloads: Flow<ReceivedPayload>
    val transportEvents: Flow<TransportEvent>

    suspend fun start()
    suspend fun stop()
    suspend fun connect(peerId: String)
    suspend fun acceptConnection(peerId: String)
    suspend fun rejectConnection(peerId: String)
    suspend fun disconnect(peerId: String)
    /** Completion means transport-level payload transfer, not peer-app persistence or final delivery. */
    suspend fun send(peerId: String, payload: ByteArray): SendResult
}
