package com.example.relay.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import com.example.relay.gateway.protocol.GatewayMessage
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Fuzzes the real Gateway sync DTO decode boundary in :relay-protocol.
 *
 * Contract under test: arbitrary JSON-ish bytes must either fail to deserialize with a bounded
 * serialization/argument error or deserialize into a GatewayMessage. Reading the decoded fields
 * (including the opaque payload JsonElement) must not throw. Any other Throwable is a finding.
 */
class GatewayDtoFuzzTest {
    private val json = Json { ignoreUnknownKeys = true }

    @FuzzTest(maxDuration = "10s")
    fun decodeFailClosed(data: FuzzedDataProvider) {
        val raw = data.consumeRemainingAsString()
        val message = try {
            json.decodeFromString(GatewayMessage.serializer(), raw)
        } catch (expected: SerializationException) {
            return
        } catch (expected: IllegalArgumentException) {
            return
        }
        // Touch every field so a decoded-but-degenerate DTO cannot hide an unchecked crash.
        val fingerprint = message.messageId.length +
            message.messageType.length +
            message.recordType.length +
            message.priority.length +
            message.status.length +
            message.originDeviceId.length +
            message.hopCount +
            message.hopLimit +
            message.payload.toString().length +
            (message.integrity?.signature?.length ?: 0)
        check(fingerprint >= Int.MIN_VALUE)
    }
}
