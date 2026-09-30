package com.example.relay.service

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.relay.RelayApplication
import com.example.relay.background.ActivationSource

/**
 * Restores automatic rescue delivery after boot, app replacement, or Bluetooth being re-enabled.
 *
 * Restoration is conservative and honours the persisted state:
 *  - An explicit in-app user stop is sticky; this receiver must NOT immediately re-arm the device.
 *  - Bluetooth changes only restore on transition to STATE_ON (not on every state change).
 *  - The actual start still passes through [RescueDeliveryService.startIfEnabled], which is gated by
 *    the persisted automation opt-in, so plain ARMED does not self-start Nearby after a reboot.
 */
class RescueDeliveryRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? RelayApplication
        // Sticky user stop wins over every OS launch route until the user opts in again.
        if (app?.backgroundRelayManager?.current?.explicitlyStoppedByUser == true) {
            app.diagnostics.record("restart_receiver_suppressed_user_stop")
            return
        }
        val action = intent.action ?: return
        // System broadcasts can be forged as explicit intents. Verify the complete action allowlist
        // before reading extras or starting any recovery work.
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != BluetoothAdapter.ACTION_STATE_CHANGED
        ) {
            return
        }
        val source = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> ActivationSource.BOOT_RESTORE
            Intent.ACTION_MY_PACKAGE_REPLACED -> ActivationSource.PACKAGE_REPLACED
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state != BluetoothAdapter.STATE_ON) return
                ActivationSource.BLUETOOTH_RESTORED
            }
            else -> return
        }
        RescueDeliveryService.startIfEnabled(context, source)
    }
}
