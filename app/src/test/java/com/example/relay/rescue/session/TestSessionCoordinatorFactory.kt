package com.example.relay.rescue.session

import com.example.relay.location.LocationProvider
import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.InMemoryRescueEnvelopeRepository
import com.example.relay.rescue.ReceiptApplicationResult
import com.example.relay.rescue.RescueRequestKey
import com.example.relay.rescue.RescueStoreResult
import com.example.relay.rescue.ShelterPublicKeyProvider
import com.example.relay.rescue.SignedShelterReceipt
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** TEST ONLY: test-scoped in-memory session persistence and ephemeral AES key. */
fun testSessionCoordinator(
    repository: InMemoryRescueEnvelopeRepository,
    shelterKeyProvider: ShelterPublicKeyProvider,
    locationProvider: LocationProvider? = null,
    nowEpochMillis: () -> Long,
): ActiveRescueSessionCoordinator = ActiveRescueSessionCoordinator(
    store = TestSessionStore(repository),
    recoveryCipher = AesGcmRecoveryPayloadCipher(TestSessionKeyProvider(), "TEST ONLY view-model"),
    shelterKeyProvider = shelterKeyProvider,
    locationProvider = locationProvider,
    nowEpochMillis = nowEpochMillis,
)

private class TestSessionKeyProvider : SessionSecretKeyProvider {
    private val keys = mutableMapOf<String, SecretKey>()
    override fun key(alias: String): SecretKey = keys.getOrPut(alias) {
        KeyGenerator.getInstance("AES").apply { init(256, SecureRandom()) }.generateKey()
    }
}

private class TestSessionStore(
    private val envelopes: InMemoryRescueEnvelopeRepository,
) : ActiveRescueSessionStore {
    private val lock = Any()
    private val sessions = linkedMapOf<String, ActiveRescueSession>()

    override fun all(): List<ActiveRescueSession> = synchronized(lock) { sessions.values.toList() }
    override fun find(requestId: String): ActiveRescueSession? = synchronized(lock) { sessions[requestId] }

    override fun createAtomically(session: ActiveRescueSession, envelope: EncryptedRescueEnvelope, receivedAtEpochMillis: Long): SessionCommitResult = synchronized(lock) {
        if (sessions.containsKey(session.requestId)) return@synchronized SessionCommitResult.VersionConflict
        when (val result = envelopes.store(envelope, receivedAtEpochMillis)) {
            is RescueStoreResult.Stored -> {
                sessions[session.requestId] = session
                SessionCommitResult.Stored(result.record)
            }
            is RescueStoreResult.Rejected -> SessionCommitResult.Rejected(result.reason)
        }
    }

    override fun createPendingDestinationAtomically(
        session: ActiveRescueSession,
        receivedAtEpochMillis: Long,
    ): SessionCommitResult = synchronized(lock) {
        if (sessions.containsKey(session.requestId)) return@synchronized SessionCommitResult.VersionConflict
        sessions[session.requestId] = session
        SessionCommitResult.PendingDestinationStored
    }

    override fun updateAtomically(expectedVersion: Int, session: ActiveRescueSession, envelope: EncryptedRescueEnvelope, receivedAtEpochMillis: Long): SessionCommitResult = synchronized(lock) {
        if (sessions[session.requestId]?.latestVersion != expectedVersion) return@synchronized SessionCommitResult.VersionConflict
        when (val result = envelopes.store(envelope, receivedAtEpochMillis)) {
            is RescueStoreResult.Stored -> {
                sessions[session.requestId] = session
                SessionCommitResult.Stored(result.record)
            }
            is RescueStoreResult.Rejected -> SessionCommitResult.Rejected(result.reason)
        }
    }

    override fun materializePendingDestinationAtomically(
        expectedVersion: Int,
        session: ActiveRescueSession,
        envelope: EncryptedRescueEnvelope,
        receivedAtEpochMillis: Long,
    ): SessionCommitResult = synchronized(lock) {
        val current = sessions[session.requestId] ?: return@synchronized SessionCommitResult.VersionConflict
        if (current.latestVersion != expectedVersion ||
            current.latestSubmissionStatus != com.example.relay.rescue.RescueSubmissionStatus.PENDING_DESTINATION.name
        ) {
            return@synchronized SessionCommitResult.VersionConflict
        }
        when (val result = envelopes.store(envelope, receivedAtEpochMillis)) {
            is RescueStoreResult.Stored -> {
                sessions[session.requestId] = session
                SessionCommitResult.Stored(result.record)
            }
            is RescueStoreResult.Rejected -> SessionCommitResult.Rejected(result.reason)
        }
    }

    override fun discardPendingDestination(requestId: String, expectedVersion: Int): Boolean = synchronized(lock) {
        val current = sessions[requestId] ?: return@synchronized false
        if (current.latestVersion != expectedVersion ||
            current.latestSubmissionStatus != com.example.relay.rescue.RescueSubmissionStatus.PENDING_DESTINATION.name ||
            current.terminalStatus != null
        ) {
            return@synchronized false
        }
        sessions.remove(requestId)
        true
    }

    override fun applyVerifiedReceipt(
        key: RescueRequestKey,
        signedReceipt: SignedShelterReceipt,
        shelterSigningPublicKey: com.example.relay.rescue.RescuePublicKey,
        observedAtEpochMillis: Long,
    ): ReceiptApplicationResult = ReceiptApplicationResult.RECORD_NOT_FOUND

    override fun markExpired(requestId: String, requestVersion: Int, observedAtEpochMillis: Long): Boolean = synchronized(lock) {
        val current = sessions[requestId] ?: return@synchronized false
        if (current.latestVersion != requestVersion || current.terminalStatus != null) return@synchronized false
        sessions[requestId] = current.copy(terminalStatus = ActiveRescueSession.SESSION_EXPIRED)
        true
    }

    override fun deleteAcknowledgedTerminal(requestId: String): Boolean = synchronized(lock) {
        val current = sessions[requestId] ?: return@synchronized false
        if (current.terminalStatus == null) return@synchronized false
        sessions.remove(requestId)
        true
    }
}
