package com.example.relay.broker

import com.example.relay.rescue.RescueKeyAlgorithm
import com.example.relay.rescue.RescuePublicKey
import com.example.relay.rescue.ShelterPublicKeyManifest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BrokerManifestRouteTest {
    private lateinit var store: BrokerStore
    private lateinit var dbFile: File

    @Before
    fun setup() {
        dbFile = File.createTempFile("broker-manifest-test", ".db")
        dbFile.deleteOnExit()
        store = BrokerStore(dbFile.absolutePath)
    }

    @After
    fun teardown() {
        store.close()
        dbFile.delete()
    }

    private fun manifest(shelterId: String = "development-pc-gateway"): ShelterPublicKeyManifest {
        val now = System.currentTimeMillis()
        return ShelterPublicKeyManifest(
            shelterId = shelterId,
            recipientPublicKey = RescuePublicKey("recipient-key-id", RescueKeyAlgorithm.RSA_OAEP_SHA256, "A".repeat(600)),
            receiptSigningPublicKey = RescuePublicKey("signing-key-id", RescueKeyAlgorithm.ECDSA_P256_SHA256, "B".repeat(120)),
            validFromEpochMillis = now - 60_000,
            validUntilEpochMillis = now + 3_600_000,
            generation = 1,
        )
    }

    private fun manifestBody(manifest: ShelterPublicKeyManifest): String =
        brokerJson.encodeToString(ShelterPublicKeyManifest.serializer(), manifest)

    @Test
    fun `gateway publishes manifest then phone fetches it`() = testApplication {
        application { brokerModule(store, BrokerConfig(profile = BrokerProfile.PRODUCTION)) }
        val credential = store.issueGatewayCredential("dev-gw", "development-pc-gateway", System.currentTimeMillis() + 60_000)
        val expected = manifest()
        val published = client.post("/v1/gateways/development-pc-gateway/manifest") {
            header("Authorization", "Bearer ${credential.token}")
            header("X-Gateway-Id", "dev-gw")
            contentType(ContentType.Application.Json)
            setBody(manifestBody(expected))
        }
        assertEquals(HttpStatusCode.Accepted, published.status)

        val fetched = client.get("/v1/shelters/development-pc-gateway/manifest")
        assertEquals(HttpStatusCode.OK, fetched.status)
        val decoded = brokerJson.decodeFromString(ShelterPublicKeyManifest.serializer(), fetched.bodyAsText())
        assertEquals(expected, decoded)
    }

    @Test
    fun `fetch returns 404 when nothing published`() = testApplication {
        application { brokerModule(store, BrokerConfig(profile = BrokerProfile.PRODUCTION)) }
        val response = client.get("/v1/shelters/development-pc-gateway/manifest")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `publish requires a scoped credential`() = testApplication {
        application { brokerModule(store, BrokerConfig(profile = BrokerProfile.PRODUCTION)) }
        val response = client.post("/v1/gateways/development-pc-gateway/manifest") {
            contentType(ContentType.Application.Json)
            setBody(manifestBody(manifest()))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `publish rejects a shelter id that does not match the manifest`() = testApplication {
        application { brokerModule(store, BrokerConfig(profile = BrokerProfile.PRODUCTION)) }
        val credential = store.issueGatewayCredential("dev-gw", "development-pc-gateway", System.currentTimeMillis() + 60_000)
        val response = client.post("/v1/gateways/development-pc-gateway/manifest") {
            header("Authorization", "Bearer ${credential.token}")
            header("X-Gateway-Id", "dev-gw")
            contentType(ContentType.Application.Json)
            setBody(manifestBody(manifest(shelterId = "some-other-shelter")))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("shelter_mismatch"))
    }
}
