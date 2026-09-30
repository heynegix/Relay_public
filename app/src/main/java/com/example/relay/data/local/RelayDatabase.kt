package com.example.relay.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.example.relay.domain.MessagePriority
import com.example.relay.domain.MessageStatus
import com.example.relay.domain.MessageType
import com.example.relay.domain.RelayRecordType
import com.example.relay.domain.ReceiptType

class RelayConverters {
    @TypeConverter fun messageType(value: MessageType): String = value.name
    @TypeConverter fun messageType(value: String): MessageType = MessageType.valueOf(value)
    @TypeConverter fun priority(value: MessagePriority): String = value.name
    @TypeConverter fun priority(value: String): MessagePriority = MessagePriority.valueOf(value)
    @TypeConverter fun status(value: MessageStatus): String = value.name
    @TypeConverter fun status(value: String): MessageStatus = MessageStatus.valueOf(value)
    @TypeConverter fun recordType(value: RelayRecordType): String = value.name
    @TypeConverter fun recordType(value: String): RelayRecordType = RelayRecordType.valueOf(value)
    @TypeConverter fun receiptType(value: ReceiptType): String = value.name
    @TypeConverter fun receiptType(value: String): ReceiptType = runCatching { ReceiptType.valueOf(value) }.getOrDefault(ReceiptType.GATEWAY_RECEIVED_UNVERIFIED)
}

@Database(
    entities = [
        MessageEntity::class,
        MessageDeliveryEntity::class,
        DeliveryReceiptEntity::class,
        RescueEntity::class,
        BrokerLedgerEntity::class,
        RegionalDirectoryEntity::class,
        ActiveRescueSessionEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
@TypeConverters(RelayConverters::class)
abstract class RelayDatabase : RoomDatabase() {
    abstract fun relayDao(): RelayDao
    abstract fun rescueDao(): RescueDao
    abstract fun brokerLedgerDao(): BrokerLedgerDao
    abstract fun regionalDirectoryDao(): RegionalDirectoryDao
    abstract fun activeRescueSessionDao(): ActiveRescueSessionDao
}
