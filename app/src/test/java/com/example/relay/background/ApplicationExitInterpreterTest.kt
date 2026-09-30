package com.example.relay.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies ApplicationExitInfo interpretation. The critical rule: the exit reason alone never marks
 * the feature as permanently user-stopped — only the persisted explicit-stop flag does.
 */
class ApplicationExitInterpreterTest {

    @Test
    fun `null record yields no interpretation`() {
        assertNull(ApplicationExitInterpreter.interpret(null))
    }

    @Test
    fun `user requested is labelled and flagged user-initiated but not abnormal`() {
        val i = ApplicationExitInterpreter.interpret(
            ProcessExitRecord(ApplicationExitInterpreter.REASON_USER_REQUESTED, 1_000L),
        )!!
        assertEquals("user_requested", i.label)
        assertTrue(i.userInitiated)
        assertFalse(i.abnormal)
    }

    @Test
    fun `crash is abnormal`() {
        val i = ApplicationExitInterpreter.interpret(
            ProcessExitRecord(ApplicationExitInterpreter.REASON_CRASH, 1_000L),
        )!!
        assertEquals("crash", i.label)
        assertTrue(i.abnormal)
        assertFalse(i.userInitiated)
    }

    @Test
    fun `user requested exit alone does NOT force a permanent user stop`() {
        val i = ApplicationExitInterpreter.interpret(
            ProcessExitRecord(ApplicationExitInterpreter.REASON_USER_REQUESTED, 1_000L),
        )
        // The persisted flag is false, so despite a USER_REQUESTED exit we must not treat it as a stop.
        assertFalse(ApplicationExitInterpreter.shouldTreatAsUserStop(explicitStopFlag = false, interpretation = i))
    }

    @Test
    fun `explicit persisted stop flag is authoritative`() {
        val i = ApplicationExitInterpreter.interpret(
            ProcessExitRecord(ApplicationExitInterpreter.REASON_LOW_MEMORY, 1_000L),
        )
        assertTrue(ApplicationExitInterpreter.shouldTreatAsUserStop(explicitStopFlag = true, interpretation = i))
    }

    @Test
    fun `unknown reason code degrades to unknown label`() {
        val i = ApplicationExitInterpreter.interpret(ProcessExitRecord(9999, 1_000L))!!
        assertEquals("unknown", i.label)
    }
}
