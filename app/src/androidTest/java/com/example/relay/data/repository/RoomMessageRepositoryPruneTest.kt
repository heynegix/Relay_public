package com.example.relay.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.relay.data.local.RelayDatabase
import com.example.relay.domain.DeliveryReceipt
import com.example.relay.domain.InsertResult
import com.example.relay.domain.MessageDelivery
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MessagePriority
import com.example.relay.domain.MessageStatus
import com.example.relay.domain.MessageType
import com.example.relay.domain.MutableClock
import com.example.relay.domain.ReceiptType
import com.example.relay.domain.RelayMessage
import com.example.relay.domain.SafetyPayload
import com.example.relay.domain.SafetyState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMessageRepositoryPruneTest {
    private lateinit var database: RelayDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RelayDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun expiredMessageAndItsDeliveryStateAreDeletedInOnePrune() = runBlocking {
        val repository = RoomMessageRepository(database)
        val expired = RelayMessage(
            messageId = "expired-critical",
            messageType = MessageType.SAFETY,
            createdAt = TEST_NOW,
            expiresAt = TEST_NOW + 1_000,
            priority = MessagePriority.CRITICAL,
            originDeviceId = "origin-A",
            payload = SafetyPayload(SafetyState.SAFE, 0, "north", "ok"),
            status = MessageStatus.CREATED,
            receivedAt = TEST_NOW,
            lifetimeMs = 1_000,
            receivedElapsedRealtimeMs = TEST_NOW,
            persistedAtWallClockMs = TEST_NOW,
            elapsedRealtimeSessionId = "test-session",
        )
        assertEquals(InsertResult.Inserted, repository.insert(expired))
        repository.markAcknowledged(MessageDelivery(expired.messageId, "peer-B", TEST_NOW, "packet-1"))
        assertEquals(
            InsertResult.Inserted,
            repository.insertReceipt(
                DeliveryReceipt("receipt-1", expired.messageId, ReceiptType.PEER_RECEIVED, "peer-B", TEST_NOW),
            ),
        )
        val clock = MutableClock(TEST_NOW + 1_000).apply {
            currentElapsedRealtimeMillis = TEST_NOW + 1_000
        }

        assertEquals(1, repository.pruneExpired(MessagePolicy(clock)))

        assertTrue(repository.all().isEmpty())
        assertTrue(repository.deliveries().isEmpty())
        assertTrue(repository.allReceipts().isEmpty())
    }

    private companion object {
        const val TEST_NOW = 1_700_000_000_000L
    }
}
