package com.example.relay.pcgateway.rescue

import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.RescuePayload
import com.example.relay.rescue.SignedShelterReceipt
import com.example.relay.pcgateway.GatewaySqliteWriteCoordinator
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Storage boundary for durable rescue intake. */
interface RescuePersistence {
    /** Executes a read/modify/write operation atomically in the backing store. */
    fun <T> transaction(block: RescuePersistence.() -> T): T
    fun find(key: RescueRequestKey): StoredRescueRequest?
    fun insert(request: StoredRescueRequest)
    fun replace(request: StoredRescueRequest)
    fun listAll(): List<StoredRescueRequest>
    fun latestVersion(requestId: String): Int?
    fun quarantine(envelope: QuarantinedRescueEnvelope)
    fun listQuarantined(): List<QuarantinedRescueEnvelope>
    fun deleteTerminalBefore(cutoffEpochMillis: Long): Int
}

class InMemoryRescuePersistence : RescuePersistence {
    private val requests = linkedMapOf<RescueRequestKey, StoredRescueRequest>()
    private val quarantined = mutableListOf<QuarantinedRescueEnvelope>()

    @Synchronized override fun <T> transaction(block: RescuePersistence.() -> T): T = block(this)
    @Synchronized override fun find(key: RescueRequestKey): StoredRescueRequest? = requests[key]
    @Synchronized override fun insert(request: StoredRescueRequest) {
        check(requests.putIfAbsent(request.key, request) == null) { "request already exists" }
    }
    @Synchronized override fun replace(request: StoredRescueRequest) {
        check(requests.replace(request.key, request) != null) { "request does not exist" }
    }
    @Synchronized override fun listAll(): List<StoredRescueRequest> = requests.values.toList()
    @Synchronized override fun latestVersion(requestId: String): Int? = requests.keys.asSequence()
        .filter { it.requestId == requestId }.maxOfOrNull { it.requestVersion }
    @Synchronized override fun quarantine(envelope: QuarantinedRescueEnvelope) { quarantined += envelope }
    @Synchronized override fun listQuarantined(): List<QuarantinedRescueEnvelope> = quarantined.toList()
    @Synchronized override fun deleteTerminalBefore(cutoffEpochMillis: Long): Int {
        val before = requests.size
        val expiredRequestIds = requests.values.filter {
            it.terminalAtEpochMillis?.let { terminalAt -> terminalAt < cutoffEpochMillis } == true
        }.map { it.key.requestId }.toSet().filterTo(mutableSetOf()) { requestId ->
            requests.values.none { it.key.requestId == requestId && it.terminalAtEpochMillis == null }
        }
        requests.entries.removeAll { it.key.requestId in expiredRequestIds }
        return before - requests.size
    }
}

/**
 * SQLite implementation.  Each instance owns its connection; SQLite WAL and a busy
 * timeout allow it to coexist with [GatewayStore]'s legacy-message connection safely.
 */
