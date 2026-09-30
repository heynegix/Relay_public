package com.example.relay.cloud

import com.example.relay.NOW
import com.example.relay.domain.InMemoryMessageRepository
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MessagePriority
import com.example.relay.domain.MutableClock
import com.example.relay.message
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InternetPrioritySyncTest {
    @Test
    fun `offline does not insert from source`() = runBlocking {
        var fetches = 0
        val repo = InMemoryMessageRepository()
        val sync = InternetPrioritySync(
            detector = AlwaysOfflineDetector(),
            source = PriorityMessageSource {
                fetches++
                listOf(message(id = "prio-1").copy(priority = MessagePriority.CRITICAL))
            },
            repository = repo,
            policy = MessagePolicy(MutableClock(NOW)),
        )
        assertEquals(ServerSyncResult.OfflineSkipped, sync.sync())
        assertEquals(0, fetches)
        assertTrue(repo.all().isEmpty())
    }

    @Test
    fun `online inserts only high and critical priority via real repository`() = runBlocking {
        val repo = InMemoryMessageRepository()
        val clock = MutableClock(NOW)
        val policy = MessagePolicy(clock)
        val high = message(id = "h1", priority = MessagePriority.HIGH)
        val low = message(id = "l1", priority = MessagePriority.LOW)
        val critical = message(id = "c1", priority = MessagePriority.CRITICAL)
        val sync = InternetPrioritySync(
            detector = AlwaysOnlineDetector(),
            source = PriorityMessageSource { listOf(high, low, critical) },
            repository = repo,
            policy = policy,
        )
        val result = sync.sync()
        assertTrue(result is ServerSyncResult.Synced)
        val synced = result as ServerSyncResult.Synced
        assertEquals(2, synced.inserted)
        assertEquals(2, repo.all().size)
        assertTrue(repo.find("h1") != null)
        assertTrue(repo.find("c1") != null)
        assertTrue(repo.find("l1") == null)
    }

    @Test
    fun `duplicate priority message is skipped not failed`() = runBlocking {
        val repo = InMemoryMessageRepository()
        val clock = MutableClock(NOW)
        val msg = message(id = "same", priority = MessagePriority.HIGH)
        repo.insert(msg)
        val sync = InternetPrioritySync(
            detector = AlwaysOnlineDetector(),
            source = PriorityMessageSource { listOf(msg) },
            repository = repo,
            policy = MessagePolicy(clock),
        )
        val result = sync.sync() as ServerSyncResult.Synced
        assertEquals(0, result.inserted)
        assertEquals(1, result.skipped)
        assertEquals(1, repo.all().size)
    }

    @Test
    fun `cancellation from remote source is propagated`() = runTest {
        val sync = InternetPrioritySync(
            detector = AlwaysOnlineDetector(),
            source = PriorityMessageSource { awaitCancellation() },
            repository = InMemoryMessageRepository(),
            policy = MessagePolicy(MutableClock(NOW)),
        )
        val job = launch { sync.sync() }
        runCurrent()

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }
}
