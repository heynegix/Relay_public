package com.example.relay.ui

import app.cash.turbine.test
import com.example.relay.NOW
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MessagePriority
import com.example.relay.domain.MessageStatus
import com.example.relay.domain.MessageType
import com.example.relay.domain.MutableClock
import com.example.relay.domain.RelayMessage
import com.example.relay.domain.RelayRecordType
import com.example.relay.domain.ReportStatus
import com.example.relay.domain.StatusChangePayload
import com.example.relay.message
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RegionalMessageSelectionTest {
    @Test
    fun `status change is projected onto its report without becoming another card`() {
        val policy = MessagePolicy(MutableClock(NOW))
        val report = message(id = "report-1", createdAt = NOW - 1_000)
        val change = statusChange(
            id = "change-1",
            target = report.messageId,
            status = ReportStatus.RESOLVED,
            createdAt = NOW,
        )

        val selected = selectRegionalMessageItems(listOf(change, report), policy)

        assertEquals(listOf("report-1"), selected.map { it.report.messageId })
        assertEquals(ReportStatus.RESOLVED, selected.single().reportStatus)
        assertEquals(true, selected.single().isStatusUpdateUnverified)
        assertEquals("報告状態: 解決済み（未検証の更新）", regionalReportStatusText(selected.single()))
    }

    @Test
    fun `latest status event wins with stable event and message id tie breaks`() {
        val policy = MessagePolicy(MutableClock(NOW))
        val report = message(id = "report-1", createdAt = NOW - 1_000)
        val older = statusChange("change-z", report.messageId, ReportStatus.RETRACTED, NOW - 10)
        val sameTimeLowerEvent = statusChange(
            id = "message-z",
            target = report.messageId,
            status = ReportStatus.RESOLVED,
            createdAt = NOW,
            eventId = "event-a",
        )
        val sameTimeHigherEventLowerMessage = statusChange(
            id = "message-a",
            target = report.messageId,
            status = ReportStatus.RETRACTED,
            createdAt = NOW,
            eventId = "event-z",
        )
        val sameTimeHigherEventHigherMessage = statusChange(
            id = "message-z2",
            target = report.messageId,
            status = ReportStatus.ACTIVE,
            createdAt = NOW,
            eventId = "event-z",
        )

        val selected = selectRegionalMessageItems(
            listOf(report, sameTimeHigherEventLowerMessage, older, sameTimeLowerEvent, sameTimeHigherEventHigherMessage),
            policy,
        )

        assertEquals(ReportStatus.ACTIVE, selected.single().reportStatus)
    }

    @Test
    fun `status update is ignored unless its claimed creator matches message and report origins`() {
        val policy = MessagePolicy(MutableClock(NOW + 10))
        val report = message(id = "report-1")
        val messageOriginMismatch = statusChange(
            id = "change-1",
            target = report.messageId,
            status = ReportStatus.RESOLVED,
            createdAt = NOW,
            origin = "device-B",
            createdBy = "device-A",
        )
        val reportOriginMismatch = statusChange(
            id = "change-2",
            target = report.messageId,
            status = ReportStatus.RETRACTED,
            createdAt = NOW + 1,
            origin = "device-B",
            createdBy = "device-B",
        )

        val selected = selectRegionalMessageItems(listOf(report, messageOriginMismatch, reportOriginMismatch), policy)

        assertEquals(ReportStatus.ACTIVE, selected.single().reportStatus)
        assertEquals(false, selected.single().isStatusUpdateUnverified)
    }

    @Test
    fun `status update with an inconsistent event timestamp is ignored`() {
        val policy = MessagePolicy(MutableClock(NOW))
        val report = message(id = "report-1", createdAt = NOW - 1_000)
        val inconsistent = statusChange(
            id = "change-1",
            target = report.messageId,
            status = ReportStatus.RETRACTED,
            createdAt = NOW,
        ).copy(
            payload = StatusChangePayload(
                eventId = "change-1",
                targetMessageId = report.messageId,
                newStatus = ReportStatus.RETRACTED,
                reason = "updated",
                createdAt = NOW + 1,
                createdBy = report.originDeviceId,
            ),
        )

        val selected = selectRegionalMessageItems(listOf(report, inconsistent), policy)

        assertEquals(ReportStatus.ACTIVE, selected.single().reportStatus)
        assertEquals(false, selected.single().isStatusUpdateUnverified)
    }

    @Test
    fun `expired status update does not change an active report`() {
        val clock = MutableClock(NOW)
        val policy = MessagePolicy(clock)
        val report = message(id = "report-1", createdAt = NOW - 1_000, expiresAt = NOW + 60_000)
        val expired = statusChange(
            id = "change-1",
            target = report.messageId,
            status = ReportStatus.RESOLVED,
            createdAt = NOW - 60_000,
        )

        val selected = selectRegionalMessageItems(listOf(report, expired), policy)

        assertEquals(ReportStatus.ACTIVE, selected.single().reportStatus)
        assertEquals(false, selected.single().isStatusUpdateUnverified)
    }

    @Test
    fun `expired messages are excluded from regional display`() {
        val clock = MutableClock(NOW + 1_000).apply {
            currentElapsedRealtimeMillis = NOW + 1_000
        }
        val policy = MessagePolicy(clock)

        val selected = selectRegionalMessages(
            listOf(
                message(id = "expired", expiresAt = NOW + 1_000),
                message(id = "active", expiresAt = NOW + 2_000),
            ),
            policy,
        )

        assertEquals(listOf("active"), selected.map { it.messageId })
    }

    @Test
    fun `regional display keeps priority created expiry and id ordering`() {
        val clock = MutableClock(NOW)
        val policy = MessagePolicy(clock)
        val messages = listOf(
            message(id = "normal", priority = MessagePriority.NORMAL, expiresAt = NOW + 30_000),
            message(id = "high-new", priority = MessagePriority.HIGH, expiresAt = NOW + 30_000),
            message(
                id = "high-old-late-b",
                priority = MessagePriority.HIGH,
                createdAt = NOW - 1_000,
                expiresAt = NOW + 20_000,
            ),
            message(
                id = "high-old-early",
                priority = MessagePriority.HIGH,
                createdAt = NOW - 1_000,
                expiresAt = NOW + 10_000,
            ),
            message(
                id = "high-old-late-a",
                priority = MessagePriority.HIGH,
                createdAt = NOW - 1_000,
                expiresAt = NOW + 20_000,
            ),
            message(id = "critical", priority = MessagePriority.CRITICAL, expiresAt = NOW + 30_000),
        )

        assertEquals(
            listOf(
                "critical",
                "high-new",
                "high-old-early",
                "high-old-late-a",
                "high-old-late-b",
                "normal",
            ),
            selectRegionalMessages(messages, policy).map { it.messageId },
        )
    }

    @Test
    fun `ticker re-evaluates TTL without a repository update`() = runTest {
        val clock = MutableClock(NOW)
        val stored = MutableStateFlow(listOf(message(id = "soon-expired", expiresAt = NOW + 1_000)))
        val ticks = MutableSharedFlow<Unit>()

        regionalMessagesFlow(stored, ticks, MessagePolicy(clock)).test {
            assertEquals(listOf("soon-expired"), awaitItem().map { it.messageId })

            clock.currentMillis = NOW + 1_000
            clock.currentElapsedRealtimeMillis = NOW + 1_000
            ticks.emit(Unit)

            assertEquals(emptyList<String>(), awaitItem().map { it.messageId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun statusChange(
        id: String,
        target: String,
        status: ReportStatus,
        createdAt: Long,
        eventId: String = id,
        origin: String = "device-A",
        createdBy: String = origin,
    ): RelayMessage = RelayMessage(
        messageId = id,
        messageType = MessageType.SAFETY,
        createdAt = createdAt,
        expiresAt = createdAt + 60_000,
        priority = MessagePriority.CRITICAL,
        originDeviceId = origin,
        payload = StatusChangePayload(
            eventId = eventId,
            targetMessageId = target,
            newStatus = status,
            reason = "updated",
            createdAt = createdAt,
            createdBy = createdBy,
        ),
        status = MessageStatus.RECEIVED,
        receivedAt = createdAt,
        recordType = RelayRecordType.STATUS_CHANGE,
        lifetimeMs = 60_000,
        receivedElapsedRealtimeMs = createdAt,
        persistedAtWallClockMs = createdAt,
        elapsedRealtimeSessionId = "test-session",
    )
}
