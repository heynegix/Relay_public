package com.example.relay.pcgateway

import com.example.relay.rescue.SignedShelterManifest
import com.example.relay.rescue.beaconFingerprintBytes
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Runtime posture is intentionally explicit.  Production is the default so an omitted
 * environment variable cannot silently turn a pilot Gateway into a LAN-wide anonymous service.
 */
enum class GatewayProfile {
    DEVELOPMENT,
    LAB,
    PRODUCTION;

    companion object {
        fun fromEnvironment(raw: String? = System.getenv("RELAY_PROFILE")): GatewayProfile = when (raw?.trim()?.lowercase()) {
            "development", "dev" -> DEVELOPMENT
            "lab" -> LAB
            null, "", "production", "prod" -> PRODUCTION
            else -> throw IllegalArgumentException("RELAY_PROFILE must be development, lab, or production")
        }
    }
}

/** How a non-loopback listener is isolated from untrusted networks. */
enum class GatewayLanMode {
    DISABLED,
    CLOSED_NETWORK,
    TLS_REVERSE_PROXY;

    companion object {
        fun fromEnvironment(raw: String? = System.getenv("RELAY_GATEWAY_LAN_MODE")): GatewayLanMode = when (raw?.trim()?.lowercase()) {
            null, "", "disabled" -> DISABLED
            "closed-network", "closed_network", "private-network", "private_network" -> CLOSED_NETWORK
            "tls-reverse-proxy", "tls_reverse_proxy", "tls" -> TLS_REVERSE_PROXY
            else -> throw IllegalArgumentException(
                "RELAY_GATEWAY_LAN_MODE must be disabled, closed-network, or tls-reverse-proxy",
            )
        }
    }
}

/** At-rest protection applied to the Gateway rescue private key file. */
enum class GatewayKeyProtection {
    /** Owner-only filesystem permission verification; deliberately not claimed as DPAPI/HSM/KMS. */
    FILE_PERMISSIONS,

    /** Windows DPAPI per-user encryption of the key file; refuses to run where DPAPI is absent. */
    DPAPI;

    companion object {
        fun fromEnvironment(raw: String? = System.getenv("RELAY_KEY_PROTECTION")): GatewayKeyProtection =
            when (raw?.trim()?.lowercase()) {
                null, "", "file-permissions", "file_permissions" -> FILE_PERMISSIONS
                "dpapi" -> DPAPI
                else -> throw IllegalArgumentException("RELAY_KEY_PROTECTION must be file-permissions or dpapi")
            }
    }
}

