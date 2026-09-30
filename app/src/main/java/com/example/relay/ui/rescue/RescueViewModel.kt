package com.example.relay.ui.rescue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.relay.rescue.CourierRescuePresenter
import com.example.relay.rescue.RescueCondition
import com.example.relay.rescue.RescueEnvelopeRepository
import com.example.relay.rescue.RescueRequestAction
import com.example.relay.rescue.RescueRequestKey
import com.example.relay.rescue.RescueRequestDraft
import com.example.relay.rescue.RescueSubmissionStatus
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.session.ActiveRescueSessionCoordinator
import com.example.relay.rescue.session.RecoveredRescueSession
import com.example.relay.rescue.session.RescueSessionOperationResult
import com.example.relay.rescue.session.RescueSessionRestoreResult
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI adapter only. Sender state, request-version allocation, encryption, and persistence belong to
 * [ActiveRescueSessionCoordinator] so activity recreation and process death do not lose them.
 */
class RescueViewModel(
    private val coordinator: ActiveRescueSessionCoordinator,
    private val repository: RescueEnvelopeRepository,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val senderDeviceId: String = "request-${UUID.randomUUID()}",
) : ViewModel(), RescueCallbacks {
    private val presenter = CourierRescuePresenter(repository)
    private val _state = MutableStateFlow(RescueUiState(draft = newDraft()))
    val state: StateFlow<RescueUiState> = _state.asStateFlow()

    init {
        refreshCourierItems()
        // Nearby and broker delivery can add or update courier records while this screen remains
        // open. Subscribe to durable-store invalidations so the inventory reflects those changes
        // without requiring navigation or a manual refresh.
        viewModelScope.launch(Dispatchers.IO) {
            repository.changes.collect { refreshCourierItemsNow() }
        }
        viewModelScope.launch(Dispatchers.IO) { restoreSession() }
    }

    override fun onToggleLanguage() {
        _state.update { current ->
            current.copy(
                language = if (current.language == RescueLanguage.JAPANESE) {
                    RescueLanguage.ENGLISH
                } else {
                    RescueLanguage.JAPANESE
                },
                formMessage = null,
            )
        }
    }

    override fun onNavigate(screen: RescueScreen) {
        // A live sender session must be restored and updated through the coordinator; do not open
        // a competing new-request form. `onPrepareUpdate` enters this screen directly after it
        // has reloaded the encrypted recovery data.
        if (screen == RescueScreen.REQUEST_FORM && _state.value.ownRequest?.terminalStatus == null &&
            _state.value.ownRequest != null
        ) {
            setFormMessage(
                "既に保存されている救助依頼があります。状況の更新を選んでください。",
                "A saved rescue request is already active. Choose Update situation instead.",
            )
            return
        }
        if (screen == RescueScreen.COURIER_INVENTORY) refreshCourierItems()
        if (screen == RescueScreen.BROADCASTING) onRefreshStatus()
        _state.update { current -> current.copy(screen = screen, draft = current.draft ?: newDraft(), formMessage = null) }
    }

    override fun onDraftChange(draft: RescueRequestDraft) {
        _state.update { it.copy(draft = draft, formMessage = null) }
    }

    override fun onSubmitRequest() {
        val draft = (_state.value.draft ?: return).withInferredConditions()
        if (draft.personCount !in 1..1_000) {
            setFormMessage("人数を確認してください。", "Enter the number of people who need help.")
            return
        }
        if (draft.conditions.isEmpty()) {
            setFormMessage("現在の状況を1つ以上選んでください。", "Select at least one current condition.")
            return
        }
        val updateRequestId = _state.value.ownRequest
            ?.takeIf { active ->
                active.requestId == draft.requestId && active.terminalStatus == null && !active.isCancelled
            }
            ?.requestId
        submit(draft.withLegacyConditionFlags(), isSos = false, updateRequestId = updateRequestId)
    }

    override fun onSendSos() {
        val base = _state.value.draft ?: newDraft()
        submit(
            base.copy(
                urgency = RescueUrgency.IMMEDIATE,
                personCount = 0,
                conditions = setOf(RescueCondition.LIFE_THREATENING),
                seriouslyInjured = true,
                medicalSupportRequired = true,
                action = RescueRequestAction.ACTIVE,
            ),
            isSos = true,
        )
    }

    override fun onPrepareUpdate() {
        val requestId = _state.value.ownRequest?.requestId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = coordinator.prepareUpdate(requestId)) {
                is RescueSessionRestoreResult.Restored -> {
                    if (result.value.session.terminalStatus != null ||
                        result.value.recovery.draft.action != RescueRequestAction.ACTIVE
                    ) {
                        setFormMessage("この依頼は更新できません。", "This request can no longer be updated.")
                        return@launch
                    }
                    if (result.value.submissionStatus == RescueSubmissionStatus.PENDING_DESTINATION) {
                        setFormMessage(
                            "受信先の確認中は内容を変更できません。取り消す場合は端末内の保留だけを削除します。",
                            "Details cannot change while a trusted receiver is being resolved. Cancel removes only the local queue.",
                        )
                        return@launch
                    }
                    _state.update { current ->
                        current.copy(
                            screen = RescueScreen.REQUEST_FORM,
                            // This is UI input only.  The coordinator allocates the next version
                            // and timestamps after its durable compare-and-swap check.
                            draft = result.value.recovery.draft.copy(action = RescueRequestAction.ACTIVE),
                            formMessage = current.language.text(
                                "変更を確認してから更新を送ってください。",
                                "Review the changes, then send the update.",
                            ),
                        )
                    }
                }
                is RescueSessionRestoreResult.Corrupt -> showRecoveryFailure()
                RescueSessionRestoreResult.None -> setFormMessage("保存された依頼が見つかりません。", "No saved request was found.")
            }
        }
    }

    override fun onCancelRequest() {
        val requestId = _state.value.ownRequest?.requestId ?: return
        _state.update { it.copy(isRequestSubmitting = true, formMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            handleOperation(coordinator.cancel(requestId), isSos = false)
        }
    }

    override fun onAcknowledgeTerminalResult() {
        val requestId = _state.value.ownRequest
            ?.takeIf { it.terminalStatus != null }
            ?.requestId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (coordinator.acknowledgeTerminalResult(requestId)) {
                _state.update { current ->
                    current.copy(
                        screen = RescueScreen.HOME,
                        ownRequest = null,
                        broadcast = RescueBroadcastUiState(),
                        formMessage = current.language.text(
                            "結果を確認しました。端末に保存していた救助依頼の内容を削除しました。",
                            "Result acknowledged. The saved rescue-request details were removed from this device.",
                        ),
                    )
                }
            } else {
                onRefreshStatus()
            }
        }
    }

    override fun onRefreshStatus() {
        val requestId = _state.value.ownRequest?.requestId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            when (val restored = coordinator.restore(requestId)) {
                is RescueSessionRestoreResult.Restored -> showRecovered(restored.value)
                is RescueSessionRestoreResult.Corrupt -> showRecoveryFailure()
                RescueSessionRestoreResult.None -> Unit
            }
        }
    }

    override fun onStopBroadcasting() {
        _state.update { current -> current.copy(screen = RescueScreen.HOME) }
    }

    override fun onSetLocationConsent(enabled: Boolean) {
        val requestId = _state.value.ownRequest
            ?.takeIf { it.terminalStatus == null && !it.isCancelled }
            ?.requestId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = coordinator.setTrackingConsent(requestId, enabled)) {
                // Consent already matched; just refresh the displayed state.
                is RescueSessionOperationResult.TrackingConsentUnchanged -> showRecovered(result.value)
                is RescueSessionOperationResult.Stored -> {
                    showRecovered(result.value)
                    // Opting in immediately shares one consented fix; opting out never captures one.
                    if (enabled) coordinator.recordConsentedLocationUpdate(requestId).let { update ->
                        if (update is RescueSessionOperationResult.Stored) showRecovered(update.value)
                    }
                }
                else -> handleOperation(result, isSos = false)
            }
        }
    }

    private fun submit(
        source: RescueRequestDraft,
        isSos: Boolean,
        updateRequestId: String? = null,
    ) {
        if (_state.value.isRequestSubmitting) return
        _state.update { it.copy(isRequestSubmitting = true, formMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = updateRequestId?.let { coordinator.update(it, source) } ?: coordinator.create(source)
            handleOperation(result, isSos)
        }
    }

    private fun handleOperation(result: RescueSessionOperationResult, isSos: Boolean) {
        when (result) {
            is RescueSessionOperationResult.PendingDestination -> {
                _state.update { current ->
                    current.copy(
                        isRequestSubmitting = false,
                        screen = RescueScreen.BROADCASTING,
                        draft = newDraft(),
                        ownRequest = result.value.toOwnRequest(),
                        broadcast = RescueBroadcastUiState(
                            isActive = result.value.recovery.draft.action == RescueRequestAction.ACTIVE,
                            statusMessage = current.language.text(
                                if (isSos) {
                                    "SOSを端末に保存しました。Nearby通信を開始し、受信先を安全に確認中です。"
                                } else {
                                    "依頼を端末に保存しました。Nearby通信を開始し、受信先を安全に確認中です。"
                                },
                                if (isSos) {
                                    "SOS is saved on this device. Nearby relay has started while a trusted receiver is resolved."
                                } else {
                                    "Request is saved on this device. Nearby relay has started while a trusted receiver is resolved."
                                },
                            ),
                        ),
                        courierAutomation = CourierAutomationUiState(
                            isEnabled = true,
                            statusMessage = current.language.text(
                                "端末内で安全に保留し、Nearby通信と受信先確認を続けます。",
                                "The request stays safely queued on this device while Nearby and receiver resolution continue.",
                            ),
                        ),
                    )
                }
            }
            is RescueSessionOperationResult.Stored -> {
                _state.update { current ->
                    current.copy(
                        isRequestSubmitting = false,
                        screen = RescueScreen.BROADCASTING,
                        draft = newDraft(),
                        ownRequest = result.value.toOwnRequest(),
                        broadcast = RescueBroadcastUiState(
                            isActive = result.value.recovery.draft.action == RescueRequestAction.ACTIVE,
                            transferCount = result.record.state.submissionCount,
                            statusMessage = current.language.text(
                                if (result.value.recovery.draft.action == RescueRequestAction.CANCELLED) {
                                    "取消を端末に保存しました。周囲のRelay端末へ中継します。"
                                } else if (isSos) {
                                    "SOSを端末に保存しました。周囲のRelay端末へ中継します。"
                                } else {
                                    "依頼を端末に保存しました。周囲のRelay端末へ中継します。"
                                },
                                if (result.value.recovery.draft.action == RescueRequestAction.CANCELLED) {
                                    "Cancellation saved on this device and relaying through nearby Relay devices."
                                } else if (isSos) {
                                    "SOS saved on this device and relaying through nearby Relay devices."
                                } else {
                                    "Request saved on this device and relaying through nearby Relay devices."
                                },
                            ),
                        ),
                        courierAutomation = CourierAutomationUiState(
                            isEnabled = true,
                            statusMessage = current.language.text(
                                "配送と確認の受信を続けます。",
                                "Delivery and confirmation checks continue automatically.",
                            ),
                        ),
                    )
                }
                refreshCourierItemsNow()
            }
            is RescueSessionOperationResult.ActiveSessionExists -> {
                showRecovered(result.value)
                setFormMessage(
                    "保存されている救助依頼を表示しています。",
                    "Showing the saved rescue request instead.",
                )
            }
            RescueSessionOperationResult.ShelterUnavailable -> failSubmission(
                "救助の受信先情報を確認できません。接続を確認してください。",
                "Rescue receiver information is unavailable. Check the connection.",
            )
            RescueSessionOperationResult.LocationUnavailable -> failSubmission(
                "現在地を取得できません。位置情報を有効にして、空が見える場所で再試行してください。",
                "GPS location is unavailable. Turn on Location and try again with a clear view of the sky.",
            )
            RescueSessionOperationResult.Expired -> failSubmission(
                "この依頼の有効期限が切れています。",
                "This rescue request has expired.",
            )
            RescueSessionOperationResult.Terminal -> failSubmission(
                "この依頼はすでに終了しています。",
                "This rescue request is already closed.",
            )
            RescueSessionOperationResult.Corrupt -> showRecoveryFailure()
            RescueSessionOperationResult.CancelledAlready -> failSubmission(
                "取消の中継中です。避難所からの確認をお待ちください。",
                "Cancellation is being relayed. Wait for shelter confirmation.",
            )
            RescueSessionOperationResult.PendingDestinationDiscarded -> {
                _state.update { current ->
                    current.copy(
                        isRequestSubmitting = false,
                        screen = RescueScreen.HOME,
                        ownRequest = null,
                        broadcast = RescueBroadcastUiState(),
                        courierAutomation = current.courierAutomation.copy(
                            isEnabled = current.courierItems.isNotEmpty(),
                        ),
                        formMessage = current.language.text(
                            "未送信の救助依頼を端末内から取り消しました。避難所や中継端末には送信されていません。",
                            "The undelivered rescue request was removed from this device. It was not sent to a shelter or relay device.",
                        ),
                    )
                }
            }
            RescueSessionOperationResult.TrackingNotConsented -> failSubmission(
                "位置情報の共有に同意していません。まず位置情報の共有をオンにしてください。",
                "Location sharing is not enabled. Turn on location sharing first.",
            )
            is RescueSessionOperationResult.TrackingConsentUnchanged -> showRecovered(result.value)
            RescueSessionOperationResult.Conflict -> failSubmission(
                "依頼の状態が更新されました。もう一度お試しください。",
                "The request changed. Please try again.",
            )
            RescueSessionOperationResult.StorageFailure -> failSubmission(
                "端末に安全に保存できなかったため、送信を開始していません。",
                "The request was not safely saved on this device, so delivery did not start.",
            )
        }
    }

    private fun restoreSession() {
        when (val restored = coordinator.restoreLatest()) {
            is RescueSessionRestoreResult.Restored -> showRecovered(restored.value)
            is RescueSessionRestoreResult.Corrupt -> showRecoveryFailure()
            RescueSessionRestoreResult.None -> Unit
        }
    }

    private fun showRecovered(recovered: RecoveredRescueSession) {
        val durableRecord = repository.get(
            RescueRequestKey(recovered.session.requestId, recovered.session.latestVersion),
        )
        _state.update { current ->
            current.copy(
                screen = RescueScreen.BROADCASTING,
                isRequestSubmitting = false,
                recoveryFailure = false,
                ownRequest = recovered.toOwnRequest(),
                broadcast = current.broadcast.copy(
                    transferCount = durableRecord?.state?.submissionCount ?: 0,
                    isActive = recovered.recovery.draft.action == RescueRequestAction.ACTIVE &&
                        recovered.session.terminalStatus == null,
                    statusMessage = current.language.text(
                        if (recovered.submissionStatus == RescueSubmissionStatus.PENDING_DESTINATION) {
                            "保存されたSOSを端末内で保留しています。Nearby通信と受信先確認を続けます。"
                        } else {
                            "保存された救助依頼の状態を復元しました。"
                        },
                        if (recovered.submissionStatus == RescueSubmissionStatus.PENDING_DESTINATION) {
                            "The saved SOS is queued on this device while Nearby and receiver resolution continue."
                        } else {
                            "Restored the saved rescue request status."
                        },
                    ),
                ),
            )
        }
    }

    private fun showRecoveryFailure() {
        _state.update { current ->
            current.copy(
                screen = RescueScreen.BROADCASTING,
                isRequestSubmitting = false,
                ownRequest = null,
                recoveryFailure = true,
                formMessage = current.language.text(
                    "保存された救助依頼を安全に読み出せません。新しい依頼を自動作成していません。",
                    "The saved rescue request cannot be safely recovered. A new request was not created automatically.",
                ),
            )
        }
    }

    private fun failSubmission(japanese: String, english: String) {
        _state.update { it.copy(isRequestSubmitting = false, formMessage = it.language.text(japanese, english)) }
    }

    private fun setFormMessage(japanese: String, english: String) {
        _state.update { it.copy(formMessage = it.language.text(japanese, english)) }
    }

    private fun refreshCourierItems() {
        viewModelScope.launch(Dispatchers.IO) { refreshCourierItemsNow() }
    }

    private fun refreshCourierItemsNow() {
        val items = presenter.items()
        _state.update { current ->
            current.copy(
                courierItems = items,
                courierAutomation = current.courierAutomation.copy(
                    isEnabled = current.courierAutomation.isEnabled || items.isNotEmpty(),
                ),
            )
        }
    }

    private fun newDraft(): RescueRequestDraft {
        val now = nowEpochMillis()
        return RescueRequestDraft(
            requestId = UUID.randomUUID().toString(),
            senderDeviceId = senderDeviceId,
            destinationShelterId = "",
            createdAtEpochMillis = now,
            expiresAtEpochMillis = now + REQUEST_LIFETIME_MILLIS,
            urgency = RescueUrgency.URGENT,
        )
    }

    private fun RescueRequestDraft.withLegacyConditionFlags(): RescueRequestDraft = copy(
        urgency = if (RescueCondition.LIFE_THREATENING in conditions) RescueUrgency.IMMEDIATE else urgency,
        seriouslyInjured = seriouslyInjured || RescueCondition.LIFE_THREATENING in conditions,
        injured = injured || RescueCondition.INJURED_OR_UNWELL in conditions,
        mobilityImpaired = mobilityImpaired || RescueCondition.MOBILITY_IMPAIRED in conditions,
        medicalSupportRequired = medicalSupportRequired || RescueCondition.SUPPORT_NEEDED in conditions,
    )

    private fun RescueRequestDraft.withInferredConditions(): RescueRequestDraft {
        if (conditions.isNotEmpty()) return this
        val inferred = buildSet {
            if (seriouslyInjured) add(RescueCondition.LIFE_THREATENING)
            if (injured) add(RescueCondition.INJURED_OR_UNWELL)
            if (mobilityImpaired) add(RescueCondition.MOBILITY_IMPAIRED)
            if (medicalSupportRequired || supportNeeds.isNotEmpty()) add(RescueCondition.SUPPORT_NEEDED)
        }
        return copy(conditions = inferred)
    }

    private fun RecoveredRescueSession.toOwnRequest(): OwnRescueRequestUiState {
        val draft = recovery.draft
        val fix = draft.location
        val durableStatus = repository.all()
            .asSequence()
            .filter { it.envelope.requestId == draft.requestId }
            .maxByOrNull { it.envelope.requestVersion }
            ?.state
            ?.submissionStatus ?: submissionStatus
        return OwnRescueRequestUiState(
            requestId = draft.requestId,
            requestVersion = session.latestVersion,
            urgency = draft.urgency,
            personCount = draft.personCount,
            latitude = fix?.latitude ?: 0.0,
            longitude = fix?.longitude ?: 0.0,
            accuracyMeters = fix?.accuracyMeters,
            locationCapturedAtEpochMillis = fix?.capturedAtEpochMillis ?: draft.createdAtEpochMillis,
            createdAtEpochMillis = session.createdAtEpochMillis,
            submissionStatus = durableStatus,
            isCancelled = draft.action == RescueRequestAction.CANCELLED,
            trackingEnabled = session.trackingMode != com.example.relay.rescue.session.ActiveRescueSession.TRACKING_DISABLED,
            terminalStatus = session.terminalStatus,
        )
    }

    private companion object {
        const val REQUEST_LIFETIME_MILLIS = 3L * 24 * 60 * 60 * 1_000
    }
}
