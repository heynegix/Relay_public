package com.example.relay.background

/**
 * Lifecycle of the opt-in background relay feature.
 *
 * This is deliberately NOT a "keep the process alive forever" model. `ARMED` only means a durable
 * opt-in and the OS launch routes (boot/package-replace/Bluetooth/user/rescue) are wired; it does
 * not run Nearby and cannot by itself detect a disaster. Only [EMERGENCY_ACTIVE] runs the
 * connectedDevice foreground service and continuous Nearby advertising/discovery/transfer.
 *
 * Android, OEM battery managers, and the user can still stop the service; nothing here claims to
 * survive a force-stop or a Task Manager kill.
 */
enum class BackgroundRelayMode {
    /** Feature not opted into. No standby, no auto-start routes are honoured. */
    DISABLED,

    /** Opted in and standing by. Persisted config + OS launch routes only; Nearby is NOT running. */
    ARMED,

    /** Disaster communication is running under a connectedDevice foreground service. */
    EMERGENCY_ACTIVE,

    /** Emergency was requested but a prerequisite (Bluetooth/permission/notification) is degraded. */
    DEGRADED,

    /** The user explicitly stopped everything; receivers/workers must not silently restart it. */
    SUSPENDED_BY_USER,
}

/** What caused an activation attempt. Persisted so the UI can explain why the service is running. */
enum class ActivationSource {
    USER_ACTION,
    RESCUE_CREATED,
    RESCUE_RECEIVED,
    BOOT_RESTORE,
    PACKAGE_REPLACED,
    BLUETOOTH_RESTORED,
    DEBUG_SIMULATION,
}

/**
 * Why the running emergency mode is degraded. [NONE] means healthy. [DEGRADED_NOTIFICATION_VISIBILITY]
 * is deliberately distinct from a hard permission failure: a foreground service can still run without
 * POST_NOTIFICATIONS, the user just cannot see it, so this is a warning rather than a stop condition.
 */
enum class DegradeReason {
    NONE,
    BLUETOOTH_DISABLED,
    MISSING_BLUETOOTH_PERMISSION,
    MISSING_NEARBY_PERMISSION,
    PLAY_SERVICES_UNAVAILABLE,
    DEGRADED_NOTIFICATION_VISIBILITY,
    OS_BACKGROUND_RESTRICTED,
}

/**
 * A logical claimant on the single shared communication runtime. Multiple owners can need
 * communication at once; the runtime must start once and stop only when the last owner releases.
 */
enum class CommunicationOwner {
    USER_COMMUNICATION,
    RESCUE_DELIVERY,
    EMERGENCY_MODE,
}
