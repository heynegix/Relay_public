package com.example.relay.rescue.session

import com.example.relay.location.LocationProvider
import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueLocation
import com.example.relay.rescue.RescueRequestAction
import com.example.relay.rescue.RescueRequestDraft
import com.example.relay.rescue.RescueSubmissionStatus
import com.example.relay.rescue.ShelterPublicKeyProvider
import com.example.relay.rescue.ShelterPublicKeys
import com.example.relay.rescue.StoredRescueRecord
import com.example.relay.rescue.toPayload
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RecoveredRescueSession(
    val session: ActiveRescueSession,
    val recovery: RescueSessionRecoveryPayload,
    val submissionStatus: RescueSubmissionStatus,
)

sealed interface RescueSessionRestoreResult {
    data object None : RescueSessionRestoreResult
    data class Restored(val value: RecoveredRescueSession) : RescueSessionRestoreResult
    /** Retained, undecryptable data; callers must show a recovery failure rather than a new request. */
    data class Corrupt(val requestId: String) : RescueSessionRestoreResult
}

sealed interface RescueSessionOperationResult {
    data class Stored(val value: RecoveredRescueSession, val record: StoredRescueRecord) : RescueSessionOperationResult
    /**
     * SOS recovery data is durable, but no courier envelope exists until a trusted destination
     * key becomes available. This state is intentionally local to the sender device.
     */
    data class PendingDestination(val value: RecoveredRescueSession) : RescueSessionOperationResult
    /** A live request already exists; it is restored rather than creating a competing request. */
    data class ActiveSessionExists(val value: RecoveredRescueSession) : RescueSessionOperationResult
    data object ShelterUnavailable : RescueSessionOperationResult
    data object LocationUnavailable : RescueSessionOperationResult
    data object Expired : RescueSessionOperationResult
    data object Terminal : RescueSessionOperationResult
    data object Corrupt : RescueSessionOperationResult
    data object CancelledAlready : RescueSessionOperationResult
    /** The SOS was removed locally before any encrypted envelope could leave this device. */
    data object PendingDestinationDiscarded : RescueSessionOperationResult
    /** A consented location update was requested, but the sender has not opted in to tracking. */
    data object TrackingNotConsented : RescueSessionOperationResult
    /** Consent already matched the requested value, so no new version or envelope was produced. */
    data class TrackingConsentUnchanged(val value: RecoveredRescueSession) : RescueSessionOperationResult
    data object Conflict : RescueSessionOperationResult
    data object StorageFailure : RescueSessionOperationResult
}

/**
 * Sender-owned rescue workflow. The ViewModel supplies UI input only; this coordinator owns
 * version allocation, encryption, atomic persistence, recovery, cancellation, and expiry.
 */
