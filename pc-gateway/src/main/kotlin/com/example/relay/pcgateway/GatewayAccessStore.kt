package com.example.relay.pcgateway

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.serialization.Serializable

/** Least-privilege roles used by the local Gateway operator API. */
@Serializable
enum class StaffRole {
    ADMIN,
    OPERATOR,
    VIEWER;

    fun permits(required: StaffRole): Boolean = ordinal <= required.ordinal
}

@Serializable
data class StaffAccountSummary(
    val username: String,
    val role: StaffRole,
    val disabled: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class AuthenticatedStaff(
    val username: String,
    val role: StaffRole,
    val sessionId: String? = null,
    val legacyDevelopmentKey: Boolean = false,
)

data class GatewaySession(
    val token: String,
    val expiresAtEpochMillis: Long,
)

sealed interface LoginResult {
    data class Success(val staff: AuthenticatedStaff, val session: GatewaySession) : LoginResult
    data object InvalidCredentials : LoginResult
    data object Disabled : LoginResult
}

@Serializable
data class AuditLogRecord(
    val id: Long,
    val occurredAtEpochMillis: Long,
    val operatorUsername: String? = null,
    val targetId: String? = null,
    val action: String,
    val result: String,
    /** Minimal transport source (typically loopback or an IP address); never a forwarded header. */
    val source: String? = null,
)

/**
 * Separate SQLite connection for account/session/audit data in the same Gateway database file.
 * WAL plus the short transactions below keep it independent from rescue ingestion, while keeping
 * the authorization boundary durable and locally inspectable for an audit.
 */
class GatewayAccessStore(
    dbPath: String,
    private val sessionTouchIntervalMillis: Long = DEFAULT_SESSION_TOUCH_INTERVAL_MILLIS,
) : AutoCloseable {
    private val lock = Any()
    private val writeCoordinator = GatewaySqliteWriteCoordinator.forDatabase(dbPath)
    private val connection: Connection

    init {
        require(sessionTouchIntervalMillis >= 0) { "session touch interval must not be negative" }
        File(dbPath).parentFile?.mkdirs()
        connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        writeCoordinator.write {
            connection.createStatement().use { statement ->
            statement.execute("PRAGMA busy_timeout=5000")
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA foreign_keys=ON")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS staff_accounts(
                  username TEXT PRIMARY KEY,
                  password_kdf TEXT NOT NULL,
                  role TEXT NOT NULL,
                  disabled INTEGER NOT NULL DEFAULT 0,
                  created_at INTEGER NOT NULL,
                  updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS staff_sessions(
                  session_hash TEXT PRIMARY KEY,
                  username TEXT NOT NULL,
                  created_at INTEGER NOT NULL,
                  expires_at INTEGER NOT NULL,
                  revoked_at INTEGER,
                  last_seen_at INTEGER NOT NULL,
                  FOREIGN KEY(username) REFERENCES staff_accounts(username) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS gateway_audit_log(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  occurred_at INTEGER NOT NULL,
                  operator_username TEXT,
                  target_id TEXT,
                  action TEXT NOT NULL,
                  result TEXT NOT NULL,
                  source_info TEXT
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS gateway_security_meta(
                  meta_key TEXT PRIMARY KEY,
                  meta_value TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS gateway_access_schema_migrations(
                  version INTEGER PRIMARY KEY,
                  applied_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                "INSERT OR IGNORE INTO gateway_access_schema_migrations(version,applied_at) VALUES($ACCESS_SCHEMA_VERSION,${System.currentTimeMillis()})",
            )
            statement.execute("CREATE INDEX IF NOT EXISTS idx_staff_sessions_lookup ON staff_sessions(expires_at, revoked_at)")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_gateway_audit_time ON gateway_audit_log(occurred_at DESC)")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_gateway_audit_action ON gateway_audit_log(action, occurred_at DESC)")
            }
        }
    }

    /**
     * Creates the first admin only once.  The caller supplies a one-time secret from an environment
     * variable or local operator workflow; it is KDF-hashed and never persisted or returned.
     */
    fun bootstrapAdmin(username: String, secret: String, source: String?, now: Long = System.currentTimeMillis()): Boolean =
        synchronized(lock) {
            if (!isValidUsername(username) || !isAcceptablePassword(secret)) {
                auditInternal(now, username.takeIf { isValidUsername(it) }, username.take(64), "BOOTSTRAP", "REJECTED", source)
                return false
            }
            val consumed = metaValue("bootstrap_consumed") == "1"
            val accountsExist = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT EXISTS(SELECT 1 FROM staff_accounts)").use { it.next(); it.getInt(1) != 0 }
            }
            if (consumed || accountsExist) {
                auditInternal(now, null, username, "BOOTSTRAP", "ALREADY_INITIALIZED", source)
                return false
            }
            transaction {
                connection.prepareStatement(
                    "INSERT INTO staff_accounts(username,password_kdf,role,disabled,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                ).use { statement ->
                    statement.setString(1, username)
                    statement.setString(2, passwordHash(secret))
                    statement.setString(3, StaffRole.ADMIN.name)
                    statement.setInt(4, 0)
                    statement.setLong(5, now)
                    statement.setLong(6, now)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO gateway_security_meta(meta_key,meta_value) VALUES('bootstrap_consumed','1')",
                ).use { it.executeUpdate() }
                auditInternal(now, username, username, "BOOTSTRAP", "SUCCESS", source)
            }
            true
        }

    fun bootstrapRequired(): Boolean = synchronized(lock) {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT EXISTS(SELECT 1 FROM staff_accounts WHERE disabled=0)").use {
                it.next()
                it.getInt(1) == 0
            }
        }
    }

    /** Exposed for migration checks; no account, session, or audit data is returned. */
    fun schemaVersion(): Int = synchronized(lock) {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT MAX(version) FROM gateway_access_schema_migrations").use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    fun login(
        username: String,
        password: String,
        source: String?,
        sessionTtlMillis: Long,
        now: Long = System.currentTimeMillis(),
    ): LoginResult = synchronized(lock) {
        val account = findAccountInternal(username)
        if (account == null || !verifyPassword(password, account.passwordKdf)) {
            auditInternal(now, username.takeIf(::isValidUsername), null, "LOGIN", "REJECTED", source)
            return LoginResult.InvalidCredentials
        }
        if (account.disabled) {
            auditInternal(now, account.username, null, "LOGIN", "DISABLED", source)
            return LoginResult.Disabled
        }
        val token = randomToken()
        val expiresAt = now + sessionTtlMillis
        writeCoordinator.write {
            connection.prepareStatement(
                "INSERT INTO staff_sessions(session_hash,username,created_at,expires_at,last_seen_at) VALUES(?,?,?,?,?)",
            ).use { statement ->
                statement.setString(1, tokenHash(token))
                statement.setString(2, account.username)
                statement.setLong(3, now)
                statement.setLong(4, expiresAt)
                statement.setLong(5, now)
                statement.executeUpdate()
            }
        }
        auditInternal(now, account.username, null, "LOGIN", "SUCCESS", source)
        LoginResult.Success(
            AuthenticatedStaff(account.username, account.role, sessionId = tokenHash(token)),
            GatewaySession(token, expiresAt),
        )
    }

    /**
     * Returns null for expired/revoked sessions and for accounts disabled after login.
     *
     * A browser polls several authenticated read endpoints. Refreshing last_seen_at on every one
     * makes each poll a SQLite writer, so persist this audit hint at a bounded interval instead.
     */
    fun authenticateSession(token: String?, now: Long = System.currentTimeMillis()): AuthenticatedStaff? = synchronized(lock) {
        if (token.isNullOrBlank() || token.length !in 32..256) return null
        val hash = tokenHash(token)
        val authenticated = connection.prepareStatement(
            """
            SELECT a.username,a.role,a.disabled,s.expires_at,s.revoked_at,s.last_seen_at
            FROM staff_sessions s JOIN staff_accounts a ON a.username=s.username
            WHERE s.session_hash=?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, hash)
            statement.executeQuery().use { result ->
                if (!result.next() || result.getInt("disabled") != 0 || result.getLong("expires_at") <= now || !result.getObject("revoked_at").let { it == null }) {
                    return null
                }
                val role = runCatching { StaffRole.valueOf(result.getString("role")) }.getOrNull() ?: return null
                AuthenticatedSession(
                    staff = AuthenticatedStaff(result.getString("username"), role, sessionId = hash),
                    lastSeenAtEpochMillis = result.getLong("last_seen_at"),
                )
            }
        }
        if (now - authenticated.lastSeenAtEpochMillis >= sessionTouchIntervalMillis) {
            writeCoordinator.write {
                connection.prepareStatement(
                    "UPDATE staff_sessions SET last_seen_at=? WHERE session_hash=? AND last_seen_at <= ?",
                ).use { update ->
                    update.setLong(1, now)
                    update.setString(2, hash)
                    update.setLong(3, now - sessionTouchIntervalMillis)
                    update.executeUpdate()
                }
            }
        }
        authenticated.staff
    }

    fun revokeSession(token: String?, actor: AuthenticatedStaff?, source: String?, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (token.isNullOrBlank()) return@synchronized
        writeCoordinator.write {
            connection.prepareStatement("UPDATE staff_sessions SET revoked_at=? WHERE session_hash=? AND revoked_at IS NULL").use { statement ->
                statement.setLong(1, now)
                statement.setString(2, tokenHash(token))
                statement.executeUpdate()
            }
        }
        auditInternal(now, actor?.username, actor?.username, "LOGOUT", "SUCCESS", source)
    }

    fun createAccount(
        actor: AuthenticatedStaff,
        username: String,
        password: String,
        role: StaffRole,
        source: String?,
        now: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lock) {
        if (!isValidUsername(username) || !isAcceptablePassword(password)) {
            auditInternal(now, actor.username, username.take(64), "ACCOUNT_CREATE", "REJECTED", source)
            return false
        }
        val inserted = writeCoordinator.write {
            connection.prepareStatement(
                "INSERT OR IGNORE INTO staff_accounts(username,password_kdf,role,disabled,created_at,updated_at) VALUES(?,?,?,?,?,?)",
            ).use { statement ->
                statement.setString(1, username)
                statement.setString(2, passwordHash(password))
                statement.setString(3, role.name)
                statement.setInt(4, 0)
                statement.setLong(5, now)
                statement.setLong(6, now)
                statement.executeUpdate() == 1
            }
        }
        auditInternal(now, actor.username, username, "ACCOUNT_CREATE", if (inserted) "SUCCESS" else "REJECTED", source)
        inserted
    }

    fun updateRole(
        actor: AuthenticatedStaff,
        username: String,
        role: StaffRole,
        source: String?,
        now: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lock) {
        val current = findAccountInternal(username) ?: run {
            auditInternal(now, actor.username, username.take(64), "ACCOUNT_ROLE_CHANGE", "NOT_FOUND", source)
            return false
        }
        if (current.role == StaffRole.ADMIN && !current.disabled && role != StaffRole.ADMIN && activeAdminCount() <= 1) {
            auditInternal(now, actor.username, username, "ACCOUNT_ROLE_CHANGE", "REJECTED_LAST_ADMIN", source)
            return false
        }
        val updated = writeCoordinator.write {
            connection.prepareStatement("UPDATE staff_accounts SET role=?,updated_at=? WHERE username=?").use { statement ->
                statement.setString(1, role.name)
                statement.setLong(2, now)
                statement.setString(3, username)
                statement.executeUpdate() == 1
            }
        }
        auditInternal(now, actor.username, username, "ACCOUNT_ROLE_CHANGE", if (updated) "SUCCESS" else "REJECTED", source)
        updated
    }

    fun setAccountDisabled(
        actor: AuthenticatedStaff,
        username: String,
        disabled: Boolean,
        source: String?,
        now: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lock) {
        val current = findAccountInternal(username) ?: run {
            auditInternal(now, actor.username, username.take(64), "ACCOUNT_DISABLE", "NOT_FOUND", source)
            return false
        }
        if (disabled && current.role == StaffRole.ADMIN && !current.disabled && activeAdminCount() <= 1) {
            auditInternal(now, actor.username, username, "ACCOUNT_DISABLE", "REJECTED_LAST_ADMIN", source)
            return false
        }
        transaction {
            connection.prepareStatement("UPDATE staff_accounts SET disabled=?,updated_at=? WHERE username=?").use { statement ->
                statement.setInt(1, if (disabled) 1 else 0)
                statement.setLong(2, now)
                statement.setString(3, username)
                statement.executeUpdate()
            }
            if (disabled) {
                connection.prepareStatement("UPDATE staff_sessions SET revoked_at=? WHERE username=? AND revoked_at IS NULL").use { statement ->
                    statement.setLong(1, now)
                    statement.setString(2, username)
                    statement.executeUpdate()
                }
            }
        }
        auditInternal(now, actor.username, username, if (disabled) "ACCOUNT_DISABLE" else "ACCOUNT_ENABLE", "SUCCESS", source)
        true
    }

    fun accounts(): List<StaffAccountSummary> = synchronized(lock) {
        connection.prepareStatement(
            "SELECT username,role,disabled,created_at,updated_at FROM staff_accounts ORDER BY username COLLATE NOCASE",
        ).use { statement ->
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        val role = runCatching { StaffRole.valueOf(result.getString("role")) }.getOrNull() ?: continue
                        add(
                            StaffAccountSummary(
                                username = result.getString("username"),
                                role = role,
                                disabled = result.getInt("disabled") != 0,
                                createdAtEpochMillis = result.getLong("created_at"),
                                updatedAtEpochMillis = result.getLong("updated_at"),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Audit only metadata: no rescue body, GPS, ciphertext, token, password, or exception text. */
    fun audit(
        actor: AuthenticatedStaff?,
        targetId: String?,
        action: String,
        result: String,
        source: String?,
        now: Long = System.currentTimeMillis(),
    ) = synchronized(lock) {
        auditInternal(now, actor?.username, targetId, action, result, source)
    }

    /**
     * Persists only the caller-provided non-secret configuration summary.  A changed summary is
     * distinguishable in the durable audit trail from an ordinary restart, without retaining
     * paths, passwords, tokens, key identifiers, or other provisioning material.
     */
    fun recordRuntimeConfiguration(
        safeConfigurationTarget: String,
        source: String?,
        now: Long = System.currentTimeMillis(),
    ) = synchronized(lock) {
        val previous = metaValue("last_runtime_configuration")
        transaction {
            connection.prepareStatement(
                "INSERT INTO gateway_security_meta(meta_key,meta_value) VALUES('last_runtime_configuration',?) " +
                    "ON CONFLICT(meta_key) DO UPDATE SET meta_value=excluded.meta_value",
            ).use { statement ->
                statement.setString(1, safeConfigurationTarget.take(512))
                statement.executeUpdate()
            }
            val action = if (previous != null && previous != safeConfigurationTarget) {
                "CONFIGURATION_CHANGE"
            } else {
                "RUNTIME_CONFIGURATION"
            }
            val result = if (previous == safeConfigurationTarget) "UNCHANGED" else "APPLIED"
            auditInternal(now, null, safeConfigurationTarget, action, result, source)
        }
    }

    fun auditRecords(
        action: String? = null,
        operator: String? = null,
        targetId: String? = null,
        limit: Int = 500,
    ): List<AuditLogRecord> = synchronized(lock) {
        val clauses = mutableListOf<String>()
        val values = mutableListOf<String>()
        action?.trim()?.takeIf { it.isNotEmpty() }?.let { clauses += "action=?"; values += it.take(80) }
        operator?.trim()?.takeIf { it.isNotEmpty() }?.let { clauses += "operator_username=?"; values += it.take(64) }
        targetId?.trim()?.takeIf { it.isNotEmpty() }?.let { clauses += "target_id=?"; values += it.take(128) }
        val where = if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
        connection.prepareStatement(
            "SELECT id,occurred_at,operator_username,target_id,action,result,source_info FROM gateway_audit_log $where ORDER BY id DESC LIMIT ?",
        ).use { statement ->
            values.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.setInt(values.size + 1, limit.coerceIn(1, 5_000))
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(
                        AuditLogRecord(
                            id = result.getLong("id"),
                            occurredAtEpochMillis = result.getLong("occurred_at"),
                            operatorUsername = result.getString("operator_username"),
                            targetId = result.getString("target_id"),
                            action = result.getString("action"),
                            result = result.getString("result"),
                            source = result.getString("source_info"),
                        ),
                    )
                }
            }
        }
    }

    fun exportAuditCsv(limit: Int = 5_000): String {
        val header = "id,occurredAtEpochMillis,operatorUsername,targetId,action,result,source"
        val rows = auditRecords(limit = limit).asReversed().joinToString("\n") { record ->
            listOf(
                record.id.toString(), record.occurredAtEpochMillis.toString(), record.operatorUsername.orEmpty(),
                record.targetId.orEmpty(), record.action, record.result, record.source.orEmpty(),
            ).joinToString(",") { value -> csvEscape(value) }
        }
        return header + "\n" + rows + "\n"
    }

    private fun findAccountInternal(username: String): AccountRow? {
        if (!isValidUsername(username)) return null
        return connection.prepareStatement(
            "SELECT username,password_kdf,role,disabled,created_at,updated_at FROM staff_accounts WHERE username=?",
        ).use { statement ->
            statement.setString(1, username)
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                val role = runCatching { StaffRole.valueOf(result.getString("role")) }.getOrNull() ?: return null
                AccountRow(
                    username = result.getString("username"),
                    passwordKdf = result.getString("password_kdf"),
                    role = role,
                    disabled = result.getInt("disabled") != 0,
                )
            }
        }
    }

    private fun activeAdminCount(): Int = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM staff_accounts WHERE role='ADMIN' AND disabled=0").use { result ->
            result.next()
            result.getInt(1)
        }
    }

    private fun metaValue(key: String): String? = connection.prepareStatement(
        "SELECT meta_value FROM gateway_security_meta WHERE meta_key=?",
    ).use { statement ->
        statement.setString(1, key)
        statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
    }

    private fun auditInternal(
        now: Long,
        operator: String?,
        target: String?,
        action: String,
        result: String,
        source: String?,
    ) {
        writeCoordinator.write {
            connection.prepareStatement(
                "INSERT INTO gateway_audit_log(occurred_at,operator_username,target_id,action,result,source_info) VALUES(?,?,?,?,?,?)",
            ).use { statement ->
                statement.setLong(1, now)
                statement.setString(2, operator?.takeIf(::isValidUsername)?.take(64))
                statement.setString(3, target?.take(128))
                statement.setString(4, action.take(80).filter { it.isLetterOrDigit() || it == '_' })
                statement.setString(5, result.take(80).filter { it.isLetterOrDigit() || it == '_' })
                statement.setString(6, minimalSource(source))
                statement.executeUpdate()
            }
        }
    }

    private fun transaction(block: () -> Unit) {
        writeCoordinator.write {
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                block()
                connection.commit()
            } catch (error: Throwable) {
                runCatching { connection.rollback() }
                throw error
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    private data class AccountRow(
        val username: String,
        val passwordKdf: String,
        val role: StaffRole,
        val disabled: Boolean,
    )

    private data class AuthenticatedSession(
        val staff: AuthenticatedStaff,
        val lastSeenAtEpochMillis: Long,
    )

    override fun close() = synchronized(lock) {
        if (!connection.isClosed) connection.close()
    }

    private companion object {
        const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        const val KDF_ITERATIONS = 210_000
        const val KDF_KEY_BITS = 256
        const val MAX_KDF_ITERATIONS = 1_000_000
        const val ACCESS_SCHEMA_VERSION = 1
        const val DEFAULT_SESSION_TOUCH_INTERVAL_MILLIS = 60_000L

        fun isValidUsername(value: String): Boolean = value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{2,63}"))

        fun isAcceptablePassword(value: String): Boolean = value.length in 12..512

        fun passwordHash(password: String): String {
            val salt = ByteArray(16).also(SecureRandom()::nextBytes)
            val derived = derive(password, salt, KDF_ITERATIONS)
            return listOf(
                "pbkdf2-sha256",
                KDF_ITERATIONS.toString(),
                Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(derived),
            ).joinToString("\$")
        }

        fun verifyPassword(password: String, encoded: String): Boolean {
            val parts = encoded.split('$')
            if (parts.size != 4 || parts[0] != "pbkdf2-sha256") return false
            val iterations = parts[1].toIntOrNull() ?: return false
            if (iterations !in 100_000..MAX_KDF_ITERATIONS) return false
            return runCatching {
                val salt = Base64.getDecoder().decode(parts[2])
                val expected = Base64.getDecoder().decode(parts[3])
                salt.size in 16..64 && expected.size == KDF_KEY_BITS / 8 &&
                    MessageDigest.isEqual(expected, derive(password, salt, iterations))
            }.getOrDefault(false)
        }

        fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KDF_KEY_BITS)
            return try {
                SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        fun randomToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(32).also(SecureRandom()::nextBytes),
        )

        /** SHA-256 is appropriate here because tokens are generated with 256 bits of entropy. */
        fun tokenHash(token: String): String = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
        )

        fun minimalSource(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
            ?.filter { it.isLetterOrDigit() || it in ".:-_" }
            ?.take(80)

        /** Prefix spreadsheet formula-looking cells so an audit export cannot execute data as a formula. */
        fun csvEscape(value: String): String = csvSafeCell(value)
    }
}
