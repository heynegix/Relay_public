package com.example.relay.ui

import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.RelayMessage
import com.example.relay.domain.RelayRecordType
import com.example.relay.domain.ReportStatus
import com.example.relay.domain.StatusChangePayload
import com.example.relay.rescue.RescueCryptography
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart

internal const val REGIONAL_MESSAGE_REEVALUATION_MS = 60_000L

data class RegionalMessageItem(
    val report: RelayMessage,
    val reportStatus: ReportStatus,
    /** Status changes are unsigned in the MVP and must never look authoritative. */
    val isStatusUpdateUnverified: Boolean,
)

internal fun regionalReportStatusText(item: RegionalMessageItem): String {
    val status = when (item.reportStatus) {
        ReportStatus.ACTIVE -> "受付中"
        ReportStatus.RESOLVED -> "解決済み"
        ReportStatus.RETRACTED -> "取り下げ"
    }
    val signatureLabel = when {
        item.report.reportSignature == null -> ""
        RescueCryptography.verifyReport(item.report) -> "（端末署名済み）"
        else -> "（署名不正）"
    }
    return if (item.isStatusUpdateUnverified) {
        "報告状態: $status（未検証の更新）$signatureLabel"
    } else {
        "報告状態: $status$signatureLabel"
    }
}

private data class ApplicableStatusChange(
    val message: RelayMessage,
    val payload: StatusChangePayload,
)

private val statusChangeOrder = compareBy<ApplicableStatusChange> { it.payload.createdAt }
    .thenBy { it.payload.eventId }
    .thenBy { it.message.messageId }

private val regionalReportOrder = compareByDescending<RelayMessage> { it.priority.ordinal }
    .thenByDescending { it.createdAt }
    .thenBy { it.expiresAt }
    .thenBy { it.messageId }

/**
 * Projects valid, active STATUS_CHANGE records onto their REPORT without changing
 * storage. Origin checks are deliberately conservative; cryptographic authorship is
 * not available yet, so every applied update remains explicitly unverified.
 */
internal fun selectRegionalMessageItems(
    messages: List<RelayMessage>,
    policy: MessagePolicy,
): List<RegionalMessageItem> {
    val active = messages.filter(policy::isActive)
    val latestChanges = buildMap<Pair<String, String>, ApplicableStatusChange> {
        active.forEach { message ->
            if (message.recordType != RelayRecordType.STATUS_CHANGE) return@forEach
            val payload = message.payload as? StatusChangePayload ?: return@forEach
            if (payload.createdBy != message.originDeviceId || payload.createdAt != message.createdAt) return@forEach
            val candidate = ApplicableStatusChange(message, payload)
            val key = payload.targetMessageId to payload.createdBy
            val existing = get(key)
            if (existing == null || statusChangeOrder.compare(candidate, existing) > 0) {
                put(key, candidate)
            }
        }
    }

    return active
        .asSequence()
        .filter { it.recordType == RelayRecordType.REPORT }
        .sortedWith(regionalReportOrder)
        .map { report ->
            val latest = latestChanges[report.messageId to report.originDeviceId]
            RegionalMessageItem(
                report = report,
                reportStatus = latest?.payload?.newStatus ?: ReportStatus.ACTIVE,
                isStatusUpdateUnverified = latest != null,
            )
        }
        .toList()
}

/** Pure display selection; repository contents and synchronization remain unchanged. */
internal fun selectRegionalMessages(
    messages: List<RelayMessage>,
    policy: MessagePolicy,
): List<RelayMessage> = selectRegionalMessageItems(messages, policy).map(RegionalMessageItem::report)

internal fun regionalMessageItemsFlow(
    storedMessages: Flow<List<RelayMessage>>,
    ticks: Flow<Unit>,
    policy: MessagePolicy,
): Flow<List<RegionalMessageItem>> = combine(
    storedMessages,
    ticks.onStart { emit(Unit) },
) { messages, _ ->
    selectRegionalMessageItems(messages, policy)
}

/**
 * Re-evaluates expiry on both database changes and clock ticks. [ticks] starts
 * only while the returned flow has a collector.
 */
internal fun regionalMessagesFlow(
    storedMessages: Flow<List<RelayMessage>>,
    ticks: Flow<Unit>,
    policy: MessagePolicy,
): Flow<List<RelayMessage>> = combine(
    storedMessages,
    ticks.onStart { emit(Unit) },
) { messages, _ ->
    selectRegionalMessages(messages, policy)
}

internal fun regionalMessageExpiryTicker(
    intervalMillis: Long = REGIONAL_MESSAGE_REEVALUATION_MS,
): Flow<Unit> = flow {
    require(intervalMillis > 0) { "intervalMillis must be positive" }
    while (true) {
        delay(intervalMillis)
        emit(Unit)
    }
}
