package com.example.relay.ui.rescue

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.relay.rescue.RescueSubmissionStatus
import com.example.relay.rescue.RescueUrgency
import java.text.DateFormat
import java.util.Date

@Composable
fun RescueFlow(
    state: RescueUiState,
    callbacks: RescueCallbacks,
    onExit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    when (state.screen) {
        RescueScreen.HOME -> RescueHomeScreen(state, callbacks, onExit, modifier)
        RescueScreen.REQUEST_FORM -> RescueRequestFormScreen(state, callbacks, modifier)
        RescueScreen.BROADCASTING -> RescueBroadcastingScreen(state, callbacks, modifier)
        RescueScreen.COURIER_INVENTORY -> CourierInventoryScreen(state, callbacks, modifier)
        RescueScreen.SAFETY_PRIVACY -> SafetyPrivacyScreen(state.language, callbacks, modifier)
    }
}

@Composable
private fun RescueHomeScreen(
    state: RescueUiState,
    callbacks: RescueCallbacks,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = state.language
    RescuePage(
        title = language.text("Relay 救助", "Relay Rescue"),
        language = language,
        onToggleLanguage = callbacks::onToggleLanguage,
        modifier = modifier,
        showBack = true,
        onBack = onExit,
    ) { contentModifier ->
        RescueScrollableColumn(contentModifier) {
            Text(language.text("今すぐ助けが必要ですか", "Do you need help now?"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(language.text("SOSは2秒長押しで、人数不明・命の危険としてGPS位置をすぐ送ります。", "Hold SOS for 2 seconds to send your GPS immediately as a life-threatening emergency with an unknown group size."))
            SosHoldButton(language, enabled = !state.isRequestSubmitting, onSos = callbacks::onSendSos)
            state.formMessage?.let { Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(16.dp)) } }
            LargeActionButton(language.text("状況を入力して救助を依頼", "Describe the situation")) { callbacks.onNavigate(RescueScreen.REQUEST_FORM) }

            state.ownRequest?.let { request ->
                OwnRequestCard(request, language, callbacks)
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(language.text("通信は自動です", "Communication is automatic"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            state.broadcast.transferCount > 0 -> language.text("中継済み。避難所への到達を確認中です。", "Relayed. Waiting for confirmation from the shelter.")
                            state.broadcast.isActive -> language.text("周囲のRelay端末を探しています。", "Looking for nearby Relay devices.")
                            else -> language.text("中継の準備ができています。依頼後は、通信サービスが動作している間は画面を閉じても中継します。", "Ready to relay. After you request help, relaying continues even if you close the screen, as long as the communication service is running.")
                        },
                    )
                    Text(language.text("端末・避難所・再送を選ぶ操作はありません。", "You never need to select a device, shelter, or retry."))
                }
            }
            OutlinedButton(
                onClick = { callbacks.onNavigate(RescueScreen.SAFETY_PRIVACY) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text(language.text("安全とプライバシー", "Safety and privacy")) }
        }
    }
}

