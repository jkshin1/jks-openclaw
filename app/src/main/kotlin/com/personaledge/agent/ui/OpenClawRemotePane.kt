package com.personaledge.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.personaledge.agent.ChatEntry
import com.personaledge.agent.ChatRole
import com.personaledge.agent.OpenClawGatewayUiState
import com.personaledge.agent.OpenClawRemoteUiState

internal data class OpenClawRemoteActions(
    val select: (Boolean) -> Unit = {},
    val configure: (String, String, String) -> Unit = { _, _, _ -> },
    val connect: () -> Unit = {},
    val disconnect: () -> Unit = {},
    val forget: () -> Unit = {},
    val updatePrompt: (String) -> Unit = {},
    val send: () -> Unit = {},
    val cancel: () -> Unit = {},
    val loadContext: () -> Unit = {},
    val selectContext: (String, Boolean) -> Unit = { _, _ -> },
    val finishContextSelection: () -> Unit = {},
    val clearContext: () -> Unit = {},
    val readHealth: () -> Unit = {},
)

/**
 * The remote turn rows the shared transcript does not hold yet.
 *
 * A stored row is rendered once, from the conversation itself. The live pair is added only while
 * it is still in flight or when its write did not land, so nothing appears twice and a storage
 * failure still leaves the owner looking at their own question and answer.
 */
internal object OpenClawRemoteTranscriptPolicy {
    const val LIVE_QUESTION_ID = "openclaw-live-question"
    const val LIVE_ANSWER_ID = "openclaw-live-answer"

    fun liveEntries(state: OpenClawRemoteUiState): List<ChatEntry> = buildList {
        if (state.sentPrompt.isNotEmpty() && !state.questionStored) {
            add(ChatEntry(LIVE_QUESTION_ID, ChatRole.USER, state.sentPrompt))
        }
        // An empty in-flight answer adds no bubble: the remote engine has no thought channel to
        // disclose, and the progress bar already says the run is live.
        if (state.answer.isNotEmpty() && !state.answerStored) {
            add(ChatEntry(LIVE_ANSWER_ID, ChatRole.ASSISTANT, state.answer))
        }
    }
}

@Composable
internal fun AgentModeSelector(
    remote: OpenClawRemoteUiState,
    localBusy: Boolean,
    actions: OpenClawRemoteActions,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !remote.selected,
            onClick = { actions.select(false) },
            label = { Text("휴대폰 · 로컬") },
            enabled = !localBusy && !remote.running && !remote.busy,
        )
        FilterChip(
            selected = remote.selected,
            onClick = { actions.select(true) },
            label = { Text("Mac · 원격") },
            enabled = !localBusy && !remote.running && !remote.busy,
        )
    }
}

