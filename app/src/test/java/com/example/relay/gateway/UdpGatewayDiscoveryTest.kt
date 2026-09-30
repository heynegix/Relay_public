package com.example.relay.gateway

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.async
import kotlin.concurrent.thread
import org.junit.Assert.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UdpGatewayDiscoveryTest {
    @Test
    fun `receives announcement on bound discovery port`() = runBlocking {
        val port = freeUdpPort()
        val discovery = UdpGatewayDiscovery(port = port)
        val waiter = async { discovery.discover(timeoutMs = 3_000) }
        delay(150)
        val payload = """
            {"service":"relay-pc-gateway","discoveryVersion":1,"protocolVersion":1,
             "gatewayId":"gw-test","apiPort":8080,
             "anonymousIngressPath":"/api/public/sync/messages","receiptTrust":"UNVERIFIED"}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        DatagramSocket().use { sender ->
            sender.broadcast = true
            val packet = DatagramPacket(payload, payload.size, InetAddress.getByName("127.0.0.1"), port)
            // The receiver binds on a background dispatcher. Repeat the beacon as a real gateway
            // does instead of assuming a fixed delay is enough for the bind to complete on every OS.
            repeat(10) {
                sender.send(packet)
                delay(100)
            }
        }
        val found = waiter.await()
        assertNotNull(found)
        assertEquals(8080, found!!.port)
        assertEquals("gw-test", found.gatewayId)
    }

    @Test
    fun `ignores malformed or wrong-service packets and times out cleanly`() = runBlocking {
        val port = freeUdpPort()
        val discovery = UdpGatewayDiscovery(port = port)
        val waiter = async { discovery.discover(timeoutMs = 1_200) }
        delay(100)
        DatagramSocket().use { sender ->
            val bad = """{"service":"other","discoveryVersion":1,"gatewayId":"x","apiPort":1}"""
                .toByteArray(Charsets.UTF_8)
            sender.send(DatagramPacket(bad, bad.size, InetAddress.getByName("127.0.0.1"), port))
        }
        assertNull(waiter.await())
    }

    @Test
    fun `shelter discovery skips a valid beacon for a different shelter`() = runBlocking {
        val port = freeUdpPort()
        val discovery = UdpGatewayDiscovery(port = port)
        val waiter = async { discovery.discoverForShelter("shelter-target", timeoutMs = 3_000) }
        delay(150)
        DatagramSocket().use { sender ->
            val wrong = """{"service":"relay-pc-gateway","discoveryVersion":1,"protocolVersion":1,"gatewayId":"gw-wrong","shelterId":"shelter-wrong","rescueIngressReady":true,"apiPort":8081,"anonymousIngressPath":"/api/public/sync/messages"}"""
                .toByteArray()
            sender.send(DatagramPacket(wrong, wrong.size, InetAddress.getByName("127.0.0.1"), port))
            delay(100)
            val target = """{"service":"relay-pc-gateway","discoveryVersion":1,"protocolVersion":1,"gatewayId":"gw-target","shelterId":"shelter-target","rescueIngressReady":true,"apiPort":8082,"anonymousIngressPath":"/api/public/sync/messages"}"""
                .toByteArray()
            repeat(5) {
                sender.send(DatagramPacket(target, target.size, InetAddress.getByName("127.0.0.1"), port))
                delay(100)
            }
        }
        val found = waiter.await()
        assertNotNull(found)
        assertEquals("gw-target", found!!.gatewayId)
        assertEquals("shelter-target", found.shelterId)
        assertEquals(8082, found.port)
    }

    @Test
    fun `multicast lock is closed after successful discovery`() = runBlocking {
        val port = freeUdpPort()
        val lock = RecordingLock()
        val diagnostics = mutableListOf<GatewayDiscoveryDiagnostic>()
        val sender = thread(start = true) {
            Thread.sleep(120)
            val payload = """{"service":"relay-pc-gateway","discoveryVersion":1,"protocolVersion":1,"gatewayId":"gw-lock","apiPort":8080,"anonymousIngressPath":"/api/public/sync/messages","receiptTrust":"UNVERIFIED"}""".toByteArray()
            DatagramSocket().use { socket ->
                socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName("127.0.0.1"), port))
            }
        }
        val found = UdpGatewayDiscovery(
            port = port,
            multicastLockFactory = { lock },
            onDiagnostic = diagnostics::add,
        ).discover(2_000)
        sender.join()
        assertNotNull(found)
        assertTrue(lock.closed)
        assertEquals("beacon_received", diagnostics.single().result)
    }

    @Test
    fun `multicast lock is closed after timeout`() = runBlocking {
        val lock = RecordingLock()
        UdpGatewayDiscovery(
            port = freeUdpPort(),
            multicastLockFactory = { lock },
        ).discover(1_000)
        assertTrue(lock.closed)
    }

    private class RecordingLock : AutoCloseable {
        var closed = false
        override fun close() { closed = true }
    }

    private fun freeUdpPort(): Int {
        return DatagramSocket(0).use { it.localPort }
    }
}
