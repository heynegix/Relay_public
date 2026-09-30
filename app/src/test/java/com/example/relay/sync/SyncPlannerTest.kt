package com.example.relay.sync

import com.example.relay.NOW
import com.example.relay.domain.InMemoryMessageRepository
import com.example.relay.domain.MessageDelivery
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MessagePriority
import com.example.relay.domain.MutableClock
import com.example.relay.domain.RelayRecordType
import com.example.relay.domain.StatusChangePayload
import com.example.relay.domain.ReportStatus
import com.example.relay.message
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlannerTest {
    @Test
    fun `difference excludes held ids and send order is deterministic`() = runBlocking {
        val repository = InMemoryMessageRepository()
        val policy = MessagePolicy(MutableClock(NOW))
        val planner = SyncPlanner(repository, policy)
        repository.insert(message("low", MessagePriority.LOW))
        repository.insert(message("critical", MessagePriority.CRITICAL))
        repository.insert(message("high", MessagePriority.HIGH))

        val template = planner.manifest().first()
        val remoteManifest = listOf(template.copy(messageId = "critical"), template.copy(messageId = "remote-only"))
        assertEquals(listOf("remote-only"), planner.missingFromLocal(remoteManifest))

        assertEquals(
            listOf("critical", "high", "low"),
            planner.messagesToSend("peer-B", listOf("low", "critical", "high")).map { it.messageId },
        )
    }

    @Test
    fun `expired and hop limited messages are excluded but explicit repair requests resend acked data`() = runBlocking {
        val repository = InMemoryMessageRepository()
        val policy = MessagePolicy(MutableClock(NOW))
        val planner = SyncPlanner(repository, policy)
        repository.insert(message(
            "expired",
            createdAt = NOW - policy.limits.clockSkewToleranceMillis - 60_000,
            expiresAt = NOW - policy.limits.clockSkewToleranceMillis,
        ))
        repository.insert(message("hop", hopCount = 2, maxHopCount = 2))
        repository.insert(message("acked"))
        repository.markAcknowledged(MessageDelivery("acked", "peer-B", NOW, "packet"))

        assertEquals(
            listOf("acked"),
            planner.messagesToSend("peer-B", listOf("expired", "hop", "acked")).map { it.messageId },
        )
    }

    @Test
    fun `status change is sent before reports`() = runBlocking {
        val repository = InMemoryMessageRepository()
        val policy = MessagePolicy(MutableClock(NOW))
        val planner = SyncPlanner(repository, policy)
        repository.insert(message("report", MessagePriority.CRITICAL))
        repository.insert(message("change", MessagePriority.NORMAL).copy(
            recordType = RelayRecordType.STATUS_CHANGE,
            payload = StatusChangePayload("change", "report", ReportStatus.RESOLVED, "resolved", NOW, "device-A"),
        ))
        assertEquals(listOf("change", "report"), planner.messagesToSend("peer-B", listOf("report", "change")).map { it.messageId })
    }
}
