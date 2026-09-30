package com.example.relay.pcgateway

import com.example.relay.gateway.protocol.GATEWAY_PROTOCOL_VERSION
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.concurrent.thread
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class GatewayLanAnnouncement(
    val service: String = "relay-pc-gateway",
    val discoveryVersion: Int = 1,
    val protocolVersion: Int = GATEWAY_PROTOCOL_VERSION,
    val gatewayId: String,
    val shelterId: String,
    val rescueIngressReady: Boolean,
    val apiPort: Int,
    /** Public endpoint scheme; production reverse-proxy deployments advertise HTTPS only. */
    val apiScheme: String = "http",
    val anonymousIngressPath: String = "/api/public/sync/messages",
    val receiptTrust: String = "UNVERIFIED",
)

/** Sends on the limited broadcast and every usable IPv4 interface broadcast. */
class GatewayLanBeacon(
    private val config: GatewayConfig,
    private val rescueTrustReady: Boolean,
    private val broadcastAddress: String = "255.255.255.255",
) : AutoCloseable {
    @Volatile private var running = false
    private var worker: Thread? = null

    fun announcementBytes(): ByteArray = GatewayJson.encodeToString(
        GatewayLanAnnouncement(
            gatewayId = config.gatewayId,
            shelterId = config.shelterId,
            rescueIngressReady = config.anonymousIngressEnabled && rescueTrustReady,
            apiPort = config.publicPort,
            apiScheme = config.publicScheme,
        ),
    ).encodeToByteArray()

    internal fun broadcastTargets(): Set<InetAddress> {
        val targets = linkedMapOf<String, InetAddress>()
        runCatching { InetAddress.getByName(broadcastAddress) }.getOrNull()?.let { targets[it.hostAddress] = it }
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { network ->
                if (!network.isUp || network.isLoopback || network.isVirtual) return@forEach
                network.interfaceAddresses.forEach { address ->
                    val broadcast = address.broadcast
                    if (address.address is Inet4Address && broadcast != null) targets[broadcast.hostAddress] = broadcast
                }
            }
        }
        return targets.values.toSet()
    }

    fun start() {
        if (running || !config.lanDiscoveryEnabled) return
        running = true
        worker = thread(name = "relay-gateway-lan-beacon", isDaemon = true) {
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    while (running) {
                        val bytes = announcementBytes()
                        broadcastTargets().forEach { target ->
                            socket.send(DatagramPacket(bytes, bytes.size, target, config.lanDiscoveryPort))
                        }
                        try { Thread.sleep(config.lanDiscoveryIntervalMs.coerceAtLeast(1_000)) }
                        catch (_: InterruptedException) { break }
                    }
                }
            }.onFailure { error ->
                if (running) System.err.println("Relay LAN discovery stopped: " + error.javaClass.simpleName)
            }
            running = false
        }
    }

    override fun close() {
        running = false
        worker?.interrupt()
        worker = null
    }
}
