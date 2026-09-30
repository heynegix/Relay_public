package com.example.relay.rescue

import android.content.Context
import android.util.Base64
import com.example.relay.BuildConfig
import java.security.MessageDigest

data class ShelterPublicKeys(
    val shelterId: String,
    val recipientKey: RescuePublicKey,
    val receiptSigningKey: RescuePublicKey,
    val manifestFingerprint: String = "",
    val generation: Int = 1,
)

fun interface ShelterPublicKeyProvider {
    fun load(): ShelterPublicKeys?
}

/**
 * A sink that persists a shelter manifest only after its independently obtained fingerprint is
 * confirmed, and can reload the verified public material. Abstracting the concrete keystore lets the
 * enrollment orchestration ([ShelterManifestEnrollment]) be verified without an Android keystore.
 */
interface VerifiedManifestStore : ShelterPublicKeyProvider {
    fun saveVerifiedManifest(manifest: ShelterPublicKeyManifest, expectedFingerprint: String)
}

/** Stores public material only. A courier/member installation never persists shelter private keys. */
class RescueShelterKeyStore(
    context: Context,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val allowDevelopmentEnrollment: Boolean = BuildConfig.DEBUG && BuildConfig.ALLOW_HTTP_GATEWAY,
) : VerifiedManifestStore {
    private val preferences = context.getSharedPreferences("relay_rescue_shelter_keys", Context.MODE_PRIVATE)

    override fun load(): ShelterPublicKeys? = runCatching {
        val enrollmentMode = preferences.getString(ENROLLMENT_MODE, PROVISIONED_ENROLLMENT)
            ?: return null
        check(enrollmentMode == PROVISIONED_ENROLLMENT || enrollmentMode == DEVELOPMENT_ENROLLMENT)
        // A key that was pinned by a debug build must never become trusted merely because a
        // release build is installed over that same app data directory.
        check(
            enrollmentMode != DEVELOPMENT_ENROLLMENT ||
                allowDevelopmentEnrollment,
        )
        val shelterId = preferences.getString("shelter_id", null)?.takeIf(String::isNotBlank) ?: return null
        val recipientKey = RescueCryptography.importPublicKey(
            preferences.getString("recipient_key_id", null) ?: return null,
            RescueKeyAlgorithm.RSA_OAEP_SHA256,
            preferences.getString("recipient_key", null) ?: return null,
        )
        val signingKey = RescueCryptography.importPublicKey(
            preferences.getString("signing_key_id", null) ?: return null,
            RescueKeyAlgorithm.ECDSA_P256_SHA256,
            preferences.getString("signing_key", null) ?: return null,
        )
        verifyPublicKeyIdentity(recipientKey)
        verifyPublicKeyIdentity(signingKey)
        val manifest = ShelterPublicKeyManifest(
            shelterId = shelterId,
            recipientPublicKey = recipientKey,
            receiptSigningPublicKey = signingKey,
            validFromEpochMillis = preferences.getLong("valid_from", 0),
            validUntilEpochMillis = preferences.getLong("valid_until", 0),
            generation = preferences.getInt("generation", 0),
        )
        check(manifest.validate(nowEpochMillis()) == RescueValidationResult.Valid)
        val storedFingerprint = preferences.getString("manifest_fingerprint", null) ?: return null
        check(constantTimeEquals(storedFingerprint, manifest.fingerprint()))
        ShelterPublicKeys(shelterId, recipientKey, signingKey, storedFingerprint, manifest.generation)
    }.getOrNull()

    /** Saves only after an independently obtained fingerprint has been confirmed by the operator/user. */
    override fun saveVerifiedManifest(manifest: ShelterPublicKeyManifest, expectedFingerprint: String) {
        saveManifest(manifest, expectedFingerprint, PROVISIONED_ENROLLMENT)
    }

    /**
     * Pins a locally discovered manifest only for an explicitly debuggable development build.
     * This marker makes the key unusable in a subsequently installed production/pilot build.
     */
    fun saveDevelopmentManifest(manifest: ShelterPublicKeyManifest) {
        check(allowDevelopmentEnrollment) {
            "development manifest enrollment is disabled in this build"
        }
        saveManifest(manifest, manifest.fingerprint(), DEVELOPMENT_ENROLLMENT)
    }

    private fun saveManifest(
        manifest: ShelterPublicKeyManifest,
        expectedFingerprint: String,
        enrollmentMode: String,
    ) {
        require(manifest.validate(nowEpochMillis()) == RescueValidationResult.Valid)
        require(expectedFingerprint.length == 64 && expectedFingerprint.all { it in '0'..'9' || it in 'a'..'f' })
        require(constantTimeEquals(expectedFingerprint, manifest.fingerprint())) { "manifest fingerprint mismatch" }
        verifyPublicKeyIdentity(manifest.recipientPublicKey)
        verifyPublicKeyIdentity(manifest.receiptSigningPublicKey)
        preferences.edit()
            .putString("shelter_id", manifest.shelterId)
            .putString("recipient_key_id", manifest.recipientPublicKey.keyId)
            .putString("recipient_key", manifest.recipientPublicKey.encodedBase64)
            .putString("signing_key_id", manifest.receiptSigningPublicKey.keyId)
            .putString("signing_key", manifest.receiptSigningPublicKey.encodedBase64)
            .putLong("valid_from", manifest.validFromEpochMillis)
            .putLong("valid_until", manifest.validUntilEpochMillis)
            .putInt("generation", manifest.generation)
            .putString("manifest_fingerprint", manifest.fingerprint())
            .putString(ENROLLMENT_MODE, enrollmentMode)
            .apply()
    }

    private fun verifyPublicKeyIdentity(key: RescuePublicKey) {
        val decoded = Base64.decode(key.encodedBase64, Base64.NO_WRAP)
        require(RescueCryptography.sha256Hex(decoded) == key.keyId) { "public key id mismatch" }
        RescueCryptography.importPublicKey(key.keyId, key.algorithm, key.encodedBase64)
    }

    private fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.encodeToByteArray(), right.encodeToByteArray())

    private companion object {
        const val ENROLLMENT_MODE = "enrollment_mode"
        const val PROVISIONED_ENROLLMENT = "provisioned"
        const val DEVELOPMENT_ENROLLMENT = "development"
    }
}
