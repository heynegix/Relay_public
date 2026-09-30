package com.example.relay.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.relay.data.local.RelayDatabase
import com.example.relay.domain.MessagePriority
import com.example.relay.domain.MessageStatus
import com.example.relay.domain.MessageType
import com.example.relay.domain.RelayMessage
import com.example.relay.domain.SafetyPayload
import com.example.relay.domain.SafetyState
import com.example.relay.rescue.RescueCryptography
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMessageRepositorySignatureTest {
    private lateinit var database: RelayDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), RelayDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun reportSignatureSurvivesRoomRoundTrip() = runBlocking {
        val keys = RescueCryptography.generateReportSigningKeyPair()
        val signed = RescueCryptography.signReport(report(), keys)
        val repository = RoomMessageRepository(database)

        repository.insert(signed)
        val restored = requireNotNull(repository.find(signed.messageId))

        assertTrue(RescueCryptography.verifyReport(restored))
    }

    private fun report() = RelayMessage(
        messageId = "signed-room-report",
        messageType = MessageType.SAFETY,
        createdAt = NOW,
        expiresAt = NOW + 60_000,
        priority = MessagePriority.HIGH,
        originDeviceId = "device-A",
        payload = SafetyPayload(SafetyState.SAFE, 1, "north", "ok"),
        status = MessageStatus.CREATED,
        receivedAt = NOW,
        lifetimeMs = 60_000,
        receivedElapsedRealtimeMs = NOW,
        persistedAtWallClockMs = NOW,
        elapsedRealtimeSessionId = "test-session",
    )

    private companion object { const val NOW = 1_700_000_000_000L }
}
