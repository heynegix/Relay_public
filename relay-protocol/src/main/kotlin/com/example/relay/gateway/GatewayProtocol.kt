package com.example.relay.gateway.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Existing production protocol. Keep this default until a trusted key is provisioned. */
const val GATEWAY_PROTOCOL_V1 = 1
const val GATEWAY_PROTOCOL_V2 = 2

/** Legacy source-compatible name. New callers must opt in to v2 explicitly. */
const val GATEWAY_PROTOCOL_VERSION = GATEWAY_PROTOCOL_V1

val SUPPORTED_GATEWAY_PROTOCOL_VERSIONS: Set<Int> = setOf(GATEWAY_PROTOCOL_V1, GATEWAY_PROTOCOL_V2)

fun isSupportedGatewayProtocolVersion(version: Int): Boolean = version in SUPPORTED_GATEWAY_PROTOCOL_VERSIONS

@Serializable
data class GatewayIntegrity(
    val algorithm: String,
    val keyId: String,
    /** Upper-case hexadecimal. Hex avoids an Android API-level-specific Base64 dependency. */
    val signature: String,
)
const val VERIFIED_GATEWAY_RECEIPT_TYPE = "GATEWAY_RECEIVED"
/** Acknowledges local storage from an unregistered source; Android must not present it as official Gateway arrival. */
const val UNVERIFIED_GATEWAY_RECEIPT_TYPE = "GATEWAY_RECEIVED_UNVERIFIED"

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
    /** Absent on protocol v1. Protocol v2 verification policy requires this field. */
    val integrity: GatewayIntegrity? = null,
    /** Serialized immutable REPORT signature, if the payload is a signed REPORT. */
    val reportSignature: JsonElement? = null,
)

@Serializable
data class GatewayReceipt(
    val receiptId: String,
    val messageId: String,
    val receiptType: String,
    val actorId: String,
    val recordedAt: Long,
    /** Optional for v1 compatibility; a future signed-receipt policy can require it in v2. */
    val integrity: GatewayIntegrity? = null,
)

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
data class GatewayRejection(val messageId: String, val reason: String)

@Serializable
data class ReceiptResponse(
    val protocolVersion: Int = GATEWAY_PROTOCOL_VERSION,
    val receipts: List<GatewayReceipt> = emptyList(),
)
