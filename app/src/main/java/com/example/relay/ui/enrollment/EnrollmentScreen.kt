package com.example.relay.ui.enrollment

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.relay.gateway.GatewayEnrollmentCodec
import com.example.relay.gateway.GatewayEnrollmentToken
import com.example.relay.gateway.enrollment.EnrollmentUiState

/**
 * Main enrollment screen that routes between the gateway list and input/scan sub-screens.
 */
@Composable
fun EnrollmentScreen(
    viewModel: EnrollmentViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show transient messages
    LaunchedEffect(state.userMessage) {
        state.userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.screen) {
                EnrollmentFlowScreen.GATEWAY_LIST -> EnrolledGatewayList(
                    gateways = state.enrolledGateways,
                    onAdd = viewModel::navigateToInput,
                    onRemove = viewModel::removeGateway,
                    onBack = onBack,
                )
                EnrollmentFlowScreen.INPUT -> EnrollmentInputScreen(
                    enrollmentState = state.enrollmentState,
                    cameraPermissionDenied = state.cameraPermissionDenied,
                    onPayloadScanned = viewModel::processPayload,
                    onConfirm = { viewModel.confirmEnrollment(allowRotation = false) },
                    onConfirmRotation = { viewModel.confirmEnrollment(allowRotation = true) },
                    onCancel = viewModel::cancelEnrollment,
                    onCameraPermissionDenied = viewModel::onCameraPermissionDenied,
                    onBack = viewModel::navigateToList,
                    onSuccessAcknowledged = viewModel::acknowledgeSuccess,
                )
            }
        }
    }
}

