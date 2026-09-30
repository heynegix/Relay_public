package com.example.relay.pcgateway.rescue

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class RescueKeyStoreTest {
    @Test
    fun generatedKeysAreReusedAndPublicManifestContainsNoPrivateMaterial() {
        val path = Files.createTempDirectory("relay-rescue-keys").resolve("keys.json")
        val clock = RescueClock { NOW }
        val first = RescueKeyStore(path, "shelter-1", clock).loadOrCreate()
        val second = RescueKeyStore(path, "shelter-1", clock).loadOrCreate()

        assertEquals(first.manifest, second.manifest)
        assertEquals(first.recipientPrivateKey, second.recipientPrivateKey)
        assertEquals(first.receiptSigningPrivateKey, second.receiptSigningPrivateKey)
        assertTrue(Files.size(path) in 1..32L * 1024)
        val publicText = kotlinx.serialization.json.Json.encodeToString(
            com.example.relay.rescue.ShelterPublicKeyManifest.serializer(),
            first.manifest,
        )
        assertFalse(publicText.contains(first.recipientPrivateKey.encodedBase64))
        assertFalse(publicText.contains(first.receiptSigningPrivateKey.encodedBase64))
    }

    @Test
    fun existingKeyFileWithGroupReadPermissionFailsClosedWhenPosixPermissionsAreAvailable() {
        val path = Files.createTempDirectory("relay-rescue-key-permissions").resolve("keys.json")
        RescueKeyStore(path, "shelter-1", RescueClock { NOW }).loadOrCreate()
        val changed = runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ),
            )
        }.isSuccess
        if (!changed) return // Windows ACL behavior is separately fail-closed in the implementation.

        assertFailsWith<IllegalArgumentException> {
            RescueKeyStore(path, "shelter-1", RescueClock { NOW }).loadOrCreate()
        }
    }

    @Test
    fun symbolicLinkKeyPathFailsClosedWhenSymbolicLinksAreAvailable() {
        val directory = Files.createTempDirectory("relay-rescue-key-symlink")
        val realPath = directory.resolve("real-keys.json")
        RescueKeyStore(realPath, "shelter-1", RescueClock { NOW }).loadOrCreate()
        val link = directory.resolve("keys-link.json")
        val linked = runCatching { Files.createSymbolicLink(link, realPath.fileName) }.isSuccess
        if (!linked) return

        assertFailsWith<IllegalArgumentException> {
            RescueKeyStore(link, "shelter-1", RescueClock { NOW }).loadOrCreate()
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
