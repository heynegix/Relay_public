package com.example.relay.rescue.ble

import android.content.Context
import com.example.relay.rescue.EncryptedRescueEnvelope
import com.example.relay.rescue.ReceiptApplicationResult
import com.example.relay.rescue.RegionalShelterDirectoryResolver
import com.example.relay.rescue.ResolvedShelterKeys
import com.example.relay.rescue.RescueEnvelopeRepository
import com.example.relay.rescue.RescueRequestKey
import com.example.relay.rescue.RescueSubmissionStatus
import com.example.relay.rescue.RescueUrgency
import com.example.relay.rescue.ShelterReceiptStatus
import com.example.relay.rescue.SignedShelterManifest
import com.example.relay.rescue.SignedShelterReceipt
import com.example.relay.rescue.StoredRescueRecord
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed interface ShelterDeliveryState {
    data object Idle : ShelterDeliveryState
    data object Scanning : ShelterDeliveryState
    data class Delivering(val shelterId: String) : ShelterDeliveryState
    data class WaitingToRetry(val reason: String) : ShelterDeliveryState
}

private const val DELIVERY_PRIORITY_HIGH = 3
private const val DELIVERY_PRIORITY_MEDIUM = 2
private const val DELIVERY_PRIORITY_LOW = 1
private const val DELIVERY_PRIORITY_NONE = 0

/**
 * Automatic courier delivery. It never reads rescue plaintext and it never
 * consumes a mesh hop: only a verified PC receipt changes submission state.
 */
