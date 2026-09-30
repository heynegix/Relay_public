package com.example.relay.pcgateway

import com.example.relay.pcgateway.rescue.InMemoryRescuePersistence
import com.example.relay.pcgateway.rescue.RescueIntakeService
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.ShelterPublicKeyManifest
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PuertaPilotTest {
    @Test fun `production never exposes local pilot`() = testApplication {
        val config = GatewayConfig(profile = GatewayProfile.PRODUCTION, dbPath = Files.createTempFile("relay-puerta-prod", ".db").toString())
        GatewayStore(config).use { store -> application { gatewayModule(config, store) }; assertEquals(HttpStatusCode.NotFound, client.get("/local-pilot").status) }
    }

    @Test fun `development must explicitly enable local pilot`() {
        assertFalse(GatewayConfig(profile = GatewayProfile.DEVELOPMENT).localPilotIngressEnabled)
    }

    @Test fun `local web entry is encrypted ingested and labelled`() = testApplication {
        val config = GatewayConfig(profile = GatewayProfile.DEVELOPMENT, localPilotIngressEnabled = true, dbPath = Files.createTempFile("relay-puerta", ".db").toString(), shelterId = "pilot-shelter")
        val recipient = RescueCryptography.generateRecipientKeyPair(); val signer = RescueCryptography.generateShelterSigningKeyPair()
        val manifest = ShelterPublicKeyManifest(shelterId = config.shelterId, recipientPublicKey = recipient.publicKey, receiptSigningPublicKey = signer.publicKey, validFromEpochMillis = 1, validUntilEpochMillis = System.currentTimeMillis() + 86_400_000)
        val intake = RescueIntakeService(config.shelterId, recipient.privateKey, signer.privateKey, InMemoryRescuePersistence())
        GatewayStore(config).use { store -> application { gatewayModule(config, store, rescueManifest = manifest, rescueDeliveryReady = true, rescueIntakeService = intake) }
            val response = client.post("/local-pilot/api/rescue") { contentType(ContentType.Application.Json); setBody("""{"urgency":"URGENT","personCount":1,"thirdParty":false,"locationDescription":"training-zone"}""") }
            assertEquals(HttpStatusCode.Created, response.status)
            val requestId = Regex("\"requestId\":\"([^\"]+)").find(response.bodyAsText())!!.groupValues[1]
            val metadata = store.puertaStore().ingress(requestId)
            assertEquals(SourceChannel.LOCAL_WEB, metadata.sourceChannel)
            assertEquals(IngressAssurance.SELF_REPORTED, metadata.assurance)
        }
    }

    @Test fun `legacy records are conservatively labelled after puerta migration`() {
        val db = Files.createTempFile("relay-puerta-legacy", ".db").toString()
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE legacy_marker(value TEXT NOT NULL)"); it.execute("INSERT INTO legacy_marker VALUES('kept')") }
        }
        PuertaStore(db).use { store ->
            assertEquals(1, store.schemaVersion())
            assertEquals(PuertaStore.legacyMetadata("v1-request"), store.ingress("v1-request"))
        }
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            connection.createStatement().executeQuery("SELECT value FROM legacy_marker").use { result -> assertTrue(result.next()); assertEquals("kept", result.getString(1)) }
        }
    }
}
