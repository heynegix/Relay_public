package com.example.relay.gateway

import android.content.Context
import com.example.relay.BuildConfig

interface GatewaySettingsStoreContract {
    fun load(): GatewaySettings
    fun save(settings: GatewaySettings)
    fun record(result: String, connectedAt: Long = System.currentTimeMillis())
    fun recordDiscovery(ip: String?, result: String) {}
    fun recordDelivery(result: String) {}
}

class GatewaySettingsStore(context: Context) : GatewaySettingsStoreContract {
    private val preferences = context.getSharedPreferences("relay_gateway_settings", Context.MODE_PRIVATE)
    private val defaultScheme = if (BuildConfig.ALLOW_HTTP_GATEWAY) "http" else "https"
    override fun load() = GatewaySettings(
        host = preferences.getString("host", "") ?: "",
        port = preferences.getInt("port", 8080),
        gatewayName = preferences.getString("name", "") ?: "",
        bridgeId = preferences.getString("bridgeId", "") ?: "",
        enabled = preferences.getBoolean("enabled", false),
        automaticSync = preferences.getBoolean("automatic", true),
        lastConnectedAt = preferences.getLong("lastConnected", 0).takeIf { it > 0 },
        lastSyncResult = preferences.getString("lastResult", null),
        lastDiscoveredGatewayIp = preferences.getString("discoveredGatewayIp", null),
        lastDiscoveryResult = preferences.getString("discoveryResult", null),
        lastDeliveryResult = preferences.getString("deliveryResult", null),
        // Preserve old manual LAN settings only in debug/localDev. Production/pilot upgrades
        // intentionally migrate an absent scheme to HTTPS rather than silently keeping HTTP.
        scheme = preferences.getString("scheme", defaultScheme) ?: defaultScheme,
    )
    override fun save(settings: GatewaySettings) { preferences.edit().putString("host", settings.host.trim()).putInt("port", settings.port).putString("name", settings.gatewayName.trim()).putString("bridgeId", settings.bridgeId.trim()).putBoolean("enabled", settings.enabled).putBoolean("automatic", settings.automaticSync).putString("scheme", settings.scheme.lowercase()).apply() }
    override fun record(result: String, connectedAt: Long) { preferences.edit().putString("lastResult", result.take(160)).putLong("lastConnected", connectedAt).apply() }
    override fun recordDiscovery(ip: String?, result: String) { preferences.edit().putString("discoveredGatewayIp", ip?.take(64)).putString("discoveryResult", result.take(80)).apply() }
    override fun recordDelivery(result: String) { preferences.edit().putString("deliveryResult", result.take(80)).apply() }
}
