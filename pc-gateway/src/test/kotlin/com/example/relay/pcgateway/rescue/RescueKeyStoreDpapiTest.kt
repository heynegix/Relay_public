package com.example.relay.pcgateway.rescue

import com.example.relay.pcgateway.GatewayKeyProtection
import java.nio.file.Files
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * DPAPI at-rest protection for the rescue key store. Windows-only behavior is guarded with
 * [Assume] so Linux CI runners skip it honestly instead of green-washing; the fail-closed
 * guards themselves are platform-independent and always run.
 */
class RescueKeyStoreDpapiTest {

    @Test
    fun dpapiModeWithoutDpapiFailsClosedInsteadOfFallingBackToPlaintext() {
        val path = Files.createTempDirectory("relay-dpapi-unavailable").resolve("keys.json")
        val error = assertFailsWith<IllegalArgumentException> {
            RescueKeyStore(
                path,
                "shelter-1",
                RescueClock { NOW },
                protection = GatewayKeyProtection.DPAPI,
                dpapiAvailable = false,
            )
        }
        assertTrue(error.message!!.contains("refusing to fall back to a plaintext key file"))
    }

    @Test
    fun keyProtectionEnvironmentParsingFailsClosedOnUnknownValues() {
        assertEquals(GatewayKeyProtection.FILE_PERMISSIONS, GatewayKeyProtection.fromEnvironment(null))
        assertEquals(GatewayKeyProtection.FILE_PERMISSIONS, GatewayKeyProtection.fromEnvironment(""))
        assertEquals(GatewayKeyProtection.FILE_PERMISSIONS, GatewayKeyProtection.fromEnvironment("file-permissions"))
        assertEquals(GatewayKeyProtection.FILE_PERMISSIONS, GatewayKeyProtection.fromEnvironment("file_permissions"))
        assertEquals(GatewayKeyProtection.DPAPI, GatewayKeyProtection.fromEnvironment("dpapi"))
        assertEquals(GatewayKeyProtection.DPAPI, GatewayKeyProtection.fromEnvironment(" DPAPI "))
        val error = assertFailsWith<IllegalArgumentException> {
            GatewayKeyProtection.fromEnvironment("tpm")
        }
        assertTrue(error.message!!.contains("RELAY_KEY_PROTECTION"))
    }

    @Test
    fun dpapiRoundtripStoresNoPlaintextKeyMaterialOnDisk() {
        Assume.assumeTrue(WindowsDpapi.isSupported)
        val path = Files.createTempDirectory("relay-dpapi-roundtrip").resolve("keys.json")
        val clock = RescueClock { NOW }
        val first = dpapiStore(path, clock).loadOrCreate()

        val onDisk = Files.readString(path, Charsets.UTF_8)
        assertTrue(onDisk.startsWith(DPAPI_HEADER))
        // The persisted JSON field names and private key material must not be readable on disk.
        assertFalse(onDisk.contains("encodedBase64"))
        assertFalse(onDisk.contains(first.recipientPrivateKey.encodedBase64))
        assertFalse(onDisk.contains(first.receiptSigningPrivateKey.encodedBase64))

        val second = dpapiStore(path, clock).loadOrCreate()
        assertEquals(first.manifest, second.manifest)
        assertEquals(first.recipientPrivateKey, second.recipientPrivateKey)
        assertEquals(first.receiptSigningPrivateKey, second.receiptSigningPrivateKey)
    }

    @Test
    fun plaintextKeyFileIsMigratedToDpapiOnFirstDpapiLoad() {
        Assume.assumeTrue(WindowsDpapi.isSupported)
        val path = Files.createTempDirectory("relay-dpapi-migration").resolve("keys.json")
        val clock = RescueClock { NOW }
        val plaintextKeys = RescueKeyStore(path, "shelter-1", clock).loadOrCreate()
        assertFalse(Files.readString(path, Charsets.UTF_8).startsWith(DPAPI_HEADER))

        val migratedKeys = dpapiStore(path, clock).loadOrCreate()
        assertEquals(plaintextKeys.manifest, migratedKeys.manifest)
        assertEquals(plaintextKeys.recipientPrivateKey, migratedKeys.recipientPrivateKey)
        assertTrue(Files.readString(path, Charsets.UTF_8).startsWith(DPAPI_HEADER))
    }

    @Test
    fun protectedKeyFileRefusesToOpenOutsideDpapiMode() {
        Assume.assumeTrue(WindowsDpapi.isSupported)
        val path = Files.createTempDirectory("relay-dpapi-mode-mismatch").resolve("keys.json")
        val clock = RescueClock { NOW }
        dpapiStore(path, clock).loadOrCreate()

        val error = assertFailsWith<IllegalArgumentException> {
            RescueKeyStore(path, "shelter-1", clock).loadOrCreate()
        }
        assertTrue(error.message!!.contains("RELAY_KEY_PROTECTION"))
    }

    @Test
    fun tamperedDpapiBlobFailsClosed() {
        Assume.assumeTrue(WindowsDpapi.isSupported)
        val path = Files.createTempDirectory("relay-dpapi-tamper").resolve("keys.json")
        val clock = RescueClock { NOW }
        dpapiStore(path, clock).loadOrCreate()

        val onDisk = Files.readString(path, Charsets.UTF_8)
        val blob = Base64.getDecoder().decode(onDisk.removePrefix(DPAPI_HEADER).trim())
        blob[blob.size / 2] = (blob[blob.size / 2].toInt() xor 0x40).toByte()
        Files.writeString(path, DPAPI_HEADER + Base64.getEncoder().encodeToString(blob))

        assertFailsWith<IllegalStateException> {
            dpapiStore(path, clock).loadOrCreate()
        }
    }

    @Test
    fun wrongEntropyIsRejectedByDpapi() {
        Assume.assumeTrue(WindowsDpapi.isSupported)
        val secret = "rescue-key-material".encodeToByteArray()
        val protectedBlob = WindowsDpapi.protect(secret, "entropy-a".encodeToByteArray())
        assertTrue(WindowsDpapi.unprotect(protectedBlob, "entropy-a".encodeToByteArray()).contentEquals(secret))
        assertFailsWith<IllegalStateException> {
            WindowsDpapi.unprotect(protectedBlob, "entropy-b".encodeToByteArray())
        }
    }

    private fun dpapiStore(path: java.nio.file.Path, clock: RescueClock) =
        RescueKeyStore(path, "shelter-1", clock, protection = GatewayKeyProtection.DPAPI)

    private companion object {
        const val NOW = 1_800_000_000_000L

        /** Mirrors the private on-disk marker in RescueKeyStore. */
        const val DPAPI_HEADER = "RELAY-DPAPI-KEYS-V1\n" // gitleaks:allow -- public file-format marker
    }
}
