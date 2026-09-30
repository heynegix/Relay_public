package com.example.relay.cloud

import com.example.relay.domain.InsertResult
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MessagePriority
import com.example.relay.domain.MessageRepository
import com.example.relay.domain.RelayMessage
import kotlinx.coroutines.CancellationException

fun interface NetworkOnlineDetector {
    fun isOnline(): Boolean
}

fun interface PriorityMessageSource {
    /** Remote feed of candidate messages; may include low priority — filter applied by sync. */
    suspend fun fetchCandidates(): List<RelayMessage>
}

/**
 * When the device has internet, pull **priority** messages into the local repository.
 * Offline SCF / Nearby / PC Gateway remain the disaster path; this only *adds* when online.
 */
class InternetPrioritySync(
    private val detector: NetworkOnlineDetector,
    private val source: PriorityMessageSource,
    private val repository: MessageRepository,
    private val policy: MessagePolicy,
    private val isImportant: (RelayMessage) -> Boolean = DEFAULT_IMPORTANT,
) : ServerSyncGateway {
    override suspend fun sync(): ServerSyncResult {
        if (!detector.isOnline()) return ServerSyncResult.OfflineSkipped
        val candidates = try {
            source.fetchCandidates()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return ServerSyncResult.Failed(failure.message ?: failure.javaClass.simpleName)
        }
        var inserted = 0
        var skipped = 0
        for (raw in candidates) {
            if (!isImportant(raw)) {
                skipped++
                continue
            }
            if (policy.validate(raw) !is com.example.relay.domain.MessageValidation.Valid) {
                skipped++
                continue
            }
            if (!policy.isActive(raw)) {
                skipped++
                continue
            }
            when (repository.insert(raw)) {
                InsertResult.Inserted -> inserted++
                InsertResult.Duplicate -> skipped++
                InsertResult.Collision -> skipped++
                is InsertResult.Rejected -> skipped++
            }
        }
        return ServerSyncResult.Synced(inserted = inserted, skipped = skipped)
    }

    companion object {
        val DEFAULT_IMPORTANT: (RelayMessage) -> Boolean = { message ->
            message.priority == MessagePriority.HIGH || message.priority == MessagePriority.CRITICAL
        }
    }
}

/** Empty remote feed (production default until a municipal URL is configured). */
object EmptyPriorityMessageSource : PriorityMessageSource {
    override suspend fun fetchCandidates(): List<RelayMessage> = emptyList()
}

class AlwaysOnlineDetector : NetworkOnlineDetector {
    override fun isOnline(): Boolean = true
}

class AlwaysOfflineDetector : NetworkOnlineDetector {
    override fun isOnline(): Boolean = false
}
