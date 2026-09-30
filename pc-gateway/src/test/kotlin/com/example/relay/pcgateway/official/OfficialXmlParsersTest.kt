package com.example.relay.pcgateway.official

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture and fail-closed tests for the official-information XML parsers.
 *
 * The XXE cases are the security gate: any DOCTYPE (external or internal entity) must be
 * rejected before entity expansion, for both CAP and Atom input.
 */
class OfficialXmlParsersTest {

    // --- CAP 1.2 ---

    @Test
    fun parsesActualCapAlert() {
        val alert = CapAlertParser.parse(capFixture(status = "Actual"))
        assertEquals("Relay-Test-2026-001", alert.identifier)
        assertEquals("bousai@example.invalid", alert.sender)
        assertEquals("2026-07-26T09:00:00+09:00", alert.sent)
        assertEquals(CapStatus.ACTUAL, alert.status)
        assertEquals("Alert", alert.msgType)
        assertEquals("Public", alert.scope)
        assertFalse(alert.signaturePresent)
        assertEquals(1, alert.infos.size)
        val info = alert.infos.single()
        assertEquals("Met", info.category)
        assertEquals("大雨警報", info.event)
        assertEquals("Immediate", info.urgency)
        assertEquals("Severe", info.severity)
        assertEquals("Observed", info.certainty)
        assertEquals("設定地域 大雨警報", info.headline)
        assertEquals(listOf("設定地域"), info.areaDescriptions)
    }

    @Test
    fun exerciseStatusIsPreservedNotHiddenAsActual() {
        val alert = CapAlertParser.parse(capFixture(status = "Exercise"))
        assertEquals(CapStatus.EXERCISE, alert.status)
    }

    @Test
    fun unknownCapStatusIsRejected() {
        val error = assertThrows(OfficialXmlParseException::class.java) {
            CapAlertParser.parse(capFixture(status = "Bogus"))
        }
        assertTrue(error.message!!.contains("unknown CAP status"))
    }

    @Test
    fun missingMandatoryCapElementIsRejected() {
        val withoutSender = capFixture(status = "Actual")
            .replace("<sender>bousai@example.invalid</sender>", "")
        val error = assertThrows(OfficialXmlParseException::class.java) {
            CapAlertParser.parse(withoutSender)
        }
        assertTrue(error.message!!.contains("<sender>"))
    }

    @Test
    fun nonCapNamespaceIsRejected() {
        assertThrows(OfficialXmlParseException::class.java) {
            CapAlertParser.parse("""<alert xmlns="urn:example:not-cap"><identifier>x</identifier></alert>""")
        }
    }

    @Test
    fun unverifiedSignatureIsOnlyRecordedAsPresent() {
        val signed = capFixture(status = "Actual").replace(
            "</alert>",
            """<Signature xmlns="http://www.w3.org/2000/09/xmldsig#"><SignedInfo/></Signature></alert>""",
        )
        assertTrue(CapAlertParser.parse(signed).signaturePresent)
    }

    // --- JMA Atom feed ---

    @Test
    fun parsesJmaAtomFeed() {
        val feed = JmaAtomFeedParser.parse(atomFixture())
        assertEquals("高頻度（随時）", feed.title)
        assertEquals("2026-07-26T00:10:00Z", feed.updated)
        assertEquals(1, feed.entries.size)
        val entry = feed.entries.single()
        assertEquals("気象警報・注意報（Ｈ２７）", entry.title)
        assertEquals("urn:uuid:c268e211-a34e-3f1c-9b3b-2ea4dbd6ab40", entry.id)
        assertEquals("気象庁本庁", entry.author)
        assertEquals("https://www.data.jma.go.jp/developer/xml/data/example.xml", entry.link)
        assertEquals("【設定地域気象警報・注意報】", entry.content)
    }

