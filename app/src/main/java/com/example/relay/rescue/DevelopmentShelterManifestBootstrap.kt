package com.example.relay.rescue

import com.example.relay.BuildConfig
import com.example.relay.gateway.GatewayDiscovery

/**
 * Explicitly development-only receiver enrollment for an unsigned local PC Gateway.
 *
 * A production/pilot build never reaches this code path: it is disabled unless the variant is a
 * debuggable HTTP-Gateway build. The normal enrollment flow still requires a separately verified
 * fingerprint, and release builds keep that fail-closed boundary.
 */
class DevelopmentShelterManifestBootstrap(
    private val discovery: GatewayDiscovery,
    private val keyStore: RescueShelterKeyStore,
    private val manifestClientFactory: (String) -> ShelterManifestClient = { scheme ->
        HttpShelterManifestClient(scheme)
    },
    private val enabled: Boolean = BuildConfig.DEBUG && BuildConfig.ALLOW_HTTP_GATEWAY,
) {
    /**
     * Discovers a development Gateway and pins the manifest it serves for this local install.
     * The sender validates the manifest shape and public-key identities before saving it. The
     * self-derived fingerprint is intentionally permitted only in the isolated development build.
     */
    suspend fun tryEnroll(): DevelopmentEnrollmentResult {
        if (!enabled) return DevelopmentEnrollmentResult.DISABLED
        if (keyStore.load() != null) return DevelopmentEnrollmentResult.ALREADY_ENROLLED
        val gateway = discovery.discover(timeoutMs = DISCOVERY_TIMEOUT_MILLIS)
            ?: return DevelopmentEnrollmentResult.GATEWAY_NOT_FOUND
        if (gateway.rescueIngressReady != true) return DevelopmentEnrollmentResult.GATEWAY_NOT_READY
        val scheme = gateway.scheme.trim().lowercase()
        if (scheme !in setOf("http", "https")) return DevelopmentEnrollmentResult.INVALID_GATEWAY
        val manifest = runCatching {
            manifestClientFactory(scheme).fetch(gateway.host, gateway.port)
        }.getOrNull() ?: return DevelopmentEnrollmentResult.MANIFEST_UNAVAILABLE
        val announcedShelterId = gateway.shelterId ?: gateway.gatewayId
        if (manifest.shelterId != announcedShelterId) return DevelopmentEnrollmentResult.SHELTER_MISMATCH
        return runCatching {
            // This is never used in release/pilot builds. The stored development marker makes
            // this self-pinned manifest unavailable if a production build later shares its data.
            keyStore.saveDevelopmentManifest(manifest)
            DevelopmentEnrollmentResult.ENROLLED
        }.getOrDefault(DevelopmentEnrollmentResult.INVALID_MANIFEST)
    }

    private companion object {
        const val DISCOVERY_TIMEOUT_MILLIS = 1_500
    }
}

enum class DevelopmentEnrollmentResult {
    DISABLED,
    ALREADY_ENROLLED,
    GATEWAY_NOT_FOUND,
    GATEWAY_NOT_READY,
    INVALID_GATEWAY,
    MANIFEST_UNAVAILABLE,
    SHELTER_MISMATCH,
    INVALID_MANIFEST,
    ENROLLED,
}
