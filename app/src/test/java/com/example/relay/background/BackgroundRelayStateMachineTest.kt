package com.example.relay.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the required state-transition matrix for the background relay feature at the pure-logic
 * level (no Android, no Nearby, no emulator), so these transitions are deterministic and CI-safe.
 */
class BackgroundRelayStateMachineTest {

    private val sm = BackgroundRelayStateMachine

    @Test
    fun `DISABLED to ARMED on explicit opt-in`() {
        val next = sm.optIn(BackgroundRelayState())
        assertEquals(BackgroundRelayMode.ARMED, next.mode)
        assertTrue(next.optedIn)
        assertFalse(next.explicitlyStoppedByUser)
    }

    @Test
    fun `ARMED to EMERGENCY_ACTIVE on user activation`() {
        val armed = sm.optIn(BackgroundRelayState())
        val next = sm.activate(armed, ActivationSource.USER_ACTION)
        assertEquals(BackgroundRelayMode.EMERGENCY_ACTIVE, next.mode)
        assertTrue(next.emergencyActive)
        assertEquals(ActivationSource.USER_ACTION, next.activationSource)
    }

    @Test
    fun `rescue creation auto-activates disaster mode`() {
        val next = sm.activate(BackgroundRelayState(), ActivationSource.RESCUE_CREATED)
        assertEquals(BackgroundRelayMode.EMERGENCY_ACTIVE, next.mode)
        assertEquals(ActivationSource.RESCUE_CREATED, next.activationSource)
    }

    @Test
    fun `rescue reception auto-activates disaster mode`() {
        val next = sm.activate(BackgroundRelayState(), ActivationSource.RESCUE_RECEIVED)
        assertEquals(BackgroundRelayMode.EMERGENCY_ACTIVE, next.mode)
        assertEquals(ActivationSource.RESCUE_RECEIVED, next.activationSource)
    }

    @Test
    fun `bluetooth off degrades a running emergency but keeps it wanted`() {
        val active = sm.activate(BackgroundRelayState(), ActivationSource.USER_ACTION)
        val degraded = sm.degrade(active, DegradeReason.BLUETOOTH_DISABLED)
        assertEquals(BackgroundRelayMode.DEGRADED, degraded.mode)
        assertEquals(DegradeReason.BLUETOOTH_DISABLED, degraded.degradeReason)
        assertTrue(degraded.emergencyActive)
    }

    @Test
    fun `bluetooth on recovers from degrade back to emergency`() {
        val active = sm.activate(BackgroundRelayState(), ActivationSource.USER_ACTION)
        val degraded = sm.degrade(active, DegradeReason.BLUETOOTH_DISABLED)
        val recovered = sm.recoverFromDegrade(degraded)
        assertEquals(BackgroundRelayMode.EMERGENCY_ACTIVE, recovered.mode)
        assertEquals(DegradeReason.NONE, recovered.degradeReason)
    }

    @Test
    fun `armed does not degrade because it is not running`() {
        val armed = sm.optIn(BackgroundRelayState())
        val next = sm.degrade(armed, DegradeReason.BLUETOOTH_DISABLED)
        assertEquals(BackgroundRelayMode.ARMED, next.mode)
    }

    @Test
    fun `user stop is sticky and non-user sources cannot reactivate`() {
        val active = sm.activate(BackgroundRelayState(), ActivationSource.RESCUE_CREATED)
        val stopped = sm.suspendByUser(active)
        assertEquals(BackgroundRelayMode.SUSPENDED_BY_USER, stopped.mode)
        assertTrue(stopped.explicitlyStoppedByUser)

        val bootAttempt = sm.activate(stopped, ActivationSource.BOOT_RESTORE)
        assertEquals(BackgroundRelayMode.SUSPENDED_BY_USER, bootAttempt.mode)
        assertFalse(bootAttempt.emergencyActive)

        val btAttempt = sm.activate(stopped, ActivationSource.BLUETOOTH_RESTORED)
        assertFalse(btAttempt.emergencyActive)
    }

    @Test
    fun `explicit user action can re-activate after a user stop`() {
        val stopped = sm.suspendByUser(sm.activate(BackgroundRelayState(), ActivationSource.RESCUE_CREATED))
        val revived = sm.activate(stopped, ActivationSource.USER_ACTION)
        assertEquals(BackgroundRelayMode.EMERGENCY_ACTIVE, revived.mode)
        assertFalse(revived.explicitlyStoppedByUser)
    }

    @Test
    fun `restore decision restores when emergency was persisted`() {
        val active = sm.activate(BackgroundRelayState(), ActivationSource.USER_ACTION)
        val decision = restoreDecision(active, hasUndeliveredRescue = false, source = ActivationSource.BOOT_RESTORE)
        assertTrue(decision.shouldRestore)
        assertEquals("emergency_active_persisted", decision.reason)
    }

    @Test
    fun `restore decision restores when undelivered rescue exists`() {
        val armed = sm.optIn(BackgroundRelayState())
        val decision = restoreDecision(armed, hasUndeliveredRescue = true, source = ActivationSource.BOOT_RESTORE)
        assertTrue(decision.shouldRestore)
        assertEquals("undelivered_rescue", decision.reason)
    }

    @Test
    fun `restore decision refuses after an explicit user stop`() {
        val stopped = sm.suspendByUser(sm.activate(BackgroundRelayState(), ActivationSource.USER_ACTION))
        val decision = restoreDecision(stopped, hasUndeliveredRescue = true, source = ActivationSource.BOOT_RESTORE)
        assertFalse(decision.shouldRestore)
        assertEquals("user_explicitly_stopped", decision.reason)
    }

    @Test
    fun `armed alone does not restore after reboot`() {
        val armed = sm.optIn(BackgroundRelayState())
        val decision = restoreDecision(armed, hasUndeliveredRescue = false, source = ActivationSource.BOOT_RESTORE)
        assertFalse(decision.shouldRestore)
        assertEquals("armed_only_no_trigger", decision.reason)
    }

    @Test
    fun `start failure backs off exponentially and healthy start resets it`() {
        var state = BackgroundRelayState()
        state = sm.recordStartFailure(state, "e1", nowEpochMillis = 0, baseBackoffMillis = 100, maxBackoffMillis = 10_000)
        assertEquals(1, state.consecutiveStartFailures)
        assertEquals(100, state.nextRetryAllowedAtEpochMillis)
        state = sm.recordStartFailure(state, "e2", nowEpochMillis = 0, baseBackoffMillis = 100, maxBackoffMillis = 10_000)
        assertEquals(2, state.consecutiveStartFailures)
        assertEquals(200, state.nextRetryAllowedAtEpochMillis)
        assertFalse(sm.canRetryNow(state, nowEpochMillis = 100))
        assertTrue(sm.canRetryNow(state, nowEpochMillis = 200))

        val healthy = sm.recordHealthyStart(state, nowEpochMillis = 500)
        assertEquals(0, healthy.consecutiveStartFailures)
        assertEquals(0, healthy.nextRetryAllowedAtEpochMillis)
    }

    @Test
    fun `deactivate falls back to armed when still opted in`() {
        val active = sm.activate(sm.optIn(BackgroundRelayState()), ActivationSource.USER_ACTION)
        val next = sm.deactivate(active)
        assertEquals(BackgroundRelayMode.ARMED, next.mode)
        assertFalse(next.emergencyActive)
    }
}
