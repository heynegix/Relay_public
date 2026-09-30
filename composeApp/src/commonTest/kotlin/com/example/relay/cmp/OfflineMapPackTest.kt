package com.example.relay.cmp

import com.example.relay.rescue.RescueKeyAlgorithm
import com.example.relay.rescue.RescuePublicKey
import com.example.relay.rescue.TrustDocumentSignature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineMapPackTest {
    private val style = "{\"version\":8,\"sources\":{}}".encodeToByteArray()
    private val pmtiles = "PMTiles\u0003fixture".encodeToByteArray()
    private val key = RescuePublicKey("test-key", RescueKeyAlgorithm.ECDSA_P256_SHA256, "test")

    @Test
    fun accepts_matching_hashes_and_signature() {
        val styleHash = com.example.relay.rescue.RescueCryptography.sha256Hex(style)
        val pmtilesHash = com.example.relay.rescue.RescueCryptography.sha256Hex(pmtiles)
        val candidate = candidate(styleHash, pmtilesHash)
        val result = OfflineMapPackVerifier.verify(candidate) { bytes, signature, publicKey ->
            bytes.contentEquals(OfflineMapPackVerifier.canonicalSigningBytes(styleHash, pmtilesHash, candidate.styleUri, candidate.pmtilesUri)) &&
                signature.signerKeyId == publicKey.keyId
        }
        assertTrue(result.isAccepted)
    }

    @Test
    fun rejects_changed_pmtiles_before_signature_verification() {
        val styleHash = com.example.relay.rescue.RescueCryptography.sha256Hex(style)
        val candidate = candidate(styleHash, "0".repeat(64), pmtiles = "PMTiles\u0003changed".encodeToByteArray())
        var signatureCalled = false
        val result = OfflineMapPackVerifier.verify(candidate) { _, _, _ -> signatureCalled = true; true }
        assertEquals(OfflineMapPackRejection.PMTILES_HASH_MISMATCH, result.rejection)
        assertTrue(!signatureCalled)
    }

    @Test
    fun rejects_remote_style_uri() {
        val candidate = candidate(
            com.example.relay.rescue.RescueCryptography.sha256Hex(style),
            com.example.relay.rescue.RescueCryptography.sha256Hex(pmtiles),
            styleUri = "https://example.invalid/style.json",
        )
        val result = OfflineMapPackVerifier.verify(candidate) { _, _, _ -> true }
        assertEquals(OfflineMapPackRejection.UNSUPPORTED_STYLE_URI, result.rejection)
    }

    private fun candidate(
        styleHash: String,
        pmtilesHash: String,
        styleUri: String = "asset://maps/relay/style.json",
        pmtilesUri: String = "asset://maps/relay/region.pmtiles",
        pmtiles: ByteArray = this.pmtiles,
    ) = OfflineMapPackCandidate(
        styleJson = style,
        pmtiles = pmtiles,
        styleUri = styleUri,
        pmtilesUri = pmtilesUri,
        styleSha256Hex = styleHash,
        pmtilesSha256Hex = pmtilesHash,
        signature = TrustDocumentSignature("test-key", signatureBase64 = "fixture"),
        signingPublicKey = key,
    )
}