@Composable
private fun SosHoldButton(language: RescueLanguage, enabled: Boolean, onSos: () -> Unit) {
    var holding by remember { mutableStateOf(false) }
    var sentForCurrentHold by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (holding) 1f else 0f,
        animationSpec = tween(durationMillis = if (holding) 2_000 else 100),
        finishedListener = { value ->
            if (enabled && holding && value >= 1f && !sentForCurrentHold) {
                sentForCurrentHold = true
                onSos()
            }
        },
        label = "SOS hold progress",
    )
    val shape = RoundedCornerShape(12.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(shape)
                .background(if (enabled) Color(0xFFB42318) else Color(0xFF8C8C8C))
                .semantics {
                    role = Role.Button
                    onLongClick(language.text("2秒長押ししてSOSを送信", "Hold for 2 seconds to send SOS")) {
                        if (enabled) onSos()
                        enabled
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(onPress = {
                        sentForCurrentHold = false
                        holding = true
                        tryAwaitRelease()
                        holding = false
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SOS", color = Color.White, style = MaterialTheme.typography.displaySmall)
                Text(
                    if (holding) language.text("そのまま長押し", "Keep holding") else language.text("2秒長押し", "Hold 2 seconds"),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun OwnRequestCard(request: OwnRescueRequestUiState, language: RescueLanguage, callbacks: RescueCallbacks) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (request.isCancelled) language.text("自分の救助依頼（取消送信中）", "My rescue request (cancelling)") else language.text("自分の救助依頼", "My rescue request"),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                if (request.terminalStatus == "EXPIRED") {
                    language.text("この救助依頼の有効期限が切れています。", "This rescue request has expired.")
                } else {
                    request.submissionStatus.statusLabel(language)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (request.terminalStatus == "EXPIRED") {
                    language.text("この救助依頼はもう受け付けられません。", "This request can no longer be acted on.")
                } else {
                    request.submissionStatus.statusCopy().description(language)
                },
            )
            MiniLocationMap(request.urgency == RescueUrgency.IMMEDIATE)
            Text("GPS: %.5f, %.5f".format(request.latitude, request.longitude))
            Text(language.text("位置の取得時刻", "Location captured") + ": ${formatRescueTime(request.locationCapturedAtEpochMillis)}${request.accuracyMeters?.let { language.text("（精度 約${it.toInt()}m）", " (accuracy about ${it.toInt()} m)") }.orEmpty()}")
            if (!request.isCancelled && request.terminalStatus == null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        language.text(
                            "位置情報を共有して最新の現在地を送る",
                            "Share my location to send my latest position",
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = request.trackingEnabled,
                        onCheckedChange = callbacks::onSetLocationConsent,
                    )
                }
                Text(
                    if (request.trackingEnabled) {
                        language.text(
                            "同意により、更新のたびに現在地を暗号化して共有します。オフにするといつでも停止できます。",
                            "With your consent, your location is shared encrypted on each update. Turn it off to stop at any time.",
                        )
                    } else {
                        language.text(
                            "位置は依頼の作成時と更新の送信時のみ取得します。常時追跡はしません。",
                            "Location is captured only when you create the request and send an update. It is not tracked continuously.",
                        )
                    },
                )
            } else {
                Text(
                    language.text(
                        "位置は依頼の作成時と更新の送信時に取得します。常時追跡はしません。",
                        "Location is captured when you create the request and when you send an update. It is not tracked continuously.",
                    ),
                )
            }
            if (!request.isCancelled && request.terminalStatus == null) {
                Button(onClick = callbacks::onPrepareUpdate, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(language.text("状況・人数を更新", "Update situation or group size"))
                }
                OutlinedButton(onClick = callbacks::onCancelRequest, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(language.text("救助依頼を取り消す", "Cancel rescue request"))
                }
            } else if (request.terminalStatus != null) {
                OutlinedButton(onClick = callbacks::onAcknowledgeTerminalResult, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(language.text("結果を確認しました", "I have reviewed this result"))
                }
            }
        }
    }
}

@Composable
private fun MiniLocationMap(isImmediate: Boolean) {
    val marker = if (isImmediate) Color(0xFFB42318) else MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        repeat(5) { index ->
            val x = size.width * index / 4f
            val y = size.height * index / 4f
            drawLine(grid, start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x, size.height))
            drawLine(grid, start = androidx.compose.ui.geometry.Offset(0f, y), end = androidx.compose.ui.geometry.Offset(size.width, y))
        }
        drawCircle(marker, radius = 12.dp.toPx(), center = center)
        drawCircle(Color.White, radius = 4.dp.toPx(), center = center)
    }
}

@Composable
internal fun LargeActionButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    ) { Text(label, style = MaterialTheme.typography.titleLarge) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RescuePage(
    title: String,
    language: RescueLanguage,
    onToggleLanguage: () -> Unit,
    modifier: Modifier = Modifier,
    showBack: Boolean = true,
    onBack: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (showBack) OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp).heightIn(min = 56.dp),
                    ) { Text(language.text("戻る", "Back")) }
                },
                actions = {
                    OutlinedButton(
                        onClick = onToggleLanguage,
                        modifier = Modifier.padding(end = 8.dp).heightIn(min = 48.dp),
                    ) { Text(if (language == RescueLanguage.JAPANESE) "English" else "日本語") }
                },
            )
        },
    ) { padding -> Box(Modifier.fillMaxSize().padding(padding)) { content(Modifier.fillMaxSize()) } }
}

@Composable
internal fun SectionTitle(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleLarge,
    modifier = Modifier.padding(top = 8.dp).semantics { heading() },
)

private fun RescueSubmissionStatus.statusLabel(language: RescueLanguage): String =
    statusCopy().shortLabel(language)

private fun formatRescueTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMillis))
