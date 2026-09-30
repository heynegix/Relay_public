package com.example.relay.ui.rescue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.relay.rescue.CourierRescueItem
import com.example.relay.rescue.RescueSubmissionStatus
import java.text.DateFormat
import java.util.Date

@Composable
internal fun RescueBroadcastingScreen(state: RescueUiState, callbacks: RescueCallbacks, modifier: Modifier = Modifier) {
    val broadcast = state.broadcast
    val language = state.language
    RescuePage(
        title = if (broadcast.isActive) language.text("救助要請を中継中", "Relaying rescue request") else language.text("救助要請を保存しました", "Rescue request saved"),
        language = language,
        onToggleLanguage = callbacks::onToggleLanguage,
        modifier = modifier,
        onBack = { callbacks.onNavigate(RescueScreen.HOME) },
    ) { contentModifier ->
        RescueScrollableColumn(contentModifier) {
            Text(if (broadcast.isActive) language.text("近くの端末へ中継中", "Relaying via nearby devices") else language.text("取り消しを中継中", "Relaying cancellation"), style = MaterialTheme.typography.headlineSmall)
            Text(broadcast.statusMessage, style = MaterialTheme.typography.bodyLarge)
            StatusCard(language.text("近くのRelay端末", "Nearby Relay devices"), language.text("${broadcast.nearbyDeviceCount}台", "${broadcast.nearbyDeviceCount}"))
            StatusCard(language.text("中継した回数", "Relay count"), language.text("${broadcast.transferCount}回", "${broadcast.transferCount}"))
            state.ownRequest?.let { own ->
                StatusCard(language.text("GPS位置", "GPS location"), "%.5f, %.5f".format(own.latitude, own.longitude))
            }
            Text(language.text("画面を閉じたあとも、通信サービスが動作している間は中継を続けます。強制終了や省電力設定で止まることがあります。止める場合はホームの「救助依頼を取り消す」を使ってください。", "Relaying continues while the app's communication service is running, but a force stop or battery saver can end it. To stop, use Cancel rescue request on Home."))
            LargeActionButton(language.text("ホームへ戻る", "Return home")) { callbacks.onNavigate(RescueScreen.HOME) }
        }
    }
}

/** Shows courier metadata only. No PC search, submit, retry, or key entry controls exist here. */
@Composable
internal fun CourierInventoryScreen(state: RescueUiState, callbacks: RescueCallbacks, modifier: Modifier = Modifier) {
    val language = state.language
    val items = state.courierItems
    val automation = state.courierAutomation
    RescuePage(
        title = language.text("運んでいる情報", "Relayed requests"),
        language = language,
        onToggleLanguage = callbacks::onToggleLanguage,
        modifier = modifier,
        onBack = { callbacks.onNavigate(RescueScreen.HOME) },
    ) { contentModifier ->
        RescueScrollableColumn(contentModifier) {
            Text(language.text("内容は表示されません", "Request details are hidden"), style = MaterialTheme.typography.titleMedium)
            Text(language.text("受信した救助要請は、地域の救助拠点のPCが見つかると安全に中継します。あなたの操作は不要です。", "Received rescue requests are relayed securely when a nearby rescue hub PC is found. No action is needed."))
            StatusCard(language.text("自動中継", "Automatic relay"), if (automation.isEnabled) automation.statusMessage else language.text("中継の準備中です", "Preparing to relay"))
            automation.lastDeliveredAtEpochMillis?.let { StatusCard(language.text("最後に中継した時刻", "Last relayed"), formatTime(it)) }
            if (items.isEmpty()) {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(language.text("運んでいる要請はありません", "No requests are being relayed"), style = MaterialTheme.typography.titleLarge)
                    Text(language.text("近くの端末から受け取ると、自動で保管して避難所へ運びます。", "Requests received from nearby devices are stored and carried to the shelter automatically."))
                } }
            } else {
                Text(language.text("保管中: ${items.size}件", "Stored: ${items.size}"), style = MaterialTheme.typography.titleLarge)
                items.forEach { item -> CourierItemCard(item, language) }
            }
        }
    }
}

@Composable
private fun CourierItemCard(item: CourierRescueItem, language: RescueLanguage) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = language.text("保管中の救助要請。", "Stored rescue request. ") + item.submissionStatus.label(language) }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(language.text("受信した救助要請", "Received rescue request"), style = MaterialTheme.typography.titleMedium)
            Text(language.text("状態", "Status") + ": ${item.submissionStatus.label(language)}")
            Text(language.text("送信先: 地域の救助拠点", "Destination: local rescue hub"))
            Text(language.text("受信", "Received") + ": ${formatTime(item.receivedAtEpochMillis)}")
            Text(language.text("期限", "Expires") + ": ${formatTime(item.expiresAtEpochMillis)}")
        }
    }
}

@Composable
internal fun SafetyPrivacyScreen(language: RescueLanguage, callbacks: RescueCallbacks, modifier: Modifier = Modifier) {
    RescuePage(
        title = language.text("安全とプライバシー", "Safety and privacy"),
        language = language,
        onToggleLanguage = callbacks::onToggleLanguage,
        modifier = modifier,
        onBack = { callbacks.onNavigate(RescueScreen.HOME) },
    ) { contentModifier ->
        RescueScrollableColumn(contentModifier) {
            SectionTitle(language.text("救助要請を作る方へ", "For people requesting rescue"))
            Text(language.text("位置や健康情報を含む内容は、避難所だけが読めるように暗号化します。", "Location and health details are encrypted so only the shelter can read them."))
            SectionTitle(language.text("情報を運ぶ方へ", "For people relaying information"))
            Text(language.text("受け取った情報の内容、正確な位置、人数は見えません。救助拠点への中継も自動です。", "You cannot see the request details, exact location, or group size. Relaying to the rescue hub is automatic."))
            SectionTitle(language.text("通信できないとき", "When no connection is available"))
            Text(language.text("エラーではありません。近くの中継端末や救助拠点のPCを見つけるまで、情報を安全に保管します。", "This is expected. Relay stores the request securely until a nearby relay device or rescue hub PC is found."))
            LargeActionButton(language.text("ホームへ戻る", "Return home")) { callbacks.onNavigate(RescueScreen.HOME) }
        }
    }
}

@Composable
private fun StatusCard(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    } }
}

private fun RescueSubmissionStatus.label(language: RescueLanguage): String =
    statusCopy().description(language)

private fun formatTime(epochMillis: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMillis))
