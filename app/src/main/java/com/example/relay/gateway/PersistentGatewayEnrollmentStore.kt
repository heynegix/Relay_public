package com.example.relay.gateway

import android.content.Context

/**
 * Durable backing for enrolled gateway identities.
 *
 * Enrollment is the out-of-band trust bootstrap that lets a discovered LAN beacon be upgraded from
 * anonymous/unverified to trusted (see [GatewayEnrollment]). The provisioned identities must survive
 * a process death so an operator does not have to re-scan a QR after every relaunch. Storage keeps
 * only the canonical self-validating [GatewayEnrollmentCodec] payloads, so a load is inherently
 * fail-closed: a tampered or truncated entry cannot decode into a partial token, it is dropped.
 */
interface EnrollmentPayloadStorage {
    fun read(): Set<String>
    fun write(payloads: Set<String>)
}

/** Outcome of trying to persist an enrolled identity. Never silently overwrites a conflict. */
sealed interface GatewayEnrollmentImport {
    /** A brand new gateway identity was stored. */
    data class Added(val token: GatewayEnrollmentToken) : GatewayEnrollmentImport

    /** The exact same identity was already enrolled; nothing changed. */
    data class AlreadyEnrolled(val token: GatewayEnrollmentToken) : GatewayEnrollmentImport

    /** An explicit rotation replaced a prior identity for the same gatewayId. */
    data class Rotated(
        val previous: GatewayEnrollmentToken,
        val token: GatewayEnrollmentToken,
    ) : GatewayEnrollmentImport

    /**
     * A different identity is already enrolled for this gatewayId and rotation was not requested.
     * The stored identity is left untouched so a spoofed QR cannot quietly displace a real gateway.
     */
    data class Conflict(
        val existing: GatewayEnrollmentToken,
        val incoming: GatewayEnrollmentToken,
    ) : GatewayEnrollmentImport

    /** The payload failed the fail-closed decode; nothing was stored. */
    data class Rejected(val reason: GatewayEnrollmentRejection) : GatewayEnrollmentImport
}

/**
 * Owns the single in-memory [GatewayEnrollmentStore] the app wires into [GatewaySyncEngine] and
 * mirrors every mutation to durable [storage]. The Android build backs this with SharedPreferences;
 * tests supply an in-memory [EnrollmentPayloadStorage] so the persistence and conflict policy can be
 * verified without a device.
 *
 * [onDiagnostic] receives short, non-secret event codes only (gatewayId + reason). Never pass token
 * fields, fingerprints, hosts, or key material: the diagnostic trail is not a place for those.
 */
class PersistentGatewayEnrollmentStore(
    private val storage: EnrollmentPayloadStorage,
    private val onDiagnostic: (String) -> Unit = {},
) {
    private val lock = Any()
    private val store = GatewayEnrollmentStore()

    init {
        var dropped = 0
        storage.read().forEach { payload ->
            when (val result = GatewayEnrollmentCodec.decodeQrPayload(payload)) {
                is GatewayEnrollmentResult.Enrolled -> store.enroll(result.token)
                is GatewayEnrollmentResult.Rejected -> {
                    dropped++
                    onDiagnostic("gw_enroll_dropped:${result.reason}")
                }
            }
        }
        // Quarantine corrupted entries by re-persisting only the canonical, decodable set.
        if (dropped > 0) persistLocked()
    }

    /** The live trust catalog to wire into [GatewaySyncEngine]. */
    fun enrollmentStore(): GatewayEnrollmentStore = store

    fun enrolledTokens(): List<GatewayEnrollmentToken> = synchronized(lock) { store.enrolledTokens() }

    /**
     * Persists a verified [token]. A different identity already enrolled for the same gatewayId is a
     * [GatewayEnrollmentImport.Conflict] unless [allowRotation] is set: rotation is always explicit so
     * a spoofed re-enrollment cannot silently replace a trusted gateway.
     */
    fun enroll(token: GatewayEnrollmentToken, allowRotation: Boolean = false): GatewayEnrollmentImport =
        synchronized(lock) {
            val existing = store.enrolledTokens().firstOrNull { it.gatewayId == token.gatewayId }
            when {
                existing == null -> {
                    store.enroll(token)
                    persistLocked()
                    onDiagnostic("gw_enroll_added:${token.gatewayId}")
                    GatewayEnrollmentImport.Added(token)
                }
                existing == token -> GatewayEnrollmentImport.AlreadyEnrolled(token)
                !allowRotation -> GatewayEnrollmentImport.Conflict(existing, token)
                else -> {
                    store.enroll(token)
                    persistLocked()
                    onDiagnostic("gw_enroll_rotated:${token.gatewayId}")
                    GatewayEnrollmentImport.Rotated(existing, token)
                }
            }
        }

    /**
     * Safe import flow for a scanned/typed payload: decode fail-closed, then apply the same conflict
     * policy as [enroll]. This is the headless entry point a QR scanner or a paste box calls; the
     * caller inspects the result to confirm a rotation or surface a rejection.
     */
    fun importPayload(payload: String, allowRotation: Boolean = false): GatewayEnrollmentImport =
        when (val decoded = GatewayEnrollmentCodec.decodeQrPayload(payload)) {
            is GatewayEnrollmentResult.Rejected -> {
                onDiagnostic("gw_enroll_rejected:${decoded.reason}")
                GatewayEnrollmentImport.Rejected(decoded.reason)
            }
            is GatewayEnrollmentResult.Enrolled -> enroll(decoded.token, allowRotation)
        }

    /** Removes a gateway identity. Returns true when an identity was actually enrolled. */
    fun forget(gatewayId: String): Boolean = synchronized(lock) {
        val present = store.enrolledTokens().any { it.gatewayId == gatewayId }
        if (present) {
            store.forget(gatewayId)
            persistLocked()
            onDiagnostic("gw_enroll_forgot:$gatewayId")
        }
        present
    }

    private fun persistLocked() {
        val payloads = store.enrolledTokens()
            .map { GatewayEnrollmentCodec.encodeQrPayload(it) }
            .toSet()
        storage.write(payloads)
    }
}

/** SharedPreferences-backed [EnrollmentPayloadStorage]. Stores only canonical payload strings. */
class SharedPreferencesEnrollmentPayloadStorage(context: Context) : EnrollmentPayloadStorage {
    private val preferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun read(): Set<String> =
        preferences.getStringSet(PAYLOADS, emptySet()).orEmpty().toSet()

    override fun write(payloads: Set<String>) {
        preferences.edit().putStringSet(PAYLOADS, payloads).apply()
    }

    private companion object {
        const val PREFERENCES = "relay_gateway_enrollments"
        const val PAYLOADS = "payloads"
    }
}
