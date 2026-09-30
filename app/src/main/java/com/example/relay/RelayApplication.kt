package com.example.relay

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.relay.data.local.RelayDatabase
import com.example.relay.diagnostics.RelayDiagnosticStore
import com.example.relay.data.local.PlaintextDatabaseMigration
import com.example.relay.data.repository.RoomMessageRepository
import com.example.relay.data.repository.RoomRescueEnvelopeRepository
import com.example.relay.data.local.SqlCipherPassphraseStore
import com.example.relay.domain.DeviceRoleStore
import com.example.relay.domain.StringDeviceRoleStore
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.SystemClock
import com.example.relay.permissions.AndroidNearbyPermissionGate
import com.example.relay.protocol.FixedWindowIncomingPayloadPolicy
import com.example.relay.protocol.PacketCodec
import com.example.relay.runtime.RelayCommunicationRuntime
import com.example.relay.sync.SyncCoordinator
import com.example.relay.sync.SyncPlanner
import com.example.relay.transport.GoogleNearbyPlatform
import com.example.relay.transport.NearbyConnectionMode
import com.example.relay.transport.NearbyConnectionPolicy
import com.example.relay.transport.NearbyConnectionsTransport
import com.example.relay.gateway.GatewayBridgeClient
import com.example.relay.gateway.GatewayCredentialStore
import com.example.relay.gateway.SharedPreferencesGatewayDeliveryLedger
import com.example.relay.gateway.GatewaySettingsStore
import com.example.relay.gateway.GatewaySyncEngine
import com.example.relay.gateway.HttpGatewayBridgeClient
import com.example.relay.gateway.PersistentGatewayEnrollmentStore
import com.example.relay.gateway.SharedPreferencesEnrollmentPayloadStorage
import com.example.relay.gateway.UdpGatewayDiscovery
import com.example.relay.cloud.AndroidNetworkOnlineDetector
import com.example.relay.cloud.InternetPrioritySync
import com.example.relay.cloud.HttpsPriorityMessageSource
import com.example.relay.cloud.InternetPrioritySyncScheduler
import com.example.relay.cloud.SharedPreferencesPriorityFeedConfigStore
import com.example.relay.cloud.ServerSyncGateway
import com.example.relay.location.AndroidLocationProvider
import com.example.relay.location.LocationProvider
import com.example.relay.background.BackgroundRelayManager
import com.example.relay.background.CommunicationOwner
import com.example.relay.background.BackgroundRelayStateStore
import com.example.relay.background.CommunicationLeaseManager
import com.example.relay.background.ProcessExitRecord
import com.example.relay.background.SharedPreferencesBackgroundRelayStateStore
import com.example.relay.domain.RelayRuntimeSettings
import com.example.relay.service.CommunicationSupervisor
import com.example.relay.service.RescueDeliveryService
import com.example.relay.rescue.RescueShelterKeyStore
import com.example.relay.rescue.ReportSigningKeyStore
import com.example.relay.rescue.UploadSigningKeyStore
import com.example.relay.rescue.HttpShelterManifestClient
import com.example.relay.rescue.ShelterManifestEnrollment
import com.example.relay.rescue.DevelopmentShelterManifestBootstrap
import com.example.relay.rescue.BrokerShelterManifestBootstrap
import com.example.relay.rescue.RegionalShelterDirectoryResolver
import com.example.relay.regional.AndroidRegionalDeploymentProfile
import com.example.relay.rescue.SignedRegionalShelterDirectory
import com.example.relay.rescue.ble.AndroidShelterBleClient
import com.example.relay.rescue.ble.SharedPreferencesCourierDeliveryIdStore
import com.example.relay.rescue.ble.ShelterDeliveryCoordinator
import com.example.relay.rescue.nearby.RescueNearbyCoordinator
import com.example.relay.rescue.session.ActiveRescueSessionCoordinator
import com.example.relay.rescue.session.AesGcmRecoveryPayloadCipher
import com.example.relay.rescue.session.AndroidKeystoreSessionSecretKeyProvider
import com.example.relay.rescue.session.RescueDeliveryNotifier
import com.example.relay.rescue.session.RoomActiveRescueSessionStore
import com.example.relay.rescue.trust.AssetRegionalRootLoader
import com.example.relay.rescue.trust.DirectoryStoreAcceptance
import com.example.relay.rescue.trust.RegionalTrustRuntime
import com.example.relay.rescue.trust.RoomRegionalDirectoryPersistence
import com.example.relay.rescue.trust.VerifiedRegionalDirectoryStore
import com.google.android.gms.nearby.connection.ConnectionsClient
import java.net.URI
import java.util.UUID
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RelayApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val deviceId: String by lazy {
        getSharedPreferences("relay_identity", MODE_PRIVATE).let { preferences ->
            preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
                preferences.edit().putString("device_id", it).apply()
            }
        }
    }
    val diagnostics: RelayDiagnosticStore by lazy { RelayDiagnosticStore(this) }
    val regionalDeploymentProfile: AndroidRegionalDeploymentProfile by lazy {
        AndroidRegionalDeploymentProfile.load(this)
    }

    val database: RelayDatabase by lazy {
        System.loadLibrary("sqlcipher")
        val passphrase = SqlCipherPassphraseStore(this).loadOrCreate()
        PlaintextDatabaseMigration.migrateIfNeeded(this, DATABASE_NAME, passphrase)
        Room.databaseBuilder(this, RelayDatabase::class.java, DATABASE_NAME)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
            )
            .build()
    }
    val messageRepository: RoomMessageRepository by lazy { RoomMessageRepository(database) }
    val rescueRepository: RoomRescueEnvelopeRepository by lazy { RoomRescueEnvelopeRepository(database) }
    val rescueShelterKeyStore: RescueShelterKeyStore by lazy { RescueShelterKeyStore(this) }
    val reportSigningKeyStore: ReportSigningKeyStore by lazy { ReportSigningKeyStore() }
    val uploadSigningKeyStore: UploadSigningKeyStore by lazy { UploadSigningKeyStore(this) }
    val activeRescueSessionStore: RoomActiveRescueSessionStore by lazy {
        RoomActiveRescueSessionStore(database, rescueRepository)
    }
    /** Separate AES-GCM key alias from the SQLCipher passphrase protection. */
    val activeRescueSessionCoordinator: ActiveRescueSessionCoordinator by lazy {
        ActiveRescueSessionCoordinator(
            store = activeRescueSessionStore,
            recoveryCipher = AesGcmRecoveryPayloadCipher(AndroidKeystoreSessionSecretKeyProvider()),
            shelterKeyProvider = rescueShelterKeyStore,
            locationProvider = locationProvider,
            nowEpochMillis = SystemClock::nowMillis,
            deliveryNotifier = RescueDeliveryNotifier {
                // Commit has already succeeded when this is called. The service can therefore
                // start Nearby even for a local-only SOS while it waits for a trusted key.
                RescueDeliveryService.enableAndStart(this)
                notifyRescueStoreChanged()
            },
            senderEnvelopeAuthorizer = uploadSigningKeyStore::authorizeEnvelope,
        )
    }

    /**
     * Broker endpoint resolution order:
     * 1. Debug/local-development SharedPreferences override (never trusted by pilot/release)
     * 2. Build-time resource (broker.xml) for a reviewed pilot build
     * Empty = Broker delivery disabled. Must be HTTPS.  A production regional provisioning
     * bundle still needs a signed directory/trust-root integration; until then the runtime
     * deliberately does not accept hand-edited endpoint preferences in a release build.
     */
    val cloudBrokerEndpoint: String
        get() {
            val fromPrefs = if (BuildConfig.DEBUG) {
                getSharedPreferences("relay_broker_config", MODE_PRIVATE)
                    .getString("broker_endpoint", null)
            } else null
            val endpoint = fromPrefs ?: getString(R.string.broker_endpoint).trim()
            return endpoint.takeIf(::isValidBrokerEndpoint).orEmpty()
        }

    /**
     * Development-only override for a local HTTPS Broker. Release/pilot variants must obtain
     * their endpoint from reviewed signed provisioning rather than a mutable preference.
     */
    fun setCloudBrokerEndpoint(url: String) {
        check(BuildConfig.DEBUG) { "mutable Broker endpoint configuration is disabled outside debug/localDev" }
        require(url.isBlank() || isValidBrokerEndpoint(url)) { "Broker endpoint must use HTTPS" }
        getSharedPreferences("relay_broker_config", MODE_PRIVATE)
            .edit().putString("broker_endpoint", url).apply()
    }
    val shelterManifestEnrollment: ShelterManifestEnrollment by lazy {
        ShelterManifestEnrollment(HttpShelterManifestClient(), rescueShelterKeyStore)
    }
    /**
     * Loads only an approved public root bundle for this variant, then re-verifies persisted signed
     * directories. An absent/malformed root or directory leaves BLE Gateway delivery unavailable
     * without disabling LAN, Nearby, or Broker paths.
     */
    val regionalTrustRuntime: RegionalTrustRuntime by lazy {
        RegionalTrustRuntime(
            rootLoader = AssetRegionalRootLoader(
                this,
                BuildConfig.REGIONAL_ROOT_BUNDLE_ASSET,
                BuildConfig.REGIONAL_ROOT_BUNDLE_ENVIRONMENTS,
            ),
            directoryStore = VerifiedRegionalDirectoryStore(RoomRegionalDirectoryPersistence(database)),
            nowEpochMillis = SystemClock::nowMillis,
        )
    }
    val regionalShelterDirectoryResolver: RegionalShelterDirectoryResolver
        get() = regionalTrustRuntime.resolver

    /** Integration point for a future signed-directory import channel. */
    suspend fun acceptRegionalDirectoryCandidate(candidate: SignedRegionalShelterDirectory): DirectoryStoreAcceptance =
        regionalTrustRuntime.acceptCandidate(candidate)
    val rescueDeliveryCoordinator: ShelterDeliveryCoordinator by lazy {
        ShelterDeliveryCoordinator(
            client = AndroidShelterBleClient(this),
            repository = rescueRepository,
            directoryResolver = regionalShelterDirectoryResolver,
            carrierId = deviceId,
            deliveryIds = SharedPreferencesCourierDeliveryIdStore(this),
            onRepositoryChanged = { rescueNearbyCoordinator?.onLocalStoreChanged() },
            receiptApplier = { key, receipt, publicKey ->
                activeRescueSessionStore.applyVerifiedReceipt(key, receipt, publicKey, SystemClock.nowMillis())
            },
        )
    }

    val deviceRoleStore: DeviceRoleStore by lazy {
        val preferences = getSharedPreferences("relay_settings", MODE_PRIVATE)
        StringDeviceRoleStore(
            readRaw = { preferences.getString("device_role", null) },
            writeRaw = { roleName -> preferences.edit().putString("device_role", roleName).apply() },
        )
    }

    val nearbyPermissionGate by lazy { AndroidNearbyPermissionGate(this) }
    /**
     * Persisted admission policy for the Nearby transport. Defaults to OPEN so the
     * disaster mesh forms hands-off; an operator can lock it to TRUSTED and the
     * choice survives restarts. Mode changes are written back on every transition.
     */
    val nearbyConnectionPolicy: NearbyConnectionPolicy by lazy {
        val preferences = getSharedPreferences("relay_settings", MODE_PRIVATE)
        val storedMode = runCatching {
            NearbyConnectionMode.valueOf(preferences.getString("nearby_connection_mode", null) ?: "")
        }.getOrDefault(NearbyConnectionMode.OPEN)
        val trustedPeers = preferences.getStringSet("nearby_trusted_peers", emptySet()).orEmpty()
        NearbyConnectionPolicy(
            initialMode = storedMode,
            trustedPeers = trustedPeers,
            onTrustedPeersChanged = { peers ->
                preferences.edit().putStringSet("nearby_trusted_peers", peers).apply()
            },
        ).also { policy ->
            applicationScope.launch {
                policy.mode.collect { mode ->
                    preferences.edit().putString("nearby_connection_mode", mode.name).apply()
                }
            }
        }
    }
    val nearbyTransport: NearbyConnectionsTransport by lazy {
        NearbyConnectionsTransport(
            localDeviceId = deviceId,
            platform = GoogleNearbyPlatform(this, packageName),
            permissionGate = nearbyPermissionGate,
            scope = applicationScope,
            connectionPolicy = nearbyConnectionPolicy,
            maxPayloadBytes = ConnectionsClient.MAX_BYTES_DATA_SIZE,
        )
    }
    /**
     * Uses the existing Nearby transport only after it is available. A construction failure leaves
     * the ordinary relay coordinator operational; rescue envelopes remain safely persisted for a
     * later retry rather than being downgraded or exposed.
     */
    val rescueNearbyCoordinator: RescueNearbyCoordinator? by lazy {
        runCatching {
            RescueNearbyCoordinator(
                repository = rescueRepository,
                transport = nearbyTransport,
                nowEpochMillis = SystemClock::nowMillis,
                shelterKeyProvider = rescueShelterKeyStore,
                onEnvelopeStored = {
                    // A Nearby receiver is also a courier. Its delivery service owns the LAN,
                    // Broker and BLE retry loops required to carry a stored envelope onward.
                    diagnostics.record("nearby_envelope_stored")
                    RescueDeliveryService.enableAndStart(this)
                },
                receiptApplier = { key, receipt, publicKey ->
                    activeRescueSessionStore.applyVerifiedReceipt(key, receipt, publicKey, SystemClock.nowMillis())
                },
            )
        }.getOrNull()
    }
    val syncCoordinator: SyncCoordinator by lazy {
        val policy = MessagePolicy(SystemClock)
        SyncCoordinator(
            deviceId = deviceId,
            transport = nearbyTransport,
            repository = messageRepository,
            planner = SyncPlanner(messageRepository, policy),
            policy = policy,
            codec = PacketCodec(policy),
            clock = SystemClock,
            scope = applicationScope,
            incomingPayloadPolicy = FixedWindowIncomingPayloadPolicy(SystemClock),
            rescueNearbyCoordinator = rescueNearbyCoordinator,
        )
    }
    val communicationRuntime: RelayCommunicationRuntime by lazy {
        RelayCommunicationRuntime(nearbyTransport, syncCoordinator, applicationScope)
    }
    val gatewaySettingsStore: GatewaySettingsStore by lazy { GatewaySettingsStore(this) }
    val gatewayCredentialStore: GatewayCredentialStore by lazy { GatewayCredentialStore(this) }
    val gatewayDiscovery: UdpGatewayDiscovery by lazy {
        UdpGatewayDiscovery(
            context = this,
            onDiagnostic = { diagnostic -> gatewaySettingsStore.recordDiscovery(diagnostic.gatewayIp, diagnostic.result) },
        )
    }
    /**
     * Development-preview only: discovers a local generated-key Gateway and enrolls its manifest
     * without asking an individual developer to copy a fingerprint. Release/pilot builds receive
     * a disabled instance and retain the ordinary independently verified enrollment boundary.
     */
    val developmentShelterManifestBootstrap: DevelopmentShelterManifestBootstrap by lazy {
        DevelopmentShelterManifestBootstrap(gatewayDiscovery, rescueShelterKeyStore)
    }
    /**
     * Development-preview only: when the phone has no shared LAN with the Gateway, it fetches the
     * recipient manifest the Gateway published to the Broker so a mobile-only SOS can be built.
     * Release/pilot builds receive a disabled instance and keep the verified enrollment boundary.
     */
    val brokerShelterManifestBootstrap: BrokerShelterManifestBootstrap by lazy {
        BrokerShelterManifestBootstrap(
            brokerEndpointProvider = { cloudBrokerEndpoint },
            shelterId = getString(R.string.rescue_shelter_id).trim(),
            keyStore = rescueShelterKeyStore,
        )
    }
    /**
     * Durable out-of-band trust material for LAN gateways. Seeds from persisted canonical enrollment
     * payloads on startup and is wired into [gatewaySyncEngine] so a discovered beacon whose identity
     * contradicts an enrolled gateway is refused instead of delivered to.
     */
    val gatewayEnrollmentStore: PersistentGatewayEnrollmentStore by lazy {
        PersistentGatewayEnrollmentStore(
            SharedPreferencesEnrollmentPayloadStorage(this),
            onDiagnostic = { diagnostics.record(it) },
        )
    }
    val gatewaySyncEngine: GatewaySyncEngine by lazy {
        GatewaySyncEngine(
            messageRepository,
            gatewaySettingsStore,
            gatewayCredentialStore,
            HttpGatewayBridgeClient(),
            MessagePolicy(SystemClock),
            applicationScope,
            SharedPreferencesGatewayDeliveryLedger(this),
            discovery = gatewayDiscovery,
            localBridgeId = deviceId,
            enrollmentStore = gatewayEnrollmentStore.enrollmentStore(),
        )
    }
    val communicationSupervisor: CommunicationSupervisor by lazy {
        CommunicationSupervisor(communicationRuntime, gatewaySyncEngine, internetPrioritySyncScheduler)
    }

    /**
     * Durable state for the opt-in background relay feature. Uses its own preferences file and does
     * NOT migrate the encrypted DB/Keystore/legacy prefs.
     */
    val backgroundRelayStateStore: BackgroundRelayStateStore by lazy {
        SharedPreferencesBackgroundRelayStateStore(this)
    }
    val backgroundRelayManager: BackgroundRelayManager by lazy {
        BackgroundRelayManager(backgroundRelayStateStore, diagnostics)
    }

    /**
     * The single owner/lease over the shared communication runtime (Nearby transport + Gateway sync).
     * Both [RelayCommunicationService] (USER_COMMUNICATION) and [RescueDeliveryService]
     * (EMERGENCY_MODE) acquire a lease here instead of each starting its own Nearby stack, so there
     * is exactly one transport / one Advertising / one Discovery / one Gateway sync regardless of how
     * many owners are active, and releasing one owner never stops another owner's communication.
     */
    val communicationLeaseManager: CommunicationLeaseManager by lazy {
        CommunicationLeaseManager(
            start = { settings: RelayRuntimeSettings -> communicationSupervisor.start(settings) },
            stop = { communicationSupervisor.stop() },
        )
    }

    val locationProvider: LocationProvider by lazy { AndroidLocationProvider(this) }

    /** When online, merge priority remote messages into local Room store. */
    val internetPrioritySync: ServerSyncGateway by lazy {
        InternetPrioritySync(
            detector = AndroidNetworkOnlineDetector(this),
            source = HttpsPriorityMessageSource(SharedPreferencesPriorityFeedConfigStore(this)),
            repository = messageRepository,
            policy = MessagePolicy(SystemClock),
        )
    }
    val internetPrioritySyncScheduler: InternetPrioritySyncScheduler by lazy {
        InternetPrioritySyncScheduler(
            gateway = internetPrioritySync,
            scope = applicationScope,
        )
    }

    fun notifyRescueStoreChanged() {
        applicationScope.launch { rescueNearbyCoordinator?.onLocalStoreChanged() }
    }

    /**
     * Releases a communication-runtime lease from the application scope. Used by services in
     * onDestroy, when their own scope is already being cancelled, so a single owner releasing never
     * leaves the shared runtime half-stopped for the others.
     */
    fun releaseCommunicationLease(owner: CommunicationOwner) {
        applicationScope.launch { communicationLeaseManager.release(owner) }
    }

    /** Hard stop for an explicit user stop: drops every lease owner and stops the shared runtime. */
    fun releaseAllCommunicationLeases() {
        applicationScope.launch { communicationLeaseManager.releaseAll() }
    }

    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Persist only the exception class and thread class; never the message or stack trace.
            diagnostics.record("uncaught_${thread.javaClass.simpleName}_${error.javaClass.simpleName}")
            previousHandler?.uncaughtException(thread, error)
        }
        diagnostics.record("application_started")
        // Task Manager / force-stop on Android 13+ delivers no onDestroy callback, so on the next
        // launch we inspect ApplicationExitInfo. The reason is recorded for diagnostics only; the
        // persisted explicit-stop flag (set by the in-app stop action) is the authority on whether
        // we treat ourselves as user-stopped — REASON_USER_REQUESTED alone is deliberately NOT.
        backgroundRelayManager.onProcessStart(newestProcessExitRecord())
        // Root asset parsing is safe on the main thread. Persisted-directory revalidation performs
        // Room I/O below; until that finishes BLE remains fail-closed with an empty resolver.
        regionalTrustRuntime
        applicationScope.launch {
            regionalTrustRuntime.reloadAcceptedDirectories()
        }
        // Test/pilot identities must be supplied explicitly through the enrollment path. Neither a
        // bundled unsigned manifest nor LAN discovery is allowed to auto-approve a Gateway.
        // A process restart must not require a courier to open a transfer screen.
        RescueDeliveryService.startIfEnabled(this)
        // Covers the narrow interval between the atomic Room commit and a foreground-service
        // start. This is not a location service and is not used to self-start after force-stop.
        applicationScope.launch {
            val hasLiveSession = activeRescueSessionStore.all().any { session ->
                session.terminalStatus == null && session.expiresAtEpochMillis > SystemClock.nowMillis()
            }
            if (hasLiveSession) RescueDeliveryService.enableAndStart(this@RelayApplication)
        }
    }

    private fun newestProcessExitRecord(): ProcessExitRecord? {
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            val am = getSystemService(android.app.ActivityManager::class.java) ?: return null
            am.getHistoricalProcessExitReasons(packageName, 0, 1)
                .firstOrNull()
                ?.let { ProcessExitRecord(reason = it.reason, timestampMillis = it.timestamp) }
        }.getOrNull()
    }

    private companion object {
        const val DATABASE_NAME = "relay.db"

        fun isValidBrokerEndpoint(value: String): Boolean = runCatching {
            val uri = URI(value.trim())
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null &&
                (uri.port == -1 || uri.port in 1..65_535)
        }.getOrDefault(false)
    }
}

