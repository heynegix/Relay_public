package com.example.relay.cmp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.relay.data.InMemoryMessageStore
import com.example.relay.domain.DeliveryPresentation
import com.example.relay.domain.MessageFactory
import com.example.relay.domain.MessagePolicy
import com.example.relay.domain.MessageType
import com.example.relay.domain.SafetyState
import com.example.relay.domain.SupplyKind
import com.example.relay.domain.defaultRelayClock
import com.example.relay.domain.deliveryPresentationLabel
import com.example.relay.domain.deriveDeliveryPresentation
import com.example.relay.domain.labelJa
import com.example.relay.domain.randomUuid
import com.example.relay.gateway.DiscoveredGateway
import com.example.relay.gateway.GatewayPublicClient
import kotlinx.coroutines.launch


private enum class Screen { HOME, SAFETY, SUPPLY, REGIONAL, OFFLINE_MAP }

/**
 * Compose Multiplatform UI shared by iPhone (iosMain) and desktop/Android bridge targets.
 * Domain + PC Gateway public path come from the Kotlin [shared] module — not a SwiftUI rewrite.
 */
@Composable
fun RelaySharedApp(
    store: InMemoryMessageStore = remember { InMemoryMessageStore() },
    deviceId: String = remember { randomUuid() },
    gatewayHostOverride: String? = null,
    gatewayPortOverride: Int = 8080,
    offlineMapPack: VerifiedOfflineMapPack? = null,
) {
    val clock = remember { defaultRelayClock() }
    val policy = remember { MessagePolicy(clock) }
    val client = remember { GatewayPublicClient() }
    val scope = rememberCoroutineScope()
    val messages by store.messagesFlow.collectAsState()
    var screen by remember { mutableStateOf(Screen.HOME) }
    var status by remember { mutableStateOf<String?>(null) }
    var lastGateway by remember { mutableStateOf<String?>(null) }

    fun syncGateway() {
        scope.launch {
            status = "中継拠点へ同期中…"
            try {
                store.pruneExpired(policy)
                val pending = store.all().mapNotNull { policy.prepareForGatewayUpload(it) }
                if (pending.isEmpty()) {
                    lastGateway = "idle"
                    status = null
                    return@launch
                }
                val host = gatewayHostOverride
                val gateway = if (!host.isNullOrBlank()) {
                    DiscoveredGateway(host, gatewayPortOverride, "manual")
                } else {
                    // Discovery is platform-specific; until wired, require override or localhost.
                    DiscoveredGateway("127.0.0.1", gatewayPortOverride, "local")
                }
                val response = client.pushPublic(gateway, deviceId, "Relay 共有版プレビュー", pending)
                client.mapReceipts(response).forEach { store.insertReceipt(it) }
                lastGateway =
                    "sent=${response.acceptedMessageIds.size} (内容は未検証)"
                status = null
            } catch (error: Exception) {
                status = "同期エラー: ${error.message?.take(120) ?: error::class.simpleName}"
                lastGateway = "network_error"
            }
        }
    }

    MaterialTheme {
        when (screen) {
            Screen.HOME -> Scaffold(
                bottomBar = {
                    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { screen = Screen.HOME }, modifier = Modifier.weight(1f)) { Text("ホーム") }
                        OutlinedButton(onClick = { screen = Screen.REGIONAL }, modifier = Modifier.weight(1f)) {
                            Text("地域情報")
                        }
                    }
                },
            ) { padding ->
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item { Text("Relay", style = MaterialTheme.typography.headlineLarge) }
                    item {
                        Card(
                            Modifier.fillMaxWidth().semantics { contentDescription = "災害通信の状態" },
                        ) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("共有Kotlin版（開発プレビュー）", style = MaterialTheme.typography.titleLarge)
                                Text("開発プレビュー版です。救助依頼（SOS）と自動中継はありません。安否・物資・地域情報の共有と地図確認に対応します。")
                                Text("保存中の情報: ${messages.size}件")
                                Text(
                                    when {
                                        lastGateway == null -> "中継拠点: 未同期"
                                        lastGateway == "idle" -> "中継拠点: 送信待ちの情報なし"
                                        lastGateway!!.startsWith("sent=") ->
                                            "中継拠点: 同期済み・内容は未検証（$lastGateway）"
                                        else -> "中継拠点: $lastGateway"
                                    },
                                )
                                Button(
                                    onClick = { syncGateway() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .semantics { contentDescription = "中継拠点へ同期" },
                                ) { Text("地域の中継拠点へ同期（開発プレビュー）") }
                            }
                        }
                    }
                    item {
                        Button(
                            onClick = { screen = Screen.SAFETY },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(68.dp)
                                .semantics { contentDescription = "無事・避難状況を登録" },
                        ) { Text("無事・避難状況を登録") }
                    }
                    item {
                        Button(
                            onClick = { screen = Screen.SUPPLY },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(68.dp)
                                .semantics { contentDescription = "不足している物資を登録" },
                        ) { Text("不足している物資を登録") }
                    }
                    item {
                        OfflineMapCard(
                            pack = offlineMapPack,
                            onOpen = { if (offlineMapPack != null) screen = Screen.OFFLINE_MAP },
                        )
                    }
                    status?.let { err ->
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Text("通信状態: $err", Modifier.padding(14.dp))
                            }
                        }
                    }
                }
            }
            Screen.SAFETY -> SafetyForm(
                onBack = { screen = Screen.HOME },
                onSave = { state, companions, location, note ->
                    scope.launch {
                        try {
                            val msg = MessageFactory.createSafety(
                                state, companions, location, note, deviceId, clock, policy,
                            )
                            store.insert(msg)
                            screen = Screen.HOME
                            status = null
                        } catch (e: Exception) {
                            status = e.message
                        }
                    }
                },
            )
            Screen.SUPPLY -> SupplyForm(
                onBack = { screen = Screen.HOME },
                onSave = { kind, count, location, note, other ->
                    scope.launch {
                        try {
                            val msg = MessageFactory.createSupply(
                                kind, count, location, note, other, deviceId, clock, policy,
                            )
                            store.insert(msg)
                            screen = Screen.HOME
                            status = null
                        } catch (e: Exception) {
                            status = e.message
                        }
                    }
                },
            )
            Screen.REGIONAL -> RegionalList(
                messages = messages,
                deviceId = deviceId,
                receipts = { id ->
                    // collectAsState snapshot is not suspend; presentation uses empty until refreshed
                    emptyList()
                },
                onBack = { screen = Screen.HOME },
            )
            Screen.OFFLINE_MAP -> OfflineMapScreen(
                pack = offlineMapPack,
                onBack = { screen = Screen.HOME },
            )
        }
    }
}

