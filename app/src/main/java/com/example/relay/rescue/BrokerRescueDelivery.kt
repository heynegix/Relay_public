package com.example.relay.rescue

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Wire model matching Broker's BrokerUploadRequest. */
@Serializable
data class BrokerUploadRequest(
    val envelope: EncryptedRescueEnvelope,
    val deviceKeyId: String,
    val uploadSignatureBase64: String,
)

/** Wire model matching Broker's BrokerUploadResponse. */
@Serializable
data class BrokerUploadResponse(
    val brokerReceiptId: String,
    val envelopeId: String,
    val status: String = "BROKER_STORED",
    val storedAtEpochMillis: Long,
)

/** Wire model matching Broker's BrokerDeviceRegisterRequest. */
@Serializable
data class BrokerDeviceRegisterRequest(
    val deviceKeyId: String,
    val publicKeyBase64: String,
    val registrationSignatureBase64: String,
)

/** Wire model matching Broker's BrokerDeviceRegisterResponse. */
@Serializable
data class BrokerDeviceRegisterResponse(
    val deviceKeyId: String,
    val capabilityToken: String,
)

/** Wire model matching Broker's BrokerReceiptBatch (with monotonic cursor). */
@Serializable
data class BrokerReceiptBatch(
    val receipts: List<SignedShelterReceipt>,
    val cursor: Long = 0,
)

@Serializable
private data class BrokerErrorResponse(
    val reason: String? = null,
)

/** Result of a Broker delivery attempt. */
sealed interface BrokerDeliveryResult {
    data class Stored(val response: BrokerUploadResponse) : BrokerDeliveryResult
    data object Offline : BrokerDeliveryResult
    data object Disabled : BrokerDeliveryResult
    data class Failed(val reason: String, val retryable: Boolean = true) : BrokerDeliveryResult
}

/**
 * Delivers encrypted rescue envelopes to the HTTPS Broker.
 * Does NOT increment hopCount — Broker is not a Store-Carry-Forward hop.
 * Runs independently of Nearby/BLE/LAN paths.
 *
 * Security: HTTPS-only, no redirects, endpoint validation.
 */
