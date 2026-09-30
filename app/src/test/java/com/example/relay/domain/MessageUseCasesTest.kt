package com.example.relay.domain

import com.example.relay.NOW
import com.example.relay.message
import com.example.relay.rescue.ReportSignature
import com.example.relay.rescue.RescueKeyAlgorithm
import com.example.relay.rescue.RescuePublicKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MessageUseCasesTest {
    @Test
    fun `production injection point signs safety reports before persistence`() = runBlocking {
        val clock = MutableClock(NOW)
        val repository = InMemoryMessageRepository()
        var calls = 0
        val signer = ReportSigner { report ->
            calls++
            report.copy(
                reportSignature = ReportSignature(
                    signerKeyId = "device-key",
                    publicKey = RescuePublicKey("device-key", RescueKeyAlgorithm.ECDSA_P256_SHA256, "public"),
                    signatureBase64 = "A".repeat(64),
                ),
            )
        }
        val created = CreateSafetyMessageUseCase(
            repository,
            MessagePolicy(clock),
            clock,
            "device-A",
            MessageIdGenerator { "signed-safety" },
            reportSigner = signer,
        )(SafetyState.SAFE, 1, "north", "ok")

        assertEquals(1, calls)
        assertNotNull(created.reportSignature)
        assertEquals(created, repository.find("signed-safety"))
    }

    @Test
    fun `safety and supply use cases create validated local messages`() = runBlocking {
        val clock = MutableClock(NOW)
        val repository = InMemoryMessageRepository()
        val policy = MessagePolicy(clock)
        val safety = CreateSafetyMessageUseCase(repository, policy, clock, "device-A", MessageIdGenerator { "safety-1" })
        val supply = CreateSupplyMessageUseCase(repository, policy, clock, "device-A", MessageIdGenerator { "supply-1" })

        assertEquals(MessageType.SAFETY, safety(SafetyState.SAFE, 2, " north ", " ok ").messageType)
        assertEquals(MessageType.SUPPLY, supply(SupplyKind.WATER, 10, "north", "needed").messageType)
        assertEquals(2, GetRegionalMessagesUseCase(repository, policy)().size)
        assertTrue(repository.all().all { it.hopCount == 0 && it.status == MessageStatus.CREATED })
    }

    @Test(expected = LegacyMessageCreationException.InvalidMessage::class)
    fun `invalid form input is rejected before insertion`() {
        runBlocking {
            val clock = MutableClock(NOW)
            val repository = InMemoryMessageRepository()
            val policy = MessagePolicy(clock)
            CreateSupplyMessageUseCase(repository, policy, clock, "device-A", MessageIdGenerator { "bad" })(
                SupplyKind.WATER, 0, "north", "needed",
            )
        }
    }

    @Test
    fun `expired records are pruned with delivery state before a full repository accepts a report`() = runBlocking {
        val clock = MutableClock(NOW)
        val repository = InMemoryMessageRepository(
            ResourcePolicy(maxStoredMessages = 1, maxStoredMessagesPerOrigin = 1),
        )
        val policy = MessagePolicy(clock)
        val expired = message(
            id = "expired-critical",
            priority = MessagePriority.CRITICAL,
            expiresAt = NOW + 1_000,
        )
        assertEquals(InsertResult.Inserted, repository.insert(expired))
        repository.markAcknowledged(MessageDelivery(expired.messageId, "peer-B", NOW, "packet-1"))
        assertEquals(
            InsertResult.Inserted,
            repository.insertReceipt(
                DeliveryReceipt("receipt-1", expired.messageId, ReceiptType.PEER_RECEIVED, "peer-B", NOW),
            ),
        )
        clock.currentMillis = NOW + 1_000
        clock.currentElapsedRealtimeMillis = NOW + 1_000

        val created = CreateSafetyMessageUseCase(
            repository,
            policy,
            clock,
            "device-A",
            MessageIdGenerator { "replacement" },
        )(SafetyState.SAFE, 0, "north", "ok")

        assertEquals("replacement", created.messageId)
        assertNull(repository.find(expired.messageId))
        assertTrue(repository.deliveries().isEmpty())
        assertTrue(repository.allReceipts().isEmpty())
        assertEquals(listOf(created), repository.all())
    }

    @Test
    fun `active records at capacity return a classified domain failure instead of check failure`() = runBlocking {
        val clock = MutableClock(NOW)
        val repository = InMemoryMessageRepository(ResourcePolicy(maxStoredMessages = 1))
        val policy = MessagePolicy(clock)
        repository.insert(message(id = "active"))
        val create = CreateSafetyMessageUseCase(
            repository,
            policy,
            clock,
            "device-B",
            MessageIdGenerator { "new-report" },
        )

        try {
            create(SafetyState.SAFE, 0, "north", "ok")
            fail("Expected storage rejection")
        } catch (failure: LegacyMessageCreationException.StorageRejected) {
            assertEquals("max stored messages", failure.reason)
        }

        assertEquals(listOf("active"), repository.all().map { it.messageId })
    }
}
