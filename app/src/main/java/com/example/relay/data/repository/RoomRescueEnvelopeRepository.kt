package com.example.relay.data.repository

import com.example.relay.data.local.RelayDatabase
import com.example.relay.data.local.RescueEntity
import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.ReceiptApplicationResult
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueEnvelopeRepository
import com.example.relay.rescue.RescueEnvelopeState
import com.example.relay.rescue.RescuePublicKey
import com.example.relay.rescue.RescueRequestKey
import com.example.relay.rescue.RescueStoreRejection
import com.example.relay.rescue.RescueStoreResult
import com.example.relay.rescue.RescueSubmissionStatus
import com.example.relay.rescue.RescueValidationResult
import com.example.relay.rescue.SignedShelterReceipt
import com.example.relay.rescue.StoredRescueRecord
import com.example.relay.rescue.forwardRescueEnvelope
import com.example.relay.rescue.hasSenderAuthorization
import com.example.relay.rescue.sameImmutableEnvelopeAs
import com.example.relay.rescue.storageSizeBytes
import com.example.relay.rescue.rank
import com.example.relay.rescue.toSubmissionStatus
import com.example.relay.rescue.validate
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed encrypted rescue store. Its API is synchronous to match [RescueEnvelopeRepository],
 * so every method must be called off Android's main thread.
 */
