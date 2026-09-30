package com.example.relay.ui.enrollment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.relay.gateway.GatewayEnrollmentToken
import com.example.relay.gateway.enrollment.EnrollmentController
import com.example.relay.gateway.enrollment.EnrollmentUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Screen within the enrollment flow. Managed as local state so process recreation
 * restores to the list (safe default) rather than a mid-scan state.
 */
enum class EnrollmentFlowScreen {
    /** Enrolled gateway list with "add" button. */
    GATEWAY_LIST,
    /** QR scan or paste input. */
    INPUT,
}

/**
 * Composite UI state for the enrollment flow.
 * [enrollmentState] mirrors [EnrollmentController]'s output for confirmation/error.
 */
data class EnrollmentScreenState(
    val screen: EnrollmentFlowScreen = EnrollmentFlowScreen.GATEWAY_LIST,
    val enrollmentState: EnrollmentUiState = EnrollmentUiState.Idle,
    val enrolledGateways: List<GatewayEnrollmentToken> = emptyList(),
    /** Prevents rapid duplicate scans from opening multiple dialogs. */
    val lastProcessedPayload: String? = null,
    /** Whether the camera permission was denied and we should show paste-only mode. */
    val cameraPermissionDenied: Boolean = false,
    /** Transient success/error message for snackbar. */
    val userMessage: String? = null,
)

/**
 * ViewModel for the Gateway enrollment flow. Wraps [EnrollmentController] with StateFlow
 * and Android lifecycle awareness. Process recreation drops back to list (safe).
 *
 * Security: QR content is NEVER logged. Fingerprints are only shown in formatted form.
 */
class EnrollmentViewModel(
    private val controller: EnrollmentController,
) : ViewModel() {

    private val _state = MutableStateFlow(EnrollmentScreenState(
        enrolledGateways = controller.enrolledGateways(),
    ))
    val state: StateFlow<EnrollmentScreenState> = _state.asStateFlow()

    fun navigateToInput() {
        _state.update { it.copy(
            screen = EnrollmentFlowScreen.INPUT,
            enrollmentState = EnrollmentUiState.Idle,
            lastProcessedPayload = null,
        ) }
    }

    fun navigateToList() {
        controller.cancel()
        _state.update { it.copy(
            screen = EnrollmentFlowScreen.GATEWAY_LIST,
            enrollmentState = EnrollmentUiState.Idle,
            enrolledGateways = controller.enrolledGateways(),
            lastProcessedPayload = null,
            userMessage = null,
        ) }
    }

    /**
     * Process a scanned QR or pasted text. Guards against duplicate processing of the
     * same payload (rapid consecutive scans).
     */
    fun processPayload(payload: String) {
        val trimmed = payload.trim()
        if (trimmed.isBlank()) return
        // Prevent duplicate processing of the same scan
        if (trimmed == _state.value.lastProcessedPayload &&
            _state.value.enrollmentState is EnrollmentUiState.PendingConfirmation
        ) return

        viewModelScope.launch {
            val result = controller.processPayload(trimmed)
            _state.update { it.copy(
                enrollmentState = result,
                lastProcessedPayload = trimmed,
            ) }
        }
    }

    /**
     * Confirm enrollment after user has verified the fingerprint.
     * [allowRotation] must only be true when user explicitly confirmed replacement.
     */
    fun confirmEnrollment(allowRotation: Boolean = false) {
        viewModelScope.launch {
            val result = controller.confirmEnrollment(allowRotation)
            when (result) {
                is EnrollmentUiState.Success -> {
                    _state.update { it.copy(
                        enrollmentState = result,
                        enrolledGateways = controller.enrolledGateways(),
                        userMessage = if (result.wasRotation) "Gateway更新完了" else "Gateway登録完了",
                    ) }
                }
                else -> {
                    _state.update { it.copy(enrollmentState = result) }
                }
            }
        }
    }

    /** Cancel pending enrollment without persisting. */
    fun cancelEnrollment() {
        controller.cancel()
        _state.update { it.copy(
            enrollmentState = EnrollmentUiState.Idle,
            lastProcessedPayload = null,
        ) }
    }

    /** Remove an enrolled gateway. */
    fun removeGateway(gatewayId: String) {
        controller.removeGateway(gatewayId)
        _state.update { it.copy(
            enrolledGateways = controller.enrolledGateways(),
            userMessage = "Gateway登録を解除しました",
        ) }
    }

    /** Record that camera permission was denied. */
    fun onCameraPermissionDenied() {
        _state.update { it.copy(cameraPermissionDenied = true) }
    }

    /** Clear the transient user message. */
    fun clearMessage() {
        _state.update { it.copy(userMessage = null) }
    }

    /** Return to idle after viewing a success state. */
    fun acknowledgeSuccess() {
        _state.update { it.copy(
            screen = EnrollmentFlowScreen.GATEWAY_LIST,
            enrollmentState = EnrollmentUiState.Idle,
            enrolledGateways = controller.enrolledGateways(),
            lastProcessedPayload = null,
        ) }
    }
}
