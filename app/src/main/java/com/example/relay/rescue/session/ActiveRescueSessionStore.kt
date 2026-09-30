package com.example.relay.rescue.session

import com.example.relay.data.local.ActiveRescueSessionEntity
import com.example.relay.data.local.RelayDatabase
import com.example.relay.data.repository.RoomRescueEnvelopeRepository
import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.ReceiptApplicationResult
import com.example.relay.rescue.RescuePublicKey
import com.example.relay.rescue.RescueRequestKey
import com.example.relay.rescue.RescueStoreRejection
import com.example.relay.rescue.RescueStoreResult
import com.example.relay.rescue.RescueSubmissionStatus
import com.example.relay.rescue.SignedShelterReceipt
import com.example.relay.rescue.StoredRescueRecord
import com.example.relay.rescue.toSubmissionStatus

/** Metadata is public routing state; recovery contents remain in the AES-GCM ciphertext fields. */
data class ActiveRescueSession(
    val requestId: String,
    val latestVersion: Int,
    val sealedRecoveryPayload: ByteArray,
    val recoveryNonce: ByteArray,
    val trackingMode: String,
    val latestSubmissionStatus: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val terminalStatus: String?,
) {
    fun isStructurallyValid(): Boolean =
        requestId.length in 1..128 &&
            latestVersion in 1..1_000_000 &&
            sealedRecoveryPayload.size > GCM_AUTH_TAG_BYTES &&
            recoveryNonce.size == 12 &&
            trackingMode in supportedTrackingModes &&
            runCatching { RescueSubmissionStatus.valueOf(latestSubmissionStatus) }.isSuccess &&
            (terminalStatus == null || terminalStatus in supportedTerminalStatuses) &&
            createdAtEpochMillis > 0 &&
            updatedAtEpochMillis >= createdAtEpochMillis &&
            expiresAtEpochMillis > createdAtEpochMillis

    companion object {
        const val TRACKING_DISABLED = "DISABLED"
        /** Phase 5 consent state: the sender has explicitly opted in to periodic location updates. */
        const val TRACKING_ENABLED = "ENABLED"
        const val SESSION_EXPIRED = "EXPIRED"
        private const val GCM_AUTH_TAG_BYTES = 16
        private val supportedTrackingModes = setOf(TRACKING_DISABLED, TRACKING_ENABLED)
        private val supportedTerminalStatuses = setOf(
            SESSION_EXPIRED,
            RescueSubmissionStatus.SHELTER_COMPLETED.name,
            RescueSubmissionStatus.CANCELLED.name,
            RescueSubmissionStatus.SHELTER_REJECTED.name,
        )
    }
}

sealed interface SessionCommitResult {
    data class Stored(val record: StoredRescueRecord) : SessionCommitResult
    /** Sender recovery was stored, but no envelope was created because a destination is unknown. */
    data object PendingDestinationStored : SessionCommitResult
    data class Rejected(val reason: RescueStoreRejection) : SessionCommitResult
    /** Another live sender session was committed by this or a different process. */
    data object ActiveSessionExists : SessionCommitResult
    data object VersionConflict : SessionCommitResult
    data object Invalid : SessionCommitResult
}

/**
 * Transaction boundary for sender-owned state. A successful result guarantees that the encrypted
 * envelope and recovery session were committed together; a rejected/conflict result commits neither.
 */
interface ActiveRescueSessionStore {
    fun all(): List<ActiveRescueSession>
    fun find(requestId: String): ActiveRescueSession?
    fun createAtomically(
        session: ActiveRescueSession,
        envelope: EncryptedRescueEnvelope,
        receivedAtEpochMillis: Long,
    ): SessionCommitResult

    /**
     * Persists an SOS before a trusted shelter key exists. This row contains only encrypted
     * sender recovery material; it deliberately creates no courier-transferable envelope.
     */
    fun createPendingDestinationAtomically(
        session: ActiveRescueSession,
        receivedAtEpochMillis: Long,
    ): SessionCommitResult = SessionCommitResult.Invalid

