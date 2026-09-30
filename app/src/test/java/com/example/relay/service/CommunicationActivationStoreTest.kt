package com.example.relay.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunicationActivationStoreTest {
    @Test
    fun `explicit stop survives recreation and suppresses automatic restart`() {
        var persisted: Boolean? = null
        fun store() = FunctionalCommunicationActivationStore(
            readValue = { persisted },
            writeValue = { persisted = it },
        )

        assertTrue(store().isEnabled())
        store().setEnabled(false)

        val recreated = store()
        assertFalse(recreated.isEnabled())
        assertFalse(shouldAutoStartCommunication(permissionsGranted = true, transportRunning = false, activationEnabled = recreated.isEnabled()))
    }

    @Test
    fun `explicit start re-enables automatic recovery`() {
        var persisted: Boolean? = false
        val store = FunctionalCommunicationActivationStore(
            readValue = { persisted },
            writeValue = { persisted = it },
        )

        store.setEnabled(true)

        assertTrue(shouldAutoStartCommunication(permissionsGranted = true, transportRunning = false, activationEnabled = store.isEnabled()))
        assertFalse(shouldAutoStartCommunication(permissionsGranted = false, transportRunning = false, activationEnabled = store.isEnabled()))
        assertFalse(shouldAutoStartCommunication(permissionsGranted = true, transportRunning = true, activationEnabled = store.isEnabled()))
    }
}
