@file:Suppress("MagicNumber", "MaxLineLength", "ReturnCount", "TooGenericExceptionCaught")
package com.example.relay.pcgateway

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import kotlinx.serialization.Serializable

@Serializable
data class OfflineMapStatus(
    val municipality: String = "Configured region",
    val state: String,
    val cachedTiles: Int,
    val expectedTiles: Int,
    val regionId: String = "global",
    val regionName: String = "Configured region",
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
    val lastError: String? = null,
    val attribution: String = "Configured map provider",
)

open class OfflineMapTileCache(
    private val root: Path,
    private val profile: RegionalMapProfile = RegionalMapProfile(),
    private val regionId: String = "global",
    private val regionName: String = "Configured region",
) : AutoCloseable {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "relay-regional-map-cache").apply { isDaemon = true } }
    @Volatile private var preparing = false
    @Volatile private var lastError: String? = null
    private val targets = buildTargets()
    private val targetSet = targets.toHashSet()

    init { profile.validate(); Files.createDirectories(root) }

    fun status(): OfflineMapStatus {
        val cached = targets.count { (z,x,y) -> Files.isRegularFile(tilePath(z,x,y)) }
        return OfflineMapStatus(
            municipality = regionName,
            state = when { !profile.enabled -> "not_configured"; preparing -> "preparing"; cached >= targets.size -> "ready"; cached > 0 -> "partial"; else -> "not_ready" },
            cachedTiles = cached, expectedTiles = targets.size, regionId = regionId, regionName = regionName,
            enabled = profile.enabled, initialLatitude = profile.initialLatitude, initialLongitude = profile.initialLongitude,
            initialZoom = profile.initialZoom, south = profile.south, north = profile.north, west = profile.west, east = profile.east,
            minZoom = profile.minZoom, maxNativeZoom = profile.maxNativeZoom, maxZoom = profile.maxZoom,
            lastError = lastError, attribution = profile.attribution,
        )
    }

    fun prepare(): Boolean = synchronized(this) {
        if (!profile.enabled || preparing) return false
        preparing = true; lastError = null
        executor.submit {
            try { targets.forEach { (z,x,y) -> if (!Files.isRegularFile(tilePath(z,x,y))) fetchAndCache(z,x,y) } }
            catch (error: Exception) { lastError = error.message?.take(200) ?: "tile_download_failed" }
            finally { preparing = false }
        }
        true
    }

    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (!profile.enabled || z !in profile.minZoom..profile.maxNativeZoom || y < 0) return null
        val wrapped = wrapX(x,z)
        if (Triple(z,wrapped,y) !in targetSet) return null
        val path = tilePath(z,wrapped,y)
        if (Files.isRegularFile(path)) return runCatching { Files.readAllBytes(path) }.getOrNull()
        return runCatching { fetchAndCache(z,wrapped,y) }.getOrNull()
    }

    private fun fetchAndCache(z: Int, x: Int, y: Int): ByteArray {
        val template = requireNotNull(profile.tileTemplate)
        val url = template.replace("{z}",z.toString()).replace("{x}",x.toString()).replace("{y}",y.toString())
        val response = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(12)).header("User-Agent","Relay/1.0 regional deployment").build(), HttpResponse.BodyHandlers.ofByteArray())
        require(response.statusCode() == 200) { "map tile HTTP " + response.statusCode() }
        val bytes = response.body(); require(bytes.size in 100..1_000_000) { "invalid map tile size" }
        val path = tilePath(z,x,y); Files.createDirectories(path.parent); val temporary = Files.createTempFile(path.parent,".tile-",".tmp")
        try {
            Files.write(temporary,bytes)
            runCatching { Files.move(temporary,path,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                .getOrElse { Files.move(temporary,path,java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
        return bytes
    }

    private fun tilePath(z: Int,x: Int,y: Int): Path = root.resolve(z.toString()).resolve(x.toString()).resolve(y.toString() + ".png")
    private fun buildTargets(): List<Triple<Int,Int,Int>> = buildList {
        if (!profile.enabled) return@buildList
        for (zoom in profile.minZoom..profile.maxNativeZoom) {
            val count = 1 shl zoom
            val minX = lonX(profile.west,zoom); val maxX = lonX(profile.east,zoom)
            val xs = if (profile.west <= profile.east) (minX..maxX).toList() else ((minX until count).toList() + (0..maxX).toList()).distinct()
            val minY = latY(profile.north,zoom); val maxY = latY(profile.south,zoom)
            for (x in xs) for (y in minY..maxY) add(Triple(zoom,x,y))
        }
        require(size <= 50_000) { "regional map bounds produce too many tiles" }
    }
    override fun close() { executor.shutdownNow() }

    private companion object {
        fun wrapX(x:Int,z:Int):Int { val n=1 shl z; return ((x%n)+n)%n }
        fun lonX(v:Double,z:Int):Int { val n=1 shl z; return floor((v+180.0)/360.0*n).toInt().coerceIn(0,n-1) }
        fun latY(v:Double,z:Int):Int { val c=v.coerceIn(-85.05112878,85.05112878); val r=c*PI/180.0; return floor((1.0-ln(tan(r)+1.0/cos(r))/PI)/2.0*(1 shl z)).toInt().coerceIn(0,(1 shl z)-1) }
    }
}

/**
 * Compatibility facade for callers compiled against the pre-profile map API.
 * It uses bounded synthetic defaults and never embeds a real deployment location.
 */
@Deprecated("Use OfflineMapTileCache with RegionalMapProfile")
class GsiTileCache(root: Path) : OfflineMapTileCache(
    root = root,
    profile = RegionalMapProfile(
        enabled = true,
        initialLatitude = 0.0,
        initialLongitude = 0.0,
        initialZoom = 15,
        south = -1.0,
        north = 1.0,
        west = -1.0,
        east = 1.0,
        minZoom = 13,
        maxNativeZoom = 15,
        maxZoom = 18,
        tileTemplate = "https://tiles.example.invalid/{z}/{x}/{y}.png",
        attribution = "Configured map provider",
    ),
)