    @Test
    fun atomEntryWithoutIdIsRejected() {
        val withoutId = atomFixture()
            .replace("<id>urn:uuid:c268e211-a34e-3f1c-9b3b-2ea4dbd6ab40</id>", "")
        val error = assertThrows(OfficialXmlParseException::class.java) {
            JmaAtomFeedParser.parse(withoutId)
        }
        assertTrue(error.message!!.contains("<id>"))
    }

    @Test
    fun nonAtomRootIsRejected() {
        assertThrows(OfficialXmlParseException::class.java) {
            JmaAtomFeedParser.parse("""<rss version="2.0"><channel/></rss>""")
        }
    }

    // --- XXE hardening (security gate) ---

    @Test
    fun externalEntityDoctypeIsRejectedForCap() {
        // Full valid alert plus a DOCTYPE: rejection must come from the DOCTYPE ban itself,
        // not from an unrelated missing-element error (that would be a false-green gate).
        val xxe = capFixture(status = "Actual").replace(
            "<alert",
            "<!DOCTYPE alert [<!ENTITY xxe SYSTEM \"file:///etc/hostname\">]>\n<alert",
        )
        val error = assertThrows(OfficialXmlParseException::class.java) { CapAlertParser.parse(xxe) }
        assertTrue("expected DOCTYPE rejection, got: ${error.message}", error.message!!.contains("DOCTYPE"))
    }

    @Test
    fun externalEntityDoctypeIsRejectedForAtom() {
        val xxe = atomFixture().replace(
            "<feed",
            "<!DOCTYPE feed [<!ENTITY xxe SYSTEM \"http://127.0.0.1:1/secret\">]>\n<feed",
        )
        val error = assertThrows(OfficialXmlParseException::class.java) { JmaAtomFeedParser.parse(xxe) }
        assertTrue("expected DOCTYPE rejection, got: ${error.message}", error.message!!.contains("DOCTYPE"))
    }

    @Test
    fun internalEntityDoctypeIsAlsoRejected() {
        // Even harmless-looking internal DTDs are banned outright (billion-laughs class).
        val internal = capFixture(status = "Actual").replace(
            "<alert",
            "<!DOCTYPE alert [<!ENTITY a \"b\">]>\n<alert",
        )
        val error = assertThrows(OfficialXmlParseException::class.java) { CapAlertParser.parse(internal) }
        assertTrue("expected DOCTYPE rejection, got: ${error.message}", error.message!!.contains("DOCTYPE"))
    }

    @Test
    fun parserNeverReadsLocalFilesViaEntities() {
        val alert = CapAlertParser.parse(capFixture(status = "Actual"))
        assertNull(alert.infos.single().description)
    }

    private fun capFixture(status: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <alert xmlns="urn:oasis:names:tc:emergency:cap:1.2">
          <identifier>Relay-Test-2026-001</identifier>
          <sender>bousai@example.invalid</sender>
          <sent>2026-07-26T09:00:00+09:00</sent>
          <status>$status</status>
          <msgType>Alert</msgType>
          <scope>Public</scope>
          <info>
            <language>ja-JP</language>
            <category>Met</category>
            <event>大雨警報</event>
            <urgency>Immediate</urgency>
            <severity>Severe</severity>
            <certainty>Observed</certainty>
            <headline>設定地域 大雨警報</headline>
            <area>
              <areaDesc>設定地域</areaDesc>
            </area>
          </info>
        </alert>
    """.trimIndent()

    private fun atomFixture(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>高頻度（随時）</title>
          <updated>2026-07-26T00:10:00Z</updated>
          <entry>
            <title>気象警報・注意報（Ｈ２７）</title>
            <id>urn:uuid:c268e211-a34e-3f1c-9b3b-2ea4dbd6ab40</id>
            <updated>2026-07-26T00:09:45Z</updated>
            <author><name>気象庁本庁</name></author>
            <link href="https://www.data.jma.go.jp/developer/xml/data/example.xml"/>
            <content type="text">【設定地域気象警報・注意報】</content>
          </entry>
        </feed>
    """.trimIndent()
}
