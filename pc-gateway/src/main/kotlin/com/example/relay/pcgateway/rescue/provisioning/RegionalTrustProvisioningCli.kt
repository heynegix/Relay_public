package com.example.relay.pcgateway.rescue.provisioning

import com.example.relay.rescue.RegionalRootBundle
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueValidationResult
import com.example.relay.rescue.SignedRegionalShelterDirectory
import com.example.relay.rescue.UnsignedRegionalShelterDirectory
import com.example.relay.rescue.signRegionalShelterDirectory
import com.example.relay.rescue.signedDirectoryFingerprint
import com.example.relay.rescue.signedManifestFingerprint
import com.example.relay.rescue.verifyRegionalShelterDirectory
import com.example.relay.rescue.verifyShelterManifest
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Offline operator entry point. It is not called by normal PC Gateway startup and does not create
 * a municipal/production root automatically. The operator must obtain authorization before using
 * any emitted public bundle in a pilot or production Android build.
 */
fun main(args: Array<String>) {
    val status = RegionalTrustProvisioningCli.run(args)
    if (status != 0) kotlin.system.exitProcess(status)
}

@Serializable
private data class RegionalRootBundleExport(
    val environment: String,
    val roots: List<RegionalRootBundle>,
)

/** Separate CLI entry point; invoke via the `regionalTrustProvisioning` Gradle task. */
object RegionalTrustProvisioningCli {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun run(args: Array<String>): Int = runCatching {
        val command = args.firstOrNull() ?: error("missing command")
        val options = parseOptions(args.drop(1))
        when (command) {
            "generate-regional-root" -> generateRegionalRoot(options)
            "export-regional-root-bundle" -> exportRegionalRootBundle(options)
            "sign-regional-directory" -> signRegionalDirectory(options)
            "verify-regional-directory" -> verifyRegionalDirectory(options)
            "print-public-fingerprints" -> printPublicFingerprints(options)
            else -> error("unsupported command")
        }
        0
    }.getOrElse {
        // Do not echo exception objects: a malformed input can contain sensitive private material.
        System.err.println("Regional trust operation failed. No trust artifact was changed.")
        2
    }

    private fun generateRegionalRoot(options: Map<String, String>) {
        val regionId = options.required("--region-id")
        val environment = options.required("--environment").uppercase()
        require(environment in setOf("TEST", "PILOT", "PRODUCTION")) { "invalid environment" }
        val output = Path.of(options.required("--private-output"))
        require(!Files.exists(output)) { "refusing to overwrite private material" }
        requireOfflinePrivatePath(output)

        val pair = RescueCryptography.generateShelterSigningKeyPair()
        val material = RegionalRootSigningMaterial(
            regionId = regionId,
            environment = environment,
            operatorLabel = "Relay Regional Root — Unofficial Development Pilot — NOT MUNICIPAL PRODUCTION",
            rootSigningPublicKey = pair.publicKey,
            rootSigningPrivateKey = PersistedRegionalPrivateKey(
                keyId = pair.privateKey.keyId,
                algorithm = pair.privateKey.algorithm,
                encodedBase64 = pair.privateKey.encodedBase64,
            ),
        )
        // Verify before writing; never print the private key or serialized material.
        material.privateKey()
        writeNewOwnerOnly(output, json.encodeToString(material).toByteArray(StandardCharsets.UTF_8))
        println("Generated offline regional root material for region=$regionId environment=$environment.")
        println("Label: Relay Regional Root — Unofficial Development Pilot — NOT MUNICIPAL PRODUCTION")
        println("Root public key fingerprint: ${pair.publicKey.keyId}")
    }

    private fun exportRegionalRootBundle(options: Map<String, String>) {
        val material = loadMaterial(Path.of(options.required("--root-material-file")))
        val output = Path.of(options.required("--output"))
        require(!Files.exists(output)) { "refusing to overwrite public bundle" }
        writeNew(
            output,
            json.encodeToString(RegionalRootBundleExport(material.environment, listOf(material.rootBundle())))
                .toByteArray(StandardCharsets.UTF_8),
        )
        println("Exported public root bundle for region=${material.regionId}.")
        println("Root public key fingerprint: ${material.rootSigningPublicKey.keyId}")
    }

    private fun signRegionalDirectory(options: Map<String, String>) {
        val material = loadMaterial(Path.of(options.required("--root-material-file")))
        val input = Path.of(options.required("--unsigned-directory-file"))
        val output = Path.of(options.required("--output"))
        require(Files.isRegularFile(input) && Files.size(input) in 1..MAX_DIRECTORY_BYTES) { "invalid directory input" }
        require(!Files.exists(output)) { "refusing to overwrite signed directory" }
        val directory = json.decodeFromString<UnsignedRegionalShelterDirectory>(Files.readString(input, StandardCharsets.UTF_8))
        require(directory.regionId == material.regionId) { "directory region does not match root" }
        require(directory.validate() == RescueValidationResult.Valid) { "invalid directory" }
        val root = material.rootBundle()
        require(directory.shelters.all { verifyShelterManifest(it, root) }) { "invalid shelter manifest signature" }
        val signed = signRegionalShelterDirectory(directory, material.privateKey())
        writeNew(output, json.encodeToString(signed).toByteArray(StandardCharsets.UTF_8))
        println("Signed regional directory region=${directory.regionId} generation=${directory.generation}.")
        printDirectoryFingerprints(signed, root)
    }

