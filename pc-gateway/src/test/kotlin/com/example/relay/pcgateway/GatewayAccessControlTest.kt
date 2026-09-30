package com.example.relay.pcgateway

import com.example.relay.gateway.protocol.GatewayMessage
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.sql.DriverManager
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayAccessControlTest {
    @Test
    fun `authenticated polling does not write session state on every request`() {
        val db = Files.createTempFile("relay-session-touch", ".db").toString()
        GatewayAccessStore(db, sessionTouchIntervalMillis = 60_000).use { access ->
            assertTrue(access.bootstrapAdmin("polling-admin", "a long bootstrap secret", "test", now = 1_000))
            val session = assertLoginSuccess(
                access.login("polling-admin", "a long bootstrap secret", "test", sessionTtlMillis = 120_000, now = 2_000),
            )

            assertNotNull(access.authenticateSession(session.token, now = 2_500))
            assertEquals(2_000L, sessionLastSeen(db))

            assertNotNull(access.authenticateSession(session.token, now = 62_000))
            assertEquals(62_000L, sessionLastSeen(db))
        }
    }

    @Test
    fun `map status polling is read-only and does not append audit rows`() = testApplication {
        val config = GatewayConfig(
            profile = GatewayProfile.DEVELOPMENT,
            dbPath = Files.createTempFile("relay-map-polling", ".db").toString(),
            legacyAdminKeyEnabled = false,
        )
        GatewayStore(config).use { store ->
            val access = store.accessStore()
            assertTrue(access.bootstrapAdmin("map-polling-admin", "a long bootstrap secret", "test"))
            GsiTileCache(Files.createTempDirectory("relay-map-cache")).use { cache ->
                application { gatewayModule(config, store, offlineMap = cache) }
                val cookie = login("map-polling-admin", "a long bootstrap secret")
                val before = access.auditRecords(limit = 100).size

                repeat(3) {
                    assertEquals(
                        HttpStatusCode.OK,
                        client.get("/api/map/status") { header(HttpHeaders.Cookie, cookie) }.status,
                    )
                }

                assertEquals(before, access.auditRecords(limit = 100).size)
            }
        }
    }

    @Test
    fun `production defaults are loopback and deny anonymous legacy management`() {
        val config = GatewayConfig(profile = GatewayProfile.PRODUCTION)

        assertEquals("127.0.0.1", config.host)
        assertFalse(config.anonymousIngressEnabled)
        assertFalse(config.lanDiscoveryEnabled)
        assertFalse(config.legacyAdminKeyEnabled)
        assertFalse(config.remoteManagementEnabled)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `production refuses non-loopback binding without explicit topology`() {
        GatewayConfig(profile = GatewayProfile.PRODUCTION, host = "0.0.0.0")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `production refuses anonymous ingress without explicit LAN topology`() {
        GatewayConfig(profile = GatewayProfile.PRODUCTION, anonymousIngressEnabled = true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `remote browser management requires a TLS reverse proxy and secure cookie`() {
        GatewayConfig(
            profile = GatewayProfile.PRODUCTION,
            lanMode = GatewayLanMode.CLOSED_NETWORK,
            remoteManagementEnabled = true,
        )
    }

    @Test
    fun `TLS proxy mode keeps operator management off until explicitly enabled`() {
        val config = GatewayConfig(
            profile = GatewayProfile.PRODUCTION,
            lanMode = GatewayLanMode.TLS_REVERSE_PROXY,
        )
        assertFalse(config.managementSourceAllowed("127.0.0.1"))
    }

    @Test
    fun `TLS proxy mode with remote management off does not serve the operator UI`() = testApplication {
        val config = GatewayConfig(
            profile = GatewayProfile.PRODUCTION,
            lanMode = GatewayLanMode.TLS_REVERSE_PROXY,
            dbPath = Files.createTempFile("relay-proxy-ui", ".db").toString(),
        )
        GatewayStore(config).use { store ->
            application { gatewayModule(config, store) }
            assertEquals(HttpStatusCode.NotFound, client.get("/").status)
            assertEquals(HttpStatusCode.Forbidden, client.post("/api/auth/login").status)
        }
    }

    @Test
    fun `access schema migrates alongside an existing gateway database and expired sessions fail closed`() {
        val db = Files.createTempFile("relay-access-migration", ".db").toString()
        val config = GatewayConfig(profile = GatewayProfile.PRODUCTION, dbPath = db)
        GatewayStore(config).use { store ->
            val access = store.accessStore()
            assertEquals(1, access.schemaVersion())
            assertTrue(access.bootstrapAdmin("migration-admin", "migration bootstrap secret", "test", now = 1_000))
            val login = access.login(
                "migration-admin",
                "migration bootstrap secret",
                "test",
                sessionTtlMillis = 1_000,
                now = 2_000,
            )
            val session = assertLoginSuccess(login)
            assertNotNull(access.authenticateSession(session.token, now = 2_999))
            assertTrue(access.authenticateSession(session.token, now = 3_000) == null)

            access.recordRuntimeConfiguration("profile=production;lan=disabled", "test", now = 4_000)
            access.recordRuntimeConfiguration("profile=production;lan=closed_network", "test", now = 5_000)
            assertTrue(access.auditRecords(limit = 20).any { it.action == "CONFIGURATION_CHANGE" && it.result == "APPLIED" })
        }
    }

    @Test
    fun `TLS remote management login returns a Secure HttpOnly SameSite session cookie`() = testApplication {
        val config = GatewayConfig(
            profile = GatewayProfile.PRODUCTION,
            lanMode = GatewayLanMode.TLS_REVERSE_PROXY,
            remoteManagementEnabled = true,
            dbPath = Files.createTempFile("relay-session-cookie", ".db").toString(),
        )
        GatewayStore(config).use { store ->
            store.accessStore().bootstrapAdmin("cookie-admin", "a long bootstrap secret", "test")
            application { gatewayModule(config, store) }
            val response = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"cookie-admin","password":"a long bootstrap secret"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val setCookie = requireNotNull(response.headers[HttpHeaders.SetCookie])
            assertTrue(setCookie.contains("Secure"))
            assertTrue(setCookie.contains("HttpOnly"))
            assertTrue(setCookie.contains("SameSite=Strict"))
        }
    }

    @Test
    fun `roles sessions audit and legacy rejection enforce authorization boundaries`() = testApplication {
        val config = GatewayConfig(
            profile = GatewayProfile.PRODUCTION,
            dbPath = Files.createTempFile("relay-access", ".db").toString(),
            adminKey = "development-key-must-not-work",
        )
        GatewayStore(config).use { store ->
            store.ingestUnregistered(listOf(message()), now = 2_000)
            val access = store.accessStore()
            assertTrue(access.bootstrapAdmin("pilot-admin", "a long bootstrap secret", "test"))
            application { gatewayModule(config, store) }

            // Production explicitly rejects the old header even when an old key was supplied.
            val legacy = client.get("/api/messages") { header("X-Admin-Key", "development-key-must-not-work") }
            assertEquals(HttpStatusCode.Unauthorized, legacy.status)

            val rejectedLogin = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"pilot-admin","password":"not-the-bootstrap-password"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, rejectedLogin.status)

            val adminCookie = login("pilot-admin", "a long bootstrap secret")
            val createViewer = client.post("/api/admin/accounts") {
                header(HttpHeaders.Cookie, adminCookie)
                contentType(ContentType.Application.Json)
                setBody("""{"username":"pilot-viewer","password":"viewer passphrase 123","role":"VIEWER"}""")
            }
            assertEquals(HttpStatusCode.Created, createViewer.status)
            val createOperator = client.post("/api/admin/accounts") {
                header(HttpHeaders.Cookie, adminCookie)
                contentType(ContentType.Application.Json)
                setBody("""{"username":"pilot-operator","password":"operator passphrase 123","role":"OPERATOR"}""")
            }
            assertEquals(HttpStatusCode.Created, createOperator.status)

            val viewerCookie = login("pilot-viewer", "viewer passphrase 123")
            assertEquals(HttpStatusCode.OK, client.get("/api/messages") { header(HttpHeaders.Cookie, viewerCookie) }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/messages/export.csv") { header(HttpHeaders.Cookie, viewerCookie) }.status)
            assertEquals(HttpStatusCode.Forbidden, client.post("/api/map/prepare") { header(HttpHeaders.Cookie, viewerCookie) }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/audit") { header(HttpHeaders.Cookie, viewerCookie) }.status)

            val operatorCookie = login("pilot-operator", "operator passphrase 123")
            // No rescue service was configured; 503 proves the request passed the OPERATOR authorization check.
            assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/api/rescue/requests/nope/status") {
                header(HttpHeaders.Cookie, operatorCookie)
                contentType(ContentType.Application.Json)
                setBody("""{"status":"CONFIRMED"}""")
            }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/audit/export.csv") { header(HttpHeaders.Cookie, operatorCookie) }.status)
            assertEquals(HttpStatusCode.OK, client.get("/api/messages/export.csv") { header(HttpHeaders.Cookie, adminCookie) }.status)

            // Disabling an account revokes every outstanding session immediately.
            assertTrue(access.setAccountDisabled(AuthenticatedStaff("pilot-admin", StaffRole.ADMIN), "pilot-viewer", true, "test"))
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/messages") { header(HttpHeaders.Cookie, viewerCookie) }.status)

            val audit = access.auditRecords(limit = 200)
            assertTrue(audit.any { it.action == "LOGIN" && it.result == "SUCCESS" })
            assertTrue(audit.any { it.action == "LOGIN" && it.result == "REJECTED" })
            assertTrue(audit.any { it.action == "AUTH_REJECTED" && it.result == "LEGACY_ADMIN_KEY_DISABLED" })
            assertTrue(audit.any { it.action == "ACCOUNT_DISABLE" && it.targetId == "pilot-viewer" })
            // Passwords and generic message payload are absent from the durable audit metadata.
            assertFalse(access.exportAuditCsv().contains("viewer passphrase"))
            assertFalse(access.exportAuditCsv().contains("sensitive-payload"))
            assertFalse(access.exportAuditCsv().contains("not-the-bootstrap-password"))
            access.audit(AuthenticatedStaff("pilot-admin", StaffRole.ADMIN), "=formula", "MESSAGE_VIEW", "SUCCESS", "test")
            assertTrue(access.exportAuditCsv().contains("'=formula"))
        }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.login(username: String, password: String): String {
        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return sessionCookie(response)
    }

    private fun sessionCookie(response: HttpResponse): String {
        val setCookie = response.headers[HttpHeaders.SetCookie]
        assertNotNull(setCookie)
        return requireNotNull(setCookie).substringBefore(';')
    }

    private fun assertLoginSuccess(result: LoginResult): GatewaySession {
        assertTrue(result is LoginResult.Success)
        return (result as LoginResult.Success).session
    }

    private fun sessionLastSeen(db: String): Long = DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
        connection.prepareStatement("SELECT last_seen_at FROM staff_sessions").use { statement ->
            statement.executeQuery().use { result ->
                assertTrue(result.next())
                result.getLong(1)
            }
        }
    }

    private fun message() = GatewayMessage(
        messageId = "access-message",
        messageType = "SAFETY",
        recordType = "REPORT",
        priority = "NORMAL",
        status = "ACTIVE",
        createdAt = 1_000,
        expiresAt = 86_401_000,
        lifetimeMs = 86_400_000,
        accumulatedAgeMs = 0,
        hopCount = 0,
        hopLimit = 8,
        originDeviceId = "access-origin",
        payload = JsonPrimitive("sensitive-payload"),
        receivedAt = 1_000,
    )
}
