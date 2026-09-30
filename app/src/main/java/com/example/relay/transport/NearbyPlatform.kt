package com.example.relay.transport

import kotlinx.coroutines.flow.Flow

sealed interface NearbyPlatformEvent {
    data class EndpointFound(val endpointId: String, val endpointName: String) : NearbyPlatformEvent
    data class EndpointLost(val endpointId: String) : NearbyPlatformEvent
    data class ConnectionInitiated(
        val endpointId: String,
        val endpointName: String,
        val authenticationDigits: String?,
        val incoming: Boolean,
    ) : NearbyPlatformEvent
    data class ConnectionSucceeded(val endpointId: String) : NearbyPlatformEvent
    data class ConnectionFailed(val endpointId: String, val reason: String) : NearbyPlatformEvent
    data class Disconnected(val endpointId: String) : NearbyPlatformEvent
    data class BytesReceived(val endpointId: String, val bytes: ByteArray) : NearbyPlatformEvent
    data class PayloadTransferSucceeded(val endpointId: String, val payloadId: Long) : NearbyPlatformEvent
    data class PayloadTransferFailed(val endpointId: String, val payloadId: Long, val reason: String) : NearbyPlatformEvent
}

interface NearbyPlatform {
    val events: Flow<NearbyPlatformEvent>
    suspend fun startAdvertising(localEndpointName: String)
    suspend fun startDiscovery()
    suspend fun requestConnection(localEndpointName: String, endpointId: String)
    suspend fun acceptConnection(endpointId: String)
    suspend fun rejectConnection(endpointId: String)
    fun disconnect(endpointId: String)
    suspend fun sendBytes(endpointId: String, bytes: ByteArray, onPayloadCreated: (Long) -> Unit): Long
    fun stopAll()
}

fun interface NearbyPermissionGate {
    fun canUseNearby(): Boolean
}

object AllowedNearbyPermissionGate : NearbyPermissionGate {
    override fun canUseNearby(): Boolean = true
}

object DeniedNearbyPermissionGate : NearbyPermissionGate {
    override fun canUseNearby(): Boolean = false
}