    fun updateAtomically(
        expectedVersion: Int,
        session: ActiveRescueSession,
        envelope: EncryptedRescueEnvelope,
        receivedAtEpochMillis: Long,
    ): SessionCommitResult

    /**
     * Atomically turns a previously local-only pending SOS into its first encrypted envelope
     * after a trusted shelter key has been resolved. The request version is intentionally kept.
     */
    fun materializePendingDestinationAtomically(
        expectedVersion: Int,
        session: ActiveRescueSession,
        envelope: EncryptedRescueEnvelope,
        receivedAtEpochMillis: Long,
    ): SessionCommitResult = SessionCommitResult.Invalid

    /** Removes an SOS that has never been exported because no trusted destination was available. */
    fun discardPendingDestination(requestId: String, expectedVersion: Int): Boolean = false

    fun applyVerifiedReceipt(
        key: RescueRequestKey,
        signedReceipt: SignedShelterReceipt,
        shelterSigningPublicKey: RescuePublicKey,
        observedAtEpochMillis: Long,
    ): ReceiptApplicationResult

    /** Marks expiry durably but keeps the encrypted recovery data for a user-visible result screen. */
    fun markExpired(requestId: String, requestVersion: Int, observedAtEpochMillis: Long): Boolean

    /** Deletes encrypted sender recovery data only after the terminal result was acknowledged. */
    fun deleteAcknowledgedTerminal(requestId: String): Boolean
}

