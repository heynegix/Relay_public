package com.example.relay.pcgateway

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PilotOperationsTest {
    private fun store() = PilotOperationsStore(Files.createTempFile("relay-pilot-ops", ".db").toString())

    @Test fun `device observation is never person confirmation`() = store().use { store ->
        store.saveBaseline(BaselineSubjectInput("demo-a", supportFlags = setOf(SupportFlag.MOBILITY), reviewDueAtEpochMillis = Long.MAX_VALUE))
        store.addObservation(ObservationInput("demo-a", ObservationType.DEVICE_OBSERVED), SourceChannel.NEARBY, IngressAssurance.UNVERIFIED, 100)
        val item = store.reviewQueue(101).single()
        assertEquals("端末観測のみ・本人未確認", item.state)
        assertEquals("中", item.priority)
    }

    @Test fun `observation-only subject remains in review queue`() = store().use { store ->
        store.addObservation(ObservationInput("new-token", ObservationType.THIRD_PARTY_REPORT), SourceChannel.STAFF_DESK, IngressAssurance.UNVERIFIED, 100)
        assertEquals("第三者情報あり・要確認", store.reviewQueue(101).single().state)
    }

    @Test fun `csv preview and rejected import preserve existing data`() = store().use { store ->
        val valid = "subject_token,group_id,support_mobility,support_power,children_present,review_due_at\ndemo-a,g,true,false,false,2026-12-31\n"
        assertEquals(1, store.importCsv(CsvImportRequest("baseline", valid, dryRun = false)).validRows)
        val invalid = "subject_token,group_id,support_mobility,support_power,children_present,review_due_at\ndemo-b,g,true,false,false,not-a-date\n"
        val result = store.importCsv(CsvImportRequest("baseline", invalid, dryRun = false))
        assertEquals(1, result.rejectedRows)
        assertEquals(listOf("demo-a"), store.reviewQueue().map { it.subjectToken })
    }

    @Test fun `csv rejects misspelled support boolean`() = store().use { store ->
        val csv = "subject_token,group_id,support_mobility,support_power,children_present,review_due_at\ndemo-a,g,treu,false,false,2026-12-31\n"
        assertEquals(1, store.previewCsv("baseline", csv, dryRun = true).rejectedRows)
    }

    @Test fun `expired and revoked support profiles are not current facts`() = store().use { store ->
        store.saveProfile(SupportProfileInput("demo-a", supportFlags = setOf(SupportFlag.POWER), reviewDueAtEpochMillis = 10))
        assertTrue(store.reviewQueue(11).single().profileExpired)
        store.revokeProfile("demo-a", 12)
        assertTrue(store.profiles().single().revokedAtEpochMillis != null)
        assertFalse(store.reviewQueue(13).any { it.subjectToken == "demo-a" })
    }

    @Test fun `route receipt remains distinct from transport success`() = store().use { store ->
        store.recordRouteAttempt(RouteAttempt("a", "env-a", RouteType.HTTPS_BROKER, 1, 2, RouteResult.BROKER_STORED))
        store.recordRouteAttempt(RouteAttempt("b", "env-a", RouteType.LAN_GATEWAY, 3, 4, RouteResult.RECEIPT_CONFIRMED, receiptId = "receipt-a"))
        assertEquals(setOf(RouteResult.BROKER_STORED, RouteResult.RECEIPT_CONFIRMED), store.routeAttempts("env-a").map { it.result }.toSet())
    }
}
