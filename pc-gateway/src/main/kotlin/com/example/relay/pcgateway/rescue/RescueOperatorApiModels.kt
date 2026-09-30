package com.example.relay.pcgateway.rescue

import com.example.relay.rescue.RescuePayload
import kotlinx.serialization.Serializable

@Serializable
data class RescueOperatorListResponse(
    val generatedAtEpochMillis: Long,
    val municipality: String = "設定地域",
    val retentionDays: Int = 30,
    val items: List<RescueOperatorRequest>,
)

@Serializable
data class RescueOperatorRequest(
    val requestId: String,
    val requestVersion: Int,
    val urgency: String,
    val personCount: Int?,
    val conditions: List<String>,
    val supportNeeds: List<String>,
    val action: String,
    val injured: Boolean,
    val seriouslyInjured: Boolean,
    val mobilityImpaired: Boolean,
    val elderlyPresent: Boolean,
    val childrenPresent: Boolean,
    val pregnantPresent: Boolean,
    val medicalSupportRequired: Boolean,
    val trapped: Boolean,
    val fireOrCollapseRisk: Boolean,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val locationDescription: String,
    val locationCapturedAtEpochMillis: Long?,
    val freeText: String,
    val createdAtEpochMillis: Long,
    val receivedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val responseStatus: RescueResponseStatus,
    val assignedNodeId: String?,
    val statusUpdatedAtEpochMillis: Long,
    val uniqueCarrierCount: Int,
    /** PUERTA provenance is non-identifying metadata, independent of encrypted rescue content. */
    val sourceChannel: String? = null,
    val ingressAssurance: String? = null,
)

@Serializable
data class RescueStatusChangeRequest(
    val status: RescueResponseStatus,
    /** Deprecated client field. The server uses the authenticated staff account instead. */
    val operatorNodeId: String? = null,
    /** Revision observed by the operator; prevents an old screen from mutating a newer revision. */
    val expectedRequestVersion: Int? = null,
)

@Serializable
data class RescueStatusChangeResponse(
    val updated: Boolean,
    val status: RescueResponseStatus? = null,
    val assignedNodeId: String? = null,
    val reason: String? = null,
)

fun StoredRescueRequest.toOperatorRequest(): RescueOperatorRequest {
    val value: RescuePayload = payload
    return RescueOperatorRequest(
        requestId = key.requestId,
        requestVersion = key.requestVersion,
        urgency = value.urgency.name,
        personCount = value.personCount.takeIf { it > 0 },
        conditions = value.conditions.map { it.name }.sorted(),
        supportNeeds = value.supportNeeds.map { it.name }.sorted(),
        action = value.action.name,
        injured = value.injured,
        seriouslyInjured = value.seriouslyInjured,
        mobilityImpaired = value.mobilityImpaired,
        elderlyPresent = value.elderlyPresent,
        childrenPresent = value.childrenPresent,
        pregnantPresent = value.pregnantPresent,
        medicalSupportRequired = value.medicalSupportRequired,
        trapped = value.trapped,
        fireOrCollapseRisk = value.fireOrCollapseRisk,
        latitude = value.location?.latitude,
        longitude = value.location?.longitude,
        accuracyMeters = value.location?.accuracyMeters,
        locationDescription = value.location?.description.orEmpty(),
        locationCapturedAtEpochMillis = value.location?.capturedAtEpochMillis,
        freeText = value.freeText,
        createdAtEpochMillis = value.createdAtEpochMillis,
        receivedAtEpochMillis = receivedAtEpochMillis,
        expiresAtEpochMillis = value.expiresAtEpochMillis,
        responseStatus = responseStatus,
        assignedNodeId = assignedNodeId,
        statusUpdatedAtEpochMillis = statusUpdatedAtEpochMillis,
        uniqueCarrierCount = uniqueCarrierCount,
    )
}
