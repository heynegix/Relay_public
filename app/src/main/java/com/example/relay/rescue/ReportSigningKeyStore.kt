package com.example.relay.rescue

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.relay.domain.RelayMessage
import com.example.relay.domain.ReportSigner
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Owns the device REPORT signing identity. The private key is generated directly in
 * Android Keystore and is never exportable or serialized into preferences.
 */
class ReportSigningKeyStore : ReportSigner {
    @Synchronized
    fun loadOrCreate(): RescuePublicKey {
        ensureKey()
        return publicKey()
    }

    override fun sign(message: RelayMessage): RelayMessage {
        require(message.recordType == com.example.relay.domain.RelayRecordType.REPORT) {
            "only REPORT records may use the report signer"
        }
        val publicKey = loadOrCreate()
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey())
            update(reportSigningBytes(message))
        }.sign()
        return message.copy(
            reportSignature = ReportSignature(
                signerKeyId = publicKey.keyId,
                publicKey = publicKey,
                signatureBase64 = Base64.encodeToString(signature, Base64.NO_WRAP),
            ),
        ).also { signed -> check(RescueCryptography.verifyReport(signed)) { "report signature self-check failed" } }
    }

    private fun ensureKey() {
        val store = keyStore()
        if (store.containsAlias(KEY_ALIAS)) return
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                ).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
        }.generateKeyPair()
    }

    private fun privateKey(): PrivateKey =
        keyStore().getKey(KEY_ALIAS, null) as? PrivateKey
            ?: error("report signing key is unavailable")

    private fun publicKey(): RescuePublicKey {
        val encoded = requireNotNull(keyStore().getCertificate(KEY_ALIAS)?.publicKey?.encoded) {
            "report signing certificate is unavailable"
        }
        return RescuePublicKey(
            keyId = RescueCryptography.sha256Hex(encoded),
            algorithm = RescueKeyAlgorithm.ECDSA_P256_SHA256,
            encodedBase64 = Base64.encodeToString(encoded, Base64.NO_WRAP),
        )
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val KEY_ALIAS = "relay_report_signing_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
