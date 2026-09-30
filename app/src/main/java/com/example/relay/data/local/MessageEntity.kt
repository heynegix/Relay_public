package com.example.relay.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.relay.domain.MessagePriority
import com.example.relay.domain.MessageStatus
import com.example.relay.domain.MessageType
import com.example.relay.domain.RelayRecordType
import com.example.relay.domain.ReceiptType
import androidx.room.Index

@Entity(tableName = "messages", indices = [Index("originDeviceId")])
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val messageType: MessageType,
    val createdAt: Long,
    val expiresAt: Long,
    val priority: MessagePriority,
    val originDeviceId: String,
    val payloadJson: String,
    val hopCount: Int,
    val maxHopCount: Int,
    val status: MessageStatus,
    val receivedAt: Long,
    val recordType: RelayRecordType,
    val lifetimeMs: Long,
    val accumulatedAgeMs: Long,
    val receivedElapsedRealtimeMs: Long,
    val persistedAtWallClockMs: Long,
    val elapsedRealtimeSessionId: String,
    val reportSignatureJson: String?,
)

@Entity(tableName = "message_deliveries", primaryKeys = ["messageId", "peerDeviceId"])
data class MessageDeliveryEntity(
    val messageId: String,
    val peerDeviceId: String,
    val acknowledgedAt: Long,
    val packetId: String,
)

@Entity(tableName = "delivery_receipts", indices = [Index(value = ["messageId", "receiptType", "actorId"], unique = true), Index("messageId")])
data class DeliveryReceiptEntity(
    @PrimaryKey val receiptId: String,
    val messageId: String,
    val receiptType: ReceiptType,
    val actorId: String,
    val recordedAt: Long,
)
