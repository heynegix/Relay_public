package com.example.relay.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.relay.MIGRATION_5_6
import com.example.relay.MIGRATION_6_7
import com.example.relay.MIGRATION_7_8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Starts with the exported v5 schema, establishes a representative v6 encrypted-store shape,
 * then verifies the v6→v7→v8 DDL preserves rescue and Broker rows while creating both new tables.
 */
@RunWith(AndroidJUnit4::class)
class RescueSessionMigrationContractTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RelayDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun v6RescueAndBrokerRowsSurviveTrustAndSessionSchemaMigrations() {
        val database = helper.createDatabase(TEST_DATABASE, 5)
        try {
            MIGRATION_5_6.migrate(database)
            insertV6Rows(database)

            MIGRATION_6_7.migrate(database)
            insertV7Directory(database)
            MIGRATION_7_8.migrate(database)
            insertV8Session(database)

            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM rescue_envelopes"))
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM broker_ledger"))
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM regional_shelter_directories"))
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM active_rescue_sessions"))
            assertTrue(tableExists(database, "active_rescue_sessions"))

            // Open with the actual Room schema/migration chain as a final contract check. This
            // catches a DDL shape that happens to satisfy raw SQL but does not match the exported
            // v8 Room schema. The existing data remains readable after that open.
            database.close()
            val opened = Room.databaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                RelayDatabase::class.java,
                TEST_DATABASE,
            )
                .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .allowMainThreadQueries()
                .build()
            try {
                opened.openHelper.writableDatabase
                assertEquals(1, opened.rescueDao().count())
                assertEquals("broker-receipt", opened.brokerLedgerDao().find("request-1", 1)?.brokerReceiptId)
                assertEquals(1, opened.activeRescueSessionDao().find("request-2")?.latestVersion)
                assertEquals(1L, opened.regionalDirectoryDao().find("region-1")?.generation)
            } finally {
                opened.close()
            }
        } finally {
            runCatching { database.close() }
        }
    }

    private fun insertV6Rows(database: SupportSQLiteDatabase) {
        database.execSQL(
            """INSERT INTO rescue_envelopes(
                requestId, requestVersion, envelopeId, ciphertextSha256Hex,
                createdAtEpochMillis, expiresAtEpochMillis, storageSizeBytes, envelopeJson,
                receivedAtEpochMillis, submissionStatus, submissionCount, signedReceiptJson
            ) VALUES ('request-1', 1, 'envelope-1', 'hash', 1, 999999999999, 1, '{}', 1, 'PENDING', 0, NULL)""",
        )
        database.execSQL(
            """INSERT INTO broker_ledger(
                requestId, requestVersion, brokerReceiptId, brokerStatus, uploadedAtEpochMillis, retryCount
            ) VALUES ('request-1', 1, 'broker-receipt', 'UPLOADED', 2, 0)""",
        )
    }

    private fun insertV7Directory(database: SupportSQLiteDatabase) {
        database.execSQL(
            """INSERT INTO regional_shelter_directories(
                regionId, generation, directoryDigest, directoryJson, acceptedAtEpochMillis
            ) VALUES ('region-1', 1, 'digest', '{}', 3)""",
        )
    }

    private fun insertV8Session(database: SupportSQLiteDatabase) {
        database.execSQL(
            """INSERT INTO active_rescue_sessions(
                requestId, latestVersion, sealedRecoveryPayload, recoveryNonce, trackingMode,
                latestSubmissionStatus, createdAtEpochMillis, updatedAtEpochMillis,
                expiresAtEpochMillis, terminalStatus
            ) VALUES ('request-2', 1, X'0102', X'000000000000000000000000', 'DISABLED', 'PENDING', 1, 1, 9, NULL)""",
        )
    }

    private fun scalar(database: SupportSQLiteDatabase, sql: String): Int = database.query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private fun tableExists(database: SupportSQLiteDatabase, name: String): Boolean = database.query(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
        arrayOf(name),
    ).use { it.moveToFirst() }

    private companion object {
        const val TEST_DATABASE = "rescue-session-migration-contract"
    }
}
