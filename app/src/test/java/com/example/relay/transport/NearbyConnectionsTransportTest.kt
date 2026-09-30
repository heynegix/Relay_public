package com.example.relay.transport

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyConnectionsTransportTest {
    @Test
    fun `stalled automatic acceptance times out without killing event collection`() = runTest {
        val platform = FakeNearbyPlatform(stallAccept = true)
        val transport = NearbyConnectionsTransport("device-Z", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("stalled", "device-A", "1234", true))
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        platform.events.emit(NearbyPlatformEvent.EndpointFound("healthy", "device-B"))
        runCurrent()
        assertTrue(transport.discoveredPeers.value.any { it.peerId == "device-B" })
        assertFalse("device-A" in transport.state.value.connectedPeerIds)
    }

    @Test
    fun `explicit disconnect cancels a pending reconnect`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("device-A", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint", "device-B"))
        platform.events.emit(NearbyPlatformEvent.ConnectionFailed("endpoint", "temporary"))
        runCurrent()
        transport.disconnect("device-B")
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf("endpoint"), platform.requested)
    }

    @Test
    fun `endpoint cannot be rebound to another peer identity`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("device-Z", platform, AllowedNearbyPermissionGate, backgroundScope)
        val received = mutableListOf<ReceivedPayload>()
        backgroundScope.launch { transport.receivedPayloads.collect(received::add) }
        transport.start()
        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint", "device-A", "1234", true))
        platform.events.emit(NearbyPlatformEvent.ConnectionSucceeded("endpoint"))
        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint", "device-B"))
        platform.events.emit(NearbyPlatformEvent.BytesReceived("endpoint", byteArrayOf(1)))
        runCurrent()
        assertEquals(listOf("device-A"), received.map { it.peerId })
        assertEquals(listOf(Peer("device-A")), transport.discoveredPeers.value)
    }

    @Test
    fun `stop during automatic acceptance does not report a retryable failure`() = runTest {
        val platform = FakeNearbyPlatform(stallAccept = true)
        val transport = NearbyConnectionsTransport("device-A", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint", "device-B", "1234", true))
        runCurrent()
        transport.stop()
        runCurrent()
        assertFalse(transport.state.value.started)
        assertEquals(null, transport.state.value.lastError)
        advanceTimeBy(31_000)
        runCurrent()
        assertTrue(platform.requested.isEmpty())
    }

    @Test
    fun `failed automatic acceptance is retried by deterministic initiator`() = runTest {
        val platform = FakeNearbyPlatform(failAccept = true)
        val transport = NearbyConnectionsTransport("device-A", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint-1", "device-B"))
        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint-1", "device-B", "1234", false))
        runCurrent()
        assertEquals(1, platform.requested.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, platform.requested.size)
    }

    @Test
    fun `discovery loss during handshake does not discard the connection`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("device-A", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint-1", "device-B"))
        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint-1", "device-B", "1234", false))
        platform.events.emit(NearbyPlatformEvent.EndpointLost("endpoint-1"))
        platform.events.emit(NearbyPlatformEvent.ConnectionSucceeded("endpoint-1"))
        runCurrent()

        assertTrue("device-B" in transport.state.value.connectedPeerIds)
        assertTrue(platform.disconnected.isEmpty())
    }

    @Test
    fun `restarted peer replaces inactive endpoint and old callbacks cannot disconnect it`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("device-A", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("old", "device-B", "1234", true))
        platform.events.emit(NearbyPlatformEvent.ConnectionSucceeded("old"))
        platform.events.emit(NearbyPlatformEvent.Disconnected("old"))
        platform.events.emit(NearbyPlatformEvent.EndpointFound("new", "device-B"))
        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("new", "device-B", "5678", false))
        platform.events.emit(NearbyPlatformEvent.ConnectionSucceeded("new"))
        platform.events.emit(NearbyPlatformEvent.EndpointLost("old"))
        platform.events.emit(NearbyPlatformEvent.Disconnected("old"))
        runCurrent()

        assertEquals(listOf("new"), platform.requested)
        assertTrue("device-B" in transport.state.value.connectedPeerIds)
        assertEquals(listOf(Peer("device-B")), transport.discoveredPeers.value)
        assertFalse("new" in platform.disconnected)
    }

    @Test
    fun `duplicate identity cannot replace an active connection`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("device-A", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("active", "device-B", "1234", true))
        platform.events.emit(NearbyPlatformEvent.ConnectionSucceeded("active"))
        platform.events.emit(NearbyPlatformEvent.EndpointFound("duplicate", "device-B"))
        runCurrent()

        assertTrue("device-B" in transport.state.value.connectedPeerIds)
        assertEquals(listOf("duplicate"), platform.disconnected)
    }

    @Test
    fun `transfer completion from another endpoint cannot acknowledge our send`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("device-A", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint-1", "device-B", "1234", true))
        platform.events.emit(NearbyPlatformEvent.ConnectionSucceeded("endpoint-1"))
        runCurrent()
        val result = async { transport.send("device-B", byteArrayOf(1)) }
        runCurrent()
        platform.events.emit(NearbyPlatformEvent.PayloadTransferSucceeded("other-endpoint", 100L))
        runCurrent()
        assertFalse(result.isCompleted)
        platform.events.emit(NearbyPlatformEvent.PayloadTransferSucceeded("endpoint-1", 100L))
        runCurrent()
        assertEquals(SendResult.PayloadTransferCompleted, result.await())
    }

    @Test
    fun `cancelling send removes pending transfer without reporting success later`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("device-A", platform, AllowedNearbyPermissionGate, backgroundScope)
        val observed = mutableListOf<TransportEvent>()
        backgroundScope.launch { transport.transportEvents.collect(observed::add) }
        transport.start()
        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint-1", "device-B", "1234", true))
        platform.events.emit(NearbyPlatformEvent.ConnectionSucceeded("endpoint-1"))
        runCurrent()
        val result = async { transport.send("device-B", byteArrayOf(1)) }
        runCurrent()
        result.cancel()
        runCurrent()
        platform.events.emit(NearbyPlatformEvent.PayloadTransferSucceeded("endpoint-1", 100L))
        runCurrent()
        assertTrue(result.isCancelled)
        assertFalse(observed.any { it is TransportEvent.PayloadTransferCompleted })
    }

    @Test
    fun `oversized incoming bytes never reach the protocol consumer`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("device-A", platform, AllowedNearbyPermissionGate, backgroundScope)
        val received = mutableListOf<ReceivedPayload>()
        backgroundScope.launch { transport.receivedPayloads.collect(received::add) }
        transport.start()
        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint-1", "device-B", "1234", true))
        platform.events.emit(NearbyPlatformEvent.ConnectionSucceeded("endpoint-1"))
        platform.events.emit(NearbyPlatformEvent.BytesReceived("endpoint-1", ByteArray(32 * 1024 + 1)))
        runCurrent()
        assertTrue(received.isEmpty())
        assertEquals("payload exceeds Nearby BYTES limit", transport.state.value.lastError)
    }

    @Test
    fun `platform discovery maps endpoint name to domain peer and loss removes it`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("local", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()

        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint-1", "device-B"))
        runCurrent()
        assertEquals(listOf(Peer("device-B")), transport.discoveredPeers.value)

        platform.events.emit(NearbyPlatformEvent.EndpointLost("endpoint-1"))
        runCurrent()
        assertTrue(transport.discoveredPeers.value.isEmpty())
    }

    @Test
    fun `connection is accepted automatically and does not require user approval`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("local", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint-1", "device-B", "1234", true))
        runCurrent()

        assertEquals(listOf("endpoint-1"), platform.accepted)
        assertTrue(transport.state.value.pendingVerifications.isEmpty())

        transport.disconnect("device-B")
        assertFalse("device-B" in transport.state.value.pendingVerifications)
        assertFalse("device-B" in transport.state.value.connectedPeerIds)
    }

    @Test
    fun `outgoing connection request is not duplicated when initiation callback arrives`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("device-A", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()

        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint-1", "device-B"))
        runCurrent()
        assertEquals(listOf("endpoint-1"), platform.requested)

        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint-1", "device-B", "1234", false))
        runCurrent()

        assertEquals(listOf("endpoint-1"), platform.requested)
        assertEquals(listOf("endpoint-1"), platform.accepted)
    }

    @Test
    fun `failed deterministic initiator retries with exponential backoff`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("device-A", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint-1", "device-B"))
        runCurrent()
        assertEquals(1, platform.requested.size)

        platform.events.emit(NearbyPlatformEvent.ConnectionFailed("endpoint-1", "transient"))
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, platform.requested.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, platform.requested.size)

        platform.events.emit(NearbyPlatformEvent.ConnectionFailed("endpoint-1", "transient"))
        runCurrent()
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(2, platform.requested.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, platform.requested.size)
    }

    @Test
    fun `missing connection result times out and retries instead of remaining connecting forever`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport(
            "device-A",
            platform,
            AllowedNearbyPermissionGate,
            backgroundScope,
            reconnectBaseDelayMs = 1_000,
            reconnectMaxDelayMs = 1_000,
        )
        val connectionEvents = mutableListOf<ConnectionEvent>()
        backgroundScope.launch { transport.connectionEvents.collect(connectionEvents::add) }
        transport.start()

        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint-1", "device-B"))
        runCurrent()
        assertEquals(1, platform.requested.size)

        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(1, platform.requested.size)
        assertFalse(connectionEvents.any { it is ConnectionEvent.Failed })

        advanceTimeBy(1)
        runCurrent()
        assertTrue(
            connectionEvents.any {
                it is ConnectionEvent.Failed &&
                    it.peerId == "device-B" &&
                    it.reason == "connection attempt timed out"
            },
        )

        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, platform.requested.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, platform.requested.size)
    }

    @Test
    fun `successful connection cancels the connection attempt timeout`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport(
            "device-A",
            platform,
            AllowedNearbyPermissionGate,
            backgroundScope,
            reconnectBaseDelayMs = 1_000,
            reconnectMaxDelayMs = 1_000,
        )
        val connectionEvents = mutableListOf<ConnectionEvent>()
        backgroundScope.launch { transport.connectionEvents.collect(connectionEvents::add) }
        transport.start()

        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint-1", "device-B"))
        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint-1", "device-B", "1234", false))
        platform.events.emit(NearbyPlatformEvent.ConnectionSucceeded("endpoint-1"))
        runCurrent()

        advanceTimeBy(31_000)
        runCurrent()

        assertEquals(1, platform.requested.size)
        assertFalse(connectionEvents.any { it is ConnectionEvent.Failed })
        assertTrue("device-B" in transport.state.value.connectedPeerIds)
    }

    @Test
    fun `incoming connection also times out when no result callback arrives`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("device-B", platform, AllowedNearbyPermissionGate, backgroundScope)
        val connectionEvents = mutableListOf<ConnectionEvent>()
        backgroundScope.launch { transport.connectionEvents.collect(connectionEvents::add) }
        transport.start()

        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint-1", "device-A", "1234", true))
        runCurrent()
        assertEquals(listOf("endpoint-1"), platform.accepted)

        advanceTimeBy(30_000)
        runCurrent()

        assertTrue(
            connectionEvents.any {
                it is ConnectionEvent.Failed &&
                    it.peerId == "device-A" &&
                    it.reason == "connection attempt timed out"
            },
        )
        assertTrue(platform.requested.isEmpty())
    }

    @Test
    fun `late success after connection timeout cannot resurrect the peer`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("device-A", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint-1", "device-B"))
        runCurrent()

        advanceTimeBy(30_000)
        runCurrent()
        platform.events.emit(NearbyPlatformEvent.ConnectionSucceeded("endpoint-1"))
        runCurrent()

        assertFalse("device-B" in transport.state.value.connectedPeerIds)
        assertTrue("endpoint-1" in platform.disconnected)
    }

    @Test
    fun `peer loss cancels a scheduled reconnect`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("device-A", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint-1", "device-B"))
        platform.events.emit(NearbyPlatformEvent.ConnectionFailed("endpoint-1", "transient"))
        runCurrent()

        platform.events.emit(NearbyPlatformEvent.EndpointLost("endpoint-1"))
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(listOf("endpoint-1"), platform.requested)
    }

    @Test
    fun `transport stop cancels a scheduled reconnect`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("device-A", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint-1", "device-B"))
        platform.events.emit(NearbyPlatformEvent.ConnectionFailed("endpoint-1", "transient"))
        runCurrent()

        transport.stop()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(listOf("endpoint-1"), platform.requested)
    }

    @Test
    fun `non initiator accepts incoming connection but never schedules reconnect`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("device-B", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint-1", "device-A", "1234", true))
        platform.events.emit(NearbyPlatformEvent.ConnectionSucceeded("endpoint-1"))
        platform.events.emit(NearbyPlatformEvent.Disconnected("endpoint-1"))
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertTrue(platform.requested.isEmpty())
        assertEquals(listOf("endpoint-1"), platform.accepted)
    }

    @Test
    fun `disconnect updates connected peer state`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("local", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint-1", "device-B", "1234", true))
        runCurrent()
        transport.acceptConnection("device-B")
        platform.events.emit(NearbyPlatformEvent.ConnectionSucceeded("endpoint-1"))
        runCurrent()
        assertTrue("device-B" in transport.state.value.connectedPeerIds)

        platform.events.emit(NearbyPlatformEvent.Disconnected("endpoint-1"))
        runCurrent()
        assertFalse("device-B" in transport.state.value.connectedPeerIds)
    }

    @Test
    fun `send requires approved connected peer and waits for transfer success`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("local", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        assertTrue(transport.send("device-B", byteArrayOf(1)) is SendResult.Failed)
        assertTrue(platform.sent.isEmpty())

        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint-1", "device-B", "1234", true))
        runCurrent()
        transport.acceptConnection("device-B")
        platform.events.emit(NearbyPlatformEvent.ConnectionSucceeded("endpoint-1"))
        runCurrent()

        val result = async { transport.send("device-B", byteArrayOf(1, 2)) }
        runCurrent()
        assertEquals(1, platform.sent.size)
        platform.events.emit(NearbyPlatformEvent.PayloadTransferSucceeded("endpoint-1", 100L))
        runCurrent()
        assertEquals(SendResult.PayloadTransferCompleted, result.await())
    }

    @Test
    fun `transfer success delivered before send returns is not lost`() = runTest {
        val platform = FakeNearbyPlatform(completeDuringSend = true)
        val transport = NearbyConnectionsTransport("local", platform, AllowedNearbyPermissionGate, backgroundScope)
        transport.start()
        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint-1", "device-B", "1234", true))
        runCurrent()
        transport.acceptConnection("device-B")
        platform.events.emit(NearbyPlatformEvent.ConnectionSucceeded("endpoint-1"))
        runCurrent()

        val result = async { transport.send("device-B", byteArrayOf(1, 2)) }
        runCurrent()

        assertEquals(SendResult.PayloadTransferCompleted, result.await())
    }

    @Test
    fun `incoming transfer update is not reported as outgoing completion`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("local", platform, AllowedNearbyPermissionGate, backgroundScope)
        val observed = mutableListOf<TransportEvent>()
        backgroundScope.launch { transport.transportEvents.collect(observed::add) }
        transport.start()
        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint-1", "device-B"))
        platform.events.emit(NearbyPlatformEvent.PayloadTransferSucceeded("endpoint-1", 999L))
        runCurrent()

        assertFalse(observed.any { it is TransportEvent.PayloadTransferCompleted })
    }

    @Test
    fun `bytes from an endpoint are ignored until its connection succeeds`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("local", platform, AllowedNearbyPermissionGate, backgroundScope)
        val received = mutableListOf<ReceivedPayload>()
        backgroundScope.launch { transport.receivedPayloads.collect(received::add) }
        transport.start()
        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint-1", "device-B"))
        platform.events.emit(NearbyPlatformEvent.BytesReceived("endpoint-1", byteArrayOf(1, 2, 3)))
        runCurrent()

        assertTrue(received.isEmpty())
    }

    @Test
    fun `send setup failure removes its pending completion`() = runTest {
        val platform = FakeNearbyPlatform(failSendAfterPayloadCreated = true)
        val transport = NearbyConnectionsTransport("local", platform, AllowedNearbyPermissionGate, backgroundScope)
        val observed = mutableListOf<TransportEvent>()
        backgroundScope.launch { transport.transportEvents.collect(observed::add) }
        transport.start()
        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint-1", "device-B", "1234", true))
        platform.events.emit(NearbyPlatformEvent.ConnectionSucceeded("endpoint-1"))
        runCurrent()

        assertTrue(transport.send("device-B", byteArrayOf(1)) is SendResult.Failed)
        platform.events.emit(NearbyPlatformEvent.PayloadTransferSucceeded("endpoint-1", 100L))
        runCurrent()

        assertFalse(observed.any { it is TransportEvent.PayloadTransferCompleted })
    }

    @Test
    fun `double start is idempotent and missing permission never invokes platform`() = runTest {
        val platform = FakeNearbyPlatform()
        val transport = NearbyConnectionsTransport("local", platform, DeniedNearbyPermissionGate, backgroundScope)
        transport.start()
        transport.start()
        assertEquals(0, platform.advertisingStarts)
        assertEquals(0, platform.discoveryStarts)

        val allowed = NearbyConnectionsTransport("local", platform, AllowedNearbyPermissionGate, backgroundScope)
        allowed.start()
        allowed.start()
        assertEquals(1, platform.advertisingStarts)
        assertEquals(1, platform.discoveryStarts)
    }

    @Test
    fun `trusted mode rejects an untrusted inbound peer and never retries`() = runTest {
        val platform = FakeNearbyPlatform()
        val policy = NearbyConnectionPolicy(NearbyConnectionMode.TRUSTED)
        val transport = NearbyConnectionsTransport(
            "device-B", platform, AllowedNearbyPermissionGate, backgroundScope, connectionPolicy = policy,
        )
        val connectionEvents = mutableListOf<ConnectionEvent>()
        backgroundScope.launch { transport.connectionEvents.collect(connectionEvents::add) }
        transport.start()

        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint-1", "device-A", "1234", true))
        runCurrent()

        assertEquals(listOf("endpoint-1"), platform.rejected)
        assertTrue(platform.accepted.isEmpty())
        assertTrue(
            connectionEvents.any {
                it is ConnectionEvent.Failed && it.peerId == "device-A" && it.reason == "peer is not trusted"
            },
        )

        // A policy refusal is intentional, not transient: it must never be retried.
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(platform.requested.isEmpty())
    }

    @Test
    fun `trusted mode accepts an inbound peer on the allow-list`() = runTest {
        val platform = FakeNearbyPlatform()
        val policy = NearbyConnectionPolicy(NearbyConnectionMode.TRUSTED, trustedPeers = setOf("device-A"))
        val transport = NearbyConnectionsTransport(
            "device-B", platform, AllowedNearbyPermissionGate, backgroundScope, connectionPolicy = policy,
        )
        transport.start()

        platform.events.emit(NearbyPlatformEvent.ConnectionInitiated("endpoint-1", "device-A", "1234", true))
        runCurrent()

        assertEquals(listOf("endpoint-1"), platform.accepted)
        assertTrue(platform.rejected.isEmpty())
    }

    @Test
    fun `trusted mode does not initiate outbound to an untrusted discovered peer`() = runTest {
        val platform = FakeNearbyPlatform()
        val policy = NearbyConnectionPolicy(NearbyConnectionMode.TRUSTED)
        // device-A < device-B, so device-A is the deterministic initiator.
        val transport = NearbyConnectionsTransport(
            "device-A", platform, AllowedNearbyPermissionGate, backgroundScope, connectionPolicy = policy,
        )
        transport.start()

        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint-1", "device-B"))
        runCurrent()

        assertTrue(platform.requested.isEmpty())
    }

    @Test
    fun `trusted mode initiates outbound to a trusted discovered peer`() = runTest {
        val platform = FakeNearbyPlatform()
        val policy = NearbyConnectionPolicy(NearbyConnectionMode.TRUSTED, trustedPeers = setOf("device-B"))
        val transport = NearbyConnectionsTransport(
            "device-A", platform, AllowedNearbyPermissionGate, backgroundScope, connectionPolicy = policy,
        )
        transport.start()

        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint-1", "device-B"))
        runCurrent()

        assertEquals(listOf("endpoint-1"), platform.requested)
    }

    @Test
    fun `trusted mode refuses an explicit connect to an untrusted peer`() = runTest {
        val platform = FakeNearbyPlatform()
        val policy = NearbyConnectionPolicy(NearbyConnectionMode.TRUSTED)
        // device-Z > device-A, so device-Z is not the deterministic initiator: any outbound
        // request could only come from the explicit connect() below, never from auto-initiate.
        val transport = NearbyConnectionsTransport(
            "device-Z", platform, AllowedNearbyPermissionGate, backgroundScope, connectionPolicy = policy,
        )
        val connectionEvents = mutableListOf<ConnectionEvent>()
        backgroundScope.launch { transport.connectionEvents.collect(connectionEvents::add) }
        transport.start()
        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint-1", "device-A"))
        runCurrent()

        transport.connect("device-A")
        runCurrent()

        assertTrue("an untrusted explicit connect must never reach the platform", platform.requested.isEmpty())
        assertTrue(
            connectionEvents.any {
                it is ConnectionEvent.Failed && it.peerId == "device-A" && it.reason == "peer is not trusted"
            },
        )

        // A policy refusal is intentional, not transient: it must never be retried.
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(platform.requested.isEmpty())
    }

    @Test
    fun `trusted mode allows an explicit connect to a trusted peer`() = runTest {
        val platform = FakeNearbyPlatform()
        val policy = NearbyConnectionPolicy(NearbyConnectionMode.TRUSTED, trustedPeers = setOf("device-A"))
        // device-Z > device-A, so auto-initiate never fires; the request proves the explicit path.
        val transport = NearbyConnectionsTransport(
            "device-Z", platform, AllowedNearbyPermissionGate, backgroundScope, connectionPolicy = policy,
        )
        transport.start()
        platform.events.emit(NearbyPlatformEvent.EndpointFound("endpoint-1", "device-A"))
        runCurrent()
        assertTrue("auto-initiate must not fire when device-Z is not the initiator", platform.requested.isEmpty())

        transport.connect("device-A")
        runCurrent()

        assertEquals(listOf("endpoint-1"), platform.requested)
    }
}

