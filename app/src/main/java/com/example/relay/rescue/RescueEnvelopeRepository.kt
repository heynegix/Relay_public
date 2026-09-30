package com.example.relay.rescue

import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescuePublicKey
import com.example.relay.rescue.RescueValidationResult
import com.example.relay.rescue.ShelterReceiptStatus
import com.example.relay.rescue.SignedShelterReceipt
import com.example.relay.rescue.forwardRescueEnvelope
import com.example.relay.rescue.validate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow

data class RescueRequestKey(val requestId: String, val requestVersion: Int)

enum class RescueSubmissionStatus {
    /**
     * Sender-only state: the SOS is encrypted in the device recovery store, but no trusted
     * shelter public key is available yet, so no transferable envelope exists.
     */
    PENDING_DESTINATION,
    PENDING,
    IN_TRANSIT,
    SHELTER_STORED,
    SHELTER_ACCEPTED,
    SHELTER_RESPONDING,
    SHELTER_COMPLETED,
    CANCELLED,
    SHELTER_REJECTED,
}

data class RescueEnvelopeState(
    val receivedAtEpochMillis: Long,
    val submissionStatus: RescueSubmissionStatus = RescueSubmissionStatus.PENDING,
    val submissionCount: Int = 0,
    val signedReceipt: SignedShelterReceipt? = null,
)

/** Persistence adapters store this encrypted record; no plaintext payload is part of the model. */
data class StoredRescueRecord(
    val envelope: EncryptedRescueEnvelope,
    val state: RescueEnvelopeState,
) {
    val key: RescueRequestKey
        get() = RescueRequestKey(envelope.requestId, envelope.requestVersion)
}

enum class RescueStoreRejection {
    INVALID_ENVELOPE,
    DUPLICATE,
    COLLISION,
    EXPIRED,
    MALFORMED_PACKET,
    SUPERSEDED_BY_NEWER_VERSION,
    /** A courier/peer must not replace a sender-owned active request outside its coordinator. */
    ACTIVE_SESSION_PROTECTED,
    INVALID_SENDER_AUTHORIZATION,
    EXCEEDS_BYTE_LIMIT,
}

sealed interface RescueStoreResult {
    data class Stored(
        val record: StoredRescueRecord,
        val pruned: List<RescueRequestKey> = emptyList(),
    ) : RescueStoreResult

    data class Rejected(val reason: RescueStoreRejection) : RescueStoreResult
}

enum class ReceiptApplicationResult {
    APPLIED,
    RECORD_NOT_FOUND,
    INVALID_SIGNATURE,
    RECEIPT_MISMATCH,
    ALREADY_APPLIED,
}

/**
 * Storage contract for a future Room adapter. Implementations must make store, export preparation,
 * and receipt application atomic.
 */
interface RescueEnvelopeRepository {
    /** Hot invalidation stream for courier UIs and background delivery owners. */
    val changes: Flow<Unit>
        get() = emptyFlow()
    fun store(envelope: EncryptedRescueEnvelope, receivedAtEpochMillis: Long): RescueStoreResult
    fun get(key: RescueRequestKey): StoredRescueRecord?
    fun all(): List<StoredRescueRecord>

    /** Returns an envelope with its hop advanced, or null when it is expired or hop-exhausted. */
    fun prepareForExport(key: RescueRequestKey, nowEpochMillis: Long): EncryptedRescueEnvelope?

    /** Commits routing state only after the transport confirms delivery. */
    fun recordSuccessfulExport(key: RescueRequestKey, exportedHopCount: Int): Boolean

    fun applyReceipt(
        key: RescueRequestKey,
        signedReceipt: SignedShelterReceipt,
        shelterSigningPublicKey: RescuePublicKey,
    ): ReceiptApplicationResult
}

