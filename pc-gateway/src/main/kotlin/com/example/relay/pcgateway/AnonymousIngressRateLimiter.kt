package com.example.relay.pcgateway

/** Process-local limiter for the unregistered LAN ingress. Restarting the Gateway resets its windows. */
class AnonymousIngressRateLimiter(
    private val maxRequests: Int,
    private val maxMessages: Int,
    private val maxBytes: Int,
    private val windowMs: Long = 60_000,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Window(var startedAt: Long, var requests: Int, var messages: Int, var bytes: Int)
    private val windows = mutableMapOf<String, Window>()

    fun allow(source: String, messageCount: Int, byteCount: Int): Boolean = synchronized(windows) {
        if (source.isBlank() || messageCount < 0 || byteCount < 0) return@synchronized false
        val now = nowMillis()
        val window = windows.getOrPut(source) { Window(now, 0, 0, 0) }
        if (now < window.startedAt || now - window.startedAt >= windowMs) {
            window.startedAt = now
            window.requests = 0
            window.messages = 0
            window.bytes = 0
        }
        if (window.requests + 1 > maxRequests || window.messages + messageCount > maxMessages || window.bytes + byteCount > maxBytes) {
            return@synchronized false
        }
        window.requests++
        window.messages += messageCount
        window.bytes += byteCount
        true
    }
}
