package com.example.relay.pcgateway

import com.example.relay.pcgateway.rescue.RescueDeliveryIngress
import com.example.relay.pcgateway.rescue.RescueIntakeService
import com.example.relay.pcgateway.rescue.RescueKeyStore
import com.example.relay.pcgateway.rescue.BrokerPullAgent
import com.example.relay.pcgateway.rescue.ReceiptOutbox
import com.example.relay.pcgateway.rescue.ShelterManifestPublisher
import com.example.relay.pcgateway.rescue.SqliteRescuePersistence
import com.example.relay.pcgateway.rescue.provisioning.BleBridgeEnvironmentStore
import com.example.relay.pcgateway.rescue.provisioning.SignedShelterManifestStore
import com.example.relay.rescue.RegionalRootBundle
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

fun main(args: Array<String>) {
    val config = GatewayConfig()
    if (args.isNotEmpty()) {
        runLocalGatewayAdminCommand(config, args)
        return
    }
    val store = GatewayStore(config)
    val access = store.accessStore()
    if (config.bootstrapUsername != null && config.bootstrapSecret != null) {
        val created = access.bootstrapAdmin(config.bootstrapUsername, config.bootstrapSecret, source = "local_bootstrap")
        println(if (created) "Initial Gateway administrator bootstrap completed." else "Initial administrator bootstrap was not applied (already initialized or invalid input).")
    }
    if (access.bootstrapRequired()) {
        System.err.println("Gateway operator access is unavailable until a one-time bootstrap administrator is configured.")
    }
    access.recordRuntimeConfiguration(config.auditConfigurationTarget, "local_startup")

    // A production/lab host must be explicitly provisioned.  Generating a replacement private
    // key at service start could silently make prior envelopes undecryptable, so this is allowed
    // only for the isolated development profile. RescueKeyStore still checks permissions before
    // importing private material; permission, parse, expiry, or self-test failures stop startup.
    val rescueKeyPath = Path.of(config.rescueKeyPath)
    if (config.profile != GatewayProfile.DEVELOPMENT && !Files.isRegularFile(rescueKeyPath)) {
        error("rescue key material is not provisioned; automatic generation is disabled outside development")
    }
    val rescueKeys = RescueKeyStore(rescueKeyPath, config.shelterId, protection = config.keyProtection).loadOrCreate()
    val now = System.currentTimeMillis()
    val rescueKeyStatus = GatewayRescueKeyStatus.valid(
        expiresAtEpochMillis = rescueKeys.manifest.validUntilEpochMillis,
        warning = rescueKeys.manifest.validUntilEpochMillis - now <= config.rescueKeyExpiryWarningMillis,
        dpapiProtected = config.keyProtection == GatewayKeyProtection.DPAPI,
    )
    if (rescueKeyStatus.status == "expiring_soon") {
        System.err.println("Rescue key expiry is approaching; manual key rotation and re-provisioning are required before pilot use.")
    }
    val verifiedBleManifest = loadVerifiedBleManifest(config, rescueKeys)
    val bleBridgeEnvironment = verifiedBleManifest?.let { signed ->
        config.bleBridgeEnvironmentFor(signed).also { environment ->
            BleBridgeEnvironmentStore.write(
                Path.of(config.rescueSignedManifestPath!!).resolveSibling("ble-bridge.env"),
                environment,
            )
        }
    }
    // Production/lab never bind public rescue delivery unless the advertised shelter identity is
    // root-signed, current, and matches the two locally held private keys. The deliberately
    // isolated development profile is the one exception: it publishes the generated local public
    // manifest so a debug/localDev phone can enroll it without a certificate or manual key copy.
    // This compatibility path is never enabled by a missing production configuration.
    val developmentRescueDelivery = config.profile == GatewayProfile.DEVELOPMENT
    val rescueDeliveryReady = verifiedBleManifest != null || developmentRescueDelivery
    var receiptOutboxRef: ReceiptOutbox? = null
    val rescueIntakeService = RescueIntakeService(
        shelterId = config.shelterId,
        recipientPrivateKey = rescueKeys.recipientPrivateKey,
        shelterSigningPrivateKey = rescueKeys.receiptSigningPrivateKey,
        persistence = store.rescuePersistence(),
        onReceiptIssued = { receipt -> receiptOutboxRef?.enqueue(receipt) },
    )
    rescueIntakeService.purgeExpiredDetails(config.rescueRetentionMillis)
    // Retention must not depend on an operator opening the console: a daemon sweep enforces the
    // configured policy periodically. Only the purged count is ever logged.
    Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "relay-retention-sweeper").apply { isDaemon = true }
    }.scheduleWithFixedDelay(
        {
            runCatching { rescueIntakeService.purgeExpiredDetails(config.rescueRetentionMillis) }
                .onSuccess { purged -> if (purged > 0) println("Retention sweep removed $purged expired terminal rescue detail(s)") }
        },
        config.retentionSweepIntervalMillis,
        config.retentionSweepIntervalMillis,
        TimeUnit.MILLISECONDS,
    )
    val offlineMap = OfflineMapTileCache(
        root = Path.of(config.offlineMapPath),
        profile = config.regionalProfile.map,
        regionId = config.regionalProfile.regionId,
        regionName = config.regionalProfile.displayName,
    )
    val officialInformation = OfficialInformationService(
        cachePath = Path.of(config.officialInfoCachePath),
        profile = config.regionalProfile,
    )
    val rescueIngress = rescueDeliveryReady.let { ready -> if (ready) RescueDeliveryIngress(rescueIntakeService, routeType = RouteType.NEARBY, routeAttemptSink = store.pilotOperationsStore()::recordRouteAttempt) else null }
    val beacon = GatewayLanBeacon(config, rescueTrustReady = rescueDeliveryReady)
    val consoleHost = if (config.host in setOf("0.0.0.0", "::")) "127.0.0.1" else config.host
    println("Relay PC Gateway listening on http://${config.host}:${config.port}")
    println("Operator console: http://$consoleHost:${config.port}/")
    println("Runtime profile: ${config.profile.name.lowercase()} / LAN mode: ${config.lanMode.name.lowercase()}")
    if (config.trainingMode) {
        println("TRAINING MODE: drill data only; production database, keys, and credentials are not touched")
    }
    if (config.legacyAdminKeyEnabled) {
        println("Legacy X-Admin-Key source: ${resolveAdminKeySource(config.trainingMode)} (development compatibility only; value is not printed)")
    } else {
        println("Operator authentication: individual local staff accounts with HttpOnly session cookies")
    }
    println("Database: ${config.dbPath}")
    println(
        "Rescue key at-rest protection: " + when (config.keyProtection) {
            GatewayKeyProtection.DPAPI -> "Windows DPAPI (per-user)"
            GatewayKeyProtection.FILE_PERMISSIONS -> "owner-only file permissions (not DPAPI/HSM/KMS)"
        },
    )
    println("Rescue retention: terminal details kept ${config.rescueRetentionDays} day(s), swept every ${config.retentionSweepIntervalMinutes} minute(s) (pilot defaults pending privacy/legal approval)")
    println("Anonymous ingress: ${config.anonymousIngressEnabled}")
    println("Rescue shelter: ${config.shelterId}")
    GatewayEnrollmentAnnouncement.consoleLines(config, rescueKeys.manifest.fingerprint()).forEach(::println)
    if (verifiedBleManifest != null) {
        println("Rescue BLE trust: ready (root-signed shelter manifest verified)")
        println("Rescue maintenance manifest fingerprint: ${verifiedBleManifest.manifest.fingerprint()}")
        println("Rescue BLE signed-manifest fingerprint base64: ${bleBridgeEnvironment?.signedManifestFingerprintBase64}")
        println("BLE sidecar ingress: http://${config.bleBridgeIngressHost}:${config.bleBridgeIngressPort} (loopback only; secret is not printed)")
    } else if (developmentRescueDelivery) {
        println("Rescue delivery: development-generated keys are enabled for debug/localDev clients only")
    } else {
        println("Rescue BLE trust: not ready; automatic rescue delivery is disabled (legacy manifest remains maintenance-only)")
    }
    if (config.lanDiscoveryEnabled &&
        (!GatewayConfig.isLoopbackHost(config.host) || config.lanMode == GatewayLanMode.TLS_REVERSE_PROXY)
    ) {
        beacon.start()
        println("LAN discovery beacon: UDP ${config.lanDiscoveryPort} → /api/public/sync/messages")
    } else if (config.lanDiscoveryEnabled) {
        println("LAN discovery is inactive while the HTTP server is bound to loopback")
    }

    // Broker cloud relay: receipt outbox + pull agent (independent of LAN/BLE)
    // Startup order: Outbox MUST be ready before PullAgent starts (receipts from early pulls must not be lost)
    val brokerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var brokerHttpClient: HttpClient? = null
    var receiptOutbox: ReceiptOutbox? = null
    if (config.brokerUrl != null) {
        val client = HttpClient(CIO)
        brokerHttpClient = client
        val brokerCredential = config.brokerCredential ?: config.brokerLegacyApiKey

        // 1. Create ReceiptOutbox FIRST using the same DB connection as rescue persistence
        val rescuePersistence = store.rescuePersistence() as SqliteRescuePersistence
        val outbox = ReceiptOutbox(
            persistence = rescuePersistence,
            brokerUrl = config.brokerUrl,
            shelterId = config.shelterId,
            gatewayId = config.gatewayId,
            httpClient = client,
            gatewayCredential = brokerCredential,
        )
        receiptOutbox = outbox
        receiptOutboxRef = outbox
        brokerScope.launch { outbox.startFlusher(this) }

        // 2. Start PullAgent AFTER outbox is ready
        val cursorPath = Path.of(config.dbPath).resolveSibling("broker-pull-cursor.txt")
        val pullAgent = BrokerPullAgent(
            brokerUrl = config.brokerUrl,
            shelterId = config.shelterId,
            gatewayId = config.gatewayId,
            intakeService = rescueIntakeService,
            httpClient = client,
            gatewayCredential = brokerCredential,
            cursorPath = cursorPath,
            pollIntervalMs = config.brokerPollIntervalMs,
            routeAttemptSink = store.pilotOperationsStore()::recordRouteAttempt,
        )
        brokerScope.launch { pullAgent.start(this) }

        // 3. Publish this shelter's public manifest so a phone on mobile data (no prior LAN visit)
        //    can fetch the recipient key. Public-only material; retries until the Broker acknowledges.
        val manifestPublisher = ShelterManifestPublisher(
            brokerUrl = config.brokerUrl,
            shelterId = config.shelterId,
            gatewayId = config.gatewayId,
            manifest = rescueKeys.manifest,
            httpClient = client,
            gatewayCredential = brokerCredential,
        )
        brokerScope.launch { manifestPublisher.start(this) }

        println("Broker cloud relay: ${config.brokerUrl} (poll every ${config.brokerPollIntervalMs}ms)")
    } else {
        println("Broker cloud relay: disabled (RELAY_BROKER_URL not set)")
    }
    if (config.host == "0.0.0.0") {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val firewallHint = when {
            os.contains("win") -> "Restrict with Windows Firewall (Private network only)."
            os.contains("mac") -> "Restrict with macOS Application Firewall / pf (scripts/setup-pc-gateway-macos.sh)."
            else -> "Restrict with the host firewall to the trusted LAN only."
        }
        println("Bound on all interfaces. $firewallHint")
    }
    try {
        val bleIngressServer = rescueIngress?.let { ingress ->
            embeddedServer(Netty, host = config.bleBridgeIngressHost, port = config.bleBridgeIngressPort) {
                bleBridgeIngressModule(config, ingress)
            }.start(wait = false)
        }
        try {
            embeddedServer(Netty, host = config.host, port = config.port) {
                // This unsigned route is retained only for explicitly marked maintenance tooling.
                // BLE delivery is gated above by the independently verified signed manifest.
                gatewayModule(
                    config,
                    store,
                    rescueManifest = rescueKeys.manifest,
                    rescueBleReady = verifiedBleManifest != null,
                    rescueDeliveryReady = rescueDeliveryReady,
                    rescueIntakeService = rescueIntakeService,
                    offlineMap = offlineMap,
                    officialInformation = officialInformation,
                    rescueKeyStatus = rescueKeyStatus,
                )
            }.start(wait = true)
        } finally {
            bleIngressServer?.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        }
    } finally {
        beacon.close()
        offlineMap.close()
        runBlocking { brokerScope.coroutineContext[Job]?.cancelAndJoin() }
        brokerHttpClient?.close()
        store.close()
    }
}