class InMemoryRescueEnvelopeRepository(
    private val maxRecordCount: Int = 256,
    private val maxStoredBytes: Long = 8L * 1_048_576,
) : RescueEnvelopeRepository {
    private val lock = Any()
    private val records = linkedMapOf<RescueRequestKey, StoredRescueRecord>()
    private val _changes = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val changes: Flow<Unit> = _changes.asSharedFlow()

    init {
        require(maxRecordCount > 0)
        require(maxStoredBytes > 0)
    }

    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "LongMethod")
    override fun store(
        envelope: EncryptedRescueEnvelope,
        receivedAtEpochMillis: Long,
    ): RescueStoreResult = synchronized(lock) {
        if (envelope.validate() != RescueValidationResult.Valid ||
            !RescueCryptography.verifyEnvelopeFraming(envelope) ||
            receivedAtEpochMillis <= 0
        ) {
            return@synchronized RescueStoreResult.Rejected(RescueStoreRejection.INVALID_ENVELOPE)
        }
        if (envelope.hasSenderAuthorization() && !RescueCryptography.verifySenderAuthorization(envelope)) {
            return@synchronized RescueStoreResult.Rejected(RescueStoreRejection.INVALID_SENDER_AUTHORIZATION)
        }
        if (envelope.expiresAtEpochMillis <= receivedAtEpochMillis) {
            return@synchronized RescueStoreResult.Rejected(RescueStoreRejection.EXPIRED)
        }
        if (envelope.storageSizeBytes() > maxStoredBytes) {
            return@synchronized RescueStoreResult.Rejected(RescueStoreRejection.EXCEEDS_BYTE_LIMIT)
        }

        records.entries.removeAll { it.value.envelope.expiresAtEpochMillis <= receivedAtEpochMillis }
        val key = envelope.requestKey()
        val previousSigned = records.values.firstOrNull {
            it.envelope.requestId == key.requestId && it.envelope.hasSenderAuthorization()
        }
        if (previousSigned != null &&
            (!envelope.hasSenderAuthorization() ||
                previousSigned.envelope.senderKeyId != envelope.senderKeyId ||
                previousSigned.envelope.senderPublicKeyBase64 != envelope.senderPublicKeyBase64)
        ) {
            return@synchronized RescueStoreResult.Rejected(RescueStoreRejection.INVALID_SENDER_AUTHORIZATION)
        }
        records[key]?.let { existing ->
            if (existing.envelope.ciphertextSha256Hex != envelope.ciphertextSha256Hex ||
                !existing.envelope.sameImmutableEnvelopeAs(envelope)
            ) {
                return@synchronized RescueStoreResult.Rejected(RescueStoreRejection.COLLISION)
            }
            if (envelope.hopCount < existing.envelope.hopCount) {
                val improved = existing.copy(
                    // Only routing depth may change. Preserve the durable encrypted object and
                    // any already verified receipt exactly as stored.
                    envelope = existing.envelope.copy(hopCount = envelope.hopCount),
                    state = existing.state.copy(
                        receivedAtEpochMillis = minOf(existing.state.receivedAtEpochMillis, receivedAtEpochMillis),
                    ),
                )
                records[key] = improved
                _changes.tryEmit(Unit)
                return@synchronized RescueStoreResult.Stored(improved)
            }
            return@synchronized RescueStoreResult.Rejected(RescueStoreRejection.DUPLICATE)
        }
        if (records.keys.any { it.requestId == key.requestId && it.requestVersion > key.requestVersion }) {
            return@synchronized RescueStoreResult.Rejected(RescueStoreRejection.SUPERSEDED_BY_NEWER_VERSION)
        }

        val superseded = records.keys
            .filter { it.requestId == key.requestId && it.requestVersion < key.requestVersion }
            .sortedWith(requestKeyOrder)
        superseded.forEach(records::remove)

        val record = StoredRescueRecord(envelope, RescueEnvelopeState(receivedAtEpochMillis))
        records[key] = record
        val pruned = pruneForCapacity(protectedKey = key)
        _changes.tryEmit(Unit)
        RescueStoreResult.Stored(record, superseded + pruned)
    }

    override fun get(key: RescueRequestKey): StoredRescueRecord? = synchronized(lock) { records[key] }

    override fun all(): List<StoredRescueRecord> = synchronized(lock) {
        records.values.sortedWith(recordOrder)
    }

    override fun prepareForExport(
        key: RescueRequestKey,
        nowEpochMillis: Long,
    ): EncryptedRescueEnvelope? = synchronized(lock) {
        val current = records[key] ?: return@synchronized null
        if (nowEpochMillis >= current.envelope.expiresAtEpochMillis) return@synchronized null
        forwardRescueEnvelope(current.envelope)
    }

    override fun recordSuccessfulExport(key: RescueRequestKey, exportedHopCount: Int): Boolean = synchronized(lock) {
        val current = records[key] ?: return@synchronized false
        if (exportedHopCount != current.envelope.hopCount + 1 || exportedHopCount > current.envelope.maxHopCount) {
            return@synchronized false
        }
        records[key] = current.copy(
            // Keep the source copy at its route depth; the forwarded packet already contains the
            // incremented depth. This separates path length from fan-out count.
            envelope = current.envelope,
            state = current.state.copy(
                submissionStatus = maxOf(
                    current.state.submissionStatus,
                    RescueSubmissionStatus.IN_TRANSIT,
                    compareBy { it.rank() },
                ),
                submissionCount = current.state.submissionCount + 1,
            ),
        )
        _changes.tryEmit(Unit)
        true
    }

    override fun applyReceipt(
        key: RescueRequestKey,
        signedReceipt: SignedShelterReceipt,
        shelterSigningPublicKey: RescuePublicKey,
    ): ReceiptApplicationResult = synchronized(lock) {
        val current = records[key] ?: return@synchronized ReceiptApplicationResult.RECORD_NOT_FOUND
        if (!RescueCryptography.verifyReceipt(signedReceipt, shelterSigningPublicKey)) {
            return@synchronized ReceiptApplicationResult.INVALID_SIGNATURE
        }
        val receipt = signedReceipt.receipt
        val envelope = current.envelope
        val matches = receipt.envelopeId == envelope.envelopeId &&
            receipt.requestId == envelope.requestId &&
            receipt.requestVersion == envelope.requestVersion &&
            receipt.ciphertextSha256Hex == envelope.ciphertextSha256Hex &&
            receipt.shelterId == envelope.destinationShelterId
        if (!matches) return@synchronized ReceiptApplicationResult.RECEIPT_MISMATCH

        val status = receipt.status.toSubmissionStatus()
        if (status.rank() <= current.state.submissionStatus.rank()) {
            return@synchronized ReceiptApplicationResult.ALREADY_APPLIED
        }
        records[key] = current.copy(
            state = current.state.copy(submissionStatus = status, signedReceipt = signedReceipt),
        )
        _changes.tryEmit(Unit)
        ReceiptApplicationResult.APPLIED
    }

    private fun pruneForCapacity(protectedKey: RescueRequestKey): List<RescueRequestKey> {
        val pruned = mutableListOf<RescueRequestKey>()
        while (records.size > maxRecordCount || records.values.sumOf { it.envelope.storageSizeBytes() } > maxStoredBytes) {
            val victim = records.values
                .asSequence()
                .filter { it.key != protectedKey }
                .minWithOrNull(pruningOrder)
                ?: error("Incoming record was checked against capacity before insertion")
            records.remove(victim.key)
            pruned += victim.key
        }
        return pruned
    }

    private companion object {
        val requestKeyOrder = compareBy<RescueRequestKey>({ it.requestId }, { it.requestVersion })
        val recordOrder = compareBy<StoredRescueRecord>(
            { it.envelope.requestId },
            { it.envelope.requestVersion },
            { it.envelope.envelopeId },
        )
        val pruningOrder = compareBy<StoredRescueRecord>(
            { it.envelope.expiresAtEpochMillis },
            { it.state.receivedAtEpochMillis },
            { it.envelope.createdAtEpochMillis },
            { it.envelope.requestId },
            { it.envelope.requestVersion },
            { it.envelope.envelopeId },
        )
    }
}