@Composable
private fun OfflineMapCard(pack: VerifiedOfflineMapPack?, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "署名済みオフライン地図" }) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("オフライン地図", style = MaterialTheme.typography.titleLarge)
            if (pack == null) {
                Text("署名とハッシュを検証した地図パックがありません。地図は初期化されていません。")
                Text("style.json と PMTiles を検証済みのローカルパックとして登録してください。")
            } else {
                Text("署名済み地図パックを利用できます。通信なしで表示します。")
                Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("地図を開く")
                }
            }
        }
    }
}

@Composable
private fun OfflineMapScreen(pack: VerifiedOfflineMapPack?, onBack: () -> Unit) {
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = onBack) { Text("戻る") }
            Text("オフライン地図", style = MaterialTheme.typography.headlineMedium)
            if (pack == null) {
                Text("地図パックが未検証のため表示できません。")
            } else if (!isPlatformMapAvailable) {
                Text("地図を準備中（このプラットフォームでは今後対応予定）")
            } else {
                Box(Modifier.fillMaxWidth().height(300.dp)) {
                    PlatformMapView(
                        modifier = Modifier.fillMaxSize(),
                        styleUri = pack.styleUri,
                    )
                }
                Text("style: ${pack.styleUri}")
                Text("PMTiles: ${pack.pmtilesUri}")
            }
        }
    }
}

