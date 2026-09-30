package com.example.relay.broker

import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.SignedShelterReceipt
import com.example.relay.rescue.UnsignedShelterReceipt
import com.example.relay.rescue.ShelterReceiptStatus
import java.io.File
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BrokerStoreTest {
    private lateinit var store: BrokerStore
    private lateinit var dbFile: File

    @Before
    fun setup() {
        dbFile = File.createTempFile("broker-test", ".db")
        dbFile.deleteOnExit()
        store = BrokerStore(dbFile.absolutePath)
    }

    @After
    fun teardown() {
        store.close()
        dbFile.delete()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `production broker refuses a direct non-loopback listener`() {
        BrokerConfig(profile = BrokerProfile.PRODUCTION, host = "0.0.0.0")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `production broker refuses the legacy shared gateway key`() {
        BrokerConfig(profile = BrokerProfile.PRODUCTION, legacyGatewayApiKey = "legacy-shared-key")
    }

    private fun testEnvelope(
        envelopeId: String = "env-001",
        requestId: String = "req-001",
        requestVersion: Int = 1,
        shelterId: String = "example-01",
        ciphertextHash: String = "a".repeat(64),
        expiresAt: Long = System.currentTimeMillis() + 3_600_000,
    ) = EncryptedRescueEnvelope(
        envelopeId = envelopeId,
        requestId = requestId,
        requestVersion = requestVersion,
        senderDeviceId = "device-001",
        destinationShelterId = shelterId,
        routingUrgency = RescueUrgency.IMMEDIATE,
        recipientKeyId = "key-001",
        createdAtEpochMillis = System.currentTimeMillis(),
        expiresAtEpochMillis = expiresAt,
        ciphertextSizeBytes = 256,
        wrappedContentKeyBase64 = "A".repeat(172),
        nonceBase64 = "B".repeat(24),
        ciphertextBase64 = "C".repeat(344),
        ciphertextSha256Hex = ciphertextHash,
    )

    private fun testReceipt(
        receiptId: String = "rcpt-001",
        envelopeId: String = "env-001",
        requestId: String = "req-001",
        requestVersion: Int = 1,
        ciphertextHash: String = "a".repeat(64),
        shelterId: String = "example-01",
        receivedAt: Long = System.currentTimeMillis(),
    ) = SignedShelterReceipt(
        receipt = UnsignedShelterReceipt(
            receiptId = receiptId,
            envelopeId = envelopeId,
            requestId = requestId,
            requestVersion = requestVersion,
            ciphertextSha256Hex = ciphertextHash,
            shelterId = shelterId,
            receivedAtEpochMillis = receivedAt,
            status = ShelterReceiptStatus.ACCEPTED,
        ),
        signerKeyId = "signer-001",
        signatureBase64 = "D".repeat(88),
    )

    @Test
    fun `put stores new envelope`() {
        val envelope = testEnvelope()
        val result = store.put(envelope, "device-key-1", System.currentTimeMillis())
        assertTrue(result is BrokerPutResult.Stored)
        val stored = result as BrokerPutResult.Stored
        assertEquals(envelope.envelopeId, stored.response.envelopeId)
        assertEquals("BROKER_STORED", stored.response.status)
    }

    @Test
    fun `put returns Duplicate for same envelope`() {
        val envelope = testEnvelope()
        val now = System.currentTimeMillis()
        val stored = store.put(envelope, "device-key-1", now) as BrokerPutResult.Stored
        val result = store.put(envelope, "device-key-1", now + 1000)
        assertTrue(result is BrokerPutResult.Duplicate)
        assertEquals(stored.response, (result as BrokerPutResult.Duplicate).response)
    }

    @Test
    fun `same envelope id with different logical request cannot return false stored`() {
        val now = System.currentTimeMillis()
        store.put(testEnvelope(), "device-key-1", now)

        val result = store.put(
            testEnvelope(requestId = "req-other", ciphertextHash = "b".repeat(64)),
            "device-key-2",
            now + 1,
        )

        assertTrue(result is BrokerPutResult.Collision)
        assertEquals("env-001", (result as BrokerPutResult.Collision).existingEnvelopeId)
        assertEquals(1, store.countPendingEnvelopes(now))
    }

    @Test
    fun `legacy database migration preserves a stable duplicate acknowledgement`() {
        val envelope = testEnvelope()
        val storedAt = System.currentTimeMillis()
        store.close()
        dbFile.delete()
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE broker_envelopes(
                      envelope_id TEXT PRIMARY KEY,
                      request_id TEXT NOT NULL,
                      request_version INTEGER NOT NULL,
                      sender_device_id TEXT NOT NULL,
                      shelter_id TEXT NOT NULL,
                      expires_at INTEGER NOT NULL,
                      ciphertext_hash TEXT NOT NULL,
                      envelope_json TEXT NOT NULL,
                      device_key_id TEXT NOT NULL,
                      stored_at INTEGER NOT NULL,
                      UNIQUE(request_id, request_version, ciphertext_hash)
                    )
                    """.trimIndent(),
                )
            }
            connection.prepareStatement(
                """INSERT INTO broker_envelopes
                   (envelope_id, request_id, request_version, sender_device_id, shelter_id,
                    expires_at, ciphertext_hash, envelope_json, device_key_id, stored_at)
                   VALUES(?,?,?,?,?,?,?,?,?,?)""",
            ).use { statement ->
                statement.setString(1, envelope.envelopeId)
                statement.setString(2, envelope.requestId)
                statement.setInt(3, envelope.requestVersion)
                statement.setString(4, envelope.senderDeviceId)
                statement.setString(5, envelope.destinationShelterId)
                statement.setLong(6, envelope.expiresAtEpochMillis)
                statement.setString(7, envelope.ciphertextSha256Hex)
                statement.setString(8, brokerJson.encodeToString(EncryptedRescueEnvelope.serializer(), envelope))
                statement.setString(9, "legacy-device")
                statement.setLong(10, storedAt)
                statement.executeUpdate()
            }
        }

        store = BrokerStore(dbFile.absolutePath)
        val duplicate = store.put(envelope, "courier-device", storedAt + 1) as BrokerPutResult.Duplicate

        assertEquals(envelope.envelopeId, duplicate.response.brokerReceiptId)
        assertEquals(storedAt, duplicate.response.storedAtEpochMillis)
    }

    @Test
    fun `put returns Collision for same request key different hash`() {
        val envelope1 = testEnvelope(ciphertextHash = "a".repeat(64))
        val envelope2 = testEnvelope(envelopeId = "env-002", ciphertextHash = "b".repeat(64))
        val now = System.currentTimeMillis()
        store.put(envelope1, "device-key-1", now)
        val result = store.put(envelope2, "device-key-2", now + 1000)
        assertTrue(result is BrokerPutResult.Collision)
        assertEquals("env-001", (result as BrokerPutResult.Collision).existingEnvelopeId)
    }

    @Test
    fun `pendingForShelter returns envelopes for correct shelter`() {
        val now = System.currentTimeMillis()
        store.put(testEnvelope(envelopeId = "env-001", shelterId = "example-01"), "dk1", now)
        store.put(testEnvelope(envelopeId = "env-002", requestId = "req-002", shelterId = "other-01"), "dk2", now)

        val batch = store.pendingForShelter("example-01", now, null, 50, "gw-1")
        assertEquals(1, batch.envelopes.size)
        assertEquals("env-001", batch.envelopes[0].envelopeId)
    }

    @Test
    fun `pendingForShelter excludes expired envelopes`() {
        val now = System.currentTimeMillis()
        store.put(testEnvelope(envelopeId = "env-expired", requestId = "req-exp", expiresAt = now - 1000), "dk1", now - 2000)
        store.put(testEnvelope(envelopeId = "env-active", requestId = "req-act"), "dk1", now)

        val batch = store.pendingForShelter("example-01", now, null, 50, "gw-1")
        assertEquals(1, batch.envelopes.size)
        assertEquals("env-active", batch.envelopes[0].envelopeId)
    }

    @Test
    fun `pendingForShelter composite cursor pagination does not skip same-timestamp records`() {
        val now = System.currentTimeMillis()
        // Both stored at the same timestamp
        store.put(testEnvelope(envelopeId = "env-aaa"), "dk1", now)
        store.put(testEnvelope(envelopeId = "env-bbb", requestId = "req-002"), "dk1", now)

        val batch1 = store.pendingForShelter("example-01", now + 200, null, 1, "gw-1")
        assertEquals(1, batch1.envelopes.size)
        assertEquals("env-aaa", batch1.envelopes[0].envelopeId)
        assertNotNull(batch1.cursor)

        // Composite cursor ensures env-bbb is not skipped
        val batch2 = store.pendingForShelter("example-01", now + 200, batch1.cursor, 1, "gw-1")
        assertEquals(1, batch2.envelopes.size)
        assertEquals("env-bbb", batch2.envelopes[0].envelopeId)
    }

    @Test
    fun `pendingForShelter returns null cursor when no results`() {
        val batch = store.pendingForShelter("example-01", System.currentTimeMillis(), null, 50, "gw-1")
        assertTrue(batch.envelopes.isEmpty())
        assertNull(batch.cursor)
    }

    @Test
    fun `device registration returns capability token`() {
        val result = store.registerDevice("device-uuid-1", "pubkey-base64", System.currentTimeMillis())
        assertEquals("device-uuid-1", result.deviceKeyId)
        assertTrue(result.capabilityToken.length >= 64) // 2x UUID without dashes
    }

    @Test
    fun `device registration is idempotent`() {
        val result1 = store.registerDevice("device-uuid-1", "pubkey-base64", System.currentTimeMillis())
        val result2 = store.registerDevice("device-uuid-1", "pubkey-base64", System.currentTimeMillis() + 1000)
        assertEquals(result1.capabilityToken, result2.capabilityToken)
    }

    @Test
    fun `device registration never returns a token for a different public key`() {
        store.registerDevice("device-uuid-1", "pubkey-base64", System.currentTimeMillis())
        try {
            store.registerDevice("device-uuid-1", "other-public-key", System.currentTimeMillis() + 1)
            throw AssertionError("expected DeviceKeyConflictException")
        } catch (error: DeviceKeyConflictException) {
            assertTrue(error.message!!.contains("device_key_conflict"))
        }
    }

    @Test
    fun `capability token resolves to device`() {
        val result = store.registerDevice("device-uuid-1", "pubkey-base64", System.currentTimeMillis())
        val resolved = store.deviceForCapabilityToken(result.capabilityToken)
        assertEquals("device-uuid-1", resolved)
    }

    @Test
    fun `invalid capability token returns null`() {
        assertNull(store.deviceForCapabilityToken("nonexistent-token"))
    }

    @Test
    fun `scoped gateway credential is hashed and becomes unusable after revocation`() {
        val now = System.currentTimeMillis()
        val issued = store.issueGatewayCredential("gateway-a", "example-01", now + 60_000, now)

        val authenticated = store.authenticateGatewayCredential(issued.token, now + 1)
        assertNotNull(authenticated)
        assertEquals("gateway-a", authenticated!!.gatewayId)
        assertEquals("example-01", authenticated.shelterId)

        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.prepareStatement("SELECT token_hash FROM broker_gateway_credentials WHERE credential_id=?").use { query ->
                query.setString(1, issued.credentialId)
                query.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertFalse(result.getString(1).contains(issued.token))
                }
            }
        }

        assertTrue(store.revokeGatewayCredential(issued.credentialId, now + 2))
        assertNull(store.authenticateGatewayCredential(issued.token, now + 3))
    }

    @Test
    fun `saveReceipt stores receipt for existing envelope`() {
        val now = System.currentTimeMillis()
        store.put(testEnvelope(envelopeId = "env-001"), "dk1", now)
        val receipt = testReceipt(receivedAt = now)
        assertTrue(store.saveReceipt("example-01", receipt, now))
    }

    @Test(expected = IllegalStateException::class)
    fun `saveReceipt rejects immutable envelope binding mismatch`() {
        val now = System.currentTimeMillis()
        store.put(testEnvelope(), "dk1", now)
        store.saveReceipt(
            "example-01",
            testReceipt(requestId = "different-request"),
            now,
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `saveReceipt throws for unknown envelope`() {
        val receipt = SignedShelterReceipt(
            receipt = UnsignedShelterReceipt(
                receiptId = "rcpt-001",
                envelopeId = "nonexistent",
                requestId = "req-001",
                requestVersion = 1,
                ciphertextSha256Hex = "a".repeat(64),
                shelterId = "example-01",
                receivedAtEpochMillis = System.currentTimeMillis(),
                status = ShelterReceiptStatus.ACCEPTED,
            ),
            signerKeyId = "signer-001",
            signatureBase64 = "D".repeat(88),
        )
        store.saveReceipt("example-01", receipt, System.currentTimeMillis())
    }

    @Test
    fun `receiptsForDevice uses capability token and monotonic seq`() {
        val now = System.currentTimeMillis()
        val reg = store.registerDevice("dk1", "pubkey1", now)
        store.put(testEnvelope(envelopeId = "env-001"), "dk1", now)
        store.put(testEnvelope(envelopeId = "env-002", requestId = "req-002"), "dk2", now)

        val receipt1 = SignedShelterReceipt(
            receipt = UnsignedShelterReceipt(
                receiptId = "rcpt-001", envelopeId = "env-001", requestId = "req-001",
                requestVersion = 1, ciphertextSha256Hex = "a".repeat(64),
                shelterId = "example-01", receivedAtEpochMillis = now, status = ShelterReceiptStatus.ACCEPTED,
            ),
            signerKeyId = "signer-001", signatureBase64 = "D".repeat(88),
        )
        store.saveReceipt("example-01", receipt1, now)

        // Use capability token (not deviceKeyId directly)
        val batch = store.receiptsForDevice(reg.capabilityToken, 0)
        assertEquals(1, batch.receipts.size)
        assertEquals("rcpt-001", batch.receipts[0].receipt.receiptId)
        assertTrue(batch.cursor > 0)

        // Invalid token returns empty
        val emptyBatch = store.receiptsForDevice("invalid-token", 0)
        assertTrue(emptyBatch.receipts.isEmpty())
    }

    @Test
    fun `receipt reaches both the original uploader and a duplicate carrying courier`() {
        val now = System.currentTimeMillis()
        val origin = store.registerDevice("origin", "origin-key", now)
        val courier = store.registerDevice("courier", "courier-key", now)
        val envelope = testEnvelope()
        assertTrue(store.put(envelope, "origin", now) is BrokerPutResult.Stored)
        assertTrue(store.put(envelope, "courier", now + 1) is BrokerPutResult.Duplicate)
        store.saveReceipt("example-01", testReceipt(receivedAt = now + 2), now + 2)

        assertEquals(1, store.receiptsForDevice(origin.capabilityToken, 0).receipts.size)
        assertEquals(1, store.receiptsForDevice(courier.capabilityToken, 0).receipts.size)
    }

    @Test
    fun `purgeExpired removes expired envelopes`() {
        val now = System.currentTimeMillis()
        store.put(testEnvelope(envelopeId = "env-expired", requestId = "req-exp", expiresAt = now - 1000), "dk1", now - 2000)
        store.put(testEnvelope(envelopeId = "env-active", requestId = "req-act"), "dk1", now)

        val purged = store.purgeExpired(now)
        assertEquals(1, purged)
        assertEquals(1, store.countPendingEnvelopes(now))
    }

    @Test
    fun `new uploads remain visible after equal timestamps clock rollback purge and restart`() {
        val now = System.currentTimeMillis()
        val first = testEnvelope(envelopeId = "env-zzz", expiresAt = now + 100)
        store.put(first, "dk1", now)
        val firstPage = store.pendingForShelter("example-01", now, null, 50, "gw")
        store.put(testEnvelope(envelopeId = "env-aaa", requestId = "req-2"), "dk1", now)
        val secondPage = store.pendingForShelter("example-01", now, firstPage.cursor, 50, "gw")
        assertEquals(listOf("env-aaa"), secondPage.envelopes.map { it.envelopeId })
        store.purgeExpired(now + 7_200_000)
        store.close()
        store = BrokerStore(dbFile.absolutePath)
        store.put(testEnvelope(envelopeId = "env-000", requestId = "req-3"), "dk1", now - 10_000)
        val afterRestart = store.pendingForShelter("example-01", now, secondPage.cursor, 50, "gw")
        assertEquals(listOf("env-000"), afterRestart.envelopes.map { it.envelopeId })
    }

    @Test
    fun `late courier receives older receipts beyond its previously consumed cursor`() {
        val now = System.currentTimeMillis()
        val courier = store.registerDevice("courier", "key", now)
        val oldEnvelope = testEnvelope()
        store.put(oldEnvelope, "origin", now)
        store.saveReceipt("example-01", testReceipt(), now)
        store.put(testEnvelope(envelopeId = "env-2", requestId = "req-2"), "courier", now)
        store.saveReceipt(
            "example-01", testReceipt(receiptId = "receipt-2", envelopeId = "env-2", requestId = "req-2"), now,
        )
        val first = store.receiptsForDevice(courier.capabilityToken, 0)
        assertEquals(listOf("receipt-2"), first.receipts.map { it.receipt.receiptId })
        store.put(oldEnvelope, "courier", now)
        val later = store.receiptsForDevice(courier.capabilityToken, first.cursor)
        assertEquals(listOf("rcpt-001"), later.receipts.map { it.receipt.receiptId })
        assertTrue(later.cursor > first.cursor)
        store.put(oldEnvelope, "courier", now)
        assertTrue(store.receiptsForDevice(courier.capabilityToken, later.cursor).receipts.isEmpty())
        store.close()
        store = BrokerStore(dbFile.absolutePath)
        assertTrue(store.receiptsForDevice(courier.capabilityToken, later.cursor).receipts.isEmpty())
    }

    @Test
    fun `receipt delivery migration preserves legacy cursor and cascades retention`() {
        val now = System.currentTimeMillis()
        val courier = store.registerDevice("courier", "key", now)
        store.put(testEnvelope(expiresAt = now + 100), "courier", now)
        store.saveReceipt("example-01", testReceipt(), now)
        store.close()
        var legacyCursor = 0L
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TRIGGER broker_receipt_available")
                statement.execute("DROP TRIGGER broker_courier_subscribed")
                statement.execute("DROP TABLE broker_receipt_deliveries")
                statement.executeQuery("SELECT MAX(seq) FROM broker_receipts").use { rows ->
                    assertTrue(rows.next())
                    legacyCursor = rows.getLong(1)
                }
            }
        }
        store = BrokerStore(dbFile.absolutePath)
        val migrated = store.receiptsForDevice(courier.capabilityToken, legacyCursor)
        assertEquals(1, migrated.receipts.size)
        assertTrue(migrated.cursor > legacyCursor)
        assertEquals(1, store.purgeExpired(now + 100))
        assertTrue(store.receiptsForDevice(courier.capabilityToken, 0).receipts.isEmpty())
    }

    @Test
    fun `rate limiter allows within limit and blocks over limit`() {
        val limiter = SlidingWindowRateLimiter(maxRequests = 3, windowMillis = 60_000)
        val now = System.currentTimeMillis()
        assertTrue(limiter.allow("key1", now))
        assertTrue(limiter.allow("key1", now + 100))
        assertTrue(limiter.allow("key1", now + 200))
        assertFalse(limiter.allow("key1", now + 300))
        // Different key is independent
        assertTrue(limiter.allow("key2", now + 300))
    }
}
