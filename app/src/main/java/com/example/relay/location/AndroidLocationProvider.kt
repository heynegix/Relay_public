package com.example.relay.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bounded location lookup used by reports and by the rescue tracker's periodic fixes:
 * 1) Prefer a fresh last-known fix
 * 2) Otherwise [requestOneShotUpdate] (getCurrentLocation / requestLocationUpdates) within timeout
 *
 * The rescue ViewModel calls this repeatedly while a request is active. Unit tests inject
 * [FixedLocationProvider].
 */
class AndroidLocationProvider(
    private val context: Context,
) : LocationProvider {
    @SuppressLint("MissingPermission")
    override suspend fun currentFix(timeoutMs: Long): GeoFix? = withContext(Dispatchers.IO) {
        if (!hasLocationPermission()) return@withContext null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext null
        val budget = timeoutMs.coerceIn(500, 12_000)
        val last = bestLastKnown(manager)
        if (last != null && isFreshEnough(last)) {
            return@withContext last.toGeoFix()
        }
        val oneShot = requestOneShotUpdate(manager, budget)
        oneShot ?: last?.toGeoFix()
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnown(manager: LocationManager): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        return providers.mapNotNull { provider ->
            runCatching {
                if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null
            }.getOrNull()
        }.maxByOrNull { it.time }
    }

    private fun isFreshEnough(location: Location): Boolean {
        val wallAge = System.currentTimeMillis() - location.time
        return wallAge in 0..(5 * 60_000L)
    }

    /**
     * Real one-shot: API 30+ getCurrentLocation, else single location update.
     * Exposed for structural tests via companion pure selector.
     */
    @SuppressLint("MissingPermission")
    private suspend fun requestOneShotUpdate(manager: LocationManager, timeoutMs: Long): GeoFix? {
        val provider = preferredProvider(manager) ?: return null
        return withTimeoutOrNull(timeoutMs) {
            if (Build.VERSION.SDK_INT >= 30) {
                getCurrentLocationApi30(manager, provider)
            } else {
                requestSingleUpdateLegacy(manager, provider)
            }
        }
    }

    @RequiresApi(30)
    @SuppressLint("MissingPermission")
    private suspend fun getCurrentLocationApi30(manager: LocationManager, provider: String): GeoFix? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            val executor = ContextCompat.getMainExecutor(context)
            runCatching {
                manager.getCurrentLocation(provider, signal, executor) { location ->
                    if (cont.isActive) {
                        cont.resume(location?.toGeoFix())
                    }
                }
            }.onFailure {
                if (cont.isActive) cont.resume(null)
            }
        }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleUpdateLegacy(manager: LocationManager, provider: String): GeoFix? =
        suspendCancellableCoroutine { cont ->
            val looper = Looper.getMainLooper()
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    manager.removeUpdates(this)
                    if (cont.isActive) cont.resume(location.toGeoFix())
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) {
                    manager.removeUpdates(this)
                    if (cont.isActive) cont.resume(null)
                }
            }
            cont.invokeOnCancellation {
                manager.removeUpdates(listener)
            }
            runCatching {
                manager.requestLocationUpdates(provider, 0L, 0f, listener, looper)
            }.onFailure {
                if (cont.isActive) cont.resume(null)
            }
        }

    private fun preferredProvider(manager: LocationManager): String? = when {
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        else -> null
    }

    private fun Location.toGeoFix(): GeoFix = GeoFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        capturedAtEpochMillis = time.takeIf { it > 0 } ?: System.currentTimeMillis(),
    )
}

/**
 * Pure selection used by tests: prefer fresh last-known, else one-shot result, else stale last-known.
 */
fun pickLocationFix(
    lastKnown: GeoFix?,
    lastKnownFresh: Boolean,
    oneShot: GeoFix?,
): GeoFix? = when {
    lastKnown != null && lastKnownFresh -> lastKnown
    oneShot != null -> oneShot
    else -> lastKnown
}
