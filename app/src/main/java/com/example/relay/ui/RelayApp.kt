@file:Suppress("FunctionNaming")
package com.example.relay.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.relay.background.ActivationSource
import com.example.relay.background.BackgroundRelayMode
import com.example.relay.background.BackgroundRelayState
import com.example.relay.background.DegradeReason
import com.example.relay.domain.SafetyState
import com.example.relay.domain.SupplyKind
import com.example.relay.permissions.AndroidNearbyPermissionGate
import com.example.relay.permissions.NearbyPrerequisite
import com.example.relay.permissions.NearbyPrerequisiteChecker
import com.example.relay.RelayApplication
import com.example.relay.regional.AndroidRegionalDeploymentProfile
import com.example.relay.service.RelayCommunicationService
import com.example.relay.service.RescueDeliveryService
import com.example.relay.service.shouldAutoStartCommunication
import com.example.relay.ui.rescue.RescueFlow
import com.example.relay.ui.rescue.RescueViewModel
import com.example.relay.ui.enrollment.EnrollmentScreen
import com.example.relay.ui.enrollment.EnrollmentViewModel

@Composable
fun RelayApp(viewModel: RelayViewModel, rescueViewModel: RescueViewModel, enrollmentViewModel: EnrollmentViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val rescueState by rescueViewModel.state.collectAsStateWithLifecycle()
    val regionalItems by viewModel.regionalItems.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionGate = remember { AndroidNearbyPermissionGate(context) }
    val activationStore = remember { RelayCommunicationService.activationStore(context) }
    val app = remember(context) { context.applicationContext as RelayApplication }
    val backgroundState by app.backgroundRelayManager.state.collectAsStateWithLifecycle()
    val activity = remember(context) { context.findActivity() }
    var explainPermissions by rememberSaveable { mutableStateOf(false) }
    var openRescueAfterPermission by rememberSaveable { mutableStateOf(false) }

    fun startCommunication() {
        RelayCommunicationService.start(context, com.example.relay.domain.OperatingMode.RELAY, state.role)
            ?.let(viewModel::reportError)
    }

    LaunchedEffect(permissionGate.canUseNearby(), state.transportRunning) {
        if (shouldAutoStartCommunication(permissionGate.canUseNearby(), state.transportRunning, activationStore.isEnabled())) {
            startCommunication()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        // Transport can start without GPS; location may still be denied after partial grant.
        if (permissionGate.canUseNearby()) {
            startCommunication()
        } else {
            val permanentlyDenied = result.filterValues { !it }.keys.any { permission ->
                activity?.shouldShowRequestPermissionRationale(permission) == false
            }
            viewModel.reportError(if (permanentlyDenied) "権限が無効です。設定からRelayの権限を許可してください。" else "通信に必要な権限が許可されませんでした。")
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (openRescueAfterPermission && permissionGate.hasLocationPermission()) {
            openRescueAfterPermission = false
            viewModel.navigate(RelayScreen.RESCUE)
        } else if (openRescueAfterPermission) {
            openRescueAfterPermission = false
            viewModel.reportError("救助依頼にはGPS位置情報が必要です。設定から位置情報を許可してください。")
        }
    }

    fun requestMissingIncludingLocation(thenStart: Boolean) {
        val missing = permissionGate.missingPermissions()
        if (missing.isEmpty()) {
            if (thenStart && permissionGate.canUseNearby()) startCommunication()
            return
        }
        permissionLauncher.launch(missing.toTypedArray())
    }

    fun ensureLocationForForms() {
        val missingLoc = permissionGate.missingLocationPermissions()
        if (missingLoc.isNotEmpty()) {
            locationPermissionLauncher.launch(missingLoc.toTypedArray())
        }
    }

    MaterialTheme {
        if (explainPermissions) {
            AlertDialog(
                onDismissRequest = { explainPermissions = false },
                title = { Text("災害通信を開始") },
                text = {
                    Text(
                        "近くのRelay端末を自動で探し、情報を保存・中継します。" +
                            "登録やペアリングは不要です。位置情報は安否・物資の場所補完に使います（拒否しても登録可能）。",
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        explainPermissions = false
                        // Includes ACCESS_FINE/COARSE on API 32+ so GPS create-path is not dead.
                        requestMissingIncludingLocation(thenStart = true)
                    }) { Text("許可して開始") }
                },
                dismissButton = { OutlinedButton(onClick = { explainPermissions = false }) { Text("あとで") } },
            )
        }

        when (state.screen) {
            RelayScreen.HOME -> HomeScreen(
                state = state,
                count = regionalItems.size,
                start = {
                    if (permissionGate.missingPermissions().isEmpty()) {
                        if (permissionGate.canUseNearby()) startCommunication()
                    } else {
                        explainPermissions = true
                    }
                },
                stop = { RelayCommunicationService.stop(context) },
                rescue = {
                    if (permissionGate.hasLocationPermission()) {
                        viewModel.navigate(RelayScreen.RESCUE)
                    } else {
                        openRescueAfterPermission = true
                        val missing = permissionGate.missingLocationPermissions()
                        if (missing.isNotEmpty()) locationPermissionLauncher.launch(missing.toTypedArray())
                    }
                },
                navigate = viewModel::navigate,
            )
            RelayScreen.RESCUE -> RescueFlow(
                state = rescueState,
                callbacks = rescueViewModel,
                onExit = { viewModel.navigate(RelayScreen.HOME) },
            )
            RelayScreen.SAFETY_FORM -> SafetyForm(viewModel)
            RelayScreen.SUPPLY_FORM -> SupplyForm(viewModel)
            RelayScreen.REGIONAL -> OfficialInformationScreen(viewModel::navigate, app.regionalDeploymentProfile)
            RelayScreen.SETTINGS -> SettingsScreen(
                state = state,
                backgroundState = backgroundState,
                diagnostics = app.diagnostics.recent(),
                clearDiagnostics = { app.diagnostics.clear() },
                enableStandby = { app.backgroundRelayManager.optIn() },
                disableStandby = { app.backgroundRelayManager.optOut() },
                startDisaster = { RescueDeliveryService.enableAndStart(context, ActivationSource.USER_ACTION) },
                endDisaster = { RescueDeliveryService.stop(context) },
                openAppSettings = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) },
                openBluetoothSettings = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                navigate = viewModel::navigate,
            )
            RelayScreen.GATEWAY_ENROLLMENT -> EnrollmentScreen(
                viewModel = enrollmentViewModel,
                onBack = { viewModel.navigate(RelayScreen.SETTINGS) },
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun HomeScreen(
    state: RelayUiState,
    count: Int,
    start: () -> Unit,
    stop: () -> Unit,
    rescue: () -> Unit,
    navigate: (RelayScreen) -> Unit,
) {
    MainScaffold(RelayScreen.HOME, navigate) { modifier ->
        LazyColumn(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("Relay", style = MaterialTheme.typography.headlineLarge) }
            item {
                Button(
                    onClick = rescue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(88.dp)
                        .semantics { contentDescription = "救助を求める" },
                ) { Text("救助を求める", style = MaterialTheme.typography.titleLarge) }
            }
            item {
                Card(Modifier.fillMaxWidth().semantics { contentDescription = "自動通信の状態" }) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            when {
                                state.transportRunning && state.connectedPeers > 0 -> "近くの端末と情報を交換中"
                                state.transportRunning -> "周囲の端末を探しています"
                                else -> "災害通信は停止中です"
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(if (state.transportRunning) "自動で接続・中継しています" else "開始すると自動で通信します")
                        Text("端末内の中継情報: ${count}件")
                        Text("接続中の端末: ${state.connectedPeers}台")
                        Text(gatewayStatusLabel(state.gatewayLastResult, state.transportRunning))
                        state.internetSyncLabel?.let { Text(it) }
                        OutlinedButton(
                            onClick = if (state.transportRunning) stop else start,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .semantics {
                                    contentDescription = if (state.transportRunning) {
                                        "災害通信を停止"
                                    } else {
                                        "災害通信を開始"
                                    }
                                },
                        ) { Text(if (state.transportRunning) "自動通信を停止" else "自動通信を開始") }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { navigate(RelayScreen.REGIONAL) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .semantics { contentDescription = "地域情報を見る" },
                ) { Text("設定地域の公式防災情報を見る") }
            }
            state.lastError?.let { error ->
                item {
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "通信状態のエラー" },
                    ) {
                        Text("通信状態: $error", Modifier.padding(14.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SafetyForm(viewModel: RelayViewModel) {
    var selected by rememberSaveable { mutableStateOf(SafetyState.SAFE) }
    var companions by rememberSaveable { mutableIntStateOf(0) }
    var location by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    FormScaffold("安否・避難状況", viewModel) {
        Text("現在の状態", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(SafetyState.entries) { option ->
            if (selected == option) Button(onClick = { selected = option }) { Text(option.label()) }
            else OutlinedButton(onClick = { selected = option }) { Text(option.label()) }
        } }
        NumberChooser("同行者数", companions, 0, 99) { companions = it }
        Text("場所が空なら保存時にGPSで補完します（拒否・未取得でも保存できます）", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(location, { location = it.take(100) }, label = { Text("場所（任意・空ならGPS）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it.take(280) }, label = { Text("短いメモ（任意）") }, minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { viewModel.createSafety(selected, companions, location, note) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .semantics { contentDescription = "安否情報を保存する" },
        ) { Text("保存する") }
    }
}

@Composable
private fun SupplyForm(viewModel: RelayViewModel) {
    var selected by rememberSaveable { mutableStateOf(SupplyKind.WATER) }
    var count by rememberSaveable { mutableIntStateOf(1) }
    var location by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var other by rememberSaveable { mutableStateOf("") }
    FormScaffold("不足物資", viewModel) {
        Text("必要な物資", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(SupplyKind.entries) { option ->
            if (selected == option) Button(onClick = { selected = option }) { Text(option.label()) }
            else OutlinedButton(onClick = { selected = option }) { Text(option.label()) }
        } }
        if (selected == SupplyKind.OTHER) OutlinedTextField(other, { other = it.take(50) }, label = { Text("物資名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        NumberChooser("必要数", count, 1, 9999) { count = it }
        Text("場所が空なら保存時にGPSで補完します（拒否・未取得でも保存できます）", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(location, { location = it.take(100) }, label = { Text("場所（任意・空ならGPS）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it.take(280) }, label = { Text("短いメモ（任意）") }, minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { viewModel.createSupply(selected, count, location, note, other.takeIf { selected == SupplyKind.OTHER }) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .semantics { contentDescription = "物資不足情報を保存する" },
        ) { Text("保存する") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormScaffold(title: String, viewModel: RelayViewModel, content: @Composable () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { OutlinedButton(onClick = { viewModel.navigate(RelayScreen.HOME) }) { Text("戻る") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { item { content() } }
    }
}

@Composable
private fun NumberChooser(label: String, value: Int, min: Int, max: Int, update: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        OutlinedButton(onClick = { update((value - 1).coerceAtLeast(min)) }) { Text("−") }
        Text("$label\n$value", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { update((value + 1).coerceAtMost(max)) }) { Text("＋") }
    } }
}

@Composable
private fun OfficialInformationScreen(navigate: (RelayScreen) -> Unit, profile: AndroidRegionalDeploymentProfile) {
    val uriHandler = LocalUriHandler.current
    val sources = profile.officialSources.map { OfficialSource(it.title, it.description, it.url) }

    MainScaffold(RelayScreen.REGIONAL, navigate) { modifier ->
        LazyColumn(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("設定地域の公式防災情報", style = MaterialTheme.typography.headlineMedium) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "誤情報を避けるため、この画面には行政・気象庁の情報だけを表示します。リンクを開くにはインターネット接続が必要です。PC版は取得済み情報をキャッシュ表示します。",
                        Modifier.padding(16.dp),
                    )
                }
            }
            if (sources.isEmpty()) item { Text("公式リンクは地域プロファイルで設定されていません。") }
            items(sources, key = { it.url }) { source ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(source.title, style = MaterialTheme.typography.titleLarge)
                        Text(source.description)
                        Button(
                            onClick = { uriHandler.openUri(source.url) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) { Text("公式サイトを開く") }
                    }
                }
            }
        }
    }
}

private data class OfficialSource(val title: String, val description: String, val url: String)

internal fun gatewayStatusLabel(lastResult: String?, transportRunning: Boolean): String = when {
    !transportRunning -> "中継拠点: 通信停止中"
    lastResult == null -> "中継拠点: 探索・同期の準備中"
    lastResult == "idle" -> "中継拠点: 送信待ちの情報なし"
    lastResult == "gateway_not_found" -> "中継拠点: 未検出（利用できる中継経路が見つかりません）"
    lastResult.startsWith("sent=") -> "中継拠点: 同期済み・内容は未検証（$lastResult）"
    lastResult.startsWith("http_") -> "中継拠点: 通信エラー（$lastResult）"
    lastResult == "network_error" -> "中継拠点: ネットワークエラー"
    else -> "中継拠点: $lastResult"
}

@Composable
private fun BackgroundRelaySection(
    backgroundState: BackgroundRelayState,
    connectedPeers: Int,
    enableStandby: () -> Unit,
    disableStandby: () -> Unit,
    startDisaster: () -> Unit,
    endDisaster: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionGate = remember { AndroidNearbyPermissionGate(context) }
    // Recomputed on each recomposition (e.g. when returning from system settings) so the status
    // reflects live Bluetooth / permission / Play-services / notification / battery state.
    val prerequisite = remember(backgroundState) { NearbyPrerequisiteChecker(context, permissionGate).check() }
    val notificationLabel = remember(backgroundState) { notificationStatusLabel(context) }
    val batteryLabel = remember(backgroundState) { batteryStatusLabel(context) }

    Card(Modifier.fillMaxWidth().semantics { contentDescription = "常時待機（ARMED）と災害通信" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("常時待機（ARMED）", style = MaterialTheme.typography.titleMedium)
            Text("現在のモード: " + modeLabel(backgroundState.mode))
            Text("起動理由: " + activationLabel(backgroundState.activationSource))
            Text("最終送受信: " + timeLabel(backgroundState.lastTransferAtEpochMillis))
            Text("接続中の端末: ${connectedPeers}台")
            Text("Bluetooth / Nearby: " + prerequisiteLabel(prerequisite))
            Text("通知: " + notificationLabel)
            Text("Google Play services: " + playServicesLabel(prerequisite))
            Text("バッテリー: " + batteryLabel)
            backgroundState.degradeReason.takeIf { it != DegradeReason.NONE }?.let {
                Text("縮退理由: " + degradeLabel(it))
            }
            backgroundState.lastError?.let { Text("最終終了/エラー: " + it, style = MaterialTheme.typography.bodySmall) }
            if (backgroundState.explicitlyStoppedByUser) {
                Text("ℹ ユーザーが停止しました。自動復元は行いません（手動で再開できます）。", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "※ ARMEDは「自動起動できる設定が保存された状態」です。" +
                    "平常時はNearbyを常時動作させず、これだけでは災害を自動検知できません。" +
                    "災害通信はユーザー操作・通知・救助情報の作成/受信・再起動復元で開始します。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "※ Androidの制約: OS・メーカー・ユーザーの強制停止を回避して永続動作することはできません。" +
                    "Android 12以降はバックグラウンドからのFGS起動が制限されます。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (notificationLabel != "許可済み") {
                Text("推奨: 通知を許可すると災害通信中の状態を確認できます（未許可でもFGSは起動します）。", style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (backgroundState.optedIn) {
                    OutlinedButton(
                        onClick = disableStandby,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "常時待機を無効化" },
                    ) { Text("待機を無効化") }
                } else {
                    Button(
                        onClick = enableStandby,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "常時待機を有効化" },
                    ) { Text("待機を有効化") }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = startDisaster,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "災害通信を開始" },
                ) { Text("災害通信を開始") }
                OutlinedButton(
                    onClick = endDisaster,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "災害通信を終了" },
                ) { Text("災害通信を終了") }
            }
        }
    }
}

private fun modeLabel(mode: BackgroundRelayMode): String = when (mode) {
    BackgroundRelayMode.DISABLED -> "無効（DISABLED）"
    BackgroundRelayMode.ARMED -> "待機中（ARMED）"
    BackgroundRelayMode.EMERGENCY_ACTIVE -> "災害通信中（EMERGENCY_ACTIVE）"
    BackgroundRelayMode.DEGRADED -> "縮退中（DEGRADED）"
    BackgroundRelayMode.SUSPENDED_BY_USER -> "ユーザー停止（SUSPENDED_BY_USER）"
}

private fun activationLabel(source: ActivationSource?): String = when (source) {
    null -> "なし"
    ActivationSource.USER_ACTION -> "ユーザー操作"
    ActivationSource.RESCUE_CREATED -> "救助情報の作成"
    ActivationSource.RESCUE_RECEIVED -> "救助情報の受信"
    ActivationSource.BOOT_RESTORE -> "再起動復元"
    ActivationSource.PACKAGE_REPLACED -> "アプリ更新復元"
    ActivationSource.BLUETOOTH_RESTORED -> "Bluetooth再有効化復元"
    ActivationSource.DEBUG_SIMULATION -> "デバッグシミュレーション"
}

private fun degradeLabel(reason: DegradeReason): String = when (reason) {
    DegradeReason.NONE -> "なし"
    DegradeReason.MISSING_BLUETOOTH_PERMISSION -> "Bluetooth権限不足"
    DegradeReason.BLUETOOTH_DISABLED -> "Bluetooth無効"
    DegradeReason.MISSING_NEARBY_PERMISSION -> "Nearby Wi-Fi権限不足"
    DegradeReason.PLAY_SERVICES_UNAVAILABLE -> "Google Play services利用不可"
    DegradeReason.DEGRADED_NOTIFICATION_VISIBILITY -> "通知非表示（動作は継続）"
    DegradeReason.OS_BACKGROUND_RESTRICTED -> "OSのバックグラウンド制限"
}

private fun prerequisiteLabel(p: NearbyPrerequisite): String = when (p) {
    NearbyPrerequisite.Ready -> "利用可能"
    is NearbyPrerequisite.MissingPermissions -> "権限不足"
    NearbyPrerequisite.BluetoothUnavailable -> "Bluetooth非対応"
    NearbyPrerequisite.BluetoothDisabled -> "Bluetooth無効"
    NearbyPrerequisite.LocationServicesDisabled -> "位置情報無効"
    is NearbyPrerequisite.PlayServicesUnavailable -> "権限OK（Play servicesを確認）"
}

private fun playServicesLabel(p: NearbyPrerequisite): String = when (p) {
    is NearbyPrerequisite.PlayServicesUnavailable -> "利用不可（code=${p.statusCode}）"
    else -> "利用可能または未確認"
}

private fun notificationStatusLabel(context: Context): String {
    if (android.os.Build.VERSION.SDK_INT < 33) return "許可済み"
    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.POST_NOTIFICATIONS,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    return if (granted) "許可済み" else "未許可（DEGRADED_NOTIFICATION_VISIBILITY）"
}

private fun batteryStatusLabel(context: Context): String {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return "不明"
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
    val statusRaw = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = statusRaw == BatteryManager.BATTERY_STATUS_CHARGING || statusRaw == BatteryManager.BATTERY_STATUS_FULL
    val pctText = if (pct >= 0) "$pct%" else "不明"
    return pctText + if (charging) "（充電中）" else "（非充電）"
}

private fun timeLabel(epochMillis: Long): String {
    if (epochMillis <= 0) return "なし"
    val fmt = java.text.SimpleDateFormat("MM/dd HH:mm:ss", java.util.Locale.JAPAN)
    return fmt.format(java.util.Date(epochMillis))
}

@Composable
private fun SettingsScreen(
    state: RelayUiState,
    backgroundState: BackgroundRelayState,
    diagnostics: List<String>,
    clearDiagnostics: () -> Unit,
    enableStandby: () -> Unit,
    disableStandby: () -> Unit,
    startDisaster: () -> Unit,
    endDisaster: () -> Unit,
    openAppSettings: () -> Unit,
    openBluetoothSettings: () -> Unit,
    navigate: (RelayScreen) -> Unit,
) {
    MainScaffold(RelayScreen.SETTINGS, navigate) { modifier ->
        LazyColumn(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("設定", style = MaterialTheme.typography.headlineMedium) }
            item {
                BackgroundRelaySection(
                    backgroundState = backgroundState,
                    connectedPeers = state.connectedPeers,
                    enableStandby = enableStandby,
                    disableStandby = disableStandby,
                    startDisaster = startDisaster,
                    endDisaster = endDisaster,
                )
            }
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("すべてローカル優先です。登録・ペアリングは不要です。")
                Text("近くの端末で中継し、利用できる安全なオンライン経路（地域の中継拠点など）へ公開同期できます。")
                Text("インターネットが戻ると重要情報を追加受信します（オフライン中継はそのまま）。")
                Text(if (state.transportRunning) "災害通信: 動作中" else "災害通信: 停止中")
                Text("中継拠点への保存は未検証の証跡です。公式到達ではありません。")
            } } }
            item {
                Card(
                    Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "PC Gateway診断情報" },
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("PC Gateway診断", style = MaterialTheme.typography.titleSmall)
                        Text("発見IP: " + (state.gatewayDiscoveredIp ?: "未検出"))
                        Text("探索結果: " + (state.gatewayDiscoveryResult ?: "未実行"))
                        Text("配送結果: " + (state.gatewayDeliveryResult ?: state.gatewayLastResult ?: "未実行"))
                    }
                }
            }
            if (state.debugEvents.isNotEmpty()) {
                item {
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "最近の通信デバッグログ" },
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("最近の通信イベント（最大10件）", style = MaterialTheme.typography.titleSmall)
                            state.debugEvents.takeLast(10).asReversed().forEach { line ->
                                Text(line, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth().semantics { contentDescription = "PoC diagnostics" }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("PoC delivery diagnostics (no rescue content)", style = MaterialTheme.typography.titleSmall)
                        if (diagnostics.isEmpty()) {
                            Text("No recorded events yet.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            diagnostics.take(12).forEach { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
                        }
                        OutlinedButton(onClick = clearDiagnostics) { Text("Clear diagnostics") }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { navigate(RelayScreen.GATEWAY_ENROLLMENT) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Gateway登録管理を開く" },
                ) { Text("Gateway登録管理") }
            }
            item {
                OutlinedButton(
                    onClick = openBluetoothSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Bluetooth設定を開く" },
                ) { Text("Bluetooth設定を開く") }
            }
            item {
                OutlinedButton(
                    onClick = openAppSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Relayの権限設定を開く" },
                ) { Text("Relayの権限設定を開く") }
            }
        }
    }
}

@Composable
private fun MainScaffold(selected: RelayScreen, navigate: (RelayScreen) -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(bottomBar = { Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(RelayScreen.HOME to "ホーム", RelayScreen.REGIONAL to "地域情報", RelayScreen.SETTINGS to "設定").forEach { (screen, label) ->
            if (selected == screen) Button(onClick = { navigate(screen) }, modifier = Modifier.weight(1f)) { Text(label) }
            else OutlinedButton(onClick = { navigate(screen) }, modifier = Modifier.weight(1f)) { Text(label) }
        }
    } }) { padding -> content(Modifier.fillMaxSize().padding(padding)) }
}

private fun SafetyState.label() = when (this) {
    SafetyState.SAFE -> "無事"
    SafetyState.INJURED -> "けががあり"
    SafetyState.EVACUATING -> "避難中"
    SafetyState.AT_SHELTER -> "避難所に到着"
}

private fun SupplyKind.label() = when (this) {
    SupplyKind.WATER -> "水"
    SupplyKind.FOOD -> "食料"
    SupplyKind.MEDICINE -> "薬"
    SupplyKind.BLANKET -> "毛布"
    SupplyKind.POWER -> "電源"
    SupplyKind.HYGIENE -> "衛生用品"
    SupplyKind.OTHER -> "その他"
}
