package com.example.relay.cmp

import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescuePublicKey
import com.example.relay.rescue.TrustDocumentSignature

/** Untrusted bytes and metadata supplied by a platform-specific local asset loader. */
data class OfflineMapPackCandidate(
    val styleJson: ByteArray,
    val pmtiles: ByteArray,
    val styleUri: String,
    val pmtilesUri: String,
    val styleSha256Hex: String,
    val pmtilesSha256Hex: String,
    val signature: TrustDocumentSignature,
    val signingPublicKey: RescuePublicKey,
)

/** Only this type may cross the verification boundary into the map UI. */
data class VerifiedOfflineMapPack internal constructor(
    val styleUri: String,
    val pmtilesUri: String,
    val styleSha256Hex: String,
    val pmtilesSha256Hex: String,
)

enum class OfflineMapPackRejection {
    EMPTY_STYLE,
    INVALID_STYLE_JSON,
    INVALID_PMTILES_HEADER,
    UNSUPPORTED_STYLE_URI,
    UNSUPPORTED_PMTILES_URI,
    STYLE_HASH_MISMATCH,
    PMTILES_HASH_MISMATCH,
    INVALID_SIGNATURE,
}

data class OfflineMapPackVerification(
    val pack: VerifiedOfflineMapPack? = null,
    val rejection: OfflineMapPackRejection? = null,
) {
    val isAccepted: Boolean get() = pack != null
}

/**
 * Verifies the complete local map boundary before MapLibre is initialized.
 * The injected verifier keeps this logic testable without platform crypto or MapLibre.
 */
object OfflineMapPackVerifier {
    fun verify(
        candidate: OfflineMapPackCandidate,
        signatureVerifier: (ByteArray, TrustDocumentSignature, RescuePublicKey) -> Boolean =
            RescueCryptography::verifyTrustDocument,
    ): OfflineMapPackVerification {
        if (candidate.styleJson.isEmpty()) return rejected(OfflineMapPackRejection.EMPTY_STYLE)
        if (!looksLikeStyleJson(candidate.styleJson)) {
            return rejected(OfflineMapPackRejection.INVALID_STYLE_JSON)
        }
        if (!candidate.pmtiles.startsWith(PMTILES_MAGIC)) {
            return rejected(OfflineMapPackRejection.INVALID_PMTILES_HEADER)
        }
        if (!isLocalUri(candidate.styleUri)) return rejected(OfflineMapPackRejection.UNSUPPORTED_STYLE_URI)
        if (!isLocalUri(candidate.pmtilesUri)) return rejected(OfflineMapPackRejection.UNSUPPORTED_PMTILES_URI)

        val actualStyleHash = RescueCryptography.sha256Hex(candidate.styleJson)
        if (!actualStyleHash.equals(candidate.styleSha256Hex, ignoreCase = true)) {
            return rejected(OfflineMapPackRejection.STYLE_HASH_MISMATCH)
        }
        val actualPmtilesHash = RescueCryptography.sha256Hex(candidate.pmtiles)
        if (!actualPmtilesHash.equals(candidate.pmtilesSha256Hex, ignoreCase = true)) {
            return rejected(OfflineMapPackRejection.PMTILES_HASH_MISMATCH)
        }

        val canonicalBytes = canonicalSigningBytes(
            styleHash = actualStyleHash,
            pmtilesHash = actualPmtilesHash,
            styleUri = candidate.styleUri,
            pmtilesUri = candidate.pmtilesUri,
        )
        if (!signatureVerifier(canonicalBytes, candidate.signature, candidate.signingPublicKey)) {
            return rejected(OfflineMapPackRejection.INVALID_SIGNATURE)
        }

        return OfflineMapPackVerification(
            pack = VerifiedOfflineMapPack(
                styleUri = candidate.styleUri,
                pmtilesUri = candidate.pmtilesUri,
                styleSha256Hex = actualStyleHash,
                pmtilesSha256Hex = actualPmtilesHash,
            ),
        )
    }

    fun canonicalSigningBytes(
        styleHash: String,
        pmtilesHash: String,
        styleUri: String,
        pmtilesUri: String,
    ): ByteArray = listOf(
        "relay-offline-map-v1",
        styleHash.lowercase(),
        pmtilesHash.lowercase(),
        styleUri,
        pmtilesUri,
    ).joinToString("\n").encodeToByteArray()

    private fun rejected(rejection: OfflineMapPackRejection) = OfflineMapPackVerification(rejection = rejection)

    private fun isLocalUri(uri: String): Boolean =
        uri.startsWith("asset://") || uri.startsWith("file://") || uri.startsWith("content://")

    private fun looksLikeStyleJson(bytes: ByteArray): Boolean {
        val text = bytes.decodeToString().trimStart()
        return text.startsWith("{") && text.contains("\"version\"") && text.contains("\"sources\"")
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val PMTILES_MAGIC = "PMTiles".encodeToByteArray()
}