    private fun verifyRegionalDirectory(options: Map<String, String>) {
        val root = loadPublicRoot(options)
        val signed = loadSignedDirectory(Path.of(options.required("--directory-file")))
        val now = options["--now-epoch-millis"]?.toLongOrNull() ?: System.currentTimeMillis()
        require(signed.directory.regionId == root.regionId) { "directory region mismatch" }
        require(verifyRegionalShelterDirectory(signed, root, now)) { "invalid directory signature or validity" }
        require(signed.directory.shelters.all { verifyShelterManifest(it, root, now) }) { "invalid shelter manifest" }
        println("Regional directory verified for region=${root.regionId} generation=${signed.directory.generation}.")
        printDirectoryFingerprints(signed, root)
    }

    private fun printPublicFingerprints(options: Map<String, String>) {
        val root = loadPublicRoot(options)
        println("Root region=${root.regionId} publicKeyFingerprint=${root.rootSigningPublicKey.keyId}")
        options["--directory-file"]?.let { value ->
            val directory = loadSignedDirectory(Path.of(value))
            printDirectoryFingerprints(directory, root)
        }
    }

    private fun loadPublicRoot(options: Map<String, String>): RegionalRootBundle {
        val path = Path.of(options.required("--root-bundle-file"))
        require(Files.isRegularFile(path) && Files.size(path) in 1..MAX_ROOT_BUNDLE_BYTES) { "invalid root bundle" }
        val exported = json.decodeFromString<RegionalRootBundleExport>(Files.readString(path, StandardCharsets.UTF_8))
        require(exported.environment in setOf("TEST", "PILOT", "PRODUCTION")) { "invalid root bundle environment" }
        val roots = exported.roots
        require(roots.size == 1) { "exactly one root bundle is required" }
        return roots.single().also { require(it.validate() == RescueValidationResult.Valid) { "invalid root bundle" } }
    }

    private fun loadSignedDirectory(path: Path): SignedRegionalShelterDirectory {
        require(Files.isRegularFile(path) && Files.size(path) in 1..MAX_DIRECTORY_BYTES) { "invalid signed directory" }
        return json.decodeFromString(Files.readString(path, StandardCharsets.UTF_8))
    }

    private fun loadMaterial(path: Path): RegionalRootSigningMaterial {
        require(Files.isRegularFile(path) && Files.size(path) in 1..MAX_ROOT_MATERIAL_BYTES) { "invalid root material" }
        requireOfflinePrivatePath(path)
        return json.decodeFromString<RegionalRootSigningMaterial>(Files.readString(path, StandardCharsets.UTF_8))
            .also { it.privateKey() }
    }

    private fun printDirectoryFingerprints(directory: SignedRegionalShelterDirectory, root: RegionalRootBundle) {
        println("Directory signed fingerprint: ${directory.signedDirectoryFingerprint()}")
        println("Root public key fingerprint: ${root.rootSigningPublicKey.keyId}")
        directory.directory.shelters.forEach { shelter ->
            println(
                "Shelter=${shelter.manifest.shelterId} recipientPublicKeyFingerprint=${shelter.manifest.recipientPublicKey.keyId} " +
                    "receiptSigningPublicKeyFingerprint=${shelter.manifest.receiptSigningPublicKey.keyId} " +
                    "signedManifestFingerprint=${shelter.signedManifestFingerprint()}",
            )
        }
    }

    private fun parseOptions(args: List<String>): Map<String, String> = args.chunked(2).associate { pair ->
        require(pair.size == 2 && pair[0].startsWith("--") && pair[1].isNotBlank()) { "invalid option" }
        pair[0] to pair[1]
    }

    private fun Map<String, String>.required(name: String): String = get(name) ?: error("missing option")

    private fun writeNew(path: Path, contents: ByteArray) {
        val parent = path.toAbsolutePath().parent ?: error("missing output parent")
        Files.createDirectories(parent)
        require(!Files.exists(path)) { "refusing to overwrite output" }
        val temporary = Files.createTempFile(parent, ".regional-trust-", ".tmp")
        try {
            Files.write(temporary, contents)
            moveNew(temporary, path)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun writeNewOwnerOnly(path: Path, contents: ByteArray) {
        val parent = path.toAbsolutePath().parent ?: error("missing output parent")
        Files.createDirectories(parent)
        require(!Files.exists(path)) { "refusing to overwrite output" }
        val temporary = Files.createTempFile(parent, ".regional-root-private-", ".tmp")
        try {
            restrictOwnerOnly(temporary)
            Files.write(temporary, contents)
            moveNew(temporary, path)
            restrictOwnerOnly(path)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun moveNew(temporary: Path, target: Path) {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target)
        }
    }

    private const val MAX_ROOT_MATERIAL_BYTES = 32L * 1024
    private const val MAX_ROOT_BUNDLE_BYTES = 256L * 1024
    private const val MAX_DIRECTORY_BYTES = 512L * 1024
}
