package com.example.relay.pcgateway

import com.example.relay.pcgateway.rescue.RescueDeliveryIngress
import com.example.relay.pcgateway.rescue.RescueIngestResult
import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.RescueCondition
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueLocation
import com.example.relay.rescue.RescuePayload
import com.example.relay.rescue.RescueSupportNeed
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.ShelterPublicKeyManifest
import com.example.relay.rescue.RescueValidationResult
import com.example.relay.rescue.validate
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlinx.serialization.Serializable

/** Non-identifying provenance for information entering the local pilot. */
@Serializable enum class SourceChannel { LEGACY, ANDROID_APP, LOCAL_WEB, STAFF_DESK, FACILITY_PORTAL, CSV_IMPORT, COURIER, BROKER, NEARBY }
@Serializable enum class IngressAssurance { UNVERIFIED, DEVICE_SIGNED, STAFF_CONFIRMED, FACILITY_SIGNED, SELF_REPORTED, THIRD_PARTY_REPORTED, ANONYMOUS }

/** Gateway-only provenance. It deliberately remains outside the v1 encrypted Envelope wire form. */
data class IngressMetadata(
    val requestId: String,
    val sourceChannel: SourceChannel,
    val assurance: IngressAssurance,
    val recordedAtEpochMillis: Long,
)

@Serializable data class LocalWebRescueRequest(
    val urgency: String, val personCount: Int = 1, val injured: Boolean = false, val mobilityImpaired: Boolean = false,
    val elderlyPresent: Boolean = false, val childrenPresent: Boolean = false, val pregnantPresent: Boolean = false,
    val trapped: Boolean = false, val fireOrCollapseRisk: Boolean = false, val supportNeeds: List<String> = emptyList(),
    val locationDescription: String = "", val freeText: String = "", val thirdParty: Boolean = false,
)
@Serializable data class LocalWebRescueResponse(
    val receiptId: String,
    val requestId: String,
    /** A signed Gateway receipt proves durable storage, not staff acknowledgement or dispatch. */
    val storageStatus: String = "gateway_receipt_confirmed",
)

/**
 * PUERTA persistence owns a separate schema version. It throws on migration failure and never
 * deletes or recreates a database. Endpoint code only talks to this store through PilotIngress.
 */
class PuertaStore(dbPath: String) : AutoCloseable {
    private val lock = Any(); private val writer = GatewaySqliteWriteCoordinator.forDatabase(dbPath)
    private val connection: Connection
    init { File(dbPath).parentFile?.mkdirs(); connection = DriverManager.getConnection("jdbc:sqlite:$dbPath"); writer.write {
        connection.createStatement().use { s ->
            s.execute("PRAGMA busy_timeout=5000"); s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA foreign_keys=ON")
            s.execute("CREATE TABLE IF NOT EXISTS puerta_schema_migrations(version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)")
            s.execute("CREATE TABLE IF NOT EXISTS puerta_ingress(request_id TEXT PRIMARY KEY, source_channel TEXT NOT NULL, assurance TEXT NOT NULL, recorded_at INTEGER NOT NULL)")
            s.execute("INSERT OR IGNORE INTO puerta_schema_migrations(version,applied_at) VALUES(1,${System.currentTimeMillis()})")
        }
    } }
    fun recordIngress(requestId: String, source: SourceChannel, assurance: IngressAssurance, now: Long) = synchronized(lock) { writer.write { connection.prepareStatement("INSERT OR IGNORE INTO puerta_ingress(request_id,source_channel,assurance,recorded_at) VALUES(?,?,?,?)").use { p -> p.setString(1,requestId);p.setString(2,source.name);p.setString(3,assurance.name);p.setLong(4,now);p.executeUpdate() } } }
    fun ingress(requestId: String): IngressMetadata = synchronized(lock) { connection.prepareStatement("SELECT source_channel,assurance,recorded_at FROM puerta_ingress WHERE request_id=?").use { p -> p.setString(1,requestId);p.executeQuery().use { r -> if(r.next()) IngressMetadata(requestId, runCatching { SourceChannel.valueOf(r.getString(1)) }.getOrDefault(SourceChannel.LEGACY), runCatching { IngressAssurance.valueOf(r.getString(2)) }.getOrDefault(IngressAssurance.UNVERIFIED), r.getLong(3)) else legacyMetadata(requestId) } } }
    fun schemaVersion(): Int = synchronized(lock) { connection.createStatement().executeQuery("SELECT MAX(version) FROM puerta_schema_migrations").use { r -> r.next(); r.getInt(1) } }
    override fun close()=connection.close()

