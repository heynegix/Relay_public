package com.example.relay.permissions

import android.bluetooth.BluetoothManager
import android.content.Context
import android.location.LocationManager
import android.os.Build
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

sealed interface NearbyPrerequisite {
    data object Ready : NearbyPrerequisite
    data class MissingPermissions(val permissions: List<String>) : NearbyPrerequisite
    data object BluetoothUnavailable : NearbyPrerequisite
    data object BluetoothDisabled : NearbyPrerequisite
    data object LocationServicesDisabled : NearbyPrerequisite
    data class PlayServicesUnavailable(val statusCode: Int) : NearbyPrerequisite
}

class NearbyPrerequisiteChecker(
    private val context: Context,
    private val permissionGate: AndroidNearbyPermissionGate,
) {
    fun check(): NearbyPrerequisite {
        val missing = permissionGate.missingPermissions()
        if (missing.isNotEmpty()) return NearbyPrerequisite.MissingPermissions(missing)
        val playServices = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (playServices != ConnectionResult.SUCCESS) return NearbyPrerequisite.PlayServicesUnavailable(playServices)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return NearbyPrerequisite.BluetoothUnavailable
        val enabled = try { adapter.isEnabled } catch (_: SecurityException) { false }
        if (!enabled) return NearbyPrerequisite.BluetoothDisabled
        if (Build.VERSION.SDK_INT <= 31) {
            val location = context.getSystemService(LocationManager::class.java)
            val locationEnabled = if (Build.VERSION.SDK_INT >= 28) location?.isLocationEnabled == true
            else location?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true || location?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
            if (!locationEnabled) return NearbyPrerequisite.LocationServicesDisabled
        }
        return NearbyPrerequisite.Ready
    }
}
