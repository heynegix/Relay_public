package com.example.relay.rescue

import com.example.relay.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Fetches a shelter's public manifest from the Broker (works over mobile data). */
fun interface BrokerShelterManifestClient {
    fun fetch(brokerEndpoint: String, shelterId: String): ShelterPublicKeyManifest
}

class HttpBrokerShelterManifestClient(
    private val json: Json = Json { ignoreUnknownKeys = false },
) : BrokerShelterManifestClient {
    override fun fetch(brokerEndpoint: String, shelterId: String): ShelterPublicKeyManifest {
        val base = brokerEndpoint.trim().trimEnd('/')
        require(base.startsWith("https://")) { "broker manifest transport must be https" }
        require(isValidShelterId(shelterId)) { "invalid shelter id" }
        val url = URL("$base/v1/shelters/$shelterId/manifest")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            // Bypass the ngrok free-tier browser interstitial on tunneled brokers.
            setRequestProperty("ngrok-skip-browser-warning", "true")
        }
        return try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) { "manifest unavailable" }
            // Read Content-Length via the header (API 1) rather than getContentLengthLong (API 24),
            // so mobile enrollment does not crash on Android 6.0 (minSdk 23) devices.
            val declaredSize = connection.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: -1L
            require(declaredSize == -1L || declaredSize in 1..MAX_MANIFEST_BYTES.toLong()) { "manifest too large" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(4_096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_MANIFEST_BYTES) { "manifest too large" }
                    output.write(buffer, 0, count)
                }
            }
            json.decodeFromString<ShelterPublicKeyManifest>(output.toByteArray().decodeToString())
        } finally {
            connection.disconnect()
        }
    }

    private fun isValidShelterId(value: String): Boolean = value.length in 1..128 &&
        value.all { it.isLetterOrDigit() || it in "-_." }

    private companion object {
        const val MAX_MANIFEST_BYTES = 24 * 1024
    }
}

/**
 * Mobile-data receiver enrollment for a development preview. When the phone has never joined the
 * shelter LAN, it fetches the recipient manifest that the PC Gateway published to the Broker and
 * self-pins it. This is the same explicitly development-only trust shortcut as LAN enrollment: it
 * is disabled unless the variant is a debuggable HTTP-Gateway build, so a release/pilot build keeps
 * the ordinary independently verified enrollment boundary and never trusts a Broker-served key.
 */
class BrokerShelterManifestBootstrap(
    private val brokerEndpointProvider: () -> String,
    private val shelterId: String,
    private val keyStore: RescueShelterKeyStore,
    private val client: BrokerShelterManifestClient = HttpBrokerShelterManifestClient(),
    private val enabled: Boolean = BuildConfig.DEBUG && BuildConfig.ALLOW_HTTP_GATEWAY,
) {
    fun tryEnroll(): DevelopmentEnrollmentResult {
        if (!enabled) return DevelopmentEnrollmentResult.DISABLED
        if (keyStore.load() != null) return DevelopmentEnrollmentResult.ALREADY_ENROLLED
        val endpoint = brokerEndpointProvider().trim()
        if (endpoint.isBlank()) return DevelopmentEnrollmentResult.GATEWAY_NOT_FOUND
        if (shelterId.isBlank()) return DevelopmentEnrollmentResult.INVALID_GATEWAY
        val manifest = runCatching {
            client.fetch(endpoint, shelterId)
        }.getOrNull() ?: return DevelopmentEnrollmentResult.MANIFEST_UNAVAILABLE
        if (manifest.shelterId != shelterId) return DevelopmentEnrollmentResult.SHELTER_MISMATCH
        return runCatching {
            keyStore.saveDevelopmentManifest(manifest)
            DevelopmentEnrollmentResult.ENROLLED
        }.getOrDefault(DevelopmentEnrollmentResult.INVALID_MANIFEST)
    }
}
