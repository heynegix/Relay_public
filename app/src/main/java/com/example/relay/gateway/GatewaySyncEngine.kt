package com.example.relay.gateway

import com.example.relay.domain.DeliveryReceipt
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MessageRepository
import com.example.relay.domain.OperatingMode
import com.example.relay.domain.RelayRecordType
import com.example.relay.domain.RelayRuntimeSettings
import com.example.relay.domain.ReceiptType
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GatewaySyncEngine(
    private val repository: MessageRepository,
    private val settingsStore: GatewaySettingsStoreContract,
    private val credentialStore: GatewayCredentialStoreContract,
    private val client: GatewayBridgeClient,
    private val policy: MessagePolicy,
    private val scope: CoroutineScope,
    private val deliveryLedger: GatewayDeliveryLedger,
    private val discovery: GatewayDiscovery? = null,
    private val localBridgeId: String = "",
    /**
     * Optional out-of-band trust material (QR/manual enrollment). When present, a discovered beacon
     * whose advertised identity contradicts an enrolled gateway is refused instead of delivered to,
     * closing the LAN-spoofing gap that plain discovery cannot. Null preserves anonymous discovery.
     */
    private val enrollmentStore: GatewayEnrollmentStore? = null,
) {
    private val mutex = Mutex()
    private val lifecycleMutex = Mutex()
    private val running = AtomicBoolean(false)
    private var job: Job? = null

    /**
     * Start background sync while communication is active.
     * Zero-op path always runs the loop (public discovery does not need UI toggles).
     * [GatewaySettings.automaticSync] false is an explicit opt-out for operators only.
     */
    suspend fun start(runtime: RelayRuntimeSettings): Boolean = lifecycleMutex.withLock {
        if (runtime.mode == OperatingMode.NORMAL) return false
        if (!running.compareAndSet(false, true)) return true
        val settings = settingsStore.load()
        // Public discovery path must work without any gateway settings UI.
        val shouldLoop = settings.automaticSync || discovery != null
        if (shouldLoop) job = scope.launch { loop() }
        return true
    }

    suspend fun stop() = lifecycleMutex.withLock {
        running.set(false)
        val stoppingJob = job
        job = null
        stoppingJob?.cancelAndJoin()
    }

    suspend fun requestPair(code: String): Boolean {
        val settings = settingsStore.load()
        return try {
            client.requestPair(settings, code)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    suspend fun syncOnce(): GatewaySyncResult = mutex.withLock {
        val settings = settingsStore.load()
        val token = credentialStore.load()
        val useAuthenticated = settings.enabled &&
            settings.host.isNotBlank() &&
            settings.bridgeId.isNotBlank() &&
            !token.isNullOrBlank()
        if (settings.enabled && settings.host.isNotBlank() && settings.bridgeId.isNotBlank() &&
            token.isNullOrBlank() && discovery == null
        ) {
            return@withLock GatewaySyncResult.Deferred("gateway_token_missing")
        }
        val all = repository.all()
        val knownIds = all.map { it.messageId }.toSet()
        val pendingIds = deliveryLedger.pendingIds(knownIds)
        val messages = all.asSequence()
            .filter { it.messageId in pendingIds }
            .filter {
                it.recordType == RelayRecordType.REPORT ||
                    it.recordType == RelayRecordType.STATUS_CHANGE
            }
            .mapNotNull(policy::prepareForGatewayUpload)
            .sortedWith(
                compareByDescending<com.example.relay.domain.RelayMessage> {
                    it.recordType == RelayRecordType.STATUS_CHANGE
                }
                    .thenByDescending { it.priority }
                    .thenByDescending { it.createdAt },
            )
            .take(128)
            .toList()
        if (messages.isEmpty() && !useAuthenticated) {
            settingsStore.record("idle")
            return@withLock GatewaySyncResult.Completed(0, 0)
        }
        return@withLock try {
            val push = if (useAuthenticated && messages.isEmpty()) {
                GatewayPushResult(com.example.relay.gateway.protocol.SyncMessagesResponse())
            } else if (useAuthenticated) {
                client.push(settings, token!!, messages)
            } else {
                val gateway = discovery?.discover()
                    ?: settings.host.trim().takeIf(::isValidLanIpv4)?.let { host ->
                        settingsStore.recordDiscovery(host, "manual_fallback")
                        DiscoveredGateway(host, settings.port, "manual-fallback", settings.scheme)
                    }
                    ?: return@withLock GatewaySyncResult.Deferred("gateway_not_found").also {
                        settingsStore.recordDiscovery(null, "udp_timeout_no_fallback")
                        settingsStore.recordDelivery("not_sent:gateway_not_found")
                        settingsStore.record("gateway_not_found")
                    }
                settingsStore.recordDiscovery(gateway.host, if (gateway.gatewayId == "manual-fallback") "manual_fallback" else "beacon_received")
                val trust = if (gateway.gatewayId == "manual-fallback") {
                    GatewayTrustDecision.UNVERIFIED
                } else {
                    enrollmentStore?.decisionFor(gateway) ?: GatewayTrustDecision.UNVERIFIED
                }
                if (trust == GatewayTrustDecision.REJECTED) {
                    return@withLock GatewaySyncResult.Deferred("gateway_trust_rejected").also {
                        settingsStore.recordDiscovery(gateway.host, "trust_rejected")
                        settingsStore.recordDelivery("not_sent:gateway_trust_rejected")
                        settingsStore.record("gateway_trust_rejected")
                    }
                }
                if (localBridgeId.isBlank()) {
                    return@withLock GatewaySyncResult.Deferred("bridge_identity_missing").also {
                        settingsStore.record("bridge_identity_missing")
                    }
                }
                client.pushPublic(gateway, localBridgeId, "Relay Bridge", messages)
            }
            val receipts = if (useAuthenticated) {
                client.pullReceipts(settings, token!!)
            } else {
                push.response.receipts.mapNotNull { receipt ->
                    when (receipt.receiptType) {
                        "GATEWAY_RECEIVED" -> DeliveryReceipt(
                            receipt.receiptId,
                            receipt.messageId,
                            ReceiptType.GATEWAY_RECEIVED,
                            receipt.actorId,
                            receipt.recordedAt,
                        )
                        "GATEWAY_RECEIVED_UNVERIFIED" -> DeliveryReceipt(
                            receipt.receiptId,
                            receipt.messageId,
                            ReceiptType.GATEWAY_RECEIVED_UNVERIFIED,
                            receipt.actorId,
                            receipt.recordedAt,
                        )
                        else -> null
                    }
                }
            }
            // Persist receipts before clearing pending so a crash mid-sync can retry.
            val persistedReceiptMessageIds = receipts
                .filter {
                    it.receiptType == ReceiptType.GATEWAY_RECEIVED ||
                        it.receiptType == ReceiptType.GATEWAY_RECEIVED_UNVERIFIED
                }
                .filter { repository.find(it.messageId) != null }
                .mapNotNull { receipt ->
                    when (repository.insertReceipt(receipt)) {
                        com.example.relay.domain.InsertResult.Inserted,
                        com.example.relay.domain.InsertResult.Duplicate,
                        -> receipt.messageId
                        com.example.relay.domain.InsertResult.Collision,
                        is com.example.relay.domain.InsertResult.Rejected,
                        -> null
                    }
                }
                .toSet()
            deliveryLedger.markCompleted(persistedReceiptMessageIds)
            deliveryLedger.markTerminal(
                push.response.rejected
                    .filter { it.reason.isTerminalGatewayRejection() }
                    .mapTo(linkedSetOf()) { it.messageId },
            )
            val deliverySummary = "sent=${push.response.acceptedMessageIds.size}, " +
                "duplicate=${push.response.duplicateMessageIds.size}, " +
                "receipts=${receipts.size}"
            settingsStore.recordDelivery("success:$deliverySummary")
            settingsStore.record(deliverySummary)
            GatewaySyncResult.Completed(
                push.response.acceptedMessageIds.size + push.response.duplicateMessageIds.size,
                receipts.size,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val httpError = error as? GatewayHttpException
            if (httpError != null) {
                settingsStore.recordDelivery("failed:http_${httpError.status}")
                settingsStore.record("http_${httpError.status}")
                GatewaySyncResult.Failed(
                    "http_${httpError.status}",
                    retryable = httpError.status >= 500 || httpError.status == 429,
                )
            } else {
                settingsStore.recordDelivery("failed:network_error")
                settingsStore.record("network_error")
                GatewaySyncResult.Failed("network_error")
            }
        }
    }

    private suspend fun loop() {
        var delayMs = 5_000L
        while (scope.coroutineContext.isActive && running.get()) {
            when (val result = syncOnce()) {
                is GatewaySyncResult.Completed -> delayMs = 5_000L
                is GatewaySyncResult.Deferred -> delayMs = 30_000L
                is GatewaySyncResult.Failed -> {
                    delayMs = if (result.retryable) {
                        (delayMs * 2).coerceAtMost(15 * 60_000L)
                    } else {
                        60_000L
                    }
                }
            }
            delay(delayMs)
        }
    }
}

interface GatewayDeliveryLedger {
    /** Reconciles local messages and returns only IDs that still require a Gateway Receipt. */
    fun pendingIds(existingMessageIds: Set<String>): Set<String>
    /** IDs confirmed by a Receipt returned directly from a PC Gateway sync response. */
    fun markCompleted(messageIds: Set<String>)
    /** Immutable records rejected by validation must not be retried forever. */
    fun markTerminal(messageIds: Set<String>)
}

class SharedPreferencesGatewayDeliveryLedger(context: Context) : GatewayDeliveryLedger {
    private val preferences = context.getSharedPreferences("relay_gateway_pending", Context.MODE_PRIVATE)

    @Synchronized
    override fun pendingIds(existingMessageIds: Set<String>): Set<String> {
        val completed = preferences.getStringSet("completed_ids", emptySet()).orEmpty().intersect(existingMessageIds)
        val terminal = preferences.getStringSet("terminal_ids", emptySet()).orEmpty().intersect(existingMessageIds)
        preferences.edit()
            .putStringSet("completed_ids", completed)
            .putStringSet("terminal_ids", terminal)
            .apply()
        return existingMessageIds - completed - terminal
    }

    @Synchronized
    override fun markCompleted(messageIds: Set<String>) {
        if (messageIds.isEmpty()) return
        val completed = preferences.getStringSet("completed_ids", emptySet()).orEmpty() + messageIds
        preferences.edit().putStringSet("completed_ids", completed).apply()
    }

    @Synchronized
    override fun markTerminal(messageIds: Set<String>) {
        if (messageIds.isEmpty()) return
        val terminal = preferences.getStringSet("terminal_ids", emptySet()).orEmpty() + messageIds
        preferences.edit().putStringSet("terminal_ids", terminal).apply()
    }

}

class InMemoryGatewayDeliveryLedger : GatewayDeliveryLedger {
    private var completed = emptySet<String>()
    private var terminal = emptySet<String>()

    override fun pendingIds(existingMessageIds: Set<String>): Set<String> {
        completed = completed.intersect(existingMessageIds)
        terminal = terminal.intersect(existingMessageIds)
        return existingMessageIds - completed - terminal
    }

    override fun markCompleted(messageIds: Set<String>) { completed += messageIds }

    override fun markTerminal(messageIds: Set<String>) { terminal += messageIds }
}

private fun String.isTerminalGatewayRejection(): Boolean = this in setOf(
    "invalid_identifier",
    "invalid_type",
    "invalid_enum",
    "invalid_ttl",
    "invalid_hop",
    "expired_or_invalid_ttl",
    "payload_too_large",
    "invalid_status_change",
    "messageId collision",
)


private fun isValidLanIpv4(value: String): Boolean {
    val octets = value.split('.')
    return octets.size == 4 && octets.all { it.toIntOrNull()?.let { octet -> octet in 0..255 } == true } &&
        value != "0.0.0.0" && value != "255.255.255.255"
}
