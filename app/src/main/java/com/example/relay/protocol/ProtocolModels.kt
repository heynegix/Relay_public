package com.example.relay.protocol

import com.example.relay.domain.MessagePriority
import com.example.relay.domain.MessageType
import com.example.relay.domain.RelayMessage
import com.example.relay.domain.DeliveryReceipt
import com.example.relay.domain.RelayRecordType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

const val CURRENT_PROTOCOL_VERSION = 1

object PacketTypes {
    const val HELLO = "HELLO"
    const val MANIFEST = "MANIFEST"
    const val MESSAGE_REQUEST = "MESSAGE_REQUEST"
    const val MESSAGE_DATA = "MESSAGE_DATA"
    const val ACK = "ACK"
    const val ERROR = "ERROR"
    const val RECEIPT_DATA = "RECEIPT_DATA"
}

@Serializable
data class WireEnvelope(
    val protocolVersion: Int,
    val packetType: String,
    val packetId: String,
    val senderDeviceId: String,
    val sentAt: Long,
    val body: JsonElement,
)

sealed interface PacketBody { val type: String }

@Serializable
data class HelloBody(val displayName: String = "Relay device") : PacketBody {
    override val type: String get() = PacketTypes.HELLO
}

@Serializable
data class ManifestEntry(
    val messageId: String,
    val messageType: MessageType,
    val priority: MessagePriority,
    val createdAt: Long,
    val expiresAt: Long,
    val hopCount: Int,
    val maxHopCount: Int,
    val transferable: Boolean,
    val recordType: RelayRecordType = RelayRecordType.REPORT,
    val lifetimeMs: Long = 0,
    val accumulatedAgeMs: Long = 0,
)

@Serializable
data class ManifestBody(val entries: List<ManifestEntry>, val receiptIds: List<String> = emptyList()) : PacketBody {
    override val type: String get() = PacketTypes.MANIFEST
}

@Serializable
data class MessageRequestBody(val messageIds: List<String>, val receiptIds: List<String> = emptyList()) : PacketBody {
    override val type: String get() = PacketTypes.MESSAGE_REQUEST
}

@Serializable
data class MessageDataBody(val message: RelayMessage) : PacketBody {
    override val type: String get() = PacketTypes.MESSAGE_DATA
}

@Serializable
data class ReceiptDataBody(val receipt: DeliveryReceipt) : PacketBody {
    override val type: String get() = PacketTypes.RECEIPT_DATA
}

@Serializable
data class AckBody(val messageId: String, val dataPacketId: String, val peerReceipt: DeliveryReceipt? = null) : PacketBody {
    override val type: String get() = PacketTypes.ACK
}

@Serializable
data class ErrorBody(val code: String, val detail: String = "") : PacketBody {
    override val type: String get() = PacketTypes.ERROR
}

data class DecodedPacket(val envelope: WireEnvelope, val body: PacketBody)

enum class DecodeError {
    PAYLOAD_TOO_LARGE,
    MALFORMED_JSON,
    UNKNOWN_PROTOCOL_VERSION,
    UNKNOWN_PACKET_TYPE,
    INVALID_ENVELOPE,
    INVALID_BODY,
}

sealed interface DecodeResult {
    data class Success(val packet: DecodedPacket) : DecodeResult
    data class Failure(val error: DecodeError, val detail: String = "") : DecodeResult
}

data class ProtocolLimits(
    val maxPacketBytes: Int = 64 * 1024,
    val maxManifestEntries: Int = 512,
    val maxRequestEntries: Int = 128,
    val maxIdentifierChars: Int = 64,
    val maxErrorDetailChars: Int = 160,
)
