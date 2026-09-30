package com.example.relay.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.example.relay.qr.QrTransferCodec

/** Standalone Jazzer entry point used by the ClusterFuzzLite JVM integration. */
object QrFrameFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        val frame = QrTransferCodec.decodeFrame(data.consumeRemainingAsBytes()) ?: return
        QrTransferCodec.assemble(listOf(frame), nowEpochMillis = 0L)
    }
}
