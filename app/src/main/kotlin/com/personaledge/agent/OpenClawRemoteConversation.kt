package com.personaledge.agent

import com.personaledge.core.agent.RemoteAgentCancelResult
import com.personaledge.core.agent.RemoteAgentEvent
import com.personaledge.core.agent.RemoteAgentGateway
import com.personaledge.core.agent.RemoteAgentIdempotencyKey
import com.personaledge.core.agent.RemoteAgentPrompt
import com.personaledge.core.agent.RemoteAgentRunId
import com.personaledge.core.agent.RemoteAgentRunLimits
import com.personaledge.core.agent.RemoteAgentRunPhase
import com.personaledge.core.agent.RemoteAgentSessionId
import com.personaledge.core.agent.RemoteAgentStartRequest
import com.personaledge.core.agent.RemoteAgentStartResult
import com.personaledge.core.agent.RemoteAgentWaitResult
import com.personaledge.core.agent.RemoteAgentWaitTimeout
import com.personaledge.core.data.OpenClawGatewayConnectionPolicy
import com.personaledge.core.data.OpenClawGatewaySettings
import com.personaledge.core.data.OpenClawGatewayTrustMode
import com.personaledge.core.data.SecretKeyName
import com.personaledge.core.openclaw.GatewayEndpoint
import com.personaledge.core.openclaw.OpenClawAuthToken
import com.personaledge.core.openclaw.OpenClawHealthReadResult
import com.personaledge.core.openclaw.OpenClawHealthSnapshot
import com.personaledge.core.openclaw.OpenClawSecretRecordCodec
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * The live remote turn. Its text is process memory; the durable copy is the ordinary conversation.
 *
 * [questionStored] and [answerStored] say whether this turn's two messages already reached the
 * same Room transcript the local model writes to. They exist so the screen can render the stored
 * rows once, and keep showing the live text when a write did not land.
 */
data class OpenClawRemoteUiState(
    val selected: Boolean = false,
    val foreground: Boolean = false,
    val endpointUrl: String = "",
    val certificatePin: String = "",
    val configured: Boolean = false,
    val connection: OpenClawGatewayUiState = OpenClawGatewayUiState.DISCONNECTED,
    val busy: Boolean = false,
    val running: Boolean = false,
    val cancellationRequested: Boolean = false,
    val prompt: String = "",
    val sentPrompt: String = "",
    val answer: String = "",
    val questionStored: Boolean = false,
    val answerStored: Boolean = false,
    val contextItems: List<OpenClawRemoteContextItem> = emptyList(),
    val selectedContextKeys: Set<String> = emptySet(),
    val contextPickerOpen: Boolean = false,
    val contextBusy: Boolean = false,
    val contextMemoryAllowed: Boolean = false,
    val healthSupported: Boolean = false,
    val healthBusy: Boolean = false,
    val healthNotice: String = "",
    val notice: String = "원격 질문과 답변은 로컬 모델과 같은 대화에 저장됩니다. " +
        "로컬 대화와 장기 기억은 참고 자료로 직접 선택할 때만 함께 전송합니다.",
) {
    val canSend: Boolean get() = selected && foreground && !busy && !running && !contextBusy && !healthBusy &&
        connection == OpenClawGatewayUiState.CONNECTED && RemoteAgentPrompt.create(prompt) != null

    override fun toString(): String = "OpenClawRemoteUiState(selected=$selected, " +
        "connection=$connection, busy=$busy, running=$running, content=<redacted>)"
}

/** Configuration never transports a credential through observable UI state or object rendering. */
internal class OpenClawRemoteConfiguration private constructor(
    val endpoint: GatewayEndpoint,
    val policy: OpenClawGatewayConnectionPolicy,
    val credentialRecord: String,
) {
    override fun toString(): String = "OpenClawRemoteConfiguration(<redacted>)"

    companion object {
        fun parse(endpointInput: String, tokenInput: String, pinInput: String): OpenClawRemoteConfiguration? {
            val input = endpointInput.trim()
            val webSocketUrl = if (input.startsWith("https://", ignoreCase = true)) {
                "wss://" + input.substring(8)
            } else input
            val endpoint = GatewayEndpoint.parseRelease(webSocketUrl) ?: return null
            val token = OpenClawAuthToken.parse(tokenInput.trim()) ?: return null
            val pin = pinInput.trim().ifEmpty { null }
            return runCatching {
                OpenClawRemoteConfiguration(
                    endpoint = endpoint,
                    policy = OpenClawGatewayConnectionPolicy(
                        endpointUrl = endpoint.url,
                        trustMode = if (pin == null) OpenClawGatewayTrustMode.SYSTEM
                        else OpenClawGatewayTrustMode.PINNED_CERT_SHA256,
                        leafCertificateDerSha256 = pin,
                    ),
                    credentialRecord = OpenClawSecretRecordCodec.encodeGatewayCredential(endpoint, token),
                )
            }.getOrNull()
        }
    }
}

