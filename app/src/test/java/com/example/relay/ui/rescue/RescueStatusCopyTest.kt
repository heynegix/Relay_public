package com.example.relay.ui.rescue

import com.example.relay.rescue.RescueSubmissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the consolidated rescue status copy (RescueStatusCopy.kt).
 *
 * These tests guard the wording rules from the copy refresh: no false delivery guarantees,
 * no raw enum names on screen, a single terminal set, and trust-level-appropriate phrasing.
 */
class RescueStatusCopyTest {
    private val all = RescueSubmissionStatus.entries

    private fun RescueSubmissionStatus.strings() = statusCopy().let {
        listOf(it.shortLabelJa, it.shortLabelEn, it.descriptionJa, it.descriptionEn)
    }

    @Test
    fun `every status has non-blank bilingual short label and description`() {
        all.forEach { status ->
            val copy = status.statusCopy()
            assertTrue("$status short ja blank", copy.shortLabelJa.isNotBlank())
            assertTrue("$status short en blank", copy.shortLabelEn.isNotBlank())
            assertTrue("$status desc ja blank", copy.descriptionJa.isNotBlank())
            assertTrue("$status desc en blank", copy.descriptionEn.isNotBlank())
        }
    }

    @Test
    fun `copy never exposes the raw enum name`() {
        all.forEach { status ->
            status.strings().forEach { text ->
                assertFalse("$status leaks enum name: $text", text.contains(status.name))
            }
        }
    }

    @Test
    fun `terminal set matches the delivery state machine`() {
        val terminal = all.filter { it.statusCopy().isTerminal }.toSet()
        assertEquals(
            setOf(
                RescueSubmissionStatus.SHELTER_COMPLETED,
                RescueSubmissionStatus.CANCELLED,
                RescueSubmissionStatus.SHELTER_REJECTED,
            ),
            terminal,
        )
    }

    @Test
    fun `no status overclaims official or guaranteed rescue`() {
        val forbidden = listOf("公式到達", "最終配信完了", "救助が保証", "必ず中継", "継続して現在地")
        all.forEach { status ->
            status.strings().forEach { text ->
                forbidden.forEach { phrase ->
                    assertFalse("$status must not contain '$phrase'", text.contains(phrase))
                }
            }
        }
    }

    @Test
    fun `device-only status stays on this device and claims no relay`() {
        val copy = RescueSubmissionStatus.PENDING_DESTINATION.statusCopy()
        assertEquals(RescueDeliveryTrust.DEVICE_ONLY, copy.trustLevel)
        assertTrue(copy.descriptionJa.contains("この端末"))
        // Must not imply the SOS itself is already relaying or has arrived.
        assertFalse(copy.descriptionJa.contains("中継中"))
        assertFalse(copy.descriptionJa.contains("送信済み"))
    }

    @Test
    fun `relaying states never claim shelter acceptance`() {
        listOf(RescueSubmissionStatus.PENDING, RescueSubmissionStatus.IN_TRANSIT).forEach { status ->
            val copy = status.statusCopy()
            assertEquals("$status trust", RescueDeliveryTrust.RELAYING, copy.trustLevel)
            assertFalse("$status claims acceptance", copy.descriptionJa.contains("受領"))
            assertFalse("$status claims completion", copy.descriptionJa.contains("完了しました"))
        }
    }

    @Test
    fun `shelter stored copy makes the signed receipt explicit`() {
        val copy = RescueSubmissionStatus.SHELTER_STORED.statusCopy()
        assertEquals(RescueDeliveryTrust.SHELTER_STORED, copy.trustLevel)
        assertTrue(copy.descriptionJa.contains("署名"))
    }

    @Test
    fun `short label and description follow the requested language`() {
        val copy = RescueSubmissionStatus.SHELTER_RESPONDING.statusCopy()
        assertEquals(copy.shortLabelJa, copy.shortLabel(RescueLanguage.JAPANESE))
        assertEquals(copy.shortLabelEn, copy.shortLabel(RescueLanguage.ENGLISH))
        assertEquals(copy.descriptionJa, copy.description(RescueLanguage.JAPANESE))
        assertEquals(copy.descriptionEn, copy.description(RescueLanguage.ENGLISH))
    }
}