data class GatewayConfig(
    val profile: GatewayProfile = GatewayProfile.fromEnvironment(),
    val lanMode: GatewayLanMode = GatewayLanMode.fromEnvironment(),
    /**
     * Drill isolation. When true, every persistent-state default moves under a `training`
     * directory and non-training paths are rejected fail-closed, so a drill can never read or
     * write production rescue data, keys, accounts, or credentials.
     */
    val trainingMode: Boolean = environmentBoolean("RELAY_TRAINING_MODE", default = false),
    val version: String = System.getProperty("relay.version") ?: System.getenv("RELAY_VERSION") ?: "dev",
    val buildSha: String = System.getenv("GIT_COMMIT") ?: "unknown",
    /**
     * Every profile binds to loopback by default. A non-loopback value is an explicit operator
     * choice and the Windows local-pilot launcher requires -AllowLan before setting one.
     */
    val host: String = System.getenv("RELAY_GATEWAY_HOST")
        ?: if (profile == GatewayProfile.DEVELOPMENT) "127.0.0.1" else "127.0.0.1",
    val port: Int = (System.getenv("RELAY_GATEWAY_PORT") ?: "8080").toIntOrNull() ?: 8080,
    /** External endpoint advertised by a TLS reverse proxy, never a secret. */
    val publicScheme: String = (System.getenv("RELAY_GATEWAY_PUBLIC_SCHEME")
        ?: if (lanMode == GatewayLanMode.TLS_REVERSE_PROXY) "https" else "http").lowercase(),
    val publicPort: Int = (System.getenv("RELAY_GATEWAY_PUBLIC_PORT") ?: System.getenv("RELAY_GATEWAY_PORT") ?: "8080")
        .toIntOrNull() ?: 8080,
    // Packaged apps (Windows EXE / macOS app image) may start with a read-only CWD.
    // Keep the default database under the user's writable home profile.
    val dbPath: String = System.getenv("RELAY_GATEWAY_DB")
        ?: File(System.getProperty("user.home"), if (trainingMode) ".relay/training/relay-gateway.db" else ".relay/relay-gateway.db").path,
    val gatewayId: String = System.getenv("RELAY_GATEWAY_ID") ?: "pc-gateway-local",
    val shelterId: String = System.getenv("RELAY_SHELTER_ID") ?: gatewayId,
    val rescueKeyPath: String = System.getenv("RELAY_RESCUE_KEY_FILE")
        ?: File(System.getProperty("user.home"), if (trainingMode) ".relay/training/rescue-keys.json" else ".relay/rescue-keys.json").path,
    /** At-rest protection mode for [rescueKeyPath]; parsing is fail-closed on unknown values. */
    val keyProtection: GatewayKeyProtection = GatewayKeyProtection.fromEnvironment(),
    val regionalProfilePath: String = System.getenv("RELAY_REGIONAL_PROFILE")
        ?: File(System.getProperty("user.home"), if (trainingMode) ".relay/training/region/profile.json" else ".relay/region/profile.json").path,
    val regionalProfile: RegionalDeploymentProfile = RegionalDeploymentProfileLoader.load(Path.of(regionalProfilePath)),
    val offlineMapPath: String = System.getenv("RELAY_OFFLINE_MAP_DIR")
        ?: File(System.getProperty("user.home"), ".relay/maps/regional").path,
    val officialInfoCachePath: String = System.getenv("RELAY_OFFICIAL_INFO_CACHE")
        ?: File(
            System.getProperty("user.home"),
            if (trainingMode) ".relay/training/official/official-info.json" else ".relay/official/official-info.json",
        ).path,
    /**
     * Public regional root used to verify the shelter's signed BLE identity.
     *
     * Keeping this optional preserves maintenance-only Gateway operation, but rescue BLE ingress
     * stays disabled until both this file and [rescueSignedManifestPath] verify successfully.
     */
    val rescueRegionalRootBundlePath: String? = System.getenv("RELAY_RESCUE_REGIONAL_ROOT_BUNDLE_FILE")
        ?.trim()
        ?.takeIf { it.isNotEmpty() },
    /** Root-signed public identity provisioned for this specific shelter PC. */
    val rescueSignedManifestPath: String? = System.getenv("RELAY_RESCUE_SIGNED_MANIFEST_FILE")
        ?.trim()
        ?.takeIf { it.isNotEmpty() },
    val rescueRecipientKeyId: String? = System.getenv("RELAY_RESCUE_RECIPIENT_KEY_ID")?.trim()?.takeIf { it.isNotEmpty() },
    val rescueManifestFingerprint: String? = System.getenv("RELAY_RESCUE_MANIFEST_FINGERPRINT")?.trim()?.takeIf { it.isNotEmpty() },
    val rescueKeyExpiryWarningMillis: Long = (System.getenv("RELAY_RESCUE_KEY_EXPIRY_WARNING_DAYS") ?: "30")
        .toLongOrNull()?.coerceIn(1, 365)?.times(24L * 60 * 60 * 1_000) ?: 30L * 24 * 60 * 60 * 1_000,
    /**
     * Retention period for decrypted terminal rescue details. The 30-day default is a pilot
     * value pending privacy/legal owner approval. Parsing is strict fail-closed: a malformed or
     * out-of-range override stops startup instead of silently keeping personal data longer.
     */
    val rescueRetentionDays: Int = environmentStrictInt("RELAY_RESCUE_RETENTION_DAYS", default = 30),
    /** Background sweep cadence so retention holds even when no operator opens the console. */
    val retentionSweepIntervalMinutes: Int = environmentStrictInt("RELAY_RETENTION_SWEEP_INTERVAL_MINUTES", default = 60),
    /** Separate loopback-only listener used exclusively by the local Windows BLE sidecar. */
    val bleBridgeIngressHost: String = "127.0.0.1",
    val bleBridgeIngressPort: Int = (System.getenv("RELAY_BLE_BRIDGE_PORT") ?: "18081").toIntOrNull()
        ?.takeIf { it in 1..65_535 } ?: 18081,
    /** HMAC secret shared only with the locally installed BLE sidecar. */
    val bleBridgeSharedSecret: String = resolveBleBridgeSharedSecret(trainingMode),
    val maxBleBridgeRequestBytes: Int = (System.getenv("RELAY_BLE_BRIDGE_MAX_REQUEST_BYTES") ?: "49152").toIntOrNull()
        ?.takeIf { it in 1_024..131_072 } ?: 49_152,
    /**
     * Legacy shared management key. It exists solely to migrate development tooling and is
     * deliberately unavailable in lab/production profiles.
     */
    val legacyAdminKeyEnabled: Boolean = profile == GatewayProfile.DEVELOPMENT &&
        environmentBoolean("RELAY_GATEWAY_ENABLE_LEGACY_ADMIN_KEY", default = true),
    val adminKey: String? = if (legacyAdminKeyEnabled) resolveAdminKey(trainingMode) else null,
    /** First-admin bootstrap inputs. No default username or password is ever generated. */
    val bootstrapUsername: String? = System.getenv("RELAY_GATEWAY_BOOTSTRAP_USERNAME")?.trim()?.takeIf { it.isNotEmpty() },
    val bootstrapSecret: String? = System.getenv("RELAY_GATEWAY_BOOTSTRAP_SECRET")?.takeIf { it.isNotBlank() },
    val sessionTtlMillis: Long = (System.getenv("RELAY_GATEWAY_SESSION_TTL_MINUTES") ?: "480")
        .toLongOrNull()?.coerceIn(5, 24 * 60)?.times(60_000) ?: 8L * 60 * 60 * 1_000,
    /** Secure cookies are mandatory when a TLS reverse proxy fronts remote management. */
    val sessionCookieSecure: Boolean = environmentBoolean(
        "RELAY_GATEWAY_SESSION_COOKIE_SECURE",
        default = lanMode == GatewayLanMode.TLS_REVERSE_PROXY,
    ),
    /** Browser/admin access from a non-loopback peer requires an explicit topology acknowledgement. */
    val remoteManagementEnabled: Boolean = environmentBoolean("RELAY_GATEWAY_REMOTE_MANAGEMENT", default = false),
    /**
     * Docker Desktop rewrites a host-loopback browser request to its bridge address. This narrowly
     * restores the local console only for the development profile with LAN mode disabled; Compose
     * binds that mode to 127.0.0.1 so it cannot expose a remote operator console.
     */
    val dockerLocalOperatorEnabled: Boolean = environmentBoolean("RELAY_GATEWAY_DOCKER_LOCAL_OPERATOR", default = false),
    val maxPayloadBytes: Int = 64 * 1024,
    val maxMessagesPerRequest: Int = 128,
    val maxStoredMessages: Int = 50_000,
    /** Anonymous ingress is a development compatibility default only. */
    val anonymousIngressEnabled: Boolean = environmentBoolean(
        "RELAY_GATEWAY_ANONYMOUS_INGRESS",
        default = profile == GatewayProfile.DEVELOPMENT,
    ),
    /** PUERTA is intentionally unavailable in production even when its environment variable is set. */
    val localPilotIngressEnabled: Boolean = profile != GatewayProfile.PRODUCTION && environmentBoolean(
        "RELAY_LOCAL_PILOT_INGRESS",
        default = false,
    ),
    val maxAnonymousRequestBytes: Int = 256 * 1024,
    val maxAnonymousMessagesPerRequest: Int = 32,
    val maxAnonymousRequestsPerMinute: Int = 30,
    val maxAnonymousMessagesPerMinute: Int = 256,
    val maxAnonymousBytesPerMinute: Int = 1024 * 1024,
    /** LAN discovery is not a trust mechanism and is disabled outside explicit LAN operation. */
    val lanDiscoveryEnabled: Boolean = environmentBoolean(
        "RELAY_GATEWAY_LAN_DISCOVERY",
        default = profile == GatewayProfile.DEVELOPMENT,
    ),
    val lanDiscoveryPort: Int = (System.getenv("RELAY_GATEWAY_DISCOVERY_PORT") ?: "42888").toIntOrNull() ?: 42888,
    val lanDiscoveryIntervalMs: Long = 5_000,
    /** Broker URL for cloud relay. Empty/null = Broker pull disabled. */
    val brokerUrl: String? = System.getenv("RELAY_BROKER_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.also {
        require(isSafeHttpsEndpoint(it)) { "RELAY_BROKER_URL must be an HTTPS endpoint without embedded credentials" }
    },
    /** Per-Gateway, per-shelter Broker credential. It is never written to health/audit/log output. */
    val brokerCredential: String? = System.getenv("RELAY_BROKER_CREDENTIAL")?.trim()?.takeIf { it.isNotEmpty() },
    /** Deprecated shared Broker key; accepted only by development-profile compatibility wiring. */
    val brokerLegacyApiKey: String? = System.getenv("RELAY_BROKER_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("RELAY_BROKER_GATEWAY_API_KEY")?.trim()?.takeIf { it.isNotEmpty() },
    val brokerPollIntervalMs: Long = (System.getenv("RELAY_BROKER_POLL_INTERVAL_MS") ?: "10000")
        .toLongOrNull()?.takeIf { it in 1_000L..300_000L } ?: 10_000L,
) {
    val rescueRetentionMillis: Long = rescueRetentionDays * 24L * 60 * 60 * 1_000
    val retentionSweepIntervalMillis: Long = retentionSweepIntervalMinutes * 60_000L

    /** Non-sensitive diagnostics surfaced by health; never contains host paths, keys, or tokens. */
    val configurationWarnings: List<String> = buildList {
        if (regionalProfile.regionId == "global") add("regional_profile_not_configured")
        if (trainingMode) add("training_mode_active_production_data_isolated")
        if (profile == GatewayProfile.DEVELOPMENT) add("development_profile_compatibility_enabled")
        if (profile == GatewayProfile.LAB) add("lab_profile_not_for_production_operation")
        if (profile != GatewayProfile.DEVELOPMENT && legacyAdminKeyMaterialConfigured()) {
            // Deliberately report only that obsolete material exists, never its value or path.
            add("legacy_admin_key_material_ignored_remove_from_host")
        }
        if (lanMode == GatewayLanMode.CLOSED_NETWORK) add("closed_network_boundary_must_be_verified_by_operator")
        if (profile != GatewayProfile.DEVELOPMENT && lanDiscoveryEnabled && publicScheme != "https") {
            add("lan_discovery_http_not_usable_by_android_release_clients")
        }
        if (brokerUrl != null && brokerCredential == null && profile == GatewayProfile.DEVELOPMENT) {
            add("broker_credential_missing_development_only")
        }
    }

    /** Non-secret configuration snapshot allowed in an operator audit record. */
    val auditConfigurationTarget: String = listOf(
        "profile=${profile.name.lowercase()}",
        "region=${regionalProfile.regionId}",
        "training=$trainingMode",
        "lan=${lanMode.name.lowercase()}",
        "anonymous=$anonymousIngressEnabled",
        "discovery=$lanDiscoveryEnabled",
        "remoteManagement=$remoteManagementEnabled",
    ).joinToString(";")

    init {
        regionalProfile.validate()
        require(port in 1..65_535) { "RELAY_GATEWAY_PORT must be a valid TCP port" }
        require(publicPort in 1..65_535) { "RELAY_GATEWAY_PUBLIC_PORT must be a valid TCP port" }
        require(lanDiscoveryPort in 1..65_535) { "RELAY_GATEWAY_DISCOVERY_PORT must be a valid UDP port" }
        require(publicScheme in setOf("http", "https")) { "RELAY_GATEWAY_PUBLIC_SCHEME must be http or https" }
        require(!(lanMode == GatewayLanMode.TLS_REVERSE_PROXY && publicScheme != "https")) {
            "TLS reverse-proxy mode requires RELAY_GATEWAY_PUBLIC_SCHEME=https"
        }
        require(!(lanMode == GatewayLanMode.TLS_REVERSE_PROXY && !isLoopbackHost(host))) {
            "TLS reverse-proxy mode must bind the Gateway to loopback; expose only the proxy"
        }
        val lanListener = !isLoopbackHost(host)
        require(!(profile != GatewayProfile.DEVELOPMENT && lanListener && lanMode == GatewayLanMode.DISABLED)) {
            "non-loopback Gateway binding requires RELAY_GATEWAY_LAN_MODE=closed-network or tls-reverse-proxy"
        }
        require(!(profile != GatewayProfile.DEVELOPMENT && (anonymousIngressEnabled || lanDiscoveryEnabled) && lanMode == GatewayLanMode.DISABLED)) {
            "anonymous ingress or LAN discovery requires an explicit LAN topology outside development"
        }
        require(!(lanDiscoveryEnabled && !anonymousIngressEnabled)) {
            "LAN discovery advertises anonymous sync; enable anonymous ingress too or disable discovery"
        }
        require(!(remoteManagementEnabled && lanMode != GatewayLanMode.TLS_REVERSE_PROXY)) {
            "remote management requires RELAY_GATEWAY_LAN_MODE=tls-reverse-proxy"
        }
        require(!(remoteManagementEnabled && !sessionCookieSecure)) {
            "remote management requires RELAY_GATEWAY_SESSION_COOKIE_SECURE=true"
        }
        require(!(dockerLocalOperatorEnabled && (profile != GatewayProfile.DEVELOPMENT || lanMode != GatewayLanMode.DISABLED))) {
            "RELAY_GATEWAY_DOCKER_LOCAL_OPERATOR is development-only and requires LAN mode disabled"
        }
        require(!(profile != GatewayProfile.DEVELOPMENT && legacyAdminKeyEnabled)) {
            "X-Admin-Key compatibility is permitted only in the development profile"
        }
        require(!(profile == GatewayProfile.PRODUCTION && localPilotIngressEnabled)) {
            "RELAY_LOCAL_PILOT_INGRESS is never available in production"
        }
        require(!(profile != GatewayProfile.DEVELOPMENT && brokerLegacyApiKey != null)) {
            "RELAY_BROKER_API_KEY / RELAY_BROKER_GATEWAY_API_KEY are development-only legacy credentials; use RELAY_BROKER_CREDENTIAL"
        }
        require(!(profile == GatewayProfile.PRODUCTION && brokerUrl != null && brokerCredential == null)) {
            "production Broker usage requires RELAY_BROKER_CREDENTIAL"
        }
        require((bootstrapUsername == null) == (bootstrapSecret == null)) {
            "set both RELAY_GATEWAY_BOOTSTRAP_USERNAME and RELAY_GATEWAY_BOOTSTRAP_SECRET, or neither"
        }
        if (bootstrapSecret != null) require(bootstrapSecret.length >= 16) {
            "RELAY_GATEWAY_BOOTSTRAP_SECRET must contain at least 16 characters"
        }
        require(rescueRetentionDays in 1..365) {
            "RELAY_RESCUE_RETENTION_DAYS must be between 1 and 365 days; refusing to run with an unapproved retention period"
        }
        require(retentionSweepIntervalMinutes in 5..1_440) {
            "RELAY_RETENTION_SWEEP_INTERVAL_MINUTES must be between 5 and 1440"
        }
        if (trainingMode) {
            // Fail closed: a training Gateway must never open production state, even when the
            // operator overrides a path via environment variables.
            listOf(
                "RELAY_GATEWAY_DB" to dbPath,
                "RELAY_RESCUE_KEY_FILE" to rescueKeyPath,
                "RELAY_OFFICIAL_INFO_CACHE" to officialInfoCachePath,
                "RELAY_REGIONAL_PROFILE" to regionalProfilePath,
            ).forEach { (name, path) ->
                require(hasTrainingPathSegment(path)) {
                    "training mode requires $name to point inside a 'training' directory; refusing to reuse production data paths"
                }
            }
        }
    }

    /**
     * In TLS-reverse-proxy mode the backend peer is normally loopback even for a remote browser.
     * Therefore loopback alone is not treated as local operator access in that mode: the explicit
     * remote-management switch is the boundary. Use `disabled` LAN mode for a local-only console.
     */
    fun managementSourceAllowed(remoteHost: String?): Boolean = when (lanMode) {
        GatewayLanMode.TLS_REVERSE_PROXY -> remoteManagementEnabled
        else -> remoteManagementEnabled || dockerLocalOperatorEnabled || isLoopbackHost(remoteHost)
    }

    companion object {
        /** True when the path contains a directory segment named `training` (case-insensitive). */
        fun hasTrainingPathSegment(path: String): Boolean =
            path.split('/', '\\').any { it.equals("training", ignoreCase = true) }

        fun isLoopbackHost(value: String?): Boolean {
            val normalized = value?.trim()?.removePrefix("[")?.removeSuffix("]")?.lowercase() ?: return false
            if (normalized == "localhost" || normalized == "::1" || normalized == "0:0:0:0:0:0:0:1") return true
            if (normalized.startsWith("127.")) return true
            return runCatching { InetAddress.getByName(normalized).isLoopbackAddress }.getOrDefault(false)
        }

        fun isSafeHttpsEndpoint(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null &&
                (uri.port == -1 || uri.port in 1..65_535)
        }.getOrDefault(false)
    }
}

private fun environmentBoolean(name: String, default: Boolean): Boolean =
    System.getenv(name)?.trim()?.toBooleanStrictOrNull() ?: default

/** Strict integer parsing: a malformed value throws instead of silently using the default. */
private fun environmentStrictInt(name: String, default: Int): Int {
    val raw = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() } ?: return default
    return requireNotNull(raw.toIntOrNull()) { "$name must be an integer" }
}

