package com.example.relay.rescue.session

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.relay.data.local.RelayDatabase
import com.example.relay.data.repository.RoomRescueEnvelopeRepository
import com.example.relay.rescue.ReceiptApplicationResult
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueRequestAction
import com.example.relay.rescue.RescueRequestDraft
import com.example.relay.rescue.RescueRequestKey
import com.example.relay.rescue.RescueSubmissionStatus
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.ShelterPublicKeyProvider
import com.example.relay.rescue.ShelterPublicKeys
import com.example.relay.rescue.ShelterReceiptStatus
import com.example.relay.rescue.UnsignedShelterReceipt
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Room contract tests for the durable sender path.  Test-only AES keys are generated in
 * process and never enter an APK asset, SharedPreferences, or source-controlled fixture.
 */
@RunWith(AndroidJUnit4::class)
class RoomActiveRescueSessionStoreTest {
    @Test
    fun sessionEnvelopeUpdateAndCancellationCommitTogetherWithoutPlaintextColumns() = runBlocking {
        fixture().use { fixture ->
            val coordinator = fixture.coordinator()
            val created = coordinator.create(draft()) as RescueSessionOperationResult.Stored
            val raw = fixture.database.activeRescueSessionDao().find(created.value.session.requestId)!!
            assertFalse(raw.sealedRecoveryPayload.decodeToString().contains(PRIVATE_NOTE))

            val updated = coordinator.update(
                created.value.session.requestId,
                created.value.recovery.draft.copy(personCount = 3, freeText = "changed rescue detail"),
            ) as RescueSessionOperationResult.Stored
            assertEquals(2, updated.value.session.latestVersion)
            assertEquals(2, fixture.store.find(updated.value.session.requestId)!!.latestVersion)
            assertNull(fixture.repository.get(RescueRequestKey(updated.value.session.requestId, 1)))
            assertEquals(2, fixture.repository.get(RescueRequestKey(updated.value.session.requestId, 2))!!.envelope.requestVersion)

            val cancelled = coordinator.cancel(updated.value.session.requestId) as RescueSessionOperationResult.Stored
            assertEquals(3, cancelled.value.session.latestVersion)
            assertEquals(RescueRequestAction.CANCELLED, cancelled.value.recovery.draft.action)
            assertEquals(3, fixture.repository.all().single().envelope.requestVersion)
        }
    }

    @Test
    fun verifiedTerminalReceiptUpdatesEnvelopeAndSessionInTheSameRoomTransaction() = runBlocking {
        fixture().use { fixture ->
            val created = fixture.coordinator().create(draft()) as RescueSessionOperationResult.Stored
            val envelope = created.record.envelope
            val receipt = RescueCryptography.signReceipt(
                UnsignedShelterReceipt(
                    receiptId = "receipt-1",
                    envelopeId = envelope.envelopeId,
                    requestId = envelope.requestId,
                    requestVersion = envelope.requestVersion,
                    ciphertextSha256Hex = envelope.ciphertextSha256Hex,
                    shelterId = envelope.destinationShelterId,
                    receivedAtEpochMillis = NOW + 1,
                    status = ShelterReceiptStatus.COMPLETED,
                ),
                fixture.receiptKeyPair.privateKey,
            )

            assertEquals(
                ReceiptApplicationResult.APPLIED,
                fixture.store.applyVerifiedReceipt(
                    RescueRequestKey(envelope.requestId, envelope.requestVersion),
                    receipt,
                    fixture.receiptKeyPair.publicKey,
                    NOW + 2,
                ),
            )
            assertEquals(
                RescueSubmissionStatus.SHELTER_COMPLETED,
                fixture.repository.get(RescueRequestKey(envelope.requestId, envelope.requestVersion))!!.state.submissionStatus,
            )
            assertEquals(
                RescueSubmissionStatus.SHELTER_COMPLETED.name,
                fixture.store.find(envelope.requestId)!!.terminalStatus,
            )
            assertTrue(fixture.store.deleteAcknowledgedTerminal(envelope.requestId))
            assertNull(fixture.store.find(envelope.requestId))
        }
    }

    @Test
    fun concurrentCoordinatorsReserveDistinctVersionsThroughRoomCompareAndSwap() = runBlocking {
        fixture().use { fixture ->
            val first = fixture.coordinator()
            val second = fixture.coordinator()
            val created = first.create(draft()) as RescueSessionOperationResult.Stored
            val base = created.value.recovery.draft

            val results = listOf(
                async(Dispatchers.Default) { first.update(created.value.session.requestId, base.copy(personCount = 2)) },
                async(Dispatchers.Default) { second.update(created.value.session.requestId, base.copy(personCount = 3)) },
            ).awaitAll()

            assertTrue(results.all { it is RescueSessionOperationResult.Stored })
            assertEquals(3, fixture.store.find(created.value.session.requestId)!!.latestVersion)
            assertEquals(3, fixture.repository.all().single().envelope.requestVersion)
        }
    }

    private fun fixture(): Fixture {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, RelayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val receipt = RescueCryptography.generateShelterSigningKeyPair()
        val repository = RoomRescueEnvelopeRepository(database)
        return Fixture(
            database = database,
            repository = repository,
            store = RoomActiveRescueSessionStore(database, repository),
            recipientKeyPair = recipient,
            receiptKeyPair = receipt,
        )
    }

    private fun draft() = RescueRequestDraft(
        requestId = "request-1",
        senderDeviceId = "sender-device",
        destinationShelterId = "shelter-1",
        createdAtEpochMillis = NOW,
        expiresAtEpochMillis = NOW + 60_000,
        urgency = RescueUrgency.URGENT,
        personCount = 1,
        freeText = PRIVATE_NOTE,
    )

    private class Fixture(
        val database: RelayDatabase,
        val repository: RoomRescueEnvelopeRepository,
        val store: RoomActiveRescueSessionStore,
        val recipientKeyPair: com.example.relay.rescue.RescueKeyPair,
        val receiptKeyPair: com.example.relay.rescue.RescueKeyPair,
    ) : AutoCloseable {
        private val cipher = AesGcmRecoveryPayloadCipher(TestKeyProvider(), "TEST ONLY instrumentation-session-key")
        private val envelopeCounter = AtomicInteger()

        fun coordinator() = ActiveRescueSessionCoordinator(
            store = store,
            recoveryCipher = cipher,
            shelterKeyProvider = ShelterPublicKeyProvider {
                ShelterPublicKeys("shelter-1", recipientKeyPair.publicKey, receiptKeyPair.publicKey)
            },
            nowEpochMillis = { NOW },
            newEnvelopeId = { "instrumented-envelope-${envelopeCounter.incrementAndGet()}" },
        )

        override fun close() = database.close()
    }

    private class TestKeyProvider : SessionSecretKeyProvider {
        private val keys = mutableMapOf<String, SecretKey>()
        override fun key(alias: String): SecretKey = synchronized(keys) {
            keys.getOrPut(alias) {
                KeyGenerator.getInstance("AES").apply { init(128, SecureRandom()) }.generateKey()
            }
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val PRIVATE_NOTE = "private rescue detail"
    }
}
