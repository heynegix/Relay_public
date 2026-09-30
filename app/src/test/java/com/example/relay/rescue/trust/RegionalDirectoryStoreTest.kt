package com.example.relay.rescue.trust

import com.example.relay.rescue.DirectoryAcceptance
import com.example.relay.rescue.RegionalRootBundle
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueKeyPair
import com.example.relay.rescue.RescuePrivateKey
import com.example.relay.rescue.ShelterPublicKeyManifest
import com.example.relay.rescue.SignedRegionalShelterDirectory
import com.example.relay.rescue.SignedShelterManifest
import com.example.relay.rescue.UnsignedRegionalShelterDirectory
import com.example.relay.rescue.signRegionalShelterDirectory
import com.example.relay.rescue.signShelterManifest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TEST ONLY: every key in this suite is generated in memory and is never persisted. */
class RegionalDirectoryStoreTest {
    @Test
    fun `accepts a valid root signed directory and reloads it`() {
        val fixture = fixture()
        val persistence = InMemoryRegionalDirectoryPersistence()
        val store = VerifiedRegionalDirectoryStore(persistence)

        assertTrue(store.accept(fixture.directory, listOf(fixture.root), NOW) is DirectoryStoreAcceptance.Accepted)
        assertEquals(1, store.loadAccepted(listOf(fixture.root), NOW).size)
        assertEquals(fixture.directory, store.loadAccepted(listOf(fixture.root), NOW).single())
    }

    @Test
    fun `root absence malformed roots and unknown roots fail closed`() {
        val fixture = fixture()
        val store = VerifiedRegionalDirectoryStore(InMemoryRegionalDirectoryPersistence())

        assertEquals(DirectoryStoreAcceptance.Invalid, store.accept(fixture.directory, emptyList(), NOW))
        assertEquals(
            listOf(fixture.root),
            RegionalRootBundleParser.parse(RegionalRootBundleParser.serialize(listOf(fixture.root))),
        )
        assertTrue(
            RegionalRootBundleParser.parse(
                RegionalRootBundleParser.serialize(
                    listOf(fixture.root),
                    RegionalRootBundleParser.ENVIRONMENT_TEST,
                ),
                setOf(
                    RegionalRootBundleParser.ENVIRONMENT_PILOT,
                    RegionalRootBundleParser.ENVIRONMENT_PRODUCTION,
                ),
            ).isEmpty(),
        )
        assertTrue(RegionalRootBundleParser.parse("{not-json").isEmpty())
        val validTestBundle = RegionalRootBundleParser.serialize(listOf(fixture.root))
        assertTrue(
            RegionalRootBundleParser.parse(
                validTestBundle.replace(
                    "\"regionId\"",
                    "\"privateKey\":\"TEST ONLY\",\"regionId\"",
                ),
            ).isEmpty(),
        )
        val unknown = RegionalRootBundle(
            regionId = "other-region",
            rootSigningPublicKey = RescueCryptography.generateShelterSigningKeyPair().publicKey,
        )
        assertEquals(DirectoryStoreAcceptance.Invalid, store.accept(fixture.directory, listOf(unknown), NOW))
    }

    @Test
    fun `expired future and tampered directories are rejected`() {
        val fixture = fixture()
        val store = VerifiedRegionalDirectoryStore(InMemoryRegionalDirectoryPersistence())

        assertEquals(
            DirectoryStoreAcceptance.Invalid,
            store.accept(fixture.directory.copy(signatureBase64 = fixture.directory.signatureBase64.reversed()), listOf(fixture.root), NOW),
        )
        assertEquals(
            DirectoryStoreAcceptance.Invalid,
            store.accept(fixture.signedDirectory(generation = 2, validUntilEpochMillis = NOW - 1), listOf(fixture.root), NOW),
        )
        assertEquals(
            DirectoryStoreAcceptance.Invalid,
            store.accept(
                fixture.signedDirectory(
                    generation = 3,
                    issuedAtEpochMillis = NOW + 1,
                    validUntilEpochMillis = NOW + 61_000,
                ),
                listOf(fixture.root),
                NOW,
            ),
        )
    }