class SqliteRescuePersistence(
    dbPath: String,
    private val json: Json,
) : RescuePersistence, AutoCloseable {
    private val lock = Any()
    private val writeCoordinator = GatewaySqliteWriteCoordinator.forDatabase(dbPath)
    private val connection: Connection

    /**
     * Runs a database operation under the same lock that protects rescue transactions.
     *
     * ReceiptOutbox uses this instead of a raw connection so its background flusher cannot
     * interleave statements with an active rescue-state transaction on the shared JDBC connection.
     * Java monitors are re-entrant, so enqueueing a receipt from inside [transaction] remains part
     * of that transaction.
     */
    fun <T> withConnection(block: (Connection) -> T): T = synchronized(lock) {
        writeCoordinator.write {
            check(!connection.isClosed) { "rescue persistence is closed" }
            block(connection)
        }
    }

    init {
        File(dbPath).parentFile?.mkdirs()
        connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        writeCoordinator.write {
            connection.createStatement().use { statement ->
            statement.execute("PRAGMA busy_timeout=5000")
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA foreign_keys=ON")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS rescue_requests(
                  request_id TEXT NOT NULL, request_version INTEGER NOT NULL,
                  envelope_hash TEXT NOT NULL, envelope_json TEXT NOT NULL, payload_json TEXT NOT NULL,
                  received_at INTEGER NOT NULL, response_status TEXT NOT NULL, receipt_json TEXT NOT NULL,
                  assigned_node_id TEXT, status_updated_at INTEGER NOT NULL DEFAULT 0, terminal_at INTEGER,
                  PRIMARY KEY(request_id, request_version)
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS rescue_request_carriers(
                  request_id TEXT NOT NULL, request_version INTEGER NOT NULL, carrier_id TEXT NOT NULL,
                  PRIMARY KEY(request_id, request_version, carrier_id),
                  FOREIGN KEY(request_id, request_version) REFERENCES rescue_requests(request_id, request_version) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS rescue_delivery_attempts(
                  request_id TEXT NOT NULL, request_version INTEGER NOT NULL, delivery_id TEXT NOT NULL, carrier_id TEXT NOT NULL,
                  PRIMARY KEY(request_id, request_version, delivery_id),
                  FOREIGN KEY(request_id, request_version) REFERENCES rescue_requests(request_id, request_version) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS rescue_quarantine(
                  quarantine_id INTEGER PRIMARY KEY AUTOINCREMENT, request_id TEXT NOT NULL, request_version INTEGER NOT NULL,
                  envelope_id TEXT NOT NULL, claimed_envelope_hash TEXT NOT NULL, existing_envelope_hash TEXT NOT NULL,
                  carrier_id TEXT NOT NULL, quarantined_at INTEGER NOT NULL, reason TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute("CREATE INDEX IF NOT EXISTS idx_rescue_requests_received ON rescue_requests(received_at DESC)")
            // Migrate databases created before the v1 operator workflow.
            runCatching { statement.execute("ALTER TABLE rescue_requests ADD COLUMN assigned_node_id TEXT") }
            runCatching { statement.execute("ALTER TABLE rescue_requests ADD COLUMN status_updated_at INTEGER NOT NULL DEFAULT 0") }
            runCatching { statement.execute("ALTER TABLE rescue_requests ADD COLUMN terminal_at INTEGER") }
            }
        }
    }

    override fun <T> transaction(block: RescuePersistence.() -> T): T = withConnection { database ->
        val originalAutoCommit = database.autoCommit
        database.autoCommit = false
        try {
            val result = block(this@SqliteRescuePersistence)
            database.commit()
            result
        } catch (error: Throwable) {
            runCatching { database.rollback() }
            throw error
        } finally {
            database.autoCommit = originalAutoCommit
        }
    }

    override fun find(key: RescueRequestKey): StoredRescueRequest? = synchronized(lock) { findInternal(key) }

    override fun insert(request: StoredRescueRequest) = synchronized(lock) {
        writeCoordinator.write {
            connection.prepareStatement(
                """INSERT INTO rescue_requests(request_id,request_version,envelope_hash,envelope_json,payload_json,received_at,response_status,receipt_json,assigned_node_id,status_updated_at,terminal_at)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
            ).use { ps ->
                ps.setString(1, request.key.requestId); ps.setInt(2, request.key.requestVersion)
                ps.setString(3, request.envelopeHash); ps.setString(4, json.encodeToString(request.envelope))
                ps.setString(5, json.encodeToString(request.payload)); ps.setLong(6, request.receivedAtEpochMillis)
                ps.setString(7, request.responseStatus.name); ps.setString(8, json.encodeToString(request.receipt))
                ps.setString(9, request.assignedNodeId); ps.setLong(10, request.statusUpdatedAtEpochMillis)
                request.terminalAtEpochMillis?.let { ps.setLong(11, it) } ?: ps.setNull(11, java.sql.Types.BIGINT)
                check(ps.executeUpdate() == 1) { "request insert failed" }
            }
            replaceCarriersAndDeliveries(request)
        }
    }

    override fun replace(request: StoredRescueRequest) = synchronized(lock) {
        writeCoordinator.write {
            connection.prepareStatement(
                """UPDATE rescue_requests SET envelope_hash=?,envelope_json=?,payload_json=?,received_at=?,response_status=?,receipt_json=?,assigned_node_id=?,status_updated_at=?,terminal_at=?
                   WHERE request_id=? AND request_version=?""",
            ).use { ps ->
                ps.setString(1, request.envelopeHash); ps.setString(2, json.encodeToString(request.envelope))
                ps.setString(3, json.encodeToString(request.payload)); ps.setLong(4, request.receivedAtEpochMillis)
                ps.setString(5, request.responseStatus.name); ps.setString(6, json.encodeToString(request.receipt))
                ps.setString(7, request.assignedNodeId); ps.setLong(8, request.statusUpdatedAtEpochMillis)
                request.terminalAtEpochMillis?.let { ps.setLong(9, it) } ?: ps.setNull(9, java.sql.Types.BIGINT)
                ps.setString(10, request.key.requestId); ps.setInt(11, request.key.requestVersion)
                check(ps.executeUpdate() == 1) { "request does not exist" }
            }
            replaceCarriersAndDeliveries(request)
        }
    }

    override fun listAll(): List<StoredRescueRequest> = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT request_id,request_version FROM rescue_requests ORDER BY received_at DESC").use { rs ->
                buildList { while (rs.next()) add(RescueRequestKey(rs.getString(1), rs.getInt(2))) }
            }
        }.mapNotNull(::findInternal)
    }

    override fun latestVersion(requestId: String): Int? = synchronized(lock) {
        connection.prepareStatement("SELECT MAX(request_version) FROM rescue_requests WHERE request_id=?").use { ps ->
            ps.setString(1, requestId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1).takeIf { !rs.wasNull() } else null }
        }
    }

    override fun quarantine(envelope: QuarantinedRescueEnvelope) = synchronized(lock) {
        writeCoordinator.write {
            connection.prepareStatement(
                """INSERT INTO rescue_quarantine(request_id,request_version,envelope_id,claimed_envelope_hash,existing_envelope_hash,carrier_id,quarantined_at,reason)
                   VALUES(?,?,?,?,?,?,?,?)""",
            ).use { ps ->
                ps.setString(1, envelope.key.requestId); ps.setInt(2, envelope.key.requestVersion); ps.setString(3, envelope.envelopeId)
                ps.setString(4, envelope.claimedEnvelopeHash); ps.setString(5, envelope.existingEnvelopeHash); ps.setString(6, envelope.carrierId)
                ps.setLong(7, envelope.quarantinedAtEpochMillis); ps.setString(8, envelope.reason); ps.executeUpdate()
            }
            Unit
        }
    }

    override fun listQuarantined(): List<QuarantinedRescueEnvelope> = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT request_id,request_version,envelope_id,claimed_envelope_hash,existing_envelope_hash,carrier_id,quarantined_at,reason FROM rescue_quarantine ORDER BY quarantine_id").use { rs ->
                buildList {
                    while (rs.next()) add(QuarantinedRescueEnvelope(
                        RescueRequestKey(rs.getString(1), rs.getInt(2)), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getLong(7), rs.getString(8),
                    ))
                }
            }
        }
    }

    override fun deleteTerminalBefore(cutoffEpochMillis: Long): Int = synchronized(lock) {
        writeCoordinator.write {
            connection.prepareStatement(
                // Purge only terminal revisions when the case has no active revision. An old
                // completed revision must not delete a newer, still-active revision with the
                // same logical request ID.
                "DELETE FROM rescue_requests WHERE terminal_at IS NOT NULL AND terminal_at < ? " +
                    "AND request_id NOT IN (SELECT request_id FROM rescue_requests WHERE terminal_at IS NULL)",
            ).use { ps ->
                ps.setLong(1, cutoffEpochMillis)
                ps.executeUpdate()
            }
        }
    }

    private fun findInternal(key: RescueRequestKey): StoredRescueRequest? = connection.prepareStatement(
        "SELECT envelope_hash,envelope_json,payload_json,received_at,response_status,receipt_json,assigned_node_id,status_updated_at,terminal_at FROM rescue_requests WHERE request_id=? AND request_version=?",
    ).use { ps ->
        ps.setString(1, key.requestId); ps.setInt(2, key.requestVersion)
        ps.executeQuery().use { rs ->
            if (!rs.next()) return null
            StoredRescueRequest(
                key, rs.getString(1), json.decodeFromString<EncryptedRescueEnvelope>(rs.getString(2)),
                json.decodeFromString<RescuePayload>(rs.getString(3)), rs.getLong(4),
                RescueResponseStatus.valueOf(rs.getString(5)), carrierIds(key),
                json.decodeFromString<SignedShelterReceipt>(rs.getString(6)), deliveryIds(key),
                assignedNodeId = rs.getString(7),
                statusUpdatedAtEpochMillis = rs.getLong(8).takeIf { it > 0 } ?: rs.getLong(4),
                terminalAtEpochMillis = rs.getLong(9).takeIf { !rs.wasNull() },
            )
        }
    }

    private fun carrierIds(key: RescueRequestKey): Set<String> = idsFor(key, "rescue_request_carriers", "carrier_id")
    private fun deliveryIds(key: RescueRequestKey): Set<String> = idsFor(key, "rescue_delivery_attempts", "delivery_id")
    private fun idsFor(key: RescueRequestKey, table: String, field: String): Set<String> = connection.prepareStatement(
        "SELECT $field FROM $table WHERE request_id=? AND request_version=?",
    ).use { ps ->
        ps.setString(1, key.requestId); ps.setInt(2, key.requestVersion)
        ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
    }

    private fun replaceCarriersAndDeliveries(request: StoredRescueRequest) {
        replaceIds(request, "rescue_request_carriers", "carrier_id", request.carrierIds)
        replaceIds(request, "rescue_delivery_attempts", "delivery_id", request.deliveryIds, request.carrierIds.firstOrNull().orEmpty())
    }

    private fun replaceIds(request: StoredRescueRequest, table: String, field: String, values: Set<String>, carrierId: String? = null) {
        connection.prepareStatement("DELETE FROM $table WHERE request_id=? AND request_version=?").use { ps ->
            ps.setString(1, request.key.requestId); ps.setInt(2, request.key.requestVersion); ps.executeUpdate()
        }
        val columns = if (carrierId == null) "request_id,request_version,$field" else "request_id,request_version,$field,carrier_id"
        val placeholders = if (carrierId == null) "?,?,?" else "?,?,?,?"
        connection.prepareStatement("INSERT INTO $table($columns) VALUES($placeholders)").use { ps ->
            values.forEach { value ->
                ps.setString(1, request.key.requestId); ps.setInt(2, request.key.requestVersion); ps.setString(3, value)
                if (carrierId != null) ps.setString(4, carrierId)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    override fun close() = synchronized(lock) { if (!connection.isClosed) connection.close() }
}
