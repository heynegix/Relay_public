package com.example.relay.background

import com.example.relay.domain.DeviceRole
import com.example.relay.domain.OperatingMode
import com.example.relay.domain.RelayRuntimeSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proves the owner/lease guarantees: single shared runtime start regardless of owner count,
 * idempotent acquire, safe release, and that one owner releasing never stops another owner's
 * communication (single Nearby transport / Advertising / Discovery / Gateway sync).
 */
class CommunicationLeaseManagerTest {

    private val settings = RelayRuntimeSettings(OperatingMode.RELAY, DeviceRole.MEMBER)

    private class FakeRuntime {
        val startCount = AtomicInteger(0)
        val stopCount = AtomicInteger(0)
        var startResult = true
        fun manager() = CommunicationLeaseManager(
            start = { startCount.incrementAndGet(); startResult },
            stop = { stopCount.incrementAndGet() },
        )
    }

    @Test
    fun `first acquire starts the runtime exactly once`() = runBlocking {
        val fake = FakeRuntime()
        val manager = fake.manager()
        assertEquals(LeaseResult.STARTED, manager.acquire(CommunicationOwner.USER_COMMUNICATION, settings))
        assertEquals(1, fake.startCount.get())
    }

    @Test
    fun `duplicate acquire by same owner is idempotent and does not restart`() = runBlocking {
        val fake = FakeRuntime()
        val manager = fake.manager()
        manager.acquire(CommunicationOwner.USER_COMMUNICATION, settings)
        val second = manager.acquire(CommunicationOwner.USER_COMMUNICATION, settings)
        assertEquals(LeaseResult.ALREADY_ACTIVE, second)
        assertEquals(1, fake.startCount.get())
    }

    @Test
    fun `second owner joins existing session without a second start`() = runBlocking {
        val fake = FakeRuntime()
        val manager = fake.manager()
        manager.acquire(CommunicationOwner.USER_COMMUNICATION, settings)
        val emergency = manager.acquire(CommunicationOwner.EMERGENCY_MODE, settings)
        assertEquals(LeaseResult.ALREADY_ACTIVE, emergency)
        assertEquals(1, fake.startCount.get())
        assertEquals(setOf(CommunicationOwner.USER_COMMUNICATION, CommunicationOwner.EMERGENCY_MODE), manager.activeOwners())
    }

    @Test
    fun `releasing one owner keeps the runtime for the remaining owner`() = runBlocking {
        val fake = FakeRuntime()
        val manager = fake.manager()
        manager.acquire(CommunicationOwner.USER_COMMUNICATION, settings)
        manager.acquire(CommunicationOwner.EMERGENCY_MODE, settings)

        val release = manager.release(CommunicationOwner.USER_COMMUNICATION)
        assertEquals(LeaseResult.STILL_ACTIVE, release)
        assertEquals(0, fake.stopCount.get())
        assertTrue(manager.isActive())

        val last = manager.release(CommunicationOwner.EMERGENCY_MODE)
        assertEquals(LeaseResult.STOPPED, last)
        assertEquals(1, fake.stopCount.get())
        assertFalse(manager.isActive())
    }

    @Test
    fun `releasing an owner that never acquired is a safe no-op`() = runBlocking {
        val fake = FakeRuntime()
        val manager = fake.manager()
        assertEquals(LeaseResult.NOT_HELD, manager.release(CommunicationOwner.EMERGENCY_MODE))
        assertEquals(0, fake.stopCount.get())
    }

    @Test
    fun `double release is safe`() = runBlocking {
        val fake = FakeRuntime()
        val manager = fake.manager()
        manager.acquire(CommunicationOwner.EMERGENCY_MODE, settings)
        assertEquals(LeaseResult.STOPPED, manager.release(CommunicationOwner.EMERGENCY_MODE))
        assertEquals(LeaseResult.NOT_HELD, manager.release(CommunicationOwner.EMERGENCY_MODE))
        assertEquals(1, fake.stopCount.get())
    }

    @Test
    fun `failed start holds no lease`() = runBlocking {
        val fake = FakeRuntime().apply { startResult = false }
        val manager = fake.manager()
        assertEquals(LeaseResult.START_FAILED, manager.acquire(CommunicationOwner.USER_COMMUNICATION, settings))
        assertFalse(manager.isActive())
    }

    @Test
    fun `releaseAll drops every owner and stops once`() = runBlocking {
        val fake = FakeRuntime()
        val manager = fake.manager()
        manager.acquire(CommunicationOwner.USER_COMMUNICATION, settings)
        manager.acquire(CommunicationOwner.EMERGENCY_MODE, settings)
        assertEquals(LeaseResult.STOPPED, manager.releaseAll())
        assertEquals(1, fake.stopCount.get())
        assertFalse(manager.isActive())
    }
}
