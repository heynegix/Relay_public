package com.example.relay.cloud

/**
 * Optional path used when wider internet is available.
 * Disaster primary path remains local Nearby + PC Gateway LAN.
 */
fun interface ServerSyncGateway {
    suspend fun sync(): ServerSyncResult
}

sealed interface ServerSyncResult {
    /** Internet not available — SCF only. */
    data object OfflineSkipped : ServerSyncResult

    /** At least attempted online merge. */
    data class Synced(val inserted: Int, val skipped: Int) : ServerSyncResult

    data class Failed(val reason: String) : ServerSyncResult

    /** @deprecated Prefer [OfflineSkipped] / explicit gateway; kept for binary compatibility in tests. */
    @Deprecated("Use OfflineSkipped or InternetPrioritySync")
    data object NotImplemented : ServerSyncResult
}

/**
 * Legacy no-op. Prefer [InternetPrioritySync] wired in production.
 */
object NoOpServerSyncGateway : ServerSyncGateway {
    override suspend fun sync(): ServerSyncResult = ServerSyncResult.OfflineSkipped
}
