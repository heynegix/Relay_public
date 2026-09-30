package com.example.relay.gateway

import com.example.relay.BuildConfig
import com.example.relay.domain.DeliveryReceipt
import com.example.relay.domain.MessagePayload
import com.example.relay.domain.RelayMessage
import com.example.relay.gateway.protocol.GatewayMessage
import com.example.relay.gateway.protocol.GatewayReceipt
import com.example.relay.gateway.protocol.SyncMessagesRequest
import com.example.relay.gateway.protocol.SyncMessagesResponse
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.Json

data class GatewayPushResult(val response: SyncMessagesResponse)

interface GatewayBridgeClient {
    suspend fun requestPair(settings: GatewaySettings, code: String): Boolean
    suspend fun push(settings: GatewaySettings, token: String, messages: List<RelayMessage>): GatewayPushResult
    suspend fun pullReceipts(settings: GatewaySettings, token: String): List<DeliveryReceipt>
    suspend fun pushPublic(gateway: DiscoveredGateway, bridgeId: String, bridgeName: String, messages: List<RelayMessage>): GatewayPushResult =
        throw UnsupportedOperationException("public gateway ingress is not supported")
}

class HttpGatewayBridgeClient(
    private val json: Json = Json { ignoreUnknownKeys = false; classDiscriminator = "payloadType"; encodeDefaults = true },
) : GatewayBridgeClient {
    override suspend fun requestPair(settings: GatewaySettings, code: String): Boolean {
        val request = PairRequest(code, settings.bridgeId, settings.gatewayName)
        val response = execute(settings, "", "POST", "/api/pair/request", json.encodeToString(request))
        return json.decodeFromString<PairResponse>(response).paired
    }
    override suspend fun push(settings: GatewaySettings, token: String, messages: List<RelayMessage>): GatewayPushResult {
        val request = SyncMessagesRequest(bridgeId = settings.bridgeId, bridgeName = settings.gatewayName, messages = messages.map(::toGatewayMessage))
        val response = execute(settings, token, "POST", "/api/sync/messages", json.encodeToString(request))
        return GatewayPushResult(json.decodeFromString(response))
    }
    override suspend fun pullReceipts(settings: GatewaySettings, token: String): List<DeliveryReceipt> {
        val response = execute(settings, token, "GET", "/api/sync/receipts", null)
        return json.decodeFromString<com.example.relay.gateway.protocol.ReceiptResponse>(response).receipts.mapNotNull(::toReceipt)
    }
    override suspend fun pushPublic(
        gateway: DiscoveredGateway,
        bridgeId: String,
        bridgeName: String,
        messages: List<RelayMessage>,
    ): GatewayPushResult {
        val request = SyncMessagesRequest(
            bridgeId = bridgeId,
            bridgeName = bridgeName,
            messages = messages.map(::toGatewayMessage),
        )
        val response = executePublic(gateway, json.encodeToString(request))
        return GatewayPushResult(json.decodeFromString(response))
    }
    private suspend fun execute(settings: GatewaySettings, token: String, method: String, path: String, body: String?): String =
        withContext(Dispatchers.IO) {
            val bodyBytes = body?.toByteArray(Charsets.UTF_8)
            val connection = (URL("${gatewayScheme(settings.scheme)}://${settings.host}:${settings.port}$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method; connectTimeout = 5_000; readTimeout = 10_000; setRequestProperty("Authorization", "Bearer $token"); setRequestProperty("X-Bridge-Id", settings.bridgeId)
                if (bodyBytes != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setFixedLengthStreamingMode(bodyBytes.size)
                }
            }
            try {
                bodyBytes?.let { connection.outputStream.use { output -> output.write(it) } }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) throw GatewayHttpException(status, text.take(160))
                text
            } finally {
                connection.disconnect()
            }
    }
    private suspend fun executePublic(gateway: DiscoveredGateway, body: String): String = withContext(Dispatchers.IO) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val connection = (URL("${gatewayScheme(gateway.scheme)}://${gateway.host}:${gateway.port}/api/public/sync/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setFixedLengthStreamingMode(bodyBytes.size)
        }
        try {
            connection.outputStream.use { output -> output.write(bodyBytes) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw GatewayHttpException(status, text.take(160))
            text
        } finally {
            connection.disconnect()
        }
    }
    private fun toGatewayMessage(message: RelayMessage): GatewayMessage = GatewayMessage(
        messageId = message.messageId, messageType = message.messageType.name, recordType = message.recordType.name,
        priority = message.priority.name, status = "ACTIVE", createdAt = message.createdAt, expiresAt = message.expiresAt,
        lifetimeMs = message.lifetimeMs, accumulatedAgeMs = message.accumulatedAgeMs, hopCount = message.hopCount,
        hopLimit = message.maxHopCount, originDeviceId = message.originDeviceId, payload = json.encodeToJsonElement(message.payload), receivedAt = message.receivedAt,
        reportSignature = message.reportSignature?.let { json.encodeToJsonElement(com.example.relay.rescue.ReportSignature.serializer(), it) },
    )
    private fun toReceipt(receipt: com.example.relay.gateway.protocol.GatewayReceipt): DeliveryReceipt? = runCatching {
        val type = when (receipt.receiptType) {
            "GATEWAY_RECEIVED" -> com.example.relay.domain.ReceiptType.GATEWAY_RECEIVED
            "GATEWAY_RECEIVED_UNVERIFIED" -> com.example.relay.domain.ReceiptType.GATEWAY_RECEIVED_UNVERIFIED
            else -> return@runCatching null
        }
        DeliveryReceipt(receipt.receiptId, receipt.messageId, type, receipt.actorId, receipt.recordedAt)
    }.getOrNull()
        .let { it }
}

@kotlinx.serialization.Serializable data class PairRequest(val code: String, val bridgeId: String, val bridgeName: String)
@kotlinx.serialization.Serializable data class PairResponse(val paired: Boolean, val token: String? = null, val reason: String? = null)

class GatewayHttpException(val status: Int, detail: String) : IllegalStateException("gateway HTTP $status: $detail")

/** HTTP Gateway compatibility is intentionally compiled into debug/localDev only. */
class InsecureGatewayTransportException : IllegalStateException("cleartext Gateway transport is disabled in this build")

private fun gatewayScheme(raw: String): String = when (raw.trim().lowercase()) {
    "https" -> "https"
    "http" -> if (BuildConfig.ALLOW_HTTP_GATEWAY) "http" else throw InsecureGatewayTransportException()
    else -> throw IllegalArgumentException("unsupported Gateway scheme")
}