class ShelterDeliveryCoordinator(
    private val client: ShelterBleClient,
    private val repository: RescueEnvelopeRepository,
    private val directoryResolver: RegionalShelterDirectoryResolver,
    private val carrierId: String,
    private val deliveryIds: CourierDeliveryIdStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { encodeDefaults = true },
    // A 16 KiB envelope needs more than 1,600 acknowledged writes at the MTU-23 fallback.
    // Allow the PC bridge's 120s bounded reassembly window plus connect/result overhead.
    private val sessionDeadlineMillis: Long = 180_000,
    private val maxEnvelopeBytes: Int = MAX_ENVELOPE_BYTES,
    private val onRepositoryChanged: suspend () -> Unit = {},
    /** Lets sender-owned sessions mirror only a verified shelter receipt in the same DB transaction. */
    private val receiptApplier: (RescueRequestKey, SignedShelterReceipt, com.example.relay.rescue.RescuePublicKey) -> ReceiptApplicationResult = repository::applyReceipt,
) {
    private val mutex = Mutex()
    private var job: Job? = null
    private val _state = MutableStateFlow<ShelterDeliveryState>(ShelterDeliveryState.Idle)
    val state: StateFlow<ShelterDeliveryState> = _state
    private val retryNotBefore = mutableMapOf<RescueRequestKey, Long>()
    private var candidateInFlight: RescueRequestKey? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = ShelterDeliveryState.Scanning
            client.advertisements.collect { advertisement -> mutex.withLock { deliverTo(advertisement) } }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = ShelterDeliveryState.Idle
    }

    private suspend fun deliverTo(advertisement: ShelterAdvertisement) {
        candidateInFlight = null
        try {
            withTimeout(sessionDeadlineMillis) {
                client.connect(advertisement).use { deliverSession(advertisement.identity, it) }
            }
        } catch (_: TimeoutCancellationException) {
            candidateInFlight?.let { retryNotBefore[it] = clock() + RETRY_COOLDOWN_MILLIS }
            _state.value = ShelterDeliveryState.WaitingToRetry("BLE delivery timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Preserve the encrypted record and replay the same idempotency key next time.
            candidateInFlight?.let { retryNotBefore[it] = clock() + RETRY_COOLDOWN_MILLIS }
            _state.value = ShelterDeliveryState.WaitingToRetry("BLE shelter unavailable")
        } finally {
            candidateInFlight = null
        }
    }

    private suspend fun deliverSession(
        advertisedIdentity: ShelterBleIdentity?,
        session: ShelterBleSession,
    ) {
        val manifest = resolveTrustedManifest(advertisedIdentity, session) ?: return
        val candidate = selectCandidate(manifest.manifest.shelterId) ?: return
        candidateInFlight = candidate.key
        // Every attempt, including unchanged receipts and local validation failures, yields
        // to other requests before polling this request again.
        retryNotBefore[candidate.key] = clock() + RETRY_COOLDOWN_MILLIS
        val encoded = encodeCandidate(candidate) ?: return
        _state.value = ShelterDeliveryState.Delivering(manifest.manifest.shelterId)
        val keys = resolveEnvelopeKeys(manifest, candidate) ?: return
        val receipt = submitCandidate(session, candidate, encoded) ?: return
        applyShelterReceipt(candidate, receipt, keys)
    }

    private suspend fun resolveTrustedManifest(
        advertisedIdentity: ShelterBleIdentity?,
        session: ShelterBleSession,
    ): SignedShelterManifest? {
        // The bridge exposes the same compact identity in its advertisement and read
        // characteristic. Do not rely solely on a platform client to compare them: a fake,
        // replacement, or future client must fail closed here as well.
        val identity = session.readIdentity()
        if (advertisedIdentity == null || !advertisedIdentity.sameWireIdentity(identity)) {
            _state.value = ShelterDeliveryState.WaitingToRetry("BLE identity changed after advertisement")
            return null
        }
        val now = clock()
        val manifest = directoryResolver.resolveBeaconIdentity(identity.signedManifestFingerprint, now)
        if (manifest == null || !directoryResolver.verifyAdvertisedManifest(manifest, now)) {
            _state.value = ShelterDeliveryState.WaitingToRetry("untrusted shelter advertisement")
            return null
        }
        return manifest
    }

    private fun selectCandidate(shelterId: String): StoredRescueRecord? {
        val now = clock()
        return repository.all()
            .asSequence()
            .filter { record ->
                (retryNotBefore[record.key] ?: 0L) <= now &&
                record.state.submissionStatus in DELIVERABLE_STATUSES &&
                    record.envelope.destinationShelterId == shelterId &&
                    record.envelope.expiresAtEpochMillis > now
            }
            // Initial delivery always wins over receipt refreshes. Within the same class,
            // emergency requests win, then the oldest request gets a turn. This prevents a
            // permanently failing receipt refresh from starving all pending SOS envelopes.
            .sortedWith(
                compareByDescending<StoredRescueRecord> { it.state.submissionStatus.deliveryPriority() }
                    .thenByDescending { it.envelope.routingUrgency.deliveryPriority() }
                    .thenBy { it.envelope.createdAtEpochMillis }
                    .thenBy { it.envelope.requestId }
                    .thenBy { it.envelope.requestVersion },
            )
            .firstOrNull()
    }

    private fun encodeCandidate(candidate: StoredRescueRecord): ByteArray? {
        val encoded = json.encodeToString(EncryptedRescueEnvelope.serializer(), candidate.envelope).encodeToByteArray()
        if (encoded.size in 1..maxEnvelopeBytes) return encoded
        _state.value = ShelterDeliveryState.WaitingToRetry("encrypted envelope exceeds BLE limit")
        return null
    }

    private fun resolveEnvelopeKeys(
        manifest: SignedShelterManifest,
        candidate: StoredRescueRecord,
    ): ResolvedShelterKeys? {
        val keys = directoryResolver.resolveForEnvelope(
            manifest.regionId,
            manifest.manifest.shelterId,
            candidate.envelope.recipientKeyId,
            clock(),
        )
        if (keys == null) _state.value = ShelterDeliveryState.WaitingToRetry("shelter key does not match envelope")
        return keys
    }

    private fun RescueSubmissionStatus.deliveryPriority(): Int = when (this) {
        RescueSubmissionStatus.PENDING -> DELIVERY_PRIORITY_HIGH
        RescueSubmissionStatus.IN_TRANSIT -> DELIVERY_PRIORITY_MEDIUM
        RescueSubmissionStatus.SHELTER_STORED -> DELIVERY_PRIORITY_LOW
        else -> DELIVERY_PRIORITY_NONE
    }

    private fun RescueUrgency.deliveryPriority(): Int = when (this) {
        RescueUrgency.IMMEDIATE -> DELIVERY_PRIORITY_HIGH
        RescueUrgency.URGENT -> DELIVERY_PRIORITY_MEDIUM
        RescueUrgency.ROUTINE -> DELIVERY_PRIORITY_LOW
    }

    private suspend fun submitCandidate(
        session: ShelterBleSession,
        candidate: StoredRescueRecord,
        encoded: ByteArray,
    ): SignedShelterReceipt? = when (val result = session.submit(
        RescueBleSubmission(deliveryIds.idFor(candidate.key), carrierId, encoded),
    )) {
        is RescueBleSubmissionResult.Accepted -> result.receipt
        is RescueBleSubmissionResult.Duplicate -> result.receipt
        is RescueBleSubmissionResult.Rejected -> null.also {
            retryNotBefore[candidate.key] = clock() + RETRY_COOLDOWN_MILLIS
            _state.value = ShelterDeliveryState.WaitingToRetry("shelter rejected delivery")
        }
    }

    private suspend fun applyShelterReceipt(
        candidate: StoredRescueRecord,
        receipt: SignedShelterReceipt,
        keys: ResolvedShelterKeys,
    ) {
        when (receiptApplier(candidate.key, receipt, keys.receiptSigningPublicKey)) {
            ReceiptApplicationResult.APPLIED -> {
                onRepositoryChanged()
                finishReceipt(candidate.key, receipt)
            }
            ReceiptApplicationResult.ALREADY_APPLIED -> {
                finishReceipt(candidate.key, receipt)
            }
            else -> _state.value = ShelterDeliveryState.WaitingToRetry("invalid shelter receipt")
        }
    }

    private fun finishReceipt(key: RescueRequestKey, receipt: SignedShelterReceipt) {
        if (receipt.receipt.status.isTerminalDeliveryReceipt()) deliveryIds.remove(key)
        _state.value = ShelterDeliveryState.Scanning
    }

    private companion object {
        const val MAX_ENVELOPE_BYTES = 16 * 1024
        const val RETRY_COOLDOWN_MILLIS = 30_000L
        val DELIVERABLE_STATUSES = setOf(
            RescueSubmissionStatus.PENDING,
            RescueSubmissionStatus.IN_TRANSIT,
            RescueSubmissionStatus.SHELTER_STORED,
            RescueSubmissionStatus.SHELTER_ACCEPTED,
            RescueSubmissionStatus.SHELTER_RESPONDING,
        )
    }
}

internal fun ShelterReceiptStatus.isTerminalDeliveryReceipt(): Boolean = this in setOf(
    ShelterReceiptStatus.COMPLETED,
    ShelterReceiptStatus.CANCELLED,
    ShelterReceiptStatus.REJECTED,
)

/** Durable per-request idempotency key, replayed after a disconnect or process restart. */
interface CourierDeliveryIdStore {
    fun idFor(key: RescueRequestKey): String
    fun remove(key: RescueRequestKey)
}

class SharedPreferencesCourierDeliveryIdStore(context: Context) : CourierDeliveryIdStore {
    private val preferences = context.applicationContext.getSharedPreferences("relay_rescue_delivery_ids", Context.MODE_PRIVATE)
    override fun idFor(key: RescueRequestKey): String {
        val storageKey = "${key.requestId}:${key.requestVersion}"
        return preferences.getString(storageKey, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(storageKey, it).commit()
        }
    }
    override fun remove(key: RescueRequestKey) {
        preferences.edit().remove("${key.requestId}:${key.requestVersion}").apply()
    }
}
