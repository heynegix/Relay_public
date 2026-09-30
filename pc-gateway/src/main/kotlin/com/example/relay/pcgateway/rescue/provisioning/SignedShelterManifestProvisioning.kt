package com.example.relay.pcgateway.rescue.provisioning

import com.example.relay.pcgateway.BleBridgeEnvironment
import com.example.relay.pcgateway.GatewayConfig
import com.example.relay.pcgateway.bleBridgeEnvironmentFor
import com.example.relay.pcgateway.rescue.RescueClock
import com.example.relay.pcgateway.rescue.RescueGatewayKeys
import com.example.relay.pcgateway.rescue.RescueKeyStore
import com.example.relay.rescue.RegionalRootBundle
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueKeyAlgorithm
import com.example.relay.rescue.RescuePrivateKey
import com.example.relay.rescue.RescuePublicKey
import com.example.relay.rescue.RescueValidationResult
import com.example.relay.rescue.SignedShelterManifest
import com.example.relay.rescue.signShelterManifest
import com.example.relay.rescue.verifyShelterManifest
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Offline-only regional signing material. The JSON file must be held by the regional operator,
 * never by a running shelter gateway or Android device.
 */
@Serializable
data class RegionalRootSigningMaterial(
    val fileVersion: Int = 1,
    val regionId: String,
    /** Operator-side classification only; Android public bundles intentionally omit private metadata. */
    val environment: String = "UNSPECIFIED",
    val operatorLabel: String = "Relay Regional Root — NOT MUNICIPAL PRODUCTION",
    val rootSigningPublicKey: RescuePublicKey,
    val rootSigningPrivateKey: PersistedRegionalPrivateKey,
) {
    fun rootBundle(): RegionalRootBundle = RegionalRootBundle(regionId = regionId, rootSigningPublicKey = rootSigningPublicKey)

    fun privateKey(): RescuePrivateKey {
        require(fileVersion == FILE_VERSION) { "unsupported root signing material" }
        require(rootBundle().validate() == RescueValidationResult.Valid) { "invalid root bundle" }
        require(rootSigningPrivateKey.algorithm == RescueKeyAlgorithm.ECDSA_P256_SHA256) { "invalid root private key" }
        require(rootSigningPrivateKey.keyId == rootSigningPublicKey.keyId) { "root key id mismatch" }
        val privateKey = RescueCryptography.importPrivateKey(
            rootSigningPrivateKey.keyId,
            rootSigningPrivateKey.algorithm,
            rootSigningPrivateKey.encodedBase64,
        )
        val proof = RescueCryptography.signTrustDocument("relay.root-key-proof.v1".encodeToByteArray(), privateKey)
        require(
            RescueCryptography.verifyTrustDocument(
                "relay.root-key-proof.v1".encodeToByteArray(),
                proof,
                rootSigningPublicKey,
            ),
        ) { "root public/private key mismatch" }
        return privateKey
    }

    companion object { const val FILE_VERSION = 1 }
}

@Serializable
data class PersistedRegionalPrivateKey(
    val keyId: String,
    val algorithm: RescueKeyAlgorithm,
    val encodedBase64: String,
)

/** Writes/reads public, root-signed shelter manifests. A running gateway only uses [loadVerified]. */
class SignedShelterManifestStore(
    private val path: Path,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
) {
    fun write(signed: SignedShelterManifest) {
        require(signed.validate() == RescueValidationResult.Valid) { "invalid signed shelter manifest" }
        atomicWriteOwnerOnly(path, json.encodeToString(signed).encodeToByteArray())
    }

    fun loadVerified(
        rootBundle: RegionalRootBundle,
        localKeys: RescueGatewayKeys,
        nowEpochMillis: Long,
    ): SignedShelterManifest {
        require(Files.isRegularFile(path)) { "signed shelter manifest is missing" }
        require(Files.size(path) in 1..MAX_SIGNED_MANIFEST_BYTES) { "invalid signed shelter manifest size" }
        val signed = json.decodeFromString<SignedShelterManifest>(Files.readString(path, Charsets.UTF_8))
        require(rootBundle.validate() == RescueValidationResult.Valid) { "invalid regional root bundle" }
        require(verifyShelterManifest(signed, rootBundle, nowEpochMillis)) { "signed shelter manifest is not trusted" }
        require(signed.manifest.shelterId == localKeys.manifest.shelterId) { "signed shelter id mismatch" }
        require(signed.manifest.recipientPublicKey == localKeys.manifest.recipientPublicKey) { "signed recipient key mismatch" }
        require(signed.manifest.receiptSigningPublicKey == localKeys.manifest.receiptSigningPublicKey) {
            "signed receipt key mismatch"
        }
        return signed
    }

    companion object { private const val MAX_SIGNED_MANIFEST_BYTES = 32L * 1024 }
}

/** Owner-only, non-secret bridge launcher environment generated from the signed identity. */
object BleBridgeEnvironmentStore {
    fun write(path: Path, configuration: BleBridgeEnvironment) {
        val contents = configuration.asEnvironmentValues().entries
            .joinToString(separator = "\n", postfix = "\n") { (name, value) -> "$name=$value" }
            .encodeToByteArray()
        atomicWriteOwnerOnly(path, contents)
    }
}

