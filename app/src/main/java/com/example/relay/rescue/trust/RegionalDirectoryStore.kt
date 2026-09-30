package com.example.relay.rescue.trust

import com.example.relay.rescue.DirectoryAcceptance
import com.example.relay.rescue.RegionalRootBundle
import com.example.relay.rescue.RegionalShelterDirectoryResolver
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueValidationResult
import com.example.relay.rescue.SignedRegionalShelterDirectory
import com.example.relay.rescue.signedDirectoryFingerprint
import com.example.relay.rescue.verifyRegionalShelterDirectory
import com.example.relay.rescue.verifyShelterManifest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Public-only document format supplied to an Android build variant by an operator. */
@Serializable
data class RegionalRootBundleDocument(
    /** TEST roots are never accepted by a release-derived Android build. */
    val environment: String,
    val roots: List<RegionalRootBundle>,
)

/**
 * Strict parser for regional public trust anchors.
 *
 * It intentionally returns an empty list for every failure.  Root absence disables only the BLE
 * trust path; callers must not fall back to TOFU or to an unsigned shelter manifest.
 */
object RegionalRootBundleParser {
    private const val MAX_ROOT_DOCUMENT_BYTES = 256 * 1_024
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun parse(
        raw: String,
        allowedEnvironments: Set<String> = ALL_ENVIRONMENTS,
    ): List<RegionalRootBundle> = runCatching {
        require(raw.encodeToByteArray().size <= MAX_ROOT_DOCUMENT_BYTES)
        val document = json.decodeFromString<RegionalRootBundleDocument>(raw)
        require(document.environment in ALL_ENVIRONMENTS && document.environment in allowedEnvironments)
        val roots = document.roots
        require(roots.map { it.regionId }.distinct().size == roots.size)
        require(roots.map { it.rootSigningPublicKey.keyId }.distinct().size == roots.size)
        roots.forEach { root ->
            require(root.validate() == RescueValidationResult.Valid)
            // Parsing as an X.509 public key rejects PKCS#8/private-key material without logging it.
            RescueCryptography.importPublicKey(
                root.rootSigningPublicKey.keyId,
                root.rootSigningPublicKey.algorithm,
                root.rootSigningPublicKey.encodedBase64,
            )
        }
        roots
    }.getOrElse { emptyList() }

    fun serialize(
        roots: List<RegionalRootBundle>,
        environment: String = ENVIRONMENT_TEST,
    ): String = json.encodeToString(RegionalRootBundleDocument(environment, roots))

    const val ENVIRONMENT_TEST = "TEST"
    const val ENVIRONMENT_PILOT = "PILOT"
    const val ENVIRONMENT_PRODUCTION = "PRODUCTION"
    val ALL_ENVIRONMENTS: Set<String> = setOf(ENVIRONMENT_TEST, ENVIRONMENT_PILOT, ENVIRONMENT_PRODUCTION)
}

/** A public signed directory and the integrity metadata used for monotonic replacement. */
data class StoredRegionalDirectory(
    val regionId: String,
    val generation: Long,
    val directoryDigest: String,
    val directoryJson: String,
    val acceptedAtEpochMillis: Long,
)

/**
 * Small transactional abstraction so the trust state can use Room in production and an in-memory
 * implementation in JVM tests.  Replacing one row never removes the last accepted directory
 * before the replacement has committed.
 */
interface RegionalDirectoryPersistence {
    fun <T> transaction(block: () -> T): T
    fun find(regionId: String): StoredRegionalDirectory?
    fun all(): List<StoredRegionalDirectory>
    fun replace(record: StoredRegionalDirectory): Boolean
}

sealed interface DirectoryStoreAcceptance {
    data class Accepted(val directory: SignedRegionalShelterDirectory) : DirectoryStoreAcceptance
    data object AlreadyAccepted : DirectoryStoreAcceptance
    data object Stale : DirectoryStoreAcceptance
    data object Invalid : DirectoryStoreAcceptance
    /** A locally persisted record cannot be authenticated, so it is retained but never overwritten. */
    data object CorruptExisting : DirectoryStoreAcceptance
    /** The previous directory remains authoritative because the replacement did not commit. */
    data object WriteFailed : DirectoryStoreAcceptance
}

/**
 * Verifies before persisting and re-verifies before use.  It stores no private material and does
 * not delete malformed records: silently deleting a corrupted session/configuration would turn a
 * failure into an apparently fresh state.
 */
