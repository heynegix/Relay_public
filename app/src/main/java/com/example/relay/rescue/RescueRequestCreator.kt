package com.example.relay.rescue

import com.example.relay.rescue.RescueCryptography
import com.example.relay.rescue.RescueCondition
import com.example.relay.rescue.RescueLocation
import com.example.relay.rescue.RescuePayload
import com.example.relay.rescue.RescuePublicKey
import com.example.relay.rescue.RescueRequestAction
import com.example.relay.rescue.RescueSupportNeed
import com.example.relay.rescue.RescueUrgency
import kotlinx.serialization.Serializable

/** Plaintext exists only at this creation boundary and is never passed to the repository. */
@Serializable
data class RescueRequestDraft(
    val requestId: String,
    val requestVersion: Int = 1,
    val senderDeviceId: String,
    val destinationShelterId: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val urgency: RescueUrgency,
    val personCount: Int = 1,
    val conditions: Set<RescueCondition> = emptySet(),
    val action: RescueRequestAction = RescueRequestAction.ACTIVE,
    val injured: Boolean = false,
    val seriouslyInjured: Boolean = false,
    val mobilityImpaired: Boolean = false,
    val elderlyPresent: Boolean = false,
    val childrenPresent: Boolean = false,
    val pregnantPresent: Boolean = false,
    val medicalSupportRequired: Boolean = false,
    val trapped: Boolean = false,
    val fireOrCollapseRisk: Boolean = false,
    val supportNeeds: Set<RescueSupportNeed> = emptySet(),
    val location: RescueLocation? = null,
    val freeText: String = "",
)

sealed interface RescueCreationResult {
    data class Stored(val record: StoredRescueRecord, val pruned: List<RescueRequestKey>) : RescueCreationResult
    data class NotStored(val reason: RescueStoreRejection) : RescueCreationResult
}

class RescueRequestCreator(
    private val repository: RescueEnvelopeRepository,
    private val envelopeAuthorizer: (EncryptedRescueEnvelope) -> EncryptedRescueEnvelope = { it },
) {
    fun create(
        draft: RescueRequestDraft,
        shelterPublicKey: RescuePublicKey,
        envelopeId: String,
        maxHopCount: Int = 8,
    ): RescueCreationResult {
        val payload = draft.toPayload()
        val unsignedEnvelope = RescueCryptography.encrypt(
            payload = payload,
            recipientPublicKey = shelterPublicKey,
            envelopeId = envelopeId,
            maxHopCount = maxHopCount,
        )
        val envelope = envelopeAuthorizer(unsignedEnvelope)
        return when (val result = repository.store(envelope, draft.createdAtEpochMillis)) {
            is RescueStoreResult.Stored -> RescueCreationResult.Stored(result.record, result.pruned)
            is RescueStoreResult.Rejected -> RescueCreationResult.NotStored(result.reason)
        }
    }
}

internal fun RescueRequestDraft.toPayload(): RescuePayload = RescuePayload(
    requestId = requestId,
    requestVersion = requestVersion,
    senderDeviceId = senderDeviceId,
    destinationShelterId = destinationShelterId,
    createdAtEpochMillis = createdAtEpochMillis,
    expiresAtEpochMillis = expiresAtEpochMillis,
    urgency = urgency,
    personCount = personCount,
    conditions = conditions,
    action = action,
    injured = injured,
    seriouslyInjured = seriouslyInjured,
    mobilityImpaired = mobilityImpaired,
    elderlyPresent = elderlyPresent,
    childrenPresent = childrenPresent,
    pregnantPresent = pregnantPresent,
    medicalSupportRequired = medicalSupportRequired,
    trapped = trapped,
    fireOrCollapseRisk = fireOrCollapseRisk,
    supportNeeds = supportNeeds,
    location = location,
    freeText = freeText,
)
