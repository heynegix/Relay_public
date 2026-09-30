package com.example.relay.pcgateway

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewaySqliteWriteCoordinatorTest {
    @Test
    fun `writers for the same database are serialized`() {
        val database = Files.createTempFile("relay-writer-coordinator", ".db").toString()
        val coordinator = GatewaySqliteWriteCoordinator.forDatabase(database)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit {
                coordinator.write {
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val second = workers.submit {
                coordinator.write { secondEntered.countDown() }
            }

            assertFalse(secondEntered.await(150, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertTrue(secondEntered.await(1, TimeUnit.SECONDS))
        } finally {
            releaseFirst.countDown()
            workers.shutdownNow()
        }
    }
}
