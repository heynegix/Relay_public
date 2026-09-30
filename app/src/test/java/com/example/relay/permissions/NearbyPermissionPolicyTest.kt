package com.example.relay.permissions

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPermissionPolicyTest {
    @Test
    fun `api 23 through 28 require coarse location for transport`() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
            NearbyPermissionPolicy.transportPermissions(28),
        )
        assertEquals(
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
            NearbyPermissionPolicy.requiredRuntimePermissions(28),
        )
    }

    @Test
    fun `api 29 and 30 require fine location for transport`() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            NearbyPermissionPolicy.requiredRuntimePermissions(30),
        )
    }

    @Test
    fun `api 31 requires fine location and bluetooth runtime permissions`() {
        assertEquals(
            setOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ),
            NearbyPermissionPolicy.requiredRuntimePermissions(31).toSet(),
        )
    }

    @Test
    fun `api 32 requires bluetooth transport plus GPS location for create path`() {
        val required = NearbyPermissionPolicy.requiredRuntimePermissions(32).toSet()
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in required)
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in required)
        assertTrue(Manifest.permission.BLUETOOTH_ADVERTISE in required)
        assertTrue(
            "API 32+ must request location so GPS create-path is not dead",
            Manifest.permission.ACCESS_FINE_LOCATION in required ||
                Manifest.permission.ACCESS_COARSE_LOCATION in required,
        )
        // Transport alone must not require location (Nearby neverForLocation)
        assertFalse(
            Manifest.permission.ACCESS_FINE_LOCATION in NearbyPermissionPolicy.transportPermissions(32),
        )
    }

    @Test
    fun `api 33 adds nearby wifi and still includes GPS location permissions`() {
        val permissions = NearbyPermissionPolicy.requiredRuntimePermissions(33)
        assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in permissions)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in permissions)
        assertFalse(Manifest.permission.POST_NOTIFICATIONS in permissions)
        assertEquals(Manifest.permission.POST_NOTIFICATIONS, NearbyPermissionPolicy.optionalNotificationPermission(33))
        // canUseNearby uses transport only — empty location list does not appear in transport
        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in NearbyPermissionPolicy.transportPermissions(33))
    }

    @Test
    fun `api 36 primary devices include fine and coarse location in start grant list`() {
        val permissions = NearbyPermissionPolicy.requiredRuntimePermissions(36)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in permissions)
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in permissions)
        assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in permissions)
    }
}
