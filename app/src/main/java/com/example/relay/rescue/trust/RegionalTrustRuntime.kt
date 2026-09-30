package com.example.relay.rescue.trust

import android.content.Context
import com.example.relay.rescue.DirectoryAcceptance
import com.example.relay.rescue.RegionalRootBundle
import com.example.relay.rescue.RegionalShelterDirectoryResolver
import com.example.relay.rescue.SignedRegionalShelterDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Supplies public trust anchors for the active build variant. */
fun interface RegionalRootLoader {
    fun load(): List<RegionalRootBundle>
}

/**
 * Asset-backed loader. Empty/missing/malformed input deliberately yields no roots and therefore no
 * BLE Gateway trust. It does not log raw documents or key material.
 */
class AssetRegionalRootLoader(
    private val context: Context,
    private val assetName: String,
    allowedEnvironmentCsv: String,
) : RegionalRootLoader {
    private val allowedEnvironments = allowedEnvironmentCsv
        .split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()

    override fun load(): List<RegionalRootBundle> {
        if (assetName.isBlank() || allowedEnvironments.isEmpty()) return emptyList()
        return runCatching {
            context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { reader ->
                RegionalRootBundleParser.parse(reader.readText(), allowedEnvironments)
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * Wires the variant root source to persisted signed directories.  It is intentionally independent
 * from LAN, Nearby, and Broker delivery: missing trust artifacts make only authenticated BLE
 * Gateway delivery unavailable.
 */
class RegionalTrustRuntime(
    private val rootLoader: RegionalRootLoader,
    private val directoryStore: VerifiedRegionalDirectoryStore,
    private val nowEpochMillis: () -> Long,
) {
    private val roots: List<RegionalRootBundle> = rootLoader.load()
    val resolver: RegionalShelterDirectoryResolver = safeResolver(roots)

    /**
     * Replays persisted state after application startup. Room I/O is dispatched internally, so
     * callers cannot accidentally access the database on the main thread. Until it finishes, the
     * resolver remains empty and BLE Gateway delivery fails closed.
     */
    suspend fun reloadAcceptedDirectories() = withContext(Dispatchers.IO) {
        // Expired or malformed persisted documents are left intact for audit but never accepted.
        directoryStore.loadAccepted(roots, nowEpochMillis()).forEach { directory ->
            resolver.accept(directory, nowEpochMillis())
        }
    }

    /** Future online/offline imports must use this path rather than mutating the resolver directly. */
    suspend fun acceptCandidate(candidate: SignedRegionalShelterDirectory): DirectoryStoreAcceptance =
        withContext(Dispatchers.IO) {
            val result = directoryStore.accept(candidate, roots, nowEpochMillis())
            if (result is DirectoryStoreAcceptance.Accepted) {
                // The store already verified it; repeat the in-memory acceptance to keep the resolver
                // and durable state synchronized. Failure remains fail-closed.
                resolver.accept(candidate, nowEpochMillis())
            }
            result
        }

    private fun safeResolver(roots: Collection<RegionalRootBundle>): RegionalShelterDirectoryResolver =
        runCatching { RegionalShelterDirectoryResolver(roots) }
            .getOrElse { RegionalShelterDirectoryResolver(emptyList()) }
}
