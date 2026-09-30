package com.example.relay.permissions

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.relay.transport.NearbyPermissionGate

/**
 * Runtime permissions for disaster Relay:
 * - Nearby / Bluetooth (transport)
 * - Location (mandatory rescue location; also required for legacy Nearby APIs)
 *
 * Location is requested together with Nearby on start so GPS is not a dead path on targetSdk 36.
 * Legacy report creation may continue without a fix; the rescue flow enforces location separately.
 */
object NearbyPermissionPolicy {
    fun requiredRuntimePermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> {
        val transport = transportPermissions(sdkInt)
        val location = locationPermissionsForGps(sdkInt)
        return (transport + location).distinct()
    }

    /** Permissions needed only for peer transport (not GPS create-path on API 32+). */
    fun transportPermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> = when {
        sdkInt >= 33 -> listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        sdkInt == 32 -> bluetoothRuntimePermissions
        sdkInt == 31 -> listOf(Manifest.permission.ACCESS_FINE_LOCATION) + bluetoothRuntimePermissions
        sdkInt >= 29 -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    /**
     * GPS create-path permissions for APIs where transport no longer implies location grant.
     * API 31 also needs coarse location in the same runtime request as fine location.
     */
    fun locationPermissionsForGps(sdkInt: Int = Build.VERSION.SDK_INT): List<String> = when {
        sdkInt >= 31 -> listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        else -> emptyList()
    }

    fun optionalNotificationPermission(sdkInt: Int = Build.VERSION.SDK_INT): String? =
        Manifest.permission.POST_NOTIFICATIONS.takeIf { sdkInt >= 33 }

    private val bluetoothRuntimePermissions = listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
    )
}

class AndroidNearbyPermissionGate(private val context: Context) : NearbyPermissionGate {
    fun missingPermissions(): List<String> = NearbyPermissionPolicy.requiredRuntimePermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun missingTransportPermissions(): List<String> = NearbyPermissionPolicy.transportPermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun missingLocationPermissions(): List<String> {
        val gps = NearbyPermissionPolicy.locationPermissionsForGps()
        val allLocation = if (gps.isNotEmpty()) {
            gps
        } else {
            // Legacy SDKs: fine/coarse already in transport list
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }
        return allLocation.filter {
            ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }.distinct()
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            coarse == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Nearby transport only — location denial must not block peer SCF. */
    override fun canUseNearby(): Boolean = missingTransportPermissions().isEmpty()
}
