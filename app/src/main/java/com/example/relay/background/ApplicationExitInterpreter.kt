package com.example.relay.background

/**
 * A single historical process-death record, modelled independently of `android.app.ApplicationExitInfo`
 * so the interpretation policy is unit-testable without an emulator. [reason] mirrors the
 * `ApplicationExitInfo.REASON_*` integer constants.
 */
data class ProcessExitRecord(
    val reason: Int,
    val timestampMillis: Long,
    val description: String? = null,
)

/**
 * A stable, log-safe summary of why the process last died. It never contains the exit description
 * verbatim beyond a short label, and it deliberately does NOT decide "the user stopped us forever":
 * that decision belongs to the persisted [BackgroundRelayState.explicitlyStoppedByUser] flag.
 */
data class ExitInterpretation(
    val reasonCode: Int,
    val label: String,
    /** True only for reasons that a normal user directly triggered (Task Manager / force-stop). */
    val userInitiated: Boolean,
    /** True for crashes/ANR/native so restore logic can back off rather than crash-loop. */
    val abnormal: Boolean,
)

/**
 * Interprets the newest [ProcessExitRecord] and combines it with the persisted explicit-stop flag.
 *
 * Key rule from the spec: `REASON_USER_REQUESTED` alone must NOT be treated as a permanent stop.
 * Its meaning shifts across OS versions (app update, Recents swipe, Task Manager), so the persisted
 * [BackgroundRelayState.explicitlyStoppedByUser] flag — set only by an explicit in-app stop — is the
 * authoritative signal. The exit reason is used for diagnostics and crash-loop back-off only.
 */
object ApplicationExitInterpreter {
    // Mirrors android.app.ApplicationExitInfo constants (API 30+).
    const val REASON_UNKNOWN = 0
    const val REASON_EXIT_SELF = 1
    const val REASON_SIGNALED = 2
    const val REASON_LOW_MEMORY = 3
    const val REASON_CRASH = 4
    const val REASON_CRASH_NATIVE = 5
    const val REASON_ANR = 6
    const val REASON_INITIALIZATION_FAILURE = 7
    const val REASON_PERMISSION_CHANGE = 8
    const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
    const val REASON_USER_REQUESTED = 10
    const val REASON_USER_STOPPED = 11
    const val REASON_DEPENDENCY_DIED = 12
    const val REASON_OTHER = 13
    const val REASON_FREEZER = 14
    const val REASON_PACKAGE_STATE_CHANGE = 15
    const val REASON_PACKAGE_UPDATED = 16

    fun interpret(record: ProcessExitRecord?): ExitInterpretation? {
        if (record == null) return null
        return ExitInterpretation(
            reasonCode = record.reason,
            label = label(record.reason),
            userInitiated = record.reason == REASON_USER_REQUESTED || record.reason == REASON_USER_STOPPED,
            abnormal = record.reason in setOf(
                REASON_CRASH,
                REASON_CRASH_NATIVE,
                REASON_ANR,
                REASON_SIGNALED,
                REASON_INITIALIZATION_FAILURE,
                REASON_DEPENDENCY_DIED,
            ),
        )
    }

    /**
     * Whether the feature should treat itself as permanently stopped. The exit reason NEVER forces
     * this on its own; only the persisted explicit-stop flag does. This prevents an OS update or a
     * Recents swipe (which some OS versions report as USER_REQUESTED) from silently disarming a
     * device the user never chose to stop.
     */
    fun shouldTreatAsUserStop(explicitStopFlag: Boolean, interpretation: ExitInterpretation?): Boolean =
        explicitStopFlag

    private fun label(reason: Int): String = when (reason) {
        REASON_EXIT_SELF -> "exit_self"
        REASON_SIGNALED -> "signaled"
        REASON_LOW_MEMORY -> "low_memory"
        REASON_CRASH -> "crash"
        REASON_CRASH_NATIVE -> "crash_native"
        REASON_ANR -> "anr"
        REASON_INITIALIZATION_FAILURE -> "init_failure"
        REASON_PERMISSION_CHANGE -> "permission_change"
        REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
        REASON_USER_REQUESTED -> "user_requested"
        REASON_USER_STOPPED -> "user_stopped"
        REASON_DEPENDENCY_DIED -> "dependency_died"
        REASON_OTHER -> "other"
        REASON_FREEZER -> "freezer"
        REASON_PACKAGE_STATE_CHANGE -> "package_state_change"
        REASON_PACKAGE_UPDATED -> "package_updated"
        else -> "unknown"
    }
}
