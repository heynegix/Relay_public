package com.example.relay.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps the SQLCipher passphrase encrypted by an Android Keystore AES key. */
class SqlCipherPassphraseStore(
    private val context: Context,
    private val preferencesName: String = PREFERENCES,
    private val keyAlias: String = KEY_ALIAS,
) {
    fun loadOrCreate(): ByteArray = synchronized(INITIALIZATION_LOCK) {
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val encrypted = preferences.getString(PASSPHRASE, null)
        if (encrypted == null) {
            val passphrase = ByteArray(32).also(SecureRandom()::nextBytes)
            check(preferences.edit().putString(PASSPHRASE, seal(passphrase)).commit()) {
                "failed to persist SQLCipher passphrase"
            }
            return@synchronized passphrase
        }
        open(encrypted)
    }

    private fun seal(value: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            // Android Keystore generates the randomized GCM IV. Passing a caller IV
            // is rejected on recent Android releases when randomized encryption is
            // required, so persist the provider-generated IV beside the ciphertext.
            init(Cipher.ENCRYPT_MODE, key())
        }
        val nonce = cipher.iv ?: error("Android Keystore did not return a GCM IV")
        return encode(nonce + cipher.doFinal(value))
    }

    private fun open(value: String): ByteArray {
        val sealed = decode(value)
        require(sealed.size > 12) { "invalid SQLCipher passphrase record" }
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
            doFinal(sealed.copyOfRange(12, sealed.size))
        }.also { require(it.size == 32) { "invalid SQLCipher passphrase length" } }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private fun encode(bytes: ByteArray) = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    private fun decode(value: String) = android.util.Base64.decode(value, android.util.Base64.DEFAULT)

    private companion object {
        val INITIALIZATION_LOCK = Any()
        const val PREFERENCES = "relay_db_security"
        const val PASSPHRASE = "sqlcipher_passphrase"
        const val KEY_ALIAS = "relay_sqlcipher_key_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
