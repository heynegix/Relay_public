@file:Suppress("MagicNumber", "MaxLineLength")
package com.example.relay.pcgateway

import com.example.relay.pcgateway.official.CapAlertParser
import com.example.relay.pcgateway.official.JmaAtomFeedParser
import com.example.relay.pcgateway.official.OfficialInfoFormat
import com.example.relay.pcgateway.official.OfficialInfoProvenance
import com.example.relay.pcgateway.official.OfficialInfoRetrieval
import com.example.relay.pcgateway.official.OfficialInfoVerification
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class OfficialInformationResponse(
    val municipality: String = "Configured region",
    val checkedAtEpochMillis: Long,
    val usedCachedWarning: Boolean,
    val urgent: Boolean,
    val warningHeadline: String,
    val warningStatuses: List<String>,
    val sources: List<OfficialSource> = emptyList(),
    val provenance: OfficialInfoProvenance,
    val regionId: String = "global",
    val regionName: String = "Configured region",
    val providerName: String = "Official information",
)
@Serializable data class OfficialSource(val title: String, val organization: String, val url: String)
@Serializable private data class OfficialCacheMetadata(val fetchedAtEpochMillis: Long, val contentSha256Hex: String)
@Serializable private data class JmaWarning(val reportDatetime: String = "", val headlineText: String = "", val areaTypes: List<JmaAreaType> = emptyList())
@Serializable private data class JmaAreaType(val areas: List<JmaArea> = emptyList())
@Serializable private data class JmaArea(val code: String = "", val warnings: List<JmaAreaWarning> = emptyList())
@Serializable private data class JmaAreaWarning(val code: String? = null, val status: String = "")
private data class ParsedOfficial(val headline: String, val statuses: List<String>, val urgent: Boolean)

class OfficialInformationService(
    private val cachePath: Path,
    private val profile: RegionalDeploymentProfile = RegionalDeploymentProfile.neutral(),
    private val fetcher: (() -> String)? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    @Volatile private var lastCheckedAt = 0L
    @Volatile private var lastResponse: OfficialInformationResponse? = null

    @Synchronized fun current(now: Long = System.currentTimeMillis()): OfficialInformationResponse {
        if (now - lastCheckedAt < 5*60_000) return requireNotNull(lastResponse)
        lastCheckedAt = now
        val live = runCatching { fetcher?.invoke() ?: fetchConfigured() }.getOrNull()
        if (live != null) {
            cachePath.parent?.let(Files::createDirectories)
            runCatching {
                Files.writeString(cachePath,live)
                Files.writeString(metadataPath(),json.encodeToString(OfficialCacheMetadata.serializer(),OfficialCacheMetadata(now,sha256Hex(live))))
            }
        }
        val cached = if (live == null) runCatching { Files.readString(cachePath) }.getOrNull() else null
        val raw = live ?: cached
        val parsed = raw?.let { runCatching { parse(it) }.getOrNull() }
        return OfficialInformationResponse(
            municipality = profile.displayName, checkedAtEpochMillis = now, usedCachedWarning = live == null && raw != null,
            urgent = parsed?.urgent == true, warningHeadline = parsed?.headline ?: "気象庁の警報情報を取得できませんでした",
            warningStatuses = parsed?.statuses.orEmpty(),
            sources = profile.officialInfo.sources.map { OfficialSource(it.title,it.organization,it.url) },
            provenance = provenanceFor(now,live,cached), regionId = profile.regionId, regionName = profile.displayName,
            providerName = profile.officialInfo.providerName,
        ).also { lastResponse = it }
    }

    private fun fetchConfigured(): String? {
        if (!profile.officialInfo.enabled) return null
        val endpoint = requireNotNull(profile.officialInfo.endpoint)
        val response = http.send(HttpRequest.newBuilder(URI(endpoint)).timeout(Duration.ofSeconds(10)).header("User-Agent","Relay/1.0 regional deployment").build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        require(response.statusCode() == 200) { "official information HTTP " + response.statusCode() }
        return response.body()
    }
    private fun parse(raw: String): ParsedOfficial = when (profile.officialInfo.format) {
        OfficialInfoFormat.JMA_BOSAI_JSON -> {
            val p = json.decodeFromString<JmaWarning>(raw)
            val areas = p.areaTypes.asSequence().flatMap { it.areas.asSequence() }
            val target = profile.officialInfo.areaCode?.let { c -> areas.firstOrNull { it.code == c } } ?: areas.firstOrNull()
            val statuses = target?.warnings.orEmpty().map { listOfNotNull(it.code,it.status).joinToString(": ") }
            ParsedOfficial(p.headlineText.ifBlank { "公式情報を取得しました" },statuses,target?.warnings.orEmpty().any { it.status.contains("発表") || it.status.contains("warning",true) || it.status.contains("alert",true) })
        }
        OfficialInfoFormat.JMA_XML_ATOM_FEED -> {
            val feed = JmaAtomFeedParser.parse(raw); val statuses = feed.entries.map { it.title }
            ParsedOfficial(feed.title,statuses,statuses.any { it.contains("警報") || it.contains("warning",true) })
        }
        OfficialInfoFormat.CAP_1_2 -> {
            val alert = CapAlertParser.parse(raw)
            val statuses = alert.infos.flatMap { listOfNotNull(it.event,it.headline,it.description) }.take(32)
            ParsedOfficial(alert.infos.firstOrNull()?.headline ?: alert.infos.firstOrNull()?.event ?: alert.identifier,statuses,
                alert.status == com.example.relay.pcgateway.official.CapStatus.ACTUAL && alert.infos.any { it.urgency.uppercase() in setOf("IMMEDIATE","EXPECTED") && it.severity.uppercase() in setOf("EXTREME","SEVERE") })
        }
    }
    private fun provenanceFor(now:Long,live:String?,cached:String?): OfficialInfoProvenance {
        val metadata = if (live == null && cached != null) readMetadata(cached) else null
        val url = profile.officialInfo.endpoint.orEmpty(); val format = profile.officialInfo.format
        return when {
            live != null -> OfficialInfoProvenance(url,format,OfficialInfoRetrieval.LIVE_FETCH,OfficialInfoVerification.TRANSPORT_TLS_ONLY,now,now,sha256Hex(live))
            cached != null -> OfficialInfoProvenance(url,format,OfficialInfoRetrieval.LOCAL_CACHE,OfficialInfoVerification.CACHED_UNVERIFIED,now,metadata?.fetchedAtEpochMillis,sha256Hex(cached))
            else -> OfficialInfoProvenance(url,format,OfficialInfoRetrieval.UNAVAILABLE,OfficialInfoVerification.UNVERIFIED,now)
        }
    }
    private fun readMetadata(cached:String):OfficialCacheMetadata? = runCatching { json.decodeFromString<OfficialCacheMetadata>(Files.readString(metadataPath())) }.getOrNull()?.takeIf { it.contentSha256Hex == sha256Hex(cached) }
    private fun metadataPath():Path = cachePath.resolveSibling(cachePath.fileName.toString()+".meta.json")
    private fun sha256Hex(content:String):String = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
