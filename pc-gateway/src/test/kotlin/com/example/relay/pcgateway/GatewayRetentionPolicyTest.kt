package com.example.relay.pcgateway

import com.example.relay.pcgateway.rescue.InMemoryRescuePersistence
import com.example.relay.pcgateway.rescue.RescueClock
import com.example.relay.pcgateway.rescue.RescueIntakeService
import com.example.relay.pcgateway.rescue.RescueResponseStatus
import com.example.relay.rescue.RescueCondition
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueLocation
import com.example.relay.rescue.RescuePayload
import com.example.relay.rescue.RescueUrgency
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRetentionPolicyTest {
    private val testSecret = "retention-test-ble-bridge-secret-0123456789"

    private fun config(
        retentionDays: Int = 30,
        sweepMinutes: Int = 60,
    ): GatewayConfig = GatewayConfig(
        profile = GatewayProfile.DEVELOPMENT,
        dbPath = Files.createTempFile("relay-retention", ".db").toString(),
        rescueRetentionDays = retentionDays,
        retentionSweepIntervalMinutes = sweepMinutes,
        bleBridgeSharedSecret = testSecret,
        adminKey = "staff-pin",
        shelterId = "regional-area",
    )

    @Test
    fun `retention defaults stay at the documented pilot values`() {
        val config = config()
        assertEquals(30, config.rescueRetentionDays)
        assertEquals(30L * 24 * 60 * 60 * 1_000, config.rescueRetentionMillis)
        assertEquals(60, config.retentionSweepIntervalMinutes)
        assertEquals(60L * 60_000, config.retentionSweepIntervalMillis)
    }

    @Test
    fun `retention below one day is rejected fail-closed`() {
        assertRetentionRejected { config(retentionDays = 0) }
    }

    @Test
    fun `retention above one year is rejected fail-closed`() {
        assertRetentionRejected { config(retentionDays = 366) }
    }

    private fun assertRetentionRejected(build: () -> GatewayConfig) {
        val error = runCatching(build).exceptionOrNull()
        assertTrue("expected the retention guard to reject the value", error is IllegalArgumentException)
        assertTrue(
            "message must name the environment variable: ${error!!.message}",
            error.message!!.contains("RELAY_RESCUE_RETENTION_DAYS"),
        )
    }

    @Test
    fun `sweep interval outside five minutes to one day is rejected`() {
        listOf(4, 1_441).forEach { minutes ->
            val error = runCatching { config(sweepMinutes = minutes) }.exceptionOrNull()
            assertTrue("expected rejection for $minutes minutes", error is IllegalArgumentException)
            assertTrue(error!!.message!!.contains("RELAY_RETENTION_SWEEP_INTERVAL_MINUTES"))
        }
    }

    @Test
    fun `configured retention period drives the purge instead of the hardcoded default`() {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        var now = 2_000L
        val service = RescueIntakeService(
            shelterId = "shelter-1",
            recipientPrivateKey = recipient.privateKey,
            shelterSigningPrivateKey = signer.privateKey,
            persistence = InMemoryRescuePersistence(),
            clock = RescueClock { now },
        )
        service.ingest(RescueCryptography.encrypt(payload(), recipient.publicKey, "envelope-1"), "courier-1")
        service.updateStatus("request-retention-1", RescueResponseStatus.CONFIRMED, "operator-a")
        service.updateStatus("request-retention-1", RescueResponseStatus.PREPARING, "operator-a")
        service.updateStatus("request-retention-1", RescueResponseStatus.RESPONDING, "operator-a")
        service.updateStatus("request-retention-1", RescueResponseStatus.COMPLETED, "operator-a")
        now += 2L * 24 * 60 * 60 * 1_000

        // Two days old: the 30-day default keeps it, a 1-day policy removes it.
        assertEquals(0, service.purgeExpiredDetails())
        val oneDayPolicy = config(retentionDays = 1)
        assertEquals(1, service.purgeExpiredDetails(oneDayPolicy.rescueRetentionMillis))
        assertNull(service.detail("request-retention-1"))
    }

    @Test
    fun `operator list reports the configured retention period`() = testApplication {
        val config = config(retentionDays = 7)
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val service = RescueIntakeService(
            shelterId = config.shelterId,
            recipientPrivateKey = recipient.privateKey,
            shelterSigningPrivateKey = signer.privateKey,
            persistence = InMemoryRescuePersistence(),
        )
        GatewayStore(config).use { store ->
            application { gatewayModule(config, store, rescueIntakeService = service) }
            val list = client.get("/api/rescue/requests") { header("X-Admin-Key", "staff-pin") }
            assertEquals(HttpStatusCode.OK, list.status)
            assertTrue(list.bodyAsText().contains("\"retentionDays\":7"))
        }
    }

    private fun payload(): RescuePayload = RescuePayload(
        requestId = "request-retention-1",
        senderDeviceId = "member-1",
        destinationShelterId = "shelter-1",
        createdAtEpochMillis = 1_000,
        expiresAtEpochMillis = 100_000,
        urgency = RescueUrgency.IMMEDIATE,
        personCount = 1,
        conditions = setOf(RescueCondition.LIFE_THREATENING),
        location = RescueLocation(0.0, 0.0, 8f, "1階", 1_500),
        freeText = "retention-test",
    )
}
