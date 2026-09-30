package com.example.relay.pcgateway.rescue

import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.RescuePayload
import com.example.relay.rescue.SignedShelterReceipt
import kotlinx.serialization.Serializable

@Serializable
enum class RescueResponseStatus {
    UNCONFIRMED,
    CONFIRMED,
    PREPARING,
    RESCUE_REQUESTED,
    RESPONDING,
    COMPLETED,
    UNABLE,
    DUPLICATE,
}

data class RescueRequestKey(val requestId: String, val requestVersion: Int)

data class StoredRescueRequest(
    val key: RescueRequestKey,
    val envelopeHash: String,
    val envelope: EncryptedRescueEnvelope,
    val payload: RescuePayload,
    val receivedAtEpochMillis: Long,
    val responseStatus: RescueResponseStatus,
    val carrierIds: Set<String>,
    val receipt: SignedShelterReceipt,
    /**
     * Idempotency keys supplied by the courier transport. A retry must reuse this value,
     * so receiving the same frame again never inflates the delivery count.
     */
    val deliveryIds: Set<String> = emptySet(),
    /** First staff node that confirmed the request. Shared storage makes this a first-writer claim. */
    val assignedNodeId: String? = null,
    val statusUpdatedAtEpochMillis: Long = receivedAtEpochMillis,
    val terminalAtEpochMillis: Long? = null,
) {
    val uniqueCarrierCount: Int get() = carrierIds.size
    val uniqueDeliveryCount: Int get() = deliveryIds.size

    override fun toString(): String =
        "StoredRescueRequest(key=$key, envelopeHash=$envelopeHash, receivedAtEpochMillis=$receivedAtEpochMillis, " +
            "responseStatus=$responseStatus, uniqueCarrierCount=$uniqueCarrierCount, " +
            "uniqueDeliveryCount=$uniqueDeliveryCount)"
}

data class RescueRequestSummary(
    val requestId: String,
    val requestVersion: Int,
    val urgency: com.example.relay.rescue.RescueUrgency,
    val personCount: Int,
    val receivedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val responseStatus: RescueResponseStatus,
    val uniqueCarrierCount: Int,
    val isLatestVersion: Boolean,
    val assignedNodeId: String? = null,
    val statusUpdatedAtEpochMillis: Long,
    val isLifeThreatening: Boolean,
    val isCancelled: Boolean,
)

data class QuarantinedRescueEnvelope(
    val key: RescueRequestKey,
    val envelopeId: String,
    val claimedEnvelopeHash: String,
    val existingEnvelopeHash: String,
    val carrierId: String,
    val quarantinedAtEpochMillis: Long,
    val reason: String = "request_version_hash_collision",
)

sealed interface RescueIngestResult {
    data class Accepted(val request: StoredRescueRequest) : RescueIngestResult
    data class Duplicate(
        val request: StoredRescueRequest,
        val carrierWasNew: Boolean,
        val deliveryWasNew: Boolean = false,
    ) : RescueIngestResult
    data class Rejected(val code: RescueRejectionCode) : RescueIngestResult
    data class Quarantined(val collision: QuarantinedRescueEnvelope) : RescueIngestResult
}

enum class RescueRejectionCode {
    INVALID_CARRIER,
    INVALID_DELIVERY_ID,
    PAYLOAD_TOO_LARGE,
    MALFORMED_SERIALIZATION,
    INVALID_ENVELOPE,
    EXPIRED,
    WRONG_SHELTER,
    WRONG_RECIPIENT_KEY,
    INVALID_SENDER_AUTHORIZATION,
    CORRUPT_OR_UNDECRYPTABLE,
}

sealed interface RescueStatusUpdateResult {
    data class Updated(val request: StoredRescueRequest) : RescueStatusUpdateResult
    data object NotFound : RescueStatusUpdateResult
    data class InvalidTransition(
        val current: RescueResponseStatus,
        val requested: RescueResponseStatus,
    ) : RescueStatusUpdateResult
    data class AssignedElsewhere(val assignedNodeId: String) : RescueStatusUpdateResult
}
