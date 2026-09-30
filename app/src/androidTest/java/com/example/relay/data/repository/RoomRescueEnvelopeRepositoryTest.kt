package com.example.relay.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.relay.data.local.RelayDatabase
import com.example.relay.rescue.RescueCreationResult
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueRequestCreator
import com.example.relay.rescue.RescueRequestDraft
import com.example.relay.rescue.RescueRequestKey
import com.example.relay.rescue.RescueStoreRejection
import com.example.relay.rescue.RescueStoreResult
import com.example.relay.rescue.RescueSupportNeed
import com.example.relay.rescue.RescueUrgency
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRescueEnvelopeRepositoryTest {
    private lateinit var context: Context
    private var database: RelayDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun encryptedEnvelopeSurvivesDatabaseReopenAndDuplicateIsRejected() {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val firstDatabase = openDatabase()
        val firstRepository = RoomRescueEnvelopeRepository(firstDatabase)
        val created = RescueRequestCreator(firstRepository).create(
            draft = RescueRequestDraft(
                requestId = "request-room-1",
                senderDeviceId = "member-device",
                destinationShelterId = "shelter-1",
                createdAtEpochMillis = TEST_NOW,
                expiresAtEpochMillis = TEST_NOW + 86_400_000,
                urgency = RescueUrgency.URGENT,
                personCount = 2,
                injured = true,
                supportNeeds = setOf(RescueSupportNeed.WATER),
                freeText = PRIVATE_NOTE,
            ),
            shelterPublicKey = recipient.publicKey,
            envelopeId = "envelope-room-1",
        ) as RescueCreationResult.Stored
        val key = RescueRequestKey("request-room-1", 1)
        val persistedJson = requireNotNull(firstDatabase.rescueDao().find(key.requestId, key.requestVersion)).envelopeJson
        assertFalse(persistedJson.contains(PRIVATE_NOTE))

        val duplicate = firstRepository.store(created.record.envelope, TEST_NOW + 1_000)
        assertEquals(
            RescueStoreRejection.DUPLICATE,
            (duplicate as RescueStoreResult.Rejected).reason,
        )

        firstDatabase.close()
        database = null
        val reopenedRepository = RoomRescueEnvelopeRepository(openDatabase())

        val restored = reopenedRepository.get(key)
        assertNotNull(restored)
        assertEquals(created.record.envelope, restored!!.envelope)
        assertEquals(created.record.state, restored.state)
        assertEquals(1, reopenedRepository.all().size)
        val decrypted = RescueCryptography.decrypt(restored.envelope, recipient.privateKey)
        assertEquals(PRIVATE_NOTE, decrypted.freeText)
        assertTrue(decrypted.injured)
    }

    private fun openDatabase(): RelayDatabase = Room.databaseBuilder(
        context,
        RelayDatabase::class.java,
        DATABASE_NAME,
    ).build().also { database = it }

    private companion object {
        const val DATABASE_NAME = "relay-rescue-repository-test.db"
        const val TEST_NOW = 1_700_000_000_000L
        const val PRIVATE_NOTE = "倒壊した建物の北側にいます"
    }
}
