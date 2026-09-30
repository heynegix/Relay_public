package com.example.relay.background

import com.example.relay.diagnostics.RelayDiagnosticStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android-facing coordinator over the pure [BackgroundRelayStateMachine] + [BackgroundRelayStateStore].
 * It owns the single source of truth for the feature's mode, exposes it as a [StateFlow] for the UI,
 * and records log-safe breadcrumbs (never payload/PII) so a phone log can explain the current state.
 *
 * It does NOT itself start Nearby or a foreground service — that stays with the services and the
 * [CommunicationLeaseManager]. This keeps ARMED genuinely idle: opting in only persists intent.
 */
class BackgroundRelayManager(
    private val stateStore: BackgroundRelayStateStore,
    private val diagnostics: RelayDiagnosticStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(stateStore.load())
    val state: StateFlow<BackgroundRelayState> = _state.asStateFlow()

    val current: BackgroundRelayState get() = stateStore.load()

    /** Explicit user opt-in to standby. Nothing arms itself without this. */
    fun optIn() {
        persist(BackgroundRelayStateMachine.optIn(stateStore.load()))
        diagnostics.record("background_relay_opt_in")
    }

    /** Explicit opt-out (feature off, but not a sticky "user stop" against later re-enabling). */
    fun optOut() {
        persist(BackgroundRelayStateMachine.optOut(stateStore.load()))
        diagnostics.record("background_relay_opt_out")
    }

    /**
     * The user pressed the in-app stop action. Sets the sticky explicit-stop flag so receivers and
     * workers must not immediately restart, and moves to SUSPENDED_BY_USER.
     */
    fun userStop() {
        persist(BackgroundRelayStateMachine.suspendByUser(stateStore.load()))
        diagnostics.record("background_relay_user_stop")
    }

    /** Whether [source] is allowed to activate given the sticky user-stop flag. */
    fun canActivate(source: ActivationSource): Boolean {
        val state = stateStore.load()
        return !(state.explicitlyStoppedByUser && source != ActivationSource.USER_ACTION)
    }

    /** Records the intent to run emergency mode from [source]. Ignored if the user stopped us. */
    fun markEmergencyRequested(source: ActivationSource, role: String?, operatingMode: String?) {
        val current = stateStore.load()
        if (!canActivate(source)) {
            diagnostics.record("background_relay_activate_ignored_${source.name}")
            return
        }
        val next = BackgroundRelayStateMachine.activate(current, source)
        persist(next.copy(deviceRole = role ?: next.deviceRole, operatingMode = operatingMode ?: next.operatingMode))
        diagnostics.record("background_relay_emergency_${source.name}")
    }

    /** The emergency runtime started cleanly. Clears failure back-off. */
    fun markHealthyStart() {
        persist(BackgroundRelayStateMachine.recordHealthyStart(stateStore.load(), clock()))
        diagnostics.record("background_relay_healthy_start")
    }

    /** A start attempt failed; records reason + backed-off next-retry time (no infinite loop). */
    fun markStartFailure(reason: String) {
        persist(BackgroundRelayStateMachine.recordStartFailure(stateStore.load(), reason, clock()))
        diagnostics.record("background_relay_start_failure")
    }

    fun markTransfer() {
        persist(BackgroundRelayStateMachine.recordTransfer(stateStore.load(), clock()))
    }

    fun markDegraded(reason: DegradeReason) {
        persist(BackgroundRelayStateMachine.degrade(stateStore.load(), reason))
        diagnostics.record("background_relay_degraded_${reason.name}")
    }

    fun recoverFromDegrade() {
        persist(BackgroundRelayStateMachine.recoverFromDegrade(stateStore.load()))
        diagnostics.record("background_relay_recovered")
    }

    /** Emergency finished normally; falls back to ARMED (if still opted in) or DISABLED. */
    fun markDeactivated() {
        persist(BackgroundRelayStateMachine.deactivate(stateStore.load()))
        diagnostics.record("background_relay_deactivated")
    }

    fun restoreDecision(hasUndeliveredRescue: Boolean, source: ActivationSource): RestoreDecision =
        restoreDecision(stateStore.load(), hasUndeliveredRescue, source)

    /**
     * Called once at process start with the newest process-exit record (may be null pre-API-30 or
     * on first launch). Records a diagnostic; the persisted explicit-stop flag — not the exit reason —
     * remains the authority on whether we treat ourselves as user-stopped.
     */
    fun onProcessStart(record: ProcessExitRecord?): ExitInterpretation? {
        val interpretation = ApplicationExitInterpreter.interpret(record) ?: return null
        persist(stateStore.load().copy(lastError = "exit_${interpretation.label}"))
        diagnostics.record("last_exit_${interpretation.label}")
        return interpretation
    }

    private fun persist(next: BackgroundRelayState) {
        stateStore.save(next)
        _state.value = next
    }
}
