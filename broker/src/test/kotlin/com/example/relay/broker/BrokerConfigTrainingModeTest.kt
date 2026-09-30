package com.example.relay.broker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BrokerConfigTrainingModeTest {
    @Test fun `training default stores drill data under a training directory`() {
        val training = BrokerConfig(trainingMode = true)
        assertTrue(BrokerConfig.hasTrainingPathSegment(training.dbPath))
    }

    @Test fun `production default is unchanged`() {
        val production = BrokerConfig(trainingMode = false, dbPath = "./data/broker.db")
        assertEquals("./data/broker.db", production.dbPath)
        assertFalse(BrokerConfig.hasTrainingPathSegment(production.dbPath))
    }

    @Test fun `training mode rejects the production database path`() {
        try {
            BrokerConfig(trainingMode = true, dbPath = "./data/broker.db")
            fail("expected training-mode guard to reject the production Broker database path")
        } catch (error: IllegalArgumentException) {
            assertTrue(
                "expected guard message naming RELAY_BROKER_DB_PATH, got: ${error.message}",
                error.message!!.contains("RELAY_BROKER_DB_PATH") && error.message!!.contains("training"),
            )
        }
    }

    @Test fun `training path segment matches whole directory names only`() {
        assertTrue(BrokerConfig.hasTrainingPathSegment("./data/training/broker.db"))
        assertTrue(BrokerConfig.hasTrainingPathSegment("C:\\relay\\TRAINING\\broker.db"))
        assertFalse(BrokerConfig.hasTrainingPathSegment("./data/broker.db"))
        // A prefix such as `trainingdata` must not satisfy the isolation guard.
        assertFalse(BrokerConfig.hasTrainingPathSegment("./trainingdata/broker.db"))
    }
}
