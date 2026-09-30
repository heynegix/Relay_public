package com.example.relay.service

import android.app.Notification
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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.relay.RelayApplication
import com.example.relay.background.CommunicationOwner
import com.example.relay.background.LeaseResult
import com.example.relay.domain.DeviceRole
import com.example.relay.domain.DeviceRoleCodec
import com.example.relay.domain.OperatingMode
import com.example.relay.domain.RelayRuntimeSettings
import com.example.relay.permissions.NearbyPrerequisite
import com.example.relay.permissions.NearbyPrerequisiteChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RelayCommunicationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var stopServiceJob: Job? = null
    private val app get() = application as RelayApplication
    private val shutdownCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        CommunicationShutdownCoordinator(
            scope = scope,
            // Release only THIS owner's lease. If EMERGENCY_MODE still holds the runtime, stopping
            // user communication must NOT tear down the shared Nearby/Gateway session.
            stopGateway = { app.communicationLeaseManager.release(CommunicationOwner.USER_COMMUNICATION) },
            stopCommunication = {},
            onFailure = { reason -> app.communicationSupervisor.reportStartFailure(reason) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            app.diagnostics.record("communication_stop_requested")
            activationStore(this).setEnabled(false)
            stopCommunication()
            return START_NOT_STICKY
        }
        val mode = intent?.getStringExtra(EXTRA_MODE)?.let { runCatching { OperatingMode.valueOf(it) }.getOrNull() }
            ?: OperatingMode.NORMAL
        val role = DeviceRoleCodec.decodeOrDefault(intent?.getStringExtra(EXTRA_ROLE))
        try {
            startForegroundSafely(notification(0))
        } catch (error: Exception) {
            app.diagnostics.record("communication_foreground_failed_${error.javaClass.simpleName}")
            app.communicationRuntime.reportStartFailure("Foreground Serviceを開始できません: ${error.message ?: error.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        val prerequisite = NearbyPrerequisiteChecker(this, app.nearbyPermissionGate).check()
        if (prerequisite !is NearbyPrerequisite.Ready) {
            app.diagnostics.record("communication_prerequisite_not_ready")
            app.communicationRuntime.reportStartFailure(prerequisite.userMessage())
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            try {
                val leaseResult = app.communicationLeaseManager.acquire(
                    CommunicationOwner.USER_COMMUNICATION,
                    RelayRuntimeSettings(mode, role),
                )
                if (leaseResult == LeaseResult.START_FAILED) {
                    app.diagnostics.record("communication_start_rejected")
                    stopForegroundCompat()
                    stopSelf()
                    return@launch
                }
                stateJob?.cancel()
                stateJob = launch {
                    app.communicationSupervisor.state.collect { state ->
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state.transport.connectedPeerIds.size))
                    }
                }
            } catch (error: Exception) {
                app.diagnostics.record("communication_start_failed_${error.javaClass.simpleName}")
                app.communicationRuntime.reportStartFailure(error.message ?: error.javaClass.simpleName)
                stopForegroundCompat()
                stopSelf()
            }
        }
        app.diagnostics.record("communication_start_requested")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        app.diagnostics.record("communication_service_destroyed")
        stateJob?.cancel()
        shutdownCoordinator.requestStop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopCommunication() {
        if (stopServiceJob?.isActive == true) return
        val shutdown = shutdownCoordinator.requestStop()
        stopServiceJob = scope.launch {
            shutdown.join()
            stopForegroundCompat()
            stopSelf()
        }
    }

    // The manifest <service> declares android:foregroundServiceType="connectedDevice" and the
    // matching type is passed on API 29+, so lint's ForegroundServiceType check is a false positive
    // it cannot correlate across the merged variant manifest.
    @android.annotation.SuppressLint("ForegroundServiceType")
    private fun startForegroundSafely(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun notification(connectedPeers: Int): Notification {
        val stopIntent = Intent()
            .setComponent(ComponentName(this, RelayCommunicationService::class.java))
            .setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Relay オフライン通信中")
            .setContentText("接続中の端末: ${connectedPeers}台")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "通信停止", stopPendingIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Relayオフライン通信", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "DRILLまたはRELAYモードで通信中であることを表示します"
                },
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "relay_communication"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.example.relay.action.START_COMMUNICATION"
        private const val ACTION_STOP = "com.example.relay.action.STOP_COMMUNICATION"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_ROLE = "role"

        fun start(context: Context, mode: OperatingMode, role: DeviceRole): String? = try {
            activationStore(context).setEnabled(true)
            val intent = Intent(context, RelayCommunicationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_ROLE, DeviceRoleCodec.encode(role))
            ContextCompat.startForegroundService(context, intent)
            null
        } catch (error: Exception) {
            "Foreground Serviceを開始できません: ${error.message ?: error.javaClass.simpleName}"
        }

        fun stop(context: Context) {
            activationStore(context).setEnabled(false)
            context.startService(Intent(context, RelayCommunicationService::class.java).setAction(ACTION_STOP))
        }

        fun activationStore(context: Context): CommunicationActivationStore =
            SharedPreferencesCommunicationActivationStore(context)
    }
}

private fun NearbyPrerequisite.userMessage(): String = when (this) {
    NearbyPrerequisite.Ready -> ""
    is NearbyPrerequisite.MissingPermissions -> "Nearby通信に必要な権限がありません"
    NearbyPrerequisite.BluetoothUnavailable -> "この端末はBluetoothを利用できません"
    NearbyPrerequisite.BluetoothDisabled -> "Bluetoothを有効にしてください"
    NearbyPrerequisite.LocationServicesDisabled -> "このAndroid版では位置情報サービスを有効にしてください"
    is NearbyPrerequisite.PlayServicesUnavailable -> "Google Play servicesを利用できません（$statusCode）"
}
