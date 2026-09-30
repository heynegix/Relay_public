package com.example.relay.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.relay.data.repository.RoomMessageRepository
import com.example.relay.domain.MessagePriority
import com.example.relay.domain.CreateSafetyMessageUseCase
import com.example.relay.domain.CreateSupplyMessageUseCase
import com.example.relay.domain.ReportSigner
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.LegacyMessageCreationException
import com.example.relay.domain.Clock
import com.example.relay.domain.RelayMessage
import com.example.relay.domain.SafetyState
import com.example.relay.domain.SupplyKind
import com.example.relay.domain.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import com.example.relay.domain.DeliveryPresentation
import com.example.relay.domain.OperatingMode
import com.example.relay.domain.DeviceRole
import com.example.relay.domain.DeviceRoleStore
import com.example.relay.runtime.RelayCommunicationRuntime
import com.example.relay.runtime.RuntimePeer
import com.example.relay.gateway.GatewayCredentialStore
import com.example.relay.gateway.GatewaySettings
import com.example.relay.gateway.GatewaySettingsStore
import com.example.relay.gateway.GatewaySyncEngine
import com.example.relay.gateway.GatewaySyncResult
import com.example.relay.location.LocationProvider
import com.example.relay.location.resolveReportLocation
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Primary user flow only. Operator/debug enums retained for navigation demotion (map to HOME). */
enum class RelayScreen { HOME, RESCUE, SAFETY_FORM, SUPPLY_FORM, REGIONAL, SETTINGS, GATEWAY_ENROLLMENT }

data class RelayUiState(
    val screen: RelayScreen = RelayScreen.HOME,
    val transportRunning: Boolean = false,
    val discoveredPeers: Int = 0,
    val connectedPeers: Int = 0,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    val mode: OperatingMode = OperatingMode.NORMAL,
    val role: DeviceRole = DeviceRole.MEMBER,
    val advertising: Boolean = false,
    val discovering: Boolean = false,
    val transportName: String = "未設定",
    val peers: Map<String, RuntimePeer> = emptyMap(),
    val debugEvents: List<String> = emptyList(),
    /** Last PC Gateway sync outcome (from settings store; public or authenticated path). */
    val gatewayLastResult: String? = null,
    val gatewayDiscoveredIp: String? = null,
    val gatewayDiscoveryResult: String? = null,
    val gatewayDeliveryResult: String? = null,
    /** Last internet priority-pull summary for home status (optional). */
    val internetSyncLabel: String? = null,
)

internal sealed interface MessageCreationUiResult {
    data object Success : MessageCreationUiResult
    data class Failure(val reason: String) : MessageCreationUiResult
}

internal suspend fun executeMessageCreation(create: suspend () -> Unit): MessageCreationUiResult = try {
    create()
    MessageCreationUiResult.Success
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    val reason = when (failure) {
        is LegacyMessageCreationException.InvalidMessage -> "入力内容を確認してください"
        is LegacyMessageCreationException.StorageRejected -> "保存容量がいっぱいです"
        is LegacyMessageCreationException.DuplicateMessageId,
        is LegacyMessageCreationException.MessageIdCollision -> "情報を保存できませんでした。もう一度お試しください"
        else -> "情報を保存できませんでした"
    }
    MessageCreationUiResult.Failure(reason)
}

internal fun RelayUiState.withMessageCreationResult(result: MessageCreationUiResult): RelayUiState = when (result) {
    MessageCreationUiResult.Success -> copy(screen = RelayScreen.HOME, lastError = null)
    is MessageCreationUiResult.Failure -> copy(lastError = result.reason.take(160))
}