/**
 * Deliberately small local-only administration command surface.  Bootstrap never accepts a
 * password on the command line, so Task Scheduler history and shell history cannot retain it.
 */
private fun runLocalGatewayAdminCommand(config: GatewayConfig, args: Array<String>) {
    when (args.first()) {
        "bootstrap-admin" -> {
            require(args.size == 3 && args[1] == "--username") {
                "usage: bootstrap-admin --username <local-staff-id>"
            }
            val username = args[2]
            val secret = System.getenv("RELAY_GATEWAY_BOOTSTRAP_CLI_SECRET")
                ?.takeIf { it.isNotBlank() }
                ?: System.console()?.readPassword("Bootstrap password: ")?.concatToString()
                ?: error(
                    "No interactive console is available. Set RELAY_GATEWAY_BOOTSTRAP_CLI_SECRET for this one command; do not pass a password as an argument.",
                )
            GatewayStore(config).use { store ->
                val created = store.accessStore().bootstrapAdmin(username, secret, source = "local_cli")
                check(created) {
                    "Bootstrap was not applied. An account already exists or the local username/password policy was not met."
                }
            }
            println("Initial Gateway administrator bootstrap completed. The secret was not persisted or printed.")
        }
        "help", "--help", "-h" -> println(
            "Relay PC Gateway commands:\n  bootstrap-admin --username <local-staff-id>\n" +
                "Use RELAY_GATEWAY_BOOTSTRAP_CLI_SECRET or an interactive console; never pass a password as an argument.",
        )
        else -> error("unknown local Gateway command: ${args.first()}")
    }
}

