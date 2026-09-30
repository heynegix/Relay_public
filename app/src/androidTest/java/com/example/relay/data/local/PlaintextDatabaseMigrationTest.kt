package com.example.relay.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class PlaintextDatabaseMigrationTest {
    @Test
    fun plaintextMessagesAreCopiedIntoEncryptedRoomDatabase() {
        System.loadLibrary("sqlcipher")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "plaintext-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        val passphrase = ByteArray(32) { (it + 11).toByte() }
        try {
            val plaintext = SQLiteDatabase.openOrCreateDatabase(file, null)
            plaintext.execSQL(
                """CREATE TABLE messages (
                    messageId TEXT NOT NULL PRIMARY KEY,
                    messageType TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    expiresAt INTEGER NOT NULL,
                    priority TEXT NOT NULL,
                    originDeviceId TEXT NOT NULL,
                    payloadJson TEXT NOT NULL,
                    hopCount INTEGER NOT NULL,
                    maxHopCount INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    receivedAt INTEGER NOT NULL
                )""",
            )
            plaintext.insertOrThrow(
                "messages",
                null,
                ContentValues().apply {
                    put("messageId", "legacy-message")
                    put("messageType", "SAFETY")
                    put("createdAt", 1L)
                    put("expiresAt", 2L)
                    put("priority", "NORMAL")
                    put("originDeviceId", "legacy-device")
                    put("payloadJson", "{}")
                    put("hopCount", 0)
                    put("maxHopCount", 8)
                    put("status", "CREATED")
                    put("receivedAt", 1L)
                },
            )
            plaintext.close()

            PlaintextDatabaseMigration.migrateIfNeeded(context, name, passphrase)

            val header = ByteArray(16)
            file.inputStream().use { it.read(header) }
            assertFalse(header.contentEquals("SQLite format 3\u0000".encodeToByteArray()))
            assertFalse(File("${file.path}.plaintext-migration").exists())

            val encrypted = Room.databaseBuilder(context, RelayDatabase::class.java, name)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
            try {
                runBlocking {
                    assertEquals(1, encrypted.relayDao().messageCount())
                    assertTrue(encrypted.relayDao().findMessage("legacy-message") != null)
                }
            } finally {
                encrypted.close()
            }
        } finally {
            context.deleteDatabase(name)
            File("${file.path}.plaintext-migration").delete()
        }
    }

    @Test
    fun failedInstallRestoresTheOriginalPlaintextDatabase() {
        System.loadLibrary("sqlcipher")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "plaintext-migration-rollback-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        try {
            createLegacyMessageDatabase(file)

            val failure = runCatching {
                PlaintextDatabaseMigration.migrateIfNeeded(
                    context = context,
                    databaseName = name,
                    passphrase = ByteArray(32) { (it + 21).toByte() },
                    afterPlaintextBackupMoved = { error("injected install failure") },
                )
            }.exceptionOrNull()

            assertTrue(failure != null)
            val header = ByteArray(16)
            file.inputStream().use { it.read(header) }
            assertTrue(header.contentEquals("SQLite format 3\u0000".encodeToByteArray()))
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { plaintext ->
                plaintext.rawQuery("SELECT COUNT(*) FROM messages", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
            }
        } finally {
            context.deleteDatabase(name)
            File("${file.path}.plaintext-migration").delete()
        }
    }

    private fun createLegacyMessageDatabase(file: File) {
        val plaintext = SQLiteDatabase.openOrCreateDatabase(file, null)
        plaintext.execSQL(
            """CREATE TABLE messages (
                messageId TEXT NOT NULL PRIMARY KEY,
                messageType TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                expiresAt INTEGER NOT NULL,
                priority TEXT NOT NULL,
                originDeviceId TEXT NOT NULL,
                payloadJson TEXT NOT NULL,
                hopCount INTEGER NOT NULL,
                maxHopCount INTEGER NOT NULL,
                status TEXT NOT NULL,
                receivedAt INTEGER NOT NULL
            )""",
        )
        plaintext.insertOrThrow(
            "messages",
            null,
            ContentValues().apply {
                put("messageId", "legacy-message")
                put("messageType", "SAFETY")
                put("createdAt", 1L)
                put("expiresAt", 2L)
                put("priority", "NORMAL")
                put("originDeviceId", "legacy-device")
                put("payloadJson", "{}")
                put("hopCount", 0)
                put("maxHopCount", 8)
                put("status", "CREATED")
                put("receivedAt", 1L)
            },
        )
        plaintext.close()
    }
}
