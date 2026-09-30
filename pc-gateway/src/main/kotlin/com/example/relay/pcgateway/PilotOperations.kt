package com.example.relay.pcgateway

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types
import java.time.OffsetDateTime
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable enum class ObservationType { IN_PERSON_CONFIRMED, SHELTER_CHECKED_IN, VEHICLE_BOARDED, SUPPORT_NEEDED, THIRD_PARTY_REPORT, DEVICE_OBSERVED, PLACE_CONFIRMED }
@Serializable enum class SupportFlag { MOBILITY, POWER, CHILDREN, COMMUNICATION, MEDICATION_CONSIDERATION }
@Serializable enum class RouteType { NEARBY, LAN_GATEWAY, HTTPS_BROKER, COURIER, LOCAL_WEB }
@Serializable enum class RouteResult { CONNECTION_SUCCEEDED, API_ACCEPTED, PAYLOAD_TRANSFERRED, PEER_ACK, BROKER_STORED, GATEWAY_STORED, RECEIPT_CONFIRMED, FAILED }

@Serializable data class ObservationInput(val subjectToken: String, val observationType: ObservationType, val locationCell: String? = null)
@Serializable data class ObservationRecord(val observationId: String, val subjectToken: String, val observationType: ObservationType, val sourceChannel: SourceChannel, val assurance: IngressAssurance, val observedAtEpochMillis: Long, val locationCell: String?, val expiresAtEpochMillis: Long)
@Serializable data class BaselineSubjectInput(val subjectToken: String, val groupId: String? = null, val supportFlags: Set<SupportFlag> = emptySet(), val reviewDueAtEpochMillis: Long)
@Serializable data class SupportProfileInput(val subjectToken: String, val groupId: String? = null, val supportFlags: Set<SupportFlag>, val reviewDueAtEpochMillis: Long)
@Serializable data class SupportProfileRecord(val subjectToken: String, val groupId: String?, val supportFlags: Set<SupportFlag>, val reviewDueAtEpochMillis: Long, val revokedAtEpochMillis: Long?)
@Serializable data class ReviewOverrideInput(val subjectToken: String, val state: String, val priority: String? = null, val note: String? = null)
@Serializable data class ReviewCandidate(val subjectToken: String, val state: String, val priority: String, val rationale: String, val lastObservedAtEpochMillis: Long? = null, val manualOverride: Boolean = false, val profileExpired: Boolean = false)
@Serializable data class RouteAttempt(val id: String, val envelopeId: String, val routeType: RouteType, val attemptedAtEpochMillis: Long, val completedAtEpochMillis: Long? = null, val result: RouteResult, val safeErrorCode: String? = null, val receiptId: String? = null)
@Serializable data class CsvPreview(val kind: String, val validRows: Int, val duplicateRows: Int, val rejectedRows: Int, val errors: List<String>, val dryRun: Boolean)
@Serializable data class CsvImportRequest(val kind: String, val csv: String, val dryRun: Boolean = true)

/**
 * Batch B-D drill records. This store keeps opaque subject tokens and safe route metadata only;
 * it never stores names, detailed medical information, ciphertext, or exception strings.
 */
class PilotOperationsStore(dbPath: String) : AutoCloseable {
    private val lock = Any(); private val writer = GatewaySqliteWriteCoordinator.forDatabase(dbPath)
    private val connection: Connection
    init { File(dbPath).parentFile?.mkdirs(); connection = DriverManager.getConnection("jdbc:sqlite:$dbPath"); migrate() }

