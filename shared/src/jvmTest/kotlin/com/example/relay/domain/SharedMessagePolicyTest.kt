package com.example.relay.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedMessagePolicyTest {
    @Test
    fun createSafetyAndGatewayLabelAreTrustSafe() {
        val clock = MutableRelayClock(1_700_000_000_000L)
        val policy = MessagePolicy(clock)
        val msg = MessageFactory.createSafety(
            state = SafetyState.SAFE,
            companionCount = 0,
            location = "north",
            note = "ok",
            originDeviceId = "device-ios",
            clock = clock,
            policy = policy,
        )
        assertTrue(policy.isActive(msg))
        assertEquals(0, msg.hopCount)
        val label = deliveryPresentationLabel(DeliveryPresentation.GATEWAY_RECEIVED_UNVERIFIED)
        assertTrue(label.contains("未認証") || label.contains("未検証"))
        assertFalse(label.contains("公式"))
    }

    @Test
    fun hopExhaustedStillActiveCanPrepareForGateway() {
        val clock = MutableRelayClock(1_700_000_000_000L)
        val policy = MessagePolicy(clock)
        val msg = MessageFactory.createSafety(
            state = SafetyState.SAFE,
            companionCount = 1,
            location = "a",
            note = "n",
            originDeviceId = "d",
            clock = clock,
            policy = policy,
            messageId = "m1",
        ).copy(hopCount = 8, maxHopCount = 8)
        assertFalse(policy.canForward(msg))
        assertTrue(policy.prepareForGatewayUpload(msg) != null)
    }
}
