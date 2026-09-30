package com.example.relay.rescue.ble

import com.example.relay.rescue.ShelterReceiptStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RescueBleFrameCodecTest {
    @Test
    fun encodesPcBridgeGattFrameLayoutExactly() {
        val session = byteArrayOf(1, 2, 3, 4)
        val encoded = RescueBleFrameCodec.Frame(
            RescueBleFrameCodec.Kind.CHUNK,
            sequence = 0x1234,
            total = 0x0002,
            sessionId = session,
            payload = byteArrayOf(9, 8),
        ).encode()

        assertArrayEquals(byteArrayOf(2, 2, 0x12, 0x34, 0, 2, 1, 2, 3, 4, 9, 8), encoded)
        val decoded = requireNotNull(RescueBleFrameCodec.decode(encoded))
        assertEquals(RescueBleFrameCodec.Kind.CHUNK, decoded.kind)
        assertEquals(0x1234, decoded.sequence)
        assertEquals(2, decoded.total)
        assertArrayEquals(session, decoded.sessionId)
    }

    @Test
    fun startMetadataChunksAndDigestCommitUseBridgeOrdering() {
        val session = byteArrayOf(1, 2, 3, 4)
        val envelope = ByteArray(21) { it.toByte() }
        val starts = RescueBleFrameCodec.startFrames("delivery-123", "carrier-456", session)
            .map { requireNotNull(RescueBleFrameCodec.decode(it)) }
        val chunks = RescueBleFrameCodec.chunkFrames(envelope, session).map { requireNotNull(RescueBleFrameCodec.decode(it)) }
        val commit = RescueBleFrameCodec.commitFrames(envelope, session).map { requireNotNull(RescueBleFrameCodec.decode(it)) }

        assertEquals(3, starts.size)
        assertEquals(listOf(0, 1, 2), starts.map { it.sequence })
        assertEquals(listOf(3, 3, 3), starts.map { it.total })
        assertEquals(listOf(RescueBleFrameCodec.Kind.START, RescueBleFrameCodec.Kind.START, RescueBleFrameCodec.Kind.START), starts.map { it.kind })
        assertEquals(3, chunks.size)
        assertEquals(listOf(0, 1, 2), chunks.map { it.sequence })
        assertEquals(listOf(3, 3, 3), chunks.map { it.total })
        assertEquals(4, commit.size) // SHA-256 has 32 bytes and GATT payloads have 10.
        assertEquals(RescueBleFrameCodec.Kind.COMMIT, commit.first().kind)
    }

    @Test
    fun startMetadataUsesVersionBigEndianLengthsAndTenByteFragments() {
        val session = byteArrayOf(1, 2, 3, 4)
        val frames = RescueBleFrameCodec.startFrames("courier-1", "carrier-2", session)
            .map { requireNotNull(RescueBleFrameCodec.decode(it)) }
        val metadata = frames.fold(byteArrayOf()) { all, frame -> all + frame.payload }

        assertArrayEquals(
            byteArrayOf(1, 0, 9, 0, 9) + "courier-1carrier-2".encodeToByteArray(),
            metadata,
        )
        assertEquals(10, frames.first().payload.size)
        assertEquals(10, frames[1].payload.size)
        assertEquals(3, frames.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun startMetadataRejectsNonAsciiIdentifier() {
        RescueBleFrameCodec.startFrames("配送", "carrier", byteArrayOf(1, 2, 3, 4))
    }

    @Test
    fun malformedFramesFailClosed() {
        assertNull(RescueBleFrameCodec.decode(byteArrayOf()))
        assertNull(RescueBleFrameCodec.decode(ByteArray(21)))
        assertNull(RescueBleFrameCodec.decode(byteArrayOf(99, 0, 0, 0, 0, 1, 1, 2, 3, 4)))
        assertNull(RescueBleFrameCodec.decode(byteArrayOf(1, 11, 0, 0, 0, 1, 1, 2, 3, 4)))
    }

    @Test
    fun stableCourierIdProducesStableSessionId() {
        assertArrayEquals(
            RescueBleFrameCodec.sessionIdFor("delivery-id"),
            RescueBleFrameCodec.sessionIdFor("delivery-id"),
        )
        assertEquals(4, RescueBleFrameCodec.sessionIdFor("delivery-id").size)
    }

    @Test
    fun v2ShelterIdentityFitsLegacyAdvertisementBudget() {
        val encoded = byteArrayOf(2) + ByteArray(9) { it.toByte() }
        val identity = requireNotNull(ShelterBleIdentity.decode(encoded))

        assertArrayEquals(encoded, identity.encoded())
        assertEquals(31, 3 + 2 + 16 + identity.encoded().size)
        assertNull(ShelterBleIdentity.decode(byteArrayOf(1) + ByteArray(9)))
    }

    @Test
    fun courierDeliveryIdIsRetainedUntilShelterStatusIsTerminal() {
        assertEquals(false, ShelterReceiptStatus.STORED.isTerminalDeliveryReceipt())
        assertEquals(false, ShelterReceiptStatus.ACCEPTED.isTerminalDeliveryReceipt())
        assertEquals(false, ShelterReceiptStatus.RESPONDING.isTerminalDeliveryReceipt())
        assertEquals(true, ShelterReceiptStatus.COMPLETED.isTerminalDeliveryReceipt())
        assertEquals(true, ShelterReceiptStatus.CANCELLED.isTerminalDeliveryReceipt())
        assertEquals(true, ShelterReceiptStatus.REJECTED.isTerminalDeliveryReceipt())
    }
}
