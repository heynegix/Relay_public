package com.example.relay.protocol

import com.example.relay.domain.Clock

fun interface IncomingPayloadPolicy {
    fun allow(peerId: String, byteCount: Int): Boolean

    /** Clears session-scoped accounting when a transport connection is torn down. */
    fun onPeerDisconnected(peerId: String) = Unit
}

object AllowAllIncomingPayloads : IncomingPayloadPolicy {
    override fun allow(peerId: String, byteCount: Int): Boolean = true
}

/** In-memory MVP limiter. A durable or rotating-identity-aware implementation can replace it. */
class FixedWindowIncomingPayloadPolicy(
    private val clock: Clock,
    private val maxPacketsPerWindow: Int = 60,
    private val maxBytesPerWindow: Int = 512 * 1024,
    private val windowMillis: Long = 60_000,
) : IncomingPayloadPolicy {
    private data class Window(var startedAt: Long, var packets: Int, var bytes: Int)
    private val windows = mutableMapOf<String, Window>()

    override fun allow(peerId: String, byteCount: Int): Boolean = synchronized(windows) {
        if (byteCount < 0 || byteCount > maxBytesPerWindow) return@synchronized false
        val now = clock.nowMillis()
        val window = windows.getOrPut(peerId) { Window(now, 0, 0) }
        if (now < window.startedAt || now - window.startedAt >= windowMillis) {
            window.startedAt = now
            window.packets = 0
            window.bytes = 0
        }
        if (window.packets + 1 > maxPacketsPerWindow || window.bytes + byteCount > maxBytesPerWindow) {
            return@synchronized false
        }
        window.packets++
        window.bytes += byteCount
        true
    }

    override fun onPeerDisconnected(peerId: String) {
        synchronized(windows) { windows.remove(peerId) }
    }
}