    @Test
    fun `rollback equivocation and corrupt stored data fail closed while exact retry is idempotent`() {
        val fixture = fixture(generation = 2)
        val persistence = InMemoryRegionalDirectoryPersistence()
        val store = VerifiedRegionalDirectoryStore(persistence)
        assertTrue(store.accept(fixture.directory, listOf(fixture.root), NOW) is DirectoryStoreAcceptance.Accepted)
        assertEquals(DirectoryStoreAcceptance.AlreadyAccepted, store.accept(fixture.directory, listOf(fixture.root), NOW))

        val stale = fixture.signedDirectory(generation = 1)
        assertEquals(DirectoryStoreAcceptance.Stale, store.accept(stale, listOf(fixture.root), NOW))
        val equivocation = fixture.signedDirectory(generation = 2, validUntilEpochMillis = NOW + 90_000)
        assertEquals(DirectoryStoreAcceptance.Invalid, store.accept(equivocation, listOf(fixture.root), NOW))

        persistence.replace(
            StoredRegionalDirectory(
                regionId = fixture.root.regionId,
                generation = 3,
                directoryDigest = "not-a-real-digest",
                directoryJson = "not-json",
                acceptedAtEpochMillis = NOW,
            ),
        )
        assertTrue(store.loadAccepted(listOf(fixture.root), NOW).isEmpty())
        assertEquals(DirectoryStoreAcceptance.CorruptExisting, store.accept(fixture.signedDirectory(generation = 4), listOf(fixture.root), NOW))
    }

    @Test
    fun `failed replacement keeps the previously accepted directory`() {
        val fixture = fixture(generation = 1)
        val persistence = InMemoryRegionalDirectoryPersistence()
        val store = VerifiedRegionalDirectoryStore(persistence)
        assertTrue(store.accept(fixture.directory, listOf(fixture.root), NOW) is DirectoryStoreAcceptance.Accepted)
        persistence.failWrites = true

        assertEquals(
            DirectoryStoreAcceptance.WriteFailed,
            store.accept(fixture.signedDirectory(generation = 2), listOf(fixture.root), NOW),
        )
        assertEquals(1L, store.loadAccepted(listOf(fixture.root), NOW).single().directory.generation)
    }

    @Test
    fun `persisted record contains no regional private key`() {
        val fixture = fixture()
        val persistence = InMemoryRegionalDirectoryPersistence()
        val store = VerifiedRegionalDirectoryStore(persistence)

        store.accept(fixture.directory, listOf(fixture.root), NOW)

        val record = persistence.all().single()
        assertTrue(!record.directoryJson.contains("privateKey", ignoreCase = true))
        assertTrue(!record.directoryJson.contains(fixture.rootPrivateKey.encodedBase64))
    }

    @Test
    fun `no roots and no directory leaves only BLE trust unavailable without crashing`() = runBlocking {
        val persistence = InMemoryRegionalDirectoryPersistence()
        val runtime = RegionalTrustRuntime(
            rootLoader = RegionalRootLoader { emptyList() },
            directoryStore = VerifiedRegionalDirectoryStore(persistence),
            nowEpochMillis = { NOW },
        )

        // Construction is safe from Application.onCreate(): it parses only the public asset and
        // does not synchronously access Room. Revalidation is explicitly dispatched to I/O.
        assertEquals(0, persistence.transactionCount)
        assertEquals(null, runtime.resolver.resolveForNewRequest("region-1", "shelter-1", NOW))
        runtime.reloadAcceptedDirectories()
        assertEquals(1, persistence.transactionCount)
    }

    private fun fixture(generation: Long = 1): Fixture {
        val root = RescueCryptography.generateShelterSigningKeyPair()
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val receipt = RescueCryptography.generateShelterSigningKeyPair()
        val rootBundle = RegionalRootBundle(regionId = "region-1", rootSigningPublicKey = root.publicKey)
        return Fixture(rootBundle, root.privateKey, recipient, receipt, generation)
    }

    private data class Fixture(
        val root: RegionalRootBundle,
        val rootPrivateKey: RescuePrivateKey,
        val recipient: RescueKeyPair,
        val receipt: RescueKeyPair,
        val generation: Long,
    ) {
        val directory: SignedRegionalShelterDirectory = signedDirectory(generation)

        fun signedDirectory(
            generation: Long,
            issuedAtEpochMillis: Long = NOW - 1_000,
            validUntilEpochMillis: Long = NOW + 60_000,
        ): SignedRegionalShelterDirectory {
            val manifest: SignedShelterManifest = signShelterManifest(
                root.regionId,
                ShelterPublicKeyManifest(
                    shelterId = "shelter-1",
                    recipientPublicKey = recipient.publicKey,
                    receiptSigningPublicKey = receipt.publicKey,
                    validFromEpochMillis = issuedAtEpochMillis - 60_000,
                    validUntilEpochMillis = validUntilEpochMillis,
                    generation = 1,
                ),
                rootPrivateKey,
            )
            return signRegionalShelterDirectory(
                UnsignedRegionalShelterDirectory(
                    regionId = root.regionId,
                    generation = generation,
                    issuedAtEpochMillis = issuedAtEpochMillis,
                    validUntilEpochMillis = validUntilEpochMillis,
                    shelters = listOf(manifest),
                ),
                rootPrivateKey,
            )
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
