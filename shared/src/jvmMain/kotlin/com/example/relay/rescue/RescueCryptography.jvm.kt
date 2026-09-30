package com.example.relay.rescue

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import com.example.relay.domain.RelayMessage

actual object RescueCryptography {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val random = SecureRandom()

    actual fun generateRecipientKeyPair(): RescueKeyPair = generate("RSA", RescueKeyAlgorithm.RSA_OAEP_SHA256) {
        initialize(3072, random)
    }

    actual fun generateShelterSigningKeyPair(): RescueKeyPair = generate("EC", RescueKeyAlgorithm.ECDSA_P256_SHA256) {
        initialize(ECGenParameterSpec("secp256r1"), random)
    }

    actual fun generateReportSigningKeyPair(): RescueKeyPair = generateShelterSigningKeyPair()

    actual fun importPublicKey(keyId: String, algorithm: RescueKeyAlgorithm, encodedBase64: String): RescuePublicKey = guarded("invalid_public_key") {
        requireValidKeyId(keyId)
        val decoded = decode(encodedBase64)
        require(sha256Hex(decoded) == keyId) { "public_key_id_mismatch" }
        validatePublicKey(algorithm, keyFactory(algorithm).generatePublic(X509EncodedKeySpec(decoded)))
        RescuePublicKey(keyId, algorithm, encode(decoded))
    }

    actual fun importPrivateKey(keyId: String, algorithm: RescueKeyAlgorithm, encodedBase64: String): RescuePrivateKey = guarded("invalid_private_key") {
        requireValidKeyId(keyId)
        val decoded = decode(encodedBase64)
        validatePrivateKey(algorithm, keyFactory(algorithm).generatePrivate(PKCS8EncodedKeySpec(decoded)))
        RescuePrivateKey(keyId, algorithm, encode(decoded))
    }

    actual fun encrypt(payload: RescuePayload, recipientPublicKey: RescuePublicKey, envelopeId: String, maxHopCount: Int): EncryptedRescueEnvelope = guarded("encryption_failed") {
        require(payload.validate() == RescueValidationResult.Valid)
        require(recipientPublicKey.algorithm == RescueKeyAlgorithm.RSA_OAEP_SHA256)
        requireValidKeyId(envelopeId)
        require(maxHopCount in 1..32)
        val publicKey = parsePublic(recipientPublicKey)
        val keyGenerator = KeyGenerator.getInstance("AES").apply { init(256, random) }
        val contentKey = keyGenerator.generateKey()
        val nonce = ByteArray(12).also(random::nextBytes)
        val wrapped = rsaCipher(Cipher.ENCRYPT_MODE, publicKey).doFinal(contentKey.encoded)
        val wrappedBase64 = encode(wrapped)
        val nonceBase64 = encode(nonce)
        val plaintext = json.encodeToString(payload).encodeToByteArray()
        val header = EncryptedRescueEnvelope(
            envelopeId = envelopeId,
            requestId = payload.requestId,
            requestVersion = payload.requestVersion,
            senderDeviceId = payload.senderDeviceId,
            destinationShelterId = payload.destinationShelterId,
            routingUrgency = payload.urgency,
            recipientKeyId = recipientPublicKey.keyId,
            createdAtEpochMillis = payload.createdAtEpochMillis,
            expiresAtEpochMillis = payload.expiresAtEpochMillis,
            maxHopCount = maxHopCount,
            ciphertextSizeBytes = plaintext.size + 16,
            wrappedContentKeyBase64 = wrappedBase64,
            nonceBase64 = nonceBase64,
            ciphertextBase64 = "",
            ciphertextSha256Hex = "0".repeat(64),
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, contentKey, GCMParameterSpec(128, nonce), random)
            updateAAD(header.authenticatedHeaderBytes())
        }
        val ciphertext = cipher.doFinal(plaintext)
        require(ciphertext.size == header.ciphertextSizeBytes)
        header.copy(
            wrappedContentKeyBase64 = wrappedBase64,
            nonceBase64 = nonceBase64,
            ciphertextBase64 = encode(ciphertext),
            ciphertextSha256Hex = sha256Hex(ciphertext),
        )
    }

    actual fun decrypt(envelope: EncryptedRescueEnvelope, recipientPrivateKey: RescuePrivateKey): RescuePayload = guarded("decryption_failed") {
        require(envelope.validate() == RescueValidationResult.Valid)
        require(!envelope.hasSenderAuthorization() || verifySenderAuthorization(envelope))
        require(recipientPrivateKey.algorithm == RescueKeyAlgorithm.RSA_OAEP_SHA256)
        require(recipientPrivateKey.keyId == envelope.recipientKeyId)
        val ciphertext = decode(envelope.ciphertextBase64)
        require(ciphertext.size in 16..RESCUE_MAX_CIPHERTEXT_BYTES)
        require(ciphertext.size == envelope.ciphertextSizeBytes)
        require(MessageDigest.isEqual(hexToBytes(envelope.ciphertextSha256Hex), digest(ciphertext)))
        val nonce = decode(envelope.nonceBase64)
        require(nonce.size == 12)
        val wrapped = decode(envelope.wrappedContentKeyBase64)
        val keyBytes = rsaCipher(Cipher.DECRYPT_MODE, parsePrivate(recipientPrivateKey)).doFinal(wrapped)
        require(keyBytes.size == 32)
        val plaintext = runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(envelope.authenticatedHeaderBytes())
            }.doFinal(ciphertext)
        }.recoverCatching {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(envelope.legacyAuthenticatedHeaderBytes())
            }.doFinal(ciphertext)
        }.getOrThrow()
        val payload = json.decodeFromString<RescuePayload>(plaintext.decodeToString())
        require(payload.validate() == RescueValidationResult.Valid)
        require(payload.requestId == envelope.requestId && payload.senderDeviceId == envelope.senderDeviceId)
        require(payload.requestVersion == envelope.requestVersion)
        require(payload.destinationShelterId == envelope.destinationShelterId && payload.urgency == envelope.routingUrgency)
        require(payload.createdAtEpochMillis == envelope.createdAtEpochMillis && payload.expiresAtEpochMillis == envelope.expiresAtEpochMillis)
        payload
    }

    actual fun verifyEnvelopeFraming(envelope: EncryptedRescueEnvelope): Boolean = try {
        if (envelope.validate() != RescueValidationResult.Valid) false else {
            val ciphertext = decode(envelope.ciphertextBase64)
            ciphertext.size == envelope.ciphertextSizeBytes &&
                ciphertext.size in 16..RESCUE_MAX_CIPHERTEXT_BYTES &&
                MessageDigest.isEqual(hexToBytes(envelope.ciphertextSha256Hex), digest(ciphertext))
        }
    } catch (_: Exception) {
        false
    }

    actual fun verifySenderAuthorization(envelope: EncryptedRescueEnvelope): Boolean = try {
        if (envelope.senderKeyId.isBlank() &&
            envelope.senderPublicKeyBase64.isBlank() &&
            envelope.senderSignatureBase64.isBlank()
        ) return false
        if (envelope.validate() != RescueValidationResult.Valid) return false
        val key = RescuePublicKey(
            keyId = envelope.senderKeyId,
            algorithm = RescueKeyAlgorithm.ECDSA_P256_SHA256,
            encodedBase64 = envelope.senderPublicKeyBase64,
        )
        val signature = decode(envelope.senderSignatureBase64)
        Signature.getInstance("SHA256withECDSA").apply {
            initVerify(parsePublic(key))
            update(envelope.senderAuthorizationBytes())
        }.verify(signature) || Signature.getInstance("SHA256withECDSA").apply {
            initVerify(parsePublic(key))
            update(envelope.legacySenderAuthorizationBytes())
        }.verify(signature)
    } catch (_: Exception) {
        false
    }

    actual fun sha256Hex(bytes: ByteArray): String = digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    actual fun signReceipt(receipt: UnsignedShelterReceipt, shelterPrivateKey: RescuePrivateKey): SignedShelterReceipt = guarded("receipt_signing_failed") {
        require(receipt.validate() == RescueValidationResult.Valid)
        require(shelterPrivateKey.algorithm == RescueKeyAlgorithm.ECDSA_P256_SHA256)
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(parsePrivate(shelterPrivateKey), random)
            update(receipt.signingBytes())
        }.sign()
        SignedShelterReceipt(receipt, shelterPrivateKey.keyId, signatureBase64 = encode(signature))
    }

    actual fun verifyReceipt(receipt: SignedShelterReceipt, shelterPublicKey: RescuePublicKey): Boolean {
        return try {
            if (receipt.validate() != RescueValidationResult.Valid || shelterPublicKey.algorithm != RescueKeyAlgorithm.ECDSA_P256_SHA256 || receipt.signerKeyId != shelterPublicKey.keyId) {
                false
            } else {
                Signature.getInstance("SHA256withECDSA").apply {
                    initVerify(parsePublic(shelterPublicKey))
                    update(receipt.receipt.signingBytes())
                }.verify(decode(receipt.signatureBase64))
            }
        } catch (_: Exception) {
            false
        }
    }

    actual fun signTrustDocument(canonicalBytes: ByteArray, regionalSigningPrivateKey: RescuePrivateKey): TrustDocumentSignature = guarded("trust_document_signing_failed") {
        requireCanonicalTrustDocument(canonicalBytes)
        require(regionalSigningPrivateKey.algorithm == RescueKeyAlgorithm.ECDSA_P256_SHA256)
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(parsePrivate(regionalSigningPrivateKey), random)
            update(canonicalBytes)
        }.sign()
        TrustDocumentSignature(
            signerKeyId = regionalSigningPrivateKey.keyId,
            signatureBase64 = encode(signature),
        )
    }

    actual fun verifyTrustDocument(canonicalBytes: ByteArray, signature: TrustDocumentSignature, regionalSigningPublicKey: RescuePublicKey): Boolean {
        return try {
            if (
                signature.validate() != RescueValidationResult.Valid ||
                regionalSigningPublicKey.algorithm != RescueKeyAlgorithm.ECDSA_P256_SHA256 ||
                signature.signerKeyId != regionalSigningPublicKey.keyId
            ) {
                false
            } else {
                requireCanonicalTrustDocument(canonicalBytes)
                Signature.getInstance("SHA256withECDSA").apply {
                    initVerify(parsePublic(regionalSigningPublicKey))
                    update(canonicalBytes)
                }.verify(decode(signature.signatureBase64))
            }
        } catch (_: Exception) {
            false
        }
    }

    actual fun signReport(message: RelayMessage, signingKeyPair: RescueKeyPair): RelayMessage = guarded("report_signing_failed") {
        require(message.recordType == com.example.relay.domain.RelayRecordType.REPORT)
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(parsePrivate(signingKeyPair.privateKey), random)
            update(reportBytes(message))
        }.sign()
        message.copy(reportSignature = ReportSignature(signingKeyPair.publicKey.keyId, publicKey = signingKeyPair.publicKey, signatureBase64 = encode(signature)))
    }

    actual fun verifyReport(message: RelayMessage): Boolean {
        return try {
            val signed = message.reportSignature ?: return false
            if (message.recordType != com.example.relay.domain.RelayRecordType.REPORT || signed.validate() != RescueValidationResult.Valid) return false
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(parsePublic(signed.publicKey))
                update(reportBytes(message))
            }.verify(decode(signed.signatureBase64))
        } catch (_: Exception) { false }
    }

    private fun reportBytes(message: RelayMessage): ByteArray = reportSigningBytes(message)

    private fun generate(jcaAlgorithm: String, algorithm: RescueKeyAlgorithm, initialize: KeyPairGenerator.() -> Unit): RescueKeyPair = guarded("key_generation_failed") {
        val pair = KeyPairGenerator.getInstance(jcaAlgorithm).apply(initialize).generateKeyPair()
        val keyId = sha256Hex(pair.public.encoded)
        RescueKeyPair(RescuePublicKey(keyId, algorithm, encode(pair.public.encoded)), RescuePrivateKey(keyId, algorithm, encode(pair.private.encoded)))
    }

    private fun parsePublic(key: RescuePublicKey): PublicKey {
        val encoded = decode(key.encodedBase64)
        require(sha256Hex(encoded) == key.keyId) { "public_key_id_mismatch" }
        return keyFactory(key.algorithm).generatePublic(X509EncodedKeySpec(encoded)).also { validatePublicKey(key.algorithm, it) }
    }
    private fun parsePrivate(key: RescuePrivateKey): PrivateKey = keyFactory(key.algorithm).generatePrivate(PKCS8EncodedKeySpec(decode(key.encodedBase64))).also { validatePrivateKey(key.algorithm, it) }
    private fun validatePublicKey(algorithm: RescueKeyAlgorithm, key: PublicKey) {
        when (algorithm) {
            RescueKeyAlgorithm.RSA_OAEP_SHA256 -> require(key is RSAPublicKey && key.modulus.bitLength() >= 3072)
            RescueKeyAlgorithm.ECDSA_P256_SHA256 -> require(key is ECPublicKey && key.params.order.bitLength() == 256)
        }
    }
    private fun validatePrivateKey(algorithm: RescueKeyAlgorithm, key: PrivateKey) {
        when (algorithm) {
            RescueKeyAlgorithm.RSA_OAEP_SHA256 -> require(key is RSAPrivateKey && key.modulus.bitLength() >= 3072)
            RescueKeyAlgorithm.ECDSA_P256_SHA256 -> require(key is ECPrivateKey && key.params.order.bitLength() == 256)
        }
    }
    private fun keyFactory(algorithm: RescueKeyAlgorithm) = KeyFactory.getInstance(if (algorithm == RescueKeyAlgorithm.RSA_OAEP_SHA256) "RSA" else "EC")
    private fun rsaCipher(mode: Int, key: java.security.Key): Cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply {
        init(mode, key, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT), random)
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)
    private fun hexToBytes(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun requireCanonicalTrustDocument(bytes: ByteArray) = require(bytes.isNotEmpty() && bytes.size <= MAX_TRUST_DOCUMENT_BYTES)
    private fun requireValidKeyId(value: String) = require(value.length in 1..128 && value.all { it.isLetterOrDigit() || it in "-_.:" })
    private inline fun <T> guarded(code: String, block: () -> T): T = try { block() } catch (error: RescueCryptoException) { throw error } catch (error: Exception) { throw RescueCryptoException(code, error) }

    private const val MAX_TRUST_DOCUMENT_BYTES = 512 * 1024
}