private class FakeNearbyPlatform(
    private val completeDuringSend: Boolean = false,
    private val failSendAfterPayloadCreated: Boolean = false,
    private val failAccept: Boolean = false,
    private val stallAccept: Boolean = false,
) : NearbyPlatform {
    override val events = MutableSharedFlow<NearbyPlatformEvent>(extraBufferCapacity = 32)
    var advertisingStarts = 0
    var discoveryStarts = 0
    val accepted = mutableListOf<String>()
    val rejected = mutableListOf<String>()
    val requested = mutableListOf<String>()
    val disconnected = mutableListOf<String>()
    val sent = mutableListOf<Pair<String, ByteArray>>()

    override suspend fun startAdvertising(localEndpointName: String) { advertisingStarts++ }
    override suspend fun startDiscovery() { discoveryStarts++ }
    override suspend fun requestConnection(localEndpointName: String, endpointId: String) { requested += endpointId }
    override suspend fun acceptConnection(endpointId: String) {
        if (stallAccept) kotlinx.coroutines.awaitCancellation()
        if (failAccept) error("temporary accept failure")
        accepted += endpointId
    }
    override suspend fun rejectConnection(endpointId: String) { rejected += endpointId }
    override fun disconnect(endpointId: String) { disconnected += endpointId }
    override suspend fun sendBytes(endpointId: String, bytes: ByteArray, onPayloadCreated: (Long) -> Unit): Long {
        sent += endpointId to bytes
        onPayloadCreated(100L)
        if (failSendAfterPayloadCreated) error("send setup failed")
        if (completeDuringSend) events.emit(NearbyPlatformEvent.PayloadTransferSucceeded(endpointId, 100L))
        return 100L
    }
    override fun stopAll() = Unit
}
