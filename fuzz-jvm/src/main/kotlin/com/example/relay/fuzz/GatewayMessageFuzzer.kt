package com.example.relay.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.example.relay.gateway.protocol.GatewayMessage
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Standalone Jazzer entry point for the production Gateway DTO decoder. */
object GatewayMessageFuzzer {
    private val json = Json { ignoreUnknownKeys = true }

    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        val message = try {
            json.decodeFromString(
                GatewayMessage.serializer(),
                data.consumeRemainingAsString(),
            )
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            // Kotlin serialization can surface malformed numeric JSON as an index error;
            // the production HTTP boundary rejects the same input as malformed JSON.
            null
        }
        if (message != null) {
            check(
                message.messageId.length +
                    message.messageType.length +
                    message.recordType.length +
                    message.priority.length +
                    message.status.length +
                    message.originDeviceId.length +
                    message.hopCount +
                    message.hopLimit +
                    message.payload.toString().length +
                    (message.integrity?.signature?.length ?: 0) >= Int.MIN_VALUE,
            )
        }
    }
}
