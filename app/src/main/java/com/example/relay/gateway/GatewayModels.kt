package com.example.relay.gateway

data class GatewaySettings(
    val host: String = "",
    val port: Int = 8080,
    val gatewayName: String = "",
    val bridgeId: String = "",
    val enabled: Boolean = false,
    /** Zero-op default: bridge tries public LAN discovery without UI configuration. */
    val automaticSync: Boolean = true,
    val lastConnectedAt: Long? = null,
    val lastSyncResult: String? = null,
    /** Safe, operator-visible LAN diagnostics; never contains message contents or ciphertext. */
    val lastDiscoveredGatewayIp: String? = null,
    val lastDiscoveryResult: String? = null,
    val lastDeliveryResult: String? = null,
    /** HTTPS is required in release/pilotRelease; HTTP remains a debug/localDev compatibility path. */
    val scheme: String = "https",
)

sealed interface GatewaySyncResult {
    data class Completed(val sent: Int, val receipts: Int) : GatewaySyncResult
    data class Deferred(val reason: String) : GatewaySyncResult
    data class Failed(val reason: String, val retryable: Boolean = true) : GatewaySyncResult
}
