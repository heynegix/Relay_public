package com.example.relay.rescue

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.relay.gateway.DiscoveredGateway
import com.example.relay.gateway.GatewayDiscovery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RescueShelterKeyStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearStore() {
        context.getSharedPreferences("relay_rescue_shelter_keys", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun onlyMatchingFingerprintCanBeSaved() {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val manifest = ShelterPublicKeyManifest(
            shelterId = "shelter-1",
            recipientPublicKey = recipient.publicKey,
            receiptSigningPublicKey = signer.publicKey,
            validFromEpochMillis = NOW - 1_000,
            validUntilEpochMillis = NOW + 1_000,
        )
        val store = RescueShelterKeyStore(context, nowEpochMillis = { NOW })

        assertThrows(IllegalArgumentException::class.java) {
            store.saveVerifiedManifest(manifest, "0".repeat(64))
        }
        store.saveVerifiedManifest(manifest, manifest.fingerprint())

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals(manifest.shelterId, loaded!!.shelterId)
        assertEquals(manifest.fingerprint(), loaded.manifestFingerprint)
    }

    @Test
    fun developmentEnrollmentNeverCarriesIntoAnUnapprovedBuildMode() {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val manifest = ShelterPublicKeyManifest(
            shelterId = "development-shelter",
            recipientPublicKey = recipient.publicKey,
            receiptSigningPublicKey = signer.publicKey,
            validFromEpochMillis = NOW - 1_000,
            validUntilEpochMillis = NOW + 1_000,
        )

        RescueShelterKeyStore(context, nowEpochMillis = { NOW }, allowDevelopmentEnrollment = true)
            .saveDevelopmentManifest(manifest)

        assertNotNull(
            RescueShelterKeyStore(context, nowEpochMillis = { NOW }, allowDevelopmentEnrollment = true).load(),
        )
        assertEquals(
            null,
            RescueShelterKeyStore(context, nowEpochMillis = { NOW }, allowDevelopmentEnrollment = false).load(),
        )
    }

    @Test
    fun developmentBootstrapPinsOnlyTheManifestForTheAnnouncedDevelopmentShelter() = runBlocking {
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val manifest = ShelterPublicKeyManifest(
            shelterId = "development-pc-gateway",
            recipientPublicKey = recipient.publicKey,
            receiptSigningPublicKey = signer.publicKey,
            validFromEpochMillis = NOW - 1_000,
            validUntilEpochMillis = NOW + 1_000,
        )
        val store = RescueShelterKeyStore(context, nowEpochMillis = { NOW }, allowDevelopmentEnrollment = true)
        val bootstrap = DevelopmentShelterManifestBootstrap(
            discovery = object : GatewayDiscovery {
                override suspend fun discover(timeoutMs: Int): DiscoveredGateway? = DiscoveredGateway(
                    host = "127.0.0.1",
                    port = 8080,
                    gatewayId = "development-pc-gateway",
                    scheme = "http",
                    shelterId = manifest.shelterId,
                    rescueIngressReady = true,
                )
            },
            keyStore = store,
            manifestClientFactory = { ShelterManifestClient { _, _ -> manifest } },
            enabled = true,
        )

        assertEquals(DevelopmentEnrollmentResult.ENROLLED, bootstrap.tryEnroll())
        assertEquals(manifest.fingerprint(), requireNotNull(store.load()).manifestFingerprint)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
