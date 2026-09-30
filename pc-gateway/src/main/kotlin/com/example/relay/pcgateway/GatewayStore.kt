package com.example.relay.pcgateway

import com.example.relay.gateway.protocol.GatewayMessage
import com.example.relay.gateway.protocol.GatewayReceipt
import com.example.relay.gateway.protocol.UNVERIFIED_GATEWAY_RECEIPT_TYPE
import com.example.relay.gateway.protocol.VERIFIED_GATEWAY_RECEIPT_TYPE
import com.example.relay.pcgateway.rescue.RescuePersistence
import com.example.relay.pcgateway.rescue.SqliteRescuePersistence
import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private const val ROUTE_AUTHENTICATED_BRIDGE = "AUTHENTICATED_BRIDGE"
private const val ROUTE_ANONYMOUS_LAN = "ANONYMOUS_LAN"
private const val CONTENT_UNVERIFIED = "UNVERIFIED"
private const val CONTENT_SIGNED_UNVERIFIED = "SIGNED_UNVERIFIED"

/**
 * Value written to the retired legacy `ingress_trust` column on every new row.
 *
 * The column used to describe transport-Bridge authentication, which now lives in
 * `route_authentication`; startup normalises every existing row to this value and the
 * authenticated-bridge UPDATE path hardcodes it. Legacy JSON clients still read it as
 * `ingressTrust`, so the INSERT must write the same conservative value instead of echoing
 * `content_verification` (whose newer `SIGNED_UNVERIFIED` value those clients do not understand).
 */
private const val LEGACY_INGRESS_TRUST = "UNVERIFIED"
private const val PAIRING_CODE_MIN = 100000
private const val PAIRING_CODE_MAX = 999999

data class StoreOutcome(
    val messageId: String,
    val disposition: String,
    val receipt: GatewayReceipt? = null,
    val reason: String? = null,
)

/**
 * Total accessor for a STATUS_CHANGE payload field.
 *
 * `JsonElement.jsonObject`/`jsonPrimitive` throw [IllegalArgumentException] for the wrong JSON
 * shape, and an unauthenticated sender can deliver `payload: 7`, `payload: []`, or `payload: null`.
 * Because a single record is persisted inside one batch transaction, letting that escape would roll
 * back every other message in the same request, so every field is read through this accessor and a
 * malformed record is rejected on its own instead.
 */
