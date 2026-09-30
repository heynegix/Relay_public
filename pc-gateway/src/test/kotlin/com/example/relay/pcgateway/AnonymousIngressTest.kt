package com.example.relay.pcgateway

import com.example.relay.gateway.protocol.GatewayMessage
import com.example.relay.gateway.protocol.SyncMessagesRequest
import com.example.relay.gateway.protocol.SyncMessagesResponse
import com.example.relay.gateway.protocol.UNVERIFIED_GATEWAY_RECEIPT_TYPE
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnonymousIngressTest {
    private fun message(id: String = "anonymous-1") = GatewayMessage(
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
        originDeviceId = "anonymous-device",
        payload = JsonPrimitive("safe"),
        receivedAt = 1_000,
    )

    private fun statusChange(id: String, targetMessageId: String) = message(id).copy(
        recordType = "STATUS_CHANGE",
        priority = "CRITICAL",
        payload = buildJsonObject {
            put("eventId", "event-$id")
            put("targetMessageId", targetMessageId)
            put("newStatus", "RETRACTED")
            put("reason", "unverified source")
            put("createdAt", 2_000)
            put("createdBy", "anonymous-device")
        },
    )

    @Test fun `public ingress stores without token but returns only unverified receipt and admin remains protected`() = testApplication {
        val config = GatewayConfig(
            profile = GatewayProfile.DEVELOPMENT,
            dbPath = Files.createTempFile("relay-anonymous", ".db").toString(),
            gatewayId = "gateway-test",
            adminKey = "admin-secret",
        )
        val store = GatewayStore(config)
        application { gatewayModule(config, store) }
        try {
            val response = client.post("/api/public/sync/messages") {
                contentType(ContentType.Application.Json)
                setBody(GatewayJson.encodeToString(SyncMessagesRequest(bridgeId = "unregistered", bridgeName = "phone", messages = listOf(message()))))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("unverified", response.headers["X-Relay-Receipt-Trust"])
            assertEquals("anonymous_lan", response.headers["X-Relay-Route-Authentication"])
            assertEquals("unverified", response.headers["X-Relay-Content-Verification"])
            assertEquals("gateway_saved", response.headers["X-Relay-Receipt-Semantics"])
            val body = GatewayJson.decodeFromString(SyncMessagesResponse.serializer(), response.bodyAsText())
            assertEquals(listOf("anonymous-1"), body.acceptedMessageIds)
            assertEquals(UNVERIFIED_GATEWAY_RECEIPT_TYPE, body.receipts.single().receiptType)
            assertEquals("ANONYMOUS_LAN", store.messageDetail("anonymous-1")?.routeAuthentication)
            assertEquals("UNVERIFIED", store.messageDetail("anonymous-1")?.contentVerification)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/messages").status)
        } finally {
            store.close()
        }
    }

    @Test fun `public ingress enforces per source request limit`() = testApplication {
        val config = GatewayConfig(
            profile = GatewayProfile.DEVELOPMENT,
            dbPath = Files.createTempFile("relay-anonymous-limit", ".db").toString(),
            maxAnonymousRequestsPerMinute = 1,
        )
        val store = GatewayStore(config)
        application { gatewayModule(config, store) }
        val body = GatewayJson.encodeToString(SyncMessagesRequest(bridgeId = "unregistered", bridgeName = "phone", messages = emptyList()))
        try {
            val first = client.post("/api/public/sync/messages") { contentType(ContentType.Application.Json); setBody(body) }
            val second = client.post("/api/public/sync/messages") { contentType(ContentType.Application.Json); setBody(body) }
            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.TooManyRequests, second.status)
        } finally {
            store.close()
        }
    }

    @Test fun `public ingress stores same batch status change as unverified without applying it`() = testApplication {
        val config = GatewayConfig(profile = GatewayProfile.DEVELOPMENT, dbPath = Files.createTempFile("relay-anonymous-status", ".db").toString())
        val store = GatewayStore(config)
        application { gatewayModule(config, store) }
        val report = message("report-1")
        val change = statusChange("change-1", report.messageId)
        try {
            val response = client.post("/api/public/sync/messages") {
                contentType(ContentType.Application.Json)
                setBody(
                    GatewayJson.encodeToString(
                        SyncMessagesRequest(
                            bridgeId = "unregistered",
                            bridgeName = "phone",
                            messages = listOf(change, report),
                        ),
                    ),
                )
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val decoded = GatewayJson.decodeFromString(SyncMessagesResponse.serializer(), response.bodyAsText())
            assertEquals(setOf(report.messageId, change.messageId), decoded.acceptedMessageIds.toSet())
            assertTrue(decoded.rejected.isEmpty())
            assertEquals(setOf(report.messageId, change.messageId), decoded.receipts.map { it.messageId }.toSet())
            assertEquals(setOf(UNVERIFIED_GATEWAY_RECEIPT_TYPE), decoded.receipts.map { it.receiptType }.toSet())
            assertEquals("ACTIVE", store.messageDetail(report.messageId)?.status)
            assertEquals("UNVERIFIED", store.messageDetail(change.messageId)?.ingressTrust)
        } finally {
            store.close()
        }
    }

    @Test fun `public sync response keeps store outcomes matched to message IDs after priority sorting`() = testApplication {
        val config = GatewayConfig(
            profile = GatewayProfile.DEVELOPMENT,
            dbPath = Files.createTempFile("relay-anonymous-outcome-ids", ".db").toString(),
            gatewayId = "gateway-test",
        )
        val store = GatewayStore(config)
        application { gatewayModule(config, store) }
        val duplicate = message("duplicate-high").copy(priority = "CRITICAL")
        val storedCollision = message("collision-normal").copy(priority = "NORMAL")
        store.ingestUnregistered(listOf(duplicate, storedCollision), now = 2_000)
        val newMessage = message("new-low").copy(priority = "LOW")
        val collidingMessage = storedCollision.copy(payload = JsonPrimitive("different"))
        val request = SyncMessagesRequest(
            bridgeId = "unregistered",
            bridgeName = "phone",
            messages = listOf(newMessage, duplicate, collidingMessage),
        )
        try {
            val response = client.post("/api/public/sync/messages") {
                contentType(ContentType.Application.Json)
                setBody(GatewayJson.encodeToString(request))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val decoded = GatewayJson.decodeFromString(SyncMessagesResponse.serializer(), response.bodyAsText())

            assertEquals(listOf("new-low"), decoded.acceptedMessageIds)
            assertEquals(listOf("duplicate-high"), decoded.duplicateMessageIds)
            assertEquals(listOf("collision-normal"), decoded.rejected.map { it.messageId })
            assertEquals("messageId collision", decoded.rejected.single().reason)
            assertEquals(setOf("new-low", "duplicate-high"), decoded.receipts.map { it.messageId }.toSet())
        } finally {
            store.close()
        }
    }

    @Test fun `limiter resets window and accounts for bytes and messages`() {
        var now = 1_000L
        val limiter = AnonymousIngressRateLimiter(2, 2, 10, windowMs = 1_000) { now }
        assertTrue(limiter.allow("source", 1, 5))
        assertFalse(limiter.allow("source", 2, 1))
        assertTrue(limiter.allow("other", 2, 10))
        now += 1_000
        assertTrue(limiter.allow("source", 2, 10))
    }

    @Test fun `LAN announcement contains discovery data but no admin secret`() {
        val config = GatewayConfig(
            profile = GatewayProfile.DEVELOPMENT,
            gatewayId = "gateway-test",
            shelterId = "shelter-test",
            adminKey = "do-not-advertise",
            port = 9080,
            publicPort = 9080,
        )
        val text = GatewayLanBeacon(config, rescueTrustReady = true).announcementBytes().decodeToString()
        val announcement = GatewayJson.decodeFromString(GatewayLanAnnouncement.serializer(), text)
        assertEquals("gateway-test", announcement.gatewayId)
        assertEquals("shelter-test", announcement.shelterId)
        assertTrue(announcement.rescueIngressReady)
        assertEquals(9080, announcement.apiPort)
        assertEquals("UNVERIFIED", announcement.receiptTrust)
        assertFalse(text.contains("do-not-advertise"))
    }

    @Test fun `LAN announcement is fail closed when rescue trust or ingress is disabled`() {
        val untrusted = GatewayConfig(gatewayId = "gateway-untrusted", shelterId = "shelter-test")
        val untrustedAnnouncement = GatewayJson.decodeFromString(
            GatewayLanAnnouncement.serializer(),
            GatewayLanBeacon(untrusted, rescueTrustReady = false).announcementBytes().decodeToString(),
        )
        assertFalse(untrustedAnnouncement.rescueIngressReady)

        val disabled = GatewayConfig(
            gatewayId = "gateway-disabled",
            shelterId = "shelter-test",
            anonymousIngressEnabled = false,
        )
        val disabledAnnouncement = GatewayJson.decodeFromString(
            GatewayLanAnnouncement.serializer(),
            GatewayLanBeacon(disabled, rescueTrustReady = true).announcementBytes().decodeToString(),
        )
        assertFalse(disabledAnnouncement.rescueIngressReady)
    }
}