@Composable
internal fun OpenClawRemotePane(
    state: OpenClawRemoteUiState,
    actions: OpenClawRemoteActions,
    messages: List<ChatEntry> = emptyList(),
) {
    // Credentials and pending consent deliberately do not use rememberSaveable.
    var configure by remember { mutableStateOf(false) }
    var connectionConsent by remember { mutableStateOf(false) }
    var forgetConsent by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        // A lifecycle-aware StateFlow collector can conflate the stopped false state with the
        // next started true state. Clear pending consent at the lifecycle event itself too.
        configure = false
        connectionConsent = false
        forgetConsent = false
        actions.clearContext()
    }
    LaunchedEffect(state.foreground) {
        if (!state.foreground) {
            configure = false
            connectionConsent = false
            forgetConsent = false
        }
    }

    Column(
        Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = when (state.connection) {
                    OpenClawGatewayUiState.DISCONNECTED -> "연결 해제"
                    OpenClawGatewayUiState.CONNECTING -> "Mac 연결 중"
                    OpenClawGatewayUiState.CONNECTED -> "Mac 연결됨"
                    OpenClawGatewayUiState.PAIRING_OR_AUTH_REQUIRED -> "기기 승인 또는 인증 필요"
                    OpenClawGatewayUiState.DEGRADED -> "연결 확인 필요"
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(top = 12.dp),
            )
            TextButton(onClick = { configure = true }, enabled = !state.running && !state.busy) {
                Text("연결 설정")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { connectionConsent = true },
                enabled = state.configured && state.foreground && !state.busy && !state.running &&
                    state.connection != OpenClawGatewayUiState.CONNECTED &&
                    state.connection != OpenClawGatewayUiState.CONNECTING,
            ) { Text("연결") }
            OutlinedButton(onClick = actions.disconnect, enabled = state.connection != OpenClawGatewayUiState.DISCONNECTED) {
                Text("연결 해제")
            }
        }
        OutlinedButton(
            onClick = actions.readHealth,
            enabled = state.foreground && state.connection == OpenClawGatewayUiState.CONNECTED &&
                state.healthSupported && !state.healthBusy && !state.running && !state.busy && !state.contextBusy,
        ) { Text("Mac 상태 확인") }
        if (state.connection == OpenClawGatewayUiState.CONNECTED && !state.healthSupported) {
            Text("이 Mac에서는 상태 조회 기능을 제공하지 않습니다.", style = MaterialTheme.typography.bodySmall)
        }
        if (state.healthNotice.isNotEmpty()) Text(state.healthNotice, style = MaterialTheme.typography.bodySmall)
        Text(state.notice, style = MaterialTheme.typography.bodySmall)
        if (state.busy || state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        val transcript = messages + OpenClawRemoteTranscriptPolicy.liveEntries(state)
        // Two visible items of slack, like the local screen: appending must not read as the owner
        // having scrolled away, and a deliberate scroll back must not be yanked to the newest row.
        val nearBottom by remember {
            derivedStateOf {
                val layout = listState.layoutInfo
                val last = layout.visibleItemsInfo.lastOrNull()
                last == null || last.index >= layout.totalItemsCount - 2
            }
        }
        val streamScrollRevision = ChatPresentationPolicy.autoScrollRevision(
            transcript.lastOrNull()?.text.orEmpty(),
        )
        LaunchedEffect(transcript.size, transcript.lastOrNull()?.id, streamScrollRevision) {
            if (transcript.isNotEmpty() && nearBottom) listState.scrollToItem(transcript.lastIndex)
        }
        if (transcript.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "질문과 답변은 로컬 모델과 같은 대화에 저장되고 대화 기록에서 함께 보입니다. " +
                        "이전 대화와 장기 기억은 참고 자료로 직접 선택할 때만 함께 보내며, " +
                        "일정, 알림, 사진, 음성은 조회하지 않습니다. " +
                        "각 질문은 별도 실행이며 외부 모델 비용이 발생할 수 있습니다. " +
                        "앱을 떠나면 연결을 닫지만 이미 시작한 실행은 계속될 수 있습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            // The same transcript composable the local screen uses: one conversation, two engines.
            ChatTranscript(
                messages = transcript,
                listState = listState,
                turnActive = state.running,
                activeReasoning = null,
                suggestions = emptyList(),
                onSuggestion = {},
                recoveryActionsEnabled = false,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = state.prompt,
            onValueChange = actions.updatePrompt,
            label = { Text("Mac에 보낼 질문") },
            supportingText = { Text("텍스트 최대 8 KiB · 보낸 질문과 답변은 이 대화에 저장됩니다") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.running,
            maxLines = 4,
        )
        if (!state.running) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.loadContext, enabled = state.canSend) {
                    Text(if (state.selectedContextKeys.isEmpty()) "참고 자료 선택" else "참고 자료 ${state.selectedContextKeys.size}개 선택됨")
                }
                if (state.selectedContextKeys.isNotEmpty()) {
                    TextButton(onClick = actions.clearContext, enabled = !state.contextBusy) { Text("선택 해제") }
                }
            }
            if (state.contextBusy) Text("참고 자료를 확인하는 중입니다.", style = MaterialTheme.typography.bodySmall)
        }
        if (state.running) {
            Button(onClick = actions.cancel, enabled = !state.cancellationRequested, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.cancellationRequested) "취소 확인 중" else "원격 실행 취소")
            }
        } else {
            // Remote mode is itself the owner's decision to use the external model, so this tap
            // sends. Selected quotes are still revalidated before the question leaves the device.
            Button(onClick = actions.send, enabled = state.canSend, modifier = Modifier.fillMaxWidth()) {
                Text("전송")
            }
        }
    }

    if (configure) {
        RemoteConfigurationDialog(state = state, onDismiss = { configure = false }, onSave = { endpoint, token, pin ->
            actions.configure(endpoint, token, pin)
            configure = false
        }, onForget = {
            configure = false
            forgetConsent = true
        })
    }
    if (connectionConsent) {
        AlertDialog(
            onDismissRequest = { connectionConsent = false },
            title = { Text("이 Mac에 연결할까요?") },
            text = { Text("설정한 Mac에 기기 인증 정보를 보내고 앱이 보이는 동안 연결합니다. " +
                "연결 자체로 모델을 실행하지 않지만, 연결한 뒤에는 전송 버튼을 누를 때마다 " +
                "추가 확인 없이 그 질문이 Mac과 외부 모델 제공업체로 전송되고 비용이 발생할 수 있습니다.") },
            confirmButton = { TextButton(onClick = { connectionConsent = false; actions.connect() }) { Text("동의하고 연결") } },
            dismissButton = { TextButton(onClick = { connectionConsent = false }) { Text("취소") } },
        )
    }
    if (forgetConsent) {
        AlertDialog(
            onDismissRequest = { forgetConsent = false },
            title = { Text("원격 연결 정보를 지울까요?") },
            text = { Text("이 휴대폰에 저장한 Gateway 토큰, 기기 키, Mac 주소를 삭제합니다. Mac의 기기 등록은 별도로 해제해야 합니다.") },
            confirmButton = { TextButton(onClick = { forgetConsent = false; actions.forget() }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { forgetConsent = false }) { Text("취소") } },
        )
    }
    if (state.contextPickerOpen) {
        AlertDialog(
            onDismissRequest = actions.clearContext,
            title = { Text("이번 질문의 참고 자료 선택") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("선택한 원문은 Mac과 외부 모델 제공업체에 공개됩니다. 민감한 정보가 있는지 확인하세요. " +
                        "이 선택은 이번 질문에만 적용되며 로컬 저장 내용을 바꾸지 않습니다.")
                    Text("현재 로컬 대화의 최근 8개 메시지와 요약, 질문에 관련된 장기 기억 최대 4개를 표시합니다.")
                    if (!state.contextMemoryAllowed) Text("장기 기억 사용이 꺼져 있어 기억을 조회하지 않았습니다.")
                    if (state.contextItems.isEmpty()) Text("선택할 관련 자료가 없습니다. 현재 질문만 보낼 수 있습니다.")
                    state.contextItems.forEach { item ->
                        Row(Modifier.fillMaxWidth().toggleable(
                            value = item.key in state.selectedContextKeys,
                            role = Role.Checkbox,
                            onValueChange = { actions.selectContext(item.key, it) },
                        )) {
                            Checkbox(checked = item.key in state.selectedContextKeys, onCheckedChange = null)
                            Column {
                                Text(item.label, style = MaterialTheme.typography.labelLarge)
                                Text(item.text)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = actions.finishContextSelection) { Text("선택 적용") } },
            dismissButton = { TextButton(onClick = actions.clearContext) { Text("취소") } },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        )
    }
}

@Composable
private fun RemoteConfigurationDialog(
    state: OpenClawRemoteUiState,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onForget: () -> Unit,
) {
    var endpoint by remember { mutableStateOf(state.endpointUrl) }
    var token by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf(state.certificatePin) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        token = ""
        onDismiss()
    }
    AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = { token = ""; onDismiss() },
        title = { Text("Mac 원격 연결") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("휴대폰과 Mac의 Tailscale VPN을 켜고 Mac의 HTTPS 주소를 입력하세요. " +
                    "Gateway 토큰은 Android Keystore로 암호화해 저장하며 다시 표시하지 않습니다. " +
                    "Mac 주소를 바꾸면 이 휴대폰의 기기 키를 새로 만들어 다시 등록합니다.")
                OutlinedTextField(
                    value = endpoint, onValueChange = { if (it.length <= 2_048) endpoint = it },
                    label = { Text("HTTPS 또는 WSS 주소") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = token, onValueChange = { if (it.length <= 2_910) token = it },
                    label = { Text("Gateway 토큰") },
                    supportingText = { Text("Mac의 Gateway 토큰을 입력하세요. OpenRouter API 키는 사용하지 않습니다.") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                )
                OutlinedTextField(
                    value = pin, onValueChange = { if (it.length <= 64) pin = it },
                    label = { Text("인증서 SHA-256 지문 · 선택") },
                    supportingText = { Text("비우면 Android의 기본 인증서 검증을 사용합니다.") },
                    singleLine = true,
                )
                if (state.configured) TextButton(onClick = { token = ""; onForget() }) { Text("저장한 연결 정보 삭제") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val submittedToken = token
                token = ""
                onSave(endpoint, submittedToken, pin)
            }, enabled = endpoint.isNotBlank() && token.isNotBlank()) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = { token = ""; onDismiss() }) { Text("취소") } },
    )
}