/** Legacy rows get lifetime=0 during migration and are intentionally treated as expired. */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN recordType TEXT NOT NULL DEFAULT 'REPORT'")
        db.execSQL("ALTER TABLE messages ADD COLUMN lifetimeMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN accumulatedAgeMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN receivedElapsedRealtimeMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN persistedAtWallClockMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_originDeviceId ON messages(originDeviceId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS delivery_receipts (receiptId TEXT NOT NULL PRIMARY KEY, messageId TEXT NOT NULL, receiptType TEXT NOT NULL, actorId TEXT NOT NULL, recordedAt INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_delivery_receipts_messageId_receiptType_actorId ON delivery_receipts(messageId, receiptType, actorId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_delivery_receipts_messageId ON delivery_receipts(messageId)")
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN elapsedRealtimeSessionId TEXT NOT NULL DEFAULT ''")
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS rescue_envelopes (
                requestId TEXT NOT NULL,
                requestVersion INTEGER NOT NULL,
                envelopeId TEXT NOT NULL,
                ciphertextSha256Hex TEXT NOT NULL,
                createdAtEpochMillis INTEGER NOT NULL,
                expiresAtEpochMillis INTEGER NOT NULL,
                storageSizeBytes INTEGER NOT NULL,
                envelopeJson TEXT NOT NULL,
                receivedAtEpochMillis INTEGER NOT NULL,
                submissionStatus TEXT NOT NULL,
                submissionCount INTEGER NOT NULL,
                signedReceiptJson TEXT,
                PRIMARY KEY(requestId, requestVersion)
            )""",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_rescue_envelopes_expiresAtEpochMillis " +
                "ON rescue_envelopes(expiresAtEpochMillis)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_rescue_envelopes_submissionStatus " +
                "ON rescue_envelopes(submissionStatus)",
        )
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN reportSignatureJson TEXT")
    }
}

internal val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS broker_ledger (
                requestId TEXT NOT NULL,
                requestVersion INTEGER NOT NULL,
                brokerReceiptId TEXT,
                brokerStatus TEXT NOT NULL DEFAULT 'PENDING',
                uploadedAtEpochMillis INTEGER,
                retryCount INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(requestId, requestVersion)
            )""",
        )
    }
}

