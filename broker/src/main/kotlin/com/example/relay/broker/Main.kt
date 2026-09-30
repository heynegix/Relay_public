package com.example.relay.broker

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Purge interval: check for expired envelopes every 10 minutes. */
private const val PURGE_INTERVAL_MS = 10L * 60 * 1000

/**
 * Relay Broker entry point.
 *
 * TLS is deliberately not faked here. Production binds to loopback by default and requires an
 * externally operated TLS reverse proxy. Android and PC Gateway clients use HTTPS URLs only.
 *
 * Local credential operations:
 *   issue-gateway-credential --gateway-id ID --shelter-id ID --expires-at EPOCH_MILLIS
 *   revoke-gateway-credential --credential-id ID
 *
 * Issuance prints the raw credential exactly once. It is not written to the SQLite database,
 * application logs, health output, or a backup helper.
 */
fun main(args: Array<String>) {
    val config = BrokerConfig()
    if (args.isNotEmpty()) {
        runCredentialCommand(config, args)
        return
    }

    val store = BrokerStore(config.dbPath)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Periodic purge of expired envelopes
    scope.launch {
        while (isActive) {
            delay(PURGE_INTERVAL_MS)
            val purged = store.purgeExpired(System.currentTimeMillis())
            if (purged > 0) println("Broker purged $purged expired envelope(s)")
        }
    }

    println("Relay Broker starting on ${config.host}:${config.port} (${config.profile.name.lowercase()} profile)")
    println("Gateway authentication: scoped per-Gateway/per-shelter credentials required")
    if (config.profile == BrokerProfile.DEVELOPMENT && config.legacyGatewayApiKey != null) {
        println("WARNING: legacy shared Gateway key compatibility is enabled for development only")
    }

    try {
        embeddedServer(Netty, host = config.host, port = config.port) {
            brokerModule(store, config)
        }.start(wait = true)
    } finally {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        store.close()
    }
}

private fun runCredentialCommand(config: BrokerConfig, args: Array<String>) {
    val command = args.first()
    val options = parseOptions(args.drop(1))
    BrokerStore(config.dbPath).use { store ->
        when (command) {
            "issue-gateway-credential" -> {
                val gatewayId = options.require("--gateway-id")
                val shelterId = options.require("--shelter-id")
                val expiresAt = options.require("--expires-at").toLongOrNull()
                    ?: error("--expires-at must be epoch milliseconds")
                val issued = store.issueGatewayCredential(gatewayId, shelterId, expiresAt)
                // This is intentionally the only raw-secret output path.
                println("credential_id=${issued.credentialId}")
                println("gateway_id=${issued.gatewayId}")
                println("shelter_id=${issued.shelterId}")
                println("expires_at=${issued.expiresAtEpochMillis}")
                println("RELAY_BROKER_CREDENTIAL=${issued.token}")
            }
            "revoke-gateway-credential" -> {
                val credentialId = options.require("--credential-id")
                val revoked = store.revokeGatewayCredential(credentialId)
                println(if (revoked) "credential_revoked=$credentialId" else "credential_not_found_or_already_revoked=$credentialId")
            }
            else -> error("unknown command: $command")
        }
    }
}

private fun parseOptions(values: List<String>): Map<String, String> {
    require(values.size % 2 == 0) { "options must be --name value pairs" }
    return values.chunked(2).associate { (name, value) ->
        require(name.startsWith("--") && value.isNotBlank()) { "invalid command option" }
        name to value
    }
}

private fun Map<String, String>.require(name: String): String = this[name]?.takeIf { it.isNotBlank() }
    ?: error("missing required option $name")
