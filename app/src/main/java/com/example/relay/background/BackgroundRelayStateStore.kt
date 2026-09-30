package com.example.relay.background

import android.content.Context

/**
 * Durable boundary for [BackgroundRelayState]. Split into a testable functional implementation and
 * a real SharedPreferences implementation.
 *
 * Compatibility: this uses its own preferences file and does NOT migrate or move the existing
 * encrypted DB, Keystore, or legacy preference files. The legacy `relay_communication_activation`
 * and `relay_rescue_automation` stores keep their current meaning; this store adds the explicit
 * standby opt-in and the richer status/diagnostic fields on top.
 */
interface BackgroundRelayStateStore {
    fun load(): BackgroundRelayState
    fun save(state: BackgroundRelayState)

    /** Read-modify-write helper for a single atomic transition. */
    fun update(transform: (BackgroundRelayState) -> BackgroundRelayState): BackgroundRelayState {
        val next = transform(load())
        save(next)
        return next
    }
}

/** In-memory store for unit tests. */
class InMemoryBackgroundRelayStateStore(
    initial: BackgroundRelayState = BackgroundRelayState(),
) : BackgroundRelayStateStore {
    private var current = initial
    override fun load(): BackgroundRelayState = current
    override fun save(state: BackgroundRelayState) {
        current = state
    }
}

class SharedPreferencesBackgroundRelayStateStore(context: Context) : BackgroundRelayStateStore {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): BackgroundRelayState = BackgroundRelayState(
        optedIn = preferences.getBoolean(KEY_OPTED_IN, false),
        mode = preferences.getString(KEY_MODE, null).toModeOrDefault(),
        emergencyActive = preferences.getBoolean(KEY_EMERGENCY_ACTIVE, false),
        activationSource = preferences.getString(KEY_ACTIVATION_SOURCE, null).toSourceOrNull(),
        deviceRole = preferences.getString(KEY_DEVICE_ROLE, null),
        operatingMode = preferences.getString(KEY_OPERATING_MODE, null),
        lastHealthyStartAtEpochMillis = preferences.getLong(KEY_LAST_HEALTHY_START, 0),
        lastTransferAtEpochMillis = preferences.getLong(KEY_LAST_TRANSFER, 0),
        lastError = preferences.getString(KEY_LAST_ERROR, null),
        explicitlyStoppedByUser = preferences.getBoolean(KEY_USER_STOPPED, false),
        consecutiveStartFailures = preferences.getInt(KEY_FAILURES, 0),
        nextRetryAllowedAtEpochMillis = preferences.getLong(KEY_NEXT_RETRY, 0),
        degradeReason = preferences.getString(KEY_DEGRADE_REASON, null).toDegradeOrDefault(),
    )

    override fun save(state: BackgroundRelayState) {
        preferences.edit()
            .putBoolean(KEY_OPTED_IN, state.optedIn)
            .putString(KEY_MODE, state.mode.name)
            .putBoolean(KEY_EMERGENCY_ACTIVE, state.emergencyActive)
            .putString(KEY_ACTIVATION_SOURCE, state.activationSource?.name)
            .putString(KEY_DEVICE_ROLE, state.deviceRole)
            .putString(KEY_OPERATING_MODE, state.operatingMode)
            .putLong(KEY_LAST_HEALTHY_START, state.lastHealthyStartAtEpochMillis)
            .putLong(KEY_LAST_TRANSFER, state.lastTransferAtEpochMillis)
            .putString(KEY_LAST_ERROR, state.lastError)
            .putBoolean(KEY_USER_STOPPED, state.explicitlyStoppedByUser)
            .putInt(KEY_FAILURES, state.consecutiveStartFailures)
            .putLong(KEY_NEXT_RETRY, state.nextRetryAllowedAtEpochMillis)
            .putString(KEY_DEGRADE_REASON, state.degradeReason.name)
            .apply()
    }

    private fun String?.toModeOrDefault(): BackgroundRelayMode =
        this?.let { runCatching { BackgroundRelayMode.valueOf(it) }.getOrNull() } ?: BackgroundRelayMode.DISABLED

    private fun String?.toSourceOrNull(): ActivationSource? =
        this?.let { runCatching { ActivationSource.valueOf(it) }.getOrNull() }

    private fun String?.toDegradeOrDefault(): DegradeReason =
        this?.let { runCatching { DegradeReason.valueOf(it) }.getOrNull() } ?: DegradeReason.NONE

    private companion object {
        const val PREFERENCES_NAME = "relay_background_relay_state"
        const val KEY_OPTED_IN = "opted_in"
        const val KEY_MODE = "mode"
        const val KEY_EMERGENCY_ACTIVE = "emergency_active"
        const val KEY_ACTIVATION_SOURCE = "activation_source"
        const val KEY_DEVICE_ROLE = "device_role"
        const val KEY_OPERATING_MODE = "operating_mode"
        const val KEY_LAST_HEALTHY_START = "last_healthy_start"
        const val KEY_LAST_TRANSFER = "last_transfer"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_USER_STOPPED = "user_stopped"
        const val KEY_FAILURES = "consecutive_failures"
        const val KEY_NEXT_RETRY = "next_retry"
        const val KEY_DEGRADE_REASON = "degrade_reason"
    }
}
