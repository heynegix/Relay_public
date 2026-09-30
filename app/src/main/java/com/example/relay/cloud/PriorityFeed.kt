package com.example.relay.cloud

import android.content.Context
import com.example.relay.domain.RelayMessage
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

data class PriorityFeedConfig(
    val endpoint: String? = null,
    val maxItems: Int = DEFAULT_MAX_ITEMS,
    val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {
    init {
        require(maxItems in 1..MAX_ALLOWED_ITEMS) { "maxItems out of range" }
        require(maxResponseBytes in 1..MAX_ALLOWED_RESPONSE_BYTES) { "maxResponseBytes out of range" }
        require(connectTimeoutMs in 1..MAX_ALLOWED_TIMEOUT_MS) { "connectTimeoutMs out of range" }
        require(readTimeoutMs in 1..MAX_ALLOWED_TIMEOUT_MS) { "readTimeoutMs out of range" }
    }

    companion object {
        const val DEFAULT_MAX_ITEMS = 128
        const val DEFAULT_MAX_RESPONSE_BYTES = 256 * 1024L
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_READ_TIMEOUT_MS = 10_000
        private const val MAX_ALLOWED_ITEMS = 1_024
        private const val MAX_ALLOWED_RESPONSE_BYTES = 4 * 1024 * 1024L
        private const val MAX_ALLOWED_TIMEOUT_MS = 60_000
    }
}

fun interface PriorityFeedConfigStore {
    fun read(): PriorityFeedConfig
}

class SharedPreferencesPriorityFeedConfigStore(context: Context) : PriorityFeedConfigStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun read(): PriorityFeedConfig = PriorityFeedConfig(
        endpoint = preferences.getString(KEY_ENDPOINT, null),
    )

    companion object {
        private const val PREFERENCES = "relay_priority_feed"
        private const val KEY_ENDPOINT = "https_endpoint"
    }
}

data class PriorityFeedHttpResponse(
    val statusCode: Int,
    val contentType: String?,
    val body: ByteArray,
)

fun interface PriorityFeedTransport {
    suspend fun get(
        endpoint: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        maxResponseBytes: Long,
    ): PriorityFeedHttpResponse
}

class UrlConnectionPriorityFeedTransport : PriorityFeedTransport {
    override suspend fun get(
        endpoint: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        maxResponseBytes: Long,
    ): PriorityFeedHttpResponse = withContext(Dispatchers.IO) {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
        }
        try {
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                val output = ByteArrayOutputStreamWithLimit(maxResponseBytes)
                input.copyTo(output)
                output.toByteArray()
            } ?: ByteArray(0)
            PriorityFeedHttpResponse(statusCode, connection.contentType, body)
        } catch (error: IOException) {
            throw PriorityFeedException("priority feed request failed", error)
        } finally {
            connection.disconnect()
        }
    }
}

private class ByteArrayOutputStreamWithLimit(private val maxBytes: Long) {
    private val bytes = java.io.ByteArrayOutputStream()
    private var count = 0L

    fun write(buffer: ByteArray, offset: Int, length: Int) {
        count += length
        if (count > maxBytes) throw PriorityFeedException("priority feed response exceeds limit")
        bytes.write(buffer, offset, length)
    }

    fun toByteArray(): ByteArray = bytes.toByteArray()
}

private fun java.io.InputStream.copyTo(output: ByteArrayOutputStreamWithLimit): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) return total
        output.write(buffer, 0, read)
        total += read
    }
}

@Serializable
data class PriorityFeedEnvelope(
    val version: Int,
    val messages: List<RelayMessage>,
)

class PriorityFeedException(message: String, cause: Throwable? = null) : IOException(message, cause)

class HttpsPriorityMessageSource(
    private val configStore: PriorityFeedConfigStore,
    private val transport: PriorityFeedTransport = UrlConnectionPriorityFeedTransport(),
    private val json: Json = Json {
        classDiscriminator = "payloadType"
        ignoreUnknownKeys = false
    },
) : PriorityMessageSource {
    override suspend fun fetchCandidates(): List<RelayMessage> {
        val config = configStore.read()
        val endpoint = config.endpoint?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        validateEndpoint(endpoint)
        val response = transport.get(
            endpoint = endpoint,
            connectTimeoutMs = config.connectTimeoutMs,
            readTimeoutMs = config.readTimeoutMs,
            maxResponseBytes = config.maxResponseBytes,
        )
        if (response.statusCode !in 200..299) {
            throw PriorityFeedException("priority feed returned HTTP ${response.statusCode}")
        }
        if (response.contentType?.substringBefore(';')?.trim()?.lowercase() != "application/json") {
            throw PriorityFeedException("priority feed content type is not application/json")
        }
        val envelope = try {
            json.decodeFromString<PriorityFeedEnvelope>(response.body.decodeToString())
        } catch (error: Exception) {
            throw PriorityFeedException("priority feed JSON is invalid", error)
        }
        if (envelope.version != SUPPORTED_VERSION) {
            throw PriorityFeedException("unsupported priority feed version ${envelope.version}")
        }
        if (envelope.messages.size > config.maxItems) {
            throw PriorityFeedException("priority feed contains too many messages")
        }
        return envelope.messages
    }

    private fun validateEndpoint(endpoint: String) {
        val uri = try {
            URI(endpoint)
        } catch (error: Exception) {
            throw PriorityFeedException("priority feed endpoint is invalid", error)
        }
        if (uri.scheme?.lowercase() != "https" || uri.host.isNullOrBlank() || uri.userInfo != null ||
            (uri.port != -1 && uri.port != 443) || uri.query != null || uri.fragment != null
        ) {
            throw PriorityFeedException("priority feed endpoint must be an https URL on port 443")
        }
    }

    companion object {
        private const val SUPPORTED_VERSION = 1
    }
}
