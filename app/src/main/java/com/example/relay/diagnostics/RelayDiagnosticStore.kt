package com.example.relay.diagnostics

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Short, local-only event trail for the Bluetooth-to-Broker PoC.
 *
 * Do not add rescue text, GPS, endpoint URLs, credentials, key material, peer identifiers, or
 * envelope identifiers here. The trail survives a process crash so the operator can report the
 * execution boundary that failed without USB/adb access.
 */
class RelayDiagnosticStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun record(event: String) {
        val safeEvent = event
            .replace(Regex("[^A-Za-z0-9_.:-]"), "_")
            .take(96)
            .ifBlank { "unknown" }
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val previous = preferences.getStringSet(EVENTS, emptySet()).orEmpty()
        val next = (previous + "$timestamp $safeEvent").sorted().takeLast(MAX_EVENTS).toSet()
        preferences.edit().putStringSet(EVENTS, next).apply()
    }

    fun recent(): List<String> = preferences.getStringSet(EVENTS, emptySet()).orEmpty().sortedDescending()

    fun clear() {
        preferences.edit().remove(EVENTS).apply()
    }

    companion object {
        private const val PREFERENCES = "relay_poc_diagnostics"
        private const val EVENTS = "events"
        private const val MAX_EVENTS = 40
    }
}
