package com.example.relay.pcgateway

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GatewayConfigTrainingModeTest {
    /** Secrets are injected so config tests never create key files under the user profile. */
    private val testSecret = "test-only-" + "0".repeat(32)

    private fun config(
        trainingMode: Boolean,
        dbPath: String? = null,
        rescueKeyPath: String? = null,
        officialInfoCachePath: String? = null,
    ): GatewayConfig {
        val defaults = GatewayConfig(
            profile = GatewayProfile.DEVELOPMENT,
            trainingMode = trainingMode,
            bleBridgeSharedSecret = testSecret,
            adminKey = "admin",
        )
        return defaults.copy(
            dbPath = dbPath ?: defaults.dbPath,
            rescueKeyPath = rescueKeyPath ?: defaults.rescueKeyPath,
            officialInfoCachePath = officialInfoCachePath ?: defaults.officialInfoCachePath,
        )
    }

    @Test fun `training defaults isolate database keys and official cache`() {
        val training = config(trainingMode = true)
        assertTrue(GatewayConfig.hasTrainingPathSegment(training.dbPath))
        assertTrue(GatewayConfig.hasTrainingPathSegment(training.rescueKeyPath))
        assertTrue(GatewayConfig.hasTrainingPathSegment(training.officialInfoCachePath))
        // Offline map tiles are public GSI data shared deliberately; they hold no user data.
        assertFalse(GatewayConfig.hasTrainingPathSegment(training.offlineMapPath))
        assertTrue(training.configurationWarnings.contains("training_mode_active_production_data_isolated"))
        assertTrue(training.auditConfigurationTarget.contains("training=true"))
    }

    @Test fun `production defaults never point at training data`() {
        val production = config(trainingMode = false)
        assertFalse(GatewayConfig.hasTrainingPathSegment(production.dbPath))
        assertFalse(GatewayConfig.hasTrainingPathSegment(production.rescueKeyPath))
        assertFalse(GatewayConfig.hasTrainingPathSegment(production.officialInfoCachePath))
        assertFalse(production.configurationWarnings.contains("training_mode_active_production_data_isolated"))
        assertTrue(production.auditConfigurationTarget.contains("training=false"))
    }

    @Test fun `training mode rejects a production database path override`() {
        assertTrainingGuardRejects("RELAY_GATEWAY_DB") {
            config(trainingMode = true, dbPath = "C:/relay/relay-gateway.db")
        }
    }

    @Test fun `training mode rejects a production rescue key path override`() {
        assertTrainingGuardRejects("RELAY_RESCUE_KEY_FILE") {
            config(trainingMode = true, rescueKeyPath = "/home/example-user/.relay/rescue-keys.json")
        }
    }

    @Test fun `training mode rejects a production official cache path override`() {
        assertTrainingGuardRejects("RELAY_OFFICIAL_INFO_CACHE") {
            config(trainingMode = true, officialInfoCachePath = ".relay/official/jma.json")
        }
    }

    private fun assertTrainingGuardRejects(expectedVariable: String, build: () -> GatewayConfig) {
        try {
            build()
            fail("expected training-mode path guard to reject a non-training path for $expectedVariable")
        } catch (error: IllegalArgumentException) {
            assertTrue(
                "expected guard message naming $expectedVariable, got: ${error.message}",
                error.message!!.contains(expectedVariable) && error.message!!.contains("training"),
            )
        }
    }

    @Test fun `training path segment matches whole directory names only`() {
        assertTrue(GatewayConfig.hasTrainingPathSegment("C:\\Users\\example-user\\.relay\\training\\relay-gateway.db"))
        assertTrue(GatewayConfig.hasTrainingPathSegment("/home/example-user/.relay/training/official/jma.json"))
        assertTrue(GatewayConfig.hasTrainingPathSegment("/home/example-user/.relay/TRAINING/db.sqlite"))
        assertFalse(GatewayConfig.hasTrainingPathSegment("/home/example-user/.relay/relay-gateway.db"))
        // A prefix such as `trainingdata` must not satisfy the isolation guard.
        assertFalse(GatewayConfig.hasTrainingPathSegment("/home/example-user/trainingdata/relay-gateway.db"))
    }

    @Test fun `health reports training mode so the staff console can show the banner`() = testApplication {
        val dbDir = Files.createTempDirectory("relay-training-health").resolve("training")
        Files.createDirectories(dbDir)
        val training = config(trainingMode = true, dbPath = dbDir.resolve("relay-gateway.db").toString())
        GatewayStore(training).use { store ->
            application { gatewayModule(training, store) }
            val response = client.get("/api/health")
            assertEquals(HttpStatusCode.OK, response.status)
            val health = GatewayJson.decodeFromString(HealthResponse.serializer(), response.bodyAsText())
            assertTrue("health must expose trainingMode=true for the console banner", health.trainingMode)
        }
    }

    @Test fun `health omits the training flag for a production style config`() {
        // Serialization default keeps older clients treating an absent field as production.
        assertFalse(HealthResponse(status = "ok", gatewayId = "g", database = "ready").trainingMode)
    }
}
