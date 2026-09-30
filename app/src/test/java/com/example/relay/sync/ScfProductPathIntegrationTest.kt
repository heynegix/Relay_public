package com.example.relay.sync

import com.example.relay.NOW
import com.example.relay.domain.CreateSafetyMessageUseCase
import com.example.relay.domain.DeviceRole
import com.example.relay.domain.InMemoryMessageRepository
import com.example.relay.domain.MessageIdGenerator
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MutableClock
import com.example.relay.domain.OperatingMode
import com.example.relay.domain.ReceiptType
import com.example.relay.domain.RelayRuntimeSettings
import com.example.relay.domain.SafetyState
import com.example.relay.domain.deliveryPresentationLabel
import com.example.relay.domain.deriveDeliveryPresentation
import com.example.relay.protocol.PacketCodec
import com.example.relay.transport.FakeNetwork
import com.example.relay.transport.FakeOfflineTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end Store–Carry–Forward product path on real [SyncCoordinator] + [FakeOfflineTransport]:
 * create safety on A → hop B → gateway role C issues UNVERIFIED receipt → returns to A.
 * Asserts UI label mapping never claims official final delivery.
 */
class ScfProductPathIntegrationTest {
    private data class Node(
        val repository: InMemoryMessageRepository,
        val transport: FakeOfflineTransport,
        val coordinator: SyncCoordinator,
    )

    @Test
    fun `safety report SCF multi-hop with gateway unverified receipt and trust-safe labels`() = runBlocking {
        val network = FakeNetwork()
        val clock = MutableClock(NOW)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())

        fun node(id: String): Node {
            val repository = InMemoryMessageRepository()
            val policy = MessagePolicy(clock)
            val transport = FakeOfflineTransport(id, network)
            val coordinator = SyncCoordinator(
                id, transport, repository, SyncPlanner(repository, policy), policy,
                PacketCodec(policy), clock, scope,
            )
            return Node(repository, transport, coordinator)
        }

        val a = node("device-A")
        val b = node("device-B")
        val c = node("device-C")

        CreateSafetyMessageUseCase(
            a.repository,
            MessagePolicy(clock),
            clock,
            "device-A",
            MessageIdGenerator { "scf-product-1" },
        )(SafetyState.SAFE, 0, "north shelter", "product path")

        val drill = RelayRuntimeSettings(OperatingMode.DRILL)
        a.coordinator.start(drill)
        b.coordinator.start(drill)
        c.coordinator.start(RelayRuntimeSettings(OperatingMode.DRILL, DeviceRole.GATEWAY))

        a.transport.connect("device-B")
        withTimeout(8_000) {
            while (a.repository.let { b.repository.find("scf-product-1") } == null) yield()
        }
        assertEquals(1, b.repository.find("scf-product-1")!!.hopCount)

        a.transport.disconnect("device-B")
        b.transport.connect("device-C")
        withTimeout(8_000) {
            while (c.repository.find("scf-product-1") == null) yield()
        }
        assertEquals(2, c.repository.find("scf-product-1")!!.hopCount)
        withTimeout(8_000) {
            while (c.repository.receiptsFor("scf-product-1").none {
                    it.receiptType == ReceiptType.GATEWAY_RECEIVED_UNVERIFIED
                }
            ) {
                yield()
            }
        }

        b.transport.disconnect("device-C")
        c.transport.connect("device-B")
        withTimeout(8_000) {
            while (b.repository.receiptsFor("scf-product-1").none {
                    it.receiptType == ReceiptType.GATEWAY_RECEIVED_UNVERIFIED
                }
            ) {
                yield()
            }
        }
        c.transport.disconnect("device-B")
        b.transport.connect("device-A")
        withTimeout(8_000) {
            while (a.repository.receiptsFor("scf-product-1").none {
                    it.receiptType == ReceiptType.GATEWAY_RECEIVED_UNVERIFIED
                }
            ) {
                yield()
            }
        }

        val presentation = deriveDeliveryPresentation(a.repository.receiptsFor("scf-product-1"))
        val label = deliveryPresentationLabel(presentation)
        assertEquals(
            com.example.relay.domain.DeliveryPresentation.GATEWAY_RECEIVED_UNVERIFIED,
            presentation,
        )
        assertTrue(label.contains("未認証") || label.contains("未検証"))
        assertFalse(label.contains("公式"))
        assertFalse(label.contains("最終配信完了"))
        assertTrue(
            a.repository.receiptsFor("scf-product-1").any {
                it.receiptType == ReceiptType.GATEWAY_RECEIVED_UNVERIFIED
            },
        )
        // GATEWAY_RECEIVED must not appear from in-process gateway-role nearby path after downgrade.
        assertFalse(
            a.repository.receiptsFor("scf-product-1").any {
                it.receiptType == ReceiptType.GATEWAY_RECEIVED
            },
        )

        scope.cancel()
    }
}
