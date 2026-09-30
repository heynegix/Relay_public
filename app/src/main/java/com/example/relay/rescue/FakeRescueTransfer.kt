package com.example.relay.rescue

import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.RescueValidationResult
import com.example.relay.rescue.validate
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class RescueTransferPacket(
    val envelope: EncryptedRescueEnvelope,
)

interface RescueTransferCodec {
    fun encode(envelope: EncryptedRescueEnvelope): ByteArray
    fun decode(bytes: ByteArray): EncryptedRescueEnvelope
}

class JsonRescueTransferCodec(
    private val maxPacketBytes: Int = 1_500_000,
) : RescueTransferCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    init {
        require(maxPacketBytes > 0)
    }

    override fun encode(envelope: EncryptedRescueEnvelope): ByteArray {
        require(envelope.validate() == RescueValidationResult.Valid)
        return json.encodeToString(RescueTransferPacket(envelope)).encodeToByteArray().also {
            require(it.size <= maxPacketBytes) { "rescue_packet_too_large" }
        }
    }

    override fun decode(bytes: ByteArray): EncryptedRescueEnvelope {
        require(bytes.isNotEmpty() && bytes.size <= maxPacketBytes) { "invalid_rescue_packet_size" }
        return json.decodeFromString<RescueTransferPacket>(bytes.decodeToString()).envelope.also {
            require(it.validate() == RescueValidationResult.Valid) { "invalid_rescue_envelope" }
        }
    }
}

sealed interface RescueTransferImportResult {
    data class Stored(val record: StoredRescueRecord, val pruned: List<RescueRequestKey>) : RescueTransferImportResult
    data class Rejected(val reason: RescueStoreRejection) : RescueTransferImportResult
}

/** Fake byte-transfer boundary that can later be replaced by Nearby without changing storage. */
class FakeRescueTransferService(
    private val repository: RescueEnvelopeRepository,
    private val codec: RescueTransferCodec = JsonRescueTransferCodec(),
) {
    fun export(key: RescueRequestKey, nowEpochMillis: Long): ByteArray? =
        repository.prepareForExport(key, nowEpochMillis)?.let(codec::encode)

    fun confirmExport(key: RescueRequestKey, exportedPacket: ByteArray): Boolean = runCatching {
        val exported = codec.decode(exportedPacket)
        val stored = repository.get(key)?.envelope ?: return@runCatching false
        if (exported.requestId != key.requestId ||
            exported.requestVersion != key.requestVersion ||
            exported.envelopeId != stored.envelopeId ||
            exported.ciphertextSha256Hex != stored.ciphertextSha256Hex
        ) {
            return@runCatching false
        }
        repository.recordSuccessfulExport(key, exported.hopCount)
    }.getOrDefault(false)

    fun import(packet: ByteArray, receivedAtEpochMillis: Long): RescueTransferImportResult {
        val envelope = runCatching { codec.decode(packet) }.getOrElse {
            return RescueTransferImportResult.Rejected(RescueStoreRejection.MALFORMED_PACKET)
        }
        return when (val result = repository.store(envelope, receivedAtEpochMillis)) {
            is RescueStoreResult.Stored -> RescueTransferImportResult.Stored(result.record, result.pruned)
            is RescueStoreResult.Rejected -> RescueTransferImportResult.Rejected(result.reason)
        }
    }
}
