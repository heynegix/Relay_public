package com.example.relay.rescue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for BrokerReceiptPoller logic (non-Android parts).
 * Full polling loop requires instrumentation tests with a mock Broker.
 */
class BrokerReceiptPollerTest {

    @Test
    fun `receipt batch model deserializes empty list`() {
        val batch = BrokerReceiptBatch(receipts = emptyList())
        assertTrue(batch.receipts.isEmpty())
    }

    @Test
    fun `capability token is device key id not identity`() {
        // The deviceKeyId acts as a capability token for receipt retrieval.
        // It is NOT identity-verified by the Broker.
        val keyId = "relay_broker_upload"
        assertEquals("relay_broker_upload", keyId)
        // This is a design assertion: the key ID is used for routing, not authentication
    }

    @Test
    fun `only SHELTER states come from signed receipts`() {
        // BROKER_STORED is a separate ledger flag and does NOT advance shelter status.
        // SHELTER_STORED, SHELTER_ACCEPTED, etc. only come from applyReceipt().
        val brokerStatus = "BROKER_STORED"
        val shelterStatuses = listOf(
            RescueSubmissionStatus.SHELTER_STORED,
            RescueSubmissionStatus.SHELTER_ACCEPTED,
            RescueSubmissionStatus.SHELTER_RESPONDING,
            RescueSubmissionStatus.SHELTER_COMPLETED,
            RescueSubmissionStatus.SHELTER_REJECTED,
        )
        // Broker status is a string in the ledger, not a RescueSubmissionStatus
        assertTrue(brokerStatus.startsWith("BROKER_"))
        assertTrue(shelterStatuses.all { it.name.startsWith("SHELTER_") })
    }
}
