package com.example.relay.pcgateway

import com.example.relay.gateway.protocol.GatewayMessage
import com.example.relay.gateway.protocol.SyncMessagesRequest
import com.example.relay.gateway.protocol.SyncMessagesResponse
import com.example.relay.gateway.protocol.VERIFIED_GATEWAY_RECEIPT_TYPE
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRoutesTest {
    private fun report(id: String = "route-report") = GatewayMessage(
        messageId = id,
        messageType = "SAFETY",
        recordType = "REPORT",
        priority = "HIGH",
        status = "ACTIVE",
        createdAt = 1_000,
        expiresAt = 86_401_000,
        lifetimeMs = 86_400_000,
        accumulatedAgeMs = 0,
        hopCount = 1,
        hopLimit = 8,
        originDeviceId = "route-origin",
        payload = JsonPrimitive("safe"),
        receivedAt = 1_000,
    )

    private fun statusChange(targetMessageId: String) = report("route-change").copy(
        recordType = "STATUS_CHANGE",
        priority = "CRITICAL",
        payload = buildJsonObject {
            put("eventId", "route-event")
            put("targetMessageId", targetMessageId)
            put("newStatus", "RESOLVED")
            put("reason", "safe")
            put("createdAt", 2_000)
            put("createdBy", "route-origin")
        },
    )

    @Test fun `health route starts and sync endpoint requires bridge token`() = testApplication {
        val db = Files.createTempFile("relay-route", ".db").toString()
        val config = GatewayConfig(profile = GatewayProfile.DEVELOPMENT, dbPath = db, gatewayId = "gateway", adminKey = "admin")
        GatewayStore(config).use { store -> application { gatewayModule(config, store) } }
        val response = client.post("/api/health")
        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
    }

    @Test fun `unauthenticated sync is rejected`() = testApplication {
        val config = GatewayConfig(profile = GatewayProfile.DEVELOPMENT, dbPath = Files.createTempFile("relay-route", ".db").toString(), adminKey = "admin")
        GatewayStore(config).use { store -> application { gatewayModule(config, store) } }
        val response = client.post("/api/sync/messages") {
            contentType(ContentType.Application.Json)
            setBody(GatewayJson.encodeToString(SyncMessagesRequest(bridgeId = "unknown", bridgeName = "x", messages = emptyList())))
        }
        assertTrue(response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden)
    }

    @Test fun `authenticated same batch report and status change are both stored and applied`() = testApplication {
        val config = GatewayConfig(profile = GatewayProfile.DEVELOPMENT, dbPath = Files.createTempFile("relay-route-batch", ".db").toString(), adminKey = "admin")
        val store = GatewayStore(config)
        val code = store.createPairingCode(1_000)
        store.requestPair(code, "bridge", "Bridge", 1_001)
        val token = store.approvePair("bridge", code, 1_002)!!
        application { gatewayModule(config, store) }
        val report = report()
        val change = statusChange(report.messageId)
        try {
            val response = client.post("/api/sync/messages") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $token")
                header("X-Bridge-Id", "bridge")
                setBody(
                    GatewayJson.encodeToString(
                        SyncMessagesRequest(
                            bridgeId = "bridge",
                            bridgeName = "Bridge",
                            messages = listOf(change, report),
                        ),
                    ),
                )
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val decoded = GatewayJson.decodeFromString(SyncMessagesResponse.serializer(), response.bodyAsText())
            assertEquals(setOf(change.messageId, report.messageId), decoded.acceptedMessageIds.toSet())
            assertEquals(setOf(change.messageId, report.messageId), decoded.receipts.map { it.messageId }.toSet())
            assertEquals(setOf(VERIFIED_GATEWAY_RECEIPT_TYPE), decoded.receipts.map { it.receiptType }.toSet())
            assertEquals("RESOLVED", store.messageDetail(report.messageId)?.status)
            assertEquals("authenticated_bridge", response.headers["X-Relay-Route-Authentication"])
            assertEquals("unverified", response.headers["X-Relay-Content-Verification"])
            assertEquals("gateway_saved", response.headers["X-Relay-Receipt-Semantics"])
            assertEquals("AUTHENTICATED_BRIDGE", store.messageDetail(report.messageId)?.routeAuthentication)
            assertEquals("UNVERIFIED", store.messageDetail(report.messageId)?.contentVerification)
        } finally {
            store.close()
        }
    }
}
