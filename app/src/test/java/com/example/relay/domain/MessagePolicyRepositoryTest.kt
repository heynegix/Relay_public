package com.example.relay.domain

import com.example.relay.NOW
import com.example.relay.message
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePolicyRepositoryTest {
    private val clock = MutableClock(NOW)
    private val policy = MessagePolicy(clock)

    @Test
    fun `same message id and body is deduplicated`() = runBlocking {
        val repository = InMemoryMessageRepository()
        assertEquals(InsertResult.Inserted, repository.insert(message()))
        assertEquals(InsertResult.Duplicate, repository.insert(message()))
        assertEquals(1, repository.all().size)
    }

    @Test
    fun `same id with different immutable body is rejected as collision`() = runBlocking {
        val repository = InMemoryMessageRepository()
        repository.insert(message(note = "first"))
        assertEquals(InsertResult.Collision, repository.insert(message(note = "forged")))
        assertEquals("first", (repository.find("message-1")!!.payload as SafetyPayload).note)
    }

    @Test
    fun `same id cannot change lifetime or record type`() = runBlocking {
        val repository = InMemoryMessageRepository()
        val original = message()
        repository.insert(original)

        assertEquals(
            InsertResult.Collision,
            repository.insert(original.copy(lifetimeMs = original.lifetimeMs - 1)),
        )
        assertEquals(
            InsertResult.Collision,
            repository.insert(original.copy(recordType = RelayRecordType.STATUS_CHANGE)),
        )
    }

    @Test
    fun `ttl boundary expires exactly at expiresAt`() {
        val value = message(expiresAt = NOW + 1_000)
        clock.currentMillis = value.expiresAt - 1
        clock.currentElapsedRealtimeMillis = value.expiresAt - 1
        assertTrue(policy.isActive(value))
        clock.currentMillis++
        clock.currentElapsedRealtimeMillis++
        assertFalse(policy.isActive(value))
    }

    @Test
    fun `receiving increments hop and hop limit prevents forwarding`() {
        val inbound = message(hopCount = 1, maxHopCount = 2)
        val received = policy.receive(inbound)!!
        assertEquals(2, received.hopCount)
        assertFalse(policy.canForward(received))
        assertNull(policy.receive(received))
    }

    @Test
    fun `acknowledgement is peer specific and idempotent`() = runBlocking {
        val repository = InMemoryMessageRepository()
        repository.insert(message())
        val first = MessageDelivery("message-1", "peer-B", NOW, "packet-1")
        repository.markAcknowledged(first)
        repository.markAcknowledged(first.copy(acknowledgedAt = NOW + 1))
        assertTrue(repository.wasAcknowledged("message-1", "peer-B"))
        assertFalse(repository.wasAcknowledged("message-1", "peer-C"))
        assertEquals(1, repository.deliveries().size)
    }

    @Test
    fun `receipt for an unknown message is rejected`() = runBlocking {
        val repository = InMemoryMessageRepository()
        val receipt = DeliveryReceipt("receipt-1", "missing", ReceiptType.PEER_RECEIVED, "peer-B", NOW)

        assertEquals(InsertResult.Rejected("unknown message"), repository.insertReceipt(receipt))
        assertTrue(repository.allReceipts().isEmpty())
    }

    @Test
    fun `receipt duplicate and collision semantics remain stable at capacity`() = runBlocking {
        val repository = InMemoryMessageRepository(
            ResourcePolicy(maxStoredReceipts = 1, maxStoredReceiptsPerMessage = 1),
        )
        repository.insert(message(id = "message-1"))
        repository.insert(message(id = "message-2"))
        val receipt = DeliveryReceipt("receipt-1", "message-1", ReceiptType.PEER_RECEIVED, "peer-B", NOW)

        assertEquals(InsertResult.Inserted, repository.insertReceipt(receipt))
        assertEquals(InsertResult.Duplicate, repository.insertReceipt(receipt))
        assertEquals(
            InsertResult.Duplicate,
            repository.insertReceipt(receipt.copy(receiptId = "same-meaning", recordedAt = NOW + 1)),
        )
        assertEquals(
            InsertResult.Collision,
            repository.insertReceipt(receipt.copy(actorId = "peer-C")),
        )
        assertEquals(
            InsertResult.Rejected("max stored receipts"),
            repository.insertReceipt(receipt.copy(receiptId = "receipt-2", messageId = "message-2")),
        )
        assertEquals(1, repository.allReceipts().size)
    }

    @Test
    fun `receipt count per message is bounded independently from global capacity`() = runBlocking {
        val repository = InMemoryMessageRepository(
            ResourcePolicy(maxStoredReceipts = 10, maxStoredReceiptsPerMessage = 2),
        )
        repository.insert(message())
        repository.insertReceipt(DeliveryReceipt("receipt-1", "message-1", ReceiptType.PEER_RECEIVED, "peer-B", NOW))
        repository.insertReceipt(DeliveryReceipt("receipt-2", "message-1", ReceiptType.PEER_RECEIVED, "peer-C", NOW))

        assertEquals(
            InsertResult.Rejected("max receipts per message"),
            repository.insertReceipt(DeliveryReceipt("receipt-3", "message-1", ReceiptType.PEER_RECEIVED, "peer-D", NOW)),
        )
        assertEquals(2, repository.receiptsFor("message-1").size)
    }

    @Test
    fun `verified gateway receipt displaces lower trust receipt at per message capacity`() = runBlocking {
        val repository = InMemoryMessageRepository(
            ResourcePolicy(maxStoredReceipts = 10, maxStoredReceiptsPerMessage = 2),
        )
        repository.insert(message())
        repository.insertReceipt(DeliveryReceipt("peer-1", "message-1", ReceiptType.PEER_RECEIVED, "peer-B", NOW))
        repository.insertReceipt(DeliveryReceipt("peer-2", "message-1", ReceiptType.PEER_RECEIVED, "peer-C", NOW + 1))

        assertEquals(
            InsertResult.Inserted,
            repository.insertReceipt(
                DeliveryReceipt("gateway-1", "message-1", ReceiptType.GATEWAY_RECEIVED, "pc-gateway", NOW + 2),
            ),
        )
        val receipts = repository.receiptsFor("message-1")
        assertEquals(2, receipts.size)
        assertTrue(receipts.any { it.receiptType == ReceiptType.GATEWAY_RECEIVED })
        assertEquals(1, receipts.count { it.receiptType == ReceiptType.PEER_RECEIVED })
    }

    @Test
    fun `verified gateway receipt displaces lower trust receipt at global capacity`() = runBlocking {
        val repository = InMemoryMessageRepository(
            ResourcePolicy(maxStoredReceipts = 2, maxStoredReceiptsPerMessage = 2),
        )
        repository.insert(message(id = "message-1"))
        repository.insert(message(id = "message-2"))
        repository.insertReceipt(DeliveryReceipt("peer-1", "message-1", ReceiptType.PEER_RECEIVED, "peer-B", NOW))
        repository.insertReceipt(DeliveryReceipt("peer-2", "message-2", ReceiptType.PEER_RECEIVED, "peer-C", NOW + 1))

        assertEquals(
            InsertResult.Inserted,
            repository.insertReceipt(
                DeliveryReceipt("gateway-1", "message-2", ReceiptType.GATEWAY_RECEIVED, "pc-gateway", NOW + 2),
            ),
        )
        val receipts = repository.allReceipts()
        assertEquals(2, receipts.size)
        assertTrue(receipts.any { it.receiptType == ReceiptType.GATEWAY_RECEIVED })
    }
}