@Suppress("LongParameterList")
class ActiveRescueSessionCoordinator(
    private val store: ActiveRescueSessionStore,
    private val recoveryCipher: RecoveryPayloadCipher,
    private val shelterKeyProvider: ShelterPublicKeyProvider,
    private val locationProvider: LocationProvider? = null,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val newEnvelopeId: () -> String = { UUID.randomUUID().toString() },
    private val requestLifetimeMillis: Long = DEFAULT_REQUEST_LIFETIME_MILLIS,
    private val deliveryNotifier: RescueDeliveryNotifier = RescueDeliveryNotifier { },
    private val senderEnvelopeAuthorizer: (EncryptedRescueEnvelope) -> EncryptedRescueEnvelope = { it },
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    init {
        require(requestLifetimeMillis in 60_000..(30L * 24 * 60 * 60 * 1_000))
    }

    /** Creates an initial request and durable recovery state as one commit. */
    suspend fun create(draft: RescueRequestDraft): RescueSessionOperationResult {
        existingLiveSession()?.let { return it }
        val now = nowEpochMillis()
        // Location improves dispatch quality but must never gate creation of an SOS.
        // Indoor, underground, permission-denied, and GPS-off cases still need a durable
        // request that can be relayed and enriched later after consent.
        val located = attachCurrentLocation(
            draft.copy(
                requestVersion = 1,
                createdAtEpochMillis = now,
                expiresAtEpochMillis = now + requestLifetimeMillis,
                action = RescueRequestAction.ACTIVE,
            ),
        )
        return mutexFor(located.requestId).withLock {
            val keys = shelterKeyProvider.load()
            if (keys == null) {
                val pending = preparePendingDestination(located)
                    ?: return@withLock RescueSessionOperationResult.StorageFailure
                when (val result = store.createPendingDestinationAtomically(pending.session, now)) {
                    SessionCommitResult.PendingDestinationStored -> {
                        val stored = RescueSessionOperationResult.PendingDestination(
                            RecoveredRescueSession(
                                session = pending.session,
                                recovery = pending.recovery,
                                submissionStatus = RescueSubmissionStatus.PENDING_DESTINATION,
                            ),
                        )
                        deliveryNotifier.onDeliveryRequired()
                        stored
                    }
                    is SessionCommitResult.Rejected,
                    SessionCommitResult.Invalid,
                    -> RescueSessionOperationResult.StorageFailure
                    SessionCommitResult.ActiveSessionExists -> existingLiveSession()
                        ?: RescueSessionOperationResult.StorageFailure
                    SessionCommitResult.VersionConflict -> RescueSessionOperationResult.Conflict
                    is SessionCommitResult.Stored -> RescueSessionOperationResult.StorageFailure
                }
            } else {
                val prepared = prepare(located.copy(destinationShelterId = keys.shelterId), keys)
                    ?: return@withLock RescueSessionOperationResult.StorageFailure
                when (val result = store.createAtomically(prepared.session, prepared.envelope, now)) {
                    is SessionCommitResult.Stored -> {
                        val stored = RescueSessionOperationResult.Stored(
                            value = RecoveredRescueSession(
                                session = prepared.session,
                                recovery = prepared.recovery,
                                submissionStatus = result.record.state.submissionStatus,
                            ),
                            record = result.record,
                        )
                        deliveryNotifier.onDeliveryRequired()
                        stored
                    }
                    is SessionCommitResult.Rejected,
                    SessionCommitResult.Invalid,
                    -> RescueSessionOperationResult.StorageFailure
                    SessionCommitResult.ActiveSessionExists -> existingLiveSession()
                        ?: RescueSessionOperationResult.StorageFailure
                    SessionCommitResult.VersionConflict -> RescueSessionOperationResult.Conflict
                    SessionCommitResult.PendingDestinationStored -> RescueSessionOperationResult.StorageFailure
                }
            }
        }
    }

    /**
     * Materializes sender-owned, local-only SOS sessions after a trusted shelter key arrives.
     * No unknown or Nearby-advertised key is accepted here: [shelterKeyProvider] is the sole
     * trust boundary. Call this from durable delivery work, not from a UI lifecycle.
     */
    suspend fun resolvePendingDestinations(): Int {
        val candidates = store.all()
            .filter { it.terminalStatus == null && it.latestSubmissionStatus == RescueSubmissionStatus.PENDING_DESTINATION.name }
            .sortedBy { it.requestId }
        if (candidates.isEmpty()) return 0
        val keys = shelterKeyProvider.load() ?: return 0
        var resolved = 0
        candidates.forEach { candidate ->
            val didResolve = mutexFor(candidate.requestId).withLock {
                val restored = (restore(candidate) as? RescueSessionRestoreResult.Restored)?.value ?: return@withLock false
                if (restored.submissionStatus != RescueSubmissionStatus.PENDING_DESTINATION) return@withLock false
                if (nowEpochMillis() >= restored.session.expiresAtEpochMillis) {
                    store.markExpired(restored.session.requestId, restored.session.latestVersion, nowEpochMillis())
                    return@withLock false
                }
                val now = nowEpochMillis()
                val prepared = prepare(
                    restored.recovery.draft.copy(destinationShelterId = keys.shelterId),
                    keys,
                ) ?: return@withLock false
                val session = prepared.session.copy(
                    createdAtEpochMillis = restored.session.createdAtEpochMillis,
                    updatedAtEpochMillis = now,
                )
                when (store.materializePendingDestinationAtomically(
                    expectedVersion = restored.session.latestVersion,
                    session = session,
                    envelope = prepared.envelope,
                    receivedAtEpochMillis = now,
                )) {
                    is SessionCommitResult.Stored -> {
                        deliveryNotifier.onDeliveryRequired()
                        true
                    }
                    else -> false
                }
            }
            if (didResolve) resolved++
        }
        return resolved
    }

    /** Lightweight state check for background work; it exposes no recovery plaintext. */
    fun hasPendingDestination(): Boolean = store.all().any {
        it.terminalStatus == null && it.latestSubmissionStatus == RescueSubmissionStatus.PENDING_DESTINATION.name
    }

    /** Restores the newest sender session, including a terminal result the user has not dismissed. */
    fun restoreLatest(): RescueSessionRestoreResult {
        val candidate = store.all()
            .sortedWith(
                compareBy<ActiveRescueSession> { it.terminalStatus != null }
                    .thenByDescending { it.updatedAtEpochMillis }
                    .thenBy { it.requestId },
            )
            .firstOrNull() ?: return RescueSessionRestoreResult.None
        return restore(candidate)
    }

    fun restore(requestId: String): RescueSessionRestoreResult =
        store.find(requestId)?.let(::restore) ?: RescueSessionRestoreResult.None

    /**
     * Builds the user-editable draft without allocating a version. Allocation happens only in
     * [update], after it re-checks the durable version within the transaction.
     */
    fun prepareUpdate(requestId: String): RescueSessionRestoreResult = restore(requestId)

    /**
     * A terminal result remains recoverable until the sender explicitly acknowledges it. The
     * acknowledgement removes only the AES-GCM recovery row; courier envelopes are left intact
     * for their independent retention/receipt policy.
     */
    suspend fun acknowledgeTerminalResult(requestId: String): Boolean = mutexFor(requestId).withLock {
        val restored = restore(requestId)
        if (restored !is RescueSessionRestoreResult.Restored || restored.value.session.terminalStatus == null) {
            false
        } else {
            store.deleteAcknowledgedTerminal(requestId)
        }
    }

    suspend fun update(
        requestId: String,
        editedDraft: RescueRequestDraft,
    ): RescueSessionOperationResult = mutate(requestId) { recovered, nextVersion, now ->
        if (recovered.recovery.draft.action != RescueRequestAction.ACTIVE) return@mutate null
        editedDraft.copy(
            requestId = requestId,
            requestVersion = nextVersion,
            senderDeviceId = recovered.recovery.draft.senderDeviceId,
            destinationShelterId = recovered.recovery.draft.destinationShelterId,
            createdAtEpochMillis = now,
            expiresAtEpochMillis = now + requestLifetimeMillis,
            action = RescueRequestAction.ACTIVE,
        )
    }

    suspend fun cancel(requestId: String): RescueSessionOperationResult = mutate(
        requestId,
        discardPendingDestination = true,
    ) { recovered, nextVersion, now ->
        val current = recovered.recovery.draft
        if (current.action != RescueRequestAction.ACTIVE) return@mutate null
        current.copy(
            requestVersion = nextVersion,
            createdAtEpochMillis = now,
            expiresAtEpochMillis = now + requestLifetimeMillis,
            action = RescueRequestAction.CANCELLED,
        )
    }

    /**
     * Records the sender's explicit Phase 5 tracking decision. Enabling requires an active,
     * already-materialized request; the durable consent flag is persisted inside the encrypted
     * recovery payload and mirrored to the public [ActiveRescueSession.trackingMode]. An unchanged
     * decision commits no new version or envelope. This never itself captures a location; callers
     * drive fixes through [recordConsentedLocationUpdate] once consent is granted.
     */
    suspend fun setTrackingConsent(requestId: String, enabled: Boolean): RescueSessionOperationResult {
        (restore(requestId) as? RescueSessionRestoreResult.Restored)?.value?.let { current ->
            if (current.session.terminalStatus == null &&
                current.submissionStatus != RescueSubmissionStatus.PENDING_DESTINATION &&
                current.recovery.trackingEnabled == enabled
            ) {
                return RescueSessionOperationResult.TrackingConsentUnchanged(current)
            }
        }
        return mutate(requestId, trackingConsent = { enabled }) { recovered, nextVersion, now ->
            if (recovered.recovery.draft.action != RescueRequestAction.ACTIVE) return@mutate null
            recovered.recovery.draft.copy(
                requestVersion = nextVersion,
                createdAtEpochMillis = now,
                expiresAtEpochMillis = now + requestLifetimeMillis,
                action = RescueRequestAction.ACTIVE,
            )
        }
    }

    /**
     * Captures one fresh fix and emits it as a new encrypted request version, but only while the
     * sender has consented to tracking. Without consent this is a no-op ([TrackingNotConsented])
     * so location is never captured or transmitted implicitly. Callers invoke this periodically
     * while a request is active.
     */
    suspend fun recordConsentedLocationUpdate(requestId: String): RescueSessionOperationResult {
        val restored = when (val result = restore(requestId)) {
            is RescueSessionRestoreResult.Restored -> result.value
            RescueSessionRestoreResult.None -> return RescueSessionOperationResult.StorageFailure
            is RescueSessionRestoreResult.Corrupt -> return RescueSessionOperationResult.Corrupt
        }
        if (restored.session.terminalStatus == ActiveRescueSession.SESSION_EXPIRED) {
            return RescueSessionOperationResult.Expired
        }
        if (restored.session.terminalStatus != null) return RescueSessionOperationResult.Terminal
        if (restored.submissionStatus == RescueSubmissionStatus.PENDING_DESTINATION) {
            return RescueSessionOperationResult.PendingDestination(restored)
        }
        // Consent is the gate: never touch the location hardware before an explicit opt-in.
        if (!restored.recovery.trackingEnabled) return RescueSessionOperationResult.TrackingNotConsented
        val provider = locationProvider ?: return RescueSessionOperationResult.LocationUnavailable
        val fix = runCatching { provider.currentFix(8_000) }.getOrNull()
            ?: return RescueSessionOperationResult.LocationUnavailable
        return mutate(requestId, trackingConsent = { it.recovery.trackingEnabled }) { recovered, nextVersion, now ->
            if (recovered.recovery.draft.action != RescueRequestAction.ACTIVE) return@mutate null
            if (!recovered.recovery.trackingEnabled) return@mutate null
            recovered.recovery.draft.copy(
                requestVersion = nextVersion,
                createdAtEpochMillis = now,
                expiresAtEpochMillis = now + requestLifetimeMillis,
                action = RescueRequestAction.ACTIVE,
                location = RescueLocation(
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    accuracyMeters = fix.accuracyMeters,
                    description = recovered.recovery.draft.location?.description.orEmpty(),
                    capturedAtEpochMillis = fix.capturedAtEpochMillis,
                ),
            )
        }
    }

    private suspend fun mutate(
        requestId: String,
        discardPendingDestination: Boolean = false,
        trackingConsent: (RecoveredRescueSession) -> Boolean = { it.recovery.trackingEnabled },
        transform: (RecoveredRescueSession, Int, Long) -> RescueRequestDraft?,
    ): RescueSessionOperationResult = mutexFor(requestId).withLock {
        repeat(MAX_CONFLICT_RETRIES) {
            val restored = when (val result = restore(requestId)) {
                is RescueSessionRestoreResult.Restored -> result.value
                RescueSessionRestoreResult.None -> return@withLock RescueSessionOperationResult.StorageFailure
                is RescueSessionRestoreResult.Corrupt -> return@withLock RescueSessionOperationResult.Corrupt
            }
            if (restored.session.terminalStatus == ActiveRescueSession.SESSION_EXPIRED) {
                return@withLock RescueSessionOperationResult.Expired
            }
            if (restored.session.terminalStatus != null) return@withLock RescueSessionOperationResult.Terminal
            if (nowEpochMillis() >= restored.session.expiresAtEpochMillis) {
                store.markExpired(requestId, restored.session.latestVersion, nowEpochMillis())
                return@withLock RescueSessionOperationResult.Expired
            }
            // A local-only SOS has never been emitted, so changing it before a trusted key is
            // resolved must not manufacture a different ciphertext or a fake version. It can be
            // safely discarded on an explicit cancel because no courier ever received an envelope.
            if (restored.submissionStatus == RescueSubmissionStatus.PENDING_DESTINATION) {
                if (discardPendingDestination) {
                    return@withLock if (store.discardPendingDestination(
                            restored.session.requestId,
                            restored.session.latestVersion,
                        )
                    ) {
                        RescueSessionOperationResult.PendingDestinationDiscarded
                    } else {
                        RescueSessionOperationResult.Conflict
                    }
                }
                return@withLock RescueSessionOperationResult.PendingDestination(restored)
            }
            val now = nowEpochMillis()
            val draft = transform(restored, restored.session.latestVersion + 1, now)
                ?: return@withLock RescueSessionOperationResult.CancelledAlready
            val recipientKey = restored.recovery.recipientPublicKey
                ?: return@withLock RescueSessionOperationResult.Corrupt
            val receiptKey = restored.recovery.receiptSigningPublicKey
                ?: return@withLock RescueSessionOperationResult.Corrupt
            val prepared = prepare(draft, recipientKey, receiptKey, trackingConsent(restored))
                ?: return@withLock RescueSessionOperationResult.StorageFailure
            val session = prepared.session.copy(createdAtEpochMillis = restored.session.createdAtEpochMillis)
            when (val committed = store.updateAtomically(
                expectedVersion = restored.session.latestVersion,
                session = session,
                envelope = prepared.envelope,
                receivedAtEpochMillis = now,
            )) {
                is SessionCommitResult.Stored -> {
                    val stored = RescueSessionOperationResult.Stored(
                        value = RecoveredRescueSession(
                            session = session,
                            recovery = prepared.recovery,
                            submissionStatus = RescueSubmissionStatus.PENDING,
                        ),
                        record = committed.record,
                    )
                    deliveryNotifier.onDeliveryRequired()
                    return@withLock stored
                }
                is SessionCommitResult.Rejected,
                SessionCommitResult.Invalid,
                -> return@withLock RescueSessionOperationResult.StorageFailure
                SessionCommitResult.ActiveSessionExists -> return@withLock existingLiveSession()
                    ?: RescueSessionOperationResult.StorageFailure
                SessionCommitResult.VersionConflict -> Unit // Re-read and safely rebuild after the short DB transaction.
                SessionCommitResult.PendingDestinationStored -> return@withLock RescueSessionOperationResult.StorageFailure
            }
        }
        RescueSessionOperationResult.Conflict
    }

    private fun restore(session: ActiveRescueSession): RescueSessionRestoreResult {
        if (!session.isStructurallyValid()) return RescueSessionRestoreResult.Corrupt(session.requestId)
        val recovery = runCatching {
            recoveryCipher.open(SealedRecoveryPayload(session.sealedRecoveryPayload, session.recoveryNonce))
        }.getOrElse { return RescueSessionRestoreResult.Corrupt(session.requestId) }
        if (recovery.draft.requestId != session.requestId ||
            recovery.draft.requestVersion != session.latestVersion ||
            recovery.draft.expiresAtEpochMillis != session.expiresAtEpochMillis
        ) {
            return RescueSessionRestoreResult.Corrupt(session.requestId)
        }
        if (session.terminalStatus == null && nowEpochMillis() >= session.expiresAtEpochMillis) {
            store.markExpired(session.requestId, session.latestVersion, nowEpochMillis())
            return restore(session.copy(terminalStatus = ActiveRescueSession.SESSION_EXPIRED, updatedAtEpochMillis = nowEpochMillis()))
        }
        val status = runCatching { RescueSubmissionStatus.valueOf(session.latestSubmissionStatus) }
            .getOrElse { return RescueSessionRestoreResult.Corrupt(session.requestId) }
        val hasRecipientKey = recovery.recipientPublicKey != null
        val hasReceiptKey = recovery.receiptSigningPublicKey != null
        if (hasRecipientKey != hasReceiptKey ||
            (status == RescueSubmissionStatus.PENDING_DESTINATION && hasRecipientKey) ||
            (status != RescueSubmissionStatus.PENDING_DESTINATION && !hasRecipientKey)
        ) {
            return RescueSessionRestoreResult.Corrupt(session.requestId)
        }
        return RescueSessionRestoreResult.Restored(RecoveredRescueSession(session, recovery, status))
    }

    private fun prepare(draft: RescueRequestDraft, keys: ShelterPublicKeys): PreparedSession? =
        prepare(draft, keys.recipientKey, keys.receiptSigningKey)

    /** Encrypts before opening the short Room transaction; CAS handles any concurrent change. */
    private fun prepare(
        draft: RescueRequestDraft,
        recipientPublicKey: com.example.relay.rescue.RescuePublicKey,
        receiptSigningPublicKey: com.example.relay.rescue.RescuePublicKey,
        trackingEnabled: Boolean = false,
    ): PreparedSession? = runCatching {
        val recovery = RescueSessionRecoveryPayload(
            draft = draft,
            recipientPublicKey = recipientPublicKey,
            receiptSigningPublicKey = receiptSigningPublicKey,
            trackingEnabled = trackingEnabled,
        )
        val sealed = recoveryCipher.seal(recovery)
        val unsignedEnvelope = RescueCryptography.encrypt(
            payload = draft.toPayload(),
            recipientPublicKey = recipientPublicKey,
            envelopeId = newEnvelopeId(),
        )
        val envelope = senderEnvelopeAuthorizer(unsignedEnvelope)
        PreparedSession(
            recovery = recovery,
            envelope = envelope,
            session = ActiveRescueSession(
                requestId = draft.requestId,
                latestVersion = draft.requestVersion,
                sealedRecoveryPayload = sealed.ciphertext,
                recoveryNonce = sealed.nonce,
                trackingMode = trackingModeFor(trackingEnabled),
                latestSubmissionStatus = RescueSubmissionStatus.PENDING.name,
                createdAtEpochMillis = draft.createdAtEpochMillis,
                updatedAtEpochMillis = draft.createdAtEpochMillis,
                expiresAtEpochMillis = draft.expiresAtEpochMillis,
                terminalStatus = null,
            ),
        )
    }.getOrNull()

    /**
     * Keeps all request content under the device recovery cipher until a trusted destination key
     * is available. There is deliberately no envelope, inventory item, or peer export at this
     * point because another device could not decrypt it safely.
     */
    private fun preparePendingDestination(draft: RescueRequestDraft): PendingDestinationSession? = runCatching {
        val recovery = RescueSessionRecoveryPayload(draft = draft)
        val sealed = recoveryCipher.seal(recovery)
        PendingDestinationSession(
            recovery = recovery,
            session = ActiveRescueSession(
                requestId = draft.requestId,
                latestVersion = draft.requestVersion,
                sealedRecoveryPayload = sealed.ciphertext,
                recoveryNonce = sealed.nonce,
                trackingMode = ActiveRescueSession.TRACKING_DISABLED,
                latestSubmissionStatus = RescueSubmissionStatus.PENDING_DESTINATION.name,
                createdAtEpochMillis = draft.createdAtEpochMillis,
                updatedAtEpochMillis = draft.createdAtEpochMillis,
                expiresAtEpochMillis = draft.expiresAtEpochMillis,
                terminalStatus = null,
            ),
        )
    }.getOrNull()

    private suspend fun attachCurrentLocation(draft: RescueRequestDraft): RescueRequestDraft {
        val provider = locationProvider ?: return draft
        return runCatching { provider.currentFix(8_000) }.getOrNull()?.let { fix ->
            draft.copy(location = RescueLocation(
                latitude = fix.latitude,
                longitude = fix.longitude,
                accuracyMeters = fix.accuracyMeters,
                description = draft.location?.description.orEmpty(),
                capturedAtEpochMillis = fix.capturedAtEpochMillis,
            ))
        } ?: draft
    }

    private fun mutexFor(requestId: String): Mutex = locks.computeIfAbsent(requestId) { Mutex() }

    private fun trackingModeFor(enabled: Boolean): String =
        if (enabled) ActiveRescueSession.TRACKING_ENABLED else ActiveRescueSession.TRACKING_DISABLED

    /** A corrupted durable record blocks new creation instead of being mistaken for an empty app. */
    private fun existingLiveSession(): RescueSessionOperationResult? {
        for (session in store.all().sortedByDescending { it.updatedAtEpochMillis }) {
            when (val restored = restore(session)) {
                is RescueSessionRestoreResult.Corrupt -> return RescueSessionOperationResult.Corrupt
                RescueSessionRestoreResult.None -> Unit
                is RescueSessionRestoreResult.Restored -> if (restored.value.session.terminalStatus == null) {
                    return RescueSessionOperationResult.ActiveSessionExists(restored.value)
                }
            }
        }
        return null
    }

    private data class PreparedSession(
        val recovery: RescueSessionRecoveryPayload,
        val session: ActiveRescueSession,
        val envelope: EncryptedRescueEnvelope,
    )

    private data class PendingDestinationSession(
        val recovery: RescueSessionRecoveryPayload,
        val session: ActiveRescueSession,
    )

    private companion object {
        const val DEFAULT_REQUEST_LIFETIME_MILLIS = 3L * 24 * 60 * 60 * 1_000
        const val MAX_CONFLICT_RETRIES = 3
    }
}

/** Android supplies this from the application boundary; tests use the no-op default. */
fun interface RescueDeliveryNotifier {
    fun onDeliveryRequired()
}
