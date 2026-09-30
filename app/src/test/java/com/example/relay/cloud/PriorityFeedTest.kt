package com.example.relay.cloud

import com.example.relay.message
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PriorityFeedTest {
    private val json = Json {
        classDiscriminator = "payloadType"
        ignoreUnknownKeys = false
    }

    @Test
    fun `unset endpoint does not make a network request`() = runBlocking {
        var requests = 0
        val source = source(endpoint = null) {
            requests++
            response(envelope())
        }

        assertTrue(source.fetchCandidates().isEmpty())
        assertEquals(0, requests)
    }

    @Test(expected = PriorityFeedException::class)
    fun `cleartext endpoint is rejected before transport`() = runBlocking {
        source(endpoint = "http://example.test/feed") { error("transport must not run") }
            .fetchCandidates()
        Unit
    }

    @Test
    fun `versioned json envelope is decoded`() = runBlocking {
        val expected = message(id = "priority-1")
        val result = source { response(envelope(expected)) }.fetchCandidates()

        assertEquals(listOf(expected), result)
    }

    @Test(expected = PriorityFeedException::class)
    fun `non json content type is rejected`() = runBlocking {
        source { PriorityFeedHttpResponse(200, "text/plain", ByteArray(0)) }.fetchCandidates()
        Unit
    }

    @Test(expected = PriorityFeedException::class)
    fun `unsupported version is rejected`() = runBlocking {
        source { response(json.encodeToString(PriorityFeedEnvelope(version = 2, messages = emptyList()))) }
            .fetchCandidates()
        Unit
    }

    @Test(expected = PriorityFeedException::class)
    fun `item limit is enforced`() = runBlocking {
        source(config = PriorityFeedConfig(endpoint = "https://example.test/feed", maxItems = 1)) {
            response(envelope(message(id = "one"), message(id = "two")))
        }.fetchCandidates()
        Unit
    }

    private fun source(
        endpoint: String? = "https://example.test/feed",
        config: PriorityFeedConfig = PriorityFeedConfig(endpoint = endpoint),
        transport: suspend (String) -> PriorityFeedHttpResponse,
    ): HttpsPriorityMessageSource = HttpsPriorityMessageSource(
        configStore = PriorityFeedConfigStore { config },
        transport = PriorityFeedTransport { endpoint, _, _, _ -> transport(endpoint) },
        json = json,
    )

    private fun response(body: String): PriorityFeedHttpResponse =
        PriorityFeedHttpResponse(200, "application/json; charset=utf-8", body.encodeToByteArray())

    private fun envelope(vararg messages: com.example.relay.domain.RelayMessage): String =
        json.encodeToString(PriorityFeedEnvelope(version = 1, messages = messages.toList()))
}
