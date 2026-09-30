package com.example.relay.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayDeliveryLedgerTest {
    @Test
    fun `completed and terminal ids stay suppressed while stale ids are pruned`() {
        val ledger = InMemoryGatewayDeliveryLedger()

        assertEquals(setOf("a", "b", "c"), ledger.pendingIds(setOf("a", "b", "c")))
        ledger.markCompleted(setOf("a"))
        ledger.markTerminal(setOf("b"))

        assertEquals(setOf("c", "d"), ledger.pendingIds(setOf("a", "b", "c", "d")))
        assertEquals(setOf("d"), ledger.pendingIds(setOf("d")))
    }
}
