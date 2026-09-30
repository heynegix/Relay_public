package com.example.relay.rescue.ble

import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.SignedShelterReceipt
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Android-facing boundary: it transports encrypted envelope bytes only. */
interface ShelterBleClient {
    val advertisements: Flow<ShelterAdvertisement>
    suspend fun connect(advertisement: ShelterAdvertisement): ShelterBleSession
}

/**
 * Fixed-size identity advertised by the Windows GATT bridge.  It contains no
 * rescue content and is checked again through the read characteristic after a
 * connection is made, preventing a scan-result substitution.
 */
data class ShelterBleIdentity(
    val protocolVersion: Int,
    val signedManifestFingerprint: ByteArray,
) {
    init {
        require(protocolVersion == PROTOCOL_VERSION)
        require(signedManifestFingerprint.size == MANIFEST_FINGERPRINT_BYTES)
    }

    fun encoded(): ByteArray = byteArrayOf(protocolVersion.toByte()) + signedManifestFingerprint

    fun sameWireIdentity(other: ShelterBleIdentity): Boolean = encoded().contentEquals(other.encoded())

    companion object {
        const val PROTOCOL_VERSION = 2
        const val MANIFEST_FINGERPRINT_BYTES = 9
        const val ENCODED_BYTES = 1 + MANIFEST_FINGERPRINT_BYTES

        fun decode(bytes: ByteArray): ShelterBleIdentity? {
            if (bytes.size != ENCODED_BYTES || bytes[0].toInt() != PROTOCOL_VERSION) return null
            return runCatching {
                ShelterBleIdentity(
                    protocolVersion = bytes[0].toInt(),
                    signedManifestFingerprint = bytes.copyOfRange(1, ENCODED_BYTES),
                )
            }.getOrNull()
        }
    }
}

/** [shelterId] is retained for test/backwards compatibility; Android discovery never trusts it. */
data class ShelterAdvertisement(
    val shelterId: String,
    val peerId: String,
    val identity: ShelterBleIdentity? = null,
)

interface ShelterBleSession : AutoCloseable {
    /** Reads the bridge identity characteristic and requires it to equal the scan identity. */
    suspend fun readIdentity(): ShelterBleIdentity
    suspend fun submit(submission: RescueBleSubmission): RescueBleSubmissionResult
    override fun close()
}

data class RescueBleSubmission(val courierDeliveryId: String, val carrierId: String, val encryptedEnvelope: ByteArray) {
    init {
        require(courierDeliveryId.isNotBlank())
        require(encryptedEnvelope.size in 1..RescueBleFrameCodec.MAX_ENVELOPE_BYTES)
    }

    val envelopeSha256Hex: String = RescueCryptography.sha256Hex(encryptedEnvelope)
}

/**
 * Exact wire codec for pc-ble-bridge/Protocol/GattFrame.cs.
 *
 * A frame is [kind, payloadLength, sequence:u16be, total:u16be, sessionId:4,
 * payload:0..10].  Values never exceed 20 bytes, which remains safe at ATT
 * MTU 23.  The START/CHUNK/COMMIT ordering is enforced by the bridge.
 */
object RescueBleFrameCodec {
    const val ATT_PAYLOAD_BYTES = 20
    const val HEADER_BYTES = 10
    const val CHUNK_BYTES = ATT_PAYLOAD_BYTES - HEADER_BYTES
    const val MAX_ENVELOPE_BYTES = 16 * 1024
    const val SESSION_ID_BYTES = 4
    private const val START_METADATA_VERSION = 1
    private const val START_METADATA_HEADER_BYTES = 5
    private const val MAX_COURIER_DELIVERY_ID_BYTES = 256
    private const val MAX_CARRIER_ID_BYTES = 128

    enum class Kind(val wireValue: Int) {
        START(1), CHUNK(2), COMMIT(3), RESULT(4), ABORT(5);
        companion object { fun fromWire(value: Int): Kind? = entries.firstOrNull { it.wireValue == value } }
    }

    data class Frame(val kind: Kind, val sequence: Int, val total: Int, val sessionId: ByteArray, val payload: ByteArray) {
        init {
            require(sequence in 0..0xffff && total in 0..0xffff)
            require(sessionId.size == SESSION_ID_BYTES)
            require(payload.size <= CHUNK_BYTES)
        }

        fun encode(): ByteArray = ByteArray(HEADER_BYTES + payload.size).also { output ->
            output[0] = kind.wireValue.toByte()
            output[1] = payload.size.toByte()
            output[2] = (sequence ushr 8).toByte(); output[3] = sequence.toByte()
            output[4] = (total ushr 8).toByte(); output[5] = total.toByte()
            sessionId.copyInto(output, destinationOffset = 6)
            payload.copyInto(output, destinationOffset = HEADER_BYTES)
        }
    }

