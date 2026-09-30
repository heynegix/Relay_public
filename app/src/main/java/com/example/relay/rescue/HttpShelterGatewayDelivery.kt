package com.example.relay.rescue

import com.example.relay.BuildConfig
import com.example.relay.gateway.DiscoveredGateway
import com.example.relay.gateway.GatewayDiscovery
import com.example.relay.gateway.UdpGatewayDiscovery
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Sends only the already encrypted rescue envelope to a discovered local PC Gateway. */
class HttpShelterGatewayDelivery(
    private val discovery: GatewayDiscovery = UdpGatewayDiscovery(),
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
) {
    suspend fun deliver(
        envelope: EncryptedRescueEnvelope,
        carrierId: String,
        courierDeliveryId: String,
    ): GatewayDeliveryResult = withContext(Dispatchers.IO) {
        val gateway = discovery.discoverForShelter(envelope.destinationShelterId)
            ?: return@withContext GatewayDeliveryResult.GatewayNotFound
        val scheme = gateway.scheme.trim().lowercase()
        if (scheme !in setOf("http", "https")) return@withContext GatewayDeliveryResult.InsecureTransportBlocked
        if (scheme == "http" && !BuildConfig.ALLOW_HTTP_GATEWAY) {
            return@withContext GatewayDeliveryResult.InsecureTransportBlocked
        }
        val request = HttpRescueDeliveryRequest(envelope, carrierId, courierDeliveryId)
        runCatching {
            val connection = (URL("$scheme://${gateway.host}:${gateway.port}/api/public/rescue/deliver")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                val requestBody = json.encodeToString(request).encodeToByteArray()
                connection.setFixedLengthStreamingMode(requestBody.size)
                connection.outputStream.use { it.write(requestBody) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status == 404) return@runCatching GatewayDeliveryResult.OldGateway
                if (status == 429) return@runCatching GatewayDeliveryResult.RateLimited
                if (status == 503) return@runCatching GatewayDeliveryResult.GatewayNotReady
                if (status in 400..499) {
                    val reason = runCatching { json.decodeFromString<HttpRescueDeliveryResponse>(body).reason }.getOrNull()
                    return@runCatching when (reason?.lowercase()) {
                        "wrong_shelter", "shelter_mismatch" -> GatewayDeliveryResult.WrongShelter
                        "wrong_recipient_key", "recipient_key_mismatch" -> GatewayDeliveryResult.WrongRecipientKey
                        else -> GatewayDeliveryResult.Rejected(status, reason ?: "client_rejected")
                    }
                }
                if (status !in 200..299) return@runCatching GatewayDeliveryResult.NetworkFailure(status)
                val response = runCatching { json.decodeFromString<HttpRescueDeliveryResponse>(body) }
                    .getOrElse { return@runCatching GatewayDeliveryResult.InvalidReceipt }
                val receipt = response.receipt ?: return@runCatching GatewayDeliveryResult.InvalidReceipt
                GatewayDeliveryResult.Accepted(receipt)
            } finally {
                connection.disconnect()
            }
        }.getOrElse { GatewayDeliveryResult.NetworkFailure(null) }
    }
}

sealed interface GatewayDeliveryResult {
    data class Accepted(val receipt: SignedShelterReceipt) : GatewayDeliveryResult
    data object GatewayNotFound : GatewayDeliveryResult
    data class NetworkFailure(val httpStatus: Int?) : GatewayDeliveryResult
    data object OldGateway : GatewayDeliveryResult
    data object WrongShelter : GatewayDeliveryResult
    data object WrongRecipientKey : GatewayDeliveryResult
    data class Rejected(val httpStatus: Int, val reason: String) : GatewayDeliveryResult
    data object RateLimited : GatewayDeliveryResult
    data object GatewayNotReady : GatewayDeliveryResult
    /** release/pilotRelease must never silently fall back to a cleartext LAN Gateway. */
    data object InsecureTransportBlocked : GatewayDeliveryResult
    data object InvalidReceipt : GatewayDeliveryResult
}

@Serializable
private data class HttpRescueDeliveryRequest(
    val envelope: EncryptedRescueEnvelope,
    val carrierId: String,
    val courierDeliveryId: String,
)

@Serializable
private data class HttpRescueDeliveryResponse(
    val outcome: String,
    val reason: String? = null,
    val receipt: SignedShelterReceipt? = null,
)
