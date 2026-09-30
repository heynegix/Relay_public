package com.example.relay.rescue.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.relay.rescue.RescueRequestDraft
import com.example.relay.rescue.RescuePublicKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val RECOVERY_PAYLOAD_SCHEMA_VERSION = 1
private const val GCM_NONCE_BYTES = 12
private const val GCM_TAG_BITS = 128
private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"

/** Plaintext recovery data, valid only while being encrypted/decrypted in process memory. */
@Serializable
data class RescueSessionRecoveryPayload(
    val schemaVersion: Int = RECOVERY_PAYLOAD_SCHEMA_VERSION,
    val draft: RescueRequestDraft,
    /**
     * The public recipient key needed to create a later encrypted update after restart.
     *
     * Both keys are absent only while a sender-owned SOS is waiting for a trusted destination.
     * That state is kept on the originating device and is never exported to a courier.
     */
    val recipientPublicKey: RescuePublicKey? = null,
    /** The public receipt verification key bound to the sender's selected shelter. */
    val receiptSigningPublicKey: RescuePublicKey? = null,
    /**
     * Durable Phase 5 consent state, the source of truth for whether periodic location updates are
     * permitted. It is set only by an explicit sender opt-in and mirrored to the public
     * [ActiveRescueSession.trackingMode]. Fresh requests default to false; tracking never starts
     * implicitly on creation or recovery.
     */
    val trackingEnabled: Boolean = false,
)

/** The database stores these binary fields separately and never stores [RescueSessionRecoveryPayload]. */
data class SealedRecoveryPayload(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
)

class RecoveryPayloadEncryptionException : IllegalStateException("Recovery payload could not be protected")
class RecoveryPayloadDecryptionException : IllegalStateException("Recovery payload could not be recovered")

interface RecoveryPayloadCipher {
    fun seal(payload: RescueSessionRecoveryPayload): SealedRecoveryPayload
    fun open(sealed: SealedRecoveryPayload): RescueSessionRecoveryPayload
}

/** Deliberately narrow seam for JVM tests; Android implementation never exports key material. */
fun interface SessionSecretKeyProvider {
    fun key(alias: String): SecretKey
}

/**
 * AES-GCM recovery cipher. A provider-generated random IV is stored beside ciphertext and is never
 * caller-reused. Authentication failures produce a generic fail-closed exception without logging
 * any plaintext, ciphertext, nonce, or key material.
 */
class AesGcmRecoveryPayloadCipher(
    private val keyProvider: SessionSecretKeyProvider,
    private val keyAlias: String = DEFAULT_RECOVERY_KEY_ALIAS,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
) : RecoveryPayloadCipher {
    init {
        require(keyAlias.isNotBlank())
    }

    override fun seal(payload: RescueSessionRecoveryPayload): SealedRecoveryPayload = try {
        require(payload.schemaVersion == RECOVERY_PAYLOAD_SCHEMA_VERSION)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, keyProvider.key(keyAlias))
        }
        val nonce = requireNotNull(cipher.iv).also { require(it.size == GCM_NONCE_BYTES) }.copyOf()
        val plaintext = json.encodeToString(payload).encodeToByteArray()
        SealedRecoveryPayload(ciphertext = cipher.doFinal(plaintext), nonce = nonce)
    } catch (_: Exception) {
        throw RecoveryPayloadEncryptionException()
    }

    override fun open(sealed: SealedRecoveryPayload): RescueSessionRecoveryPayload = try {
        require(sealed.nonce.size == GCM_NONCE_BYTES)
        // GCM always appends a 128-bit authentication tag.  Reject truncated records before
        // asking the provider to decrypt, without exposing the malformed bytes.
        require(sealed.ciphertext.size > GCM_TAG_BYTES)
        val plaintext = Cipher.getInstance(AES_GCM_TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, keyProvider.key(keyAlias), GCMParameterSpec(GCM_TAG_BITS, sealed.nonce))
            doFinal(sealed.ciphertext)
        }
        json.decodeFromString<RescueSessionRecoveryPayload>(plaintext.decodeToString()).also {
            require(it.schemaVersion == RECOVERY_PAYLOAD_SCHEMA_VERSION)
        }
    } catch (_: Exception) {
        throw RecoveryPayloadDecryptionException()
    }

    companion object {
        /** Separate from the SQLCipher passphrase alias. */
        const val DEFAULT_RECOVERY_KEY_ALIAS = "relay_rescue_session_recovery_v1"
    }
}

/** Android Keystore-backed provider. Keys are non-exportable and AES-GCM-only. */
class AndroidKeystoreSessionSecretKeyProvider : SessionSecretKeyProvider {
    override fun key(alias: String): SecretKey = synchronized(lock) {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        val lock = Any()
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
