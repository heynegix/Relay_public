package com.example.relay.background

/**
 * Pure, side-effect-free transitions for [BackgroundRelayState]. Every function returns a new state
 * and never touches Android, storage, or the network, so the whole policy is unit-testable.
 *
 * Design rules encoded here:
 *  - Opt-in is explicit; nothing arms itself.
 *  - An explicit user stop ([suspendByUser]) is sticky: only [USER_ACTION] or a fresh [optIn] may
 *    clear it, so receivers/workers cannot silently resurrect a stopped device.
 *  - ARMED never implies running Nearby; only [activate] moves to EMERGENCY_ACTIVE.
 */
object BackgroundRelayStateMachine {

    /** Explicit opt-in. Clears any prior user-stop and moves DISABLED/SUSPENDED to ARMED. */
    fun optIn(state: BackgroundRelayState): BackgroundRelayState = state.copy(
        optedIn = true,
        explicitlyStoppedByUser = false,
        mode = if (state.emergencyActive) BackgroundRelayMode.EMERGENCY_ACTIVE else BackgroundRelayMode.ARMED,
    )

    /** Explicit opt-out. Turns the feature fully off but is not a "user stop" against restarts. */
    fun optOut(state: BackgroundRelayState): BackgroundRelayState = state.copy(
        optedIn = false,
        emergencyActive = false,
        activationSource = null,
        degradeReason = DegradeReason.NONE,
        mode = BackgroundRelayMode.DISABLED,
    )

    /**
     * Attempts to move into disaster mode from [source]. A device the user explicitly stopped only
     * re-activates on a direct [ActivationSource.USER_ACTION]; every other route is ignored so a
     * boot/package/Bluetooth receiver cannot override the user's stop.
     */
    fun activate(state: BackgroundRelayState, source: ActivationSource): BackgroundRelayState {
        if (state.explicitlyStoppedByUser && source != ActivationSource.USER_ACTION) return state
        return state.copy(
            optedIn = true,
            explicitlyStoppedByUser = false,
            emergencyActive = true,
            activationSource = source,
            mode = BackgroundRelayMode.EMERGENCY_ACTIVE,
        )
    }

    /** A prerequisite failed while emergency was wanted. Stays "wanted" so recovery can resume it. */
    fun degrade(state: BackgroundRelayState, reason: DegradeReason): BackgroundRelayState {
        if (!state.emergencyActive) return state
        return state.copy(mode = BackgroundRelayMode.DEGRADED, degradeReason = reason)
    }

    /** A prerequisite was restored (e.g. Bluetooth re-enabled); resume emergency if still wanted. */
    fun recoverFromDegrade(state: BackgroundRelayState): BackgroundRelayState {
        if (!state.emergencyActive || state.mode != BackgroundRelayMode.DEGRADED) return state
        return state.copy(mode = BackgroundRelayMode.EMERGENCY_ACTIVE, degradeReason = DegradeReason.NONE)
    }

    /** Emergency work finished normally (no undelivered data). Falls back to ARMED or DISABLED. */
    fun deactivate(state: BackgroundRelayState): BackgroundRelayState = state.copy(
        emergencyActive = false,
        activationSource = null,
        degradeReason = DegradeReason.NONE,
        mode = if (state.optedIn) BackgroundRelayMode.ARMED else BackgroundRelayMode.DISABLED,
    )

    /**
     * The user pressed an in-app stop. Everything is turned off and the sticky stop flag is set so
     * receivers/workers do not immediately restart. The user may still opt in again later.
     */
    fun suspendByUser(state: BackgroundRelayState): BackgroundRelayState = state.copy(
        optedIn = false,
        emergencyActive = false,
        explicitlyStoppedByUser = true,
        activationSource = null,
        degradeReason = DegradeReason.NONE,
        mode = BackgroundRelayMode.SUSPENDED_BY_USER,
    )

    fun recordHealthyStart(state: BackgroundRelayState, nowEpochMillis: Long): BackgroundRelayState =
        state.copy(
            lastHealthyStartAtEpochMillis = nowEpochMillis,
            consecutiveStartFailures = 0,
            nextRetryAllowedAtEpochMillis = 0,
            lastError = null,
        )

    fun recordTransfer(state: BackgroundRelayState, nowEpochMillis: Long): BackgroundRelayState =
        state.copy(lastTransferAtEpochMillis = nowEpochMillis)

    /**
     * A start attempt failed. Records the reason and an exponentially backed-off next-retry time so
     * we never spin in an infinite restart loop (the user-facing high-importance notification is the
     * fallback, per Android 12+ background-FGS-start limits).
     */
    fun recordStartFailure(
        state: BackgroundRelayState,
        reason: String,
        nowEpochMillis: Long,
        baseBackoffMillis: Long = 30_000L,
        maxBackoffMillis: Long = 15 * 60_000L,
    ): BackgroundRelayState {
        val failures = state.consecutiveStartFailures + 1
        val backoff = (baseBackoffMillis shl (failures - 1).coerceIn(0, 20)).coerceAtMost(maxBackoffMillis)
        return state.copy(
            consecutiveStartFailures = failures,
            nextRetryAllowedAtEpochMillis = nowEpochMillis + backoff,
            lastError = reason.take(160),
        )
    }

    fun canRetryNow(state: BackgroundRelayState, nowEpochMillis: Long): Boolean =
        nowEpochMillis >= state.nextRetryAllowedAtEpochMillis
}

/** Decision returned by [restoreDecision]; separates "restore" from "why". */
data class RestoreDecision(
    val shouldRestore: Boolean,
    val source: ActivationSource,
    val reason: String,
)

/**
 * Decides whether a boot/package-replaced/OS-recreation event should restore emergency mode.
 *
 * Restoration is intentionally conservative: it happens ONLY when disaster mode was active or there
 * is undelivered rescue data, and NEVER when the user explicitly stopped. Plain ARMED does not
 * self-start Nearby after a reboot — it just keeps the launch routes available.
 */
fun restoreDecision(
    state: BackgroundRelayState,
    hasUndeliveredRescue: Boolean,
    source: ActivationSource,
): RestoreDecision {
    if (state.explicitlyStoppedByUser) {
        return RestoreDecision(false, source, "user_explicitly_stopped")
    }
    if (state.emergencyActive) {
        return RestoreDecision(true, source, "emergency_active_persisted")
    }
    if (hasUndeliveredRescue) {
        return RestoreDecision(true, source, "undelivered_rescue")
    }
    return RestoreDecision(false, source, if (state.optedIn) "armed_only_no_trigger" else "not_opted_in")
}
