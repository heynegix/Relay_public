package com.example.relay.rescue

import com.example.relay.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

fun interface ShelterManifestClient {
    fun fetch(host: String, port: Int): ShelterPublicKeyManifest
}

class HttpShelterManifestClient(
    /** HTTPS is the release default. Debug/localDev may deliberately opt into local HTTP. */
    private val scheme: String = "https",
    private val json: Json = Json { ignoreUnknownKeys = false },
) : ShelterManifestClient {
    override fun fetch(host: String, port: Int): ShelterPublicKeyManifest {
        require(isValidHost(host) && port in 1..65_535) { "invalid shelter address" }
        val normalizedScheme = scheme.trim().lowercase()
        require(normalizedScheme == "https" || (normalizedScheme == "http" && BuildConfig.ALLOW_HTTP_GATEWAY)) {
            "cleartext shelter manifest transport is disabled in this build"
        }
        val connection = (URL(normalizedScheme, host, port, "/api/public/rescue/manifest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        return try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) { "manifest unavailable" }
            // Read Content-Length via the header (API 1) rather than getContentLengthLong (API 24),
            // so LAN enrollment does not crash on Android 6.0 (minSdk 23) devices.
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

    private fun isValidHost(value: String): Boolean = value.length in 1..253 &&
        value.none { it.isWhitespace() || it in "/\\@?#" } &&
        value.all { it.isLetterOrDigit() || it in ".-:" }

    private companion object {
        const val MAX_MANIFEST_BYTES = 24 * 1024
    }
}

class ShelterManifestEnrollment(
    private val client: ShelterManifestClient,
    private val keyStore: VerifiedManifestStore,
) {
    /**
     * Fetches, verifies, and pins a shelter manifest. When [expectedShelterId] is supplied (the
     * shelterId carried by a verified gateway enrollment token), a manifest advertising any other
     * shelter is refused *before* it is persisted — a fail-closed guard so a swapped or spoofed
     * manifest cannot be pinned under the wrong shelter identity. Fingerprint verification is still
     * enforced by [keyStore].
     */
    fun enroll(
        host: String,
        port: Int,
        expectedFingerprint: String,
        expectedShelterId: String? = null,
    ): ShelterPublicKeys {
        val manifest = client.fetch(host.trim(), port)
        if (expectedShelterId != null) {
            require(manifest.shelterId == expectedShelterId.trim()) { "shelter identity mismatch" }
        }
        keyStore.saveVerifiedManifest(manifest, normalizeFingerprint(expectedFingerprint))
        return requireNotNull(keyStore.load())
    }

    private fun normalizeFingerprint(value: String): String = value
        .filterNot { it == ':' || it == '-' || it.isWhitespace() }
        .lowercase()
}