    fun addObservation(input: ObservationInput, source: SourceChannel, assurance: IngressAssurance, now: Long = System.currentTimeMillis()): ObservationRecord {
        require(subjectToken(input.subjectToken)); require(input.locationCell == null || input.locationCell.length <= 64)
        val row = ObservationRecord(UUID.randomUUID().toString(), input.subjectToken, input.observationType, source, assurance, now, input.locationCell, now + OBSERVATION_TTL)
        transaction { connection.prepareStatement("INSERT INTO pilot_observations VALUES(?,?,?,?,?,?,?,?)").use { s ->
            s.setString(1,row.observationId); s.setString(2,row.subjectToken); s.setString(3,row.observationType.name); s.setString(4,row.sourceChannel.name); s.setString(5,row.assurance.name); s.setLong(6,row.observedAtEpochMillis); s.setString(7,row.locationCell); s.setLong(8,row.expiresAtEpochMillis); s.executeUpdate() } }
        return row
    }
    fun observations(subjectToken: String? = null): List<ObservationRecord> = synchronized(lock) {
        val sql = if (subjectToken == null) "SELECT * FROM pilot_observations ORDER BY observed_at DESC" else "SELECT * FROM pilot_observations WHERE subject_token=? ORDER BY observed_at DESC"
        connection.prepareStatement(sql).use { s -> subjectToken?.let { s.setString(1,it) }; s.executeQuery().use { r -> buildList { while(r.next()) add(ObservationRecord(r.getString(1),r.getString(2),ObservationType.valueOf(r.getString(3)),SourceChannel.valueOf(r.getString(4)),IngressAssurance.valueOf(r.getString(5)),r.getLong(6),r.getString(7),r.getLong(8))) } } }
    }
    fun saveBaseline(input: BaselineSubjectInput) = saveSubject("pilot_baseline_subjects", input.subjectToken, input.groupId, input.supportFlags, input.reviewDueAtEpochMillis, false)
    fun saveProfile(input: SupportProfileInput) = saveSubject("pilot_support_profiles", input.subjectToken, input.groupId, input.supportFlags, input.reviewDueAtEpochMillis, false)
    fun revokeProfile(subject: String, now: Long = System.currentTimeMillis()) { require(subjectToken(subject)); transaction { connection.prepareStatement("UPDATE pilot_support_profiles SET revoked_at=? WHERE subject_token=?").use { s -> s.setLong(1,now);s.setString(2,subject);s.executeUpdate() } } }
    fun profiles(): List<SupportProfileRecord> = synchronized(lock) { connection.createStatement().executeQuery("SELECT subject_token,group_id,flags,review_due_at,revoked_at FROM pilot_support_profiles").use { r -> buildList { while(r.next()) add(SupportProfileRecord(r.getString(1),r.getString(2),flags(r.getString(3)),r.getLong(4),r.getLong(5).takeIf{!r.wasNull()})) } } }

    fun saveOverride(input: ReviewOverrideInput) { require(subjectToken(input.subjectToken)); require(input.state in setOf("確認済み","再確認対象","本人申告あり","第三者情報あり・要確認","端末観測のみ・本人未確認","未確認")); require(input.priority == null || input.priority in setOf("高","中","低","完了")); require(input.note == null || input.note.length <= 256); transaction { connection.prepareStatement("INSERT INTO pilot_review_overrides(subject_token,state,priority,note,updated_at) VALUES(?,?,?,?,?) ON CONFLICT(subject_token) DO UPDATE SET state=excluded.state,priority=excluded.priority,note=excluded.note,updated_at=excluded.updated_at").use { s -> s.setString(1,input.subjectToken);s.setString(2,input.state);s.setString(3,input.priority);s.setString(4,input.note);s.setLong(5,System.currentTimeMillis());s.executeUpdate() } } }
    fun reviewQueue(now: Long = System.currentTimeMillis()): List<ReviewCandidate> = synchronized(lock) {
        val subjects = linkedMapOf<String, Pair<Set<SupportFlag>, Boolean>>()
        connection.createStatement().executeQuery("SELECT subject_token,flags,review_due_at FROM pilot_baseline_subjects").use { r -> while(r.next()) subjects[r.getString(1)] = flags(r.getString(2)) to (r.getLong(3) < now) }
        connection.createStatement().executeQuery("SELECT subject_token,flags,review_due_at,revoked_at FROM pilot_support_profiles").use { r -> while(r.next()) if(r.getLong(4).let{r.wasNull()}) { val old=subjects[r.getString(1)] ?: (emptySet<SupportFlag>() to false); val expired=r.getLong(3)<now; subjects[r.getString(1)] = (if(expired) old.first else old.first + flags(r.getString(2))) to (old.second || expired) } }
        connection.prepareStatement("SELECT DISTINCT subject_token FROM pilot_observations WHERE expires_at > ?").use { s -> s.setLong(1, now); s.executeQuery().use { r -> while(r.next()) subjects.putIfAbsent(r.getString(1), emptySet<SupportFlag>() to false) } }
        subjects.map { (subject, profile) -> candidate(subject, profile.first, profile.second, now) }.sortedByDescending { priorityWeight(it.priority) }
    }
    fun recordRouteAttempt(attempt: RouteAttempt) { require(attempt.envelopeId.length in 1..128); require(attempt.safeErrorCode == null || attempt.safeErrorCode.length <= 80); transaction { connection.prepareStatement("INSERT INTO pilot_route_attempts VALUES(?,?,?,?,?,?,?,?)").use { s -> s.setString(1,attempt.id);s.setString(2,attempt.envelopeId);s.setString(3,attempt.routeType.name);s.setLong(4,attempt.attemptedAtEpochMillis);attempt.completedAtEpochMillis?.let{s.setLong(5,it)}?:s.setNull(5,Types.BIGINT);s.setString(6,attempt.result.name);s.setString(7,attempt.safeErrorCode);s.setString(8,attempt.receiptId);s.executeUpdate() } } }
    fun routeAttempts(envelopeId: String): List<RouteAttempt> = synchronized(lock) { connection.prepareStatement("SELECT * FROM pilot_route_attempts WHERE envelope_id=? ORDER BY attempted_at DESC").use { s -> s.setString(1,envelopeId);s.executeQuery().use { r -> buildList { while(r.next()) add(RouteAttempt(r.getString(1),r.getString(2),RouteType.valueOf(r.getString(3)),r.getLong(4),r.getLong(5).takeIf{!r.wasNull()},RouteResult.valueOf(r.getString(6)),r.getString(7),r.getString(8))) } } } }

