package com.example.relay.rescue.trust

import com.example.relay.rescue.RegionalRootBundle
import com.example.relay.rescue.RescueCryptography
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guardrail: test fixtures are generated in test memory and must never become APK assets. */
class RegionalTrustPackagingTest {
    @Test
    fun `main and release assets contain no test root or private root material`() {
        val roots = listOf(File("src/main/assets"), File("src/release/assets"), File("src/pilotRelease/assets"))
        val files = roots.filter { it.isDirectory }.flatMap { root -> root.walkTopDown().filter { it.isFile }.toList() }

        assertTrue(files.none { file ->
            val contents = file.readText()
            contents.contains("TEST ONLY", ignoreCase = true) ||
                contents.contains("rootSigningPrivateKey") ||
                contents.contains("privateKey", ignoreCase = true)
        })
    }

    @Test
    fun `a TEST root bundle is rejected by the release environment policy`() {
        val root = RegionalRootBundle(
            regionId = "test-region",
            rootSigningPublicKey = RescueCryptography.generateShelterSigningKeyPair().publicKey,
        )
        val testOnlyBundle = RegionalRootBundleParser.serialize(
            listOf(root),
            RegionalRootBundleParser.ENVIRONMENT_TEST,
        )

        assertTrue(
            RegionalRootBundleParser.parse(
                testOnlyBundle,
                setOf(
                    RegionalRootBundleParser.ENVIRONMENT_PILOT,
                    RegionalRootBundleParser.ENVIRONMENT_PRODUCTION,
                ),
            ).isEmpty(),
        )
    }
}