internal interface OpenClawRemoteBackend : AutoCloseable {
    val settings: Flow<OpenClawGatewaySettings>
    val connection: StateFlow<OpenClawGatewayUiState>
    fun setForeground(visible: Boolean)
    fun activeGateway(): RemoteAgentGateway?
    fun setEnabled(enabled: Boolean): Deferred<OwnerConsentMutationOutcome>
    suspend fun save(configuration: OpenClawRemoteConfiguration)
    suspend fun forget()
    val supportsHealthSnapshot: Boolean get() = false
    suspend fun readHealthSnapshot(): OpenClawRemoteHealthResult = OpenClawRemoteHealthResult.Unsupported
}

internal sealed interface OpenClawRemoteHealthResult {
    data class Observation(val observedAtEpochMillis: Long, val gatewayHealthy: Boolean,
        val dockerHealthy: Boolean, val policyValid: Boolean, val secretsClean: Boolean) : OpenClawRemoteHealthResult
    data object Unsupported : OpenClawRemoteHealthResult
    data object Unavailable : OpenClawRemoteHealthResult
    data object Invalid : OpenClawRemoteHealthResult
}

/**
 * The one durable transcript for both engines.
 *
 * The remote screen writes the owner's typed question and the received answer through this seam
 * into the same conversation the local model appends to, so a restart, a history switch, and a
 * later local turn all see one thread. Selected context quotes are not stored again here: they are
 * already owner-visible rows, and re-storing them would duplicate memory text into the transcript.
 */
internal interface OpenClawRemoteTranscript {
    /** Returns the conversation the question was stored in, or null when storage is unavailable. */
    suspend fun recordQuestion(question: String): String?

    /** Stores the answer in the exact conversation [recordQuestion] returned. */
    suspend fun recordAnswer(conversationId: String, answer: String): Boolean
}

internal object OpenClawRemoteIdentityPolicy {
    /** Retain pairing for a token or trust repair, but never reuse identity across origins. */
    fun resetForEndpoint(previousEndpointUrl: String?, nextEndpoint: GatewayEndpoint): Boolean =
        previousEndpointUrl?.let(GatewayEndpoint::parseRelease) != nextEndpoint
}

internal class AppOpenClawRemoteBackend(
    private val container: AppContainer,
    scope: CoroutineScope,
) : OpenClawRemoteBackend {
    private val coordinator = OpenClawGatewayCoordinator(
        settingsRepository = container.settings,
        ownerConsentInterlock = container.ownerConsentInterlock,
        revocationGate = container.openClawGatewayConsentManager,
        secretVault = container.secretVault,
        parentScope = scope,
        clientVersion = BuildConfig.VERSION_NAME,
    )
    override val settings = container.settings.settings.map { it.openClawGateway }
    override val connection = coordinator.state
    override fun setForeground(visible: Boolean) = coordinator.setForeground(visible)
    override fun activeGateway(): RemoteAgentGateway? = coordinator.activeGateway()
    override val supportsHealthSnapshot: Boolean get() = coordinator.supportsHealthSnapshot()
    override suspend fun readHealthSnapshot(): OpenClawRemoteHealthResult = when (val result = coordinator.readHealthSnapshot()) {
        is OpenClawHealthReadResult.Success -> result.snapshot.let { snapshot ->
            OpenClawRemoteHealthResult.Observation(snapshot.observedAtEpochMillis, snapshot.gatewayHealthy,
                snapshot.dockerHealthy, snapshot.policyValid, snapshot.secretsClean)
        }
        OpenClawHealthReadResult.Unsupported -> OpenClawRemoteHealthResult.Unsupported
        OpenClawHealthReadResult.InvalidSnapshot -> OpenClawRemoteHealthResult.Invalid
        OpenClawHealthReadResult.NotConnected, OpenClawHealthReadResult.Unavailable -> OpenClawRemoteHealthResult.Unavailable
    }
    override fun setEnabled(enabled: Boolean) = container.openClawGatewayConsentManager.setEnabled(enabled)

    override suspend fun save(configuration: OpenClawRemoteConfiguration) {
        // The caller has durably disabled and closed the coordinator before replacing either record.
        val previousEndpointUrl = container.settings.settings.first().openClawGateway.endpointUrl
        if (OpenClawRemoteIdentityPolicy.resetForEndpoint(previousEndpointUrl, configuration.endpoint)) {
            container.secretVault.remove(SecretKeyName.OPENCLAW_DEVICE_IDENTITY_RECORD)
        }
        container.settings.setOpenClawGatewayConnectionPolicy(configuration.policy)
        container.secretVault.store(
            SecretKeyName.OPENCLAW_GATEWAY_CREDENTIAL_RECORD,
            configuration.credentialRecord,
        )
        coordinator.credentialsChanged()
    }

    override suspend fun forget() {
        container.secretVault.remove(SecretKeyName.OPENCLAW_GATEWAY_CREDENTIAL_RECORD)
        container.secretVault.remove(SecretKeyName.OPENCLAW_DEVICE_IDENTITY_RECORD)
        container.settings.setOpenClawGatewayConnectionPolicy(OpenClawGatewayConnectionPolicy())
        coordinator.credentialsChanged()
    }

    override fun close() = coordinator.close()
}

