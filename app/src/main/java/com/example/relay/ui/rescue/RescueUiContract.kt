package com.example.relay.ui.rescue

import com.example.relay.rescue.CourierRescueItem
import com.example.relay.rescue.RescueRequestDraft
import com.example.relay.rescue.RescueSubmissionStatus
import com.example.relay.rescue.RescueUrgency

/** Screens available in the ordinary rescue flow.
 *
 * A courier never has to discover a shelter PC, select a network, submit data,
 * retry a transfer, or enrol a key.  Those actions are intentionally absent
 * from this contract so they cannot accidentally return through a UI change.
 */
enum class RescueScreen {
    HOME,
    REQUEST_FORM,
    BROADCASTING,
    COURIER_INVENTORY,
    SAFETY_PRIVACY,
}

enum class RescueLanguage { JAPANESE, ENGLISH }

internal fun RescueLanguage.text(japanese: String, english: String): String =
    if (this == RescueLanguage.JAPANESE) japanese else english

data class RescueBroadcastUiState(
    val isActive: Boolean = false,
    val nearbyDeviceCount: Int = 0,
    val transferCount: Int = 0,
    val statusMessage: String = "周囲のRelay端末を待っています",
)

data class OwnRescueRequestUiState(
    val requestId: String,
    val requestVersion: Int,
    val urgency: RescueUrgency,
    val personCount: Int,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val locationCapturedAtEpochMillis: Long,
    val createdAtEpochMillis: Long,
    val submissionStatus: RescueSubmissionStatus,
    val isCancelled: Boolean = false,
    /** Phase 5 consent state: true only after the sender explicitly opted in to location tracking. */
    val trackingEnabled: Boolean = false,
    /** Public terminal state only; rescue contents remain in the encrypted recovery payload. */
    val terminalStatus: String? = null,
)

/** Metadata-only, passive status shown to a courier. */
data class CourierAutomationUiState(
    val isEnabled: Boolean = false,
    val statusMessage: String = "受信した情報は、地域の救助拠点が見つかると中継します",
    val lastDeliveredAtEpochMillis: Long? = null,
)

data class RescueUiState(
    val screen: RescueScreen = RescueScreen.HOME,
    val draft: RescueRequestDraft? = null,
    val broadcast: RescueBroadcastUiState = RescueBroadcastUiState(),
    val courierItems: List<CourierRescueItem> = emptyList(),
    val courierAutomation: CourierAutomationUiState = CourierAutomationUiState(),
    val ownRequest: OwnRescueRequestUiState? = null,
    val isRequestSubmitting: Boolean = false,
    /** Prevents a corrupt encrypted recovery record from being mistaken for a fresh empty form. */
    val recoveryFailure: Boolean = false,
    val formMessage: String? = null,
    val language: RescueLanguage = RescueLanguage.JAPANESE,
)

/** Parent-owned actions. RescueFlow performs no networking or persistence. */
interface RescueCallbacks {
    fun onToggleLanguage()
    fun onNavigate(screen: RescueScreen)
    fun onDraftChange(draft: RescueRequestDraft)
    fun onSubmitRequest()
    fun onSendSos()
    fun onPrepareUpdate()
    fun onCancelRequest()
    fun onAcknowledgeTerminalResult()
    fun onRefreshStatus()
    fun onStopBroadcasting()
    /** Phase 5: record the sender's explicit opt-in/out for periodic location updates. */
    fun onSetLocationConsent(enabled: Boolean)
}
