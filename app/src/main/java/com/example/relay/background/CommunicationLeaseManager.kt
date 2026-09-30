package com.example.relay.background

import com.example.relay.domain.RelayRuntimeSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Outcome of an acquire/release so callers can log without guessing the manager's internal state. */
enum class LeaseResult {
    /** This acquire actually started the shared runtime (first owner). */
    STARTED,

    /** The runtime was already running for another owner; this owner joined the existing session. */
    ALREADY_ACTIVE,

    /** The underlying start callback reported failure; no lease is held by this owner. */
    START_FAILED,

    /** This release actually stopped the shared runtime (last owner). */
    STOPPED,

    /** This owner released but other owners still need communication; runtime kept running. */
    STILL_ACTIVE,

    /** The owner did not hold a lease; the release was a safe no-op. */
    NOT_HELD,
}

/**
 * Reference-counted ownership over the single shared communication runtime (Nearby transport +
 * Gateway sync). This is the "owner/lease" model chosen over spawning more foreground services:
 * both [com.example.relay.service.RelayCommunicationService] (USER_COMMUNICATION) and
 * [com.example.relay.service.RescueDeliveryService] (RESCUE_DELIVERY / EMERGENCY_MODE) acquire a
 * lease instead of each starting Nearby.
 *
 * Guarantees:
 *  - Exactly one runtime start regardless of how many owners acquire → single Nearby transport,
 *    single Advertising, single Discovery, single Gateway sync.
 *  - Duplicate acquires by the same owner are idempotent.
 *  - Release is safe to call multiple times.
 *  - One owner releasing never stops communication another owner still needs.
 *
 * The [start] callback's [RelayRuntimeSettings] from the FIRST acquire define the shared session;
 * later owners join it. Callers that need a different mode must fully release first.
 */
class CommunicationLeaseManager(
    private val start: suspend (RelayRuntimeSettings) -> Boolean,
    private val stop: suspend () -> Unit,
) {
    private val mutex = Mutex()
    private val owners = LinkedHashSet<CommunicationOwner>()

    suspend fun activeOwners(): Set<CommunicationOwner> = mutex.withLock { owners.toSet() }

    suspend fun isActive(): Boolean = mutex.withLock { owners.isNotEmpty() }

    suspend fun acquire(owner: CommunicationOwner, settings: RelayRuntimeSettings): LeaseResult =
        mutex.withLock {
            val alreadyRunning = owners.isNotEmpty()
            if (alreadyRunning) {
                owners.add(owner)
                return@withLock LeaseResult.ALREADY_ACTIVE
            }
            val started = start(settings)
            if (!started) {
                return@withLock LeaseResult.START_FAILED
            }
            owners.add(owner)
            LeaseResult.STARTED
        }

    suspend fun release(owner: CommunicationOwner): LeaseResult = mutex.withLock {
        if (!owners.remove(owner)) return@withLock LeaseResult.NOT_HELD
        if (owners.isNotEmpty()) return@withLock LeaseResult.STILL_ACTIVE
        stop()
        LeaseResult.STOPPED
    }

    /** Hard stop for an explicit user stop: drops every owner and stops the runtime once. */
    suspend fun releaseAll(): LeaseResult = mutex.withLock {
        if (owners.isEmpty()) return@withLock LeaseResult.NOT_HELD
        owners.clear()
        stop()
        LeaseResult.STOPPED
    }
}
