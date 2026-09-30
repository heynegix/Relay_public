package com.example.relay.gateway

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface GatewayCredentialStoreContract {
    fun save(token: String)
    fun load(): String?
    fun clear()
    fun hasToken(): Boolean
}

class GatewayCredentialStore(context: Context) : GatewayCredentialStoreContract {
    private val preferences = context.getSharedPreferences("relay_gateway_credentials", Context.MODE_PRIVATE)
    private val keyAlias = "relay_gateway_token"

    override fun save(token: String) {
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        preferences.edit().putString("token", Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)).apply()
    }

    override fun load(): String? = runCatching {
        val raw = preferences.getString("token", null) ?: return null
        val bytes = Base64.decode(raw, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }.getOrNull()

    override fun clear() { preferences.edit().remove("token").apply() }
    override fun hasToken(): Boolean = preferences.contains("token")

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(android.security.keystore.KeyGenParameterSpec.Builder(keyAlias, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }
}
