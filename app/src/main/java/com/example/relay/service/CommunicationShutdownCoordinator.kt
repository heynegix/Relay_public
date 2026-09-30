package com.example.relay.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the service shutdown sequence independently from the Android Service's
 * cancellable lifecycle scope. Cleanup remains idempotent while it is running,
 * and stopping Nearby is never skipped because the gateway stop failed.
 */
internal class CommunicationShutdownCoordinator(
    private val scope: CoroutineScope,
    private val stopGateway: suspend () -> Unit,
    private val stopCommunication: suspend () -> Unit,
    private val onFailure: (String) -> Unit = {},
) {
    private val lock = Any()
    private var activeJob: Job? = null

    fun requestStop(): Job = synchronized(lock) {
        activeJob ?: scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Service.onDestroy cancels its scope immediately after requesting
            // shutdown. NonCancellable lets both cleanup steps finish anyway.
            withContext(NonCancellable) {
                stopSafely("gateway", stopGateway)
                stopSafely("communication", stopCommunication)
            }
        }.also { activeJob = it }
    }

    private suspend fun stopSafely(component: String, stop: suspend () -> Unit) {
        try {
            stop()
        } catch (error: Exception) {
            // Exception messages can contain endpoint or credential details.
            // Report only a bounded exception type to the existing safe log/UI.
            runCatching {
                onFailure("$component stop failed: ${error.javaClass.simpleName}".take(180))
            }
        }
    }
}
