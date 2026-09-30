package com.example.relay.pcgateway.e2e

import com.example.relay.rescue.ShelterPublicKeyManifest
import com.example.relay.broker.BrokerConfig
import com.example.relay.broker.BrokerProfile
import com.example.relay.broker.BrokerStore
import com.example.relay.broker.brokerModule
import com.example.relay.pcgateway.GatewayConfig
import com.example.relay.pcgateway.GatewayLanMode
import com.example.relay.pcgateway.GatewayProfile
import com.example.relay.pcgateway.GatewayStore
import com.example.relay.pcgateway.gatewayModule
import com.example.relay.pcgateway.GatewayRescueKeyStatus
import com.example.relay.pcgateway.rescue.RescueKeyStore
import com.example.relay.pcgateway.rescue.RescueIntakeService
import com.example.relay.pcgateway.GsiTileCache
import com.example.relay.pcgateway.OfficialInformationService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Black-box E2E test exercising the real Broker and Gateway over HTTP on loopback.
 *
 * This does NOT require external services, ngrok, Docker, or real Android devices.
 * It starts both servers in-process against throwaway temp directories, then:
 * 1. Verifies Broker health
 * 2. Verifies Gateway health
 * 3. Verifies the rescue manifest endpoint responds
 *
 * Security: temp keys are generated and deleted after the test.
 * No credentials, raw tokens, or secrets are printed to console.
 */
class PackagedBrokerGatewayE2eTest {

    private lateinit var brokerServer: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private lateinit var gatewayServer: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private lateinit var tempDir: Path
    private lateinit var client: HttpClient

    private val brokerPort = 19876
    private val gatewayPort = 19877

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("relay-e2e-")
        client = HttpClient(CIO)

        // Start Broker with development profile (allows HTTP loopback)
        val brokerDbPath = tempDir.resolve("broker.db").toString()
        val brokerStore = BrokerStore(brokerDbPath)
        val brokerConfig = BrokerConfig(
            profile = BrokerProfile.DEVELOPMENT,
            host = "127.0.0.1",
            port = brokerPort,
            dbPath = brokerDbPath,
        )

        brokerServer = embeddedServer(Netty, host = "127.0.0.1", port = brokerPort) {
            brokerModule(brokerStore, brokerConfig)
        }
        brokerServer.start(wait = false)

        // Start Gateway with development profile
        val gatewayDbPath = tempDir.resolve("gateway.db").toString()
        val rescueKeyPath = tempDir.resolve("rescue-keys.json").toString()
        val mapDir = tempDir.resolve("maps").toString()
        val officialInfoPath = tempDir.resolve("official.json").toString()
        Files.createDirectories(tempDir.resolve("maps"))

        val config = GatewayConfig(
            profile = GatewayProfile.DEVELOPMENT,
            lanMode = GatewayLanMode.DISABLED,
            host = "127.0.0.1",
            port = gatewayPort,
            dbPath = gatewayDbPath,
            gatewayId = "e2e-gateway",
            shelterId = "e2e-shelter",
            rescueKeyPath = rescueKeyPath,
            offlineMapPath = mapDir,
            officialInfoCachePath = officialInfoPath,
        )

        val store = GatewayStore(config)
        val rescueKeys = RescueKeyStore(Path.of(rescueKeyPath), config.shelterId).loadOrCreate()
        val intake = RescueIntakeService(
            shelterId = config.shelterId,
            recipientPrivateKey = rescueKeys.recipientPrivateKey,
            shelterSigningPrivateKey = rescueKeys.receiptSigningPrivateKey,
            persistence = store.rescuePersistence(),
        )

        gatewayServer = embeddedServer(Netty, host = "127.0.0.1", port = gatewayPort) {
            gatewayModule(
                config,
                store,
                rescueManifest = rescueKeys.manifest,
                rescueBleReady = false,
                rescueDeliveryReady = true,
                rescueIntakeService = intake,
                offlineMap = GsiTileCache(Path.of(mapDir)),
                officialInformation = OfficialInformationService(Path.of(officialInfoPath)),
                rescueKeyStatus = GatewayRescueKeyStatus.valid(
                    expiresAtEpochMillis = rescueKeys.manifest.validUntilEpochMillis,
                    warning = false,
                ),
            )
        }
        gatewayServer.start(wait = false)

        // Give servers time to bind
        Thread.sleep(2000)
    }

    @After
    fun tearDown() {
        runCatching { client.close() }
        runCatching { gatewayServer.stop(500, 1000) }
        runCatching { brokerServer.stop(500, 1000) }
        // Clean temp directory (no secrets remain on disk)
        runCatching {
            tempDir.toFile().walkBottomUp().forEach { it.delete() }
        }
    }

    @Test
    fun testBrokerHealth() = runBlocking {
        val response = client.get("http://127.0.0.1:$brokerPort/v1/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("pendingEnvelopes") || body.contains("profile"))
    }

    @Test
    fun testGatewayHealth() = runBlocking {
        val response = client.get("http://127.0.0.1:$gatewayPort/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("ok") || body.contains("healthy") || body.contains("status"))
    }

    @Test
    fun testGatewayManifestAvailable() = runBlocking {
        val response = client.get("http://127.0.0.1:$gatewayPort/api/public/rescue/manifest")
        assertEquals(HttpStatusCode.OK, response.status)
        val manifest = kotlinx.serialization.json.Json.decodeFromString<ShelterPublicKeyManifest>(
            response.bodyAsText(),
        )
        assertEquals("e2e-shelter", manifest.shelterId)
        assertTrue(manifest.recipientPublicKey.encodedBase64.isNotBlank())
    }
}
