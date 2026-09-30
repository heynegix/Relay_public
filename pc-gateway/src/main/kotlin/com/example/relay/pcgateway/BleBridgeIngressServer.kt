package com.example.relay.pcgateway

import com.example.relay.pcgateway.rescue.RescueDeliveryIngress
import com.example.relay.pcgateway.rescue.RescueIngestResult
import com.example.relay.rescue.SignedShelterReceipt
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.LinkedHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable

private const val BLE_DELIVERY_PATH = "/api/internal/rescue/ble/deliver"
private const val BLE_INGRESS_HMAC_DOMAIN = "relay.ble-ingress.v1"
private const val BLE_TIMESTAMP_HEADER = "X-Relay-Ble-Timestamp"
private const val BLE_NONCE_HEADER = "X-Relay-Ble-Nonce"
private const val BLE_SIGNATURE_HEADER = "X-Relay-Ble-Signature"

/**
 * Contract for the Windows BLE sidecar. This listener is separate from the
 * LAN-facing gateway and is always bound to a loopback address.
 *
 * The sidecar signs exact UTF-8 JSON bytes with HMAC-SHA-256 over the domain,
 * method, path, timestamp, nonce, and SHA-256(body), joined with LF.
 */
@Serializable
data class BleBridgeDeliveryRequest(
    val carrierId: String,
    val courierDeliveryId: String,
    val envelopeBase64: String,
)

@Serializable
data class BleBridgeDeliveryResponse(
    val status: String,
    val rejectionCode: String? = null,
    val receipt: SignedShelterReceipt? = null,
)

/** A bounded replay cache. Restart safety comes from the durable delivery-id idempotency key. */
class BleBridgeReplayGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    private val allowedSkewMillis: Long = 60_000,
    private val maxEntries: Int = 2_048,
) {
    private val usedNonces = LinkedHashMap<String, Long>()

    fun accept(timestampEpochMillis: Long, nonce: String): Boolean = synchronized(usedNonces) {
        val now = clock()
        if (timestampEpochMillis !in (now - allowedSkewMillis)..(now + allowedSkewMillis)) return false
        if (!nonce.matches(Regex("[A-Za-z0-9._:-]{16,128}"))) return false
        val iterator = usedNonces.entries.iterator()
        while (iterator.hasNext()) if (iterator.next().value <= now) iterator.remove()
        if (usedNonces.containsKey(nonce)) return false
        usedNonces[nonce] = now + allowedSkewMillis
        while (usedNonces.size > maxEntries) {
            val oldest = usedNonces.entries.iterator()
            oldest.next()
            oldest.remove()
        }
        true
    }
}

fun Application.bleBridgeIngressModule(
    config: GatewayConfig,
    ingress: RescueDeliveryIngress,
    replayGuard: BleBridgeReplayGuard = BleBridgeReplayGuard(),
) {
    install(ContentNegotiation) { json(GatewayJson) }
    routing {
        post(BLE_DELIVERY_PATH) {
            // Defense in depth if a future change accidentally changes the listener binding.
            if (!call.request.local.remoteHost.isLoopbackPeer()) {
                return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "loopback_required"))
            }
            val contentLength = call.request.headers["Content-Length"]?.toIntOrNull()
            if (contentLength == null || contentLength !in 1..config.maxBleBridgeRequestBytes) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "invalid_content_length"))
            }
            val raw = call.receiveText()
            val rawBytes = raw.encodeToByteArray()
            if (rawBytes.size !in 1..config.maxBleBridgeRequestBytes) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "request_too_large"))
            }
            val timestamp = call.request.headers[BLE_TIMESTAMP_HEADER]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "missing_timestamp"))
            val nonce = call.request.headers[BLE_NONCE_HEADER]
                ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "missing_nonce"))
            val signature = call.request.headers[BLE_SIGNATURE_HEADER]
                ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "missing_signature"))
            if (!verifyBleBridgeSignature(config.bleBridgeSharedSecret, timestamp, nonce, rawBytes, signature)) {
                return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_signature"))
            }
            if (!replayGuard.accept(timestamp, nonce)) {
                return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "stale_or_replayed_request"))
            }
            val request = runCatching {
                GatewayJson.decodeFromString(BleBridgeDeliveryRequest.serializer(), raw)
            }.getOrElse {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "malformed_json"))
            }
            val envelope = runCatching { Base64.getDecoder().decode(request.envelopeBase64) }.getOrElse {
                return@post call.respond(HttpStatusCode.UnprocessableEntity, BleBridgeDeliveryResponse("rejected", "MALFORMED_SERIALIZATION"))
            }
            if (envelope.size > RescueDeliveryIngress.DEFAULT_MAX_ENVELOPE_BYTES) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge, BleBridgeDeliveryResponse("rejected", "PAYLOAD_TOO_LARGE"))
            }
            when (val result = ingress.ingest(envelope, request.carrierId, request.courierDeliveryId)) {
                is RescueIngestResult.Accepted -> call.respond(
                    HttpStatusCode.OK,
                    BleBridgeDeliveryResponse("accepted", receipt = result.request.receipt),
                )
                is RescueIngestResult.Duplicate -> call.respond(
                    HttpStatusCode.OK,
                    BleBridgeDeliveryResponse("duplicate", receipt = result.request.receipt),
                )
                is RescueIngestResult.Rejected -> call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    BleBridgeDeliveryResponse("rejected", result.code.name),
                )
                is RescueIngestResult.Quarantined -> call.respond(
                    HttpStatusCode.Conflict,
                    BleBridgeDeliveryResponse("quarantined"),
                )
            }
        }
    }
}

/** Public for the sidecar contract and integration tests; callers must not log secret-bearing inputs. */
fun bleBridgeSignatureInput(timestampEpochMillis: Long, nonce: String, body: ByteArray): ByteArray =
    listOf(
        BLE_INGRESS_HMAC_DOMAIN,
        "POST",
        BLE_DELIVERY_PATH,
        timestampEpochMillis.toString(),
        nonce,
        MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it.toInt() and 0xff) },
    ).joinToString("\n").encodeToByteArray()

private fun verifyBleBridgeSignature(secret: String, timestamp: Long, nonce: String, body: ByteArray, encoded: String): Boolean {
    val supplied = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return false
    val expected = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret.encodeToByteArray(), "HmacSHA256"))
        doFinal(bleBridgeSignatureInput(timestamp, nonce, body))
    }
    return MessageDigest.isEqual(expected, supplied)
}

private fun String.isLoopbackPeer(): Boolean = runCatching {
    InetAddress.getByName(this).isLoopbackAddress
}.getOrDefault(false)