@Composable
private fun SafetyForm(
    onBack: () -> Unit,
    onSave: (SafetyState, Int, String, String) -> Unit,
) {
    var selected by remember { mutableStateOf(SafetyState.SAFE) }
    var companions by remember { mutableStateOf(0) }
    var location by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    Scaffold { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { OutlinedButton(onClick = onBack) { Text("戻る") } }
            item { Text("安否・避難状況", style = MaterialTheme.typography.headlineMedium) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SafetyState.entries) { option ->
                        if (selected == option) Button(onClick = { selected = option }) { Text(option.labelJa()) }
                        else OutlinedButton(onClick = { selected = option }) { Text(option.labelJa()) }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { companions = (companions - 1).coerceAtLeast(0) }) { Text("−") }
                    Text("同行者数\n$companions")
                    OutlinedButton(onClick = { companions = (companions + 1).coerceAtMost(99) }) { Text("＋") }
                }
            }
            item {
                OutlinedTextField(
                    location,
                    { location = it.take(100) },
                    label = { Text("おおまかな場所（任意）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    note,
                    { note = it.take(280) },
                    label = { Text("短いメモ（任意）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(
                    onClick = { onSave(selected, companions, location, note) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .semantics { contentDescription = "安否情報を保存する" },
                ) { Text("保存する") }
            }
        }
    }
}

@Composable
private fun SupplyForm(
    onBack: () -> Unit,
    onSave: (SupplyKind, Int, String, String, String?) -> Unit,
) {
    var selected by remember { mutableStateOf(SupplyKind.WATER) }
    var count by remember { mutableStateOf(1) }
    var location by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var other by remember { mutableStateOf("") }
    Scaffold { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { OutlinedButton(onClick = onBack) { Text("戻る") } }
            item { Text("不足物資", style = MaterialTheme.typography.headlineMedium) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SupplyKind.entries) { option ->
                        if (selected == option) Button(onClick = { selected = option }) { Text(option.labelJa()) }
                        else OutlinedButton(onClick = { selected = option }) { Text(option.labelJa()) }
                    }
                }
            }
            if (selected == SupplyKind.OTHER) {
                item {
                    OutlinedTextField(
                        other,
                        { other = it.take(50) },
                        label = { Text("物資名") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { count = (count - 1).coerceAtLeast(1) }) { Text("−") }
                    Text("必要数\n$count")
                    OutlinedButton(onClick = { count = (count + 1).coerceAtMost(9999) }) { Text("＋") }
                }
            }
            item {
                OutlinedTextField(
                    location,
                    { location = it.take(100) },
                    label = { Text("おおまかな場所（任意）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    note,
                    { note = it.take(280) },
                    label = { Text("短いメモ（任意）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(
                    onClick = {
                        onSave(selected, count, location, note, other.takeIf { selected == SupplyKind.OTHER })
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .semantics { contentDescription = "物資不足情報を保存する" },
                ) { Text("保存する") }
            }
        }
    }
}

@Composable
private fun RegionalList(
    messages: List<com.example.relay.domain.RelayMessage>,
    deviceId: String,
    receipts: (String) -> List<com.example.relay.domain.DeliveryReceipt>,
    onBack: () -> Unit,
) {
    Scaffold { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { OutlinedButton(onClick = onBack) { Text("戻る") } }
            item { Text("地域情報", style = MaterialTheme.typography.headlineMedium) }
            if (messages.isEmpty()) item { Text("保存されている情報はありません") }
            items(messages, key = { it.messageId }) { message ->
                val presentation = deriveDeliveryPresentation(receipts(message.messageId))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            if (message.messageType == MessageType.SAFETY) "安否情報" else "物資不足情報",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text("中継: ${message.hopCount}/${message.maxHopCount}")
                        Text(if (message.originDeviceId == deviceId) "自分が登録" else "他の端末から受信")
                        Text(deliveryPresentationLabel(presentation))
                    }
                }
            }
        }
    }
}
