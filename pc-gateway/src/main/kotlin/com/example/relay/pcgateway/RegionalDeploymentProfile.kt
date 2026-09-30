@file:Suppress("MagicNumber", "MaxLineLength", "CyclomaticComplexMethod")
package com.example.relay.pcgateway

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class RegionalDeploymentProfile(
    val protocolVersion: Int = 1,
    val regionId: String = "global",
    val displayName: String = "Configured region",
    val countryCode: String = "XX",
    val subdivisionCode: String? = null,
    val municipality: String? = null,
    val timezoneId: String = "UTC",
    val defaultLocale: String = "en",
    val supportedLocales: List<String> = listOf("en"),
    val map: RegionalMapProfile = RegionalMapProfile(),
    val officialInfo: RegionalOfficialInfoProfile = RegionalOfficialInfoProfile(),
) {
    fun validate() {
        require(protocolVersion == 1) { "unsupported regional profile protocol" }
        require(Regex("[a-z0-9][a-z0-9._-]{0,127}").matches(regionId)) { "regionId must be a lowercase stable identifier" }
        require(displayName.isNotBlank() && displayName.length <= 160) { "displayName is invalid" }
        require(countryCode.matches(Regex("[A-Z]{2}|XX"))) { "countryCode must be ISO 3166-1 alpha-2 or XX" }
        require(ZoneId.getAvailableZoneIds().contains(timezoneId)) { "timezoneId is invalid" }
        require(defaultLocale.isNotBlank() && supportedLocales.isNotEmpty() && defaultLocale in supportedLocales) {
            "defaultLocale must be included in supportedLocales"
        }
        map.validate()
        officialInfo.validate()
    }
    companion object { fun neutral() = RegionalDeploymentProfile() }
}

@Serializable
data class RegionalMapProfile(
    val enabled: Boolean = false,
    val initialLatitude: Double = 0.0,
    val initialLongitude: Double = 0.0,
    val initialZoom: Int = 2,
    val south: Double = -1.0,
    val north: Double = 1.0,
    val west: Double = -1.0,
    val east: Double = 1.0,
    val minZoom: Int = 0,
    val maxNativeZoom: Int = 2,
    val maxZoom: Int = 5,
    val tileTemplate: String? = null,
    val attribution: String = "Configured map provider",
) {
    fun validate() {
        require(initialLatitude in -90.0..90.0 && initialLongitude in -180.0..180.0) { "initial map center is invalid" }
        require(south in -90.0..90.0 && north in -90.0..90.0 && south <= north) { "map latitude bounds are invalid" }
        require(west in -180.0..180.0 && east in -180.0..180.0) { "map longitude bounds are invalid" }
        require(initialLatitude in south..north) { "initial latitude is outside map bounds" }
        require(minZoom in 0..22 && maxNativeZoom in minZoom..22 && maxZoom in maxNativeZoom..24) { "map zoom limits are invalid" }
        if (enabled) {
            val template = requireNotNull(tileTemplate) { "enabled map requires tileTemplate" }
            require(template.contains("{z}") && template.contains("{x}") && template.contains("{y}")) { "tileTemplate must contain XYZ placeholders" }
        }
        tileTemplate?.let {
            val uri = runCatching { URI(it.replace("{z}","0").replace("{x}","0").replace("{y}","0")) }.getOrNull()
            require(uri != null && uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "tileTemplate must be HTTPS without credentials or query parameters"
            }
        }
    }
}

@Serializable
data class RegionalOfficialInfoProfile(
    val enabled: Boolean = false,
    val providerName: String = "Official information",
    val endpoint: String? = null,
    val format: com.example.relay.pcgateway.official.OfficialInfoFormat = com.example.relay.pcgateway.official.OfficialInfoFormat.JMA_BOSAI_JSON,
    val areaCode: String? = null,
    val sources: List<RegionalOfficialSource> = emptyList(),
) {
    fun validate() {
        require(providerName.isNotBlank() && providerName.length <= 160) { "providerName is invalid" }
        endpoint?.let { validateHttps(it) }
        sources.forEach { validateHttps(it.url) }
        if (enabled) require(endpoint != null) { "enabled official information requires endpoint" }
    }
}

@Serializable
data class RegionalOfficialSource(val title: String, val organization: String, val url: String) {
    init {
        require(title.isNotBlank() && organization.isNotBlank())
        validateHttps(url)
    }
}

private fun validateHttps(value: String) {
    val uri = runCatching { URI(value) }.getOrNull()
    require(uri != null && uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "official endpoints must be HTTPS URLs without credentials, query, or fragments"
    }
}

object RegionalDeploymentProfileLoader {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }
    fun load(path: Path): RegionalDeploymentProfile {
        if (!Files.isRegularFile(path)) return RegionalDeploymentProfile.neutral()
        return json.decodeFromString<RegionalDeploymentProfile>(Files.readString(path)).also { it.validate() }
    }
}
