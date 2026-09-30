package com.example.relay.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the lifecycle of bounded, periodic internet-priority sync attempts.
 *
 * The supplied scope remains owned by the caller. Only this scheduler's child
 * job is cancelled by [stop], so offline and nearby delivery can continue
 * independently.
 */
class InternetPrioritySyncScheduler(
    private val gateway: ServerSyncGateway,
    private val scope: CoroutineScope,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val onResult: (ServerSyncResult) -> Unit = {},
) {
    private val lock = Any()
    private var job: Job? = null
    private var stopping = false

    init {
        require(intervalMillis > 0) { "intervalMillis must be positive" }
        require(maxAttempts >= 0) { "maxAttempts must not be negative" }
    }

    /** True while this scheduler owns an active periodic job. */
    val isRunning: Boolean
        get() = synchronized(lock) { job?.isActive == true }

    /**
     * Starts one bounded loop. Returns false when a loop is already active.
     * A zero-attempt scheduler starts and completes without invoking the gateway.
     */
    fun start(): Boolean {
        synchronized(lock) {
            if (stopping || job?.isActive == true) return false

            job = scope.launch {
                repeat(maxAttempts) { attempt ->
                    if (!isActive) return@launch

                    val result = try {
                        gateway.sync()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        ServerSyncResult.Failed(failure.message ?: failure.javaClass.simpleName)
                    }
                    onResult(result)

                    if (attempt < maxAttempts - 1) {
                        delay(intervalMillis)
                    }
                }
            }.also { started ->
                started.invokeOnCompletion {
                    synchronized(lock) {
                        if (job === started) job = null
                    }
                }
            }
            return true
        }
    }

    /** Cancels the current loop; it does not cancel the caller's scope. */
    suspend fun stop() {
        val runningJob = synchronized(lock) {
            if (stopping) return
            stopping = true
            job
        }
        try {
            runningJob?.cancelAndJoin()
        } finally {
            synchronized(lock) {
                if (job === runningJob) job = null
                stopping = false
            }
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MILLIS = 15 * 60 * 1_000L
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}