private fun EncryptedRescueEnvelope.requestKey() = RescueRequestKey(requestId, requestVersion)

/** Stable accounting used by every persistence adapter for deterministic byte-limit behavior. */
fun EncryptedRescueEnvelope.storageSizeBytes(): Long = ciphertextSizeBytes.toLong() +
    envelopeId.utf8Size() + requestId.utf8Size() + senderDeviceId.utf8Size() +
    destinationShelterId.utf8Size() + recipientKeyId.utf8Size() +
    wrappedContentKeyBase64.utf8Size() + nonceBase64.utf8Size() + ciphertextBase64.utf8Size() +
    ciphertextSha256Hex.utf8Size() + 96L

private fun String.utf8Size(): Long = encodeToByteArray().size.toLong()

/** Returns true when two envelopes represent the same immutable encrypted object. */
@Suppress("CyclomaticComplexMethod")
internal fun EncryptedRescueEnvelope.sameImmutableEnvelopeAs(other: EncryptedRescueEnvelope): Boolean =
    protocolVersion == other.protocolVersion &&
        envelopeId == other.envelopeId &&
        requestId == other.requestId &&
        requestVersion == other.requestVersion &&
        senderDeviceId == other.senderDeviceId &&
        destinationShelterId == other.destinationShelterId &&
        routingUrgency == other.routingUrgency &&
        recipientKeyId == other.recipientKeyId &&
        createdAtEpochMillis == other.createdAtEpochMillis &&
        expiresAtEpochMillis == other.expiresAtEpochMillis &&
        maxHopCount == other.maxHopCount &&
        keyWrapAlgorithm == other.keyWrapAlgorithm &&
        contentEncryptionAlgorithm == other.contentEncryptionAlgorithm &&
        ciphertextSizeBytes == other.ciphertextSizeBytes &&
        wrappedContentKeyBase64 == other.wrappedContentKeyBase64 &&
        nonceBase64 == other.nonceBase64 &&
        ciphertextBase64 == other.ciphertextBase64 &&
        ciphertextSha256Hex == other.ciphertextSha256Hex &&
        senderKeyId == other.senderKeyId &&
        senderPublicKeyBase64 == other.senderPublicKeyBase64

