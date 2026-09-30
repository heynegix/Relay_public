package com.example.relay.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.validate
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Fuzzes the real rescue envelope decode + validate boundary in :shared.
 *
 * Contract under test: arbitrary bytes must either (a) fail to deserialize with a bounded
 * serialization/argument error, or (b) deserialize and then be classified by validate() which
 * returns a RescueValidationResult and must never throw. Any other Throwable (NPE, OOM,
 * StackOverflow, unchecked crash) is a genuine finding.
 */
class EnvelopeDecoderFuzzTest {
    private val json = Json { ignoreUnknownKeys = false }

    @FuzzTest(maxDuration = "10s")
    fun decodeAndValidateFailClosed(data: FuzzedDataProvider) {
        val raw = data.consumeRemainingAsString()
        val envelope = try {
            json.decodeFromString(EncryptedRescueEnvelope.serializer(), raw)
        } catch (expected: SerializationException) {
            return
        } catch (expected: IllegalArgumentException) {
            // kotlinx-serialization surfaces some structural problems as IllegalArgumentException.
            return
        }
        // A structurally valid envelope must be classified without throwing.
        envelope.validate()
    }
}