class RelayViewModel(
    private val repository: RoomMessageRepository,
    private val deviceId: String,
    private val deviceRoleStore: DeviceRoleStore,
    private val communicationRuntime: RelayCommunicationRuntime,
    private val gatewaySettingsStore: GatewaySettingsStore,
    private val gatewayCredentialStore: GatewayCredentialStore,
    private val gatewaySyncEngine: GatewaySyncEngine,
    private val clock: Clock = SystemClock,
    private val regionalMessageTicks: Flow<Unit> = regionalMessageExpiryTicker(),
    private val locationProvider: LocationProvider? = null,
    private val reportSigner: ReportSigner? = null,
) : ViewModel() {
    private val policy = MessagePolicy(clock)
    private val createSafetyMessage = CreateSafetyMessageUseCase(repository, policy, clock, deviceId, reportSigner = reportSigner)
    private val createSupplyMessage = CreateSupplyMessageUseCase(repository, policy, clock, deviceId, reportSigner = reportSigner)
    private val _uiState = MutableStateFlow(RelayUiState(role = deviceRoleStore.load()))
    private val _gatewaySettings = MutableStateFlow(gatewaySettingsStore.load())
    val gatewaySettings: StateFlow<GatewaySettings> = _gatewaySettings.asStateFlow()
    private val _gatewayResult = MutableStateFlow<GatewaySyncResult?>(null)
    val gatewayResult: StateFlow<GatewaySyncResult?> = _gatewayResult.asStateFlow()
    val uiState: StateFlow<RelayUiState> = _uiState.asStateFlow()
    val regionalItems: StateFlow<List<RegionalMessageItem>> = regionalMessageItemsFlow(
        repository.observeAll(),
        regionalMessageTicks,
        policy,
    )
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val messages: StateFlow<List<RelayMessage>> = regionalItems
        .map { items -> items.map(RegionalMessageItem::report) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val deliveryStates: StateFlow<Map<String, DeliveryPresentation>> = combine(
        regionalItems,
        repository.observeReceipts(),
    ) { currentItems, receipts ->
        currentItems.associate { item ->
            val message = item.report
            message.messageId to repository.deliveryPresentation(receipts.filter { it.messageId == message.messageId })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        viewModelScope.launch {
            communicationRuntime.state.collect { runtime ->
                val current = _uiState.value
                val gateway = gatewaySettingsStore.load()
                _gatewaySettings.value = gateway
                _uiState.value = current.copy(
                    transportRunning = runtime.running,
                    discoveredPeers = runtime.peers.values.count { it.status != com.example.relay.runtime.RuntimePeerStatus.CONNECTED },
                    connectedPeers = runtime.transport.connectedPeerIds.size,
                    lastSyncAt = runtime.lastSyncAt,
                    lastError = runtime.lastError,
                    mode = if (runtime.running) runtime.mode else current.mode,
                    role = if (runtime.running) runtime.role else current.role,
                    advertising = runtime.transport.advertising,
                    discovering = runtime.transport.discovering,
                    transportName = runtime.transportName,
                    peers = runtime.peers,
                    debugEvents = runtime.debugEvents,
                    gatewayLastResult = gateway.lastSyncResult,
                    gatewayDiscoveredIp = gateway.lastDiscoveredGatewayIp,
                    gatewayDiscoveryResult = gateway.lastDiscoveryResult,
                    gatewayDeliveryResult = gateway.lastDeliveryResult,
                )
            }
        }
        // Gateway sync runs in the communication service; refresh status for the home card.
        viewModelScope.launch {
            while (isActive) {
                delay(3_000)
                val gateway = gatewaySettingsStore.load()
                _gatewaySettings.value = gateway
                if (_uiState.value.gatewayLastResult != gateway.lastSyncResult) {
                    _uiState.value = _uiState.value.copy(
                        gatewayLastResult = gateway.lastSyncResult,
                        gatewayDiscoveredIp = gateway.lastDiscoveredGatewayIp,
                        gatewayDiscoveryResult = gateway.lastDiscoveryResult,
                        gatewayDeliveryResult = gateway.lastDeliveryResult,
                    )
                }
            }
        }
    }

    fun navigate(screen: RelayScreen) {
        val allowed = when (screen) {
            RelayScreen.HOME,
            RelayScreen.RESCUE,
            RelayScreen.SAFETY_FORM,
            RelayScreen.SUPPLY_FORM,
            RelayScreen.REGIONAL,
            RelayScreen.SETTINGS,
            RelayScreen.GATEWAY_ENROLLMENT,
            -> screen
        }
        _uiState.value = _uiState.value.copy(screen = allowed)
    }

    fun reportError(reason: String) { _uiState.value = _uiState.value.copy(lastError = reason.take(160)) }

    fun createSafety(
        state: SafetyState,
        companions: Int,
        location: String,
        note: String,
    ) = viewModelScope.launch {
        createMessageAndReturnHome {
            val resolved = resolveReportLocation(location, locationProvider)
            createSafetyMessage(state, companions, resolved, note, MessagePriority.HIGH)
        }
    }

    fun createSupply(
        kind: SupplyKind,
        count: Int,
        location: String,
        note: String,
        otherLabel: String?,
    ) = viewModelScope.launch {
        createMessageAndReturnHome {
            val resolved = resolveReportLocation(location, locationProvider)
            createSupplyMessage(kind, count, resolved, note, otherLabel, MessagePriority.CRITICAL)
        }
    }

    private suspend fun createMessageAndReturnHome(create: suspend () -> Unit) {
        _uiState.value = _uiState.value.withMessageCreationResult(executeMessageCreation(create))
    }
}
