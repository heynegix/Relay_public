package com.example.relay.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.relay.data.local.RelayDatabase
import com.example.relay.domain.DeliveryReceipt
import com.example.relay.domain.InsertResult
import com.example.relay.domain.MessagePriority
import com.example.relay.domain.MessageStatus
import com.example.relay.domain.MessageType
import com.example.relay.domain.ReceiptType
import com.example.relay.domain.RelayMessage
import com.example.relay.domain.ResourcePolicy
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
class RoomMessageRepositoryReceiptTest {
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
    fun receiptForUnknownMessageIsRejected() = runBlocking {
        val repository = RoomMessageRepository(database)

        assertEquals(
            InsertResult.Rejected("unknown message"),
            repository.insertReceipt(receipt("receipt-1", "missing", "peer-B")),
        )
        assertTrue(repository.allReceipts().isEmpty())
    }

    @Test
    fun duplicateAndCollisionRemainClassifiedAtCapacity() = runBlocking {
        val repository = RoomMessageRepository(
            database,
            ResourcePolicy(maxStoredReceipts = 1, maxStoredReceiptsPerMessage = 1),
        )
        repository.insert(message("message-1"))
        repository.insert(message("message-2"))
        val stored = receipt("receipt-1", "message-1", "peer-B")

        assertEquals(InsertResult.Inserted, repository.insertReceipt(stored))
        assertEquals(InsertResult.Duplicate, repository.insertReceipt(stored))
        assertEquals(
            InsertResult.Duplicate,
            repository.insertReceipt(stored.copy(receiptId = "same-meaning", recordedAt = TEST_NOW + 1)),
        )
        assertEquals(InsertResult.Collision, repository.insertReceipt(stored.copy(actorId = "peer-C")))
        assertEquals(
            InsertResult.Rejected("max stored receipts"),
            repository.insertReceipt(receipt("receipt-2", "message-2", "peer-C")),
        )
        assertEquals(1, repository.allReceipts().size)
    }

    @Test
    fun perMessageReceiptLimitIsIndependentFromGlobalLimit() = runBlocking {
        val repository = RoomMessageRepository(
            database,
            ResourcePolicy(maxStoredReceipts = 10, maxStoredReceiptsPerMessage = 2),
        )
        repository.insert(message("message-1"))
        repository.insertReceipt(receipt("receipt-1", "message-1", "peer-B"))
        repository.insertReceipt(receipt("receipt-2", "message-1", "peer-C"))

        assertEquals(
            InsertResult.Rejected("max receipts per message"),
            repository.insertReceipt(receipt("receipt-3", "message-1", "peer-D")),
        )
        assertEquals(2, repository.receiptsFor("message-1").size)
    }

    @Test
    fun verifiedGatewayReceiptDisplacesPeerReceiptAtCapacity() = runBlocking {
        val repository = RoomMessageRepository(
            database,
            ResourcePolicy(maxStoredReceipts = 2, maxStoredReceiptsPerMessage = 2),
        )
        repository.insert(message("message-1"))
        repository.insertReceipt(receipt("peer-1", "message-1", "peer-B"))
        repository.insertReceipt(receipt("peer-2", "message-1", "peer-C").copy(recordedAt = TEST_NOW + 1))

        assertEquals(
            InsertResult.Inserted,
            repository.insertReceipt(
                DeliveryReceipt(
                    "gateway-1",
                    "message-1",
                    ReceiptType.GATEWAY_RECEIVED,
                    "pc-gateway",
                    TEST_NOW + 2,
                ),
            ),
        )
        val receipts = repository.receiptsFor("message-1")
        assertEquals(2, receipts.size)
        assertTrue(receipts.any { it.receiptType == ReceiptType.GATEWAY_RECEIVED })
    }

    private fun message(id: String) = RelayMessage(
        messageId = id,
        messageType = MessageType.SAFETY,
        createdAt = TEST_NOW,
        expiresAt = TEST_NOW + 60_000,
        priority = MessagePriority.HIGH,
        originDeviceId = "origin-A",
        payload = SafetyPayload(SafetyState.SAFE, 1, "north", "ok"),
        status = MessageStatus.CREATED,
        receivedAt = TEST_NOW,
        lifetimeMs = 60_000,
        receivedElapsedRealtimeMs = TEST_NOW,
        persistedAtWallClockMs = TEST_NOW,
        elapsedRealtimeSessionId = "test-session",
    )

    private fun receipt(id: String, messageId: String, actorId: String) = DeliveryReceipt(
        receiptId = id,
        messageId = messageId,
        receiptType = ReceiptType.PEER_RECEIVED,
        actorId = actorId,
        recordedAt = TEST_NOW,
    )

    private companion object {
        const val TEST_NOW = 1_700_000_000_000L
    }
}