private fun legacyAdminKeyMaterialConfigured(): Boolean =
    !System.getenv("RELAY_GATEWAY_ADMIN_KEY").isNullOrBlank() || defaultAdminKeyFile().isFile

/** Exact non-secret environment consumed by the Windows BLE bridge launcher. */
data class BleBridgeEnvironment(
    val signedManifestFingerprintBase64: String,
    val ingressPort: Int,
    val sharedSecretFile: String,
) {
    fun asEnvironmentValues(): Map<String, String> = linkedMapOf(
        "RELAY_BLE_SIGNED_MANIFEST_FINGERPRINT_BASE64" to signedManifestFingerprintBase64,
        "RELAY_BLE_BRIDGE_PORT" to ingressPort.toString(),
        "RELAY_BLE_BRIDGE_SECRET_FILE" to sharedSecretFile,
    )
}

/** Builds the bridge identity only from a verified signed manifest, never its legacy fingerprint. */
fun GatewayConfig.bleBridgeEnvironmentFor(manifest: SignedShelterManifest): BleBridgeEnvironment =
    BleBridgeEnvironment(
        signedManifestFingerprintBase64 = Base64.getEncoder().encodeToString(manifest.beaconFingerprintBytes()),
        ingressPort = bleBridgeIngressPort,
        sharedSecretFile = defaultBleBridgeSecretFile(trainingMode).absolutePath,
    )

