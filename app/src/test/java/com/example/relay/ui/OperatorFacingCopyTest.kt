package com.example.relay.ui

import com.example.relay.domain.DeliveryPresentation
import com.example.relay.domain.deliveryPresentationLabel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: UI copy for gateway status must not overclaim official/final delivery.
 */
class OperatorFacingCopyTest {
    @Test
    fun `gateway status after send still marks content as unverified`() {
        val label = gatewayStatusLabel("sent=2", transportRunning = true)
        assertTrue(label.contains("未検証") || label.contains("未認証"))
        assertFalse(label.contains("公式到達"))
        assertFalse(label.contains("最終配信完了"))
    }

    @Test
    fun `public unverified receipt label is distinct from peer ACK`() {
        val gateway = deliveryPresentationLabel(DeliveryPresentation.GATEWAY_RECEIVED_UNVERIFIED)
        val peer = deliveryPresentationLabel(DeliveryPresentation.PEER_RECEIVED)
        assertTrue(gateway.contains("中継拠点"))
        assertTrue(peer.contains("近くの端末"))
        assertFalse(gateway == peer)
    }
}