    fun previewCsv(kind: String, raw: String, dryRun: Boolean): CsvPreview {
        require(raw.encodeToByteArray().size <= MAX_CSV_BYTES)
        val rows = csv(raw); require(rows.size in 2..MAX_CSV_ROWS + 1)
        val header = rows.first().map { it.trim() }
        val required = when (kind) { "baseline", "profile" -> setOf("subject_token", "review_due_at"); "observation" -> setOf("subject_token", "observation_type", "observed_at"); else -> throw IllegalArgumentException("kind") }
        require(required.all { it in header }) { "missing_required_column" }
        val errors=mutableListOf<String>(); var valid=0; var duplicates=0; val seen=mutableSetOf<String>()
        rows.drop(1).forEachIndexed { i,row -> try {
            require(row.size == header.size) { "column_count" }; val map=header.zip(row).toMap()
            val key = when(kind) { "baseline", "profile" -> map.getValue("subject_token"); else -> "${map.getValue("subject_token")}:${map.getValue("observed_at")}" }
            if(!seen.add(key)) { duplicates++; return@forEachIndexed }
            if(!subjectToken(map.getValue("subject_token"))) error("subject_token")
            when(kind) { "baseline", "profile" -> { parseTime(map.getValue("review_due_at")); CSV_BOOLEAN_FIELDS.forEach { field -> map[field]?.let { require(it in setOf("true", "false")) { "invalid_boolean" } } } }; "observation" -> { ObservationType.valueOf(map.getValue("observation_type")); parseTime(map.getValue("observed_at")) } }
            valid++
        } catch(_: Exception) { errors += "row ${i+2}: invalid_value" } }
        return CsvPreview(kind,valid,duplicates,errors.size,errors.take(100),dryRun)
    }
    fun importCsv(request: CsvImportRequest): CsvPreview { require(request.csv.encodeToByteArray().size <= MAX_CSV_BYTES); val preview=previewCsv(request.kind,request.csv,request.dryRun); if(request.dryRun || preview.rejectedRows > 0) return preview; val rows=csv(request.csv); val header=rows.first().map{it.trim()}; transaction { val seen=mutableSetOf<String>(); rows.drop(1).forEach { row -> val map=header.zip(row).toMap(); val key=if(request.kind=="observation")"${map.getValue("subject_token")}:${map.getValue("observed_at")}" else map.getValue("subject_token"); if(!seen.add(key)) return@forEach; when(request.kind) { "baseline" -> saveSubjectInTransaction("pilot_baseline_subjects",map); "profile" -> saveSubjectInTransaction("pilot_support_profiles",map); "observation" -> insertObservationInTransaction(map); else -> error("kind") } } }; return preview }

