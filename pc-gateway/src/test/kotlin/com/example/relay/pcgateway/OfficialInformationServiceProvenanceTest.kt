package com.example.relay.pcgateway

import com.example.relay.pcgateway.official.OfficialInfoRetrieval
import com.example.relay.pcgateway.official.OfficialInfoVerification
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Provenance-state tests for [OfficialInformationService]: each retrieval path must report
 * an honest verification state and the exact digest of the document actually used.
 */
class OfficialInformationServiceProvenanceTest {

    @get:Rule val temp = TemporaryFolder()

    private val warningJson = """
        {"reportDatetime":"2026-07-26T09:00:00+09:00","headlineText":"大雨警報を発表",
         "areaTypes":[{"areas":[{"code":"0000000","warnings":[{"code":"03","status":"発表"}]}]}]}
    """.trimIndent()

    private fun cachePath(): Path = temp.newFolder("official").toPath().resolve("jma.json")

    @Test
    fun liveFetchIsAtMostTransportVerified() {
        val cache = cachePath()
        val service = OfficialInformationService(cache, fetcher = { warningJson })
        val response = service.current(now = 1_000_000L)
        assertEquals(OfficialInfoRetrieval.LIVE_FETCH, response.provenance.retrieval)
        // Never a stronger claim than TLS: JMA content carries no signature.
        assertEquals(OfficialInfoVerification.TRANSPORT_TLS_ONLY, response.provenance.verification)
        assertEquals(1_000_000L, response.provenance.fetchedAtEpochMillis)
        assertEquals(sha256Hex(warningJson), response.provenance.contentSha256Hex)
        assertFalse(response.usedCachedWarning)
        assertTrue(response.urgent)
        assertTrue(Files.exists(cache))
        assertTrue(Files.exists(cache.resolveSibling("jma.json.meta.json")))
    }

    @Test
    fun cacheReplayIsExplicitlyUnverifiedAndKeepsOriginalFetchTime() {
        val cache = cachePath()
        val healthy = OfficialInformationService(cache, fetcher = { warningJson })
        healthy.current(now = 1_000_000L)
        // New instance (no in-memory state), origin down: must fall back to the cache.
        val offline = OfficialInformationService(cache, fetcher = { error("origin unreachable") })
        val response = offline.current(now = 9_000_000L)
        assertEquals(OfficialInfoRetrieval.LOCAL_CACHE, response.provenance.retrieval)
        assertEquals(OfficialInfoVerification.CACHED_UNVERIFIED, response.provenance.verification)
        assertEquals(1_000_000L, response.provenance.fetchedAtEpochMillis)
        assertEquals(sha256Hex(warningJson), response.provenance.contentSha256Hex)
        assertTrue(response.usedCachedWarning)
        assertTrue(response.urgent)
    }

    @Test
    fun tamperedCacheLosesItsFetchTimeButStaysUnverified() {
        val cache = cachePath()
        OfficialInformationService(cache, fetcher = { warningJson }).current(now = 1_000_000L)
        // Simulate on-disk tampering after the metadata sidecar was written.
        Files.writeString(cache, warningJson.replace("発表", "解除"))
        val offline = OfficialInformationService(cache, fetcher = { error("origin unreachable") })
        val response = offline.current(now = 9_000_000L)
        assertEquals(OfficialInfoVerification.CACHED_UNVERIFIED, response.provenance.verification)
        // Digest mismatch: the recorded fetch time no longer belongs to this content.
        assertNull(response.provenance.fetchedAtEpochMillis)
        assertFalse(response.urgent)
    }

    @Test
    fun nothingAvailableIsNeverPresentedAsVerified() {
        val cache = cachePath()
        val service = OfficialInformationService(cache, fetcher = { error("origin unreachable") })
        val response = service.current(now = 1_000_000L)
        assertEquals(OfficialInfoRetrieval.UNAVAILABLE, response.provenance.retrieval)
        assertEquals(OfficialInfoVerification.UNVERIFIED, response.provenance.verification)
        assertNull(response.provenance.fetchedAtEpochMillis)
        assertNull(response.provenance.contentSha256Hex)
        assertFalse(response.urgent)
        assertEquals("気象庁の警報情報を取得できませんでした", response.warningHeadline)
    }

    @Test
    fun throttledRepeatReturnsTheSameProvenance() {
        val cache = cachePath()
        var fetches = 0
        val service = OfficialInformationService(cache, fetcher = { fetches++; warningJson })
        val first = service.current(now = 1_000_000L)
        val second = service.current(now = 1_000_000L + 60_000L)
        assertEquals(1, fetches)
        assertEquals(first.provenance, second.provenance)
    }

    private fun sha256Hex(content: String): String =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