/**
 * Operator entrypoint. It loads the PC's already-generated public manifest, signs it off-device
 * with the supplied root material, and writes no private material to the shelter manifest file.
 */
class ShelterManifestProvisioner(
    private val clock: RescueClock = RescueClock(System::currentTimeMillis),
) {
    fun provision(
        shelterKeyFile: Path,
        shelterId: String,
        regionalRootSigningMaterialFile: Path,
        signedManifestFile: Path,
    ): SignedShelterManifest {
        val rootMaterial = loadRootSigningMaterial(regionalRootSigningMaterialFile)
        val localKeys = RescueKeyStore(shelterKeyFile, shelterId, clock).loadOrCreate()
        val signed = signShelterManifest(rootMaterial.regionId, localKeys.manifest, rootMaterial.privateKey())
        SignedShelterManifestStore(signedManifestFile).write(signed)
        BleBridgeEnvironmentStore.write(
            signedManifestFile.resolveSibling("ble-bridge.env"),
            GatewayConfig(shelterId = shelterId).bleBridgeEnvironmentFor(signed),
        )
        return signed
    }

    fun loadRootSigningMaterial(path: Path): RegionalRootSigningMaterial {
        require(Files.isRegularFile(path)) { "regional root signing material is missing" }
        require(Files.size(path) in 1..MAX_ROOT_MATERIAL_BYTES) { "invalid regional root signing material size" }
        requireOfflinePrivatePath(path)
        return Json { encodeDefaults = true; ignoreUnknownKeys = false }
            .decodeFromString<RegionalRootSigningMaterial>(Files.readString(path, Charsets.UTF_8))
            .also { it.privateKey() }
    }

    private companion object { const val MAX_ROOT_MATERIAL_BYTES = 32L * 1024 }
}

/**
 * Minimal offline CLI adapter. It is deliberately not wired into the normal Gateway startup.
 * Example: provision --shelter-key-file <path> --shelter-id <id> --root-material-file <path>
 * --signed-manifest-file <path>
 */
object ShelterManifestProvisioningCli {
    fun run(args: Array<String>): Int = runCatching {
        require(args.firstOrNull() == "provision") { "expected 'provision' command" }
        val options = args.drop(1).chunked(2).associate { pair ->
            require(pair.size == 2 && pair[0].startsWith("--")) { "invalid option" }
            pair[0] to pair[1]
        }
        val signed = ShelterManifestProvisioner().provision(
            shelterKeyFile = Path.of(options.required("--shelter-key-file")),
            shelterId = options.required("--shelter-id"),
            regionalRootSigningMaterialFile = Path.of(options.required("--root-material-file")),
            signedManifestFile = Path.of(options.required("--signed-manifest-file")),
        )
        println("Provisioned signed shelter manifest for ${signed.manifest.shelterId}; fingerprint=${signed.manifest.fingerprint()}")
        0
    }.getOrElse {
        // A malformed root-material input can contain sensitive text. Do not reflect exception
        // messages to the console from this offline signing path.
        System.err.println("Provisioning failed. No signed shelter manifest was written.")
        2
    }

    private fun Map<String, String>.required(name: String): String = get(name)?.takeIf { it.isNotBlank() }
        ?: error("missing $name")
}

private fun atomicWriteOwnerOnly(path: Path, contents: ByteArray) {
    val parent = path.toAbsolutePath().parent ?: error("signed manifest requires a parent directory")
    Files.createDirectories(parent)
    val temporary = Files.createTempFile(parent, ".signed-shelter-manifest-", ".tmp")
    try {
        restrictOwnerOnly(temporary)
        Files.write(temporary, contents)
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
        restrictOwnerOnly(path)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

/** Shared by offline root generation; normal Gateway startup never receives root material. */
internal fun restrictOwnerOnly(target: Path) {
    val posix = runCatching { Files.getPosixFilePermissions(target) }.getOrNull()
    if (posix != null) {
        Files.setPosixFilePermissions(target, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        return
    }
    val aclView = Files.getFileAttributeView(target, AclFileAttributeView::class.java)
        ?: error("filesystem cannot enforce owner-only signed manifest permissions")
    val owner = Files.getOwner(target)
    val ownerOnly = AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(owner)
        .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
        .build()
    aclView.acl = listOf(ownerOnly)
}

/** Private root material is an offline operator artifact and must never live in a Git worktree. */
internal fun requireOfflinePrivatePath(path: Path) {
    val absolutePath = path.toAbsolutePath().normalize()
    val unresolvedSegments = mutableListOf<Path>()
    var existingAncestor = absolutePath
    while (!Files.exists(existingAncestor, LinkOption.NOFOLLOW_LINKS)) {
        unresolvedSegments.add(existingAncestor.fileName ?: error("private material requires a filesystem parent"))
        existingAncestor = existingAncestor.parent
            ?: error("private material requires a filesystem parent")
    }
    var canonicalPath = existingAncestor.toRealPath()
    unresolvedSegments.asReversed().forEach { segment ->
        canonicalPath = canonicalPath.resolve(segment)
    }

    var parent: Path? = canonicalPath.parent
    while (parent != null) {
        require(!Files.exists(parent.resolve(".git"))) { "private material may not be stored under a Git worktree" }
        parent = parent.parent
    }
}
