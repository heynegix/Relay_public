package com.example.relay.pcgateway

import com.example.relay.gateway.protocol.GatewayMessage
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardUiTest {
    private fun message(id: String = "dash-1") = GatewayMessage(
        messageId = id,
        messageType = "SAFETY",
        recordType = "REPORT",
        priority = "HIGH",
        status = "ACTIVE",
        createdAt = 1_000,
        expiresAt = 86_401_000,
        lifetimeMs = 86_400_000,
        accumulatedAgeMs = 0,
        hopCount = 1,
        hopLimit = 8,
        originDeviceId = "origin",
        payload = JsonPrimitive("safe"),
        receivedAt = 1_000,
    )

    @Test
    fun `staff response labels are centralised and separate rescue request from external dispatch`() {
        val script = requireNotNull(javaClass.classLoader.getResource("web/app.js")).readText()
        // All eight staff response states must resolve to a Japanese label (not the raw enum).
        listOf(
            "UNCONFIRMED", "CONFIRMED", "PREPARING", "RESCUE_REQUESTED",
            "RESPONDING", "COMPLETED", "UNABLE", "DUPLICATE",
        ).forEach { assertTrue("missing label for $it", script.contains("$it:")) }
        // RESCUE_REQUESTED must not claim an external fire/rescue dispatch actually happened.
        assertTrue(script.contains("外部連携未確認"))
        assertFalse(script.contains("救助隊を要請済み"))
    }

    @Test
    fun `settings screen names the signed in operator instead of the PC`() {
        val markup = requireNotNull(javaClass.classLoader.getResource("web/index.html")).readText()
        // assignedNodeId / nodeId() is a staff username, so the console must not call it "this PC".
        assertFalse(markup.contains("このPC"))
        assertTrue(markup.contains("サインイン中の運用者"))
        assertTrue(markup.contains("最初に確認した運用者が担当"))
        assertFalse(markup.contains("最初に確認したPCが担当"))
    }

    @Test
    fun `console shows build profile and hides raw errors from normal screens`() {
        val markup = requireNotNull(javaClass.classLoader.getResource("web/index.html")).readText()
        val script = requireNotNull(javaClass.classLoader.getResource("web/app.js")).readText()
        // Operation mode / build profile must be surfaced to staff.
        assertTrue(markup.contains("運用モード"))
        assertTrue(markup.contains("id=\"settingsProfile\""))
        assertTrue(script.contains("renderOperationMode"))
        assertTrue(script.contains("health.profile"))
        assertTrue(script.contains("開発プレビュー"))
        assertTrue(script.contains("正式運用"))
        // A concurrency conflict must be phrased for an operator, not "another PC".
        assertTrue(script.contains("別の運用者が先に担当"))
        assertFalse(script.contains("別のPCが先に担当"))
    }

    @Test
    fun `gateway store is not presented as a signed shelter receipt`() {
        // The dashboard summary must keep gateway-stored content marked unverified, never "official".
        val script = requireNotNull(javaClass.classLoader.getResource("web/app.js")).readText()
        assertFalse(script.contains("公式到達"))
        assertFalse(script.contains("救助が保証"))
    }

    @Test
    fun `rescue list escapes user supplied location before inserting markup`() {
        val script = requireNotNull(javaClass.classLoader.getResource("web/app.js")).readText()
        val unsafeExpression = "$" + "{request.locationDescription || \"GPS位置あり\"}"
        val escapedExpression = "$" + "{escapeHtml(request.locationDescription || \"GPS位置あり\")}"

        assertFalse(script.contains(unsafeExpression))
        assertTrue(script.contains(escapedExpression))
    }

    @Test
    fun `offline map supports zoom pan keyboard and touch without online map code`() {
        val script = requireNotNull(javaClass.classLoader.getResource("web/app.js")).readText()
        val markup = requireNotNull(javaClass.classLoader.getResource("web/index.html")).readText()
        val styles = requireNotNull(javaClass.classLoader.getResource("web/app.css")).readText()

        assertTrue(script.contains("data-map-action=\"zoom-in\""))
        assertTrue(script.contains("addEventListener(\"wheel\""))
        assertTrue(script.contains("addEventListener(\"dblclick\""))
        assertTrue(script.contains("addEventListener(\"keydown\""))
        assertTrue(script.contains("addEventListener(\"pointermove\""))
        assertTrue(script.contains("Math.log2(distance / interaction.pinchDistance)"))
        assertTrue(script.contains("mapLimits.maxNativeZoom"))
        assertFalse(script.contains("https://cyberjapandata.gsi.go.jp"))
        assertTrue(markup.contains("マウスホイール、ダブルクリック、ピンチ、ドラッグ、キーボード"))
        assertTrue(styles.contains("touch-action: none"))
    }

    @Test
    fun `offline map status publishes native and overzoom limits`() {
        val root = Files.createTempDirectory("relay-map-status")
        GsiTileCache(root).use { cache ->
            val status = cache.status()
            assertEquals(13, status.minZoom)
            assertEquals(15, status.maxNativeZoom)
            assertEquals(18, status.maxZoom)
            assertEquals("not_ready", status.state)
            assertNull(cache.tile(status.maxZoom, 0, 0))
        }
    }

    @Test
    fun `dashboard requires authorization while static console stays public`() = testApplication {
        val config = GatewayConfig(
            profile = GatewayProfile.DEVELOPMENT,
            dbPath = Files.createTempFile("relay-ui", ".db").toString(),
            adminKey = "admin-secret",
            gatewayId = "gw-ui",
        )
        GatewayStore(config).use { store ->
            store.ingestUnregistered(listOf(message()), 2_000)
            application { gatewayModule(config, store) }

            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/dashboard").status)
            val dash = client.get("/api/dashboard") { header("X-Admin-Key", "admin-secret") }
            assertEquals(HttpStatusCode.OK, dash.status)
            val body = dash.bodyAsText()
            assertTrue(body.contains("\"gatewayId\":\"gw-ui\""))
            assertTrue(body.contains("\"unverifiedMessages\":1"))
            assertTrue(body.contains("\"verifiedMessages\":0"))
            assertTrue(body.contains("\"contentUnverifiedMessages\":1"))
            assertTrue(body.contains("\"contentVerifiedMessages\":0"))
            assertTrue(body.contains("\"anonymousRouteMessages\":1"))
            assertTrue(body.contains("\"authenticatedRouteMessages\":0"))

            val index = client.get("/")
            assertEquals(HttpStatusCode.OK, index.status)
            assertTrue(index.bodyAsText().contains("Relay 救助拠点"))
            assertTrue(index.bodyAsText().contains("設定地域"))
            assertTrue(index.bodyAsText().contains("STAFF ONLY"))

            val css = client.get("/app.css")
            assertEquals(HttpStatusCode.OK, css.status)
            assertTrue(css.bodyAsText().contains(".critical-alert"))
        }
    }

    @Test
    fun `message detail and csv require admin key`() = testApplication {
        val config = GatewayConfig(
            profile = GatewayProfile.DEVELOPMENT,
            dbPath = Files.createTempFile("relay-ui2", ".db").toString(),
            adminKey = "admin-secret",
        )
        GatewayStore(config).use { store ->
            store.ingestUnregistered(listOf(message("detail-1")), 2_000)
            application { gatewayModule(config, store) }

            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/messages/detail-1").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/messages/export.csv").status)

            val detail = client.get("/api/messages/detail-1") {
                header("X-Admin-Key", "admin-secret")
            }
            assertEquals(HttpStatusCode.OK, detail.status)
            assertTrue(detail.bodyAsText().contains("detail-1"))

            val csv = client.get("/api/messages/export.csv") {
                header("X-Admin-Key", "admin-secret")
            }
            assertEquals(HttpStatusCode.OK, csv.status)
            assertTrue(csv.bodyAsText().contains("messageId"))
            assertTrue(csv.bodyAsText().contains("detail-1"))
        }
    }

    @Test
    fun `messages csv neutralizes spreadsheet formula injection`() = testApplication {
        val config = GatewayConfig(
            profile = GatewayProfile.DEVELOPMENT,
            dbPath = Files.createTempFile("relay-ui-csvinj", ".db").toString(),
            adminKey = "admin-secret",
        )
        GatewayStore(config).use { store ->
            // messageId / originDeviceId are only length-validated on ingest, so an attacker can
            // supply spreadsheet formula payloads that a staff CSV export would otherwise execute.
            val hostile = message("=cmd").copy(originDeviceId = "=2+5")
            store.ingestUnregistered(listOf(hostile), 2_000)
            application { gatewayModule(config, store) }

            val csv = client.get("/api/messages/export.csv") {
                header("X-Admin-Key", "admin-secret")
            }
            assertEquals(HttpStatusCode.OK, csv.status)
            val body = csv.bodyAsText()
            // Formula-looking cells must be neutralised with a leading quote.
            assertTrue(body.contains("'=cmd"))
            assertTrue(body.contains("'=2+5"))
            // ...and must never survive as a raw formula cell that a spreadsheet would execute.
            assertFalse(
                body.lines().any { line -> line.split(",").any { it == "=cmd" || it == "=2+5" } },
            )
        }
    }

    @Test
    fun `messages csv neutralizes tab-prefixed formula injection`() = testApplication {
        val config = GatewayConfig(
            profile = GatewayProfile.DEVELOPMENT,
            dbPath = Files.createTempFile("relay-ui-tabinj", ".db").toString(),
            adminKey = "admin-secret",
        )
        GatewayStore(config).use { store ->
            // A tab character before a formula trigger can bypass naive sanitizers.
            val hostile = message("\t=HYPERLINK(\"http://evil\")")
            store.ingestUnregistered(listOf(hostile), 2_000)
            application { gatewayModule(config, store) }

            val csv = client.get("/api/messages/export.csv") {
                header("X-Admin-Key", "admin-secret")
            }
            assertEquals(HttpStatusCode.OK, csv.status)
            val body = csv.bodyAsText()
            // Tab-prefixed formula cells must also be neutralised with a leading quote.
            assertTrue(body.contains("'\t=HYPERLINK"))
            // Must not appear as a raw cell without the quote prefix.
            assertFalse(
                body.lines().any { line ->
                    line.split(",").any { it.startsWith("\t=") }
                },
            )
        }
    }

    @Test
    fun `filtered messages endpoint accepts trust query`() = testApplication {
        val config = GatewayConfig(
            profile = GatewayProfile.DEVELOPMENT,
            dbPath = Files.createTempFile("relay-ui3", ".db").toString(),
            adminKey = "admin-secret",
        )
        GatewayStore(config).use { store ->
            store.ingestUnregistered(listOf(message("u-1")), 2_000)
            application { gatewayModule(config, store) }
            val res = client.get("/api/messages?trust=UNVERIFIED") {
                header("X-Admin-Key", "admin-secret")
            }
            assertEquals(HttpStatusCode.OK, res.status)
            assertTrue(res.bodyAsText().contains("u-1"))
        }
    }

    @Test
    fun `paired route is separately filterable while content remains unverified`() = testApplication {
        val config = GatewayConfig(
            profile = GatewayProfile.DEVELOPMENT,
            dbPath = Files.createTempFile("relay-ui-route", ".db").toString(),
            adminKey = "admin-secret",
        )
        GatewayStore(config).use { store ->
            val code = store.createPairingCode(1_000)
            store.requestPair(code, "bridge-a", "Bridge A", 1_001)
            store.approvePair("bridge-a", code, 1_002)
            store.ingest("bridge-a", listOf(message("paired-1")), 2_000)
            application { gatewayModule(config, store) }

            val authenticated = client.get("/api/messages?routeAuthentication=AUTHENTICATED_BRIDGE") {
                header("X-Admin-Key", "admin-secret")
            }
            assertEquals(HttpStatusCode.OK, authenticated.status)
            assertTrue(authenticated.bodyAsText().contains("paired-1"))
            assertTrue(authenticated.bodyAsText().contains("\"contentVerification\":\"UNVERIFIED\""))
            assertTrue(authenticated.bodyAsText().contains("\"routeAuthentication\":\"AUTHENTICATED_BRIDGE\""))

            val contentVerified = client.get("/api/messages?trust=VERIFIED") {
                header("X-Admin-Key", "admin-secret")
            }
            assertEquals(HttpStatusCode.OK, contentVerified.status)
            assertTrue(!contentVerified.bodyAsText().contains("paired-1"))
        }
    }
}
