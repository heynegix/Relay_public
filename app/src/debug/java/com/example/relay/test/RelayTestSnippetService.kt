package com.example.relay.test

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import com.example.relay.RelayApplication
import com.example.relay.domain.CreateSafetyMessageUseCase
import com.example.relay.domain.DeviceRole
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.OperatingMode
import com.example.relay.domain.RelayRuntimeSettings
import com.example.relay.domain.SafetyState
import com.example.relay.domain.SystemClock
import com.example.relay.gateway.SharedPreferencesGatewayDeliveryLedger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Debug-only host test surface. It is deliberately kept under app/src/debug so
 * no production manifest or bytecode can reference this endpoint.
 *
 * The wire contract is a small synchronous Binder transaction:
 * interface token, method name, Bundle arguments -> Bundle result. All values
 * crossing the boundary are strings, numbers, booleans, or JSON strings.
 */
class RelayTestSnippetService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binder by lazy { RelayTestSnippetBinder(this) }
    private val app: RelayApplication get() = application as RelayApplication
    private val json = Json { classDiscriminator = "payloadType"; encodeDefaults = true }

    override fun onBind(intent: Intent?): IBinder = binder

    internal fun dispatch(method: String, arguments: Bundle): Bundle = try {
        when (method) {
            "contract" -> ok().apply {
                putInt("protocolVersion", PROTOCOL_VERSION)
                putString("methods", SUPPORTED_METHODS.joinToString(","))
            }
            "createReport" -> createReport(arguments)
            "findMessage" -> findMessage(arguments)
            "deliveryLedger" -> deliveryLedger()
            "nearbyStart" -> startNearby(arguments)
            "nearbyStop" -> stopNearby()
            "gatewayStart" -> startGateway(arguments)
            "gatewayStop" -> stopGateway()
            "bleStart" -> startBle()
            "bleStop" -> stopBle()
            "state" -> state()
            else -> fail("unknown method: $method")
        }
    } catch (error: Exception) {
        fail(error.message ?: error.javaClass.simpleName)
    }

    private fun createReport(arguments: Bundle): Bundle = runBlocking(Dispatchers.IO) {
        val message = CreateSafetyMessageUseCase(
            repository = app.messageRepository,
            policy = MessagePolicy(SystemClock),
            clock = SystemClock,
            originDeviceId = app.deviceId,
            defaultMaxHopCount = arguments.getInt("maxHopCount", 8),
        )(
            state = SafetyState.valueOf(arguments.getString("state", SafetyState.SAFE.name)),
            companionCount = arguments.getInt("companionCount", 0),
            approximateLocation = arguments.getString("approximateLocation", "test-location"),
            note = arguments.getString("note", "Mobly debug report"),
            ttlMillis = arguments.getLong("ttlMillis", DEFAULT_TTL_MILLIS),
        )
        ok().apply {
            putString("messageId", message.messageId)
            putString("messageJson", json.encodeToString(message))
        }
    }

    private fun findMessage(arguments: Bundle): Bundle = runBlocking(Dispatchers.IO) {
        val messageId = arguments.getString("messageId")?.trim().orEmpty()
        if (messageId.isEmpty()) return@runBlocking fail("messageId is required")
        val message = app.messageRepository.find(messageId)
            ?: return@runBlocking fail("message not found: $messageId")
        ok().apply { putString("messageJson", json.encodeToString(message)) }
    }

    private fun deliveryLedger(): Bundle = runBlocking(Dispatchers.IO) {
        val messages = app.messageRepository.all()
        val messageIds = messages.map { it.messageId }.toSet()
        val pending = SharedPreferencesGatewayDeliveryLedger(this@RelayTestSnippetService)
            .pendingIds(messageIds)
        ok().apply {
            putString("messagesJson", json.encodeToString(messages))
            putString("deliveriesJson", json.encodeToString(app.messageRepository.deliveries()))
            putString("receiptsJson", json.encodeToString(app.messageRepository.allReceipts()))
            putString("pendingGatewayIdsJson", json.encodeToString(ListSerializer(String.serializer()), pending.toList()))
        }
    }

    private fun startNearby(arguments: Bundle): Bundle = runBlocking(Dispatchers.IO) {
        val started = app.communicationRuntime.start(runtimeSettings(arguments))
        ok().apply { putBoolean("started", started) }
    }

    private fun stopNearby(): Bundle = runBlocking(Dispatchers.IO) {
        app.communicationRuntime.stop()
        ok().apply { putBoolean("stopped", true) }
    }

    private fun startGateway(arguments: Bundle): Bundle = runBlocking(Dispatchers.IO) {
        val started = app.gatewaySyncEngine.start(runtimeSettings(arguments))
        ok().apply { putBoolean("started", started) }
    }

    private fun stopGateway(): Bundle = runBlocking(Dispatchers.IO) {
        app.gatewaySyncEngine.stop()
        ok().apply { putBoolean("stopped", true) }
    }

    private fun startBle(): Bundle = ok().apply {
        val coordinator = app.rescueDeliveryCoordinator
        putBoolean("started", coordinator != null)
        coordinator?.start(serviceScope)
        putString("state", coordinator?.state?.value?.javaClass?.simpleName ?: "Unavailable")
    }

    private fun stopBle(): Bundle = ok().apply {
        app.rescueDeliveryCoordinator?.stop()
        putBoolean("stopped", true)
        putString("state", app.rescueDeliveryCoordinator?.state?.value?.javaClass?.simpleName ?: "Unavailable")
    }

    private fun state(): Bundle = ok().apply {
        val communication = app.communicationRuntime.state.value
        putBoolean("nearbyRunning", communication.running)
        putString("nearbyMode", communication.mode.name)
        putInt("nearbyPeerCount", communication.peers.size)
        putBoolean("gatewayEnabled", app.gatewaySettingsStore.load().enabled)
        putString("gatewayLastSyncResult", app.gatewaySettingsStore.load().lastSyncResult)
        putString("bleState", app.rescueDeliveryCoordinator?.state?.value?.javaClass?.simpleName ?: "Unavailable")
    }

    private fun runtimeSettings(arguments: Bundle): RelayRuntimeSettings = RelayRuntimeSettings(
        mode = runCatching {
            OperatingMode.valueOf(arguments.getString("mode", OperatingMode.RELAY.name))
        }.getOrDefault(OperatingMode.RELAY),
        role = runCatching {
            DeviceRole.valueOf(arguments.getString("role", DeviceRole.RELAY.name))
        }.getOrDefault(DeviceRole.RELAY),
    )

    private fun ok() = Bundle().apply { putBoolean("ok", true) }
    private fun fail(reason: String) = Bundle().apply {
        putBoolean("ok", false)
        putString("error", reason.take(256))
    }

    override fun onDestroy() {
        runCatching { app.rescueDeliveryCoordinator?.stop() }
        runCatching { runBlocking(Dispatchers.IO) { app.gatewaySyncEngine.stop(); app.communicationRuntime.stop() } }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION = "com.example.relay.debug.RELAY_TEST_SNIPPET"
        const val DESCRIPTOR = "com.example.relay.test.RelayTestSnippet"
        const val TRANSACTION_CALL = IBinder.FIRST_CALL_TRANSACTION
        const val PROTOCOL_VERSION = 1
        const val DEFAULT_TTL_MILLIS = 24L * 60 * 60 * 1_000
        val SUPPORTED_METHODS = listOf(
            "contract", "createReport", "findMessage", "deliveryLedger", "state",
            "nearbyStart", "nearbyStop", "gatewayStart", "gatewayStop", "bleStart", "bleStop",
        )
    }
}

class RelayTestSnippetBinder(private val service: RelayTestSnippetService) : Binder() {
    fun call(method: String, arguments: Bundle = Bundle()): Bundle = service.dispatch(method, arguments)

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != RelayTestSnippetService.TRANSACTION_CALL) return super.onTransact(code, data, reply, flags)
        val output = reply ?: return false
        data.enforceInterface(RelayTestSnippetService.DESCRIPTOR)
        val method = data.readString().orEmpty()
        @Suppress("DEPRECATION")
        val arguments = data.readBundle(javaClass.classLoader) ?: Bundle()
        val result = call(method, arguments)
        output.writeNoException()
        output.writeBundle(result)
        return true
    }
}
