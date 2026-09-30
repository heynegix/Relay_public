package com.example.relay.pcgateway.rescue.provisioning

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TEST ONLY: root material is generated in a temporary non-Git directory and deleted afterward. */
class RegionalTrustProvisioningCliTest {
    @Test
    fun `offline root generation exports public-only bundle and refuses overwrite`() {
        val temp = Files.createTempDirectory("relay-regional-root-test-")
        try {
            val privateOutput = temp.resolve("regional-root-private.json")
            val bundleOutput = temp.resolve("regional-root-bundle.json")

            assertEquals(
                0,
                RegionalTrustProvisioningCli.run(
                    arrayOf(
                        "generate-regional-root",
                        "--region-id", "test-region",
                        "--environment", "TEST",
                        "--private-output", privateOutput.toString(),
                    ),
                ),
            )
            assertTrue(Files.isRegularFile(privateOutput))

            assertEquals(
                0,
                RegionalTrustProvisioningCli.run(
                    arrayOf(
                        "export-regional-root-bundle",
                        "--root-material-file", privateOutput.toString(),
                        "--output", bundleOutput.toString(),
                    ),
                ),
            )
            val publicBundle = Files.readString(bundleOutput)
            assertTrue(publicBundle.contains("\"environment\":\"TEST\""))
            assertFalse(publicBundle.contains("rootSigningPrivateKey"))

            // A repeat must neither overwrite the private material nor convert it into a new Root.
            assertEquals(
                2,
                RegionalTrustProvisioningCli.run(
                    arrayOf(
                        "generate-regional-root",
                        "--region-id", "test-region",
                        "--environment", "TEST",
                        "--private-output", privateOutput.toString(),
                    ),
                ),
            )
        } finally {
            deleteTree(temp)
        }
    }

    @Test
    fun `private root material is refused inside the Git worktree`() {
        val pathInsideWorktree = Path.of("build", "TEST-ONLY-regional-root-private.json").toAbsolutePath()
        assertEquals(
            2,
            RegionalTrustProvisioningCli.run(
                arrayOf(
                    "generate-regional-root",
                    "--region-id", "test-region",
                    "--environment", "TEST",
                    "--private-output", pathInsideWorktree.toString(),
                ),
            ),
        )
        assertFalse(Files.exists(pathInsideWorktree))
    }

    @Test
    fun `private root material reached through a worktree symlink is refused`() {
        val temporaryDirectory = Files.createTempDirectory("relay-regional-root-symlink-test-")
        try {
            val linkedWorktree = temporaryDirectory.resolve("linked-worktree")
            val linked = runCatching {
                Files.createSymbolicLink(linkedWorktree, Path.of("").toAbsolutePath())
            }.isSuccess
            if (!linked) return

            val privateOutput = linkedWorktree.resolve("build/TEST-ONLY-regional-root-private.json")
            assertEquals(
                2,
                RegionalTrustProvisioningCli.run(
                    arrayOf(
                        "generate-regional-root",
                        "--region-id", "test-region",
                        "--environment", "TEST",
                        "--private-output", privateOutput.toString(),
                    ),
                ),
            )
            assertFalse(Files.exists(privateOutput))
        } finally {
            deleteTree(temporaryDirectory)
        }
    }

    private fun deleteTree(path: Path) {
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
