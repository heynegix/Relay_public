package com.example.relay.location

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationProviderTest {
    @Test
    fun `formatGpsLocation is stable and bounded`() {
        val text = formatGpsLocation(GeoFix(0.123456, 0.654321, 12f), maxChars = 100)
        assertTrue(text.startsWith("GPS:"))
        assertTrue(text.contains("0.12346") || text.contains("0.12345"))
        assertTrue(text.length <= 100)
    }

    @Test
    fun `resolveReportLocation keeps manual text and does not call provider`() = runBlocking {
        var called = false
        val provider = LocationProvider {
            called = true
            GeoFix(1.0, 2.0)
        }
        val resolved = resolveReportLocation("  shelter A  ", provider)
        assertEquals("shelter A", resolved)
        assertEquals(false, called)
    }

    @Test
    fun `resolveReportLocation uses GPS when manual blank`() = runBlocking {
        val provider = FixedLocationProvider(GeoFix(0.0, 0.0, 8f))
        val resolved = resolveReportLocation("  ", provider)
        assertTrue(resolved.startsWith("GPS:"))
        assertTrue(resolved.contains("0.00000"))
    }

    @Test
    fun `resolveReportLocation empty when GPS unavailable`() = runBlocking {
        val resolved = resolveReportLocation("", NullLocationProvider)
        assertEquals("", resolved)
    }

    @Test
    fun `pickLocationFix prefers fresh last-known over one-shot`() {
        val last = GeoFix(1.0, 2.0)
        val shot = GeoFix(3.0, 4.0)
        assertEquals(last, pickLocationFix(last, lastKnownFresh = true, oneShot = shot))
    }

    @Test
    fun `pickLocationFix uses one-shot when last-known missing or stale`() {
        val shot = GeoFix(3.0, 4.0)
        assertEquals(shot, pickLocationFix(null, lastKnownFresh = false, oneShot = shot))
        assertEquals(shot, pickLocationFix(GeoFix(1.0, 2.0), lastKnownFresh = false, oneShot = shot))
    }

    @Test
    fun `pickLocationFix falls back to stale last-known when one-shot null`() {
        val last = GeoFix(1.0, 2.0)
        assertEquals(last, pickLocationFix(last, lastKnownFresh = false, oneShot = null))
    }
}
