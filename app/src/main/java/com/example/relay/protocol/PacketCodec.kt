package com.example.relay.protocol

import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MessageValidation
import com.example.relay.domain.ReceiptType
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

class PacketCodec(
    private val messagePolicy: MessagePolicy,
    private val limits: ProtocolLimits = ProtocolLimits(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "payloadType"
    },
) {
    fun encode(
        senderDeviceId: String,
        sentAt: Long,
        body: PacketBody,
        packetId: String = UUID.randomUUID().toString(),
    ): ByteArray {
        val bodyElement = when (body) {
            is HelloBody -> json.encodeToJsonElement(HelloBody.serializer(), body)
            is ManifestBody -> json.encodeToJsonElement(ManifestBody.serializer(), body)
            is MessageRequestBody -> json.encodeToJsonElement(MessageRequestBody.serializer(), body)
            is MessageDataBody -> json.encodeToJsonElement(MessageDataBody.serializer(), body)
            is ReceiptDataBody -> json.encodeToJsonElement(ReceiptDataBody.serializer(), body)
            is AckBody -> json.encodeToJsonElement(AckBody.serializer(), body)
            is ErrorBody -> json.encodeToJsonElement(ErrorBody.serializer(), body)
        }
        return json.encodeToString(
            WireEnvelope.serializer(),
            WireEnvelope(CURRENT_PROTOCOL_VERSION, body.type, packetId, senderDeviceId, sentAt, bodyElement),
        ).encodeToByteArray()
    }

    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.size > limits.maxPacketBytes) return DecodeResult.Failure(DecodeError.PAYLOAD_TOO_LARGE)
        val envelope = try {
            json.decodeFromString(WireEnvelope.serializer(), bytes.decodeToString())
        } catch (_: Exception) {
            return DecodeResult.Failure(DecodeError.MALFORMED_JSON)
        }
        if (envelope.protocolVersion != CURRENT_PROTOCOL_VERSION) {
            return DecodeResult.Failure(DecodeError.UNKNOWN_PROTOCOL_VERSION)
        }
        if (!validId(envelope.packetId) || !validId(envelope.senderDeviceId) || envelope.sentAt < 0) {
            return DecodeResult.Failure(DecodeError.INVALID_ENVELOPE)
        }
        val body = try {
            when (envelope.packetType) {
                PacketTypes.HELLO -> json.decodeFromJsonElement(HelloBody.serializer(), envelope.body)
                PacketTypes.MANIFEST -> json.decodeFromJsonElement(ManifestBody.serializer(), envelope.body)
                PacketTypes.MESSAGE_REQUEST -> json.decodeFromJsonElement(MessageRequestBody.serializer(), envelope.body)
                PacketTypes.MESSAGE_DATA -> json.decodeFromJsonElement(MessageDataBody.serializer(), envelope.body)
                PacketTypes.RECEIPT_DATA -> json.decodeFromJsonElement(ReceiptDataBody.serializer(), envelope.body)
                PacketTypes.ACK -> json.decodeFromJsonElement(AckBody.serializer(), envelope.body)
                PacketTypes.ERROR -> json.decodeFromJsonElement(ErrorBody.serializer(), envelope.body)
                else -> return DecodeResult.Failure(DecodeError.UNKNOWN_PACKET_TYPE)
            }
        } catch (_: Exception) {
            return DecodeResult.Failure(DecodeError.INVALID_BODY)
        }
        val invalid = validateBody(body, envelope.senderDeviceId)
        return if (invalid == null) DecodeResult.Success(DecodedPacket(envelope, body))
        else DecodeResult.Failure(DecodeError.INVALID_BODY, invalid)
    }

    private fun validateBody(body: PacketBody, senderDeviceId: String): String? = when (body) {
        is HelloBody -> if (body.displayName.length <= 64) null else "display name too long"
        is ManifestBody -> when {
            body.entries.size > limits.maxManifestEntries -> "manifest too large"
            body.receiptIds.size > limits.maxManifestEntries -> "receipt manifest too large"
            body.receiptIds.distinct().size != body.receiptIds.size -> "duplicate receipt manifest id"
            body.receiptIds.any { !validId(it) } -> "invalid receipt manifest id"
            body.entries.any { !validId(it.messageId) || it.hopCount < 0 || it.maxHopCount < 1 || it.hopCount > it.maxHopCount } -> "invalid manifest entry"
            else -> null
        }
        is MessageRequestBody -> when {
            body.messageIds.size > limits.maxRequestEntries -> "request too large"
            body.receiptIds.size > limits.maxRequestEntries -> "receipt request too large"
            body.messageIds.distinct().size != body.messageIds.size -> "duplicate request id"
            body.receiptIds.distinct().size != body.receiptIds.size -> "duplicate receipt request id"
            body.messageIds.any { !validId(it) } -> "invalid request id"
            body.receiptIds.any { !validId(it) } -> "invalid receipt request id"
            else -> null
        }
        is MessageDataBody -> when (val validation = messagePolicy.validate(body.message)) {
            MessageValidation.Valid -> null
            is MessageValidation.Invalid -> validation.reason
        }
        is ReceiptDataBody -> if (validId(body.receipt.receiptId) && validId(body.receipt.messageId) &&
            validId(body.receipt.actorId) && body.receipt.recordedAt >= 0
        ) null else "invalid receipt"
        is AckBody -> when {
            !validId(body.messageId) || !validId(body.dataPacketId) -> "invalid ack"
            body.peerReceipt == null -> "peer receipt required"
            !validId(body.peerReceipt.receiptId) || !validId(body.peerReceipt.actorId) -> "invalid peer receipt"
            body.peerReceipt.receiptType != ReceiptType.PEER_RECEIVED -> "peer receipt type required"
            body.peerReceipt.messageId != body.messageId -> "peer receipt message mismatch"
            body.peerReceipt.actorId != senderDeviceId -> "peer receipt actor mismatch"
            body.peerReceipt.recordedAt < 0 -> "invalid peer receipt time"
            else -> null
        }
        is ErrorBody -> if (body.code.length in 1..32 && body.detail.length <= limits.maxErrorDetailChars) null else "invalid error"
    }

    private fun validId(value: String): Boolean = value.length in 1..limits.maxIdentifierChars
}