internal fun ShelterReceiptStatus.toSubmissionStatus(): RescueSubmissionStatus = when (this) {
    ShelterReceiptStatus.STORED -> RescueSubmissionStatus.SHELTER_STORED
    ShelterReceiptStatus.ACCEPTED -> RescueSubmissionStatus.SHELTER_ACCEPTED
    ShelterReceiptStatus.RESPONDING -> RescueSubmissionStatus.SHELTER_RESPONDING
    ShelterReceiptStatus.COMPLETED -> RescueSubmissionStatus.SHELTER_COMPLETED
    ShelterReceiptStatus.CANCELLED -> RescueSubmissionStatus.CANCELLED
    ShelterReceiptStatus.REJECTED -> RescueSubmissionStatus.SHELTER_REJECTED
}

internal fun RescueSubmissionStatus.rank(): Int = when (this) {
    RescueSubmissionStatus.PENDING_DESTINATION -> -1
    RescueSubmissionStatus.PENDING -> 0
    RescueSubmissionStatus.IN_TRANSIT -> 1
    RescueSubmissionStatus.SHELTER_STORED -> 2
    RescueSubmissionStatus.SHELTER_ACCEPTED -> 3
    RescueSubmissionStatus.SHELTER_RESPONDING -> 4
    RescueSubmissionStatus.SHELTER_COMPLETED,
    RescueSubmissionStatus.CANCELLED,
    RescueSubmissionStatus.SHELTER_REJECTED,
    -> 5
}
