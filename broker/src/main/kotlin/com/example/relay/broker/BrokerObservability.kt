package com.example.relay.broker

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Categories of security-relevant rejections the Broker can surface to an operator.
 *
 * These are deliberately coarse *categories* only. A [BrokerObservability] sink is never handed a
 * capability token, gateway credential, device key, ciphertext, envelope, or any request payload —
 * recording an event must never be able to leak a secret, mirroring the "no secrets in logs" rule
 * the credential-issuance CLI already follows. Counting how often a boundary refused a request lets
 * an operator notice spoofing or abuse without weakening any fail-closed check.
 */
enum class BrokerSecurityEvent {
    /** A Gateway presented missing/invalid Bearer credentials. */
    AUTH_FAILED,

    /** A Gateway credential was valid but scoped to a different gateway/shelter than requested. */
    GATEWAY_SCOPE_MISMATCH,

    /** A caller exceeded a sliding-window rate limit on any endpoint. */
    RATE_LIMITED,

    /** A device registration proof did not verify against the presented public key. */
    INVALID_REGISTRATION_PROOF,

    /** A registration reused a device key id already bound to a different public key. */
    DEVICE_KEY_CONFLICT,

    /** An upload was signed by a key that is not registered for the device. */
    INVALID_SIGNATURE,

    /** An upload named a device key id that has never registered. */
    DEVICE_NOT_REGISTERED,

    /** Two distinct envelopes hashed to the same ciphertext digest. */
    CIPHERTEXT_COLLISION,
}

/**
 * Sink for [BrokerSecurityEvent]s. The default is a no-op so the server has zero observability side
 * effects unless an operator opts in; an operator can supply a sink that forwards to metrics or an
 * audit log. Implementations must be safe to call from many request coroutines concurrently.
 */
fun interface BrokerObservability {
    fun record(event: BrokerSecurityEvent)

    companion object {
        /** Discards every event. */
        val None: BrokerObservability = BrokerObservability {}
    }
}

/**
 * Thread-safe in-memory counters. Intended for tests and lightweight self-hosting where an operator
 * wants a live tally without wiring an external metrics system. Holds only per-category counts.
 */
class InMemoryBrokerObservability : BrokerObservability {
    private val counters = ConcurrentHashMap<BrokerSecurityEvent, AtomicLong>()

    override fun record(event: BrokerSecurityEvent) {
        counters.getOrPut(event) { AtomicLong(0) }.incrementAndGet()
    }

    /** Current count for a single category. */
    fun count(event: BrokerSecurityEvent): Long = counters[event]?.get() ?: 0L

    /** Immutable snapshot of every non-zero category. */
    fun snapshot(): Map<BrokerSecurityEvent, Long> =
        counters.entries.associate { it.key to it.value.get() }
}
