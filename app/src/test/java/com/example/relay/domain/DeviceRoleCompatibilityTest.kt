package com.example.relay.domain

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceRoleCompatibilityTest {
    private val json = Json

    @Test
    fun `member is stored and restored by name`() {
        var raw: String? = null
        val store = StringDeviceRoleStore({ raw }, { raw = it })

        store.save(DeviceRole.MEMBER)

        assertEquals("MEMBER", raw)
        assertEquals(DeviceRole.MEMBER, store.load())
    }

    @Test
    fun `gateway is stored and restored by name`() {
        var raw: String? = null
        val store = StringDeviceRoleStore({ raw }, { raw = it })

        store.save(DeviceRole.GATEWAY)

        assertEquals("GATEWAY", raw)
        assertEquals(DeviceRole.GATEWAY, store.load())
    }

    @Test
    fun `all device roles serialize through JSON by stable names`() {
        DeviceRole.entries.forEach { role ->
            val encoded = json.encodeToString(role)
            assertEquals("\"${role.name}\"", encoded)
            assertEquals(role, json.decodeFromString<DeviceRole>(encoded))
        }
    }

    @Test
    fun `unknown stored role falls back without crashing`() {
        val store = StringDeviceRoleStore({ "FUTURE_ROLE" }, {})

        assertEquals(DeviceRole.MEMBER, store.load())
    }

    @Test
    fun `legacy gateway name remains gateway`() {
        val store = StringDeviceRoleStore({ "GATEWAY" }, {})

        assertEquals(DeviceRole.GATEWAY, store.load())
    }
}
