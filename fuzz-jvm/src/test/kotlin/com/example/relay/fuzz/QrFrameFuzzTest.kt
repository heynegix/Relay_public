package com.example.relay.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import com.example.relay.qr.QrTransferCodec

/**
 * Fuzzes the real QR transport codec in :shared.
 *
 * Contract under test: decodeFrame() swallows every decode error and returns null (never throws),
 * and assemble() classifies any single arbitrary frame into an accept/reject result without
 * throwing. Any Throwable escaping either call is a genuine finding.
 */
class QrFrameFuzzTest {

    @FuzzTest(maxDuration = "10s")
    fun decodeAndAssembleNeverCrash(data: FuzzedDataProvider) {
        val raw = data.consumeRemainingAsBytes()
        val frame = QrTransferCodec.decodeFrame(raw) ?: return
        // A decoded frame must be classifiable in isolation without throwing.
        QrTransferCodec.assemble(listOf(frame), nowEpochMillis = 0L)
    }
}
