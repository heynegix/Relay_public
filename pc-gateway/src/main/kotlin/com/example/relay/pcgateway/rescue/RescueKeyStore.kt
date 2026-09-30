package com.example.relay.pcgateway.rescue

import com.example.relay.pcgateway.GatewayKeyProtection
import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueKeyAlgorithm
import com.example.relay.rescue.RescuePayload
import com.example.relay.rescue.RescuePrivateKey
import com.example.relay.rescue.RescuePublicKey
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.RescueValidationResult
import com.example.relay.rescue.ShelterPublicKeyManifest
import com.example.relay.rescue.ShelterReceiptStatus
import com.example.relay.rescue.UnsignedShelterReceipt
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.util.EnumSet
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class RescueGatewayKeys(
    val manifest: ShelterPublicKeyManifest,
    val recipientPrivateKey: RescuePrivateKey,
    val receiptSigningPrivateKey: RescuePrivateKey,
)

/** Persistent PC-only private key store. Its contents must never be served or logged. */
class RescueKeyStore(
    private val path: Path,
    private val shelterId: String,
    private val clock: RescueClock = RescueClock(System::currentTimeMillis),
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
    private val protection: GatewayKeyProtection = GatewayKeyProtection.FILE_PERMISSIONS,
    private val dpapiAvailable: Boolean = WindowsDpapi.isSupported,
) {
    init {
        require(isIdentifier(shelterId)) { "invalid shelter id" }
        // Fail closed: a DPAPI request on a platform without DPAPI must stop startup instead of
        // silently keeping the private keys in a plaintext file.
        require(!(protection == GatewayKeyProtection.DPAPI && !dpapiAvailable)) {
            "RELAY_KEY_PROTECTION=dpapi requires Windows DPAPI; refusing to fall back to a plaintext key file"
        }
    }

    @Synchronized
    fun loadOrCreate(): RescueGatewayKeys {
        val now = clock.nowEpochMillis()
        val keys = if (Files.exists(path)) load(now) else create(now)
        validateKeySet(keys, now)
        return keys
    }

    private fun load(now: Long): RescueGatewayKeys {
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "rescue key path is not a regular file"
        }
        verifyOwnerOnly(path)
        val size = Files.size(path)
        require(size in 1..MAX_PROTECTED_FILE_BYTES) { "invalid rescue key file size" }
        val raw = Files.readString(path, Charsets.UTF_8)
        val protectedFile = raw.startsWith(DPAPI_HEADER)
        if (protectedFile) {
            // Fail closed in both directions: a protected file never opens outside DPAPI mode.
            require(protection == GatewayKeyProtection.DPAPI) {
                "rescue key file is DPAPI-protected; set RELAY_KEY_PROTECTION=dpapi to open it"
            }
        }
        val plaintext = if (protectedFile) {
            val blob = Base64.getDecoder().decode(raw.removePrefix(DPAPI_HEADER).trim())
            WindowsDpapi.unprotect(blob, DPAPI_ENTROPY).toString(Charsets.UTF_8)
        } else {
            raw
        }
        require(plaintext.encodeToByteArray().size <= MAX_KEY_FILE_BYTES) { "invalid rescue key file size" }
        val persisted = json.decodeFromString<PersistedRescueKeys>(plaintext)
        require(persisted.fileVersion == KEY_FILE_VERSION) { "unsupported rescue key file" }
        require(persisted.manifest.shelterId == shelterId) { "rescue key shelter mismatch" }
        require(persisted.manifest.validate(now) == RescueValidationResult.Valid) { "rescue key manifest is invalid" }
        val keys = RescueGatewayKeys(
            manifest = persisted.manifest,
            recipientPrivateKey = persisted.recipientPrivateKey.import(),
            receiptSigningPrivateKey = persisted.receiptSigningPrivateKey.import(),
        )
        if (!protectedFile && protection == GatewayKeyProtection.DPAPI) {
            // One-way migration: rewrite the plaintext file DPAPI-protected and prove it on disk.
            persist(keys)
            require(Files.readString(path, Charsets.UTF_8).startsWith(DPAPI_HEADER)) {
                "DPAPI migration failed to protect the rescue key file"
            }
        }
        return keys
    }

    private fun create(now: Long): RescueGatewayKeys {
        require(now > CLOCK_SKEW_ALLOWANCE_MS) { "invalid system clock" }
        val recipient = RescueCryptography.generateRecipientKeyPair()
        val signer = RescueCryptography.generateShelterSigningKeyPair()
        val manifest = ShelterPublicKeyManifest(
            shelterId = shelterId,
            recipientPublicKey = recipient.publicKey,
            receiptSigningPublicKey = signer.publicKey,
            validFromEpochMillis = now - CLOCK_SKEW_ALLOWANCE_MS,
            validUntilEpochMillis = now + KEY_VALIDITY_MS,
        )
        val keys = RescueGatewayKeys(manifest, recipient.privateKey, signer.privateKey)
        validateKeySet(keys, now)
        persist(keys)
        return keys
    }

    private fun persist(keys: RescueGatewayKeys) {
        val parent = path.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, ".rescue-keys-", ".tmp")
        try {
            restrictOwnerOnly(temp)
            val encoded = json.encodeToString(
                PersistedRescueKeys(
                    manifest = keys.manifest,
                    recipientPrivateKey = PersistedPrivateKey.from(keys.recipientPrivateKey),
                    receiptSigningPrivateKey = PersistedPrivateKey.from(keys.receiptSigningPrivateKey),
                ),
            ).encodeToByteArray()
            require(encoded.size <= MAX_KEY_FILE_BYTES)
            val stored = if (protection == GatewayKeyProtection.DPAPI) {
                (DPAPI_HEADER + Base64.getEncoder().encodeToString(WindowsDpapi.protect(encoded, DPAPI_ENTROPY)))
                    .encodeToByteArray()
            } else {
                encoded
            }
            Files.write(temp, stored)
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, path)
            }
            restrictOwnerOnly(path)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun validateKeySet(keys: RescueGatewayKeys, now: Long) {
        require(keys.manifest.validate(now) == RescueValidationResult.Valid)
        require(keys.manifest.shelterId == shelterId)
        require(keys.recipientPrivateKey.algorithm == RescueKeyAlgorithm.RSA_OAEP_SHA256)
        require(keys.receiptSigningPrivateKey.algorithm == RescueKeyAlgorithm.ECDSA_P256_SHA256)
        require(keys.recipientPrivateKey.keyId == keys.manifest.recipientPublicKey.keyId)
        require(keys.receiptSigningPrivateKey.keyId == keys.manifest.receiptSigningPublicKey.keyId)
        verifyPublicKeyIdentity(keys.manifest.recipientPublicKey)
        verifyPublicKeyIdentity(keys.manifest.receiptSigningPublicKey)
        selfTest(keys)
    }

    private fun selfTest(keys: RescueGatewayKeys) {
        val payload = RescuePayload(
            requestId = "key-self-test",
            senderDeviceId = "key-self-test",
            destinationShelterId = shelterId,
            createdAtEpochMillis = 1,
            expiresAtEpochMillis = 2,
            urgency = RescueUrgency.ROUTINE,
        )
        val envelope = RescueCryptography.encrypt(payload, keys.manifest.recipientPublicKey, "key-self-test")
        require(RescueCryptography.decrypt(envelope, keys.recipientPrivateKey) == payload) { "recipient key pair mismatch" }
        val receipt = RescueCryptography.signReceipt(
            UnsignedShelterReceipt(
                receiptId = "key-self-test",
                envelopeId = envelope.envelopeId,
                requestId = envelope.requestId,
                requestVersion = envelope.requestVersion,
                ciphertextSha256Hex = envelope.ciphertextSha256Hex,
                shelterId = shelterId,
                receivedAtEpochMillis = 1,
                status = ShelterReceiptStatus.STORED,
            ),
            keys.receiptSigningPrivateKey,
        )
        require(RescueCryptography.verifyReceipt(receipt, keys.manifest.receiptSigningPublicKey)) {
            "receipt signing key pair mismatch"
        }
    }

    private fun verifyPublicKeyIdentity(key: RescuePublicKey) {
        val decoded = Base64.getDecoder().decode(key.encodedBase64)
        require(RescueCryptography.sha256Hex(decoded) == key.keyId) { "public key id mismatch" }
        RescueCryptography.importPublicKey(key.keyId, key.algorithm, key.encodedBase64)
    }

    private fun restrictOwnerOnly(target: Path) {
        val posix = runCatching { Files.getPosixFilePermissions(target) }.getOrNull()
        if (posix != null) {
            Files.setPosixFilePermissions(
                target,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
            return
        }
        val aclView = Files.getFileAttributeView(target, AclFileAttributeView::class.java)
            ?: error("filesystem cannot enforce owner-only rescue key permissions")
        val owner = Files.getOwner(target)
        val ownerOnly = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(owner)
            .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
            .build()
        aclView.acl = listOf(ownerOnly)
    }

    /**
     * Existing files are checked before importing Base64 private key material.  We do not claim
     * DPAPI/HSM protection here: this is a fail-closed filesystem permission boundary only.
     */
    private fun verifyOwnerOnly(target: Path) {
        val posix = runCatching { Files.getPosixFilePermissions(target) }.getOrNull()
        if (posix != null) {
            val permitted = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            require(posix.all { it in permitted } && PosixFilePermission.OWNER_READ in posix) {
                "rescue key file permissions are not owner-only"
            }
            return
        }
        val aclView = Files.getFileAttributeView(target, AclFileAttributeView::class.java)
            ?: error("filesystem cannot verify owner-only rescue key permissions")
        val owner = Files.getOwner(target)
        val readableByOther = aclView.acl.any { entry ->
            entry.type() == AclEntryType.ALLOW && entry.principal() != owner &&
                entry.permissions().any {
                    it in setOf(
                        AclEntryPermission.READ_DATA,
                        AclEntryPermission.WRITE_DATA,
                        AclEntryPermission.APPEND_DATA,
                        AclEntryPermission.READ_ACL,
                        AclEntryPermission.WRITE_ACL,
                        AclEntryPermission.WRITE_OWNER,
                    )
                }
        }
        require(!readableByOther) { "rescue key ACL is not owner-only" }
    }

    private fun PersistedPrivateKey.import(): RescuePrivateKey =
        RescueCryptography.importPrivateKey(keyId, algorithm, encodedBase64)

    @Serializable
    private data class PersistedRescueKeys(
        val fileVersion: Int = KEY_FILE_VERSION,
        val manifest: ShelterPublicKeyManifest,
        val recipientPrivateKey: PersistedPrivateKey,
        val receiptSigningPrivateKey: PersistedPrivateKey,
    )

    @Serializable
    private data class PersistedPrivateKey(
        val keyId: String,
        val algorithm: RescueKeyAlgorithm,
        val encodedBase64: String,
    ) {
        companion object {
            fun from(key: RescuePrivateKey) = PersistedPrivateKey(key.keyId, key.algorithm, key.encodedBase64)
        }
    }

    private companion object {
        const val KEY_FILE_VERSION = 1
        const val MAX_KEY_FILE_BYTES = 32L * 1024
        const val MAX_PROTECTED_FILE_BYTES = 64L * 1024
        const val CLOCK_SKEW_ALLOWANCE_MS = 24L * 60 * 60 * 1_000
        const val KEY_VALIDITY_MS = 5L * 366 * 24 * 60 * 60 * 1_000

        /** File marker for a DPAPI-wrapped key file; the blob itself carries the protection. */
        const val DPAPI_HEADER = "RELAY-DPAPI-KEYS-V1\n" // gitleaks:allow -- public file-format marker

        /** Fixed application entropy for domain separation only; deliberately not a secret. */
        val DPAPI_ENTROPY: ByteArray = "relay-rescue-key-store-v1".encodeToByteArray()

        fun isIdentifier(value: String): Boolean = value.length in 1..128 &&
            value.all { it.isLetterOrDigit() || it in "-_.:" }
    }
}
