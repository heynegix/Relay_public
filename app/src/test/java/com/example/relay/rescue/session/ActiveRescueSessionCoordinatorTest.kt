package com.example.relay.rescue.session

import com.example.relay.location.GeoFix
import com.example.relay.location.LocationProvider
import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.InMemoryRescueEnvelopeRepository
import com.example.relay.rescue.ReceiptApplicationResult
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueEnvelopeState
import com.example.relay.rescue.RescueRequestDraft
import com.example.relay.rescue.RescueRequestKey
import com.example.relay.rescue.RescuePrivateKey
import com.example.relay.rescue.RescueStoreRejection
import com.example.relay.rescue.RescueStoreResult
import com.example.relay.rescue.RescueSubmissionStatus
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.ShelterPublicKeyProvider
import com.example.relay.rescue.ShelterPublicKeys
import com.example.relay.rescue.SignedShelterReceipt
import com.example.relay.rescue.StoredRescueRecord
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveRescueSessionCoordinatorTest {
    @Test
    fun `creates restores updates and cancels without plaintext session storage`() = runBlocking {
        val fixture = fixture()
        val created = fixture.coordinator.create(draft()) as RescueSessionOperationResult.Stored
        assertEquals(1, created.value.session.latestVersion)
        assertFalse(created.value.session.sealedRecoveryPayload.decodeToString().contains(PRIVATE_NOTE))

        val restored = fixture.coordinator.restoreLatest() as RescueSessionRestoreResult.Restored
        assertEquals(PRIVATE_NOTE, restored.value.recovery.draft.freeText)
        val updated = fixture.coordinator.update(
            restored.value.session.requestId,
            restored.value.recovery.draft.copy(personCount = 3, freeText = "changed details"),
        ) as RescueSessionOperationResult.Stored
        assertEquals(2, updated.value.session.latestVersion)

        val cancelled = fixture.coordinator.cancel(updated.value.session.requestId) as RescueSessionOperationResult.Stored
        assertEquals(3, cancelled.value.session.latestVersion)
        assertEquals(com.example.relay.rescue.RescueRequestAction.CANCELLED, cancelled.value.recovery.draft.action)
        assertEquals(RescueSessionOperationResult.CancelledAlready, fixture.coordinator.cancel(updated.value.session.requestId))
        assertEquals(3, fixture.deliveryNotifications)
    }

    @Test
    fun `a live session is restored instead of creating a competing request`() = runBlocking {
        val fixture = fixture()
        val created = fixture.coordinator.create(draft()) as RescueSessionOperationResult.Stored

        val second = fixture.coordinator.create(draft(requestId = "request-2"))

        assertTrue(second is RescueSessionOperationResult.ActiveSessionExists)
        assertEquals(created.value.session.requestId, (second as RescueSessionOperationResult.ActiveSessionExists).value.session.requestId)
        assertEquals(listOf("request-1"), fixture.store.all().map { it.requestId })
        assertEquals(1, fixture.envelopes.all().size)
    }

    @Test
    fun `two coordinators allocate distinct versions through compare and swap`() = runBlocking {
        val fixture = fixture()
        val created = fixture.coordinator.create(draft()) as RescueSessionOperationResult.Stored
        val secondCoordinator = fixture.newCoordinator()
        val base = created.value.recovery.draft

        val results = listOf(
            async { fixture.coordinator.update(created.value.session.requestId, base.copy(personCount = 2)) },
            async { secondCoordinator.update(created.value.session.requestId, base.copy(personCount = 3)) },
        ).awaitAll()

        assertTrue(results.all { it is RescueSessionOperationResult.Stored })
        assertEquals(3, fixture.store.find(created.value.session.requestId)!!.latestVersion)
        assertEquals(3, fixture.envelopes.all().single().envelope.requestVersion)
    }

    @Test
    fun `terminal receipt state or expiry prevents further updates`() = runBlocking {
        val fixture = fixture()
        val created = fixture.coordinator.create(draft()) as RescueSessionOperationResult.Stored
        fixture.store.forceTerminal(created.value.session.requestId, RescueSubmissionStatus.SHELTER_COMPLETED.name)
        assertEquals(
            RescueSessionOperationResult.Terminal,
            fixture.coordinator.update(created.value.session.requestId, created.value.recovery.draft),
        )

        val expiring = fixture(now = 1_700_000_000_000L, lifetimeMillis = 60_000)
        val expiringCreated = expiring.coordinator.create(draft(requestId = "expiry-request")) as RescueSessionOperationResult.Stored
        expiring.now += 60_001
        assertEquals(
            RescueSessionOperationResult.Expired,
            expiring.coordinator.update(expiringCreated.value.session.requestId, expiringCreated.value.recovery.draft),
        )
        assertEquals(ActiveRescueSession.SESSION_EXPIRED, expiring.store.find("expiry-request")!!.terminalStatus)
    }

    @Test
    fun `terminal recovery is retained until acknowledgement then securely removed`() = runBlocking {
        val fixture = fixture()
        val created = fixture.coordinator.create(draft()) as RescueSessionOperationResult.Stored
        fixture.store.forceTerminal(created.value.session.requestId, RescueSubmissionStatus.SHELTER_COMPLETED.name)

        assertTrue(fixture.coordinator.restore(created.value.session.requestId) is RescueSessionRestoreResult.Restored)
        assertTrue(fixture.coordinator.acknowledgeTerminalResult(created.value.session.requestId))
        assertEquals(RescueSessionRestoreResult.None, fixture.coordinator.restore(created.value.session.requestId))
        assertFalse(fixture.coordinator.acknowledgeTerminalResult(created.value.session.requestId))
    }

    @Test
    fun `tampered recovery ciphertext is retained and fails closed`() = runBlocking {
        val fixture = fixture()
        val created = fixture.coordinator.create(draft()) as RescueSessionOperationResult.Stored
        fixture.store.tamper(created.value.session.requestId)

        assertEquals(RescueSessionRestoreResult.Corrupt(created.value.session.requestId), fixture.coordinator.restoreLatest())
        assertEquals(
            RescueSessionOperationResult.Corrupt,
            fixture.coordinator.update(created.value.session.requestId, draft()),
        )
    }

    @Test
    fun `failed atomic commit leaves neither session nor envelope behind`() = runBlocking {
        val fixture = fixture()
        fixture.store.failWrites = true

        assertEquals(RescueSessionOperationResult.StorageFailure, fixture.coordinator.create(draft()))
        assertTrue(fixture.store.all().isEmpty())
        assertTrue(fixture.envelopes.all().isEmpty())
        assertEquals(0, fixture.deliveryNotifications)
    }

    @Test
    fun `SOS is durably queued without a key then materialized only after trusted resolution`() = runBlocking {
        val fixture = fixture(keysAvailable = false)

        val pending = fixture.coordinator.create(draft()) as RescueSessionOperationResult.PendingDestination

        assertEquals(RescueSubmissionStatus.PENDING_DESTINATION, pending.value.submissionStatus)
        assertTrue(fixture.envelopes.all().isEmpty())
        assertFalse(pending.value.session.sealedRecoveryPayload.decodeToString().contains(PRIVATE_NOTE))
        assertEquals(1, fixture.deliveryNotifications)

        fixture.configuredKeys = fixture.keys
        assertEquals(1, fixture.coordinator.resolvePendingDestinations())

        val envelope = fixture.envelopes.all().single().envelope
        assertEquals("shelter-1", envelope.destinationShelterId)
        assertEquals(PRIVATE_NOTE, RescueCryptography.decrypt(envelope, fixture.recipientPrivateKey).freeText)
        val restored = fixture.coordinator.restoreLatest() as RescueSessionRestoreResult.Restored
        assertEquals(RescueSubmissionStatus.PENDING, restored.value.submissionStatus)
        assertEquals(2, fixture.deliveryNotifications)
    }

    @Test
    fun `pending SOS can be discarded before any envelope is emitted`() = runBlocking {
        val fixture = fixture(keysAvailable = false)
        val pending = fixture.coordinator.create(draft()) as RescueSessionOperationResult.PendingDestination

        assertEquals(
            RescueSessionOperationResult.PendingDestinationDiscarded,
            fixture.coordinator.cancel(pending.value.session.requestId),
        )
        assertTrue(fixture.store.all().isEmpty())
        assertTrue(fixture.envelopes.all().isEmpty())
    }

    @Test
    fun `tracking consent is off by default and a location update is refused before opt-in`() = runBlocking {
        val location = MutableLocationProvider(GeoFix(0.0, 0.0, 5f, 1_000))
        val fixture = fixture(locationProvider = location)
        val created = fixture.coordinator.create(draft()) as RescueSessionOperationResult.Stored
        val requestId = created.value.session.requestId

        assertEquals(ActiveRescueSession.TRACKING_DISABLED, created.value.session.trackingMode)
        val restored = fixture.coordinator.restore(requestId) as RescueSessionRestoreResult.Restored
        assertFalse(restored.value.recovery.trackingEnabled)

        // Without consent the update is a no-op: no fresh version, no new envelope.
        assertEquals(
            RescueSessionOperationResult.TrackingNotConsented,
            fixture.coordinator.recordConsentedLocationUpdate(requestId),
        )
        assertEquals(1, fixture.store.find(requestId)!!.latestVersion)
        assertEquals(1, fixture.envelopes.all().size)
    }

    @Test
    fun `consent durably enables tracking and a consented update emits a fresh encrypted fix`() = runBlocking {
        val location = MutableLocationProvider(GeoFix(0.0, 0.0, 5f, 1_000))
        val fixture = fixture(locationProvider = location)
        val created = fixture.coordinator.create(draft()) as RescueSessionOperationResult.Stored
        val requestId = created.value.session.requestId

        val consented = fixture.coordinator.setTrackingConsent(requestId, true) as RescueSessionOperationResult.Stored
        assertEquals(2, consented.value.session.latestVersion)
        assertEquals(ActiveRescueSession.TRACKING_ENABLED, consented.value.session.trackingMode)
        assertTrue(consented.value.recovery.trackingEnabled)

        // Durable: a fresh coordinator (process restart) still sees consent and the enabled mode.
        val afterRestart = fixture.newCoordinator().restoreLatest() as RescueSessionRestoreResult.Restored
        assertTrue(afterRestart.value.recovery.trackingEnabled)
        assertEquals(ActiveRescueSession.TRACKING_ENABLED, afterRestart.value.session.trackingMode)

        // A newer fix arrives; the consented update publishes it as the next encrypted version.
        location.fix = GeoFix(0.001, 0.002, 3f, 2_000)
        val updated = fixture.coordinator.recordConsentedLocationUpdate(requestId) as RescueSessionOperationResult.Stored
        assertEquals(3, updated.value.session.latestVersion)
        assertEquals(ActiveRescueSession.TRACKING_ENABLED, updated.value.session.trackingMode)
        val envelope = fixture.envelopes.all().maxByOrNull { it.envelope.requestVersion }!!.envelope
        val payload = RescueCryptography.decrypt(envelope, fixture.recipientPrivateKey)
        assertEquals(0.001, payload.location!!.latitude!!, 1e-9)
        assertEquals(0.002, payload.location!!.longitude!!, 1e-9)
    }

    @Test
    fun `withdrawing consent disables tracking, stops updates, and is idempotent`() = runBlocking {
        val location = MutableLocationProvider(GeoFix(0.0, 0.0, 5f, 1_000))
        val fixture = fixture(locationProvider = location)
        val created = fixture.coordinator.create(draft()) as RescueSessionOperationResult.Stored
        val requestId = created.value.session.requestId
        fixture.coordinator.setTrackingConsent(requestId, true) as RescueSessionOperationResult.Stored

        val withdrawn = fixture.coordinator.setTrackingConsent(requestId, false) as RescueSessionOperationResult.Stored
        assertEquals(ActiveRescueSession.TRACKING_DISABLED, withdrawn.value.session.trackingMode)
        assertFalse(withdrawn.value.recovery.trackingEnabled)

        val versionAfterWithdraw = fixture.store.find(requestId)!!.latestVersion
        assertEquals(
            RescueSessionOperationResult.TrackingNotConsented,
            fixture.coordinator.recordConsentedLocationUpdate(requestId),
        )
        assertEquals(versionAfterWithdraw, fixture.store.find(requestId)!!.latestVersion)

        // Re-applying the same decision changes nothing: no new version and no new envelope.
        val envelopesBefore = fixture.envelopes.all().size
        val unchanged = fixture.coordinator.setTrackingConsent(requestId, false)
        assertTrue(unchanged is RescueSessionOperationResult.TrackingConsentUnchanged)
        assertEquals(versionAfterWithdraw, fixture.store.find(requestId)!!.latestVersion)
        assertEquals(envelopesBefore, fixture.envelopes.all().size)
    }

    private fun fixture(
        now: Long = 1_700_000_000_000L,
        lifetimeMillis: Long = 3L * 24 * 60 * 60 * 1_000,
        keysAvailable: Boolean = true,
        locationProvider: LocationProvider? = null,
    ): Fixture {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val receipt = RescueCryptography.generateShelterSigningKeyPair()
        val keys = ShelterPublicKeys("shelter-1", recipient.publicKey, receipt.publicKey)
        val store = FakeSessionStore()
        val cipher = AesGcmRecoveryPayloadCipher(TestKeyProvider(), "TEST ONLY session-key")
        return Fixture(store, cipher, keys, recipient.privateKey, now, lifetimeMillis, keysAvailable, locationProvider)
    }

    private fun draft(requestId: String = "request-1") = RescueRequestDraft(
        requestId = requestId,
        senderDeviceId = "member-device",
        destinationShelterId = "ignored-before-key-resolution",
        createdAtEpochMillis = 1,
        expiresAtEpochMillis = 2,
        urgency = RescueUrgency.URGENT,
        personCount = 1,
        freeText = PRIVATE_NOTE,
    )

    private class Fixture(
        val store: FakeSessionStore,
        private val cipher: RecoveryPayloadCipher,
        val keys: ShelterPublicKeys,
        val recipientPrivateKey: RescuePrivateKey,
        var now: Long,
        private val lifetimeMillis: Long,
        keysAvailable: Boolean,
        private val locationProvider: LocationProvider? = null,
    ) {
        var deliveryNotifications: Int = 0
        var configuredKeys: ShelterPublicKeys? = keys.takeIf { keysAvailable }
        val envelopes: InMemoryRescueEnvelopeRepository get() = store.envelopes
        val coordinator: ActiveRescueSessionCoordinator get() = newCoordinator()
        fun newCoordinator() = ActiveRescueSessionCoordinator(
            store = store,
            recoveryCipher = cipher,
            shelterKeyProvider = ShelterPublicKeyProvider { configuredKeys },
            locationProvider = locationProvider,
            nowEpochMillis = { now },
            requestLifetimeMillis = lifetimeMillis,
            newEnvelopeId = { "envelope-${++envelopeCounter}" },
            deliveryNotifier = RescueDeliveryNotifier { deliveryNotifications++ },
        )

        private var envelopeCounter = 0
    }

    private class MutableLocationProvider(var fix: GeoFix?) : LocationProvider {
        override suspend fun currentFix(timeoutMs: Long): GeoFix? = fix
    }

    private class TestKeyProvider : SessionSecretKeyProvider {
        private val keys = mutableMapOf<String, SecretKey>()
        override fun key(alias: String): SecretKey = keys.getOrPut(alias) {
            KeyGenerator.getInstance("AES").apply { init(256, SecureRandom()) }.generateKey()
        }
    }

    private class FakeSessionStore : ActiveRescueSessionStore {
        val envelopes = InMemoryRescueEnvelopeRepository()
        private val sessions = linkedMapOf<String, ActiveRescueSession>()
        private val lock = Any()
        var failWrites: Boolean = false

        override fun all(): List<ActiveRescueSession> = synchronized(lock) { sessions.values.toList() }
        override fun find(requestId: String): ActiveRescueSession? = synchronized(lock) { sessions[requestId] }

        override fun createAtomically(
            session: ActiveRescueSession,
            envelope: EncryptedRescueEnvelope,
            receivedAtEpochMillis: Long,
        ): SessionCommitResult = synchronized(lock) {
            if (failWrites) return@synchronized SessionCommitResult.Rejected(RescueStoreRejection.EXCEEDS_BYTE_LIMIT)
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
            if (failWrites) return@synchronized SessionCommitResult.Rejected(RescueStoreRejection.EXCEEDS_BYTE_LIMIT)
            if (sessions.containsKey(session.requestId)) return@synchronized SessionCommitResult.VersionConflict
            sessions[session.requestId] = session
            SessionCommitResult.PendingDestinationStored
        }

        override fun updateAtomically(
            expectedVersion: Int,
            session: ActiveRescueSession,
            envelope: EncryptedRescueEnvelope,
            receivedAtEpochMillis: Long,
        ): SessionCommitResult = synchronized(lock) {
            if (failWrites) return@synchronized SessionCommitResult.Rejected(RescueStoreRejection.EXCEEDS_BYTE_LIMIT)
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
            if (failWrites) return@synchronized SessionCommitResult.Rejected(RescueStoreRejection.EXCEEDS_BYTE_LIMIT)
            val current = sessions[session.requestId]
                ?: return@synchronized SessionCommitResult.VersionConflict
            if (current.latestVersion != expectedVersion ||
                current.latestSubmissionStatus != RescueSubmissionStatus.PENDING_DESTINATION.name
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
                current.latestSubmissionStatus != RescueSubmissionStatus.PENDING_DESTINATION.name ||
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
            sessions[requestId] = current.copy(terminalStatus = ActiveRescueSession.SESSION_EXPIRED, updatedAtEpochMillis = observedAtEpochMillis)
            true
        }

        override fun deleteAcknowledgedTerminal(requestId: String): Boolean = synchronized(lock) {
            val current = sessions[requestId] ?: return@synchronized false
            if (current.terminalStatus == null) return@synchronized false
            sessions.remove(requestId)
            true
        }

        fun forceTerminal(requestId: String, terminal: String) = synchronized(lock) {
            sessions[requestId] = requireNotNull(sessions[requestId]).copy(terminalStatus = terminal)
        }

        fun tamper(requestId: String) = synchronized(lock) {
            val current = requireNotNull(sessions[requestId])
            sessions[requestId] = current.copy(
                sealedRecoveryPayload = current.sealedRecoveryPayload.copyOf().also {
                    it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
                },
            )
        }
    }

    private companion object {
        const val PRIVATE_NOTE = "private rescue details"
    }
}