    override fun close()=connection.close()
    private fun candidate(subject: String, flags: Set<SupportFlag>, expired: Boolean, now: Long): ReviewCandidate { val latest=observations(subject).firstOrNull { it.expiresAtEpochMillis > now }; val base=when(latest?.observationType) { ObservationType.IN_PERSON_CONFIRMED,ObservationType.SHELTER_CHECKED_IN -> ReviewCandidate(subject,"確認済み","完了","対面または到着確認",latest.observedAtEpochMillis); ObservationType.THIRD_PARTY_REPORT -> ReviewCandidate(subject,"第三者情報あり・要確認","中","第三者からの情報",latest.observedAtEpochMillis); ObservationType.DEVICE_OBSERVED -> ReviewCandidate(subject,"端末観測のみ・本人未確認","中","端末の観測だけでは本人確認にならない",latest.observedAtEpochMillis); else -> ReviewCandidate(subject,"未確認",if(SupportFlag.MOBILITY in flags || SupportFlag.POWER in flags)"高" else "中","情報不足",latest?.observedAtEpochMillis) }; val override=connection.prepareStatement("SELECT state,priority FROM pilot_review_overrides WHERE subject_token=?").use{s->s.setString(1,subject);s.executeQuery().use{r->if(r.next())r.getString(1) to r.getString(2) else null}}; return if(override==null) base.copy(profileExpired=expired) else base.copy(state=override.first,priority=override.second?:base.priority,manualOverride=true,profileExpired=expired) }
    private fun saveSubject(table:String, token:String, group:String?, values:Set<SupportFlag>, due:Long, revoked:Boolean) { require(subjectToken(token));require(group==null||group.length<=64);require(due>0);transaction { connection.prepareStatement("INSERT INTO $table(subject_token,group_id,flags,review_due_at,revoked_at) VALUES(?,?,?,?,?) ON CONFLICT(subject_token) DO UPDATE SET group_id=excluded.group_id,flags=excluded.flags,review_due_at=excluded.review_due_at,revoked_at=excluded.revoked_at").use{s->s.setString(1,token);s.setString(2,group);s.setString(3,values.joinToString(","));s.setLong(4,due);if(revoked)s.setLong(5,System.currentTimeMillis())else s.setNull(5,Types.BIGINT);s.executeUpdate()} } }
    private fun saveSubjectInTransaction(table:String,map:Map<String,String>) { val token=map.getValue("subject_token");val flags=buildSet { if(csvBoolean(map,"support_mobility"))add(SupportFlag.MOBILITY);if(csvBoolean(map,"support_power"))add(SupportFlag.POWER);if(csvBoolean(map,"children_present"))add(SupportFlag.CHILDREN) };connection.prepareStatement("INSERT INTO $table(subject_token,group_id,flags,review_due_at,revoked_at) VALUES(?,?,?,?,NULL) ON CONFLICT(subject_token) DO UPDATE SET group_id=excluded.group_id,flags=excluded.flags,review_due_at=excluded.review_due_at,revoked_at=NULL").use{s->s.setString(1,token);s.setString(2,map["group_id"]);s.setString(3,flags.joinToString(","));s.setLong(4,parseTime(map.getValue("review_due_at")));s.executeUpdate()} }
    private fun insertObservationInTransaction(map:Map<String,String>) { connection.prepareStatement("INSERT INTO pilot_observations VALUES(?,?,?,?,?,?,?,?)").use{s->val now=parseTime(map.getValue("observed_at"));s.setString(1,UUID.randomUUID().toString());s.setString(2,map.getValue("subject_token"));s.setString(3,ObservationType.valueOf(map.getValue("observation_type")).name);s.setString(4,SourceChannel.CSV_IMPORT.name);s.setString(5,IngressAssurance.UNVERIFIED.name);s.setLong(6,now);s.setNull(7,Types.VARCHAR);s.setLong(8,now+OBSERVATION_TTL);s.executeUpdate()} }
    private fun migrate() {
        // SQLite rejects journal-mode changes inside an explicit transaction.  Keep the
        // migration itself atomic, but establish its safe connection settings first.
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA busy_timeout=5000")
            statement.execute("PRAGMA journal_mode=WAL")
        }
        transaction {
            connection.createStatement().use { s ->
                s.execute("CREATE TABLE IF NOT EXISTS pilot_operations_schema_migrations(version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)")
                s.execute("CREATE TABLE IF NOT EXISTS pilot_observations(observation_id TEXT PRIMARY KEY,subject_token TEXT NOT NULL,observation_type TEXT NOT NULL,source_channel TEXT NOT NULL,assurance TEXT NOT NULL,observed_at INTEGER NOT NULL,location_cell TEXT,expires_at INTEGER NOT NULL)")
                s.execute("CREATE TABLE IF NOT EXISTS pilot_baseline_subjects(subject_token TEXT PRIMARY KEY,group_id TEXT,flags TEXT NOT NULL,review_due_at INTEGER NOT NULL,revoked_at INTEGER)")
                s.execute("CREATE TABLE IF NOT EXISTS pilot_support_profiles(subject_token TEXT PRIMARY KEY,group_id TEXT,flags TEXT NOT NULL,review_due_at INTEGER NOT NULL,revoked_at INTEGER)")
                s.execute("CREATE TABLE IF NOT EXISTS pilot_review_overrides(subject_token TEXT PRIMARY KEY,state TEXT NOT NULL,priority TEXT,note TEXT,updated_at INTEGER NOT NULL)")
                s.execute("CREATE TABLE IF NOT EXISTS pilot_route_attempts(id TEXT PRIMARY KEY,envelope_id TEXT NOT NULL,route_type TEXT NOT NULL,attempted_at INTEGER NOT NULL,completed_at INTEGER,result TEXT NOT NULL,safe_error_code TEXT,receipt_id TEXT)")
                s.execute("CREATE INDEX IF NOT EXISTS idx_pilot_observations_subject ON pilot_observations(subject_token,observed_at DESC)")
                s.execute("CREATE INDEX IF NOT EXISTS idx_pilot_routes_envelope ON pilot_route_attempts(envelope_id,attempted_at DESC)")
                s.execute("INSERT OR IGNORE INTO pilot_operations_schema_migrations VALUES(1,${System.currentTimeMillis()})")
            }
        }
    }
    private fun <T> transaction(block:()->T):T=synchronized(lock){writer.write{val auto=connection.autoCommit;connection.autoCommit=false;try{block().also{connection.commit()}}catch(e:Throwable){runCatching{connection.rollback()};throw e}finally{connection.autoCommit=auto}}}
    private fun subjectToken(value:String)=value.length in 3..128&&value.all{it.isLetterOrDigit()||it in "-_.:"}
    private fun csvBoolean(row:Map<String,String>, field:String):Boolean = row[field]?.also { require(it in setOf("true", "false")) { "invalid_boolean" } } == "true"
    private fun flags(raw:String)=raw.split(',').filter{it.isNotBlank()}.map(SupportFlag::valueOf).toSet()
    private fun priorityWeight(value:String)=mapOf("高" to 3,"中" to 2,"低" to 1,"完了" to 0)[value]?:0
    private fun parseTime(value:String)=runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrElse { LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
    private fun csv(input:String):List<List<String>> { val out=mutableListOf<List<String>>();var row=mutableListOf<String>();val cell=StringBuilder();var quoted=false;var i=0;val raw=input.removePrefix("\uFEFF");while(i<raw.length){val c=raw[i];if(c=='"'){if(quoted&&i+1<raw.length&&raw[i+1]=='"'){cell.append(c);i++}else quoted=!quoted}else if(c==','&&!quoted){row+=cell.toString();cell.clear()}else if((c=='\n'||c=='\r')&&!quoted){if(c=='\r'&&i+1<raw.length&&raw[i+1]=='\n')i++;row+=cell.toString();cell.clear();if(row.any{it.isNotEmpty()})out+=row;row=mutableListOf()}else cell.append(c);i++};if(quoted)throw IllegalArgumentException("unterminated_quote");if(cell.isNotEmpty()||row.isNotEmpty()){row+=cell.toString();out+=row};return out }
    companion object { const val MAX_CSV_ROWS=5_000;const val MAX_CSV_BYTES=256*1024;const val OBSERVATION_TTL=30L*24*60*60*1000;private val CSV_BOOLEAN_FIELDS=setOf("support_mobility","support_power","children_present") }
}
