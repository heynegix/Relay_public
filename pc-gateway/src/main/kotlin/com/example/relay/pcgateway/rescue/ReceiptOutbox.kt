package com.example.relay.pcgateway.rescue

import com.example.relay.rescue.SignedShelterReceipt
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.ContentType.Application.Json
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Wire model matching Broker's BrokerReceiptUpload. */
@Serializable
data class BrokerReceiptUpload(
    val receipt: SignedShelterReceipt,
    val gatewayId: String,
)

/**
 * Transactional Receipt Outbox for the PC Gateway.
 * Uses the SAME database connection as SqliteRescuePersistence so that
 * receipt enqueue is atomic with rescue state changes (same transaction).
 *
 * Ensures signed receipts (STORED/ACCEPTED/RESPONDING/COMPLETED/REJECTED) are
 * reliably delivered to the Broker via at-least-once semantics.
 * Broker deduplicates by receipt_id (idempotent).
 *
 * The flusher only marks SENT after receiving 2xx from Broker. A 422 can mean the envelope arrived
 * over LAN/BLE before the Broker; it remains pending with a delay so the receipt is retried after
 * the ciphertext is eventually uploaded.
 */
class ReceiptOutbox(
    private val persistence: SqliteRescuePersistence,
    private val brokerUrl: String,
    private val shelterId: String,
    private val gatewayId: String,
    private val httpClient: HttpClient,
    /** Per-Gateway, per-shelter Broker credential; never stored in the outbox database. */
    private val gatewayCredential: String? = null,
    private val flushIntervalMs: Long = 5_000L,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    init {
        require(flushIntervalMs > 0) { "flushIntervalMs must be positive" }
        createTable()
    }

    private fun createTable() {
        persistence.withConnection { connection ->
            connection.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """CREATE TABLE IF NOT EXISTS receipt_outbox (
                        receipt_id TEXT NOT NULL PRIMARY KEY,
                        envelope_id TEXT NOT NULL,
                        shelter_id TEXT NOT NULL,
                        receipt_json TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        created_at INTEGER NOT NULL,
                        sent_at INTEGER,
                        retry_count INTEGER NOT NULL DEFAULT 0,
                        last_attempt_at INTEGER
                    )""",
                )
                runCatching { stmt.executeUpdate("ALTER TABLE receipt_outbox ADD COLUMN last_attempt_at INTEGER") }
            }
        }
    }

    /**
     * Enqueues a signed receipt for delivery to the Broker.
     * Called within the same transaction as the status change (via onReceiptIssued callback
     * inside persistence.transaction{}), ensuring atomicity.
     */
    fun enqueue(receipt: SignedShelterReceipt) {
        persistence.withConnection { connection ->
            connection.prepareStatement(
                "INSERT OR IGNORE INTO receipt_outbox (receipt_id, envelope_id, shelter_id, receipt_json, status, created_at) VALUES (?, ?, ?, ?, 'PENDING', ?)",
            ).use { stmt ->
                stmt.setString(1, receipt.receipt.receiptId)
                stmt.setString(2, receipt.receipt.envelopeId)
                stmt.setString(3, receipt.receipt.shelterId)
                stmt.setString(4, json.encodeToString(receipt))
                stmt.setLong(5, System.currentTimeMillis())
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Starts the background flusher. Call from a coroutine scope.
     * POSTs pending receipts to Broker; marks SENT only after 2xx.
     */
    suspend fun startFlusher(scope: CoroutineScope) {
        while (scope.isActive) {
            try {
                flushPending()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Transport exception text can contain endpoint diagnostics. Receipt metadata is
                // also sensitive operational data, so retain only a stable error class here.
                System.err.println("[ReceiptOutbox] flush failed (${error.javaClass.simpleName})")
            }
            delay(flushIntervalMs)
        }
    }

    /**
     * Flushes all pending receipts. Exposed for testing.
     * Returns number of receipts successfully sent.
     */
    suspend fun flushPending(): Int {
        val pending = loadPending()
        if (pending.isEmpty()) return 0

        var sent = 0
        for ((receiptId, receiptJson) in pending) {
            val upload = BrokerReceiptUpload(
                receipt = json.decodeFromString<SignedShelterReceipt>(receiptJson),
                gatewayId = gatewayId,
            )
            try {
                val response: HttpResponse = httpClient.post("$brokerUrl/v1/gateways/$shelterId/receipts") {
                    contentType(Json)
                    setBody(json.encodeToString(upload))
                    headers {
                        gatewayCredential?.let { append(HttpHeaders.Authorization, "Bearer $it") }
                        append("X-Gateway-Id", gatewayId)
                    }
                }
                if (response.status.isSuccess()) {
                    markSent(receiptId)
                    sent++
                } else if (response.status == HttpStatusCode.UnprocessableEntity) {
                    // The Gateway may have received this envelope over LAN/BLE before the
                    // Broker. Keep the signed receipt pending so it can be accepted once the
                    // ciphertext reaches the Broker; 422 is not proof that delivery is doomed.
                    markAttempt(receiptId)
                    incrementRetry(receiptId)
                } else {
                    // Every attempt gets a next-attempt timestamp. Without this, a permanent
                    // 4xx/5xx or a network failure remains in the first LIMIT 20 rows forever and
                    // starves all receipts created later.
                    markAttempt(receiptId)
                    incrementRetry(receiptId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A connection reset is normally transient. Permit one immediate retry so a
                // one-shot radio/proxy failure does not add a full minute of delivery latency.
                // Once an item has already failed once, record the attempt timestamp to keep a
                // permanently broken endpoint from monopolising the LIMIT 20 window.
                recordTransientFailure(receiptId)
            }
        }
        return sent
    }

    private fun loadPending(): List<Pair<String, String>> {
        return persistence.withConnection { connection ->
            val results = mutableListOf<Pair<String, String>>()
            connection.prepareStatement(
                "SELECT receipt_id, receipt_json FROM receipt_outbox WHERE status = 'PENDING' " +
                    "AND (last_attempt_at IS NULL OR last_attempt_at < ?) ORDER BY created_at ASC LIMIT 20",
            ).use { stmt ->
                stmt.setLong(1, System.currentTimeMillis() - RETRY_DELAY_MILLIS)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        results.add(rs.getString("receipt_id") to rs.getString("receipt_json"))
                    }
                }
            }
            results
        }
    }

    private fun markSent(receiptId: String) {
        persistence.withConnection { connection ->
            connection.prepareStatement(
                "UPDATE receipt_outbox SET status = 'SENT', sent_at = ? WHERE receipt_id = ?",
            ).use { stmt ->
                stmt.setLong(1, System.currentTimeMillis())
                stmt.setString(2, receiptId)
                stmt.executeUpdate()
            }
        }
    }

    private fun incrementRetry(receiptId: String) {
        persistence.withConnection { connection ->
            connection.prepareStatement(
                "UPDATE receipt_outbox SET retry_count = retry_count + 1 WHERE receipt_id = ?",
            ).use { stmt ->
                stmt.setString(1, receiptId)
                stmt.executeUpdate()
            }
        }
    }

    private fun recordTransientFailure(receiptId: String) {
        persistence.withConnection { connection ->
            connection.prepareStatement(
                "UPDATE receipt_outbox SET retry_count = retry_count + 1, " +
                    "last_attempt_at = CASE WHEN retry_count = 0 THEN last_attempt_at ELSE ? END " +
                    "WHERE receipt_id = ?",
            ).use { stmt ->
                stmt.setLong(1, System.currentTimeMillis())
                stmt.setString(2, receiptId)
                stmt.executeUpdate()
            }
        }
    }

    private fun markAttempt(receiptId: String) {
        persistence.withConnection { connection ->
            connection.prepareStatement(
                "UPDATE receipt_outbox SET last_attempt_at = ? WHERE receipt_id = ?",
            ).use { stmt ->
                stmt.setLong(1, System.currentTimeMillis())
                stmt.setString(2, receiptId)
                stmt.executeUpdate()
            }
        }
    }

    private companion object {
        const val RETRY_DELAY_MILLIS = 60_000L
    }
}
