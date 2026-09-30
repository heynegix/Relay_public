package com.example.relay.pcgateway

import com.example.relay.gateway.protocol.GatewayMessage
import com.example.relay.gateway.protocol.UNVERIFIED_GATEWAY_RECEIPT_TYPE
import com.example.relay.gateway.protocol.VERIFIED_GATEWAY_RECEIPT_TYPE
import java.nio.file.Files
import java.sql.DriverManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayStoreTest {
    private fun store(): GatewayStore = GatewayStore(GatewayConfig(dbPath = Files.createTempFile("relay-gateway", ".db").toString(), gatewayId = "gateway-test"))

    private fun message(id: String = "m-1", age: Long = 0) = GatewayMessage(
        messageId = id, messageType = "SAFETY", recordType = "REPORT", priority = "HIGH", status = "ACTIVE",
        createdAt = 1_000, expiresAt = 86_401_000, lifetimeMs = 86_400_000, accumulatedAgeMs = age,
        hopCount = 1, hopLimit = 8, originDeviceId = "origin", payload = JsonPrimitive("safe"), receivedAt = 1_000,
    )

    private fun statusChange(
        id: String = "change-1",
        targetMessageId: String = "m-1",
        newStatus: String = "RESOLVED",
        eventCreatedAt: Long = 2_000,
    ) =
        message(id).copy(
            recordType = "STATUS_CHANGE",
            priority = "CRITICAL",
            createdAt = eventCreatedAt,
            expiresAt = eventCreatedAt + 86_400_000,
            receivedAt = eventCreatedAt,
            payload = buildJsonObject {
                put("eventId", "event-$id")
                put("targetMessageId", targetMessageId)
                put("newStatus", newStatus)
                put("reason", "safe")
                put("createdAt", eventCreatedAt)
                put("createdBy", "admin")
            },
        )

    @Test fun `saving creates gateway receipt and duplicate is idempotent`() {
        store().use { db ->
            val code = db.createPairingCode(1_000)
            assertEquals(true, db.requestPair(code, "bridge", "Bridge", 1_001))
            assertNotNull(db.approvePair("bridge", code, 1_002))
            val result = db.ingest("bridge", listOf(message()), now = 2_000)
            assertEquals("STORED", result.single().disposition)
            assertEquals("AUTHENTICATED_BRIDGE", db.messageDetail("m-1")?.routeAuthentication)
            assertEquals("UNVERIFIED", db.messageDetail("m-1")?.contentVerification)
            assertEquals(0 to 1, db.trustCounts())
            assertEquals(RouteAuthenticationCounts(1, 0), db.routeAuthenticationCounts())
            assertEquals(1, db.receipts().size)
            val duplicate = db.ingest("bridge", listOf(message()), now = 3_000)
            assertEquals("DUPLICATE", duplicate.single().disposition)
            assertEquals(1, db.receipts().size)
        }
    }

    @Test fun `signed without issuer trust remains in unverified counts and filters`() {
        store().use { db ->
            val signedUnverified = "SIGNED_UNVERIFIED"
            val signed = message("signed-unverified").copy(reportSignature = JsonPrimitive("signature-present"))

            db.ingestUnregistered(listOf(signed), now = 2_000)

            assertEquals(signedUnverified, db.messageDetail(signed.messageId)?.contentVerification)
            assertEquals(0 to 1, db.trustCounts())
            assertEquals(listOf(signed.messageId), db.messages(trust = "UNVERIFIED").map { it.messageId })
            assertEquals(listOf(signed.messageId), db.messages(trust = signedUnverified).map { it.messageId })

            val code = db.createPairingCode(3_000)
            db.requestPair(code, "bridge", "Bridge", 3_001)
            db.approvePair("bridge", code, 3_002)
            db.ingest("bridge", listOf(signed), now = 4_000)

            assertEquals(signedUnverified, db.messageDetail(signed.messageId)?.contentVerification)
            assertEquals(0 to 1, db.trustCounts())
        }
    }

    @Test fun `duplicate returns its existing receipt after database reaches message limit`() {
        val config = GatewayConfig(
            dbPath = Files.createTempFile("relay-gateway-full", ".db").toString(),
            gatewayId = "gateway-test",
            maxStoredMessages = 1,
        )
        GatewayStore(config).use { db ->
            val first = db.ingestUnregistered(listOf(message()), now = 2_000).single()
            assertEquals("STORED", first.disposition)

            val duplicate = db.ingestUnregistered(listOf(message()), now = 3_000).single()

            assertEquals("DUPLICATE", duplicate.disposition)
            assertEquals(first.receipt?.receiptId, duplicate.receipt?.receiptId)
            assertEquals(UNVERIFIED_GATEWAY_RECEIPT_TYPE, duplicate.receipt?.receiptType)
        }
    }

    @Test fun `duplicate transport metadata keeps conservative age and hop without collision`() {
        store().use { db ->
            val original = message(age = 1_000).copy(hopCount = 1, receivedAt = 1_000)
            val first = db.ingestUnregistered(listOf(original), now = 2_000).single()
            val forwarded = original.copy(
                accumulatedAgeMs = 5_000,
                hopCount = 3,
                receivedAt = 4_000,
            )

            val forwardedDuplicate = db.ingestUnregistered(listOf(forwarded), now = 5_000).single()

            assertEquals("DUPLICATE", forwardedDuplicate.disposition)
            assertEquals(first.receipt?.receiptId, forwardedDuplicate.receipt?.receiptId)
            assertEquals(5_000L, db.messageDetail(original.messageId)?.accumulatedAgeMs)
            assertEquals(3, db.messageDetail(original.messageId)?.hopCount)
            assertEquals(4_000L, db.messageDetail(original.messageId)?.receivedAt)

            val shorterRoute = forwarded.copy(accumulatedAgeMs = 4_000, hopCount = 1, receivedAt = 3_000)
            val shorterRouteDuplicate = db.ingestUnregistered(listOf(shorterRoute), now = 6_000).single()

            assertEquals("DUPLICATE", shorterRouteDuplicate.disposition)
            assertEquals(first.receipt?.receiptId, shorterRouteDuplicate.receipt?.receiptId)
            assertEquals(5_000L, db.messageDetail(original.messageId)?.accumulatedAgeMs)
            assertEquals(3, db.messageDetail(original.messageId)?.hopCount)
            assertEquals(4_000L, db.messageDetail(original.messageId)?.receivedAt)
        }
    }

    @Test fun `transport metadata tolerance does not hide immutable message collisions`() {
        store().use { db ->
            val original = message(age = 1_000)
            db.ingestUnregistered(listOf(original), now = 2_000)
            val alteredMessages = listOf(
                original.copy(accumulatedAgeMs = 2_000, payload = JsonPrimitive("tampered")),
                original.copy(accumulatedAgeMs = 2_000, originDeviceId = "other-origin"),
                original.copy(accumulatedAgeMs = 2_000, lifetimeMs = original.lifetimeMs - 1),
            )

            alteredMessages.forEach { altered ->
                val result = db.ingestUnregistered(listOf(altered), now = 3_000).single()
                assertEquals("COLLISION", result.disposition)
                assertEquals("messageId collision", result.reason)
                assertNull(result.receipt)
            }
        }
    }

    @Test fun `expired duplicate is rejected without returning receipt`() {
        store().use { db ->
            val original = message(age = 1_000)
            db.ingestUnregistered(listOf(original), now = 2_000)
            val expired = original.copy(accumulatedAgeMs = original.lifetimeMs)

            val result = db.ingestUnregistered(listOf(expired), now = 3_000).single()

            assertEquals("REJECTED", result.disposition)
            assertEquals("expired_or_invalid_ttl", result.reason)
            assertNull(result.receipt)
        }
    }

    @Test fun `invalid age is rejected before database save`() {
        store().use { db ->
            val code = db.createPairingCode(1_000)
            db.requestPair(code, "bridge", "Bridge", 1_001)
            db.approvePair("bridge", code, 1_002)
            val result = db.ingest("bridge", listOf(message(age = 86_400_000)), now = 2_000)
            assertEquals("REJECTED", result.single().disposition)
        }
    }

    @Test fun `paired resync adds authenticated route receipt without verifying content`() {
        store().use { db ->
            val anonymous = db.ingestUnregistered(listOf(message()), now = 2_000).single()
            assertEquals("STORED", anonymous.disposition)
            assertEquals(UNVERIFIED_GATEWAY_RECEIPT_TYPE, anonymous.receipt?.receiptType)
            assertEquals(listOf(UNVERIFIED_GATEWAY_RECEIPT_TYPE), db.receipts().map { it.receiptType })
            assertEquals("UNVERIFIED", db.messages().single().ingressTrust)

            val code = db.createPairingCode(3_000)
            db.requestPair(code, "bridge", "Bridge", 3_001)
            db.approvePair("bridge", code, 3_002)
            val paired = db.ingest("bridge", listOf(message()), now = 4_000).single()
            assertEquals("DUPLICATE", paired.disposition)
            assertEquals(VERIFIED_GATEWAY_RECEIPT_TYPE, paired.receipt?.receiptType)
            assertEquals(setOf(UNVERIFIED_GATEWAY_RECEIPT_TYPE, VERIFIED_GATEWAY_RECEIPT_TYPE), db.receipts().map { it.receiptType }.toSet())
            val saved = db.messages().single()
            assertEquals("UNVERIFIED", saved.ingressTrust)
            assertEquals("UNVERIFIED", saved.contentVerification)
            assertEquals("AUTHENTICATED_BRIDGE", saved.routeAuthentication)
        }
    }

    @Test fun `authenticated duplicate upgrades route evidence despite lower transport age and hop`() {
        store().use { db ->
            val publicCopy = message(age = 5_000).copy(hopCount = 3, receivedAt = 5_000)
            val unverified = db.ingestUnregistered(listOf(publicCopy), now = 6_000).single()
            val code = db.createPairingCode(7_000)
            db.requestPair(code, "bridge", "Bridge", 7_001)
            db.approvePair("bridge", code, 7_002)

            val pairedCopy = publicCopy.copy(accumulatedAgeMs = 1_000, hopCount = 1, receivedAt = 2_000)
            val verified = db.ingest("bridge", listOf(pairedCopy), now = 8_000).single()

            assertEquals("DUPLICATE", verified.disposition)
            assertEquals(UNVERIFIED_GATEWAY_RECEIPT_TYPE, unverified.receipt?.receiptType)
            assertEquals(VERIFIED_GATEWAY_RECEIPT_TYPE, verified.receipt?.receiptType)
            assertEquals("UNVERIFIED", db.messageDetail(publicCopy.messageId)?.ingressTrust)
            assertEquals("UNVERIFIED", db.messageDetail(publicCopy.messageId)?.contentVerification)
            assertEquals("AUTHENTICATED_BRIDGE", db.messageDetail(publicCopy.messageId)?.routeAuthentication)
            assertEquals(5_000L, db.messageDetail(publicCopy.messageId)?.accumulatedAgeMs)
            assertEquals(3, db.messageDetail(publicCopy.messageId)?.hopCount)
        }
    }

    @Test fun `expired pairing code cannot be used`() {
        store().use { db ->
            val code = db.createPairingCode(1_000)
            assertEquals(false, db.requestPair(code, "bridge", "Bridge", 1_000 + 5 * 60_001))
            assertNull(db.approvePair("bridge", code, 1_000 + 5 * 60_001))
        }
    }

    @Test fun `STATUS_CHANGE updates the target report status`() {
        store().use { db ->
            val code = db.createPairingCode(1_000); db.requestPair(code, "bridge", "Bridge", 1_001); db.approvePair("bridge", code, 1_002)
            db.ingest("bridge", listOf(message()), 2_000)
            val change = statusChange()
            assertEquals("STORED", db.ingest("bridge", listOf(change), 2_100).single().disposition)
            assertEquals("RESOLVED", db.messages().first { it.messageId == "m-1" }.status)
        }
    }

    @Test fun `authenticated batch persists report before its higher priority status change`() {
        store().use { db ->
            val code = db.createPairingCode(1_000)
            db.requestPair(code, "bridge", "Bridge", 1_001)
            db.approvePair("bridge", code, 1_002)
            val report = message("batch-report")
            val change = statusChange("batch-change", report.messageId)

            val outcomes = db.ingest("bridge", listOf(change, report), now = 2_000)

            assertEquals(setOf(report.messageId, change.messageId), outcomes.filter { it.disposition == "STORED" }.map { it.messageId }.toSet())
            assertEquals(setOf(report.messageId, change.messageId), outcomes.mapNotNull { it.receipt?.messageId }.toSet())
            assertEquals(setOf(VERIFIED_GATEWAY_RECEIPT_TYPE), outcomes.mapNotNull { it.receipt?.receiptType }.toSet())
            assertEquals("RESOLVED", db.messageDetail(report.messageId)?.status)
        }
    }

    @Test fun `public batch persists report before its higher priority status change`() {
        store().use { db ->
            val report = message("public-batch-report")
            val change = statusChange("public-batch-change", report.messageId)

            val outcomes = db.ingestUnregistered(listOf(change, report), now = 2_000)

            assertEquals(setOf(report.messageId, change.messageId), outcomes.filter { it.disposition == "STORED" }.map { it.messageId }.toSet())
            assertEquals(setOf(report.messageId, change.messageId), outcomes.mapNotNull { it.receipt?.messageId }.toSet())
            assertEquals(setOf(UNVERIFIED_GATEWAY_RECEIPT_TYPE), outcomes.mapNotNull { it.receipt?.receiptType }.toSet())
            assertEquals("ACTIVE", db.messageDetail(report.messageId)?.status)
            assertEquals("UNVERIFIED", db.messageDetail(change.messageId)?.ingressTrust)
        }
    }

    @Test fun `late older authenticated status change cannot overwrite a newer event`() {
        store().use { db ->
            val code = db.createPairingCode(1_000)
            db.requestPair(code, "bridge", "Bridge", 1_001)
            db.approvePair("bridge", code, 1_002)
            val report = message("ordered-report")
            db.ingest("bridge", listOf(report), now = 1_500)
            val newer = statusChange(
                id = "status-newer",
                targetMessageId = report.messageId,
                newStatus = "RESOLVED",
                eventCreatedAt = 3_000,
            )
            val older = statusChange(
                id = "status-older",
                targetMessageId = report.messageId,
                newStatus = "RETRACTED",
                eventCreatedAt = 2_000,
            )

            assertEquals("STORED", db.ingest("bridge", listOf(newer), now = 3_100).single().disposition)
            assertEquals("RESOLVED", db.messageDetail(report.messageId)?.status)
            assertEquals("STORED", db.ingest("bridge", listOf(older), now = 3_200).single().disposition)

            assertEquals("RESOLVED", db.messageDetail(report.messageId)?.status)
        }
    }

    @Test fun `reject pair revokes token and prevents authentication`() {
        store().use { db ->
            val code = db.createPairingCode(1_000)
            db.requestPair(code, "bridge-a", "Bridge A", 1_001)
            val token = db.approvePair("bridge-a", code, 1_002)!!
            assertEquals(true, db.authenticate("bridge-a", token))
            assertEquals(true, db.rejectPair("bridge-a", code = null, now = 1_003))
            assertEquals(false, db.authenticate("bridge-a", token))
        }
    }

    @Test fun `receipts for bridge are scoped to that bridge submissions`() {
        store().use { db ->
            val codeA = db.createPairingCode(1_000)
            db.requestPair(codeA, "bridge-a", "A", 1_001)
            db.approvePair("bridge-a", codeA, 1_002)
            val codeB = db.createPairingCode(1_010)
            db.requestPair(codeB, "bridge-b", "B", 1_011)
            db.approvePair("bridge-b", codeB, 1_012)
            db.ingest("bridge-a", listOf(message("from-a")), 2_000)
            db.ingest("bridge-b", listOf(message("from-b")), 2_100)
            assertEquals(listOf("from-a"), db.receiptsForBridge("bridge-a").map { it.messageId })
            assertEquals(listOf("from-b"), db.receiptsForBridge("bridge-b").map { it.messageId })
            assertEquals(2, db.receipts().size)
        }
    }

    @Test fun `unverified public messages show unverified receipt flag`() {
        store().use { db ->
            db.ingestUnregistered(listOf(message()), 2_000)
            val row = db.messages().single()
            assertEquals(true, row.gatewayReceivedUnverified)
            assertEquals(false, row.gatewayReceived)
            assertEquals("UNVERIFIED", row.ingressTrust)
            assertEquals("UNVERIFIED", row.contentVerification)
            assertEquals("ANONYMOUS_LAN", row.routeAuthentication)
            assertNull(row.sourceBridgeId)
        }
    }

    @Test fun `legacy verified ingress migrates to authenticated route but unverified content`() {
        val path = Files.createTempFile("relay-gateway-legacy-trust", ".db").toString()
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE messages(
                      message_id TEXT PRIMARY KEY, canonical_json TEXT NOT NULL, message_type TEXT NOT NULL,
                      record_type TEXT NOT NULL, priority TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL,
                      expires_at INTEGER NOT NULL, lifetime_ms INTEGER NOT NULL, accumulated_age_ms INTEGER NOT NULL,
                      hop_count INTEGER NOT NULL, hop_limit INTEGER NOT NULL, origin_id TEXT NOT NULL, received_at INTEGER NOT NULL,
                      ingress_trust TEXT NOT NULL DEFAULT 'VERIFIED', source_bridge_id TEXT,
                      status_event_created_at INTEGER NOT NULL DEFAULT -1, status_event_id TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent(),
                )
            }
            val legacy = message("legacy-auth-route")
            connection.prepareStatement(
                """
                INSERT INTO messages(
                  message_id,canonical_json,message_type,record_type,priority,status,created_at,expires_at,
                  lifetime_ms,accumulated_age_ms,hop_count,hop_limit,origin_id,received_at,ingress_trust,source_bridge_id
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, legacy.messageId)
                ps.setString(2, GatewayJson.encodeToString(legacy))
                ps.setString(3, legacy.messageType)
                ps.setString(4, legacy.recordType)
                ps.setString(5, legacy.priority)
                ps.setString(6, legacy.status)
                ps.setLong(7, legacy.createdAt)
                ps.setLong(8, legacy.expiresAt)
                ps.setLong(9, legacy.lifetimeMs)
                ps.setLong(10, legacy.accumulatedAgeMs)
                ps.setInt(11, legacy.hopCount)
                ps.setInt(12, legacy.hopLimit)
                ps.setString(13, legacy.originDeviceId)
                ps.setLong(14, legacy.receivedAt)
                ps.setString(15, "VERIFIED")
                ps.setString(16, "legacy-bridge")
                ps.executeUpdate()
            }
        }

        GatewayStore(GatewayConfig(dbPath = path, gatewayId = "gateway-test")).use { migrated ->
            val row = migrated.messageDetail("legacy-auth-route")!!
            assertEquals("AUTHENTICATED_BRIDGE", row.routeAuthentication)
            assertEquals("UNVERIFIED", row.contentVerification)
            assertEquals("UNVERIFIED", row.ingressTrust)
            assertEquals(0 to 1, migrated.trustCounts())
        }
        GatewayStore(GatewayConfig(dbPath = path, gatewayId = "gateway-test")).use { reopened ->
            assertEquals("AUTHENTICATED_BRIDGE", reopened.messageDetail("legacy-auth-route")?.routeAuthentication)
            assertEquals("UNVERIFIED", reopened.messageDetail("legacy-auth-route")?.contentVerification)
        }
    }
}