/**
 * Main-thread UI owner. One send is one start, with no automatic retries.
 *
 * Choosing the remote engine is the owner's standing decision to use it, so the send control
 * dispatches the visible question directly. Everything a send still has to prove stays: the
 * question and any selected quotes are exactly what the owner typed and ticked, the selection is
 * revalidated against current storage and memory consent immediately before dispatch, and a
 * connection the owner has not consented to cannot carry a question at all.
 */
internal class OpenClawRemoteConversation(
    private val backend: OpenClawRemoteBackend,
    private val scope: CoroutineScope,
    private val contextSource: OpenClawRemoteContextSource? = null,
    private val transcript: OpenClawRemoteTranscript? = null,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val mutableState = MutableStateFlow(OpenClawRemoteUiState())
    val state = mutableState.asStateFlow()
    private var epoch = 0L
    private var runJob: Job? = null
    private var runId: RemoteAgentRunId? = null
    private var runGateway: RemoteAgentGateway? = null
    private var cancelJob: Job? = null
    private var connectionRequested = false
    private var closed = false
    private val contextGeneration = AtomicLong()
    private val contextLock = Any()
    private var contextSnapshot: OpenClawRemoteContextSnapshot? = null
    private var contextSubscription: AutoCloseable? = null
    private var runConversationId: String? = null
    private var cancelPending = false
    private var healthJob: Job? = null
    private var healthExpiryJob: Job? = null
    private var healthGeneration = 0L

    init {
        scope.launch {
            backend.settings.catch { failure ->
                if (failure is CancellationException) throw failure
                emit(OpenClawGatewaySettings())
            }.collect { settings ->
                if (state.value.endpointUrl != settings.endpointUrl.orEmpty() ||
                    state.value.certificatePin != settings.leafCertificateDerSha256.orEmpty()
                ) invalidateContext()
                mutableState.update {
                    it.copy(
                        endpointUrl = settings.endpointUrl.orEmpty(),
                        certificatePin = settings.leafCertificateDerSha256.orEmpty(),
                        configured = settings.endpointUrl != null,
                    )
                }
            }
        }
        scope.launch {
            backend.connection.collect { connection ->
                if (connection != OpenClawGatewayUiState.CONNECTED) {
                    invalidateContext()
                    clearHealth()
                }
                mutableState.update { it.copy(connection = connection,
                    healthSupported = connection == OpenClawGatewayUiState.CONNECTED && backend.supportsHealthSnapshot) }
                if (state.value.running && connection != OpenClawGatewayUiState.CONNECTED) {
                    disconnect("연결이 끊겼습니다. 원격 실행의 종료와 과금 여부를 확인할 수 없습니다.")
                }
            }
        }
    }

    fun selectRemote(selected: Boolean) {
        if (closed || state.value.running || state.value.busy) return
        if (!selected) disconnect("로컬 모델을 선택했습니다.")
        mutableState.update { it.copy(selected = selected) }
    }

    fun setForeground(visible: Boolean) {
        if (closed) return
        mutableState.update { it.copy(foreground = visible) }
        if (!visible) {
            disconnect(
                if (state.value.running) "화면을 떠나 연결을 닫았습니다. 원격 취소 완료는 확인되지 않았습니다."
                else "앱으로 돌아오면 연결 버튼을 눌러 다시 연결하세요.",
            )
        }
    }

    fun updatePrompt(prompt: String) {
        if (prompt.length <= MAX_DRAFT_CHARACTERS && !state.value.running) {
            if (prompt != state.value.prompt) invalidateContext()
            mutableState.update { it.copy(prompt = prompt) }
        }
    }

    /** Also called synchronously before owner edits/deletes/imports, before their first suspension. */
    fun invalidateContext() {
        synchronized(contextLock) {
            contextGeneration.incrementAndGet()
            clearContextState()
        }
    }

    private fun clearContextState() {
        contextSnapshot = null
        mutableState.update {
            it.copy(contextItems = emptyList(), selectedContextKeys = emptySet(),
                contextPickerOpen = false, contextBusy = false, contextMemoryAllowed = false)
        }
    }

    fun loadContextOptions() {
        val source = contextSource ?: return
        if (closed || !state.value.canSend) return
        invalidateContext()
        if (contextSubscription == null) {
            contextSubscription = runCatching { source.observeInvalidations(::invalidateContext) }.getOrElse {
                mutableState.update { it.copy(notice = "참고 자료 저장소를 열지 못했습니다. 현재 질문만 보낼 수 있습니다.") }
                return
            }
        }
        val (expectedGeneration, query) = synchronized(contextLock) {
            if (closed || !state.value.canSend) return
            val captured = contextGeneration.get() to state.value.prompt
            mutableState.update { it.copy(contextBusy = true) }
            captured
        }
        scope.launch {
            try {
                val snapshot = source.load(query)
                synchronized(contextLock) {
                    if (expectedGeneration != contextGeneration.get() || !state.value.foreground) return@launch
                    contextSnapshot = snapshot
                    mutableState.update { it.copy(contextItems = snapshot.items, contextPickerOpen = true,
                        contextMemoryAllowed = snapshot.memoryAllowed, contextBusy = false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (expectedGeneration == contextGeneration.get()) {
                    invalidateContext()
                    mutableState.update { it.copy(notice = "참고 자료를 읽지 못했습니다. 현재 질문만 보내거나 다시 선택하세요.") }
                }
            }
        }
    }

    fun selectContextItem(key: String, selected: Boolean) {
        synchronized(contextLock) {
            if (!state.value.contextPickerOpen || state.value.contextItems.none { it.key == key }) return
            mutableState.update { it.copy(selectedContextKeys = if (selected) it.selectedContextKeys + key else it.selectedContextKeys - key) }
        }
    }

    fun finishContextSelection() {
        mutableState.update { it.copy(contextPickerOpen = false) }
    }

    /**
     * Sends the visible question now, with exactly the quotes the owner ticked.
     *
     * The selection is rebuilt and re-checked here rather than trusted from when the picker
     * closed, so an edited prompt, an expired memory, a withdrawn memory consent, or a changed
     * conversation row stops the send instead of quietly sending stale text.
     */
    fun send() {
        val pending = synchronized(contextLock) {
            val pending = buildPendingSendLocked() ?: return
            mutableState.update { it.copy(contextBusy = true) }
            pending
        }
        scope.launch {
            val valid = try {
                pending.selected.isEmpty() || (pending.snapshot != null &&
                    contextSource?.validate(pending.snapshot, pending.selected) == true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            val ready = synchronized(contextLock) {
                if (!pendingCurrent(pending)) return@launch
                if (!valid || monotonicMillis() >= pending.expiresAtMillis) {
                    invalidateContext()
                    mutableState.update { it.copy(notice = "선택한 자료나 사용 동의가 바뀌었습니다. 참고 자료와 전송 내용을 다시 확인하세요.") }
                    return@launch
                }
                mutableState.update { it.copy(contextBusy = false) }
                state.value.canSend
            }
            if (ready) dispatch(pending.outboundText, pending.query, pending.generation)
        }
    }

    private fun buildPendingSendLocked(): PendingSend? {
        if (closed || !state.value.canSend || state.value.contextPickerOpen) return null
        val current = state.value
        val selected = current.selectedContextKeys.toSet()
        val snapshot = contextSnapshot
        if (selected.isNotEmpty() && snapshot == null) return null
        val items = current.contextItems.filter { it.key in selected }
        if (items.size != selected.size) return null
        val outbound = OpenClawRemoteContextText.compose(current.prompt, items)
        if (RemoteAgentPrompt.create(outbound) == null) {
            mutableState.update { it.copy(notice = "질문과 선택한 참고 자료의 합계가 8 KiB를 넘습니다. 항목이나 질문을 줄이세요.") }
            return null
        }
        val lifetime = snapshot?.selectionLifetimeMillis(selected, wallClockMillis())
            ?: OpenClawRemoteContextSnapshot.MAX_SELECTION_AGE_MILLIS
        if (lifetime <= 0L) {
            invalidateContext()
            mutableState.update { it.copy(notice = "선택한 기억의 유효기간이 지났습니다. 참고 자료를 다시 선택하세요.") }
            return null
        }
        return PendingSend(outbound, current.prompt, current.endpointUrl, snapshot, selected,
            contextGeneration.get(), monotonicMillis() + lifetime)
    }

    private fun pendingCurrent(pending: PendingSend): Boolean =
        pending.generation == contextGeneration.get() && state.value.foreground && state.value.selected &&
            state.value.endpointUrl == pending.endpoint && state.value.prompt == pending.query &&
            state.value.connection == OpenClawGatewayUiState.CONNECTED

    private class PendingSend(
        val outboundText: String,
        val query: String,
        val endpoint: String,
        val snapshot: OpenClawRemoteContextSnapshot?,
        val selected: Set<String>,
        val generation: Long,
        val expiresAtMillis: Long,
    )

    /** A single advertised read-only RPC; it never starts a model or schedules another read. */
    fun readMacHealth() {
        val current = state.value
        if (closed || !current.selected || !current.foreground || !current.healthSupported ||
            current.connection != OpenClawGatewayUiState.CONNECTED || current.running || current.busy ||
            current.contextBusy || current.healthBusy || !backend.supportsHealthSnapshot
        ) return
        val expectedGeneration = ++healthGeneration
        healthExpiryJob?.cancel()
        mutableState.update { it.copy(healthBusy = true, healthNotice = "Mac의 최근 감시 결과를 확인하는 중입니다.") }
        healthJob = scope.launch {
            val result = try {
                withTimeout(6_000) { backend.readHealthSnapshot() }
            } catch (cancelled: TimeoutCancellationException) {
                OpenClawRemoteHealthResult.Unavailable
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                OpenClawRemoteHealthResult.Unavailable
            }
            if (expectedGeneration != healthGeneration || !state.value.foreground ||
                state.value.connection != OpenClawGatewayUiState.CONNECTED
            ) return@launch
            val now = wallClockMillis()
            val observation = (result as? OpenClawRemoteHealthResult.Observation)?.takeIf {
                it.observedAtEpochMillis >= now - OpenClawHealthSnapshot.MAX_AGE_MILLIS &&
                    it.observedAtEpochMillis <= now + OpenClawHealthSnapshot.MAX_FUTURE_SKEW_MILLIS
            }
            val notice = when {
                observation != null -> {
                    fun status(ok: Boolean) = if (ok) "정상" else "확인 필요"
                    "최근 감시 결과: Gateway ${status(observation.gatewayHealthy)} · Docker ${status(observation.dockerHealthy)} · " +
                        "정책 ${status(observation.policyValid)} · 비밀정보 검사 ${status(observation.secretsClean)}. " +
                        "조회 당시 최대 ${((now - observation.observedAtEpochMillis).coerceAtLeast(0) / 1_000)}초 전 기록이며 실시간 점검이 아닙니다."
                }
                result == OpenClawRemoteHealthResult.Unsupported -> "이 Mac은 상태 조회 기능을 제공하지 않습니다."
                result == OpenClawRemoteHealthResult.Invalid || result is OpenClawRemoteHealthResult.Observation ->
                    "상태 기록이 오래되었거나 검증되지 않아 표시하지 않습니다."
                else -> "Mac 상태를 읽지 못했습니다. 연결과 Mac의 감시 상태를 확인하세요."
            }
            mutableState.update { it.copy(healthBusy = false, healthNotice = notice,
                healthSupported = it.healthSupported && result != OpenClawRemoteHealthResult.Unsupported) }
            if (observation != null) healthExpiryJob = scope.launch {
                delay((OpenClawHealthSnapshot.MAX_AGE_MILLIS - (now - observation.observedAtEpochMillis).coerceAtLeast(0)).coerceAtLeast(1))
                if (expectedGeneration == healthGeneration) mutableState.update {
                    it.copy(healthNotice = "마지막 상태 기록의 유효시간이 지났습니다. Mac 상태를 다시 확인하세요.")
                }
            }
        }
    }

    private fun clearHealth() {
        healthGeneration++
        healthJob?.cancel()
        healthExpiryJob?.cancel()
        healthJob = null
        healthExpiryJob = null
        mutableState.update { it.copy(healthSupported = false, healthBusy = false, healthNotice = "") }
    }

    fun configure(endpoint: String, token: String, pin: String) {
        if (closed || state.value.busy || state.value.running) return
        val configuration = OpenClawRemoteConfiguration.parse(endpoint, token, pin)
        if (configuration == null) {
            mutableState.update {
                it.copy(notice = "HTTPS 주소와 Gateway 토큰을 확인하세요. 지문은 비우거나 SHA-256 64자리로 입력하세요.")
            }
            return
        }
        changeConfiguration("연결 정보를 저장했습니다. 사용 동의 후 연결하세요.") {
            backend.save(configuration)
        }
    }

    fun forget() {
        if (closed || state.value.busy || state.value.running) return
        changeConfiguration("이 휴대폰의 원격 연결 정보와 기기 키를 삭제했습니다. Mac의 기기 등록은 별도로 해제하세요.") {
            backend.forget()
        }
    }

    private fun changeConfiguration(successNotice: String, change: suspend () -> Unit) {
        val revocation = disconnect("연결 설정을 저장하는 중입니다.")
        val expectedEpoch = epoch
        mutableState.update { it.copy(busy = true) }
        scope.launch {
            try {
                check(revocation.await() == OwnerConsentMutationOutcome.APPLIED)
                if (expectedEpoch != epoch) return@launch
                change()
                if (expectedEpoch == epoch) mutableState.update { it.copy(notice = successNotice) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (expectedEpoch == epoch) mutableState.update {
                    it.copy(notice = "연결 설정을 저장하지 못했습니다. 연결은 해제된 상태입니다.")
                }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    /** Called only by the explicit connection-consent dialog; it never starts an inference. */
    fun connect() {
        if (closed || !state.value.selected || !state.value.foreground ||
            !state.value.configured || state.value.busy || state.value.running
        ) return
        val expectedEpoch = ++epoch
        connectionRequested = true
        mutableState.update { it.copy(busy = true, notice = "연결 동의를 저장하는 중입니다.") }
        val enablement = backend.setEnabled(true)
        scope.launch {
            try {
                val outcome = enablement.await()
                if (expectedEpoch != epoch) return@launch
                if (outcome == OwnerConsentMutationOutcome.APPLIED && connectionRequested && state.value.foreground) {
                    backend.setForeground(true)
                    mutableState.update { it.copy(notice = "연결 중입니다. 기기 승인이 필요하면 Mac에서 등록을 승인하세요.") }
                } else {
                    mutableState.update { it.copy(notice = "연결 동의를 저장하지 못했습니다. 연결은 해제된 상태입니다.") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (expectedEpoch == epoch) mutableState.update { it.copy(notice = "연결 준비에 실패했습니다.") }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun disconnect(
        notice: String = "연결을 해제했습니다. 다시 연결하려면 사용에 동의하세요.",
    ): Deferred<OwnerConsentMutationOutcome> {
        val expectedEpoch = ++epoch
        invalidateContext()
        clearHealth()
        connectionRequested = false
        backend.setForeground(false) // Closes immediately and queues best-effort aborts.
        val revocation = backend.setEnabled(false) // Fsync continues in application scope.
        runJob?.cancel()
        cancelJob?.cancel()
        runJob = null
        runId = null
        runGateway = null
        cancelPending = false
        mutableState.update {
            it.copy(connection = OpenClawGatewayUiState.DISCONNECTED,
                running = false, cancellationRequested = false, notice = notice)
        }
        // A run sealed by connection loss has an unknown remote outcome, but the text the owner
        // already saw is still their conversation, so it is stored like any other partial answer.
        storeAnswer(expectedEpoch, notice)
        scope.launch {
            val outcome = try {
                revocation.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (expectedEpoch == epoch && outcome != OwnerConsentMutationOutcome.APPLIED) {
                mutableState.update {
                    it.copy(notice = "$notice 사용 동의 해제를 저장하지 못해 원격 기능을 차단했습니다.")
                }
            }
        }
        return revocation
    }

    private fun dispatch(outboundText: String, question: String, requiredGeneration: Long) {
        val prompt = RemoteAgentPrompt.create(outboundText) ?: return
        val gateway = backend.activeGateway() ?: return
        val dispatchGeneration = synchronized(contextLock) {
            // A Room or owner-consent callback may arrive off the main thread between the final
            // freshness check and dispatch. Claim that exact generation without losing the change.
            if (!contextGeneration.compareAndSet(requiredGeneration, requiredGeneration + 1)) return
            clearContextState()
            requiredGeneration + 1
        }
        val expectedEpoch = ++epoch
        val identity = UUID.randomUUID().toString()
        val request = RemoteAgentStartRequest(
            idempotencyKey = requireNotNull(RemoteAgentIdempotencyKey.parse(identity)),
            sessionId = requireNotNull(RemoteAgentSessionId.parse("model-run-$identity")),
            prompt = prompt,
            // 60 s was short enough that a long answer died before the Gateway produced anything,
            // because this one-shot model-run mode delivers its text at completion rather than as
            // deltas. Three minutes lets a full 2,048-token answer finish; the Gateway's own
            // per-run timeout must be at least as long or it fails first.
            limits = RemoteAgentRunLimits(
                maxEvents = 256,
                maxOutputUtf8Bytes = 32 * 1_024,
                timeoutMillis = RUN_TIMEOUT_MILLIS,
            ),
        )
        runConversationId = null
        cancelPending = false
        mutableState.update {
            it.copy(running = true, cancellationRequested = false, sentPrompt = question,
                prompt = "", answer = "", questionStored = false, answerStored = false,
                notice = "질문 1회를 전송하는 중입니다.")
        }
        runGateway = gateway
        runJob = scope.launch {
            try {
                withTimeout(OBSERVATION_TIMEOUT_MILLIS) {
                    if (dispatchGeneration != contextGeneration.get() || expectedEpoch != epoch) {
                        finish(expectedEpoch, "전송 전에 자료나 연결 상태가 바뀌었습니다. 전송 내용을 다시 확인하세요.")
                        return@withTimeout
                    }
                    // Stored before the start so an interrupted run still leaves the question in
                    // the transcript. This write is what makes the remote turn ordinary history;
                    // the generation check above already passed, so its own Room invalidation
                    // cannot cancel this send.
                    storeQuestion(question, expectedEpoch)
                    when (val started = gateway.start(request)) {
                        is RemoteAgentStartResult.Started -> {
                            if (expectedEpoch != epoch) return@withTimeout
                            runId = started.runId
                            // A cancel tapped before the run id existed is sent now, before the
                            // first observation, so the owner never pays for the held window.
                            if (cancelPending) {
                                cancelPending = false
                                requestCancel(started.runId, gateway, expectedEpoch)
                            }
                            observe(gateway, started.runId, expectedEpoch)
                        }
                        is RemoteAgentStartResult.OutcomeUnknown -> if (expectedEpoch == epoch) disconnect(
                            "전송 결과를 확인할 수 없습니다. 자동으로 재전송하지 않습니다. Mac에서 실행과 과금을 확인하세요.")
                        is RemoteAgentStartResult.Refused -> finish(expectedEpoch,
                            "원격 요청이 거절되었습니다. 연결 인증과 Mac의 제공업체 설정을 확인하세요.")
                    }
                }
            } catch (_: TimeoutCancellationException) {
                if (expectedEpoch == epoch) disconnect("응답 확인 시간이 지났습니다. 원격 종료와 과금 여부는 확인되지 않았습니다.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (expectedEpoch == epoch) disconnect("원격 응답을 확인하지 못했습니다. Mac에서 실행 상태를 확인하세요.")
            }
        }
    }

    private suspend fun observe(gateway: RemoteAgentGateway, id: RemoteAgentRunId, expectedEpoch: Long) = coroutineScope {
        val terminal = CompletableDeferred<RemoteAgentRunPhase?>()
        var outputBytes = 0
        val events = launch(start = CoroutineStart.UNDISPATCHED) {
            gateway.events(id).collect { event ->
                if (expectedEpoch != epoch || event.runId != id) return@collect
                when (event) {
                    is RemoteAgentEvent.TextDelta -> {
                        outputBytes += event.utf8Bytes
                        if (outputBytes <= MAX_ANSWER_BYTES) {
                            mutableState.update { it.copy(answer = it.answer + event.text) }
                        } else terminal.complete(null)
                    }
                    is RemoteAgentEvent.StatusChanged -> {
                        if (event.phase.isTerminal) terminal.complete(event.phase)
                        else mutableState.update { it.copy(notice = "원격 모델이 답변을 작성하는 중입니다.") }
                    }
                    is RemoteAgentEvent.Failed -> terminal.complete(RemoteAgentRunPhase.FAILED)
                }
            }
        }
        val wait = launch {
            while (!terminal.isCompleted && expectedEpoch == epoch) {
                when (val result = gateway.waitForTerminal(id, RemoteAgentWaitTimeout(15_000))) {
                    is RemoteAgentWaitResult.Terminal -> {
                        yield() // Drain the ordered output events emitted by the terminal adapter.
                        terminal.complete(result.status.phase)
                    }
                    is RemoteAgentWaitResult.TimedOut -> delay(250)
                    else -> terminal.complete(null)
                }
            }
        }
        try {
            when (terminal.await()) {
                RemoteAgentRunPhase.SUCCEEDED -> finish(expectedEpoch,
                    if (state.value.answer.isNotBlank()) "답변을 받았습니다."
                    else "원격 실행은 끝났지만 표시할 답변을 받지 못했습니다.")
                RemoteAgentRunPhase.CANCELLED -> finish(expectedEpoch, "원격 실행 취소가 확인되었습니다.")
                RemoteAgentRunPhase.TIMED_OUT -> finish(expectedEpoch, "원격 실행 시간 제한에 도달했습니다.")
                RemoteAgentRunPhase.FAILED -> finish(expectedEpoch, "원격 모델 실행에 실패했습니다. Mac의 제공업체 설정을 확인하세요.")
                else -> if (expectedEpoch == epoch) disconnect("원격 상태를 확인할 수 없습니다. 취소 완료와 과금 여부는 확인되지 않았습니다.")
            }
        } finally {
            events.cancel()
            wait.cancel()
        }
    }

    /**
     * A cancel tapped in the first moment of a turn is held, not turned into a closed connection.
     *
     * `running` is true from dispatch, but the remote run id only exists once start returns. The
     * old behaviour closed the socket and reported an unknown outcome for that window, which is
     * both alarming and worse for the owner than cancelling: the run keeps costing until its own
     * timeout. The request is now remembered and sent the instant the run id arrives.
     */
    fun cancel() {
        if (!state.value.running || state.value.cancellationRequested) return
        val id = runId
        val gateway = runGateway
        if (id == null || gateway == null) {
            cancelPending = true
            mutableState.update {
                it.copy(cancellationRequested = true,
                    notice = "취소를 요청했습니다. 실행이 시작되는 즉시 중단합니다.")
            }
            return
        }
        mutableState.update { it.copy(cancellationRequested = true, notice = "원격 취소를 요청하는 중입니다.") }
        requestCancel(id, gateway, epoch)
    }

    private fun requestCancel(id: RemoteAgentRunId, gateway: RemoteAgentGateway, expectedEpoch: Long) {
        cancelJob = scope.launch {
            val result = try {
                gateway.cancel(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (expectedEpoch == epoch && state.value.running) {
                mutableState.update {
                    it.copy(notice = if (result == RemoteAgentCancelResult.Accepted)
                        "취소 요청을 전달했습니다. 원격 종료를 확인하는 중입니다."
                    else "취소 완료를 확인하지 못했습니다. 원격 상태를 계속 확인합니다.")
                }
            }
        }
    }

    private fun finish(expectedEpoch: Long, notice: String) {
        if (expectedEpoch != epoch) return
        runId = null
        runGateway = null
        cancelPending = false
        mutableState.update { it.copy(running = false, cancellationRequested = false, notice = notice) }
        storeAnswer(expectedEpoch, notice)
    }

    private suspend fun storeQuestion(question: String, expectedEpoch: Long) {
        val sink = transcript ?: return
        val conversationId = try {
            sink.recordQuestion(question)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (expectedEpoch != epoch) return
        runConversationId = conversationId
        if (conversationId != null) {
            mutableState.update { it.copy(questionStored = true) }
        } else {
            mutableState.update {
                it.copy(notice = "이 질문을 대화에 저장하지 못했습니다. 전송은 계속합니다.")
            }
        }
    }

    /**
     * Stores whatever answer text arrived, including a partial one from a cancelled or failed run.
     *
     * A run whose question never reached storage keeps its answer on screen instead, so a storage
     * failure loses no visible text.
     */
    private fun storeAnswer(expectedEpoch: Long, notice: String) {
        val sink = transcript
        val conversationId = runConversationId
        runConversationId = null
        val answer = state.value.answer
        if (sink == null || conversationId == null || answer.isBlank()) return
        scope.launch {
            val stored = try {
                sink.recordAnswer(conversationId, answer)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (expectedEpoch != epoch) return@launch
            if (stored) {
                mutableState.update { it.copy(answerStored = true) }
            } else {
                mutableState.update {
                    it.copy(notice = "$notice 이 답변을 대화에 저장하지 못했습니다.")
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        disconnect("원격 연결을 닫았습니다.")
        backend.close()
        contextSubscription?.close()
    }

    private companion object {
        const val MAX_DRAFT_CHARACTERS = 8 * 1_024
        const val MAX_ANSWER_BYTES = 32 * 1_024
        const val RUN_TIMEOUT_MILLIS = 180_000L

        /** Always above [RUN_TIMEOUT_MILLIS]: the app must outlive the run it is observing. */
        const val OBSERVATION_TIMEOUT_MILLIS = 200_000L
    }
}