// --- Enrolled Gateway List ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnrolledGatewayList(
    gateways: List<GatewayEnrollmentToken>,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf<GatewayEnrollmentToken?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gateway登録") },
                navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Button(
                    onClick = onAdd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .semantics { contentDescription = "Gateway登録を追加" },
                ) { Text("QRスキャンまたは貼り付けで追加") }
            }

            if (gateways.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "登録されたGatewayはありません。\nQRコードをスキャンするか、登録コードを貼り付けて追加してください。",
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                items(gateways, key = { it.gatewayId }) { token ->
                    GatewayCard(
                        token = token,
                        onRemove = { confirmRemove = token },
                    )
                }
            }
        }
    }

    // Confirm removal dialog
    confirmRemove?.let { token ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Gateway登録を解除") },
            text = { Text("「${token.gatewayId}」の登録を解除しますか？\n再登録にはQRスキャンが必要です。") },
            confirmButton = {
                Button(onClick = {
                    onRemove(token.gatewayId)
                    confirmRemove = null
                }) { Text("解除") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun GatewayCard(
    token: GatewayEnrollmentToken,
    onRemove: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Gateway: ${token.gatewayId}" },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(token.gatewayId, style = MaterialTheme.typography.titleMedium)
            Text("Shelter: ${token.shelterId}", style = MaterialTheme.typography.bodySmall)
            Text("${token.scheme}://${token.host}:${token.port}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Fingerprint: ${GatewayEnrollmentCodec.formatManualFingerprint(token.manifestFingerprint)}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRemove,
                modifier = Modifier.semantics { contentDescription = "${token.gatewayId}の登録解除" },
            ) { Text("登録解除") }
        }
    }
}

// --- Enrollment Input Screen (QR + paste) ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnrollmentInputScreen(
    enrollmentState: EnrollmentUiState,
    cameraPermissionDenied: Boolean,
    onPayloadScanned: (String) -> Unit,
    onConfirm: () -> Unit,
    onConfirmRotation: () -> Unit,
    onCancel: () -> Unit,
    onCameraPermissionDenied: () -> Unit,
    onBack: () -> Unit,
    onSuccessAcknowledged: () -> Unit,
) {
    val context = LocalContext.current
    var pasteText by rememberSaveable { mutableStateOf("") }
    var showCamera by rememberSaveable { mutableStateOf(false) }
    val hasCameraPermission = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showCamera = true
        } else {
            onCameraPermissionDenied()
        }
    }

    // Show confirmation/conflict/success dialogs based on enrollment state
    when (enrollmentState) {
        is EnrollmentUiState.PendingConfirmation -> {
            if (enrollmentState.isConflict) {
                EnrollmentConflictDialog(
                    pending = enrollmentState,
                    onConfirmRotation = onConfirmRotation,
                    onCancel = onCancel,
                )
            } else {
                EnrollmentConfirmationDialog(
                    pending = enrollmentState,
                    onConfirm = onConfirm,
                    onCancel = onCancel,
                )
            }
        }
        is EnrollmentUiState.Success -> {
            AlertDialog(
                onDismissRequest = onSuccessAcknowledged,
                title = { Text(if (enrollmentState.wasRotation) "Gateway更新完了" else "Gateway登録完了") },
                text = {
                    Text("「${enrollmentState.token.gatewayId}」を${if (enrollmentState.wasRotation) "更新" else "登録"}しました。")
                },
                confirmButton = { Button(onClick = onSuccessAcknowledged) { Text("OK") } },
            )
        }
        is EnrollmentUiState.Error -> {
            AlertDialog(
                onDismissRequest = onCancel,
                title = { Text("登録エラー") },
                text = { Text(enrollmentState.message) },
                confirmButton = { Button(onClick = onCancel) { Text("OK") } },
            )
        }
        EnrollmentUiState.Idle -> { /* no dialog */ }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gateway追加") },
                navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Camera section
            if (showCamera && !cameraPermissionDenied) {
                Card(Modifier.fillMaxWidth().height(280.dp)) {
                    CameraQrScanner(
                        onQrDetected = { payload ->
                            showCamera = false
                            onPayloadScanned(payload)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                TextButton(onClick = { showCamera = false }) { Text("カメラを閉じる") }
            } else if (!cameraPermissionDenied) {
                Button(
                    onClick = {
                        if (hasCameraPermission) showCamera = true
                        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                        .semantics { contentDescription = "カメラでQRスキャン" },
                ) { Text("カメラでQRをスキャン") }
            } else {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Text(
                        "カメラの権限が拒否されました。\n下の入力欄に登録コードを貼り付けてください。",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // Paste input section
            Text("または登録コードを貼り付け", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = pasteText,
                onValueChange = { pasteText = it },
                label = { Text("登録コード") },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "登録コード入力欄" },
                maxLines = 4,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (pasteText.isNotBlank()) {
                            onPayloadScanned(pasteText)
                            pasteText = ""
                        }
                    },
                    enabled = pasteText.isNotBlank(),
                    modifier = Modifier.semantics { contentDescription = "貼り付けコードで登録" },
                ) { Text("登録") }
                OutlinedButton(onClick = onBack) { Text("キャンセル") }
            }
        }
    }
}

// --- Confirmation Dialog ---

@Composable
private fun EnrollmentConfirmationDialog(
    pending: EnrollmentUiState.PendingConfirmation,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Gateway登録の確認") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("以下のGatewayを登録しますか？")
                Spacer(Modifier.height(4.dp))
                DetailRow("Gateway ID", pending.token.gatewayId)
                DetailRow("Shelter ID", pending.token.shelterId)
                DetailRow("接続先", "${pending.token.scheme}://${pending.token.host}:${pending.token.port}")
                Spacer(Modifier.height(4.dp))
                Text("Manifest Fingerprint:", style = MaterialTheme.typography.labelMedium)
                Text(
                    pending.formattedFingerprint,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "このfingerprintがGateway管理者から提供されたものと一致することを確認してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.semantics { contentDescription = "登録を確認" },
            ) { Text("登録する") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("キャンセル") } },
    )
}

// --- Conflict/Rotation Dialog ---

@Composable
private fun EnrollmentConflictDialog(
    pending: EnrollmentUiState.PendingConfirmation,
    onConfirmRotation: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                "⚠ Gateway identity変更の警告",
                color = MaterialTheme.colorScheme.error,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "「${pending.token.gatewayId}」は既に別のidentityで登録されています。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "これはGateway鍵のrotationか、なりすましの可能性があります。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Text("新しいFingerprint:", style = MaterialTheme.typography.labelMedium)
                Text(
                    pending.formattedFingerprint,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                pending.existingToken?.let { existing ->
                    Spacer(Modifier.height(4.dp))
                    Text("現在登録中のFingerprint:", style = MaterialTheme.typography.labelMedium)
                    Text(
                        GatewayEnrollmentCodec.formatManualFingerprint(existing.manifestFingerprint),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Gateway管理者から鍵変更の連絡を受けている場合のみ「置換する」を選択してください。" +
                        "確認が取れない場合は「キャンセル」してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmRotation,
                modifier = Modifier.semantics { contentDescription = "Gateway identityを置換する" },
            ) { Text("置換する") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("キャンセル") } },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.alignByBaseline())
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.alignByBaseline())
    }
}
