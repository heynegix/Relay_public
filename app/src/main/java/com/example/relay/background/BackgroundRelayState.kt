package com.example.relay.background

/**
 * The persisted snapshot of the background relay feature. Every field here is durable so that a
 * process restart, an `ApplicationExitInfo` review, or a UI status screen can reconstruct exactly
 * why the device is (or is not) relaying.
 */
data class BackgroundRelayState(
    /** Explicit user opt-in to the standby feature. Defaults false: nothing is armed silently. */
    val optedIn: Boolean = false,
    val mode: BackgroundRelayMode = BackgroundRelayMode.DISABLED,
    /** Whether disaster mode is (or should be) running; drives reboot restoration. */
    val emergencyActive: Boolean = false,
    val activationSource: ActivationSource? = null,
    val deviceRole: String? = null,
    val operatingMode: String? = null,
    val lastHealthyStartAtEpochMillis: Long = 0,
    val lastTransferAtEpochMillis: Long = 0,
    val lastError: String? = null,
    /** Set only by an explicit in-app stop. It is the primary signal against silent restarts. */
    val explicitlyStoppedByUser: Boolean = false,
    val consecutiveStartFailures: Int = 0,
    val nextRetryAllowedAtEpochMillis: Long = 0,
    val degradeReason: DegradeReason = DegradeReason.NONE,
)