fun defaultAdminKeyFile(trainingMode: Boolean = false): File = File(
    System.getenv("RELAY_GATEWAY_ADMIN_KEY_FILE")
        ?: File(System.getProperty("user.home"), if (trainingMode) ".relay/training/admin.key" else ".relay/admin.key").path,
)

/** Returns only a source descriptor; callers must never print the legacy key itself. */
fun resolveAdminKeySource(trainingMode: Boolean = false): String {
    val env = System.getenv("RELAY_GATEWAY_ADMIN_KEY")
    if (!env.isNullOrBlank()) return "env:RELAY_GATEWAY_ADMIN_KEY"
    val file = defaultAdminKeyFile(trainingMode)
    return if (file.isFile) "file:${file.absolutePath}" else "generated-file:${file.absolutePath}"
}

/** Development-only migration compatibility. Production never calls this function. */
fun resolveAdminKey(trainingMode: Boolean = false): String {
    val env = System.getenv("RELAY_GATEWAY_ADMIN_KEY")
    if (!env.isNullOrBlank()) return env.trim()
    val file = defaultAdminKeyFile(trainingMode)
    if (file.isFile) {
        val existing = file.readText(Charsets.UTF_8).trim()
        if (existing.isNotEmpty()) return existing
    }
    val generated = UUID.randomUUID().toString()
    file.parentFile?.mkdirs()
    file.writeText(generated, Charsets.UTF_8)
    return generated
}

