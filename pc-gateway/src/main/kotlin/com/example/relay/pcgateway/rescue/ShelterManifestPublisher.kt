package com.example.relay.pcgateway.rescue

import com.example.relay.rescue.ShelterPublicKeyManifest
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType.Application.Json
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json as KotlinJson

/**
 * Publishes this Gateway's public shelter manifest to the Broker so a phone that has never joined
 * the shelter LAN can still fetch the recipient key over mobile data. The manifest is public-only
 * key material (it already leaves the Gateway on the LAN manifest route); no private key is sent.
 *
 * The Broker stores the last published manifest per shelter. Publishing is idempotent, so the
 * agent simply retries until the Broker acknowledges once, then republishes on a slow keepalive so
 * a Broker that restarts with a fresh database recovers the manifest without a Gateway restart.
 */
class ShelterManifestPublisher(
    private val brokerUrl: String,
    private val shelterId: String,
    private val gatewayId: String,
    private val manifest: ShelterPublicKeyManifest,
    private val httpClient: HttpClient,
    private val gatewayCredential: String? = null,
    private val republishIntervalMs: Long = 10 * 60_000L,
    private val retryIntervalMs: Long = 15_000L,
    private val json: KotlinJson = KotlinJson { encodeDefaults = true },
) {
    /** Runs a background loop that keeps the Broker's copy of the manifest current. */
    suspend fun start(scope: CoroutineScope) {
        while (scope.isActive) {
            val published = runCatching { publishOnce() }.getOrDefault(false)
            delay(if (published) republishIntervalMs else retryIntervalMs)
        }
    }

    /** Publishes once. Returns true only when the Broker acknowledges. Exposed for testing. */
    suspend fun publishOnce(): Boolean {
        val response: HttpResponse = httpClient.post("$brokerUrl/v1/gateways/$shelterId/manifest") {
            contentType(Json)
            setBody(json.encodeToString(ShelterPublicKeyManifest.serializer(), manifest))
            headers {
                gatewayCredential?.let { append(HttpHeaders.Authorization, "Bearer $it") }
                append("X-Gateway-Id", gatewayId)
                // Bypass the ngrok free-tier browser interstitial on tunneled brokers.
                append("ngrok-skip-browser-warning", "true")
            }
        }
        return response.status.isSuccess()
    }
}
