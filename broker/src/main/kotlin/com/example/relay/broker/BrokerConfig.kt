package com.example.relay.broker

import java.net.InetAddress

/** Broker profile controls whether legacy shared Gateway secrets can ever be accepted. */
enum class BrokerProfile {
    DEVELOPMENT,
    LAB,
    PRODUCTION;

    companion object {
        fun fromEnvironment(
            raw: String? = System.getenv("RELAY_BROKER_PROFILE") ?: System.getenv("RELAY_PROFILE"),
        ): BrokerProfile = when (raw?.trim()?.lowercase()) {
            "development", "dev" -> DEVELOPMENT
            "lab" -> LAB
            null, "", "production", "prod" -> PRODUCTION
            else -> throw IllegalArgumentException("RELAY_BROKER_PROFILE must be development, lab, or production")
        }
    }
}

/**
 * Broker itself remains plain HTTP only on its private listener.  A production deployment must
 * place a TLS reverse proxy in front of it; Android and Gateway clients enforce HTTPS URLs.
 */
data class BrokerConfig(
    val profile: BrokerProfile = BrokerProfile.fromEnvironment(),
    /**
     * Drill isolation. Honors the Broker-specific switch first so a shared host env cannot
     * silently flip a production Broker; when enabled, the store default moves under
     * `data/training` and non-training database paths are rejected fail-closed.
     */
    val trainingMode: Boolean = (System.getenv("RELAY_BROKER_TRAINING_MODE") ?: System.getenv("RELAY_TRAINING_MODE"))
        ?.trim()?.toBooleanStrictOrNull() ?: false,
    val host: String = System.getenv("RELAY_BROKER_HOST") ?: if (profile == BrokerProfile.DEVELOPMENT) "0.0.0.0" else "127.0.0.1",
    val port: Int = System.getenv("RELAY_BROKER_PORT")?.toIntOrNull()?.takeIf { it in 1..65_535 } ?: 8443,
    val dbPath: String = System.getenv("RELAY_BROKER_DB_PATH")
        ?: if (trainingMode) "./data/training/broker.db" else "./data/broker.db",
    /** Deprecated. A shared key is only accepted by a deliberate development profile. */
    val legacyGatewayApiKey: String? = System.getenv("RELAY_BROKER_GATEWAY_API_KEY")?.trim()?.takeIf { it.isNotEmpty() },
) {
    init {
        require(profile == BrokerProfile.DEVELOPMENT || isLoopbackHost(host)) {
            "production/lab Broker must bind loopback; expose it only through an externally operated TLS reverse proxy"
        }
        require(!(profile != BrokerProfile.DEVELOPMENT && legacyGatewayApiKey != null)) {
            "RELAY_BROKER_GATEWAY_API_KEY is development-only; issue scoped Gateway credentials instead"
        }
        if (trainingMode) {
            // Fail closed: a training Broker must never open the production store, even when the
            // operator overrides the path via RELAY_BROKER_DB_PATH.
            require(hasTrainingPathSegment(dbPath)) {
                "training mode requires RELAY_BROKER_DB_PATH to point inside a 'training' directory; " +
                    "refusing to reuse the production database"
            }
        }
    }

    companion object {
        /** True when the path contains a directory segment named `training` (case-insensitive). */
        fun hasTrainingPathSegment(path: String): Boolean =
            path.split('/', '\\').any { it.equals("training", ignoreCase = true) }
    }

    private fun isLoopbackHost(value: String): Boolean {
        val normalized = value.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (normalized == "localhost" || normalized == "::1" || normalized == "0:0:0:0:0:0:0:1" || normalized.startsWith("127.")) return true
        return runCatching { InetAddress.getByName(normalized).isLoopbackAddress }.getOrDefault(false)
    }
}
