package com.example.relay.pcgateway

import com.example.relay.pcgateway.rescue.InMemoryRescuePersistence
import com.example.relay.pcgateway.rescue.RescueClock
import com.example.relay.pcgateway.rescue.RescueIntakeService
import com.example.relay.rescue.RescueCondition
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueLocation
import com.example.relay.rescue.RescuePayload
import com.example.relay.rescue.RescueUrgency
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueOperatorApiTest {
    @Test
    fun `anonymous rescue ingress stays closed until explicitly ready`() = testApplication {
        val config = GatewayConfig(
            profile = GatewayProfile.DEVELOPMENT,
            dbPath = Files.createTempFile("relay-rescue-ingress-gate", ".db").toString(),
            shelterId = "regional-area",
        )
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
            assertEquals(HttpStatusCode.NotFound, client.post("/api/public/rescue/deliver").status)
            assertTrue(client.get("/api/health").bodyAsText().contains("\"rescueIngressReady\":false"))
        }
    }

    @Test
    fun `development generated-key mode explicitly enables anonymous rescue ingress`() = testApplication {
        val config = GatewayConfig(
            profile = GatewayProfile.DEVELOPMENT,
            dbPath = Files.createTempFile("relay-rescue-development-ingress", ".db").toString(),
            shelterId = "regional-area",
        )
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val service = RescueIntakeService(
            shelterId = config.shelterId,
            recipientPrivateKey = recipient.privateKey,
            shelterSigningPrivateKey = signer.privateKey,
            persistence = InMemoryRescuePersistence(),
        )

        GatewayStore(config).use { store ->
            application {
                gatewayModule(
                    config,
                    store,
                    rescueIntakeService = service,
                    rescueDeliveryReady = true,
                )
            }
            assertTrue(client.get("/api/health").bodyAsText().contains("\"rescueIngressReady\":true"))
        }
    }

    @Test
    fun `staff can view exact rescue detail and claim the response`() = testApplication {
        val config = GatewayConfig(
            profile = GatewayProfile.DEVELOPMENT,
            dbPath = Files.createTempFile("relay-rescue-api", ".db").toString(),
            adminKey = "staff-pin",
            shelterId = "regional-area",
        )
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val service = RescueIntakeService(
            shelterId = config.shelterId,
            recipientPrivateKey = recipient.privateKey,
            shelterSigningPrivateKey = signer.privateKey,
            persistence = InMemoryRescuePersistence(),
            clock = RescueClock { 2_000 },
        )
        val payload = RescuePayload(
            requestId = "request-api-1",
            senderDeviceId = "member-1",
            destinationShelterId = config.shelterId,
            createdAtEpochMillis = 1_000,
            expiresAtEpochMillis = 100_000,
            urgency = RescueUrgency.IMMEDIATE,
            personCount = 0,
            conditions = setOf(RescueCondition.LIFE_THREATENING),
            location = RescueLocation(0.0, 0.0, 8f, "2階", 1_500),
            freeText = "動けません",
        )
        service.ingest(RescueCryptography.encrypt(payload, recipient.publicKey, "envelope-api-1"), "courier-1")

        GatewayStore(config).use { store ->
            application { gatewayModule(config, store, rescueIntakeService = service) }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/rescue/requests").status)

            val list = client.get("/api/rescue/requests") { header("X-Admin-Key", "staff-pin") }
            assertEquals(HttpStatusCode.OK, list.status)
            assertTrue(list.bodyAsText().contains("動けません"))
            assertTrue(list.bodyAsText().contains("0.0"))

            val claimed = client.post("/api/rescue/requests/request-api-1/status") {
                header("X-Admin-Key", "staff-pin")
                contentType(ContentType.Application.Json)
                setBody("""{"status":"CONFIRMED","operatorNodeId":"shelter-pc-a"}""")
            }
            assertEquals(HttpStatusCode.OK, claimed.status)
            assertTrue(claimed.bodyAsText().contains("legacy-development-admin"))
            assertTrue(
                store.accessStore().auditRecords(limit = 20).any {
                    it.action == "RESCUE_ASSIGNMENT_START" && it.targetId == "request-api-1"
                },
            )
        }
    }
}
