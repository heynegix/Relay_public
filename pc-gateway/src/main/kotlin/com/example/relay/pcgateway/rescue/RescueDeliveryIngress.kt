package com.example.relay.pcgateway.rescue

import com.example.relay.pcgateway.GatewayJson
import com.example.relay.pcgateway.RouteAttempt
import com.example.relay.pcgateway.RouteResult
import com.example.relay.pcgateway.RouteType
import com.example.relay.rescue.EncryptedRescueEnvelope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString

/**
 * Transport-neutral entry point for courier deliveries.
 *
 * BLE, USB, and a future local HTTP adapter pass only opaque bytes here.  The byte
 * cap and serialization boundary live before decryption, while [RescueIntakeService]
 * owns all semantic validation and the durable-commit-before-receipt guarantee.
 */
class RescueDeliveryIngress(
    private val intakeService: RescueIntakeService,
    private val maxEnvelopeBytes: Int = DEFAULT_MAX_ENVELOPE_BYTES,
    private val routeType: RouteType? = null,
    private val routeAttemptSink: ((RouteAttempt) -> Unit)? = null,
) {
    init {
        require(maxEnvelopeBytes in 1..MAX_SUPPORTED_ENVELOPE_BYTES) { "invalid rescue envelope byte cap" }
    }

    fun ingest(
        envelopeBytes: ByteArray,
        carrierId: String,
        courierDeliveryId: String,
    ): RescueIngestResult {
        if (envelopeBytes.size > maxEnvelopeBytes) {
            return RescueIngestResult.Rejected(RescueRejectionCode.PAYLOAD_TOO_LARGE)
        }
        val envelope = try {
            GatewayJson.decodeFromString<EncryptedRescueEnvelope>(envelopeBytes.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            return RescueIngestResult.Rejected(RescueRejectionCode.MALFORMED_SERIALIZATION)
        } catch (_: IllegalArgumentException) {
            return RescueIngestResult.Rejected(RescueRejectionCode.MALFORMED_SERIALIZATION)
        }
        val result = intakeService.ingest(envelope, carrierId, courierDeliveryId)
        routeType?.let { type -> routeAttemptSink?.invoke(RouteAttempt(
            id = java.util.UUID.randomUUID().toString(), envelopeId = envelope.envelopeId, routeType = type,
            attemptedAtEpochMillis = System.currentTimeMillis(), completedAtEpochMillis = System.currentTimeMillis(),
            result = if (result is RescueIngestResult.Accepted || result is RescueIngestResult.Duplicate) RouteResult.RECEIPT_CONFIRMED else RouteResult.FAILED,
            safeErrorCode = (result as? RescueIngestResult.Rejected)?.code?.name,
            receiptId = when (result) { is RescueIngestResult.Accepted -> result.request.receipt.receipt.receiptId; is RescueIngestResult.Duplicate -> result.request.receipt.receipt.receiptId; else -> null },
        )) }
        return result
    }

    companion object {
        const val DEFAULT_MAX_ENVELOPE_BYTES = 32 * 1024
        const val MAX_SUPPORTED_ENVELOPE_BYTES = 64 * 1024
    }
}
