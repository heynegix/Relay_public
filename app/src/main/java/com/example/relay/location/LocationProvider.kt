package com.example.relay.location

/**
 * One-shot location for report create. Implementations must not block forever.
 * Tests inject [FixedLocationProvider] / [NullLocationProvider].
 */
fun interface LocationProvider {
    /**
     * @return null if permission denied, GPS off, timeout, or unavailable.
     */
    suspend fun currentFix(timeoutMs: Long): GeoFix?
}

suspend fun LocationProvider.currentFixOrDefault(timeoutMs: Long = 4_000): GeoFix? = currentFix(timeoutMs)

data class GeoFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val capturedAtEpochMillis: Long = System.currentTimeMillis(),
)

/** Pure formatter for payload.approximateLocation (≤ maxChars). */
fun formatGpsLocation(fix: GeoFix, maxChars: Int = 100): String {
    val lat = String.format(java.util.Locale.US, "%.5f", fix.latitude)
    val lon = String.format(java.util.Locale.US, "%.5f", fix.longitude)
    val base = "GPS:$lat,$lon"
    val withAcc = if (fix.accuracyMeters != null && fix.accuracyMeters.isFinite()) {
        val acc = fix.accuracyMeters.coerceIn(0f, 99_999f).toInt()
        "$base(~${acc}m)"
    } else {
        base
    }
    return withAcc.take(maxChars)
}

/**
 * If [manualLocation] is blank, try GPS; otherwise keep manual text (trimmed).
 * Never throws for missing GPS — returns manual or empty.
 */
suspend fun resolveReportLocation(
    manualLocation: String,
    locationProvider: LocationProvider?,
    timeoutMs: Long = 4_000,
    maxChars: Int = 100,
): String {
    val trimmed = manualLocation.trim()
    if (trimmed.isNotEmpty()) return trimmed.take(maxChars)
    if (locationProvider == null) return ""
    val fix = runCatching { locationProvider.currentFixOrDefault(timeoutMs) }.getOrNull() ?: return ""
    return formatGpsLocation(fix, maxChars)
}

class FixedLocationProvider(private val fix: GeoFix?) : LocationProvider {
    override suspend fun currentFix(timeoutMs: Long): GeoFix? = fix
}

object NullLocationProvider : LocationProvider {
    override suspend fun currentFix(timeoutMs: Long): GeoFix? = null
}
