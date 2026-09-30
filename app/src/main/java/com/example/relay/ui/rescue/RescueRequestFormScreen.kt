package com.example.relay.ui.rescue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.relay.rescue.RescueCondition
import com.example.relay.rescue.RescueRequestDraft
import com.example.relay.rescue.RescueSupportNeed

@Composable
internal fun RescueRequestFormScreen(
    state: RescueUiState,
    callbacks: RescueCallbacks,
    modifier: Modifier = Modifier,
) {
    val language = state.language
    RescuePage(
        title = if ((state.draft?.requestVersion ?: 1) > 1) language.text("救助要請を更新", "Update rescue request") else language.text("助けを求める", "Request help"),
        language = language,
        onToggleLanguage = callbacks::onToggleLanguage,
        modifier = modifier,
        onBack = { callbacks.onNavigate(RescueScreen.HOME) },
    ) { contentModifier ->
        val draft = state.draft
        if (draft == null) {
            Column(contentModifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(language.text("入力の準備ができませんでした", "The form could not be prepared."), style = MaterialTheme.typography.titleLarge)
                LargeActionButton(language.text("ホームへ戻る", "Return home")) { callbacks.onNavigate(RescueScreen.HOME) }
            }
            return@RescuePage
        }

        RescueScrollableColumn(contentModifier) {
            Text(language.text("人数と現在の状態だけで送れます。GPS位置は送信時に自動取得します。", "Only group size and current condition are required. GPS is captured automatically when you send."))
            PersonCountChooser(language, draft.personCount.coerceAtLeast(1)) {
                callbacks.onDraftChange(draft.copy(personCount = it))
            }

            SectionTitle(language.text("現在の状態（1つ以上）", "Current condition (select at least one)"))
            RescueCondition.entries.forEach { condition ->
                DraftOption(condition.label(language), condition in draft.conditions) { checked ->
                    val updated = if (checked) draft.conditions + condition else draft.conditions - condition
                    callbacks.onDraftChange(draft.copy(conditions = updated))
                }
            }

            SectionTitle(language.text("補足タグ（任意）", "Additional tags (optional)"))
            RescueSupportNeed.entries.forEach { need ->
                DraftOption(need.label(language), need in draft.supportNeeds) { checked ->
                    val updated = if (checked) draft.supportNeeds + need else draft.supportNeeds - need
                    callbacks.onDraftChange(draft.copy(supportNeeds = updated))
                }
            }
            DraftOption(language.text("高齢者がいる", "Older adult present"), draft.elderlyPresent) {
                callbacks.onDraftChange(draft.copy(elderlyPresent = it))
            }
            DraftOption(language.text("子どもがいる", "Child present"), draft.childrenPresent) {
                callbacks.onDraftChange(draft.copy(childrenPresent = it))
            }
            DraftOption(language.text("妊娠中の人がいる", "Pregnant person present"), draft.pregnantPresent) {
                callbacks.onDraftChange(draft.copy(pregnantPresent = it))
            }
            DraftOption(language.text("閉じ込め・倒壊・火災の危険", "Trapped, collapse, or fire risk"), draft.trapped || draft.fireOrCollapseRisk) {
                callbacks.onDraftChange(draft.copy(trapped = it, fireOrCollapseRisk = it))
            }

            SectionTitle(language.text("場所の補足（任意）", "Location details (optional)"))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(language.text("GPS位置を自動添付", "GPS is attached automatically"), style = MaterialTheme.typography.titleMedium)
                    Text(language.text("位置が古い場合は取得時刻も一緒に避難所へ伝わります。", "The capture time is included so the shelter can identify an older location."))
                }
            }
            OutlinedTextField(
                value = draft.location?.description.orEmpty(),
                onValueChange = { description ->
                    callbacks.onDraftChange(
                        draft.copy(
                            location = (draft.location ?: com.example.relay.rescue.RescueLocation())
                                .copy(description = description.take(256)),
                        ),
                    )
                },
                label = { Text(language.text("建物名・階・目印", "Building, floor, or landmark")) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            OutlinedTextField(
                value = draft.freeText,
                onValueChange = { callbacks.onDraftChange(draft.copy(freeText = it.take(2_000))) },
                label = { Text(language.text("ほかに伝えたいこと", "Anything else to tell responders")) },
                supportingText = { Text(language.text("任意・2000文字まで", "Optional, up to 2,000 characters")) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )

            state.formMessage?.let { message ->
                Card(Modifier.fillMaxWidth()) { Text(message, Modifier.padding(16.dp)) }
            }
            Button(
                onClick = callbacks::onSubmitRequest,
                enabled = !state.isRequestSubmitting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            ) {
                Text(if (state.isRequestSubmitting) language.text("GPSを確認して保存中…", "Checking GPS and saving…") else language.text("この内容で救助を依頼", "Send rescue request"))
            }
            Text(language.text("内容と正確な位置は暗号化され、中継する人には見えません。", "Details and exact location are encrypted and hidden from people relaying the request."))
        }
    }
}

@Composable
internal fun RescueScrollableColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun PersonCountChooser(language: RescueLanguage, value: Int, onChange: (Int) -> Unit) {
    SectionTitle(language.text("助けが必要な人数", "People who need help"))
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { onChange((value - 1).coerceAtLeast(1)) }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text("−")
            }
            Text(language.text("${value}人", "$value people"), style = MaterialTheme.typography.headlineMedium)
            Button(onClick = { onChange((value + 1).coerceAtMost(1_000)) }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text("＋")
            }
        }
    }
}

@Composable
private fun DraftOption(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun RescueCondition.label(language: RescueLanguage): String = when (this) {
    RescueCondition.LIFE_THREATENING -> language.text("命の危険がある", "Life-threatening danger")
    RescueCondition.INJURED_OR_UNWELL -> language.text("けが・体調不良", "Injured or unwell")
    RescueCondition.MOBILITY_IMPAIRED -> language.text("自力で移動できない", "Unable to move without help")
    RescueCondition.SUPPORT_NEEDED -> language.text("生活・医療の支援が必要", "Daily living or medical support needed")
}

private fun RescueSupportNeed.label(language: RescueLanguage): String = when (this) {
    RescueSupportNeed.WATER -> language.text("水", "Water")
    RescueSupportNeed.FOOD -> language.text("食料", "Food")
    RescueSupportNeed.MEDICINE -> language.text("薬・医療", "Medicine or medical care")
    RescueSupportNeed.RESCUE_TEAM -> language.text("救助隊", "Rescue team")
    RescueSupportNeed.TRANSPORT -> language.text("移動支援", "Transport assistance")
}