/** Public signed directory state; no private root material is ever persisted here. */
internal val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS regional_shelter_directories (
                regionId TEXT NOT NULL,
                generation INTEGER NOT NULL,
                directoryDigest TEXT NOT NULL,
                directoryJson TEXT NOT NULL,
                acceptedAtEpochMillis INTEGER NOT NULL,
                PRIMARY KEY(regionId)
            )""",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_regional_shelter_directories_generation " +
                "ON regional_shelter_directories(generation)",
        )
    }
}

/** Session recovery ciphertext is kept separate from route envelopes and from the SQLCipher key. */
internal val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS active_rescue_sessions (
                requestId TEXT NOT NULL,
                latestVersion INTEGER NOT NULL,
                sealedRecoveryPayload BLOB NOT NULL,
                recoveryNonce BLOB NOT NULL,
                trackingMode TEXT NOT NULL,
                latestSubmissionStatus TEXT NOT NULL,
                createdAtEpochMillis INTEGER NOT NULL,
                updatedAtEpochMillis INTEGER NOT NULL,
                expiresAtEpochMillis INTEGER NOT NULL,
                terminalStatus TEXT,
                PRIMARY KEY(requestId)
            )""",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_active_rescue_sessions_updatedAtEpochMillis " +
                "ON active_rescue_sessions(updatedAtEpochMillis)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_active_rescue_sessions_expiresAtEpochMillis " +
                "ON active_rescue_sessions(expiresAtEpochMillis)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_active_rescue_sessions_terminalStatus " +
                "ON active_rescue_sessions(terminalStatus)",
        )
    }
}
