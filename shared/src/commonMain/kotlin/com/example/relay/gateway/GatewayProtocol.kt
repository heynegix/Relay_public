package com.example.relay.gateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

const val GATEWAY_PROTOCOL_VERSION = 1
const val UNVERIFIED_GATEWAY_RECEIPT_TYPE = "GATEWAY_RECEIVED_UNVERIFIED"
const val VERIFIED_GATEWAY_RECEIPT_TYPE = "GATEWAY_RECEIVED"

@Serializable
data class GatewayMessage(
    val messageId: String,
    val messageType: String,
    val recordType: String,
    val priority: String,
    val status: String,
    val createdAt: Long,
    val expiresAt: Long,
    val lifetimeMs: Long,
    val accumulatedAgeMs: Long,
    val hopCount: Int,
    val hopLimit: Int,
    val originDeviceId: String,
    val payload: JsonElement,
    val receivedAt: Long,
    val reportSignature: JsonElement? = null,
)

@Serializable
data class GatewayReceipt(
    val receiptId: String,
    val messageId: String,
    val receiptType: String,
    val actorId: String,
    val recordedAt: Long,
)

@Serializable
data class GatewayRejection(val messageId: String, val reason: String)

@Serializable
data class SyncMessagesRequest(
    val protocolVersion: Int = GATEWAY_PROTOCOL_VERSION,
    val bridgeId: String,
    val bridgeName: String,
    val messages: List<GatewayMessage>,
)

@Serializable
data class SyncMessagesResponse(
    val protocolVersion: Int = GATEWAY_PROTOCOL_VERSION,
    val acceptedMessageIds: List<String> = emptyList(),
    val duplicateMessageIds: List<String> = emptyList(),
    val rejected: List<GatewayRejection> = emptyList(),
    val receipts: List<GatewayReceipt> = emptyList(),
)

@Serializable
data class GatewayLanAnnouncement(
    val service: String = "relay-pc-gateway",
    val discoveryVersion: Int = 1,
    val protocolVersion: Int = 1,
    val gatewayId: String = "",
    /** Shelter handled by the public rescue ingress; null for legacy announcements. */
    val shelterId: String? = null,
    /** Null means a legacy gateway that did not advertise rescue readiness. */
    val rescueIngressReady: Boolean? = null,
    val apiPort: Int = 8080,
    val apiScheme: String = "http",
    val anonymousIngressPath: String = "/api/public/sync/messages",
    val receiptTrust: String = "UNVERIFIED",
)

data class DiscoveredGateway(
    val host: String,
    val port: Int,
    val gatewayId: String,
    val scheme: String = "http",
    val shelterId: String? = null,
    val rescueIngressReady: Boolean? = null,
)
