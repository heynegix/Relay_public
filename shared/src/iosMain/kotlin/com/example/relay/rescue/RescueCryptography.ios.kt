package com.example.relay.rescue

import com.example.relay.domain.RelayMessage

/**
 * iOS actual for RescueCryptography.
 *
 * sha256Hex is implemented in pure Kotlin (no cinterop dependency).
 * All other cryptographic operations throw [RescueCryptoException] with code
 * "ios_not_yet_implemented" — they are not exercised by the current iOS smoke
 * build which only renders the Compose UI.
 *
 * Full Apple Security framework implementation is planned for a future PR.
 */
actual object RescueCryptography {

    private fun notImplemented(): Nothing =
        throw RescueCryptoException("ios_not_yet_implemented")

    actual fun generateRecipientKeyPair(): RescueKeyPair = notImplemented()

    actual fun generateShelterSigningKeyPair(): RescueKeyPair = notImplemented()

    actual fun generateReportSigningKeyPair(): RescueKeyPair = notImplemented()

    actual fun importPublicKey(
        keyId: String,
        algorithm: RescueKeyAlgorithm,
        encodedBase64: String,
    ): RescuePublicKey = notImplemented()

    actual fun importPrivateKey(
        keyId: String,
        algorithm: RescueKeyAlgorithm,
        encodedBase64: String,
    ): RescuePrivateKey = notImplemented()

    actual fun encrypt(
        payload: RescuePayload,
        recipientPublicKey: RescuePublicKey,
        envelopeId: String,
        maxHopCount: Int,
    ): EncryptedRescueEnvelope = notImplemented()

    actual fun decrypt(
        envelope: EncryptedRescueEnvelope,
        recipientPrivateKey: RescuePrivateKey,
    ): RescuePayload = notImplemented()

    actual fun verifyEnvelopeFraming(envelope: EncryptedRescueEnvelope): Boolean = notImplemented()

    actual fun verifySenderAuthorization(envelope: EncryptedRescueEnvelope): Boolean = notImplemented()

    actual fun sha256Hex(bytes: ByteArray): String {
        val hash = Sha256.digest(bytes)
        return buildString(64) {
            for (b in hash) {
                val unsigned = b.toInt() and 0xFF
                append(unsigned.toString(16).padStart(2, '0'))
            }
        }
    }

    actual fun signReceipt(
        receipt: UnsignedShelterReceipt,
        shelterPrivateKey: RescuePrivateKey,
    ): SignedShelterReceipt = notImplemented()

    actual fun verifyReceipt(
        receipt: SignedShelterReceipt,
        shelterPublicKey: RescuePublicKey,
    ): Boolean = notImplemented()

    actual fun signTrustDocument(
        canonicalBytes: ByteArray,
        regionalSigningPrivateKey: RescuePrivateKey,
    ): TrustDocumentSignature = notImplemented()

    actual fun verifyTrustDocument(
        canonicalBytes: ByteArray,
        signature: TrustDocumentSignature,
        regionalSigningPublicKey: RescuePublicKey,
    ): Boolean = notImplemented()

    actual fun signReport(
        message: RelayMessage,
        signingKeyPair: RescueKeyPair,
    ): RelayMessage = notImplemented()

    actual fun verifyReport(message: RelayMessage): Boolean = notImplemented()
}

/** Pure-Kotlin SHA-256 — no cinterop or platform dependencies. */
private object Sha256 {
    private val K = intArrayOf(
        0x428a2f98.toInt(), 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

    fun digest(input: ByteArray): ByteArray {
        var h0 = 0x6a09e667
        var h1 = 0xbb67ae85.toInt()
        var h2 = 0x3c6ef372
        var h3 = 0xa54ff53a.toInt()
        var h4 = 0x510e527f
        var h5 = 0x9b05688c.toInt()
        var h6 = 0x1f83d9ab
        var h7 = 0x5be0cd19

        val msgLen = input.size
        val bitLen = msgLen.toLong() * 8
        // padding: 1 byte (0x80) + zeros + 8 bytes length
        val padLen = (56 - (msgLen + 1) % 64 + 64) % 64
        val padded = ByteArray(msgLen + 1 + padLen + 8)
        input.copyInto(padded)
        padded[msgLen] = 0x80.toByte()
        for (i in 0..7) padded[padded.size - 1 - i] = (bitLen ushr (i * 8)).toByte()

        val w = IntArray(64)
        var offset = 0
        while (offset < padded.size) {
            for (i in 0..15) {
                w[i] = ((padded[offset + i * 4].toInt() and 0xFF) shl 24) or
                    ((padded[offset + i * 4 + 1].toInt() and 0xFF) shl 16) or
                    ((padded[offset + i * 4 + 2].toInt() and 0xFF) shl 8) or
                    (padded[offset + i * 4 + 3].toInt() and 0xFF)
            }
            for (i in 16..63) {
                val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h0; var b = h1; var c = h2; var d = h3
            var e = h4; var f = h5; var g = h6; var h = h7
            for (i in 0..63) {
                val S1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = h + S1 + ch + K[i] + w[i]
                val S0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = S0 + maj
                h = g; g = f; f = e; e = d + temp1
                d = c; c = b; b = a; a = temp1 + temp2
            }
            h0 += a; h1 += b; h2 += c; h3 += d
            h4 += e; h5 += f; h6 += g; h7 += h
            offset += 64
        }

        val result = ByteArray(32)
        intToBytes(h0, result, 0); intToBytes(h1, result, 4)
        intToBytes(h2, result, 8); intToBytes(h3, result, 12)
        intToBytes(h4, result, 16); intToBytes(h5, result, 20)
        intToBytes(h6, result, 24); intToBytes(h7, result, 28)
        return result
    }

    private fun intToBytes(value: Int, dest: ByteArray, offset: Int) {
        dest[offset] = (value ushr 24).toByte()
        dest[offset + 1] = (value ushr 16).toByte()
        dest[offset + 2] = (value ushr 8).toByte()
        dest[offset + 3] = value.toByte()
    }

    private fun Int.rotateRight(n: Int): Int = (this ushr n) or (this shl (32 - n))
}
