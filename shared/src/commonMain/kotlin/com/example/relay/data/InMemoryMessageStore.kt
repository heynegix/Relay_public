package com.example.relay.data

import com.example.relay.domain.DeliveryReceipt
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.RelayMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Simple multiplatform store for iOS / shared drills (Android production still uses Room). */
class InMemoryMessageStore {
    private val mutex = Mutex()
    private val messages = linkedMapOf<String, RelayMessage>()
    private val receipts = mutableListOf<DeliveryReceipt>()
    private val _messages = MutableStateFlow<List<RelayMessage>>(emptyList())
    val messagesFlow: StateFlow<List<RelayMessage>> = _messages.asStateFlow()

    suspend fun insert(message: RelayMessage): Boolean = mutex.withLock {
        if (messages.containsKey(message.messageId)) return false
        messages[message.messageId] = message
        publish()
        true
    }

    suspend fun all(): List<RelayMessage> = mutex.withLock { messages.values.toList() }

    suspend fun find(id: String): RelayMessage? = mutex.withLock { messages[id] }

    suspend fun insertReceipt(receipt: DeliveryReceipt) = mutex.withLock {
        if (receipts.none { it.receiptId == receipt.receiptId }) {
            receipts += receipt
        }
    }

    suspend fun receiptsFor(messageId: String): List<DeliveryReceipt> = mutex.withLock {
        receipts.filter { it.messageId == messageId }
    }

    suspend fun pruneExpired(policy: MessagePolicy) = mutex.withLock {
        val doomed = messages.values.filterNot { policy.isActive(it) }.map { it.messageId }
        doomed.forEach { messages.remove(it) }
        receipts.removeAll { it.messageId in doomed }
        publish()
    }

    private fun publish() {
        _messages.value = messages.values.sortedByDescending { it.createdAt }
    }
}
