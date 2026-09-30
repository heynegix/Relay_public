package com.example.relay.rescue

import android.content.Context

/** Persistent opt-in set when this device creates or receives a rescue envelope.
 *
 * Automatic delivery normally has no casual toggle, because stopping it would strand already
 * accepted emergency data. [disable] exists only for the explicit in-app user stop: pressing the
 * emergency-stop action must prevent the restart receiver/worker from immediately re-arming the
 * service. The delivery service still fails closed when Android has not granted
 * Bluetooth/foreground-service permission.
 */
interface RescueAutomationStore {
    fun isEnabled(): Boolean
    fun enable()

    /** Explicit user stop only. Clears the opt-in so receivers do not immediately restart delivery. */
    fun disable()
}

class SharedPreferencesRescueAutomationStore(context: Context) : RescueAutomationStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    override fun enable() {
        preferences.edit().putBoolean(KEY_ENABLED, true).apply()
    }

    override fun disable() {
        preferences.edit().putBoolean(KEY_ENABLED, false).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "relay_rescue_automation"
        const val KEY_ENABLED = "enabled"
    }
}