class BrokerRescueDelivery(
    private val context: Context,
    endpoint: String,
    private val signingKeyStore: UploadSigningKeyStore,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val normalizedEndpoint = endpoint.trim().trimEnd('/')
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    init {
        require(normalizedEndpoint.isBlank() || isSafeBrokerBaseEndpoint(normalizedEndpoint)) {
            "Broker endpoint must use HTTPS without embedded credentials"
        }
    }

    /**
     * Registers this device with the Broker and stores the capability token.
     * Must be called before upload/receipt operations. Idempotent.
     */
    suspend fun ensureRegistered(): Boolean = withContext(Dispatchers.IO) {
        if (normalizedEndpoint.isBlank()) return@withContext false
        if (signingKeyStore.capabilityToken != null) return@withContext true
        if (!isOnline()) return@withContext false

        runCatching {
            val request = registrationRequest()
            val requestBody = json.encodeToString(request).toByteArray()
            val connection = openSecureConnection("$normalizedEndpoint/v1/devices/register", "POST")
            try {
                connection.setFixedLengthStreamingMode(requestBody.size)
                connection.outputStream.use { it.write(requestBody) }
                val status = connection.responseCode
                if (status !in 200..299) return@withContext false
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val response = json.decodeFromString<BrokerDeviceRegisterResponse>(body)
                if (response.deviceKeyId != signingKeyStore.keyId || response.capabilityToken.isBlank()) {
                    return@withContext false
                }
                signingKeyStore.capabilityToken = response.capabilityToken
                true
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    /**
     * Attempts to upload the envelope to the Broker.
     * The envelope is sent as-is (hopCount unchanged).
     */
    suspend fun deliver(envelope: EncryptedRescueEnvelope): BrokerDeliveryResult = withContext(Dispatchers.IO) {
        if (normalizedEndpoint.isBlank()) return@withContext BrokerDeliveryResult.Disabled
        if (!isOnline()) return@withContext BrokerDeliveryResult.Offline

        // Ensure device is registered before upload
        if (signingKeyStore.capabilityToken == null) {
            if (!ensureRegisteredSync()) {
                return@withContext BrokerDeliveryResult.Failed("registration_failed", retryable = true)
            }
        }

        val signature = try {
            signingKeyStore.sign(envelope)
        } catch (_: Exception) {
            // Keystore/provider messages can reveal implementation detail; expose only a stable
            // diagnosis to UI and persisted retry state.
            return@withContext BrokerDeliveryResult.Failed("signing_failed", retryable = false)
        }

        val request = BrokerUploadRequest(
            envelope = envelope,
            deviceKeyId = signingKeyStore.keyId,
            uploadSignatureBase64 = signature,
        )

        runCatching {
            val requestBody = json.encodeToString(request).toByteArray()
            val connection = openSecureConnection("$normalizedEndpoint/v1/rescue/upload", "POST")
            try {
                connection.setFixedLengthStreamingMode(requestBody.size)
                connection.outputStream.use { it.write(requestBody) }
                val status = connection.responseCode
                val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()

                when {
                    status == 201 || status == 200 -> {
                        val response = json.decodeFromString<BrokerUploadResponse>(body)
                        BrokerDeliveryResult.Stored(response)
                    }
                    status == 401 -> {
                        val reason = brokerErrorReason(body)
                        if (reason == "device_not_registered") {
                            // A Broker restore can lose the registration while Android retains its
                            // token. Clear it so the next attempt performs a fresh registration.
                            signingKeyStore.capabilityToken = null
                        }
                        BrokerDeliveryResult.Failed(
                            reason = reason,
                            retryable = reason == "device_not_registered",
                        )
                    }
                    status == 409 -> BrokerDeliveryResult.Failed("collision", retryable = false)
                    status == 429 -> BrokerDeliveryResult.Failed("rate_limited", retryable = true)
                    status in 400..499 -> BrokerDeliveryResult.Failed("client_error_$status", retryable = false)
                    else -> BrokerDeliveryResult.Failed("server_error_$status", retryable = true)
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse {
            BrokerDeliveryResult.Failed("network_error", retryable = true)
        }
    }

    /**
     * Polls the Broker for signed shelter receipts using the unguessable capability token.
     * Uses Broker monotonic seq cursor (not device time).
     */
    suspend fun pollReceipts(sinceSeq: Long = 0): BrokerReceiptBatch = withContext(Dispatchers.IO) {
        if (normalizedEndpoint.isBlank()) return@withContext BrokerReceiptBatch(emptyList(), sinceSeq)
        if (!isOnline()) return@withContext BrokerReceiptBatch(emptyList(), sinceSeq)

        val token = signingKeyStore.capabilityToken
            ?: return@withContext BrokerReceiptBatch(emptyList(), sinceSeq)

        runCatching {
            val url = "$normalizedEndpoint/v1/receipts?sinceSeq=$sinceSeq"
            val connection = openSecureConnection(url, "GET")
            try {
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $token")
                val status = connection.responseCode
                if (status == 401) {
                    signingKeyStore.capabilityToken = null
                    return@withContext BrokerReceiptBatch(emptyList(), sinceSeq)
                }
                if (status !in 200..299) return@withContext BrokerReceiptBatch(emptyList(), sinceSeq)
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                json.decodeFromString<BrokerReceiptBatch>(body)
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(BrokerReceiptBatch(emptyList(), sinceSeq))
    }

    /**
     * Opens an HTTPS-only connection with redirects disabled.
     * Rejects non-HTTPS URLs at the connection level.
     */
    private fun openSecureConnection(url: String, method: String): HttpsURLConnection {
        require(isSafeHttpsEndpoint(url)) { "HTTPS required for Broker communication" }
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.requestMethod = method
        // HttpsURLConnection defaults doOutput to false. Without this, both device registration
        // and rescue upload fail before their request body is sent.
        connection.doOutput = method == "POST"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Content-Type", "application/json")
        // Free ngrok domains can serve a browser interstitial to requests they classify as browser
        // traffic; this header opts out so a programmatic Broker upload always reaches the tunnel.
        // Harmless for non-ngrok endpoints, which ignore the unknown header.
        connection.setRequestProperty("ngrok-skip-browser-warning", "true")
        return connection
    }

    private fun ensureRegisteredSync(): Boolean {
        if (signingKeyStore.capabilityToken != null) return true
        return runCatching {
            val request = registrationRequest()
            val requestBody = json.encodeToString(request).toByteArray()
            val connection = openSecureConnection("$normalizedEndpoint/v1/devices/register", "POST")
            try {
                connection.setFixedLengthStreamingMode(requestBody.size)
                connection.outputStream.use { it.write(requestBody) }
                val status = connection.responseCode
                if (status !in 200..299) return false
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val response = json.decodeFromString<BrokerDeviceRegisterResponse>(body)
                if (response.deviceKeyId != signingKeyStore.keyId || response.capabilityToken.isBlank()) {
                    return false
                }
                signingKeyStore.capabilityToken = response.capabilityToken
                true
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    private fun registrationRequest(): BrokerDeviceRegisterRequest {
        val deviceKeyId = signingKeyStore.keyId
        val publicKeyBase64 = signingKeyStore.publicKeyBase64()
        return BrokerDeviceRegisterRequest(
            deviceKeyId = deviceKeyId,
            publicKeyBase64 = publicKeyBase64,
            registrationSignatureBase64 = signingKeyStore.signRegistration(deviceKeyId, publicKeyBase64),
        )
    }

    private fun brokerErrorReason(body: String): String = runCatching {
        json.decodeFromString<BrokerErrorResponse>(body).reason
    }.getOrNull().orEmpty().ifBlank { "unauthorized" }

    private fun isOnline(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun isSafeHttpsEndpoint(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.fragment == null &&
                (uri.port == -1 || uri.port in 1..65_535)
    }.getOrDefault(false)

    private fun isSafeBrokerBaseEndpoint(value: String): Boolean = runCatching {
        URI(value).query == null && isSafeHttpsEndpoint(value)
    }.getOrDefault(false)
}