    companion object {
        val LEGACY_PROVENANCE = SourceChannel.LEGACY to IngressAssurance.UNVERIFIED
        fun legacyMetadata(requestId: String) = IngressMetadata(requestId, LEGACY_PROVENANCE.first, LEGACY_PROVENANCE.second, 0)
    }
}

class PilotIngress(private val store: PuertaStore, private val manifest: ShelterPublicKeyManifest, private val delivery: RescueDeliveryIngress, private val routeAttemptSink: ((RouteAttempt) -> Unit)? = null, private val now: () -> Long = System::currentTimeMillis) {
    fun submitWeb(input: LocalWebRescueRequest): LocalWebRescueResponse {
        require(input.personCount in 0..1000 && input.locationDescription.length<=256 && input.freeText.length<=2000)
        val urgency=runCatching{RescueUrgency.valueOf(input.urgency)}.getOrElse{throw IllegalArgumentException("invalid_urgency")}
        val supportNeeds = input.supportNeeds.map { RescueSupportNeed.valueOf(it) }.toSet()
        val id="web-${UUID.randomUUID()}"; val time=now(); val payload=RescuePayload(requestId=id,senderDeviceId="local-web",destinationShelterId=manifest.shelterId,createdAtEpochMillis=time,expiresAtEpochMillis=time+24L*60*60*1000,urgency=urgency,personCount=input.personCount,conditions=buildSet { if(input.injured) add(RescueCondition.INJURED_OR_UNWELL);if(input.mobilityImpaired) add(RescueCondition.MOBILITY_IMPAIRED);if(input.trapped||input.fireOrCollapseRisk) add(RescueCondition.LIFE_THREATENING);if(supportNeeds.isNotEmpty()) add(RescueCondition.SUPPORT_NEEDED) },injured=input.injured,mobilityImpaired=input.mobilityImpaired,elderlyPresent=input.elderlyPresent,childrenPresent=input.childrenPresent,pregnantPresent=input.pregnantPresent,trapped=input.trapped,fireOrCollapseRisk=input.fireOrCollapseRisk,supportNeeds=supportNeeds,location=RescueLocation(description=input.locationDescription),freeText=input.freeText)
        require(payload.validate() == RescueValidationResult.Valid) { "invalid_payload" }
        val envelope=RescueCryptography.encrypt(payload,manifest.recipientPublicKey,"envelope-$id")
        val result=delivery.ingest(GatewayJson.encodeToString(EncryptedRescueEnvelope.serializer(),envelope).encodeToByteArray(),"local-web","local-web-$id")
        val request=when(result){is RescueIngestResult.Accepted->result.request;is RescueIngestResult.Duplicate->result.request;else->throw IllegalArgumentException("delivery_rejected")}
        store.recordIngress(id,SourceChannel.LOCAL_WEB,if(input.thirdParty) IngressAssurance.THIRD_PARTY_REPORTED else IngressAssurance.SELF_REPORTED,time)
        routeAttemptSink?.invoke(RouteAttempt(UUID.randomUUID().toString(), envelope.envelopeId, RouteType.LOCAL_WEB, time, time, RouteResult.RECEIPT_CONFIRMED, receiptId = request.receipt.receipt.receiptId))
        return LocalWebRescueResponse(request.receipt.receipt.receiptId,id)
    }
}
