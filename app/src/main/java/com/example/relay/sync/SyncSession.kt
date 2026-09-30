package com.example.relay.sync

import com.example.relay.domain.RelayRuntimeSettings
import kotlinx.coroutines.flow.Flow

interface SyncSession {
    val debugEvents: Flow<SyncDebugEvent>
    suspend fun start(settings: RelayRuntimeSettings): Boolean
    suspend fun stop()
}