class RoomRescueEnvelopeRepository(
    private val database: RelayDatabase,
    private val maxRecordCount: Int = 256,
    private val maxStoredBytes: Long = 8L * 1_048_576,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
) : RescueEnvelopeRepository {
    private val dao = database.rescueDao()
    override val changes: Flow<Unit> = dao.observeAll().map { Unit }

    init {
        require(maxRecordCount > 0)
        require(maxStoredBytes > 0)
    }

    override fun store(
        envelope: EncryptedRescueEnvelope,
        receivedAtEpochMillis: Long,
    ): RescueStoreResult {
        val result = database.runInTransaction<RescueStoreResult> {
            storeInTransaction(envelope, receivedAtEpochMillis)
        }
        return result
    }

    /**
     * Session updates call this while holding the same Room transaction that updates the recovery
     * record. `allowPruning=false` rejects rather than evicting another active sender's envelope.
     */
    @Suppress("ComplexCondition", "LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    internal fun storeInTransaction(
        envelope: EncryptedRescueEnvelope,
        receivedAtEpochMillis: Long,
        allowPruning: Boolean = true,
    ): RescueStoreResult {
        if (envelope.validate() != RescueValidationResult.Valid ||
            !RescueCryptography.verifyEnvelopeFraming(envelope) ||
            receivedAtEpochMillis <= 0
        ) {
            return RescueStoreResult.Rejected(RescueStoreRejection.INVALID_ENVELOPE)
        }
        if (envelope.hasSenderAuthorization() && !RescueCryptography.verifySenderAuthorization(envelope)) {
            return RescueStoreResult.Rejected(RescueStoreRejection.INVALID_SENDER_AUTHORIZATION)
        }
        if (envelope.expiresAtEpochMillis <= receivedAtEpochMillis) {
            return RescueStoreResult.Rejected(RescueStoreRejection.EXPIRED)
        }
        val sizeBytes = envelope.storageSizeBytes()
        if (sizeBytes > maxStoredBytes) {
            return RescueStoreResult.Rejected(RescueStoreRejection.EXCEEDS_BYTE_LIMIT)
        }

        dao.deleteExpired(receivedAtEpochMillis)
        val key = RescueRequestKey(envelope.requestId, envelope.requestVersion)
        val previousSigned = dao.all().asSequence().mapNotNull { it.toRecordOrNull() }.firstOrNull {
            it.envelope.requestId == key.requestId && it.envelope.hasSenderAuthorization()
        }
        if (previousSigned != null &&
            (!envelope.hasSenderAuthorization() ||
                previousSigned.envelope.senderKeyId != envelope.senderKeyId ||
                previousSigned.envelope.senderPublicKeyBase64 != envelope.senderPublicKeyBase64)
        ) {
            return RescueStoreResult.Rejected(RescueStoreRejection.INVALID_SENDER_AUTHORIZATION)
        }
        dao.find(key.requestId, key.requestVersion)?.let { existing ->
            val current = existing.toRecordOrNull()
                ?: return RescueStoreResult.Rejected(RescueStoreRejection.INVALID_ENVELOPE)
            if (existing.ciphertextSha256Hex != envelope.ciphertextSha256Hex ||
                !current.envelope.sameImmutableEnvelopeAs(envelope)
            ) {
                return RescueStoreResult.Rejected(RescueStoreRejection.COLLISION)
            }
            if (envelope.hopCount < current.envelope.hopCount) {
                val improved = current.copy(
                    // Only routing depth may change. Preserve the durable encrypted object and
                    // any already verified receipt exactly as stored.
                    envelope = current.envelope.copy(hopCount = envelope.hopCount),
                    state = current.state.copy(
                        receivedAtEpochMillis = minOf(current.state.receivedAtEpochMillis, receivedAtEpochMillis),
                    ),
                )
                dao.update(improved.toEntity(current.envelope.storageSizeBytes()))
                return RescueStoreResult.Stored(improved)
            }
            return RescueStoreResult.Rejected(RescueStoreRejection.DUPLICATE)
        }
        if (dao.hasNewerVersion(key.requestId, key.requestVersion)) {
            return RescueStoreResult.Rejected(
                RescueStoreRejection.SUPERSEDED_BY_NEWER_VERSION,
            )
        }

        val activeSessionKeys = database.activeRescueSessionDao().activeEnvelopeKeys(receivedAtEpochMillis)
            .mapTo(mutableSetOf()) { RescueRequestKey(it.requestId, it.latestVersion) }
        if (allowPruning && activeSessionKeys.any { it.requestId == key.requestId && it.requestVersion < key.requestVersion }) {
            return RescueStoreResult.Rejected(RescueStoreRejection.ACTIVE_SESSION_PROTECTED)
        }

        val olderEntities = dao.olderVersions(key.requestId, key.requestVersion)
        val superseded = olderEntities.map { it.key() }
        val projectedCount = dao.count() - olderEntities.size + 1
        val projectedBytes = dao.totalStorageSizeBytes() - olderEntities.sumOf { it.storageSizeBytes } + sizeBytes
        val protectedKeys = activeSessionKeys.also { it += key }
        if (!canFitAfterPruning(
                projectedCount = projectedCount,
                projectedBytes = projectedBytes,
                olderKeys = superseded.toSet(),
                protectedKeys = protectedKeys,
                allowPruning = allowPruning,
            )
        ) {
            // Check capacity before deleting a prior version or inserting the new one.  A rejection
            // therefore leaves a sender session and its envelope unchanged in the enclosing Room
            // transaction.
            return RescueStoreResult.Rejected(RescueStoreRejection.EXCEEDS_BYTE_LIMIT)
        }
        dao.deleteOlderVersions(key.requestId, key.requestVersion)
        val record = StoredRescueRecord(envelope, RescueEnvelopeState(receivedAtEpochMillis))
        dao.insert(record.toEntity(sizeBytes))
        val pruned = if (allowPruning) {
            pruneForCapacity(protectedKeys)
        } else {
            emptyList()
        }
        return RescueStoreResult.Stored(record, superseded + pruned)
    }

    override fun get(key: RescueRequestKey): StoredRescueRecord? =
        dao.find(key.requestId, key.requestVersion)?.toRecordOrNull()

    override fun all(): List<StoredRescueRecord> = dao.all().mapNotNull { it.toRecordOrNull() }

    override fun prepareForExport(
        key: RescueRequestKey,
        nowEpochMillis: Long,
    ): EncryptedRescueEnvelope? = database.runInTransaction<EncryptedRescueEnvelope?> {
        val current = dao.find(key.requestId, key.requestVersion)?.toRecordOrNull()
            ?: return@runInTransaction null
        if (nowEpochMillis >= current.envelope.expiresAtEpochMillis) return@runInTransaction null
        forwardRescueEnvelope(current.envelope)
    }

    override fun recordSuccessfulExport(key: RescueRequestKey, exportedHopCount: Int): Boolean {
        val changed = database.runInTransaction<Boolean> {
            val entity = dao.find(key.requestId, key.requestVersion) ?: return@runInTransaction false
            val current = entity.toRecordOrNull() ?: return@runInTransaction false
            if (exportedHopCount != current.envelope.hopCount + 1 ||
                exportedHopCount > current.envelope.maxHopCount
            ) {
                return@runInTransaction false
            }
            val updated = current.copy(
                // hopCount describes the depth of this stored copy, not how many peers this
                // device has fanned it out to. Each exported copy carries hopCount + 1 while the
                // source remains reusable for another independent route.
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
            dao.update(updated.toEntity(updated.envelope.storageSizeBytes())) == 1
        }
        return changed
    }

    override fun applyReceipt(
        key: RescueRequestKey,
        signedReceipt: SignedShelterReceipt,
        shelterSigningPublicKey: RescuePublicKey,
    ): ReceiptApplicationResult {
        val result = database.runInTransaction<ReceiptApplicationResult> {
            applyReceiptInTransaction(key, signedReceipt, shelterSigningPublicKey)
        }
        return result
    }

    /** Must be called from the encompassing Room transaction when a session mirrors receipt state. */
    internal fun applyReceiptInTransaction(
        key: RescueRequestKey,
        signedReceipt: SignedShelterReceipt,
        shelterSigningPublicKey: RescuePublicKey,
    ): ReceiptApplicationResult {
        val entity = dao.find(key.requestId, key.requestVersion)
            ?: return ReceiptApplicationResult.RECORD_NOT_FOUND
        val current = entity.toRecordOrNull()
            ?: return ReceiptApplicationResult.RECORD_NOT_FOUND
        if (!RescueCryptography.verifyReceipt(signedReceipt, shelterSigningPublicKey)) {
            return ReceiptApplicationResult.INVALID_SIGNATURE
        }
        val receipt = signedReceipt.receipt
        val envelope = current.envelope
        if (receipt.envelopeId != envelope.envelopeId ||
            receipt.requestId != envelope.requestId ||
            receipt.requestVersion != envelope.requestVersion ||
            receipt.ciphertextSha256Hex != envelope.ciphertextSha256Hex ||
            receipt.shelterId != envelope.destinationShelterId
        ) {
            return ReceiptApplicationResult.RECEIPT_MISMATCH
        }
        val status = receipt.status.toSubmissionStatus()
        if (status.rank() <= current.state.submissionStatus.rank()) {
            return ReceiptApplicationResult.ALREADY_APPLIED
        }
        val updated = current.copy(
            state = current.state.copy(submissionStatus = status, signedReceipt = signedReceipt),
        )
        return if (dao.update(updated.toEntity(updated.envelope.storageSizeBytes())) == 1) {
            ReceiptApplicationResult.APPLIED
        } else {
            ReceiptApplicationResult.RECORD_NOT_FOUND
        }
    }

    /** Performs the capacity decision before this transaction mutates an older version. */
    private fun canFitAfterPruning(
        projectedCount: Int,
        projectedBytes: Long,
        olderKeys: Set<RescueRequestKey>,
        protectedKeys: Set<RescueRequestKey>,
        allowPruning: Boolean,
    ): Boolean {
        if (projectedCount <= maxRecordCount && projectedBytes <= maxStoredBytes) return true
        if (!allowPruning) return false
        var remainingCount = projectedCount
        var remainingBytes = projectedBytes
        for (candidate in dao.pruningCandidates()) {
            if (candidate.key() in olderKeys || candidate.key() in protectedKeys) continue
            if (remainingCount <= maxRecordCount && remainingBytes <= maxStoredBytes) return true
            remainingCount -= 1
            remainingBytes -= candidate.storageSizeBytes
        }
        return remainingCount <= maxRecordCount && remainingBytes <= maxStoredBytes
    }

    private fun pruneForCapacity(protectedKeys: Set<RescueRequestKey>): List<RescueRequestKey> {
        val pruned = mutableListOf<RescueRequestKey>()
        while (dao.count() > maxRecordCount || dao.totalStorageSizeBytes() > maxStoredBytes) {
            val victim = dao.pruningCandidates().firstOrNull { it.key() !in protectedKeys }
                // [canFitAfterPruning] ran before the transaction changed rows. If this ever
                // occurs, fail closed and roll back the enclosing Room transaction.
                ?: throw IllegalStateException("No eligible rescue envelope is available for pruning")
            dao.delete(victim.requestId, victim.requestVersion)
            pruned += victim.key()
        }
        return pruned
    }

    private fun StoredRescueRecord.toEntity(sizeBytes: Long) = RescueEntity(
        requestId = envelope.requestId,
        requestVersion = envelope.requestVersion,
        envelopeId = envelope.envelopeId,
        ciphertextSha256Hex = envelope.ciphertextSha256Hex,
        createdAtEpochMillis = envelope.createdAtEpochMillis,
        expiresAtEpochMillis = envelope.expiresAtEpochMillis,
        storageSizeBytes = sizeBytes,
        envelopeJson = json.encodeToString(envelope),
        receivedAtEpochMillis = state.receivedAtEpochMillis,
        submissionStatus = state.submissionStatus.name,
        submissionCount = state.submissionCount,
        signedReceiptJson = state.signedReceipt?.let { json.encodeToString(it) },
    )

    private fun RescueEntity.toRecordOrNull(): StoredRescueRecord? = runCatching {
        val envelope = json.decodeFromString<EncryptedRescueEnvelope>(envelopeJson)
        check(envelope.requestId == requestId && envelope.requestVersion == requestVersion)
        check(envelope.envelopeId == envelopeId && envelope.ciphertextSha256Hex == ciphertextSha256Hex)
        check(envelope.createdAtEpochMillis == createdAtEpochMillis)
        check(envelope.expiresAtEpochMillis == expiresAtEpochMillis)
        check(envelope.storageSizeBytes() == storageSizeBytes)
        check(envelope.validate() == RescueValidationResult.Valid)
        check(RescueCryptography.verifyEnvelopeFraming(envelope))
        check(!envelope.hasSenderAuthorization() || RescueCryptography.verifySenderAuthorization(envelope))
        check(receivedAtEpochMillis > 0 && submissionCount >= 0)
        val receipt = signedReceiptJson?.let { json.decodeFromString<SignedShelterReceipt>(it) }
        check(receipt == null || receipt.validate() == RescueValidationResult.Valid)
        check(
            receipt == null ||
                receipt.receipt.envelopeId == envelope.envelopeId &&
                receipt.receipt.requestId == envelope.requestId &&
                receipt.receipt.requestVersion == envelope.requestVersion &&
                receipt.receipt.ciphertextSha256Hex == envelope.ciphertextSha256Hex &&
                receipt.receipt.shelterId == envelope.destinationShelterId,
        )
        val status = RescueSubmissionStatus.valueOf(submissionStatus)
        val receiptStatus = receipt?.receipt?.status?.toSubmissionStatus()
        check(receiptStatus == null || receiptStatus == status)
        check(receipt != null || status !in receiptOnlyStatuses)
        StoredRescueRecord(
            envelope = envelope,
            state = RescueEnvelopeState(
                receivedAtEpochMillis = receivedAtEpochMillis,
                submissionStatus = status,
                submissionCount = submissionCount,
                signedReceipt = receipt,
            ),
        )
    }.getOrNull()

    private fun RescueEntity.key() = RescueRequestKey(requestId, requestVersion)

    private companion object {
        val receiptOnlyStatuses = setOf(
            RescueSubmissionStatus.SHELTER_STORED,
            RescueSubmissionStatus.SHELTER_ACCEPTED,
            RescueSubmissionStatus.SHELTER_RESPONDING,
            RescueSubmissionStatus.SHELTER_COMPLETED,
            RescueSubmissionStatus.CANCELLED,
            RescueSubmissionStatus.SHELTER_REJECTED,
        )
    }
}
