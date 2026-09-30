package com.example.relay.pcgateway

import com.example.relay.pcgateway.rescue.RescueClock
import com.example.relay.pcgateway.rescue.RescueKeyStore
import com.example.relay.rescue.ShelterPublicKeyManifest
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RescueManifestRouteTest {
    @Test
    fun publicEndpointReturnsOnlyTheConfiguredManifest() = testApplication {
        val directory = Files.createTempDirectory("relay-manifest-route")
        val config = GatewayConfig(
            dbPath = directory.resolve("gateway.db").toString(),
            rescueKeyPath = directory.resolve("rescue-keys.json").toString(),
            shelterId = "shelter-1",
        )
        val keys = RescueKeyStore(
            directory.resolve("rescue-keys.json"),
            config.shelterId,
            RescueClock { NOW },
        ).loadOrCreate()
        GatewayStore(config).use { store ->
            application { gatewayModule(config, store, rescueManifest = keys.manifest) }
            val response = client.get("/api/public/rescue/manifest")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            val decoded = Json.decodeFromString(ShelterPublicKeyManifest.serializer(), body)
            assertEquals(keys.manifest, decoded)
            assertFalse(body.contains(keys.recipientPrivateKey.encodedBase64))
            assertFalse(body.contains(keys.receiptSigningPrivateKey.encodedBase64))
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
