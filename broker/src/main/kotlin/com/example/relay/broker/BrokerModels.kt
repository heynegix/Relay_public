package com.example.relay.broker

import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.SignedShelterReceipt
import kotlinx.serialization.Serializable

/** Upload request from an Android device. The envelope is already encrypted; Broker never decrypts. */
@Serializable
data class BrokerUploadRequest(
    val envelope: EncryptedRescueEnvelope,
    /** Per-device unique key identifier (UUID generated on first launch). */
    val deviceKeyId: String,
    /** ECDSA-P256 signature over envelope authenticatedHeaderBytes + ciphertextSha256Hex. */
    val uploadSignatureBase64: String,
)

/** Broker acknowledgement that the ciphertext is stored. This is NOT a shelter receipt. */
@Serializable
data class BrokerUploadResponse(
    val brokerReceiptId: String,
    val envelopeId: String,
    val status: String = "BROKER_STORED",
    val storedAtEpochMillis: Long,
)

/** Device registration request. */
@Serializable
data class BrokerDeviceRegisterRequest(
    val deviceKeyId: String,
    val publicKeyBase64: String,
    /** ECDSA-P256 proof that the caller holds the private key being registered. */
    val registrationSignatureBase64: String,
)

/** Device registration response with unguessable capability token for receipt polling. */
@Serializable
data class BrokerDeviceRegisterResponse(
    val deviceKeyId: String,
    val capabilityToken: String,
)

/** Batch of pending envelopes for a Gateway to pull. Composite cursor pagination. */
@Serializable
data class BrokerEnvelopeBatch(
    val envelopes: List<EncryptedRescueEnvelope>,
    /** Opaque composite cursor (stored_at:envelope_id). Null when no results. */
    val cursor: String? = null,
)

/** A signed shelter receipt uploaded by the PC Gateway for relay back to the device. */
@Serializable
data class BrokerReceiptUpload(
    val receipt: SignedShelterReceipt,
    val gatewayId: String,
)

@Serializable
data class BrokerReceiptUploadResponse(
    val accepted: Boolean,
    val reason: String? = null,
)

/** Receipts available for a device to poll. Uses Broker monotonic seq cursor. */
@Serializable
data class BrokerReceiptBatch(
    val receipts: List<SignedShelterReceipt>,
    /** Monotonic cursor: pass as sinceSeq on next poll. */
    val cursor: Long = 0,
)

@Serializable
data class BrokerHealthResponse(
    val status: String = "ok",
    val pendingEnvelopes: Int,
    val pendingReceipts: Int,
    /** Configuration posture only; no token, credential, personal, or envelope data is exposed. */
    val profile: String = "production",
    val version: String = "1.2.0",
)

/** Internal ledger status for Broker-side tracking. Separate from shelter receipt statuses. */
enum class BrokerLedgerStatus {
    BROKER_STORED,
    GATEWAY_PULLED,
}