/** Room implementation that uses one database transaction for the session and envelope write. */
class RoomActiveRescueSessionStore(
    private val database: RelayDatabase,
    private val envelopes: RoomRescueEnvelopeRepository,
) : ActiveRescueSessionStore {
    private val sessions = database.activeRescueSessionDao()

    override fun all(): List<ActiveRescueSession> = sessions.all().map { it.toSession() }

    override fun find(requestId: String): ActiveRescueSession? = sessions.find(requestId)?.toSession()

    override fun createAtomically(
        session: ActiveRescueSession,
        envelope: EncryptedRescueEnvelope,
        receivedAtEpochMillis: Long,
    ): SessionCommitResult {
        if (!validCommit(session, envelope, receivedAtEpochMillis)) return SessionCommitResult.Invalid
        return try {
            database.runInTransaction<SessionCommitResult> {
                if (sessions.hasLiveActiveSession(receivedAtEpochMillis)) {
                    return@runInTransaction SessionCommitResult.ActiveSessionExists
                }
                if (sessions.find(session.requestId) != null) throw SessionVersionConflict()
                when (val stored = envelopes.storeInTransaction(envelope, receivedAtEpochMillis, allowPruning = false)) {
                    is RescueStoreResult.Stored -> {
                        check(sessions.insert(session.toEntity()) != -1L)
                        SessionCommitResult.Stored(stored.record)
                    }
                    is RescueStoreResult.Rejected -> SessionCommitResult.Rejected(stored.reason)
                }
            }
        } catch (_: SessionVersionConflict) {
            SessionCommitResult.VersionConflict
        }
    }

    override fun createPendingDestinationAtomically(
        session: ActiveRescueSession,
        receivedAtEpochMillis: Long,
    ): SessionCommitResult {
        if (!validPendingDestination(session, receivedAtEpochMillis)) return SessionCommitResult.Invalid
        return try {
            database.runInTransaction<SessionCommitResult> {
                if (sessions.hasLiveActiveSession(receivedAtEpochMillis)) {
                    return@runInTransaction SessionCommitResult.ActiveSessionExists
                }
                if (sessions.find(session.requestId) != null) throw SessionVersionConflict()
                check(sessions.insert(session.toEntity()) != -1L)
                SessionCommitResult.PendingDestinationStored
            }
        } catch (_: SessionVersionConflict) {
            SessionCommitResult.VersionConflict
        }
    }

    override fun updateAtomically(
        expectedVersion: Int,
        session: ActiveRescueSession,
        envelope: EncryptedRescueEnvelope,
        receivedAtEpochMillis: Long,
    ): SessionCommitResult {
        if (!validCommit(session, envelope, receivedAtEpochMillis) || session.latestVersion <= expectedVersion) {
            return SessionCommitResult.Invalid
        }
        return try {
            database.runInTransaction<SessionCommitResult> {
                val current = sessions.find(session.requestId) ?: throw SessionVersionConflict()
                if (current.latestVersion != expectedVersion) throw SessionVersionConflict()
                if (current.terminalStatus != null) return@runInTransaction SessionCommitResult.Invalid
                when (val stored = envelopes.storeInTransaction(envelope, receivedAtEpochMillis, allowPruning = false)) {
                    is RescueStoreResult.Stored -> {
                        val updated = sessions.updateIfVersion(
                            requestId = session.requestId,
                            expectedVersion = expectedVersion,
                            nextVersion = session.latestVersion,
                            sealedRecoveryPayload = session.sealedRecoveryPayload,
                            recoveryNonce = session.recoveryNonce,
                            trackingMode = session.trackingMode,
                            latestSubmissionStatus = session.latestSubmissionStatus,
                            updatedAtEpochMillis = session.updatedAtEpochMillis,
                            expiresAtEpochMillis = session.expiresAtEpochMillis,
                            terminalStatus = session.terminalStatus,
                        )
                        if (updated != 1) throw SessionVersionConflict()
                        SessionCommitResult.Stored(stored.record)
                    }
                    is RescueStoreResult.Rejected -> SessionCommitResult.Rejected(stored.reason)
                }
            }
        } catch (_: SessionVersionConflict) {
            SessionCommitResult.VersionConflict
        }
    }

    override fun materializePendingDestinationAtomically(
        expectedVersion: Int,
        session: ActiveRescueSession,
        envelope: EncryptedRescueEnvelope,
        receivedAtEpochMillis: Long,
    ): SessionCommitResult {
        if (!validCommit(session, envelope, receivedAtEpochMillis) ||
            session.latestVersion != expectedVersion ||
            session.latestSubmissionStatus != RescueSubmissionStatus.PENDING.name
        ) {
            return SessionCommitResult.Invalid
        }
        return try {
            database.runInTransaction<SessionCommitResult> {
                val current = sessions.find(session.requestId) ?: throw SessionVersionConflict()
                if (current.latestVersion != expectedVersion ||
                    current.latestSubmissionStatus != RescueSubmissionStatus.PENDING_DESTINATION.name ||
                    current.terminalStatus != null
                ) {
                    throw SessionVersionConflict()
                }
                when (val stored = envelopes.storeInTransaction(envelope, receivedAtEpochMillis, allowPruning = false)) {
                    is RescueStoreResult.Stored -> {
                        val updated = sessions.materializePendingDestination(
                            requestId = session.requestId,
                            expectedVersion = expectedVersion,
                            sealedRecoveryPayload = session.sealedRecoveryPayload,
                            recoveryNonce = session.recoveryNonce,
                            trackingMode = session.trackingMode,
                            latestSubmissionStatus = session.latestSubmissionStatus,
                            updatedAtEpochMillis = session.updatedAtEpochMillis,
                            expiresAtEpochMillis = session.expiresAtEpochMillis,
                        )
                        if (updated != 1) throw SessionVersionConflict()
                        SessionCommitResult.Stored(stored.record)
                    }
                    is RescueStoreResult.Rejected -> SessionCommitResult.Rejected(stored.reason)
                }
            }
        } catch (_: SessionVersionConflict) {
            SessionCommitResult.VersionConflict
        }
    }

    override fun discardPendingDestination(requestId: String, expectedVersion: Int): Boolean =
        database.runInTransaction<Boolean> {
            sessions.deletePendingDestination(requestId, expectedVersion) == 1
        }

    override fun applyVerifiedReceipt(
        key: RescueRequestKey,
        signedReceipt: SignedShelterReceipt,
        shelterSigningPublicKey: RescuePublicKey,
        observedAtEpochMillis: Long,
    ): ReceiptApplicationResult = database.runInTransaction<ReceiptApplicationResult> {
        val result = envelopes.applyReceiptInTransaction(key, signedReceipt, shelterSigningPublicKey)
        if (result == ReceiptApplicationResult.APPLIED) {
            val status = signedReceipt.receipt.status.toSubmissionStatus()
            val senderSession = sessions.find(key.requestId)
            if (senderSession?.latestVersion == key.requestVersion) {
                // A matching sender session is part of the same durable state machine.  Never
                // report an Envelope receipt as applied if the corresponding session row could
                // not be updated; throwing rolls back the enclosing Room transaction.
                check(
                    sessions.updateReceiptStatus(
                        requestId = key.requestId,
                        requestVersion = key.requestVersion,
                        latestSubmissionStatus = status.name,
                        terminalStatus = status.takeIf { it.isTerminal() }?.name,
                        updatedAtEpochMillis = observedAtEpochMillis,
                    ) == 1,
                ) { "sender session receipt state was not updated" }
            }
        }
        result
    }

    override fun markExpired(requestId: String, requestVersion: Int, observedAtEpochMillis: Long): Boolean =
        database.runInTransaction<Boolean> {
            sessions.markExpired(requestId, requestVersion, observedAtEpochMillis) == 1
        }

    override fun deleteAcknowledgedTerminal(requestId: String): Boolean = database.runInTransaction<Boolean> {
        sessions.deleteTerminal(requestId) == 1
    }

    private fun validCommit(
        session: ActiveRescueSession,
        envelope: EncryptedRescueEnvelope,
        receivedAtEpochMillis: Long,
    ): Boolean =
        session.isStructurallyValid() &&
            receivedAtEpochMillis > 0 &&
            session.requestId == envelope.requestId &&
            session.latestVersion == envelope.requestVersion &&
            session.expiresAtEpochMillis == envelope.expiresAtEpochMillis &&
            session.terminalStatus == null

    private fun validPendingDestination(
        session: ActiveRescueSession,
        receivedAtEpochMillis: Long,
    ): Boolean =
        session.isStructurallyValid() &&
            receivedAtEpochMillis > 0 &&
            session.latestSubmissionStatus == RescueSubmissionStatus.PENDING_DESTINATION.name &&
            session.terminalStatus == null

    private fun ActiveRescueSessionEntity.toSession() = ActiveRescueSession(
        requestId = requestId,
        latestVersion = latestVersion,
        sealedRecoveryPayload = sealedRecoveryPayload,
        recoveryNonce = recoveryNonce,
        trackingMode = trackingMode,
        latestSubmissionStatus = latestSubmissionStatus,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        expiresAtEpochMillis = expiresAtEpochMillis,
        terminalStatus = terminalStatus,
    )

    private fun ActiveRescueSession.toEntity() = ActiveRescueSessionEntity(
        requestId = requestId,
        latestVersion = latestVersion,
        sealedRecoveryPayload = sealedRecoveryPayload,
        recoveryNonce = recoveryNonce,
        trackingMode = trackingMode,
        latestSubmissionStatus = latestSubmissionStatus,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        expiresAtEpochMillis = expiresAtEpochMillis,
        terminalStatus = terminalStatus,
    )

    private class SessionVersionConflict : RuntimeException()
}

private fun RescueSubmissionStatus.isTerminal(): Boolean = this in setOf(
    RescueSubmissionStatus.SHELTER_COMPLETED,
    RescueSubmissionStatus.CANCELLED,
    RescueSubmissionStatus.SHELTER_REJECTED,
)