private fun JsonElement?.statusChangeField(name: String): String? =
    ((this as? JsonObject)?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

@kotlinx.serialization.Serializable
data class BridgeSummary(
    val bridgeId: String,
    val name: String,
    val paired: Boolean,
    val connected: Boolean,
    val lastSyncAt: Long? = null,
    val receivedCount: Long,
)

@kotlinx.serialization.Serializable
data class MessageSummary(
    val messageId: String,
    val messageType: String,
    val recordType: String,
    val priority: String,
    val status: String,
    val origin: String,
    val createdAt: Long,
    val receivedAt: Long,
    val hopCount: Int,
    val gatewayReceived: Boolean,
    val gatewayReceivedUnverified: Boolean,
    /** @deprecated Use [contentVerification]. Kept for JSON clients from the first PC Gateway MVP. */
    val ingressTrust: String,
    val routeAuthentication: String,
    val contentVerification: String,
    val sourceBridgeId: String? = null,
)

@kotlinx.serialization.Serializable
data class MessageDetail(
    val messageId: String,
    val messageType: String,
    val recordType: String,
    val priority: String,
    val status: String,
    val originDeviceId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val lifetimeMs: Long,
    val accumulatedAgeMs: Long,
    val hopCount: Int,
    val hopLimit: Int,
    val receivedAt: Long,
    /** @deprecated Use [contentVerification]. Kept for JSON clients from the first PC Gateway MVP. */
    val ingressTrust: String,
    val routeAuthentication: String,
    val contentVerification: String,
    val sourceBridgeId: String?,
    val gatewayReceived: Boolean,
    val gatewayReceivedUnverified: Boolean,
    val payloadJson: String,
    val receipts: List<GatewayReceipt>,
)

@kotlinx.serialization.Serializable
data class TypeCount(val messageType: String, val count: Int)

data class RouteAuthenticationCounts(val authenticatedBridge: Int, val anonymousLan: Int)

@kotlinx.serialization.Serializable
data class DashboardSnapshot(
    val gatewayId: String,
    val totalMessages: Int,
    val activeMessages: Int,
    /** Legacy aliases; both fields count content verification, not route authentication. */
    val verifiedMessages: Int,
    val unverifiedMessages: Int,
    val contentVerifiedMessages: Int,
    val contentUnverifiedMessages: Int,
    val authenticatedRouteMessages: Int,
    val anonymousRouteMessages: Int,
    val bridgeCount: Int,
    val pairedBridgeCount: Int,
    val receiptCount: Int,
    val byType: List<TypeCount>,
    val recentMessages: List<MessageSummary>,
    val bridges: List<BridgeSummary>,
    val anonymousIngressEnabled: Boolean,
    val lanDiscoveryEnabled: Boolean,
    val lanDiscoveryPort: Int,
    val httpPort: Int,
    val bindHost: String,
    val maxStoredMessages: Int,
    val generatedAt: Long,
)

class GatewayStore(private val config: GatewayConfig, private val json: Json = GatewayJson) : AutoCloseable {
    private val lock = Any()
    private val writeCoordinator = GatewaySqliteWriteCoordinator.forDatabase(config.dbPath)
    private val connection: Connection
    private val rescuePersistenceDelegate = lazy {
        SqliteRescuePersistence(config.dbPath, json)
    }
    private val accessStoreDelegate = lazy {
        GatewayAccessStore(config.dbPath)
    }
    private val puertaStoreDelegate = lazy { PuertaStore(config.dbPath) }
    private val pilotOperationsStoreDelegate = lazy { PilotOperationsStore(config.dbPath) }

    /**
     * Durable rescue storage sharing the gateway database file, but using its own
     * WAL-configured connection so legacy gateway work cannot hold a rescue intake lock.
     */
    fun rescuePersistence(): RescuePersistence = rescuePersistenceDelegate.value

    /** Durable local staff accounts, sessions, and audit metadata in this Gateway's SQLite file. */
    fun accessStore(): GatewayAccessStore = accessStoreDelegate.value

    /** PUERTA Gateway-only provenance; all callers use the validating local ingress service. */
    fun puertaStore(): PuertaStore = puertaStoreDelegate.value

    /** Batch B-D drill records, separated from encrypted rescue payloads and staff sessions. */
    fun pilotOperationsStore(): PilotOperationsStore = pilotOperationsStoreDelegate.value

    init {
        File(config.dbPath).parentFile?.mkdirs()
        connection = DriverManager.getConnection("jdbc:sqlite:${config.dbPath}")
        writeCoordinator.write {
            connection.createStatement().use { statement ->
            statement.execute("PRAGMA busy_timeout=5000")
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA foreign_keys=ON")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS messages(
                  message_id TEXT PRIMARY KEY, canonical_json TEXT NOT NULL, message_type TEXT NOT NULL,
                  record_type TEXT NOT NULL, priority TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL,
                  expires_at INTEGER NOT NULL, lifetime_ms INTEGER NOT NULL, accumulated_age_ms INTEGER NOT NULL,
                  hop_count INTEGER NOT NULL, hop_limit INTEGER NOT NULL, origin_id TEXT NOT NULL, received_at INTEGER NOT NULL,
                  ingress_trust TEXT NOT NULL DEFAULT 'UNVERIFIED',
                  route_authentication TEXT NOT NULL DEFAULT 'ANONYMOUS_LAN',
                  content_verification TEXT NOT NULL DEFAULT 'UNVERIFIED',
                  source_bridge_id TEXT,
                  status_event_created_at INTEGER NOT NULL DEFAULT -1,
                  status_event_id TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent(),
            )
            ensureColumn(statement, "messages", "ingress_trust", "TEXT NOT NULL DEFAULT 'UNVERIFIED'")
            ensureColumn(statement, "messages", "source_bridge_id", "TEXT")
            val routeColumnAdded = ensureColumn(
                statement,
                "messages",
                "route_authentication",
                "TEXT NOT NULL DEFAULT 'ANONYMOUS_LAN'",
            )
            ensureColumn(statement, "messages", "content_verification", "TEXT NOT NULL DEFAULT 'UNVERIFIED'")
            if (routeColumnAdded) {
                // Legacy ingress_trust represented whether the transport Bridge was authenticated,
                // not whether the report content or claimed origin had been verified.
                statement.execute(
                    """
                    UPDATE messages
                    SET route_authentication = CASE
                      WHEN ingress_trust='VERIFIED' OR source_bridge_id IS NOT NULL
                        THEN 'AUTHENTICATED_BRIDGE'
                      ELSE 'ANONYMOUS_LAN'
                    END
                    """.trimIndent(),
                )
            }
            // No deployed MVP version cryptographically verified report content. Preserve route
            // evidence in route_authentication, and conservatively migrate every legacy row.
            statement.execute("UPDATE messages SET content_verification='UNVERIFIED', ingress_trust='UNVERIFIED'")
            ensureColumn(statement, "messages", "status_event_created_at", "INTEGER NOT NULL DEFAULT -1")
            ensureColumn(statement, "messages", "status_event_id", "TEXT NOT NULL DEFAULT ''")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_messages_created ON messages(priority, created_at, expires_at)")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_messages_source_bridge ON messages(source_bridge_id)")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS receipts(
                  receipt_id TEXT PRIMARY KEY, message_id TEXT NOT NULL, receipt_type TEXT NOT NULL,
                  actor_id TEXT NOT NULL, recorded_at INTEGER NOT NULL,
                  UNIQUE(message_id, receipt_type, actor_id)
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS bridges(
                  bridge_id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT, paired INTEGER NOT NULL DEFAULT 0,
                  connected INTEGER NOT NULL DEFAULT 0, last_sync_at INTEGER, received_count INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS pairing_codes(code TEXT PRIMARY KEY, expires_at INTEGER NOT NULL, used INTEGER NOT NULL DEFAULT 0)
                """.trimIndent(),
            )
            }
        }
    }

    private fun ensureColumn(statement: java.sql.Statement, table: String, column: String, ddl: String): Boolean {
        val has = statement.executeQuery("PRAGMA table_info($table)").use { columns ->
            var found = false
            while (columns.next()) if (columns.getString("name") == column) found = true
            found
        }
        if (!has) statement.execute("ALTER TABLE $table ADD COLUMN $column $ddl")
        return !has
    }

    fun createPairingCode(now: Long = System.currentTimeMillis()): String = synchronized(lock) {
        // CSPRNG: pairing codes gate bridge enrollment, so kotlin.random is not acceptable.
        val span = PAIRING_CODE_MAX - PAIRING_CODE_MIN + 1
        val code = (PAIRING_CODE_MIN + java.security.SecureRandom().nextInt(span)).toString()
        writeCoordinator.write {
            connection.prepareStatement("INSERT INTO pairing_codes(code, expires_at) VALUES (?, ?)").use {
                it.setString(1, code)
                it.setLong(2, now + 5 * 60_000)
                it.executeUpdate()
            }
        }
        code
    }

    fun requestPair(code: String, bridgeId: String, name: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        connection.prepareStatement("SELECT used, expires_at FROM pairing_codes WHERE code=?").use { ps ->
            ps.setString(1, code)
            ps.executeQuery().use { rs ->
                if (!rs.next() || rs.getInt("used") != 0 || rs.getLong("expires_at") < now) return false
            }
        }
        writeCoordinator.write {
            connection.prepareStatement(
                "INSERT INTO bridges(bridge_id,name) VALUES(?,?) ON CONFLICT(bridge_id) DO UPDATE SET name=excluded.name",
            ).use {
                it.setString(1, bridgeId)
                it.setString(2, name.take(80))
                it.executeUpdate()
            }
        }
        true
    }

    fun approvePair(bridgeId: String, code: String, now: Long = System.currentTimeMillis()): String? = synchronized(lock) {
        if (!requestPair(code, bridgeId, bridgeId, now)) return null
        val token = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
        writeCoordinator.write {
            connection.prepareStatement("UPDATE bridges SET paired=1, token_hash=? WHERE bridge_id=?").use {
                it.setString(1, hash(token))
                it.setString(2, bridgeId)
                it.executeUpdate()
            }
            connection.prepareStatement("UPDATE pairing_codes SET used=1 WHERE code=?").use {
                it.setString(1, code)
                it.executeUpdate()
            }
        }
        token
    }

    /**
     * Rejects an in-flight pairing request and/or revokes an existing bridge token.
     * Marks the pairing code used (if provided) so it cannot be approved later.
     */
    fun rejectPair(bridgeId: String, code: String? = null, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        var changed = false
        if (!code.isNullOrBlank()) {
            val updated = writeCoordinator.write {
                connection.prepareStatement(
                    "UPDATE pairing_codes SET used=1 WHERE code=? AND used=0 AND expires_at>=?",
                ).use {
                    it.setString(1, code)
                    it.setLong(2, now)
                    it.executeUpdate()
                }
            }
            changed = changed || updated > 0
        }
        val revoked = writeCoordinator.write {
            connection.prepareStatement(
                "UPDATE bridges SET paired=0, token_hash=NULL, connected=0 WHERE bridge_id=?",
            ).use {
                it.setString(1, bridgeId)
                it.executeUpdate()
            }
        }
        changed = changed || revoked > 0
        // Ensure a row exists so the dashboard can show the rejected bridge.
        if (revoked == 0) {
            writeCoordinator.write {
                connection.prepareStatement(
                    "INSERT INTO bridges(bridge_id,name,paired) VALUES(?,?,0) ON CONFLICT(bridge_id) DO NOTHING",
                ).use {
                    it.setString(1, bridgeId)
                    it.setString(2, bridgeId.take(80))
                    it.executeUpdate()
                }
            }
        }
        changed || bridgeId.isNotBlank()
    }

    fun revokeBridge(bridgeId: String): Boolean = rejectPair(bridgeId, code = null)

    fun authenticate(bridgeId: String, token: String): Boolean = synchronized(lock) {
        connection.prepareStatement("SELECT paired, token_hash FROM bridges WHERE bridge_id=?").use { ps ->
            ps.setString(1, bridgeId)
            ps.executeQuery().use { rs ->
                rs.next() && rs.getInt("paired") == 1 && constantTimeEquals(rs.getString("token_hash"), hash(token))
            }
        }
    }

    fun ingest(bridgeId: String, messages: List<GatewayMessage>, now: Long = System.currentTimeMillis()): List<StoreOutcome> =
        synchronized(lock) {
            writeCoordinator.write {
                connection.autoCommit = false
                try {
                    val results = MutableList<StoreOutcome?>(messages.size) { null }
                    persistenceOrder(messages).forEach { indexed ->
                        results[indexed.index] = ingestOne(
                            indexed.value,
                            now,
                            VERIFIED_GATEWAY_RECEIPT_TYPE,
                            sourceBridgeId = bridgeId,
                            applyTargetStatus = true,
                        )
                    }
                    connection.prepareStatement(
                        "UPDATE bridges SET connected=1,last_sync_at=?,received_count=received_count+? WHERE bridge_id=?",
                    ).use {
                        it.setLong(1, now)
                        it.setInt(2, messages.size)
                        it.setString(3, bridgeId)
                        it.executeUpdate()
                    }
                    connection.commit()
                    results.map { requireNotNull(it) }
                } catch (error: Exception) {
                    connection.rollback()
                    throw error
                } finally {
                    connection.autoCommit = true
                }
            }
        }

    /** Unregistered LAN senders can store validated data but receive only an explicitly unverified receipt. */
    fun ingestUnregistered(messages: List<GatewayMessage>, now: Long = System.currentTimeMillis()): List<StoreOutcome> =
        synchronized(lock) {
            writeCoordinator.write {
                connection.autoCommit = false
                try {
                    val results = MutableList<StoreOutcome?>(messages.size) { null }
                    persistenceOrder(messages).forEach { indexed ->
                        results[indexed.index] = ingestOne(
                            indexed.value,
                            now,
                            UNVERIFIED_GATEWAY_RECEIPT_TYPE,
                            sourceBridgeId = null,
                            applyTargetStatus = false,
                        )
                    }
                    connection.commit()
                    results.map { requireNotNull(it) }
                } catch (error: Exception) {
                    connection.rollback()
                    throw error
                } finally {
                    connection.autoCommit = true
                }
            }
        }

    /**
     * Transmission keeps STATUS_CHANGE at higher priority. Only the atomic persistence pass is
     * dependency ordered so a newly received REPORT exists before its STATUS_CHANGE is checked.
     * Results are written back to their original request positions by the callers.
     */
    private fun persistenceOrder(messages: List<GatewayMessage>): List<IndexedValue<GatewayMessage>> =
        messages.withIndex().sortedWith(
            compareBy<IndexedValue<GatewayMessage>> { if (it.value.recordType == "REPORT") 0 else 1 }
                .thenByDescending { priorityRank(it.value.priority) }
                .thenByDescending { it.value.createdAt },
        )

    private fun priorityRank(priority: String): Int = when (priority) {
        "CRITICAL" -> 4
        "HIGH" -> 3
        "NORMAL" -> 2
        else -> 1
    }

    private fun ingestOne(
        message: GatewayMessage,
        now: Long,
        receiptType: String,
        sourceBridgeId: String?,
        applyTargetStatus: Boolean,
    ): StoreOutcome {
        val routeAuthentication = if (receiptType == VERIFIED_GATEWAY_RECEIPT_TYPE) {
            ROUTE_AUTHENTICATED_BRIDGE
        } else {
            ROUTE_ANONYMOUS_LAN
        }
        // Bridge authentication proves only which paired transport submitted the bytes. A carried
        // REPORT signature is retained as signed-but-unverified until an issuer registry exists.
        val contentVerification = if (message.reportSignature != null) CONTENT_SIGNED_UNVERIFIED else CONTENT_UNVERIFIED
        if (message.messageId.isBlank() || message.messageId.length > 64 || message.originDeviceId.length !in 1..64) {
            return StoreOutcome(message.messageId, "REJECTED", reason = "invalid_identifier")
        }
        if (message.lifetimeMs !in 1..604_800_000L ||
            message.accumulatedAgeMs !in 0..message.lifetimeMs ||
            message.accumulatedAgeMs >= message.lifetimeMs
        ) {
            return StoreOutcome(message.messageId, "REJECTED", reason = "expired_or_invalid_ttl")
        }
        if (message.hopLimit !in 1..32 || message.hopCount !in 0..message.hopLimit) {
            return StoreOutcome(message.messageId, "REJECTED", reason = "invalid_hop")
        }
        if (message.recordType == "STATUS_CHANGE") {
            val target = message.payload.statusChangeField("targetMessageId")
                ?: return StoreOutcome(message.messageId, "REJECTED", reason = "invalid_status_change")
            val newStatus = message.payload.statusChangeField("newStatus")
                ?: return StoreOutcome(message.messageId, "REJECTED", reason = "invalid_status_change")
            if (newStatus !in setOf("ACTIVE", "RESOLVED", "RETRACTED")) {
                return StoreOutcome(message.messageId, "REJECTED", reason = "invalid_status_change")
            }
            val exists = connection.prepareStatement(
                "SELECT 1 FROM messages WHERE message_id=? AND record_type='REPORT'",
            ).use { ps ->
                ps.setString(1, target)
                ps.executeQuery().use { it.next() }
            }
            if (!exists) return StoreOutcome(message.messageId, "REJECTED", reason = "target_report_not_found")
        }
        val existing = connection.prepareStatement("SELECT canonical_json FROM messages WHERE message_id=?").use { ps ->
            ps.setString(1, message.messageId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
        if (existing != null) {
            val storedMessage = runCatching {
                json.decodeFromString(GatewayMessage.serializer(), existing)
            }.getOrNull() ?: return StoreOutcome(
                message.messageId,
                "COLLISION",
                reason = "stored message unreadable",
            )
            if (!sameImmutableMessage(storedMessage, message)) {
                return StoreOutcome(message.messageId, "COLLISION", reason = "messageId collision")
            }
            val mergedMessage = message.copy(
                // A younger copy must never make an old report live longer.
                accumulatedAgeMs = maxOf(storedMessage.accumulatedAgeMs, message.accumulatedAgeMs),
                // Keep the conservative path value; a shorter duplicate must not regain relay budget.
                hopCount = maxOf(storedMessage.hopCount, message.hopCount),
                receivedAt = maxOf(storedMessage.receivedAt, message.receivedAt),
            )
            connection.prepareStatement(
                "UPDATE messages SET canonical_json=?, accumulated_age_ms=?, hop_count=?, received_at=? WHERE message_id=?",
            ).use { ps ->
                ps.setString(1, json.encodeToString(mergedMessage))
                ps.setLong(2, mergedMessage.accumulatedAgeMs)
                ps.setInt(3, mergedMessage.hopCount)
                ps.setLong(4, mergedMessage.receivedAt)
                ps.setString(5, message.messageId)
                ps.executeUpdate()
            }
            if (routeAuthentication == ROUTE_AUTHENTICATED_BRIDGE) {
                connection.prepareStatement(
                    """
                    UPDATE messages
                    SET route_authentication='AUTHENTICATED_BRIDGE',
                        content_verification=?, ingress_trust='UNVERIFIED',
                        source_bridge_id=COALESCE(?, source_bridge_id)
                    WHERE message_id=?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, contentVerification)
                    ps.setString(2, sourceBridgeId)
                    ps.setString(3, message.messageId)
                    ps.executeUpdate()
                }
            }
            if (applyTargetStatus && message.recordType == "STATUS_CHANGE") {
                applyStatusChange(message)
            }
            return StoreOutcome(message.messageId, "DUPLICATE", receiptFor(message.messageId, now, receiptType))
        }
        val total = connection.createStatement().use {
            it.executeQuery("SELECT COUNT(*) FROM messages").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
        if (total >= config.maxStoredMessages) {
            return StoreOutcome(message.messageId, "REJECTED", reason = "db_message_limit")
        }
        connection.prepareStatement(
            """
            INSERT INTO messages(message_id,canonical_json,message_type,record_type,priority,status,created_at,expires_at,lifetime_ms,accumulated_age_ms,hop_count,hop_limit,origin_id,received_at,ingress_trust,route_authentication,content_verification,source_bridge_id)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, message.messageId)
            ps.setString(2, json.encodeToString(message))
            ps.setString(3, message.messageType)
            ps.setString(4, message.recordType)
            ps.setString(5, message.priority)
            ps.setString(6, message.status)
            ps.setLong(7, message.createdAt)
            ps.setLong(8, message.expiresAt)
            ps.setLong(9, message.lifetimeMs)
            ps.setLong(10, message.accumulatedAgeMs)
            ps.setInt(11, message.hopCount)
            ps.setInt(12, message.hopLimit)
            ps.setString(13, message.originDeviceId)
            ps.setLong(14, message.receivedAt)
            ps.setString(15, LEGACY_INGRESS_TRUST)
            ps.setString(16, routeAuthentication)
            ps.setString(17, contentVerification)
            ps.setString(18, sourceBridgeId)
            ps.executeUpdate()
        }
        if (applyTargetStatus && message.recordType == "STATUS_CHANGE") {
            applyStatusChange(message)
        }
        return StoreOutcome(message.messageId, "STORED", receiptFor(message.messageId, now, receiptType))
    }

    private fun applyStatusChange(message: GatewayMessage) {
        // Both fields were validated by the caller; re-reading them through the total accessor
        // keeps this function from throwing out of the enclosing batch transaction if it is ever
        // reached with a payload shape the validator did not see.
        val target = message.payload.statusChangeField("targetMessageId") ?: return
        val newStatus = message.payload.statusChangeField("newStatus") ?: return
        // Store-carry-forward can deliver events out of order. Apply only the
        // newest event; messageId is a deterministic tie-breaker for equal clocks.
        connection.prepareStatement(
            """
            UPDATE messages
            SET status=?, status_event_created_at=?, status_event_id=?
            WHERE message_id=? AND record_type='REPORT'
              AND (status_event_created_at < ? OR
                   (status_event_created_at = ? AND status_event_id < ?))
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, newStatus)
            ps.setLong(2, message.createdAt)
            ps.setString(3, message.messageId)
            ps.setString(4, target)
            ps.setLong(5, message.createdAt)
            ps.setLong(6, message.createdAt)
            ps.setString(7, message.messageId)
            ps.executeUpdate()
        }
    }

    /**
     * Store-carry-forward updates age, hop count and the receiving device's wall-clock timestamp.
     * The wall clock is deliberately not ordered because independent Android clocks can be skewed.
     */
    private fun sameImmutableMessage(stored: GatewayMessage, incoming: GatewayMessage): Boolean =
        stored.copy(
            accumulatedAgeMs = incoming.accumulatedAgeMs,
            hopCount = incoming.hopCount,
            receivedAt = incoming.receivedAt,
        ) == incoming

    private fun receiptFor(messageId: String, now: Long, receiptType: String): GatewayReceipt {
        val existing = connection.prepareStatement(
            "SELECT receipt_id, message_id, receipt_type, actor_id, recorded_at FROM receipts WHERE message_id=? AND receipt_type=? AND actor_id=?",
        ).use { ps ->
            ps.setString(1, messageId)
            ps.setString(2, receiptType)
            ps.setString(3, config.gatewayId)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    GatewayReceipt(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5))
                } else {
                    null
                }
            }
        }
        if (existing != null) return existing
        val receipt = GatewayReceipt(UUID.randomUUID().toString(), messageId, receiptType, config.gatewayId, now)
        connection.prepareStatement(
            "INSERT INTO receipts(receipt_id,message_id,receipt_type,actor_id,recorded_at) VALUES(?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, receipt.receiptId)
            ps.setString(2, receipt.messageId)
            ps.setString(3, receipt.receiptType)
            ps.setString(4, receipt.actorId)
            ps.setLong(5, receipt.recordedAt)
            ps.executeUpdate()
        }
        return receipt
    }

    fun receipts(): List<GatewayReceipt> = synchronized(lock) {
        connection.prepareStatement(
            "SELECT receipt_id,message_id,receipt_type,actor_id,recorded_at FROM receipts ORDER BY recorded_at",
        ).use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(GatewayReceipt(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)))
                    }
                }
            }
        }
    }

    /** Receipts for messages this bridge submitted (or later claimed via verified re-sync). */
    fun receiptsForBridge(bridgeId: String): List<GatewayReceipt> = synchronized(lock) {
        connection.prepareStatement(
            """
            SELECT r.receipt_id,r.message_id,r.receipt_type,r.actor_id,r.recorded_at
            FROM receipts r
            INNER JOIN messages m ON m.message_id = r.message_id
            WHERE m.source_bridge_id = ?
            ORDER BY r.recorded_at
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, bridgeId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(GatewayReceipt(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)))
                    }
                }
            }
        }
    }

    fun summaries(): List<BridgeSummary> = synchronized(lock) {
        connection.prepareStatement(
            "SELECT bridge_id,name,paired,connected,last_sync_at,received_count FROM bridges ORDER BY name",
        ).use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            BridgeSummary(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getInt(3) == 1,
                                rs.getInt(4) == 1,
                                rs.getLong(5).takeIf { !rs.wasNull() },
                                rs.getLong(6),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun counts(): Pair<Int, Int> = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery(
                "SELECT COUNT(*), COALESCE(SUM(CASE WHEN status='ACTIVE' THEN 1 ELSE 0 END),0) FROM messages",
            ).use { rs ->
                rs.next()
                rs.getInt(1) to rs.getInt(2)
            }
        }
    }

    /** Legacy API name: these are content-verification counts, never transport-authentication counts. */
    fun trustCounts(): Pair<Int, Int> = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT
                  COALESCE(SUM(CASE WHEN content_verification='VERIFIED' THEN 1 ELSE 0 END),0),
                  COALESCE(SUM(CASE WHEN content_verification<>'VERIFIED' THEN 1 ELSE 0 END),0)
                FROM messages
                """.trimIndent(),
            ).use { rs ->
                rs.next()
                rs.getInt(1) to rs.getInt(2)
            }
        }
    }

    fun routeAuthenticationCounts(): RouteAuthenticationCounts = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT
                  COALESCE(SUM(CASE WHEN route_authentication='AUTHENTICATED_BRIDGE' THEN 1 ELSE 0 END),0),
                  COALESCE(SUM(CASE WHEN route_authentication='ANONYMOUS_LAN' THEN 1 ELSE 0 END),0)
                FROM messages
                """.trimIndent(),
            ).use { rs ->
                rs.next()
                RouteAuthenticationCounts(rs.getInt(1), rs.getInt(2))
            }
        }
    }

    fun messages(
        type: String? = null,
        status: String? = null,
        query: String? = null,
        trust: String? = null,
        routeAuthentication: String? = null,
        limit: Int = 500,
    ): List<MessageSummary> = synchronized(lock) {
        val rows = connection.prepareStatement(
            """
            SELECT m.message_id,m.message_type,m.record_type,m.priority,m.status,m.origin_id,m.created_at,m.received_at,m.hop_count,
              EXISTS(SELECT 1 FROM receipts r WHERE r.message_id=m.message_id AND r.receipt_type='GATEWAY_RECEIVED'),
              EXISTS(SELECT 1 FROM receipts r WHERE r.message_id=m.message_id AND r.receipt_type='GATEWAY_RECEIVED_UNVERIFIED'),
              m.ingress_trust,m.route_authentication,m.content_verification,m.source_bridge_id
            FROM messages m
            ORDER BY CASE priority WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3 WHEN 'NORMAL' THEN 2 ELSE 1 END DESC, created_at DESC
            """.trimIndent(),
        ).use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            MessageSummary(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getString(5),
                                rs.getString(6),
                                rs.getLong(7),
                                rs.getLong(8),
                                rs.getInt(9),
                                rs.getInt(10) == 1,
                                rs.getInt(11) == 1,
                                rs.getString(12),
                                rs.getString(13),
                                rs.getString(14),
                                rs.getString(15),
                            ),
                        )
                    }
                }
            }
        }
        rows.asSequence()
            .filter { type == null || it.messageType == type }
            .filter { status == null || it.status == status }
            .filter {
                trust == null ||
                    it.contentVerification.equals(trust, ignoreCase = true) ||
                    (trust.equals(CONTENT_UNVERIFIED, ignoreCase = true) &&
                        !it.contentVerification.equals("VERIFIED", ignoreCase = true))
            }
            .filter {
                routeAuthentication == null ||
                    it.routeAuthentication.equals(routeAuthentication, ignoreCase = true)
            }
            .filter {
                query.isNullOrBlank() ||
                    it.messageId.contains(query, true) ||
                    it.origin.contains(query, true) ||
                    (it.sourceBridgeId?.contains(query, true) == true)
            }
            .take(limit.coerceIn(1, 5_000))
            .toList()
    }

    fun messageDetail(messageId: String): MessageDetail? = synchronized(lock) {
        connection.prepareStatement(
            """
            SELECT m.message_id,m.message_type,m.record_type,m.priority,m.status,m.origin_id,m.created_at,m.expires_at,
              m.lifetime_ms,m.accumulated_age_ms,m.hop_count,m.hop_limit,m.received_at,m.ingress_trust,
              m.route_authentication,m.content_verification,m.source_bridge_id,m.canonical_json,
              EXISTS(SELECT 1 FROM receipts r WHERE r.message_id=m.message_id AND r.receipt_type='GATEWAY_RECEIVED'),
              EXISTS(SELECT 1 FROM receipts r WHERE r.message_id=m.message_id AND r.receipt_type='GATEWAY_RECEIVED_UNVERIFIED')
            FROM messages m WHERE m.message_id=?
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, messageId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                val receipts = connection.prepareStatement(
                    "SELECT receipt_id,message_id,receipt_type,actor_id,recorded_at FROM receipts WHERE message_id=? ORDER BY recorded_at",
                ).use { rps ->
                    rps.setString(1, messageId)
                    rps.executeQuery().use { rrs ->
                        buildList {
                            while (rrs.next()) {
                                add(
                                    GatewayReceipt(
                                        rrs.getString(1),
                                        rrs.getString(2),
                                        rrs.getString(3),
                                        rrs.getString(4),
                                        rrs.getLong(5),
                                    ),
                                )
                            }
                        }
                    }
                }
                val canonical = rs.getString(18)
                val payloadJson = runCatching {
                    val element = json.parseToJsonElement(canonical)
                    val payload = element.jsonObject["payload"]
                    payload?.toString() ?: canonical
                }.getOrDefault(canonical)
                MessageDetail(
                    messageId = rs.getString(1),
                    messageType = rs.getString(2),
                    recordType = rs.getString(3),
                    priority = rs.getString(4),
                    status = rs.getString(5),
                    originDeviceId = rs.getString(6),
                    createdAt = rs.getLong(7),
                    expiresAt = rs.getLong(8),
                    lifetimeMs = rs.getLong(9),
                    accumulatedAgeMs = rs.getLong(10),
                    hopCount = rs.getInt(11),
                    hopLimit = rs.getInt(12),
                    receivedAt = rs.getLong(13),
                    ingressTrust = rs.getString(14),
                    routeAuthentication = rs.getString(15),
                    contentVerification = rs.getString(16),
                    sourceBridgeId = rs.getString(17),
                    gatewayReceived = rs.getInt(19) == 1,
                    gatewayReceivedUnverified = rs.getInt(20) == 1,
                    payloadJson = payloadJson.take(8_192),
                    receipts = receipts,
                )
            }
        }
    }

    fun typeCounts(): List<TypeCount> = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery(
                "SELECT message_type, COUNT(*) FROM messages GROUP BY message_type ORDER BY COUNT(*) DESC",
            ).use { rs ->
                buildList {
                    while (rs.next()) add(TypeCount(rs.getString(1), rs.getInt(2)))
                }
            }
        }
    }

    fun receiptCount(): Int = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM receipts").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    fun dashboard(config: GatewayConfig, recentLimit: Int = 12): DashboardSnapshot {
        val counts = counts()
        val trust = trustCounts()
        val routes = routeAuthenticationCounts()
        val bridges = summaries()
        return DashboardSnapshot(
            gatewayId = config.gatewayId,
            totalMessages = counts.first,
            activeMessages = counts.second,
            verifiedMessages = trust.first,
            unverifiedMessages = trust.second,
            contentVerifiedMessages = trust.first,
            contentUnverifiedMessages = trust.second,
            authenticatedRouteMessages = routes.authenticatedBridge,
            anonymousRouteMessages = routes.anonymousLan,
            bridgeCount = bridges.size,
            pairedBridgeCount = bridges.count { it.paired },
            receiptCount = receiptCount(),
            byType = typeCounts(),
            recentMessages = messages(limit = recentLimit),
            bridges = bridges,
            anonymousIngressEnabled = config.anonymousIngressEnabled,
            lanDiscoveryEnabled = config.lanDiscoveryEnabled,
            lanDiscoveryPort = config.lanDiscoveryPort,
            httpPort = config.port,
            bindHost = config.host,
            maxStoredMessages = config.maxStoredMessages,
            generatedAt = System.currentTimeMillis(),
        )
    }

    fun exportCsv(limit: Int = 5_000): String = synchronized(lock) {
        val header = listOf(
            "messageId", "messageType", "recordType", "priority", "status", "contentVerification", "routeAuthentication",
            "origin", "sourceBridgeId", "createdAt", "receivedAt", "hopCount",
            "gatewayReceived", "gatewayReceivedUnverified",
        ).joinToString(",")
        val rows = messages(limit = limit).joinToString("\n") { m ->
            listOf(
                m.messageId,
                m.messageType,
                m.recordType,
                m.priority,
                m.status,
                m.contentVerification,
                m.routeAuthentication,
                m.origin,
                m.sourceBridgeId.orEmpty(),
                m.createdAt.toString(),
                m.receivedAt.toString(),
                m.hopCount.toString(),
                m.gatewayReceived.toString(),
                m.gatewayReceivedUnverified.toString(),
            ).joinToString(",") { csvEscape(it) }
        }
        header + "\n" + rows + "\n"
    }

    // Delegates to the shared [csvSafeCell] so message and audit exports cannot drift apart.
    // Message ids / origin ids are only length-validated on ingest, so this must also neutralise
    // spreadsheet formula injection, not just quote delimiter characters.
    private fun csvEscape(value: String): String = csvSafeCell(value)

    override fun close() {
        if (rescuePersistenceDelegate.isInitialized()) rescuePersistenceDelegate.value.close()
        if (accessStoreDelegate.isInitialized()) accessStoreDelegate.value.close()
        if (puertaStoreDelegate.isInitialized()) puertaStoreDelegate.value.close()
        if (pilotOperationsStoreDelegate.isInitialized()) pilotOperationsStoreDelegate.value.close()
        synchronized(lock) { connection.close() }
    }

    private fun hash(value: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    private fun constantTimeEquals(a: String?, b: String): Boolean =
        a != null && MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

val GatewayJson = Json { ignoreUnknownKeys = false; encodeDefaults = true; classDiscriminator = "payloadType" }
