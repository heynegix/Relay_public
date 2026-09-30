package com.example.relay.pcgateway.rescue

import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.pcgateway.RouteAttempt
import com.example.relay.pcgateway.RouteResult
import com.example.relay.pcgateway.RouteType
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire model matching Broker's BrokerEnvelopeBatch. */
@Serializable
data class BrokerEnvelopeBatch(
    val envelopes: List<EncryptedRescueEnvelope>,
    val cursor: String?,
)

/**
 * Pulls encrypted rescue envelopes from the Broker via outbound HTTPS GET.
 * v1: 1 shelterId = 1 active Gateway. No multi-Gateway failover.
 * On Broker unreachable: logs and retries with backoff. LAN/BLE ingress unaffected.
 *
 * Cursor is persisted to disk so restarts resume from the last position
 * (composite cursor: stored_at:envelope_id — no wrap, no skip).
 */
class BrokerPullAgent(
    private val brokerUrl: String,
    private val shelterId: String,
    private val gatewayId: String,
    private val intakeService: RescueIntakeService,
    private val httpClient: HttpClient,
    /** Per-Gateway, per-shelter Broker credential; never persisted in the cursor file. */
    private val gatewayCredential: String? = null,
    private val cursorPath: Path? = null,
    private val pollIntervalMs: Long = 10_000L,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val routeAttemptSink: ((RouteAttempt) -> Unit)? = null,
) {
    init {
        require(pollIntervalMs in 1_000L..300_000L) {
            "pollIntervalMs must be between 1000 and 300000"
        }
    }

    private var cursor: String? = loadCursor()
    private var consecutiveFailures = 0

    /**
     * Starts the pull loop. Call from a coroutine scope.
     * Runs independently of LAN/BLE ingress.
     */
    suspend fun start(scope: CoroutineScope) {
        while (scope.isActive) {
            try {
                val pulled = pullOnce()
                consecutiveFailures = 0
                if (pulled > 0) {
                    // Immediately poll again if we got data (catch up)
                    continue
                }
            } catch (e: Exception) {
                consecutiveFailures++
                val backoff = minOf(pollIntervalMs * consecutiveFailures, 60_000L)
                // Exception messages can include endpoint or transport diagnostics. Do not log
                // them from a credential-bearing request path.
                System.err.println("[BrokerPullAgent] pull failed (attempt $consecutiveFailures; ${e.javaClass.simpleName})")
                delay(backoff)
                continue
            }
            delay(pollIntervalMs)
        }
    }

    /**
     * Single pull iteration. Returns number of envelopes ingested.
     * Exposed for testing.
     */
    suspend fun pullOnce(): Int {
        val url = buildString {
            append("$brokerUrl/v1/gateways/$shelterId/pull")
            append("?limit=50")
            cursor?.let { append("&cursor=$it") }
        }

        val response: HttpResponse = httpClient.get(url) {
            headers {
                gatewayCredential?.let { append(HttpHeaders.Authorization, "Bearer $it") }
                append("X-Gateway-Id", gatewayId)
            }
        }
        if (!response.status.isSuccess()) {
            throw RuntimeException("Broker pull returned ${response.status}")
        }

        val batch = json.decodeFromString<BrokerEnvelopeBatch>(response.bodyAsText())

        var ingested = 0
        for (envelope in batch.envelopes) {
            val result = intakeService.ingest(
                envelope = envelope,
                carrierId = "broker",
                courierDeliveryId = "broker:${envelope.envelopeId}",
            )
            routeAttemptSink?.invoke(RouteAttempt(
                id = java.util.UUID.randomUUID().toString(), envelopeId = envelope.envelopeId,
                routeType = RouteType.HTTPS_BROKER, attemptedAtEpochMillis = System.currentTimeMillis(),
                completedAtEpochMillis = System.currentTimeMillis(),
                result = if (result is RescueIngestResult.Accepted || result is RescueIngestResult.Duplicate) RouteResult.RECEIPT_CONFIRMED else RouteResult.FAILED,
                safeErrorCode = (result as? RescueIngestResult.Rejected)?.code?.name,
                receiptId = when (result) { is RescueIngestResult.Accepted -> result.request.receipt.receipt.receiptId; is RescueIngestResult.Duplicate -> result.request.receipt.receipt.receiptId; else -> null },
            ))
            when (result) {
                is RescueIngestResult.Accepted -> ingested++
                is RescueIngestResult.Duplicate -> { /* already have it */ }
                is RescueIngestResult.Quarantined -> {
                    System.err.println("[BrokerPullAgent] quarantined envelope: ${envelope.envelopeId}")
                }
                is RescueIngestResult.Rejected -> {
                    System.err.println("[BrokerPullAgent] rejected envelope: ${envelope.envelopeId} (${result.code})")
                }
            }
        }

        // Advance cursor AFTER successful processing (at-least-once: re-pull on crash is safe)
        if (batch.cursor != null) {
            cursor = batch.cursor
            persistCursor()
        }
        return ingested
    }

    private fun loadCursor(): String? {
        if (cursorPath == null) return null
        return runCatching {
            Files.readString(cursorPath).trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun persistCursor() {
        if (cursorPath == null || cursor == null) return
        runCatching {
            Files.writeString(cursorPath, cursor!!)
        }.onFailure {
            System.err.println("[BrokerPullAgent] failed to persist cursor (${it.javaClass.simpleName})")
        }
    }
}