class VerifiedRegionalDirectoryStore(
    private val persistence: RegionalDirectoryPersistence,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
) {
    fun accept(
        candidate: SignedRegionalShelterDirectory,
        roots: Collection<RegionalRootBundle>,
        nowEpochMillis: Long,
    ): DirectoryStoreAcceptance {
        val resolver = resolverOrNull(roots) ?: return DirectoryStoreAcceptance.Invalid
        return persistence.transaction {
            // Always validate the incoming document at the current time before considering a
            // same-generation retry. An expired document is never accepted as "idempotent".
            if (resolver.accept(candidate, nowEpochMillis) !is DirectoryAcceptance.Accepted) {
                return@transaction DirectoryStoreAcceptance.Invalid
            }
            val existing = persistence.find(candidate.directory.regionId)
            if (existing != null) {
                val decoded = decodeVerifiedRecord(existing) ?: return@transaction DirectoryStoreAcceptance.CorruptExisting
                if (!verifyHistorical(decoded, roots)) return@transaction DirectoryStoreAcceptance.CorruptExisting
                when {
                    candidate.directory.generation < decoded.directory.generation ->
                        return@transaction DirectoryStoreAcceptance.Stale
                    candidate.directory.generation == decoded.directory.generation &&
                        candidate.signedDirectoryFingerprint() == decoded.signedDirectoryFingerprint() ->
                        return@transaction DirectoryStoreAcceptance.AlreadyAccepted
                    candidate.directory.generation == decoded.directory.generation ->
                        return@transaction DirectoryStoreAcceptance.Invalid
                }
            }
            val record = StoredRegionalDirectory(
                regionId = candidate.directory.regionId,
                generation = candidate.directory.generation,
                directoryDigest = candidate.signedDirectoryFingerprint(),
                directoryJson = json.encodeToString(candidate),
                acceptedAtEpochMillis = nowEpochMillis,
            )
            if (persistence.replace(record)) DirectoryStoreAcceptance.Accepted(candidate)
            else DirectoryStoreAcceptance.WriteFailed
        }
    }

    /**
     * Replays only current, authenticated data into a newly constructed resolver.  Expired data is
     * intentionally left on disk for audit/recovery, but is not returned or trusted.
     */
    fun loadAccepted(
        roots: Collection<RegionalRootBundle>,
        nowEpochMillis: Long,
    ): List<SignedRegionalShelterDirectory> {
        val resolver = resolverOrNull(roots) ?: return emptyList()
        return persistence.transaction {
            persistence.all()
                .sortedBy { it.regionId }
                .mapNotNull { record ->
                    val directory = decodeVerifiedRecord(record) ?: return@mapNotNull null
                    when (resolver.accept(directory, nowEpochMillis)) {
                        is DirectoryAcceptance.Accepted,
                        DirectoryAcceptance.AlreadyAccepted,
                        -> directory
                        DirectoryAcceptance.Stale,
                        DirectoryAcceptance.Invalid,
                        -> null
                    }
                }
        }
    }

    private fun decodeVerifiedRecord(record: StoredRegionalDirectory): SignedRegionalShelterDirectory? = runCatching {
        require(record.regionId.isNotBlank() && record.generation > 0 && record.acceptedAtEpochMillis > 0)
        val decoded = json.decodeFromString<SignedRegionalShelterDirectory>(record.directoryJson)
        require(decoded.directory.regionId == record.regionId)
        require(decoded.directory.generation == record.generation)
        require(decoded.signedDirectoryFingerprint() == record.directoryDigest)
        decoded
    }.getOrNull()

    private fun resolverOrNull(roots: Collection<RegionalRootBundle>): RegionalShelterDirectoryResolver? =
        runCatching { RegionalShelterDirectoryResolver(roots) }.getOrNull()

    /** Authenticates an old record without extending its validity window. */
    private fun verifyHistorical(
        directory: SignedRegionalShelterDirectory,
        roots: Collection<RegionalRootBundle>,
    ): Boolean {
        val root = roots.singleOrNull { it.regionId == directory.directory.regionId } ?: return false
        return root.validate() == RescueValidationResult.Valid &&
            verifyRegionalShelterDirectory(directory, root) &&
            directory.directory.shelters.all { verifyShelterManifest(it, root) }
    }
}
