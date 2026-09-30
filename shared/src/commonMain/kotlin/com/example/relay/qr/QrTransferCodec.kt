package com.example.relay.qr

import com.example.relay.rescue.RescueCryptography
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val CURRENT_SCHEMA_VERSION = 1

/** A QR frame carries only an opaque, already encrypted Relay payload. */
@Serializable
data class QrTransferFrame(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val transferId: String,
    val frameIndex: Int,
    val totalFrames: Int,
    val payloadChunk: String,
    val payloadHash: String,
    val expiresAtEpochMillis: Long,
)

sealed interface QrTransferDecodeResult {
    data class Accepted(val payload: ByteArray, val transferId: String) : QrTransferDecodeResult
    data class Rejected(val reason: QrTransferRejection) : QrTransferDecodeResult
}

enum class QrTransferRejection {
    MALFORMED_FRAME,
    UNSUPPORTED_VERSION,
    INVALID_METADATA,
    EXPIRED,
    MIXED_TRANSFER,
    DUPLICATE_FRAME,
    MISSING_FRAME,
    HASH_MISMATCH,
}

/**
 * Deterministic codec for QR transport. The input must be an encrypted envelope
 * serialization or another opaque payload; this class never decodes rescue data.
 */
object QrTransferCodec {
    private const val MAX_FRAMES = 256
    private const val MAX_PAYLOAD_BYTES = 512 * 1024
    private const val MAX_CHUNK_BYTES = 4 * 1024
    private const val MAX_CHUNK_HEX_CHARS = MAX_CHUNK_BYTES * 2
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun encode(
        transferId: String,
        encryptedPayload: ByteArray,
        expiresAtEpochMillis: Long,
        maxChunkBytes: Int = 1_024,
    ): List<QrTransferFrame> {
        require(transferId.isNotBlank() && transferId.length <= 128)
        require(encryptedPayload.isNotEmpty() && encryptedPayload.size <= MAX_PAYLOAD_BYTES)
        require(maxChunkBytes in 128..MAX_CHUNK_BYTES)
        require(expiresAtEpochMillis > 0)
        val hash = RescueCryptography.sha256Hex(encryptedPayload)
        val chunks = encryptedPayload.asList().chunked(maxChunkBytes)
        require(chunks.size <= MAX_FRAMES)
        return chunks.mapIndexed { index, bytes ->
            QrTransferFrame(
                transferId = transferId,
                frameIndex = index,
                totalFrames = chunks.size,
                payloadChunk = bytes.toByteArray().toHex(),
                payloadHash = hash,
                expiresAtEpochMillis = expiresAtEpochMillis,
            )
        }
    }

    fun encodeForQr(frame: QrTransferFrame): ByteArray = json.encodeToString(QrTransferFrame.serializer(), frame).encodeToByteArray()

    fun decodeFrame(encoded: ByteArray): QrTransferFrame? = try {
        json.decodeFromString(QrTransferFrame.serializer(), encoded.decodeToString())
    } catch (_: Exception) {
        null
    }

    fun assemble(
        frames: Collection<QrTransferFrame>,
        nowEpochMillis: Long,
    ): QrTransferDecodeResult {
        if (frames.isEmpty()) return QrTransferDecodeResult.Rejected(QrTransferRejection.MISSING_FRAME)
        val first = frames.first()
        if (first.schemaVersion != CURRENT_SCHEMA_VERSION) return QrTransferDecodeResult.Rejected(QrTransferRejection.UNSUPPORTED_VERSION)
        if (first.transferId.isBlank() || first.totalFrames !in 1..MAX_FRAMES || first.expiresAtEpochMillis <= 0) {
            return QrTransferDecodeResult.Rejected(QrTransferRejection.INVALID_METADATA)
        }
        if (nowEpochMillis >= first.expiresAtEpochMillis) return QrTransferDecodeResult.Rejected(QrTransferRejection.EXPIRED)
        if (frames.size != first.totalFrames) return QrTransferDecodeResult.Rejected(QrTransferRejection.MISSING_FRAME)
        if (frames.any { it.schemaVersion != first.schemaVersion || it.transferId != first.transferId || it.totalFrames != first.totalFrames || it.payloadHash != first.payloadHash || it.expiresAtEpochMillis != first.expiresAtEpochMillis }) {
            return QrTransferDecodeResult.Rejected(QrTransferRejection.MIXED_TRANSFER)
        }
        if (frames.map { it.frameIndex }.toSet().size != frames.size) return QrTransferDecodeResult.Rejected(QrTransferRejection.DUPLICATE_FRAME)
        if (frames.any { it.frameIndex !in 0 until first.totalFrames || it.payloadChunk.length > MAX_CHUNK_HEX_CHARS || !it.payloadChunk.isHex() }) {
            return QrTransferDecodeResult.Rejected(QrTransferRejection.INVALID_METADATA)
        }
        val ordered = frames.sortedBy { it.frameIndex }
        if (ordered.map { it.frameIndex } != (0 until first.totalFrames).toList()) return QrTransferDecodeResult.Rejected(QrTransferRejection.MISSING_FRAME)
        val payload = ordered.flatMap { it.payloadChunk.fromHex().asList() }.toByteArray()
        if (payload.isEmpty() || payload.size > MAX_PAYLOAD_BYTES || RescueCryptography.sha256Hex(payload) != first.payloadHash) {
            return QrTransferDecodeResult.Rejected(QrTransferRejection.HASH_MISMATCH)
        }
        return QrTransferDecodeResult.Accepted(payload, first.transferId)
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }
    private fun String.isHex(): Boolean = length % 2 == 0 && all { it in "0123456789abcdefABCDEF" }
    private fun String.fromHex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private const val HEX = "0123456789abcdef"

}
