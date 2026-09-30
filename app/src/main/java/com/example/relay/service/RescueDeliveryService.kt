package com.example.relay.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.relay.MainActivity
import com.example.relay.RelayApplication
import com.example.relay.background.ActivationSource
import com.example.relay.background.CommunicationOwner
import com.example.relay.background.DegradeReason
import com.example.relay.domain.OperatingMode
import com.example.relay.domain.RelayRuntimeSettings
import com.example.relay.permissions.NearbyPrerequisite
import com.example.relay.permissions.NearbyPrerequisiteChecker
import com.example.relay.rescue.DevelopmentEnrollmentResult
import com.example.relay.rescue.RescueSubmissionStatus
import com.example.relay.rescue.SharedPreferencesRescueAutomationStore
import com.example.relay.rescue.BrokerDeliveryResult
import com.example.relay.rescue.BrokerReceiptPoller
import com.example.relay.rescue.BrokerRescueDelivery
import com.example.relay.rescue.BrokerRetryWorker
import com.example.relay.rescue.HttpShelterGatewayDelivery
import com.example.relay.rescue.GatewayDeliveryResult
import com.example.relay.rescue.ble.SharedPreferencesCourierDeliveryIdStore
import com.example.relay.rescue.RescueRequestKey
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.StoredRescueRecord
import com.example.relay.rescue.rank
import com.example.relay.data.local.BrokerLedgerEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Lifecycle owner for automatic rescue delivery. */
class RescueDeliveryService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var statusMonitorJob: Job? = null
    private var foregroundNotificationJob: Job? = null
    private var localGatewayDeliveryJob: Job? = null
    private var brokerDeliveryJob: Job? = null
    private var brokerReceiptPollJob: Job? = null
    private var destinationResolutionJob: Job? = null

    // The manifest <service> declares android:foregroundServiceType="connectedDevice" and the
    // matching type is passed on API 29+, so lint's ForegroundServiceType check is a false positive
    // it cannot correlate across the merged variant manifest.
    @android.annotation.SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? RelayApplication
        // Explicit in-app user stop: disarm standby + emergency and drop the shared lease so the
        // restart receiver/worker does not immediately re-arm this device.
        if (intent?.action == ACTION_STOP) {
            app?.let {
                it.diagnostics.record("rescue_delivery_user_stop")
                it.backgroundRelayManager.userStop()
                SharedPreferencesRescueAutomationStore(this).disable()
                it.releaseAllCommunicationLeases()
            }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // A null Intent means the OS recreated the service; the automation opt-in (persisted) is the
        // gate. START_STICKY is only returned below when emergency/undelivered rescue truly needs it.
        if (!SharedPreferencesRescueAutomationStore(this).isEnabled()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val source = intent?.getStringExtra(EXTRA_ACTIVATION_SOURCE)
            ?.let { runCatching { ActivationSource.valueOf(it) }.getOrNull() }
            ?: if (intent == null) ActivationSource.BOOT_RESTORE else ActivationSource.RESCUE_RECEIVED
        createChannel()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        } catch (_: SecurityException) {
            app?.diagnostics?.record("rescue_foreground_security_exception")
            app?.backgroundRelayManager?.markStartFailure("foreground_security_exception")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        app?.let {
            it.diagnostics.record("rescue_delivery_started")
            it.backgroundRelayManager.markEmergencyRequested(source, it.deviceRoleStore.load().name, OperatingMode.RELAY.name)
            startNearbyRelayForRescue(it)
            it.rescueDeliveryCoordinator.start(serviceScope)
            startDestinationResolution(it)
            startLocalGatewayDelivery(it)
            startBrokerDelivery(it)
            startBrokerReceiptPolling(it)
            monitorOwnRequestStatus(it)
            refreshForegroundNotification(it)
        }
        // Emergency delivery is exactly the case the spec allows START_STICKY for: an undelivered
        // rescue must survive OS-recreation. The user-stop path above returns START_NOT_STICKY.
        return START_STICKY
    }

    override fun onDestroy() {
        (application as? RelayApplication)?.let {
            it.diagnostics.record("rescue_delivery_destroyed")
            it.rescueDeliveryCoordinator.stop()
            // Release only THIS owner's lease; USER_COMMUNICATION (if held) keeps the runtime alive.
            it.releaseCommunicationLease(CommunicationOwner.EMERGENCY_MODE)
        }
        foregroundNotificationJob?.cancel()
        localGatewayDeliveryJob?.cancel()
        brokerDeliveryJob?.cancel()
        brokerReceiptPollJob?.cancel()
        destinationResolutionJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Keeps the ongoing notification's connected-peer count and last-transfer time current. */
    private fun refreshForegroundNotification(app: RelayApplication) {
        if (foregroundNotificationJob?.isActive == true) return
        foregroundNotificationJob = serviceScope.launch {
            app.communicationSupervisor.state.collect { state ->
                val peers = state.transport.connectedPeerIds.size
                if (peers > 0) app.backgroundRelayManager.markTransfer()
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(peers))
            }
        }
    }

    private fun notification(connectedPeers: Int = 0): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent().setComponent(ComponentName(this, MainActivity::class.java)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            4,
            Intent().setComponent(ComponentName(this, RescueDeliveryService::class.java)).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Relay 災害通信中")
            .setContentText("接続中の端末: ${connectedPeers}台")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, "アプリを開く", openApp)
            .addAction(0, "災害通信を終了", stopIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel(CHANNEL_ID, "救助要請の自動運搬", NotificationManager.IMPORTANCE_LOW))
            createNotificationChannel(NotificationChannel(STATUS_CHANNEL_ID, "救助要請の対応状況", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun monitorOwnRequestStatus(app: RelayApplication) {
        if (statusMonitorJob?.isActive == true) return
        statusMonitorJob = serviceScope.launch {
            val preferences = getSharedPreferences("relay_rescue_status_notifications", MODE_PRIVATE)
            while (isActive) {
                val own = app.rescueRepository.all()
                    .filter { it.envelope.senderDeviceId == app.deviceId }
                    .maxByOrNull { it.envelope.requestVersion }
                if (own != null && own.state.submissionStatus.rank() >= RescueSubmissionStatus.SHELTER_ACCEPTED.rank()) {
                    val storageKey = "${own.envelope.requestId}:${own.envelope.requestVersion}"
                    val previous = preferences.getString(storageKey, null)
                    val current = own.state.submissionStatus.name
                    if (previous != current) {
                        preferences.edit().putString(storageKey, current).apply()
                        notifyStatus(own.state.submissionStatus)
                    }
                }
                delay(5_000)
            }
        }
    }

    /**
     * Emergency mode reuses the single shared Nearby transport via the [CommunicationLeaseManager]
     * rather than starting a second foreground service. This is the double-FGS fix: the rescue
     * delivery service is already a connectedDevice foreground service, so it acquires an
     * EMERGENCY_MODE lease on the shared runtime instead of launching RelayCommunicationService.
     * Bluetooth/permission/Play-services prerequisites are checked here and reported as DEGRADED
     * (not a crash) when unmet; the lease is not acquired until they are ready.
     */
    private fun startNearbyRelayForRescue(app: RelayApplication) {
        val prerequisite = NearbyPrerequisiteChecker(this, app.nearbyPermissionGate).check()
        if (prerequisite !is NearbyPrerequisite.Ready) {
            app.backgroundRelayManager.markDegraded(prerequisite.toDegradeReason())
            getSharedPreferences("relay_rescue_diagnostics", MODE_PRIVATE).edit()
                .putString("last_nearby_start_result", "degraded")
                .putLong("last_nearby_start_at", System.currentTimeMillis())
                .apply()
            return
        }
        serviceScope.launch {
            val role = app.deviceRoleStore.load()
            when (app.communicationLeaseManager.acquire(
                CommunicationOwner.EMERGENCY_MODE,
                RelayRuntimeSettings(OperatingMode.RELAY, role),
            )) {
                com.example.relay.background.LeaseResult.STARTED,
                com.example.relay.background.LeaseResult.ALREADY_ACTIVE -> {
                    app.backgroundRelayManager.recoverFromDegrade()
                    app.backgroundRelayManager.markHealthyStart()
                }
                com.example.relay.background.LeaseResult.START_FAILED -> {
                    app.backgroundRelayManager.markStartFailure("emergency_lease_start_failed")
                    getSharedPreferences("relay_rescue_diagnostics", MODE_PRIVATE).edit()
                        .putString("last_nearby_start_result", "failed")
                        .putLong("last_nearby_start_at", System.currentTimeMillis())
                        .apply()
                }
                else -> Unit
            }
        }
    }

    /**
     * A no-key SOS remains only under the sender's recovery cipher. Development builds may
     * discover their local generated-key Gateway here; production never auto-enrolls and simply
     * waits for a normal trusted provisioning source to populate the key store.
     */
    private fun startDestinationResolution(app: RelayApplication) {
        if (destinationResolutionJob?.isActive == true) return
        destinationResolutionJob = serviceScope.launch {
            // Dedup guards so the enrollment loop (every 2s) does not flood the capped 40-event
            // trail: a breadcrumb is emitted only when the observable state actually changes.
            var awaitingKeyRecorded = false
            var lastEnrollment: String? = null
            while (isActive) {
                val hasPendingDestination = app.activeRescueSessionCoordinator.hasPendingDestination()
                if (hasPendingDestination && app.rescueShelterKeyStore.load() == null) {
                    if (!awaitingKeyRecorded) {
                        // The SOS is stored but has no trusted shelter key yet; without this the
                        // phone log froze at "rescue_delivery_started" with no explanation.
                        app.diagnostics.record("rescue_awaiting_shelter_key")
                        awaitingKeyRecorded = true
                    }
                    var enrollment = app.developmentShelterManifestBootstrap.tryEnroll()
                    if (enrollment != DevelopmentEnrollmentResult.ENROLLED &&
                        app.rescueShelterKeyStore.load() == null
                    ) {
                        // No shared LAN with the Gateway: fetch the recipient manifest the Gateway
                        // published to the Broker so a mobile-only phone can still build an SOS.
                        enrollment = app.brokerShelterManifestBootstrap.tryEnroll()
                    }
                    // Surface the enrollment outcome on the MAIN trail (deduped) so a phone log
                    // shows WHY it is still waiting (e.g. MANIFEST_UNAVAILABLE = Gateway has not
                    // published / tunnel down; SHELTER_MISMATCH = id mismatch; ENROLLED = success).
                    if (enrollment.name != lastEnrollment) {
                        app.diagnostics.record("rescue_enroll_${enrollment.name}")
                        lastEnrollment = enrollment.name
                    }
                    if (enrollment !in setOf(
                            DevelopmentEnrollmentResult.DISABLED,
                            DevelopmentEnrollmentResult.GATEWAY_NOT_FOUND,
                        )
                    ) {
                        getSharedPreferences("relay_rescue_diagnostics", MODE_PRIVATE).edit()
                            .putString("last_development_enrollment", enrollment.name)
                            .putLong("last_development_enrollment_at", System.currentTimeMillis())
                            .apply()
                    }
                } else {
                    // Key arrived (or nothing pending): reset so a later stall re-announces itself.
                    awaitingKeyRecorded = false
                    lastEnrollment = null
                }
                val resolved = if (hasPendingDestination) {
                    runCatching { app.activeRescueSessionCoordinator.resolvePendingDestinations() }.getOrDefault(0)
                } else {
                    0
                }
                if (resolved > 0) {
                    // The no-key SOS was promoted to PENDING; broker delivery can now pick it up.
                    app.diagnostics.record("rescue_destination_resolved")
                    app.notifyRescueStoreChanged()
                }
                delay(DESTINATION_RESOLUTION_INTERVAL_MILLIS)
            }
        }
    }

    private fun startLocalGatewayDelivery(app: RelayApplication) {
        if (localGatewayDeliveryJob?.isActive == true) return
        localGatewayDeliveryJob = serviceScope.launch {
            // Share the application discovery socket/multicast lock with normal Gateway sync.
            val delivery = HttpShelterGatewayDelivery(app.gatewayDiscovery)
            val deliveryIds = SharedPreferencesCourierDeliveryIdStore(app)
            val attemptedInRound = linkedSetOf<RescueRequestKey>()
            while (isActive) {
                val candidate = selectLocalGatewayCandidate(
                    app.rescueRepository.all(),
                    System.currentTimeMillis(),
                    attemptedInRound,
                )
                if (candidate != null) {
                    // Reserve before the network call so a failed or cancelled attempt cannot
                    // monopolise the next service iteration and starve another rescue request.
                    attemptedInRound += RescueRequestKey(
                        candidate.envelope.requestId,
                        candidate.envelope.requestVersion,
                    )
                    when (val result = delivery.deliver(
                        candidate.envelope,
                        app.deviceId,
                        deliveryIds.idFor(RescueRequestKey(candidate.envelope.requestId, candidate.envelope.requestVersion)),
                    )) {
                    is GatewayDeliveryResult.Accepted -> {
                        val keys = app.rescueShelterKeyStore.load()
                        if (keys != null) {
                            val applied = app.activeRescueSessionStore.applyVerifiedReceipt(
                                RescueRequestKey(candidate.envelope.requestId, candidate.envelope.requestVersion),
                                result.receipt,
                                keys.receiptSigningKey,
                                System.currentTimeMillis(),
                            )
                            if (applied == com.example.relay.rescue.ReceiptApplicationResult.APPLIED) {
                                app.rescueNearbyCoordinator?.onLocalStoreChanged()
                            }
                        }
                    }
                    else -> recordDeliveryDiagnostic(result)
                    }
                } else if (attemptedInRound.isNotEmpty()) {
                    // Start a fresh priority-ordered round after every currently eligible request
                    // has received one delivery or status-polling opportunity.
                    attemptedInRound.clear()
                }
                delay(2_000)
            }
        }
    }

    /**
     * Broker delivery runs independently of LAN/Nearby/BLE.
     * On failure, enqueues a unique OneTime WorkManager retry.
     * Does NOT increment hopCount.
     */
    private fun startBrokerDelivery(app: RelayApplication) {
        if (brokerDeliveryJob?.isActive == true) return
        val endpoint = app.cloudBrokerEndpoint
        if (endpoint.isBlank()) {
            // No baked/overridden Broker endpoint: mobile-data delivery is impossible. Make the
            // dead end visible instead of silently never uploading.
            app.diagnostics.record("broker_delivery_endpoint_blank")
            return
        }

        brokerDeliveryJob = serviceScope.launch {
            val delivery = BrokerRescueDelivery(
                context = this@RescueDeliveryService,
                endpoint = endpoint,
                signingKeyStore = app.uploadSigningKeyStore,
            )
            val dao = app.database.brokerLedgerDao()
            while (isActive) {
                val candidates = app.rescueRepository.all()
                    .filter { it.envelope.expiresAtEpochMillis > System.currentTimeMillis() }
                    .filter {
                        it.state.submissionStatus in setOf(
                            RescueSubmissionStatus.PENDING,
                            RescueSubmissionStatus.IN_TRANSIT,
                        )
                    }
                for (record in candidates) {
                    val ledger = dao.find(record.envelope.requestId, record.envelope.requestVersion)
                    if (ledger?.brokerStatus in setOf("UPLOADED", "FAILED")) continue

                    // Ensure ledger entry exists
                    if (ledger == null) {
                        dao.upsert(BrokerLedgerEntity(
                            requestId = record.envelope.requestId,
                            requestVersion = record.envelope.requestVersion,
                            brokerReceiptId = null,
                            brokerStatus = "PENDING",
                            uploadedAtEpochMillis = null,
                        ))
                    }

                    when (val result = delivery.deliver(record.envelope)) {
                        is BrokerDeliveryResult.Stored -> {
                            app.diagnostics.record("broker_envelope_stored")
                            dao.markUploaded(
                                requestId = record.envelope.requestId,
                                requestVersion = record.envelope.requestVersion,
                                receiptId = result.response.brokerReceiptId,
                                uploadedAt = result.response.storedAtEpochMillis,
                            )
                        }
                        is BrokerDeliveryResult.Offline -> {
                            app.diagnostics.record("broker_offline_retry")
                            dao.markRetrying(record.envelope.requestId, record.envelope.requestVersion)
                            BrokerRetryWorker.enqueue(
                                this@RescueDeliveryService,
                                record.envelope.requestId,
                                record.envelope.requestVersion,
                            )
                        }
                        is BrokerDeliveryResult.Failed -> {
                            app.diagnostics.record(if (result.retryable) "broker_retryable_failure" else "broker_nonretryable_failure")
                            if (result.retryable) {
                                dao.markRetrying(record.envelope.requestId, record.envelope.requestVersion)
                                BrokerRetryWorker.enqueue(
                                    this@RescueDeliveryService,
                                    record.envelope.requestId,
                                    record.envelope.requestVersion,
                                )
                            } else {
                                dao.markFailed(record.envelope.requestId, record.envelope.requestVersion)
                            }
                        }
                        is BrokerDeliveryResult.Disabled -> app.diagnostics.record("broker_disabled")
                    }
                }
                delay(10_000)
            }
        }
    }

    /**
     * Polls Broker for signed shelter receipts.
     * Only SHELTER_* states come from signed receipts via applyReceipt().
     */
    private fun startBrokerReceiptPolling(app: RelayApplication) {
        if (brokerReceiptPollJob?.isActive == true) return
        val endpoint = app.cloudBrokerEndpoint
        if (endpoint.isBlank()) return

        brokerReceiptPollJob = serviceScope.launch {
            val poller = BrokerReceiptPoller(this@RescueDeliveryService)
            poller.start(this, app)
        }
    }

    private fun recordDeliveryDiagnostic(result: GatewayDeliveryResult) {
        getSharedPreferences("relay_rescue_diagnostics", MODE_PRIVATE).edit()
            .putString("last_delivery_result", result.javaClass.simpleName)
            .putLong("last_delivery_at", System.currentTimeMillis())
            .apply()
    }

    private fun notifyStatus(status: RescueSubmissionStatus) {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent().setComponent(ComponentName(this, MainActivity::class.java)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (status) {
            RescueSubmissionStatus.SHELTER_ACCEPTED -> "避難所が救助依頼を確認しました"
            RescueSubmissionStatus.SHELTER_RESPONDING -> "避難所が対応中です"
            RescueSubmissionStatus.SHELTER_COMPLETED -> "対応完了の連絡を受信しました"
            RescueSubmissionStatus.CANCELLED -> "取消を避難所が確認しました"
            RescueSubmissionStatus.SHELTER_REJECTED -> "避難所で確認が必要です"
            else -> return
        }
        val notification = NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Relay 救助依頼の更新")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        getSystemService(NotificationManager::class.java).notify(STATUS_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "relay_rescue_delivery"
        private const val STATUS_CHANNEL_ID = "relay_rescue_status"
        private const val NOTIFICATION_ID = 1002
        private const val STATUS_NOTIFICATION_ID = 1003
        private const val DESTINATION_RESOLUTION_INTERVAL_MILLIS = 2_000L
        const val ACTION_STOP = "com.example.relay.action.STOP_EMERGENCY"
        const val EXTRA_ACTIVATION_SOURCE = "activation_source"

        fun startIfEnabled(context: Context, source: ActivationSource = ActivationSource.BOOT_RESTORE) {
            if (!SharedPreferencesRescueAutomationStore(context).isEnabled()) return
            val intent = Intent(context, RescueDeliveryService::class.java)
                .putExtra(EXTRA_ACTIVATION_SOURCE, source.name)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun enableAndStart(context: Context, source: ActivationSource = ActivationSource.RESCUE_RECEIVED) {
            SharedPreferencesRescueAutomationStore(context).enable()
            startIfEnabled(context, source)
        }

        /** Explicit in-app user stop of disaster communication. Prevents immediate auto-restart. */
        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, RescueDeliveryService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}

private fun NearbyPrerequisite.toDegradeReason(): DegradeReason = when (this) {
    NearbyPrerequisite.Ready -> DegradeReason.NONE
    is NearbyPrerequisite.MissingPermissions -> DegradeReason.MISSING_NEARBY_PERMISSION
    NearbyPrerequisite.BluetoothUnavailable -> DegradeReason.MISSING_BLUETOOTH_PERMISSION
    NearbyPrerequisite.BluetoothDisabled -> DegradeReason.BLUETOOTH_DISABLED
    NearbyPrerequisite.LocationServicesDisabled -> DegradeReason.OS_BACKGROUND_RESTRICTED
    is NearbyPrerequisite.PlayServicesUnavailable -> DegradeReason.PLAY_SERVICES_UNAVAILABLE
}

internal fun selectLocalGatewayCandidate(
    records: List<StoredRescueRecord>,
    nowEpochMillis: Long,
    attemptedKeys: Set<RescueRequestKey> = emptySet(),
): StoredRescueRecord? {
    return records.asSequence()
    .filter { it.envelope.expiresAtEpochMillis > nowEpochMillis }
    .filter {
        it.state.submissionStatus in setOf(
            RescueSubmissionStatus.PENDING,
            RescueSubmissionStatus.IN_TRANSIT,
            RescueSubmissionStatus.SHELTER_STORED,
            RescueSubmissionStatus.SHELTER_ACCEPTED,
            RescueSubmissionStatus.SHELTER_RESPONDING,
        )
    }
    .sortedWith(
        compareByDescending<StoredRescueRecord> { it.state.submissionStatus.deliveryPriority() }
            .thenByDescending { it.envelope.routingUrgency.deliveryPriority() }
            .thenByDescending { it.envelope.createdAtEpochMillis },
    )
    .firstOrNull {
        RescueRequestKey(it.envelope.requestId, it.envelope.requestVersion) !in attemptedKeys
    }
}

private fun RescueSubmissionStatus.deliveryPriority(): Int = when (this) {
    RescueSubmissionStatus.PENDING -> 3
    RescueSubmissionStatus.IN_TRANSIT -> 2
    RescueSubmissionStatus.SHELTER_STORED -> 1
    else -> 0
}

private fun RescueUrgency.deliveryPriority(): Int = when (this) {
    RescueUrgency.IMMEDIATE -> 3
    RescueUrgency.URGENT -> 2
    RescueUrgency.ROUTINE -> 1
}
