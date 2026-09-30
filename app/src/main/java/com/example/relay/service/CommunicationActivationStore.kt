package com.example.relay.service

import android.content.Context

interface CommunicationActivationStore {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
}

/** Testable preference boundary; absence defaults to hands-off relay operation. */
class FunctionalCommunicationActivationStore(
    private val readValue: () -> Boolean?,
    private val writeValue: (Boolean) -> Unit,
    private val defaultEnabled: Boolean = true,
) : CommunicationActivationStore {
    override fun isEnabled(): Boolean = readValue() ?: defaultEnabled
    override fun setEnabled(enabled: Boolean) = writeValue(enabled)
}

class SharedPreferencesCommunicationActivationStore(context: Context) : CommunicationActivationStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, true)

    override fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "relay_communication_activation"
        const val KEY_ENABLED = "enabled"
    }
}

fun shouldAutoStartCommunication(
    permissionsGranted: Boolean,
    transportRunning: Boolean,
    activationEnabled: Boolean,
): Boolean = permissionsGranted && !transportRunning && activationEnabled
