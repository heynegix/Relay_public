package com.example.relay.rescue

import android.content.Context
import com.example.relay.RelayApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope

/**
 * Periodically polls the Broker for signed shelter receipts addressed to this device.
 * Uses an unguessable capability token (not the public deviceKeyId).
 * Uses Broker-assigned monotonic seq cursor (not device time) for reliable incremental polling.
 * Only SHELTER_* states come from signed receipts via applyReceipt();
 * BROKER_STORED is a separate ledger flag and does NOT advance shelter status.
 */
class BrokerReceiptPoller(
    private val context: Context,
    private val pollIntervalMs: Long = 30_000L,
) {
    /** Broker monotonic seq cursor. Persisted via SharedPreferences for restart resilience. */
    private var lastSeq: Long = 0L
    private var cursorPreferenceKey: String = "last_seq"

    /**
     * Starts the polling loop. Call from a coroutine scope.
     * Runs independently of Nearby/BLE/LAN paths.
     */
    suspend fun start(scope: CoroutineScope, app: RelayApplication) {
        val endpoint = app.cloudBrokerEndpoint
        if (endpoint.isBlank()) return

        val delivery = BrokerRescueDelivery(
            context = context,
            endpoint = endpoint,
            signingKeyStore = app.uploadSigningKeyStore,
        )

        // Load persisted cursor
        cursorPreferenceKey = cursorKey(endpoint)
        lastSeq = context.getSharedPreferences("relay_broker_poller", Context.MODE_PRIVATE)
            .getLong(cursorPreferenceKey, 0L)

        while (scope.isActive) {
            pollOnce(delivery, app)
            delay(pollIntervalMs)
        }
    }

    /** Single poll iteration. Exposed for testing. */
    suspend fun pollOnce(delivery: BrokerRescueDelivery, app: RelayApplication) {
        // A Broker restart, token loss, or Android backup restore can leave the local receipt
        // cursor intact while the registration is gone. Re-establish the capability before
        // polling rather than silently treating that state as an empty receipt batch forever.
        if (!delivery.ensureRegistered()) return
        val batch = delivery.pollReceipts(sinceSeq = lastSeq)
        if (batch.receipts.isEmpty()) return

        // A missing key is transient (for example while a shelter directory is being refreshed),
        // so keep the cursor pinned until verification can actually run.
        val keys = app.rescueShelterKeyStore.load() ?: return

        for (receipt in batch.receipts) {
            val result = app.activeRescueSessionStore.applyVerifiedReceipt(
                RescueRequestKey(receipt.receipt.requestId, receipt.receipt.requestVersion),
                receipt,
                keys.receiptSigningKey,
                System.currentTimeMillis(),
            )
            if (result == ReceiptApplicationResult.APPLIED) {
                app.rescueNearbyCoordinator?.onLocalStoreChanged()
            }
            // A receipt for a pruned/expired request or a permanently invalid signature must not
            // block every later receipt in the Broker's ordered stream. The durable cursor is
            // advanced after the batch; valid receipts remain idempotent on retry.
        }

        // Advance cursor to Broker's monotonic seq (not device time)
        if (batch.cursor > lastSeq) {
            lastSeq = batch.cursor
            persistCursor()
        }
    }

    private fun persistCursor() {
        context.getSharedPreferences("relay_broker_poller", Context.MODE_PRIVATE)
            .edit().putLong(cursorPreferenceKey, lastSeq).apply()
    }

    private fun cursorKey(endpoint: String): String = "last_seq:" + endpoint
}
