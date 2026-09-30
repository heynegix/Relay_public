package com.example.relay.ui

import com.example.relay.NOW
import com.example.relay.domain.CreateSafetyMessageUseCase
import com.example.relay.domain.InMemoryMessageRepository
import com.example.relay.domain.MessageIdGenerator
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MutableClock
import com.example.relay.domain.ResourcePolicy
import com.example.relay.domain.SafetyState
import com.example.relay.message
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayViewModelMessageCreationTest {
    @Test
    fun `active full repository becomes a short Japanese UI error without escaping`() = runBlocking {
        val clock = MutableClock(NOW)
        val repository = InMemoryMessageRepository(ResourcePolicy(maxStoredMessages = 1))
        repository.insert(message(id = "active"))
        val create = CreateSafetyMessageUseCase(
            repository,
            MessagePolicy(clock),
            clock,
            "device-B",
            MessageIdGenerator { "new-report" },
        )

        val result = executeMessageCreation {
            create(SafetyState.SAFE, 0, "north", "ok")
        }

        val updated = RelayUiState(screen = RelayScreen.SAFETY_FORM).withMessageCreationResult(result)

        assertEquals(RelayScreen.SAFETY_FORM, updated.screen)
        assertEquals("保存容量がいっぱいです", updated.lastError)
    }
}