    fun decode(bytes: ByteArray): Frame? {
        if (bytes.size !in HEADER_BYTES..ATT_PAYLOAD_BYTES) return null
        val payloadLength = bytes[1].toInt() and 0xff
        if (payloadLength > CHUNK_BYTES || bytes.size != HEADER_BYTES + payloadLength) return null
        val kind = Kind.fromWire(bytes[0].toInt() and 0xff) ?: return null
        val sequence = ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
        val total = ((bytes[4].toInt() and 0xff) shl 8) or (bytes[5].toInt() and 0xff)
        return Frame(kind, sequence, total, bytes.copyOfRange(6, HEADER_BYTES), bytes.copyOfRange(HEADER_BYTES, bytes.size))
    }

    /**
     * Encodes the PC bridge's authenticated-delivery metadata before any opaque envelope bytes.
     *
     * Wire format is deliberately byte-for-byte compatible with
     * `DeliveryStartMetadata.Encode` in pc-ble-bridge:
     * `[version=1, courierLength:u16be, carrierLength:u16be, courier ASCII, carrier ASCII]`.
     * The metadata is fragmented using ordinary 10-byte START payloads, so every START is
     * completely written before the first CHUNK frame.
     */
    fun startFrames(courierDeliveryId: String, carrierId: String, sessionId: ByteArray): List<ByteArray> {
        val courierBytes = asciiIdentifier(courierDeliveryId, MAX_COURIER_DELIVERY_ID_BYTES, "courierDeliveryId")
        val carrierBytes = asciiIdentifier(carrierId, MAX_CARRIER_ID_BYTES, "carrierId")
        val metadata = ByteArray(START_METADATA_HEADER_BYTES + courierBytes.size + carrierBytes.size)
        metadata[0] = START_METADATA_VERSION.toByte()
        writeUnsignedShortBigEndian(metadata, 1, courierBytes.size)
        writeUnsignedShortBigEndian(metadata, 3, carrierBytes.size)
        courierBytes.copyInto(metadata, START_METADATA_HEADER_BYTES)
        carrierBytes.copyInto(metadata, START_METADATA_HEADER_BYTES + courierBytes.size)
        return fragment(Kind.START, metadata, sessionId)
    }
    fun abort(sessionId: ByteArray): ByteArray = Frame(Kind.ABORT, 0, 1, sessionId, byteArrayOf()).encode()

    fun chunkFrames(envelope: ByteArray, sessionId: ByteArray): List<ByteArray> = fragment(Kind.CHUNK, envelope, sessionId)
    fun commitFrames(envelope: ByteArray, sessionId: ByteArray): List<ByteArray> =
        fragment(Kind.COMMIT, MessageDigest.getInstance("SHA-256").digest(envelope), sessionId)

    /** Stable retry session derived from the durable courier delivery id; no request bytes are exposed. */
    fun sessionIdFor(courierDeliveryId: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(courierDeliveryId.encodeToByteArray()).copyOf(SESSION_ID_BYTES)

    private fun fragment(kind: Kind, bytes: ByteArray, sessionId: ByteArray): List<ByteArray> {
        require(bytes.size <= MAX_ENVELOPE_BYTES)
        require(sessionId.size == SESSION_ID_BYTES)
        val total = maxOf(1, (bytes.size + CHUNK_BYTES - 1) / CHUNK_BYTES)
        require(total <= 0xffff)
        return (0 until total).map { index ->
            val from = index * CHUNK_BYTES
            val to = minOf(bytes.size, from + CHUNK_BYTES)
            Frame(kind, index, total, sessionId, if (from == to) byteArrayOf() else bytes.copyOfRange(from, to)).encode()
        }
    }

    private fun writeUnsignedShortBigEndian(destination: ByteArray, offset: Int, value: Int) {
        require(value in 0..0xffff)
        destination[offset] = (value ushr 8).toByte()
        destination[offset + 1] = value.toByte()
    }

    private fun asciiIdentifier(value: String, maximumBytes: Int, field: String): ByteArray {
        require(value.isNotEmpty()) { "$field must not be empty" }
        require(value.all { it.isAsciiIdentifierCharacter() }) { "$field must be ASCII identifier text" }
        // Every permitted character is one byte in UTF-8; encode only after validation so the
        // declared u16 values cannot differ from the bridge's strict UTF-8 byte lengths.
        val encoded = value.encodeToByteArray()
        require(encoded.size <= maximumBytes) { "$field is too long" }
        return encoded
    }

    private fun Char.isAsciiIdentifierCharacter(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '-' || this == '_' || this == '.' || this == ':'
}

sealed interface RescueBleSubmissionResult {
    data class Accepted(val receipt: SignedShelterReceipt) : RescueBleSubmissionResult
    data class Duplicate(val receipt: SignedShelterReceipt) : RescueBleSubmissionResult
    data class Rejected(val code: String) : RescueBleSubmissionResult
}

/** Test/offline adapter. It never discovers a PC by itself. */
class FakeShelterBleClient(
    override val advertisements: Flow<ShelterAdvertisement> = emptyFlow(),
    private val sessionFactory: suspend (ShelterAdvertisement) -> ShelterBleSession = { error("No fake BLE session configured") },
) : ShelterBleClient {
    override suspend fun connect(advertisement: ShelterAdvertisement) = sessionFactory(advertisement)
}
