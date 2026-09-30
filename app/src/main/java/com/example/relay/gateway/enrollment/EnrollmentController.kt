package com.example.relay.gateway.enrollment

import com.example.relay.gateway.GatewayEnrollmentCodec
import com.example.relay.gateway.GatewayEnrollmentImport
import com.example.relay.gateway.GatewayEnrollmentResult
import com.example.relay.gateway.GatewayEnrollmentToken
import com.example.relay.gateway.PersistentGatewayEnrollmentStore

/**
 * Presentation-layer state for the Gateway enrollment screen.
 *
 * Security invariants enforced by this ViewModel:
 * - QR content is NEVER logged or sent to analytics
 * - Fingerprint confirmation is required before enrollment
 * - Conflicts require explicit user action (rotation confirm)
 * - Invalid payloads are rejected without partial registration
 */
sealed interface EnrollmentUiState {
    /** Initial state: waiting for QR scan or text input. */
    data object Idle : EnrollmentUiState

    /** QR decoded and awaiting user confirmation before persisting. */
    data class PendingConfirmation(
        val token: GatewayEnrollmentToken,
        val formattedFingerprint: String,
        val isConflict: Boolean = false,
        val existingToken: GatewayEnrollmentToken? = null,
    ) : EnrollmentUiState

    /** Enrollment succeeded. */
    data class Success(val token: GatewayEnrollmentToken, val wasRotation: Boolean = false) : EnrollmentUiState

    /** Import failed with reason. */
    data class Error(val message: String) : EnrollmentUiState
}

/**
 * Gateway enrollment logic separated from Android ViewModel for testability.
 * The actual ViewModel wraps this with LiveData/StateFlow + Android lifecycle.
 *
 * This class intentionally does NOT auto-register on scan. The caller must:
 * 1. Call [processPayload] with scanned/pasted text
 * 2. Show [EnrollmentUiState.PendingConfirmation] to the user
 * 3. Call [confirmEnrollment] only after user explicitly agrees
 *
 * This separation ensures:
 * - No auto-TOFU from QR scan alone
 * - Fingerprint visible before commitment
 * - Conflict/rotation requires user decision
 */
class EnrollmentController(
    private val store: PersistentGatewayEnrollmentStore,
) {
    private var pendingToken: GatewayEnrollmentToken? = null

    /**
     * Process a scanned QR or pasted text. Returns the UI state to display.
     * Does NOT persist anything — only validates and prepares for confirmation.
     */
    fun processPayload(payload: String): EnrollmentUiState {
        if (payload.isBlank()) return EnrollmentUiState.Error("Empty input")
        if (payload.length > 600) return EnrollmentUiState.Error("Input too long")
        // Check for control characters (except whitespace already trimmed)
        if (payload.any { it.isISOControl() && !it.isWhitespace() }) {
            return EnrollmentUiState.Error("Invalid characters in input")
        }

        return when (val result = GatewayEnrollmentCodec.decodeQrPayload(payload)) {
            is GatewayEnrollmentResult.Rejected -> {
                EnrollmentUiState.Error("Invalid enrollment data: ${result.reason.name}")
            }
            is GatewayEnrollmentResult.Enrolled -> {
                val token = result.token
                pendingToken = token
                // Check for existing enrollment conflict
                val existing = store.enrolledTokens().firstOrNull { it.gatewayId == token.gatewayId }
                val isConflict = existing != null && existing != token
                EnrollmentUiState.PendingConfirmation(
                    token = token,
                    formattedFingerprint = GatewayEnrollmentCodec.formatManualFingerprint(
                        token.manifestFingerprint,
                    ),
                    isConflict = isConflict,
                    existingToken = if (isConflict) existing else null,
                )
            }
        }
    }

    /**
     * Confirm enrollment after user has verified the fingerprint.
     * [allowRotation] must only be true when user explicitly confirmed replacement.
     */
    fun confirmEnrollment(allowRotation: Boolean = false): EnrollmentUiState {
        val token = pendingToken ?: return EnrollmentUiState.Error("No pending enrollment")

        return when (val result = store.enroll(token, allowRotation)) {
            is GatewayEnrollmentImport.Added ->
                EnrollmentUiState.Success(result.token).also { pendingToken = null }
            is GatewayEnrollmentImport.Rotated ->
                EnrollmentUiState.Success(result.token, wasRotation = true).also { pendingToken = null }
            is GatewayEnrollmentImport.AlreadyEnrolled ->
                EnrollmentUiState.Success(result.token).also { pendingToken = null }
            is GatewayEnrollmentImport.Conflict -> {
                // Keep [pendingToken] so the conflict dialog's explicit "replace" action can call
                // confirmEnrollment(allowRotation = true) WITHOUT a re-scan. Clearing it here made
                // the second stage fail with "No pending enrollment", so a gateway key rotation
                // could never be confirmed through the UI.
                EnrollmentUiState.PendingConfirmation(
                    token = token,
                    formattedFingerprint = GatewayEnrollmentCodec.formatManualFingerprint(
                        token.manifestFingerprint,
                    ),
                    isConflict = true,
                    existingToken = result.existing,
                )
            }
            is GatewayEnrollmentImport.Rejected ->
                EnrollmentUiState.Error("Rejected: ${result.reason.name}").also { pendingToken = null }
        }
    }

    /** Cancel pending enrollment without persisting. */
    fun cancel() {
        pendingToken = null
    }

    /** List currently enrolled gateways. */
    fun enrolledGateways(): List<GatewayEnrollmentToken> = store.enrolledTokens()

    /** Remove an enrolled gateway. Returns true if removed. */
    fun removeGateway(gatewayId: String): Boolean = store.forget(gatewayId)
}
