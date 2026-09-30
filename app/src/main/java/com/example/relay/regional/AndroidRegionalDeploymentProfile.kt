@file:Suppress("MagicNumber", "MaxLineLength")
package com.example.relay.regional

import android.content.Context
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class AndroidRegionalDeploymentProfile(
    val protocolVersion: Int = 1,
    val regionId: String = "global",
    val displayName: String = "Configured region",
    val timezoneId: String = "UTC",
    val defaultLocale: String = "en",
    val officialSources: List<AndroidOfficialSource> = emptyList(),
) {
    fun validate() {
        require(protocolVersion == 1)
        require(Regex("[a-z0-9][a-z0-9._-]{0,127}").matches(regionId))
        require(displayName.isNotBlank() && displayName.length <= 160)
        require(ZoneId.getAvailableZoneIds().contains(timezoneId))
        require(defaultLocale.isNotBlank())
        officialSources.forEach {
            require(it.title.isNotBlank() && it.description.isNotBlank())
            require(it.url.startsWith("https://") && !it.url.contains("?") && !it.url.contains("#"))
        }
    }
    companion object {
        private val json = Json { ignoreUnknownKeys = false; isLenient = false }
        fun load(context: Context, assetName: String = "relay-regional-profile.json"): AndroidRegionalDeploymentProfile =
            runCatching { context.assets.open(assetName).bufferedReader().use { json.decodeFromString<AndroidRegionalDeploymentProfile>(it.readText()).also { p -> p.validate() } } }
                .getOrElse { AndroidRegionalDeploymentProfile() }
    }
}
@Serializable data class AndroidOfficialSource(val title:String,val description:String,val url:String)
