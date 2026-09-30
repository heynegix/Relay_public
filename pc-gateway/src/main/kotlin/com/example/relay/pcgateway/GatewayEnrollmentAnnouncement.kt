package com.example.relay.pcgateway

import com.example.relay.gateway.GatewayEnrollmentCodec
import com.example.relay.gateway.GatewayEnrollmentToken
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Builds the out-of-band trust bootstrap material an operator shares with a device so a discovered
 * LAN beacon can be upgraded from anonymous to verified. The payload carries only non-secret
 * identity fields plus the already-public shelter manifest fingerprint; it never contains a private
 * key, session token, or admin secret.
 */
object GatewayEnrollmentAnnouncement {
    fun buildToken(config: GatewayConfig, manifestFingerprint: String, host: String): GatewayEnrollmentToken =
        GatewayEnrollmentToken(
            gatewayId = config.gatewayId,
            shelterId = config.shelterId,
            host = host,
            port = config.publicPort,
            scheme = config.publicScheme,
            manifestFingerprint = manifestFingerprint.trim().lowercase(),
        )

    /**
     * Best-effort LAN address used only as a connection hint (the trust match ignores host). Falls
     * back to a site-local IPv4 when the server binds all interfaces, then to the configured host.
     */
    fun resolveAnnouncedHost(config: GatewayConfig): String {
        if (config.host !in setOf("0.0.0.0", "::")) return config.host
        val siteLocal = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.address as? Inet4Address }
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
        return siteLocal ?: config.host
    }

    /**
     * Operator-facing console lines. Returns an empty list when the identity cannot be encoded
     * (invalid gatewayId/fingerprint) so startup never aborts on a cosmetic banner.
     */
    fun consoleLines(config: GatewayConfig, manifestFingerprint: String): List<String> {
        val token = buildToken(config, manifestFingerprint, resolveAnnouncedHost(config))
        val payload = runCatching { GatewayEnrollmentCodec.encodeQrPayload(token) }.getOrNull() ?: return emptyList()
        return listOf(
            "Gateway enrollment (share by QR or manual entry to trust this gateway on a device):",
            "  QR payload : $payload",
            "  Manual id  : ${token.gatewayId} / shelter ${token.shelterId} at ${token.scheme}://${token.host}:${token.port}",
            "  Manual fp  : ${GatewayEnrollmentCodec.formatManualFingerprint(token.manifestFingerprint)}",
        )
    }
}
