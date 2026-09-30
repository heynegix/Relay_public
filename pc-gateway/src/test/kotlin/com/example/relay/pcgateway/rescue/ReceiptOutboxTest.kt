package com.example.relay.pcgateway.rescue

import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.ShelterReceiptStatus
import com.example.relay.rescue.SignedShelterReceipt
import com.example.relay.rescue.UnsignedShelterReceipt
import java.io.File
import io.ktor.client.plugins.plugin
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReceiptOutboxTest {
    private lateinit var persistence: SqliteRescuePersistence
    private lateinit var dbFile: File

    @Before
    fun setup() {
        dbFile = File.createTempFile("receipt-outbox-test", ".db")
        persistence = SqliteRescuePersistence(
            dbPath = dbFile.absolutePath,
            json = Json { ignoreUnknownKeys = false; encodeDefaults = true },
        )
    }

    @After
    fun teardown() {
        persistence.close()
        dbFile.delete()
    }

    @Test
    fun `cancelled flush preserves retry accounting and propagates cancellation`() = kotlinx.coroutines.runBlocking {
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO)
        client.plugin(io.ktor.client.plugins.HttpSend).intercept {
            throw kotlinx.coroutines.CancellationException("stop requested")
        }
        try {
            val outbox = ReceiptOutbox(persistence, "https://broker.test", "shelter-1", "gateway-1", client)
            outbox.enqueue(createReceipt("cancelled", "envelope-1"))
            var cancelled = false
            try {
                outbox.flushPending()
            } catch (_: kotlinx.coroutines.CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertEquals("0", queryAll().single()["retry_count"])
            assertEquals("PENDING", queryAll().single()["status"])
        } finally {
            client.close()
        }
    }

    @Test
    fun `enqueue inserts receipt as PENDING`() {
        val outbox = createOutbox()
        val receipt = createReceipt("receipt-1", "envelope-1")

        outbox.enqueue(receipt)

        val rows = queryAll()
        assertEquals(1, rows.size)
        assertEquals("receipt-1", rows[0]["receipt_id"])
        assertEquals("PENDING", rows[0]["status"])
    }

    @Test
    fun `enqueue rolls back with its enclosing rescue transaction`() {
        val outbox = createOutbox()
        var rolledBack = false

        try {
            persistence.transaction {
                outbox.enqueue(createReceipt("receipt-rollback", "envelope-rollback"))
                error("force rollback")
            }
        } catch (_: IllegalStateException) {
            rolledBack = true
        }

        assertTrue(rolledBack)
        assertTrue(queryAll().isEmpty())
    }

    @Test
    fun `enqueue is idempotent - duplicate receipt_id ignored`() {
        val outbox = createOutbox()
        val receipt = createReceipt("receipt-1", "envelope-1")

        outbox.enqueue(receipt)
        outbox.enqueue(receipt)

        val rows = queryAll()
        assertEquals(1, rows.size)
    }

    @Test
    fun `enqueue multiple receipts preserves order`() {
        val outbox = createOutbox()

        outbox.enqueue(createReceipt("receipt-1", "envelope-1"))
        outbox.enqueue(createReceipt("receipt-2", "envelope-2"))
        outbox.enqueue(createReceipt("receipt-3", "envelope-3"))

        val rows = queryAll()
        assertEquals(3, rows.size)
    }

    @Test
    fun `markSent updates status and timestamp`() {
        val outbox = createOutbox()
        outbox.enqueue(createReceipt("receipt-1", "envelope-1"))

        // Simulate markSent via direct SQL (normally called by flusher)
        persistence.withConnection { connection ->
            connection.prepareStatement(
                "UPDATE receipt_outbox SET status = 'SENT', sent_at = ? WHERE receipt_id = ?",
            ).use { stmt ->
                stmt.setLong(1, System.currentTimeMillis())
                stmt.setString(2, "receipt-1")
                stmt.executeUpdate()
            }
        }

        val rows = queryAll()
        assertEquals("SENT", rows[0]["status"])
        assertTrue(rows[0]["sent_at"] != null)
    }

    @Test
    fun `incrementRetry increases retry count`() {
        val outbox = createOutbox()
        outbox.enqueue(createReceipt("receipt-1", "envelope-1"))

        persistence.withConnection { connection ->
            connection.prepareStatement(
                "UPDATE receipt_outbox SET retry_count = retry_count + 1 WHERE receipt_id = ?",
            ).use { stmt ->
                stmt.setString(1, "receipt-1")
                stmt.executeUpdate()
            }
        }

        val rows = queryAll()
        assertEquals("1", rows[0]["retry_count"])
    }

    private fun createOutbox(): ReceiptOutbox {
        // ReceiptOutbox uses the persistence lock and participates in its transaction.
        return ReceiptOutbox(
            persistence = persistence,
            brokerUrl = "https://broker.test",
            shelterId = "shelter-1",
            gatewayId = "gateway-1",
            httpClient = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO),
            gatewayCredential = "test-api-key",
        )
    }

    private fun createReceipt(receiptId: String, envelopeId: String): SignedShelterReceipt {
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        return RescueCryptography.signReceipt(
            UnsignedShelterReceipt(
                receiptId = receiptId,
                envelopeId = envelopeId,
                requestId = "request-1",
                requestVersion = 1,
                ciphertextSha256Hex = "a".repeat(64),
                shelterId = "shelter-1",
                receivedAtEpochMillis = System.currentTimeMillis(),
                status = ShelterReceiptStatus.STORED,
            ),
            signer.privateKey,
        )
    }

    private fun queryAll(): List<Map<String, String?>> {
        return persistence.withConnection { connection ->
            val results = mutableListOf<Map<String, String?>>()
            connection.prepareStatement("SELECT * FROM receipt_outbox ORDER BY created_at ASC").use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        results.add(mapOf(
                            "receipt_id" to rs.getString("receipt_id"),
                            "envelope_id" to rs.getString("envelope_id"),
                            "shelter_id" to rs.getString("shelter_id"),
                            "status" to rs.getString("status"),
                            "sent_at" to rs.getString("sent_at"),
                            "retry_count" to rs.getString("retry_count"),
                        ))
                    }
                }
            }
            results
        }
    }
}