/**
 * Startup boundary for automatic rescue delivery. It intentionally returns no usable identity on
 * any parse, signature, expiry, root, or local-key mismatch failure.
 */
private fun loadVerifiedBleManifest(
    config: GatewayConfig,
    localKeys: com.example.relay.pcgateway.rescue.RescueGatewayKeys,
): com.example.relay.rescue.SignedShelterManifest? = runCatching {
    val rootPath = requireNotNull(config.rescueRegionalRootBundlePath) {
        "RELAY_RESCUE_REGIONAL_ROOT_BUNDLE_FILE is not configured"
    }
    val signedManifestPath = requireNotNull(config.rescueSignedManifestPath) {
        "RELAY_RESCUE_SIGNED_MANIFEST_FILE is not configured"
    }
    val rootBundle = Json { ignoreUnknownKeys = false }
        .decodeFromString<RegionalRootBundle>(java.nio.file.Files.readString(Path.of(rootPath)))
    SignedShelterManifestStore(Path.of(signedManifestPath)).loadVerified(
        rootBundle = rootBundle,
        localKeys = localKeys,
        nowEpochMillis = System.currentTimeMillis(),
    )
}.onFailure { error ->
    // Do not expose key values, signatures, or file contents in logs or HTTP status.
    // Do not surface parsing/crypto exception text: it can contain paths or serialized input.
    System.err.println("Rescue BLE trust verification failed; automatic rescue delivery remains disabled (${error.javaClass.simpleName})")
}.getOrNull()
