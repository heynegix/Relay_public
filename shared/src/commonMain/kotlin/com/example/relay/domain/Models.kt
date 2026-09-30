package com.example.relay.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.example.relay.rescue.ReportSignature

@Serializable
enum class MessageType { SAFETY, SUPPLY }

@Serializable
enum class RelayRecordType { REPORT, STATUS_CHANGE }

@Serializable
enum class ReportStatus { ACTIVE, RESOLVED, RETRACTED }

@Serializable
enum class MessagePriority { LOW, NORMAL, HIGH, CRITICAL }

@Serializable
enum class MessageStatus { CREATED, RECEIVED }

@Serializable
enum class SafetyState { SAFE, INJURED, EVACUATING, AT_SHELTER }

@Serializable
enum class SupplyKind { WATER, FOOD, MEDICINE, BLANKET, POWER, HYGIENE, OTHER }

@Serializable
enum class ReceiptType { PEER_RECEIVED, GATEWAY_RECEIVED, GATEWAY_RECEIVED_UNVERIFIED }

@Serializable
sealed interface MessagePayload

@Serializable
@SerialName("safety")
data class SafetyPayload(
    val state: SafetyState,
    val companionCount: Int,
    val approximateLocation: String,
    val note: String,
) : MessagePayload

@Serializable
@SerialName("supply")
data class SupplyPayload(
    val kind: SupplyKind,
    val requiredCount: Int,
    val approximateLocation: String,
    val note: String,
    val otherLabel: String? = null,
) : MessagePayload

@Serializable
@SerialName("status_change")
data class StatusChangePayload(
    val eventId: String,
    val targetMessageId: String,
    val newStatus: ReportStatus,
    val reason: String,
    val createdAt: Long,
    val createdBy: String,
) : MessagePayload

@Serializable
data class RelayMessage(
    val messageId: String,
    val messageType: MessageType,
    val createdAt: Long,
    val expiresAt: Long,
    val priority: MessagePriority,
    val originDeviceId: String,
    val payload: MessagePayload,
    val hopCount: Int = 0,
    val maxHopCount: Int = 8,
    val status: MessageStatus,
    val receivedAt: Long,
    val recordType: RelayRecordType = RelayRecordType.REPORT,
    val lifetimeMs: Long = (expiresAt - createdAt).coerceAtLeast(0),
    val accumulatedAgeMs: Long = 0,
    val receivedElapsedRealtimeMs: Long = 0,
    val persistedAtWallClockMs: Long = receivedAt,
    val elapsedRealtimeSessionId: String = "",
    val reportSignature: ReportSignature? = null,
)

@Serializable
data class DeliveryReceipt(
    val receiptId: String,
    val messageId: String,
    val receiptType: ReceiptType,
    val actorId: String,
    val recordedAt: Long,
)

data class PayloadTransfer(
    val messageId: String,
    val peerId: String,
    val packetId: String,
    val completedAt: Long,
)

enum class DeliveryPresentation {
    NOT_CONFIRMED,
    NEARBY_PAYLOAD_COMPLETE,
    PEER_RECEIVED,
    GATEWAY_RECEIVED_UNVERIFIED,
    GATEWAY_RECEIVED,
}

fun deriveDeliveryPresentation(
    receipts: Collection<DeliveryReceipt>,
    payloadTransferred: Boolean = false,
): DeliveryPresentation = when {
    receipts.any { it.receiptType == ReceiptType.GATEWAY_RECEIVED } -> DeliveryPresentation.GATEWAY_RECEIVED
    receipts.any { it.receiptType == ReceiptType.GATEWAY_RECEIVED_UNVERIFIED } ->
        DeliveryPresentation.GATEWAY_RECEIVED_UNVERIFIED
    receipts.any { it.receiptType == ReceiptType.PEER_RECEIVED } -> DeliveryPresentation.PEER_RECEIVED
    payloadTransferred -> DeliveryPresentation.NEARBY_PAYLOAD_COMPLETE
    else -> DeliveryPresentation.NOT_CONFIRMED
}

/** Trust-safe UI copy shared by Android Compose and iOS Compose Multiplatform. */
fun deliveryPresentationLabel(presentation: DeliveryPresentation): String = when (presentation) {
    DeliveryPresentation.GATEWAY_RECEIVED -> "中継拠点へ保存済み（認証経路・内容は未検証）"
    DeliveryPresentation.GATEWAY_RECEIVED_UNVERIFIED -> "中継拠点へ保存済み（未認証・公開LAN）"
    DeliveryPresentation.PEER_RECEIVED -> "近くの端末へ保存済み（最終配信ではありません）"
    DeliveryPresentation.NEARBY_PAYLOAD_COMPLETE -> "転送完了（相手端末の保存確認前）"
    DeliveryPresentation.NOT_CONFIRMED -> "転送待ち"
}

fun SafetyState.labelJa(): String = when (this) {
    SafetyState.SAFE -> "無事"
    SafetyState.INJURED -> "けががあり"
    SafetyState.EVACUATING -> "避難中"
    SafetyState.AT_SHELTER -> "避難所に到着"
}

fun SupplyKind.labelJa(): String = when (this) {
    SupplyKind.WATER -> "水"
    SupplyKind.FOOD -> "食料"
    SupplyKind.MEDICINE -> "薬"
    SupplyKind.BLANKET -> "毛布"
    SupplyKind.POWER -> "電源"
    SupplyKind.HYGIENE -> "衛生用品"
    SupplyKind.OTHER -> "その他"
}
