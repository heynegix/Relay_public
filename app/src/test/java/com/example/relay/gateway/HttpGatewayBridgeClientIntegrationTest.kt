package com.example.relay.gateway

import com.example.relay.NOW
import com.example.relay.domain.InMemoryMessageRepository
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MutableClock
import com.example.relay.domain.ReceiptType
import com.example.relay.message
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives the real [HttpGatewayBridgeClient] against a real TCP HTTP server.
 * Avoids jdk.httpserver (not on Android unit-test classpath).
 */
class HttpGatewayBridgeClientIntegrationTest {
    private val running = AtomicBoolean(true)
    private val lastPath = AtomicReference("")
    private val lastBody = AtomicReference("")
    private val lastAuth = AtomicReference<String?>(null)
    private lateinit var serverSocket: ServerSocket
    private var port: Int = 0
    private val executor = Executors.newCachedThreadPool()

    @Before
    fun startServer() {
        serverSocket = ServerSocket(0)
        port = serverSocket.localPort
        executor.execute {
            while (running.get()) {
                try {
                    val socket = serverSocket.accept()
                    executor.execute { handle(socket) }
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }
    }

    @After
    fun stopServer() {
        running.set(false)
        runCatching { serverSocket.close() }
        executor.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(" ").getOrNull(1) ?: ""
            lastPath.set(path)
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).lowercase()] = line.substring(idx + 1).trim()
            }
            lastAuth.set(headers["authorization"])
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyChars = CharArray(length)
            var read = 0
            while (read < length) {
                val n = reader.read(bodyChars, read, length - read)
                if (n < 0) break
                read += n
            }
            val body = String(bodyChars, 0, read)
            lastBody.set(body)
            val messageId = Regex(""""messageId"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: "unknown"
            val receiptType = if (path.contains("/public/")) {
                "GATEWAY_RECEIVED_UNVERIFIED"
            } else {
                "GATEWAY_RECEIVED"
            }
            val responseBody = """
                {"protocolVersion":1,"acceptedMessageIds":["$messageId"],"duplicateMessageIds":[],"rejected":[],"receipts":[{"receiptId":"r-$messageId","messageId":"$messageId","receiptType":"$receiptType","actorId":"pc-gateway-local","recordedAt":$NOW,"integrity":null}]}
            """.trimIndent()
            val writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
            writer.write("HTTP/1.1 200 OK\r\n")
            writer.write("Content-Type: application/json\r\n")
            writer.write("X-Relay-Receipt-Trust: unverified\r\n")
            writer.write("X-Relay-Content-Verification: unverified\r\n")
            writer.write("Content-Length: ${responseBody.toByteArray(Charsets.UTF_8).size}\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.write(responseBody)
            writer.flush()
        }
    }

    @Test
    fun `public push uses real HTTP client and returns unverified gateway receipt type`() = runBlocking {
        val client = HttpGatewayBridgeClient()
        val result = client.pushPublic(
            DiscoveredGateway("127.0.0.1", port, "pc-gateway-local"),
            bridgeId = "bridge-http-test",
            bridgeName = "Relay Bridge",
            messages = listOf(message(id = "http-public-1")),
        )
        assertTrue(lastPath.get().contains("/api/public/sync/messages"))
        assertTrue(lastBody.get().contains("http-public-1"))
        assertEquals(1, result.response.acceptedMessageIds.size)
        assertEquals("GATEWAY_RECEIVED_UNVERIFIED", result.response.receipts.single().receiptType)
    }

    @Test
    fun `authenticated push sends bearer and maps GATEWAY_RECEIVED receipt type`() = runBlocking {
        val client = HttpGatewayBridgeClient()
        val settings = GatewaySettings(
            host = "127.0.0.1",
            port = port,
            scheme = "http",
            gatewayName = "pc",
            bridgeId = "bridge-auth",
            enabled = true,
        )
        val result = client.push(settings, token = "secret-token", messages = listOf(message(id = "http-auth-1")))
        assertTrue(lastPath.get().contains("/api/sync/messages"))
        assertEquals("Bearer secret-token", lastAuth.get())
        assertEquals("GATEWAY_RECEIVED", result.response.receipts.single().receiptType)
    }

    @Test
    fun `engine over real public HTTP client persists UNVERIFIED receipt only`() = runBlocking {
        val repository = InMemoryMessageRepository().also { it.insert(message(id = "engine-http-1")) }
        val settings = object : GatewaySettingsStoreContract {
            private var value = GatewaySettings()
            override fun load() = value
            override fun save(settings: GatewaySettings) { value = settings }
            override fun record(result: String, connectedAt: Long) {
                value = value.copy(lastSyncResult = result, lastConnectedAt = connectedAt)
            }
        }
        val pending = object : GatewayDeliveryLedger {
            private val completed = linkedSetOf<String>()
            private val terminal = linkedSetOf<String>()
            override fun pendingIds(existingMessageIds: Set<String>): Set<String> {
                completed.retainAll(existingMessageIds)
                terminal.retainAll(existingMessageIds)
                return existingMessageIds - completed - terminal
            }
            override fun markCompleted(messageIds: Set<String>) { completed += messageIds }
            override fun markTerminal(messageIds: Set<String>) { terminal += messageIds }
        }
        val engine = GatewaySyncEngine(
            repository = repository,
            settingsStore = settings,
            credentialStore = object : GatewayCredentialStoreContract {
                override fun save(token: String) = Unit
                override fun load() = null
                override fun clear() = Unit
                override fun hasToken() = false
            },
            client = HttpGatewayBridgeClient(),
            policy = MessagePolicy(MutableClock(NOW)),
            scope = CoroutineScope(Dispatchers.Unconfined),
            deliveryLedger = pending,
            discovery = object : GatewayDiscovery {
                override suspend fun discover(timeoutMs: Int) =
                    DiscoveredGateway("127.0.0.1", port, "pc-gateway-local")
            },
            localBridgeId = "bridge-engine-http",
        )
        val result = engine.syncOnce()
        assertTrue(result is GatewaySyncResult.Completed)
        val receipt = repository.allReceipts().single()
        assertEquals(ReceiptType.GATEWAY_RECEIVED_UNVERIFIED, receipt.receiptType)
        assertEquals("engine-http-1", receipt.messageId)
        assertTrue(repository.allReceipts().none { it.receiptType == ReceiptType.GATEWAY_RECEIVED })
    }
}