fun defaultBleBridgeSecretFile(trainingMode: Boolean = false): File = File(
    System.getenv("RELAY_BLE_BRIDGE_SECRET_FILE")
        ?: File(System.getProperty("user.home"), if (trainingMode) ".relay/training/ble-bridge.key" else ".relay/ble-bridge.key").path,
)

/**
 * The BLE sidecar runs as the same interactive user and reads this secret locally.
 * It is never returned from an HTTP route or written to diagnostics.
 */
fun resolveBleBridgeSharedSecret(trainingMode: Boolean = false): String {
    val environment = System.getenv("RELAY_BLE_BRIDGE_SECRET")?.trim()
    if (!environment.isNullOrEmpty()) {
        require(environment.length >= 32) { "RELAY_BLE_BRIDGE_SECRET must be at least 32 characters" }
        return environment
    }
    val file = defaultBleBridgeSecretFile(trainingMode)
    if (file.isFile) {
        val existing = file.readText(Charsets.UTF_8).trim()
        require(existing.length >= 32) { "BLE bridge secret file is too short" }
        return existing
    }
    val generated = ByteArray(32).also(SecureRandom()::nextBytes)
    val value = Base64.getUrlEncoder().withoutPadding().encodeToString(generated)
    file.parentFile?.mkdirs()
    file.writeText(value, Charsets.UTF_8)
    return value
}
