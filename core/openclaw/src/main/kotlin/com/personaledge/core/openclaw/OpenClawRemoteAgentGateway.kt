package com.personaledge.core.openclaw

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.personaledge.core.agent.RemoteAgentCancelResult
import com.personaledge.core.agent.RemoteAgentConnectionState
import com.personaledge.core.agent.RemoteAgentContractLimits
import com.personaledge.core.agent.RemoteAgentEvent
import com.personaledge.core.agent.RemoteAgentFailureCode
import com.personaledge.core.agent.RemoteAgentGateway
import com.personaledge.core.agent.RemoteAgentRunId
import com.personaledge.core.agent.RemoteAgentRunLimits
import com.personaledge.core.agent.RemoteAgentRunPhase
import com.personaledge.core.agent.RemoteAgentRunStatus
import com.personaledge.core.agent.RemoteAgentStartRequest
import com.personaledge.core.agent.RemoteAgentStartResult
import com.personaledge.core.agent.RemoteAgentStatusResult
import com.personaledge.core.agent.RemoteAgentWaitResult
import com.personaledge.core.agent.RemoteAgentWaitTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Narrow test seam for the already-authenticated protocol client.
 *
 * Keeping this seam in the adapter file prevents the high-level gateway from depending on socket
 * implementation details and lets its wire projection be tested adversarially.
 */
internal interface OpenClawGatewayRpcPort {
    val connectionState: StateFlow<OpenClawRpcConnectionState>
    val events: Flow<OpenClawEventFrame>

    suspend fun request(
        method: String,
        params: JsonElement?,
        timeoutMillis: Long,
    ): OpenClawRpcResult

    fun sealPostConnectRequests()

    fun enqueueWithoutResponse(
        method: String,
        params: JsonElement?,
    ): OpenClawRpcEnqueueResult
}

/**
 * Content-free receipt for an orderly lifecycle abort pass.
 *
 * [attempted] counts bounded nonterminal local run reservations. [queued] counts frames accepted
 * by the local WebSocket queue; it never means that remote cancellation succeeded.
 */
data class OpenClawOrderlyAbortSummary(
    val attempted: Int,
    val queued: Int,
) {
    init {
        require(attempted in 0..OpenClawProtocol.MAX_ORDERLY_ABORT_REQUESTS)
        require(queued in 0..attempted)
    }
}

/**
 * OpenClaw 2026.8.1 implementation of the bounded remote-agent contract.
 *
 * This adapter deliberately exposes only `agent`, `agent.wait`, and `chat.abort`. Every turn uses
 * OpenClaw's one-shot raw-model path with a fresh explicit model-run UUID. In 2026.8.1 that path
 * neither initial-touches nor stores a skill snapshot on the explicit source key, has no caller
 * transcript to fork, submits an empty provider system prompt, constructs no model tools, and
 * routes transcript effects through a run-owned hidden SQLite session. Normal `finally` cleanup
 * logically deletes that hidden session without an archive; a cleanup failure or process crash may
 * leave SQLite/WAL residue, so this is not a secure-erasure guarantee. On abrupt socket or process
 * loss an already submitted remote run may continue for its contracted timeout, up to 30 minutes,
 * and its hidden SQLite/WAL residue may remain. Provider payloads, errors, prompts, and app session
 * identifiers are never copied to public errors or `toString()`.
 */
class OpenClawRemoteAgentGateway internal constructor(
    private val rpc: OpenClawGatewayRpcPort,
    parentScope: CoroutineScope,
) : RemoteAgentGateway, AutoCloseable {
    constructor(client: OpenClawRpcClient) : this(
        rpc = OpenClawClientRpcPort(client),
        parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private val lock = Any()
    private val adapterJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + adapterJob)
    private val order = AtomicLong(0L)
    private val runs = linkedMapOf<String, RunRecord>()
    private val orderlyAbortLock = Any()
    private var degraded = false
    private var lifecycleSealed = false
    private var orderlyAbortSummary: OpenClawOrderlyAbortSummary? = null
    private var rawConnectionState = rpc.connectionState.value
    private val mutableConnectionState = MutableStateFlow(mapConnectionState(rawConnectionState))

    override val connectionState: StateFlow<RemoteAgentConnectionState> =
        mutableConnectionState.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            rpc.connectionState.collect { state ->
                synchronized(lock) {
                    rawConnectionState = state
                    if (state != OpenClawRpcConnectionState.READY) degraded = false
                    refreshConnectionStateLocked()
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            rpc.events.collect(::handleEventFrame)
        }
    }

    override suspend fun start(request: RemoteAgentStartRequest): RemoteAgentStartResult {
        val expectedRunId = request.idempotencyKey.value
        val fingerprint = requestFingerprint(request)
        val preparation = synchronized(lock) {
            val existing = runs[expectedRunId]
            when {
                lifecycleSealed -> StartPreparation(result = RemoteAgentStartResult.Refused(
                    RemoteAgentFailureCode.NOT_CONNECTED,
                ))
                existing != null && existing.fingerprint != fingerprint ->
                    StartPreparation(result = RemoteAgentStartResult.Refused(
                        RemoteAgentFailureCode.REQUEST_REJECTED,
                    ))
                existing != null && existing.accepted ->
                    StartPreparation(result = RemoteAgentStartResult.Started(existing.runId!!))
                degraded -> StartPreparation(result = RemoteAgentStartResult.Refused(
                    RemoteAgentFailureCode.PROTOCOL_MISMATCH,
                ))
                existing != null -> StartPreparation(record = existing)
                !reserveRunSlotLocked() -> StartPreparation(result = RemoteAgentStartResult.Refused(
                    RemoteAgentFailureCode.LIMIT_EXCEEDED,
                ))
                else -> {
                    val modelRunSession = newModelRunSession()
                    val record = RunRecord(
                        expectedRunId = expectedRunId,
                        sessionId = modelRunSession.sessionId,
                        sessionKey = modelRunSession.sessionKey,
                        fingerprint = fingerprint,
                        limits = request.limits,
                        createdOrder = order.incrementAndGet(),
                    )
                    runs[expectedRunId] = record
                    StartPreparation(record = record)
                }
            }
        }
        preparation.result?.let { return it }
        val record = checkNotNull(preparation.record)

        return record.submissionMutex.withLock {
            synchronized(lock) {
                if (lifecycleSealed) {
                    return@withLock RemoteAgentStartResult.Refused(
                        RemoteAgentFailureCode.NOT_CONNECTED,
                    )
                } else if (record.accepted) {
                    return@withLock RemoteAgentStartResult.Started(checkNotNull(record.runId))
                }
            }
            val result = requestRpc(
                method = OpenClawProtocol.METHOD_AGENT,
                params = buildStartParams(request, record.sessionId, record.sessionKey),
                timeoutMillis = OpenClawProtocol.REQUEST_TIMEOUT_MILLIS,
            ) ?: run {
                synchronized(lock) { record.outcomeUnknown = true }
                return@withLock RemoteAgentStartResult.OutcomeUnknown(request.idempotencyKey)
            }
            when (result) {
                is OpenClawRpcResult.Success -> {
                    val runId = parseAcceptance(
                        payload = result.payload,
                        expectedRunId = expectedRunId,
                        expectedSessionKey = record.sessionKey,
                    )
                    if (runId == null) {
                        synchronized(lock) {
                            runs.remove(expectedRunId, record)
                            markProtocolFailureLocked(record)
                        }
                        RemoteAgentStartResult.Refused(RemoteAgentFailureCode.PROTOCOL_MISMATCH)
                    } else {
                        var earlyFailure: RemoteAgentEvent.Failed? = null
                        synchronized(lock) {
                            record.accepted = true
                            record.runId = runId
                            record.outcomeUnknown = false
                            if (record.earlyProtocolViolation) {
                                val sequence = record.observedEvents.coerceAtLeast(1)
                                earlyFailure = RemoteAgentEvent.Failed(
                                    runId,
                                    sequence,
                                    RemoteAgentFailureCode.PROTOCOL_MISMATCH,
                                )
                            }
                        }
                        earlyFailure?.let(record.events::tryEmit)
                        RemoteAgentStartResult.Started(runId)
                    }
                }
                OpenClawRpcResult.OutcomeUnknown -> {
                    synchronized(lock) { record.outcomeUnknown = true }
                    RemoteAgentStartResult.OutcomeUnknown(request.idempotencyKey)
                }
                is OpenClawRpcResult.NotSent -> synchronized(lock) {
                    if (record.outcomeUnknown) {
                        RemoteAgentStartResult.OutcomeUnknown(request.idempotencyKey)
                    } else {
                        runs.remove(expectedRunId, record)
                        RemoteAgentStartResult.Refused(mapNotSent(result.reason))
                    }
                }
                is OpenClawRpcResult.Rejected -> synchronized(lock) {
                    if (record.outcomeUnknown) {
                        RemoteAgentStartResult.OutcomeUnknown(request.idempotencyKey)
                    } else {
                        runs.remove(expectedRunId, record)
                        RemoteAgentStartResult.Refused(mapRejected(result.code))
                    }
                }
            }
        }
    }

    override fun events(runId: RemoteAgentRunId): Flow<RemoteAgentEvent> = synchronized(lock) {
        runs[runId.value]
            ?.takeIf { it.accepted && it.runId == runId }
            ?.events
            ?.asSharedFlow()
            ?: emptyFlow()
    }

    override suspend fun status(runId: RemoteAgentRunId): RemoteAgentStatusResult {
        val record = synchronized(lock) {
            runs[runId.value]?.takeIf { it.accepted && it.runId == runId }
        } ?: return RemoteAgentStatusResult.NotFound
        synchronized(lock) {
            if (record.phase.isTerminal) {
                return RemoteAgentStatusResult.Found(statusLocked(record))
            }
            if (degraded) {
                return RemoteAgentStatusResult.Unavailable(
                    RemoteAgentFailureCode.PROTOCOL_MISMATCH,
                )
            }
        }
        return when (val result = requestRpc(
            method = OpenClawProtocol.METHOD_AGENT_WAIT,
            params = buildWaitParams(runId, timeoutMillis = 0L),
            timeoutMillis = STATUS_REQUEST_TIMEOUT_MILLIS,
        )) {
            null,
            OpenClawRpcResult.OutcomeUnknown,
            -> RemoteAgentStatusResult.Unknown(synchronized(lock) { statusLocked(record) })
            is OpenClawRpcResult.NotSent -> RemoteAgentStatusResult.Unavailable(
                mapNotSent(result.reason),
            )
            is OpenClawRpcResult.Rejected -> RemoteAgentStatusResult.Unavailable(
                mapRejected(result.code),
            )
            is OpenClawRpcResult.Success -> when (
                val parsed = parseWaitResult(result.payload, runId.value)
            ) {
                null -> {
                    synchronized(lock) { markProtocolFailureLocked(record) }
                    RemoteAgentStatusResult.Unavailable(RemoteAgentFailureCode.PROTOCOL_MISMATCH)
                }
                is ParsedWait.Terminal -> {
                    val applied = applyWaitTerminal(record, parsed)
                    if (applied.failureCode != null) {
                        RemoteAgentStatusResult.Unavailable(applied.failureCode)
                    } else {
                        RemoteAgentStatusResult.Found(applied.status)
                    }
                }
                is ParsedWait.Pending -> synchronized(lock) {
                    updatePhaseLocked(record, parsed.phase)
                    RemoteAgentStatusResult.Found(statusLocked(record))
                }
                ParsedWait.ObservationTimedOut -> RemoteAgentStatusResult.Unknown(
                    synchronized(lock) { statusLocked(record) },
                )
            }
        }
    }

    override suspend fun cancel(runId: RemoteAgentRunId): RemoteAgentCancelResult {
        val record = synchronized(lock) {
            runs[runId.value]?.takeIf { it.accepted && it.runId == runId }
        } ?: return RemoteAgentCancelResult.NotFound
        synchronized(lock) {
            if (record.phase.isTerminal && record.authoritativeTerminal) {
                return RemoteAgentCancelResult.AlreadyTerminal
            }
        }
        return when (val result = requestRpc(
            method = OpenClawProtocol.METHOD_CHAT_ABORT,
            params = buildAbortParams(record, runId),
            timeoutMillis = OpenClawProtocol.REQUEST_TIMEOUT_MILLIS,
        )) {
            null,
            OpenClawRpcResult.OutcomeUnknown,
            -> RemoteAgentCancelResult.Refused(RemoteAgentFailureCode.REMOTE_UNAVAILABLE)
            is OpenClawRpcResult.NotSent -> RemoteAgentCancelResult.Refused(
                mapNotSent(result.reason),
            )
            is OpenClawRpcResult.Rejected -> RemoteAgentCancelResult.Refused(
                mapRejected(result.code),
            )
            is OpenClawRpcResult.Success -> when (parseAbortResult(result.payload, runId.value)) {
                ParsedAbort.ACCEPTED -> {
                    synchronized(lock) { record.abortRequested = true }
                    RemoteAgentCancelResult.Accepted
                }
                ParsedAbort.ALREADY_TERMINAL -> RemoteAgentCancelResult.AlreadyTerminal
                null -> {
                    synchronized(lock) { markProtocolFailureLocked(record) }
                    RemoteAgentCancelResult.Refused(RemoteAgentFailureCode.PROTOCOL_MISMATCH)
                }
            }
        }
    }

    override suspend fun waitForTerminal(
        runId: RemoteAgentRunId,
        timeout: RemoteAgentWaitTimeout,
    ): RemoteAgentWaitResult {
        val record = synchronized(lock) {
            runs[runId.value]?.takeIf { it.accepted && it.runId == runId }
        } ?: return RemoteAgentWaitResult.NotFound
        synchronized(lock) {
            if (record.phase.isTerminal) {
                return RemoteAgentWaitResult.Terminal(statusLocked(record))
            }
            if (degraded) {
                return RemoteAgentWaitResult.Unavailable(
                    RemoteAgentFailureCode.PROTOCOL_MISMATCH,
                )
            }
        }

        var remainingMillis = timeout.millis
        while (remainingMillis > 0L) {
            val remoteWaitMillis = remainingMillis.coerceAtMost(MAX_REMOTE_WAIT_CHUNK_MILLIS)
            val requestTimeoutMillis = (remoteWaitMillis + WAIT_RESPONSE_GRACE_MILLIS)
                .coerceAtMost(OpenClawProtocol.REQUEST_TIMEOUT_MILLIS)
            val result = requestRpc(
                method = OpenClawProtocol.METHOD_AGENT_WAIT,
                params = buildWaitParams(runId, remoteWaitMillis),
                timeoutMillis = requestTimeoutMillis,
            )
            when (result) {
                null,
                OpenClawRpcResult.OutcomeUnknown,
                -> return RemoteAgentWaitResult.Unavailable(RemoteAgentFailureCode.REMOTE_UNAVAILABLE)
                is OpenClawRpcResult.NotSent -> return RemoteAgentWaitResult.Unavailable(
                    mapNotSent(result.reason),
                )
                is OpenClawRpcResult.Rejected -> return RemoteAgentWaitResult.Unavailable(
                    mapRejected(result.code),
                )
                is OpenClawRpcResult.Success -> when (
                    val parsed = parseWaitResult(result.payload, runId.value)
                ) {
                    null -> {
                        synchronized(lock) { markProtocolFailureLocked(record) }
                        return RemoteAgentWaitResult.Unavailable(
                            RemoteAgentFailureCode.PROTOCOL_MISMATCH,
                        )
                    }
                    is ParsedWait.Terminal -> {
                        val applied = applyWaitTerminal(record, parsed)
                        if (applied.failureCode != null) {
                            return RemoteAgentWaitResult.Unavailable(applied.failureCode)
                        }
                        return RemoteAgentWaitResult.Terminal(applied.status)
                    }
                    is ParsedWait.Pending -> return synchronized(lock) {
                        updatePhaseLocked(record, parsed.phase)
                        RemoteAgentWaitResult.TimedOut(statusLocked(record))
                    }
                    ParsedWait.ObservationTimedOut -> {
                        remainingMillis -= remoteWaitMillis
                        if (remainingMillis <= 0L) {
                            return RemoteAgentWaitResult.TimedOut(
                                synchronized(lock) { statusLocked(record) },
                            )
                        }
                    }
                }
            }
        }
        return RemoteAgentWaitResult.TimedOut(synchronized(lock) { statusLocked(record) })
    }

    /**
     * Seals new submissions and enqueues best-effort aborts for every nonterminal reservation.
     *
     * This pass includes accepted, outcome-unknown, and not-yet-accepted submission records. It is
     * capped at 64 records, performs no response wait, and is idempotent. The RPC send gate makes
     * each abort follow any `agent` frame already handed to the socket and prevents a later ordinary
     * request from overtaking the abort pass. A queued frame is not evidence of remote cancellation.
     */
    fun enqueueOrderlyAborts(): OpenClawOrderlyAbortSummary = synchronized(orderlyAbortLock) {
        orderlyAbortSummary?.let { return@synchronized it }
        val targets = synchronized(lock) {
            lifecycleSealed = true
            runs.values
                .asSequence()
                .filterNot { it.phase.isTerminal }
                .take(OpenClawProtocol.MAX_ORDERLY_ABORT_REQUESTS)
                .map { OrderlyAbortTarget(it.expectedRunId, it.sessionKey) }
                .toList()
        }
        val sendGateSealed = try {
            rpc.sealPostConnectRequests()
            true
        } catch (_: Throwable) {
            false
        }
        var queued = 0
        if (sendGateSealed) {
            targets.forEach { target ->
                val result = try {
                    rpc.enqueueWithoutResponse(
                        method = OpenClawProtocol.METHOD_CHAT_ABORT,
                        params = buildOrderlyAbortParams(target),
                    )
                } catch (_: Throwable) {
                    OpenClawRpcEnqueueResult.NotSent(OpenClawRpcNotSentReason.NOT_CONNECTED)
                }
                if (result == OpenClawRpcEnqueueResult.Queued) queued += 1
            }
        }
        OpenClawOrderlyAbortSummary(attempted = targets.size, queued = queued).also {
            orderlyAbortSummary = it
        }
    }

    override fun close() {
        try {
            enqueueOrderlyAborts()
        } finally {
            scope.cancel()
        }
    }

    override fun toString(): String = synchronized(lock) {
        "OpenClawRemoteAgentGateway(runs=${runs.size}, connectionState=${connectionState.value})"
    }

    private suspend fun requestRpc(
        method: String,
        params: JsonElement,
        timeoutMillis: Long,
    ): OpenClawRpcResult? = try {
        rpc.request(method, params, timeoutMillis)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private fun handleEventFrame(frame: OpenClawEventFrame) {
        if (frame.event !in SUPPORTED_RUN_EVENTS) return
        val root = frame.payload.asObjectOrNull() ?: return
        val routedRunId = root.routingString("runId") ?: return
        val record = synchronized(lock) { runs[routedRunId] } ?: return
        synchronized(lock) {
            if (!record.accepted || record.runId?.value != routedRunId) {
                if (!record.phase.isTerminal) {
                    record.observedEvents = (record.observedEvents + 1)
                        .coerceAtMost(record.limits.maxEvents)
                    record.earlyProtocolViolation = true
                    markProtocolFailureLocked(record)
                }
                return
            }
            if (record.phase.isTerminal) return
        }
        val parsed = when (frame.event) {
            EVENT_CHAT -> parseChatEvent(frame, root, record)
            EVENT_AGENT -> parseAgentEvent(frame, root, record)
            EVENT_SESSION_TOOL -> parseSessionToolEvent(frame, root, record)
            else -> null
        }
        applyEvent(record, parsed)
    }

    /**
     * Reads a failure terminal as the cancellation this client asked for, and only then.
     *
     * OpenClaw 2026.8.1 deliberately surfaces an externally aborted run to its clients as an
     * error (`action: "surface_error"` for an external abort), so the frame that ends the run says
     * failure even when the Gateway stopped it because we told it to. Left alone, an owner who
     * cancelled is told their run failed, and a cancelled run and a broken one become
     * indistinguishable.
     *
     * The evidence used is not the error frame and not an inference from timing: it is the
     * Gateway's own `chat.abort` acknowledgement, which reports `aborted: true` against this exact
     * run id, and which is only sent for a run that was still live. [RunRecord.abortRequested] is
     * set from nothing else, so a run that fails on its own, or one whose abort was refused or
     * answered `already terminal`, keeps its failure terminal. A timeout also keeps its own
     * terminal: expiring is not the same as being stopped.
     */
    private fun reconcileRequestedAbortLocked(
        record: RunRecord,
        phase: RemoteAgentRunPhase,
    ): RemoteAgentRunPhase = if (
        record.abortRequested && phase == RemoteAgentRunPhase.FAILED
    ) {
        RemoteAgentRunPhase.CANCELLED
    } else {
        phase
    }

    private fun applyEvent(record: RunRecord, parsed: ParsedEvent?) {
        var projected: RemoteAgentEvent? = null
        synchronized(lock) {
            if (record.phase.isTerminal) return
            if (record.observedEvents >= record.limits.maxEvents) {
                failRunLocked(record, RemoteAgentFailureCode.LIMIT_EXCEEDED, degrade = false)
                return
            }
            record.observedEvents += 1
            val projectedSequence = record.observedEvents
            if (parsed == null) {
                projected = failRunLocked(
                    record,
                    RemoteAgentFailureCode.PROTOCOL_MISMATCH,
                    degrade = true,
                    sequence = projectedSequence,
                )
                return@synchronized
            }
            val previousProviderSequence = when (parsed.channel) {
                EventChannel.CHAT -> record.lastChatSequence
                EventChannel.AGENT -> record.lastAgentSequence
            }
            if (previousProviderSequence != null && parsed.providerSequence <= previousProviderSequence) {
                projected = failRunLocked(
                    record,
                    RemoteAgentFailureCode.PROTOCOL_MISMATCH,
                    degrade = true,
                    sequence = projectedSequence,
                )
                return@synchronized
            }
            when (parsed.channel) {
                EventChannel.CHAT -> record.lastChatSequence = parsed.providerSequence
                EventChannel.AGENT -> record.lastAgentSequence = parsed.providerSequence
            }
            when (parsed) {
                is ParsedEvent.Delta -> {
                    val currentText = record.outputText.toString()
                    val deltaText = if (parsed.replace) {
                        if (!parsed.text.startsWith(currentText)) {
                            projected = failRunLocked(
                                record,
                                RemoteAgentFailureCode.PROTOCOL_MISMATCH,
                                degrade = true,
                                sequence = projectedSequence,
                            )
                            return@synchronized
                        }
                        parsed.text.substring(currentText.length)
                    } else {
                        parsed.text
                    }
                    if (parsed.canonicalText != null &&
                        parsed.canonicalText != currentText + deltaText
                    ) {
                        projected = failRunLocked(
                            record,
                            RemoteAgentFailureCode.PROTOCOL_MISMATCH,
                            degrade = true,
                            sequence = projectedSequence,
                        )
                        return@synchronized
                    }
                    val utf8Bytes = deltaText.toByteArray(Charsets.UTF_8).size
                    if (utf8Bytes > RemoteAgentContractLimits.MAX_EVENT_TEXT_UTF8_BYTES ||
                        utf8Bytes > record.limits.maxOutputUtf8Bytes - record.observedOutputUtf8Bytes
                    ) {
                        projected = failRunLocked(
                            record,
                            RemoteAgentFailureCode.LIMIT_EXCEEDED,
                            degrade = false,
                            sequence = projectedSequence,
                        )
                    } else if (deltaText.isNotEmpty()) {
                        val delta = RemoteAgentEvent.TextDelta.create(
                            runId = checkNotNull(record.publicRunId()),
                            sequence = projectedSequence,
                            text = deltaText,
                        )
                        if (delta == null) {
                            projected = failRunLocked(
                                record,
                                RemoteAgentFailureCode.PROTOCOL_MISMATCH,
                                degrade = true,
                                sequence = projectedSequence,
                            )
                        } else {
                            record.observedOutputUtf8Bytes += utf8Bytes
                            record.outputText.append(deltaText)
                            record.phase = RemoteAgentRunPhase.RUNNING
                            projected = delta
                        }
                    }
                }
                is ParsedEvent.Phase -> {
                    updatePhaseLocked(record, parsed.phase, authoritative = parsed.phase.isTerminal)
                    projected = RemoteAgentEvent.StatusChanged(
                        runId = checkNotNull(record.publicRunId()),
                        sequence = projectedSequence,
                        phase = record.phase,
                    )
                }
                is ParsedEvent.TerminalSnapshot -> {
                    val snapshotPhase = reconcileRequestedAbortLocked(record, parsed.phase)
                    if (record.pendingTerminalPhase != null &&
                        record.pendingTerminalPhase != snapshotPhase
                    ) {
                        projected = failRunLocked(
                            record,
                            RemoteAgentFailureCode.PROTOCOL_MISMATCH,
                            degrade = true,
                            sequence = projectedSequence,
                        )
                        return@synchronized
                    }
                    val currentText = record.outputText.toString()
                    val deltaText = parsed.text?.let { snapshot ->
                        if (!snapshot.startsWith(currentText)) {
                            projected = failRunLocked(
                                record,
                                RemoteAgentFailureCode.PROTOCOL_MISMATCH,
                                degrade = true,
                                sequence = projectedSequence,
                            )
                            return@synchronized
                        }
                        snapshot.substring(currentText.length)
                    }.orEmpty()
                    val utf8Bytes = deltaText.toByteArray(Charsets.UTF_8).size
                    if (utf8Bytes > RemoteAgentContractLimits.MAX_EVENT_TEXT_UTF8_BYTES ||
                        utf8Bytes > record.limits.maxOutputUtf8Bytes - record.observedOutputUtf8Bytes
                    ) {
                        projected = failRunLocked(
                            record,
                            RemoteAgentFailureCode.LIMIT_EXCEEDED,
                            degrade = false,
                            sequence = projectedSequence,
                        )
                        return@synchronized
                    }
                    updatePhaseLocked(record, snapshotPhase, authoritative = true)
                    if (deltaText.isEmpty()) {
                        projected = RemoteAgentEvent.StatusChanged(
                            runId = checkNotNull(record.publicRunId()),
                            sequence = projectedSequence,
                            phase = record.phase,
                        )
                    } else {
                        val delta = RemoteAgentEvent.TextDelta.create(
                            runId = checkNotNull(record.publicRunId()),
                            sequence = projectedSequence,
                            text = deltaText,
                        )
                        if (delta == null) {
                            projected = failRunLocked(
                                record,
                                RemoteAgentFailureCode.PROTOCOL_MISMATCH,
                                degrade = true,
                                sequence = projectedSequence,
                            )
                        } else {
                            record.observedOutputUtf8Bytes += utf8Bytes
                            record.outputText.append(deltaText)
                            projected = delta
                        }
                    }
                }
                is ParsedEvent.TerminalHint -> {
                    val previous = record.pendingTerminalPhase
                    if (previous != null && previous != parsed.phase) {
                        projected = failRunLocked(
                            record,
                            RemoteAgentFailureCode.PROTOCOL_MISMATCH,
                            degrade = true,
                            sequence = projectedSequence,
                        )
                    } else {
                        record.pendingTerminalPhase = parsed.phase
                    }
                }
                is ParsedEvent.Ignored -> Unit
                is ParsedEvent.ToolSurfaceViolation -> {
                    projected = failRunLocked(
                        record,
                        RemoteAgentFailureCode.PROTOCOL_MISMATCH,
                        degrade = true,
                        sequence = projectedSequence,
                    )
                }
            }
        }
        projected?.let(record.events::tryEmit)
    }

    private fun parseChatEvent(
        frame: OpenClawEventFrame,
        root: JsonObject,
        record: RunRecord,
    ): ParsedEvent? {
        if (!validEnvelopeSequence(frame.sequence)) return null
        if (root.requiredString("runId", MAX_PROVIDER_ID_CHARACTERS) != record.expectedRunId) return null
        if (root.requiredString("sessionKey", MAX_SESSION_KEY_CHARACTERS) != record.sessionKey) return null
        val agentId = root.optionalString("agentId", MAX_AGENT_ID_CHARACTERS)
        if (root.has("agentId") && agentId == null) return null
        if (agentId != null && agentId != AGENT_ID) return null
        if (!root.optionalSafeString("spawnedBy", MAX_PROVIDER_ID_CHARACTERS)) return null
        val sequence = root.requiredNonNegativeLong("seq") ?: return null
        val state = root.requiredString("state", MAX_STATE_CHARACTERS) ?: return null
        if (root.has("message") && root.get("message").containsToolSurface()) {
            return ParsedEvent.ToolSurfaceViolation(EventChannel.CHAT, sequence)
        }
        val canonicalMessage = parseCanonicalMessage(root)
        if (canonicalMessage == CanonicalMessage.Invalid) return null
        val canonicalText = (canonicalMessage as? CanonicalMessage.Text)?.text
        return when (state) {
            "status" -> {
                if (!root.hasOnly(CHAT_STATUS_FIELDS)) return null
                val phase = root.requiredString("phase", MAX_STATE_CHARACTERS)
                if (phase !in CHAT_STARTUP_PHASES) return null
                ParsedEvent.Phase(EventChannel.CHAT, sequence, RemoteAgentRunPhase.RUNNING)
            }
            "delta" -> {
                if (!root.hasOnly(CHAT_DELTA_FIELDS)) return null
                val text = root.requiredStringAllowEmpty("deltaText") ?: return null
                val replace = root.optionalBoolean("replace") ?: false
                if (root.has("replace") && !root.isBoolean("replace")) return null
                ParsedEvent.Delta(sequence, text, replace, canonicalText)
            }
            "final" -> {
                if (!root.hasOnly(CHAT_FINAL_FIELDS)) return null
                if (!root.optionalSafeString("stopReason", MAX_REASON_CHARACTERS)) return null
                if (!root.optionalBooleanIs("yielded", expected = true)) return null
                ParsedEvent.TerminalSnapshot(
                    sequence,
                    RemoteAgentRunPhase.SUCCEEDED,
                    canonicalText,
                )
            }
            "aborted" -> {
                if (!root.hasOnly(CHAT_ABORTED_FIELDS)) return null
                if (!root.optionalSafeString("errorMessage", MAX_ERROR_CHARACTERS)) return null
                if (!root.optionalSafeString("stopReason", MAX_REASON_CHARACTERS)) return null
                ParsedEvent.TerminalSnapshot(
                    sequence,
                    RemoteAgentRunPhase.CANCELLED,
                    canonicalText,
                )
            }
            "error" -> {
                if (!root.hasOnly(CHAT_ERROR_FIELDS)) return null
                // 2026.9.x adds `errorDetail`. Its bounded shape carries provider prose
                // (`providerErrorMessagePreview`) alongside provider/model identity, so the
                // envelope is validated only so that a real remote failure is not misreported as a
                // protocol violation. Its contents are deliberately never read, exactly like the
                // remote error prose the frame codec already validates and discards.
                if (!root.optionalObject("errorDetail")) return null
                if (!root.optionalSafeString("errorMessage", MAX_ERROR_CHARACTERS)) return null
                if (!root.optionalSafeString("stopReason", MAX_REASON_CHARACTERS)) return null
                val errorKind = root.optionalString("errorKind", MAX_STATE_CHARACTERS)
                if (root.has("errorKind") && errorKind !in CHAT_ERROR_KINDS) return null
                val terminalPhase = if (errorKind == "timeout" ||
                    root.optionalString("stopReason", MAX_REASON_CHARACTERS).isTimeoutReason()
                ) {
                    RemoteAgentRunPhase.TIMED_OUT
                } else {
                    RemoteAgentRunPhase.FAILED
                }
                ParsedEvent.TerminalSnapshot(sequence, terminalPhase, canonicalText)
            }
            else -> null
        }
    }

    private fun parseCanonicalMessage(root: JsonObject): CanonicalMessage {
        if (!root.has("message")) return CanonicalMessage.Missing
        val message = root.get("message").asObjectOrNull() ?: return CanonicalMessage.Invalid
        if (!message.hasOnly(CANONICAL_MESSAGE_FIELDS)) return CanonicalMessage.Invalid
        if (message.requiredString("role", MAX_STATE_CHARACTERS) != "assistant") {
            return CanonicalMessage.Invalid
        }
        message.requiredNonNegativeLong("timestamp") ?: return CanonicalMessage.Invalid
        val content = message.get("content")
            ?.takeIf(JsonElement::isJsonArray)
            ?.asJsonArray
            ?: return CanonicalMessage.Invalid
        if (content.size() != 1) return CanonicalMessage.Invalid
        val item = content[0].asObjectOrNull() ?: return CanonicalMessage.Invalid
        if (!item.hasOnly(CANONICAL_TEXT_FIELDS)) return CanonicalMessage.Invalid
        if (item.requiredString("type", MAX_STATE_CHARACTERS) != "text") {
            return CanonicalMessage.Invalid
        }
        val text = item.requiredStringAllowEmpty("text") ?: return CanonicalMessage.Invalid
        return CanonicalMessage.Text(text)
    }

    private fun parseAgentEvent(
        frame: OpenClawEventFrame,
        root: JsonObject,
        record: RunRecord,
    ): ParsedEvent? {
        if (!validEnvelopeSequence(frame.sequence)) return null
        if (!root.hasOnly(AGENT_EVENT_FIELDS)) return null
        if (root.requiredString("runId", MAX_PROVIDER_ID_CHARACTERS) != record.expectedRunId) return null
        val sequence = root.requiredNonNegativeLong("seq") ?: return null
        root.requiredNonNegativeLong("ts") ?: return null
        val stream = root.requiredString("stream", MAX_STREAM_CHARACTERS) ?: return null
        val data = root.requiredObject("data") ?: return null
        if (!root.optionalSafeString("spawnedBy", MAX_PROVIDER_ID_CHARACTERS)) return null
        if (!root.optionalBoolean("isHeartbeat").isValidOptional(root.has("isHeartbeat"))) return null
        val sessionKey = root.optionalString("sessionKey", MAX_SESSION_KEY_CHARACTERS)
        if (root.has("sessionKey") && sessionKey == null) return null
        if (sessionKey != null && sessionKey != record.sessionKey) return null
        val agentId = root.optionalString("agentId", MAX_AGENT_ID_CHARACTERS)
        if (root.has("agentId") && agentId == null) return null
        if (agentId != null && agentId != AGENT_ID) return null
        if (!root.optionalSafeString("sessionId", MAX_PROVIDER_ID_CHARACTERS)) return null

        if (stream == "tool") {
            return ParsedEvent.ToolSurfaceViolation(EventChannel.AGENT, sequence)
        }
        if (stream != "lifecycle") {
            return ParsedEvent.Ignored(EventChannel.AGENT, sequence)
        }
        val phase = data.requiredString("phase", MAX_STATE_CHARACTERS) ?: return null
        if (!data.optionalBoolean("aborted").isValidOptional(data.has("aborted"))) return null
        if (!data.optionalBoolean("fallbackExhaustedFailure")
                .isValidOptional(data.has("fallbackExhaustedFailure"))
        ) {
            return null
        }
        if (!data.optionalSafeString("stopReason", MAX_REASON_CHARACTERS)) return null
        if (!data.optionalSafeString("status", MAX_STATE_CHARACTERS)) return null
        val timeoutPhase = data.optionalString("timeoutPhase", MAX_STATE_CHARACTERS)
        if (data.has("timeoutPhase") && timeoutPhase !in TIMEOUT_PHASES) return null
        val aborted = data.optionalBoolean("aborted") == true
        val stopReason = data.optionalString("stopReason", MAX_REASON_CHARACTERS)
        return when (phase) {
            "start" -> ParsedEvent.Phase(
                EventChannel.AGENT,
                sequence,
                RemoteAgentRunPhase.RUNNING,
            )
            "end" -> ParsedEvent.TerminalHint(
                EventChannel.AGENT,
                sequence,
                when {
                    aborted || stopReason.isCancellationReason() -> RemoteAgentRunPhase.CANCELLED
                    stopReason.isTimeoutReason() || timeoutPhase.isHardTimeoutPhase() ->
                        RemoteAgentRunPhase.TIMED_OUT
                    data.optionalString("status", MAX_STATE_CHARACTERS).isFailureStatus() ->
                        RemoteAgentRunPhase.FAILED
                    else -> RemoteAgentRunPhase.SUCCEEDED
                },
            )
            "error" -> when {
                aborted || stopReason.isCancellationReason() -> ParsedEvent.TerminalHint(
                    EventChannel.AGENT,
                    sequence,
                    RemoteAgentRunPhase.CANCELLED,
                )
                stopReason.isTimeoutReason() || timeoutPhase.isHardTimeoutPhase() ->
                    ParsedEvent.TerminalHint(
                        EventChannel.AGENT,
                        sequence,
                        RemoteAgentRunPhase.TIMED_OUT,
                    )
                data.optionalBoolean("fallbackExhaustedFailure") == true -> ParsedEvent.TerminalHint(
                    EventChannel.AGENT,
                    sequence,
                    RemoteAgentRunPhase.FAILED,
                )
                else -> ParsedEvent.Ignored(EventChannel.AGENT, sequence)
            }
            else -> null
        }
    }

    private fun parseSessionToolEvent(
        frame: OpenClawEventFrame,
        root: JsonObject,
        record: RunRecord,
    ): ParsedEvent? {
        if (!validEnvelopeSequence(frame.sequence)) return null
        if (root.requiredString("runId", MAX_PROVIDER_ID_CHARACTERS) != record.expectedRunId) return null
        val sessionKey = root.optionalString("sessionKey", MAX_SESSION_KEY_CHARACTERS)
        if (root.has("sessionKey") && sessionKey == null) return null
        if (sessionKey != null && sessionKey != record.sessionKey) return null
        val sequence = root.requiredNonNegativeLong("seq") ?: return null
        return ParsedEvent.ToolSurfaceViolation(EventChannel.AGENT, sequence)
    }

    private fun buildStartParams(
        request: RemoteAgentStartRequest,
        sessionId: String,
        sessionKey: String,
    ): JsonObject = JsonObject().apply {
        addProperty("message", request.prompt.text)
        addProperty("agentId", AGENT_ID)
        addProperty("sessionId", sessionId)
        addProperty("sessionKey", sessionKey)
        addProperty("thinking", THINKING_LEVEL)
        addProperty("deliver", false)
        addProperty("timeout", request.limits.timeoutMillis / 1_000L)
        addProperty("modelRun", true)
        addProperty("promptMode", PROMPT_MODE)
        addProperty("disableMessageTool", true)
        addProperty("cleanupBundleMcpOnRunEnd", true)
        addProperty("idempotencyKey", request.idempotencyKey.value)
    }

    private fun buildWaitParams(runId: RemoteAgentRunId, timeoutMillis: Long): JsonObject =
        JsonObject().apply {
            addProperty("runId", runId.value)
            addProperty("timeoutMs", timeoutMillis)
        }

    private fun buildAbortParams(record: RunRecord, runId: RemoteAgentRunId): JsonObject =
        JsonObject().apply {
            addProperty("sessionKey", record.sessionKey)
            addProperty("agentId", AGENT_ID)
            addProperty("runId", runId.value)
        }

    private fun buildOrderlyAbortParams(target: OrderlyAbortTarget): JsonObject =
        JsonObject().apply {
            addProperty("sessionKey", target.sessionKey)
            addProperty("agentId", AGENT_ID)
            addProperty("runId", target.expectedRunId)
        }

    private fun parseAcceptance(
        payload: JsonElement?,
        expectedRunId: String,
        expectedSessionKey: String,
    ): RemoteAgentRunId? {
        val root = payload.asObjectOrNull() ?: return null
        if (!root.hasOnly(ACCEPTANCE_FIELDS)) return null
        if (root.requiredString("runId", MAX_PROVIDER_ID_CHARACTERS) != expectedRunId) return null
        val runId = RemoteAgentRunId.parse(expectedRunId) ?: return null
        val status = root.requiredString("status", MAX_STATE_CHARACTERS) ?: return null
        val sessionKey = root.optionalString("sessionKey", MAX_SESSION_KEY_CHARACTERS)
        if (root.has("sessionKey") && sessionKey == null) return null
        if (sessionKey != null && sessionKey != expectedSessionKey) return null
        val agentId = root.optionalString("agentId", MAX_AGENT_ID_CHARACTERS)
        if (root.has("agentId") && agentId == null) return null
        if (agentId != null && agentId != AGENT_ID) return null
        if (root.has("runtime") && !root.get("runtime").isJsonObject) return null
        if (root.has("admissionPending") && !root.isBoolean("admissionPending")) return null
        return when (status) {
            "accepted" -> runId.takeIf {
                sessionKey == expectedSessionKey &&
                    agentId == AGENT_ID &&
                    root.requiredNonNegativeLong("acceptedAt") != null &&
                    !root.has("admissionPending")
            }
            "in_flight" -> runId.takeIf {
                !root.has("acceptedAt") &&
                    (!root.has("admissionPending") || root.optionalBoolean("admissionPending") == true)
            }
            else -> null
        }
    }

    private fun parseWaitResult(payload: JsonElement?, expectedRunId: String): ParsedWait? {
        val root = payload.asObjectOrNull() ?: return null
        if (!root.hasOnly(WAIT_RESULT_FIELDS)) return null
        if (root.requiredString("runId", MAX_PROVIDER_ID_CHARACTERS) != expectedRunId) return null
        val status = root.requiredString("status", MAX_STATE_CHARACTERS) ?: return null
        if (!root.optionalSafeString("error", MAX_ERROR_CHARACTERS)) return null
        if (!root.optionalSafeString("stopReason", MAX_REASON_CHARACTERS)) return null
        if (!root.optionalSafeString("livenessState", MAX_STATE_CHARACTERS)) return null
        if (!root.optionalNonNegativeLong("startedAt")) return null
        if (!root.optionalNonNegativeLong("endedAt")) return null
        if (!root.optionalBoolean("yielded").isValidOptional(root.has("yielded"))) return null
        if (!root.optionalBoolean("pendingError").isValidOptional(root.has("pendingError"))) return null
        if (!root.optionalBoolean("providerStarted").isValidOptional(root.has("providerStarted"))) {
            return null
        }
        val timeoutPhase = root.optionalString("timeoutPhase", MAX_STATE_CHARACTERS)
        if (root.has("timeoutPhase") && timeoutPhase !in TIMEOUT_PHASES) return null
        if (!root.optionalObject("terminalDelivery")) return null
        if (!root.optionalObject("terminalReceipt")) return null
        val terminalReply = parseTerminalReply(root) ?: return null

        val stopReason = root.optionalString("stopReason", MAX_REASON_CHARACTERS)
        val providerStarted = root.optionalBoolean("providerStarted") == true
        if (stopReason.isCancellationReason()) {
            return ParsedWait.Terminal(RemoteAgentRunPhase.CANCELLED, terminalReply)
        }
        if (stopReason.isTimeoutReason()) {
            return ParsedWait.Terminal(RemoteAgentRunPhase.TIMED_OUT, terminalReply)
        }
        return when (status) {
            "ok" -> ParsedWait.Terminal(RemoteAgentRunPhase.SUCCEEDED, terminalReply)
            "error" -> if (root.optionalBoolean("pendingError") == true && !root.has("endedAt")) {
                ParsedWait.Pending(RemoteAgentRunPhase.RUNNING)
            } else {
                ParsedWait.Terminal(RemoteAgentRunPhase.FAILED, terminalReply)
            }
            "pending" -> ParsedWait.Pending(
                if (!providerStarted && timeoutPhase == "queue") {
                    RemoteAgentRunPhase.QUEUED
                } else {
                    RemoteAgentRunPhase.RUNNING
                },
            )
            "timeout" -> if (providerStarted || timeoutPhase.isHardTimeoutPhase()) {
                ParsedWait.Terminal(RemoteAgentRunPhase.TIMED_OUT, terminalReply)
            } else {
                ParsedWait.ObservationTimedOut
            }
            else -> null
        }
    }

    private fun parseAbortResult(payload: JsonElement?, expectedRunId: String): ParsedAbort? {
        val root = payload.asObjectOrNull() ?: return null
        if (!root.hasOnly(ABORT_RESULT_FIELDS)) return null
        if (root.optionalBoolean("ok") != true || !root.has("ok")) return null
        val aborted = root.optionalBoolean("aborted") ?: return null
        val runIds = root.get("runIds")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (runIds.size() > MAX_ABORT_RUN_IDS) return null
        val parsedRunIds = runIds.map { element ->
            element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.takeIf { it.length <= MAX_PROVIDER_ID_CHARACTERS }
                ?: return null
        }
        if (parsedRunIds.distinct().size != parsedRunIds.size) return null
        return when {
            aborted && parsedRunIds == listOf(expectedRunId) -> ParsedAbort.ACCEPTED
            !aborted && parsedRunIds.isEmpty() -> ParsedAbort.ALREADY_TERMINAL
            else -> null
        }
    }

    private fun parseTerminalReply(root: JsonObject): CanonicalOutput? {
        if (!root.has("terminalReply")) return CanonicalOutput.Missing
        val reply = root.get("terminalReply").asObjectOrNull() ?: return null
        val disposition = reply.requiredString("disposition", MAX_STATE_CHARACTERS) ?: return null
        return when (disposition) {
            "visible" -> {
                if (!reply.hasOnly(TERMINAL_REPLY_VISIBLE_FIELDS)) return null
                CanonicalOutput.Visible(reply.requiredStringAllowEmpty("text") ?: return null)
            }
            "silent", "empty" -> {
                if (!reply.hasOnly(TERMINAL_REPLY_HIDDEN_FIELDS)) return null
                CanonicalOutput.Empty
            }
            else -> null
        }
    }

    private fun applyWaitTerminal(
        record: RunRecord,
        parsed: ParsedWait.Terminal,
    ): AppliedTerminal {
        var projected: RemoteAgentEvent? = null
        var failureCode: RemoteAgentFailureCode? = null
        val status = synchronized(lock) {
            if (record.phase.isTerminal) return@synchronized statusLocked(record)
            // The wait result is the other way a terminal reaches this client, and OpenClaw wraps
            // an abort the same way here as it does on the chat channel.
            val waitPhase = reconcileRequestedAbortLocked(record, parsed.phase)
            if (record.pendingTerminalPhase != null && record.pendingTerminalPhase != waitPhase) {
                failureCode = RemoteAgentFailureCode.PROTOCOL_MISMATCH
            }
            val currentText = record.outputText.toString()
            val canonicalText = when (val output = parsed.output) {
                CanonicalOutput.Missing -> null
                CanonicalOutput.Empty -> {
                    if (currentText.isNotEmpty()) {
                        failureCode = RemoteAgentFailureCode.PROTOCOL_MISMATCH
                    }
                    ""
                }
                is CanonicalOutput.Visible -> output.text
            }
            val deltaText = if (failureCode == null && canonicalText != null) {
                if (!canonicalText.startsWith(currentText)) {
                    failureCode = RemoteAgentFailureCode.PROTOCOL_MISMATCH
                    ""
                } else {
                    canonicalText.substring(currentText.length)
                }
            } else {
                ""
            }
            if (failureCode == null && deltaText.isNotEmpty()) {
                if (record.observedEvents >= record.limits.maxEvents) {
                    failureCode = RemoteAgentFailureCode.LIMIT_EXCEEDED
                } else {
                    record.observedEvents += 1
                    val sequence = record.observedEvents
                    val utf8Bytes = deltaText.toByteArray(Charsets.UTF_8).size
                    if (utf8Bytes > RemoteAgentContractLimits.MAX_EVENT_TEXT_UTF8_BYTES ||
                        utf8Bytes > record.limits.maxOutputUtf8Bytes - record.observedOutputUtf8Bytes
                    ) {
                        failureCode = RemoteAgentFailureCode.LIMIT_EXCEEDED
                        projected = failRunLocked(
                            record,
                            RemoteAgentFailureCode.LIMIT_EXCEEDED,
                            degrade = false,
                            sequence = sequence,
                        )
                    } else {
                        projected = RemoteAgentEvent.TextDelta.create(
                            runId = checkNotNull(record.publicRunId()),
                            sequence = sequence,
                            text = deltaText,
                        )
                        if (projected == null) {
                            failureCode = RemoteAgentFailureCode.PROTOCOL_MISMATCH
                        } else {
                            record.observedOutputUtf8Bytes += utf8Bytes
                            record.outputText.append(deltaText)
                        }
                    }
                }
            }
            if (failureCode != null && !record.phase.isTerminal) {
                if (record.observedEvents < record.limits.maxEvents) {
                    record.observedEvents += 1
                    projected = failRunLocked(
                        record,
                        checkNotNull(failureCode),
                        degrade = failureCode == RemoteAgentFailureCode.PROTOCOL_MISMATCH,
                        sequence = record.observedEvents,
                    )
                } else {
                    failRunLocked(
                        record,
                        checkNotNull(failureCode),
                        degrade = failureCode == RemoteAgentFailureCode.PROTOCOL_MISMATCH,
                    )
                }
            } else if (failureCode == null) {
                updatePhaseLocked(record, waitPhase, authoritative = true)
            }
            statusLocked(record)
        }
        projected?.let(record.events::tryEmit)
        return AppliedTerminal(status, failureCode)
    }

    private fun reserveRunSlotLocked(): Boolean {
        if (runs.size < MAX_TRACKED_RUNS) return true
        val evictable = runs.values
            .filter { it.accepted && it.phase.isTerminal && it.authoritativeTerminal }
            .minByOrNull(RunRecord::createdOrder)
            ?: return false
        runs.remove(evictable.expectedRunId, evictable)
        return true
    }

    private fun statusLocked(record: RunRecord): RemoteAgentRunStatus = RemoteAgentRunStatus(
        runId = checkNotNull(record.publicRunId()),
        phase = record.phase,
        observedEvents = record.observedEvents,
        observedOutputUtf8Bytes = record.observedOutputUtf8Bytes,
    )

    private fun updatePhaseLocked(
        record: RunRecord,
        phase: RemoteAgentRunPhase,
        authoritative: Boolean = phase.isTerminal,
    ) {
        if (!record.phase.isTerminal) {
            record.phase = phase
            if (phase.isTerminal) record.authoritativeTerminal = authoritative
        }
    }

    private fun markProtocolFailureLocked(record: RunRecord) {
        failRunLocked(record, RemoteAgentFailureCode.PROTOCOL_MISMATCH, degrade = true)
    }

    private fun failRunLocked(
        record: RunRecord,
        code: RemoteAgentFailureCode,
        degrade: Boolean,
        sequence: Int? = null,
    ): RemoteAgentEvent.Failed? {
        if (!record.phase.isTerminal) record.phase = RemoteAgentRunPhase.FAILED
        record.authoritativeTerminal = false
        if (degrade) {
            degraded = true
            refreshConnectionStateLocked()
        }
        val runId = record.publicRunId() ?: return null
        return sequence?.let { RemoteAgentEvent.Failed(runId, it, code) }
    }

    private fun refreshConnectionStateLocked() {
        mutableConnectionState.value = if (
            degraded && rawConnectionState == OpenClawRpcConnectionState.READY
        ) {
            RemoteAgentConnectionState.DEGRADED
        } else {
            mapConnectionState(rawConnectionState)
        }
    }

    private fun newModelRunSession(): ModelRunSession {
        val sessionId = "$MODEL_RUN_SESSION_ID_PREFIX${UUID.randomUUID()}"
        return ModelRunSession(
            sessionId = sessionId,
            sessionKey = "$MODEL_RUN_SESSION_KEY_PREFIX$sessionId",
        )
    }

    private fun requestFingerprint(request: RemoteAgentStartRequest): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val encoded = value.toByteArray(Charsets.UTF_8)
            digest.update(encoded.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(encoded)
            digest.update(0)
        }
        field(FINGERPRINT_DOMAIN)
        field(request.sessionId.value)
        field(request.idempotencyKey.value)
        field(request.limits.maxEvents.toString())
        field(request.limits.maxOutputUtf8Bytes.toString())
        field(request.limits.timeoutMillis.toString())
        field(request.prompt.text)
        return digest.digest().toHex()
    }

    private class RunRecord(
        val expectedRunId: String,
        val sessionId: String,
        val sessionKey: String,
        val fingerprint: String,
        val limits: RemoteAgentRunLimits,
        val createdOrder: Long,
    ) {
        val submissionMutex = Mutex()
        val events = MutableSharedFlow<RemoteAgentEvent>(replay = limits.maxEvents)
        var accepted = false
        var outcomeUnknown = false
        var runId: RemoteAgentRunId? = null
        var phase = RemoteAgentRunPhase.QUEUED
        var authoritativeTerminal = false
        var earlyProtocolViolation = false
        var pendingTerminalPhase: RemoteAgentRunPhase? = null

        /**
         * Set only by an accepted `chat.abort` acknowledgement naming this exact run.
         *
         * A refusal, a protocol mismatch, or an `already terminal` answer all leave it false, so
         * it means "the Gateway told us it stopped this live run". See
         * [reconcileRequestedAbortLocked].
         */
        var abortRequested = false
        var observedEvents = 0
        var observedOutputUtf8Bytes = 0
        val outputText = StringBuilder()
        var lastChatSequence: Long? = null
        var lastAgentSequence: Long? = null

        fun publicRunId(): RemoteAgentRunId? = runId.takeIf { accepted }

        override fun toString(): String =
            "RunRecord(identity=<redacted>, accepted=$accepted, phase=$phase, " +
                "observedEvents=$observedEvents, observedOutputUtf8Bytes=$observedOutputUtf8Bytes)"
    }

    private class StartPreparation(
        val record: RunRecord? = null,
        val result: RemoteAgentStartResult? = null,
    )

    private class ModelRunSession(
        val sessionId: String,
        val sessionKey: String,
    )

    private class OrderlyAbortTarget(
        val expectedRunId: String,
        val sessionKey: String,
    )

    private enum class EventChannel { CHAT, AGENT }

    private sealed interface ParsedEvent {
        val channel: EventChannel
        val providerSequence: Long

        class Delta(
            override val providerSequence: Long,
            val text: String,
            val replace: Boolean,
            val canonicalText: String?,
        ) : ParsedEvent {
            override val channel: EventChannel = EventChannel.CHAT
            override fun toString(): String =
                "ParsedEvent.Delta(sequence=$providerSequence, text=<redacted>)"
        }

        class Phase(
            override val channel: EventChannel,
            override val providerSequence: Long,
            val phase: RemoteAgentRunPhase,
        ) : ParsedEvent

        class TerminalSnapshot(
            override val providerSequence: Long,
            val phase: RemoteAgentRunPhase,
            val text: String?,
        ) : ParsedEvent {
            override val channel: EventChannel = EventChannel.CHAT
            override fun toString(): String =
                "ParsedEvent.TerminalSnapshot(sequence=$providerSequence, phase=$phase, " +
                    "text=<redacted>)"
        }

        class TerminalHint(
            override val channel: EventChannel,
            override val providerSequence: Long,
            val phase: RemoteAgentRunPhase,
        ) : ParsedEvent

        class Ignored(
            override val channel: EventChannel,
            override val providerSequence: Long,
        ) : ParsedEvent

        class ToolSurfaceViolation(
            override val channel: EventChannel,
            override val providerSequence: Long,
        ) : ParsedEvent
    }

    private sealed interface ParsedWait {
        class Terminal(
            val phase: RemoteAgentRunPhase,
            val output: CanonicalOutput,
        ) : ParsedWait
        class Pending(val phase: RemoteAgentRunPhase) : ParsedWait
        data object ObservationTimedOut : ParsedWait
    }

    private sealed interface CanonicalMessage {
        data object Missing : CanonicalMessage
        data object Invalid : CanonicalMessage
        class Text(val text: String) : CanonicalMessage {
            override fun toString(): String = "CanonicalMessage.Text(<redacted>)"
        }
    }

    private sealed interface CanonicalOutput {
        data object Missing : CanonicalOutput
        data object Empty : CanonicalOutput
        class Visible(val text: String) : CanonicalOutput {
            override fun toString(): String = "CanonicalOutput.Visible(<redacted>)"
        }
    }

    private class AppliedTerminal(
        val status: RemoteAgentRunStatus,
        val failureCode: RemoteAgentFailureCode?,
    )

    private enum class ParsedAbort { ACCEPTED, ALREADY_TERMINAL }

    private companion object {
        const val EVENT_CHAT = "chat"
        const val EVENT_AGENT = "agent"
        const val EVENT_SESSION_TOOL = "session.tool"
        const val AGENT_ID = "main"
        const val THINKING_LEVEL = "low"
        const val PROMPT_MODE = "none"
        const val MODEL_RUN_SESSION_ID_PREFIX = "model-run-"
        const val MODEL_RUN_SESSION_KEY_PREFIX = "agent:main:explicit:"
        const val FINGERPRINT_DOMAIN = "PersonalEdge/OpenClaw/request/v2026.8.1"
        const val MAX_TRACKED_RUNS = OpenClawProtocol.MAX_ORDERLY_ABORT_REQUESTS
        const val MAX_ABORT_RUN_IDS = 1
        const val MAX_PROVIDER_ID_CHARACTERS = 128
        const val MAX_SESSION_KEY_CHARACTERS = 192
        const val MAX_AGENT_ID_CHARACTERS = 64
        const val MAX_STATE_CHARACTERS = 64
        const val MAX_STREAM_CHARACTERS = 64
        const val MAX_REASON_CHARACTERS = 256
        const val MAX_ERROR_CHARACTERS = 4_096
        const val STATUS_REQUEST_TIMEOUT_MILLIS = 5_000L
        const val MAX_REMOTE_WAIT_CHUNK_MILLIS = 25_000L
        const val WAIT_RESPONSE_GRACE_MILLIS = 2_000L

        val CHAT_STARTUP_PHASES = setOf(
            "preparing_workspace",
            "naming_worktree",
            "creating_worktree",
            "running_setup",
            "provisioning_environment",
            "preparing_context",
            "starting_model",
        )
        val CHAT_ERROR_KINDS = setOf(
            "refusal",
            "timeout",
            "rate_limit",
            "context_length",
            "unknown",
        )
        val TIMEOUT_PHASES = setOf("queue", "preflight", "provider", "post_turn", "gateway_draining")
        val SUPPORTED_RUN_EVENTS = setOf(EVENT_CHAT, EVENT_AGENT, EVENT_SESSION_TOOL)
        val ACCEPTANCE_FIELDS = setOf(
            "runId",
            "status",
            "sessionKey",
            "agentId",
            "acceptedAt",
            "runtime",
            "admissionPending",
        )
        val WAIT_RESULT_FIELDS = setOf(
            "runId",
            "status",
            "error",
            "startedAt",
            "endedAt",
            "stopReason",
            "livenessState",
            "yielded",
            "pendingError",
            "timeoutPhase",
            "providerStarted",
            "terminalDelivery",
            "terminalReceipt",
            "terminalReply",
        )
        val ABORT_RESULT_FIELDS = setOf("ok", "aborted", "runIds")
        val CHAT_BASE_FIELDS = setOf("runId", "sessionKey", "agentId", "spawnedBy", "seq", "state")
        val CHAT_STATUS_FIELDS = CHAT_BASE_FIELDS + "phase"
        val CHAT_DELTA_FIELDS = CHAT_BASE_FIELDS + setOf("message", "deltaText", "replace", "usage")
        val CHAT_FINAL_FIELDS = CHAT_BASE_FIELDS + setOf("message", "usage", "stopReason", "yielded")
        val CHAT_ABORTED_FIELDS = CHAT_BASE_FIELDS + setOf("message", "errorMessage", "stopReason")
        val CHAT_ERROR_FIELDS = CHAT_BASE_FIELDS +
            setOf("message", "errorMessage", "errorKind", "errorDetail", "usage", "stopReason")
        val AGENT_EVENT_FIELDS = setOf(
            "runId",
            "seq",
            "stream",
            "ts",
            "spawnedBy",
            "isHeartbeat",
            "data",
            "sessionKey",
            "sessionId",
            "agentId",
        )
        val TERMINAL_REPLY_VISIBLE_FIELDS = setOf("disposition", "text")
        val TERMINAL_REPLY_HIDDEN_FIELDS = setOf("disposition")
        val CANONICAL_MESSAGE_FIELDS = setOf("role", "content", "timestamp")
        val CANONICAL_TEXT_FIELDS = setOf("type", "text")

        fun mapConnectionState(state: OpenClawRpcConnectionState): RemoteAgentConnectionState =
            when (state) {
                OpenClawRpcConnectionState.DISCONNECTED -> RemoteAgentConnectionState.DISCONNECTED
                OpenClawRpcConnectionState.CONNECTING -> RemoteAgentConnectionState.CONNECTING
                OpenClawRpcConnectionState.READY -> RemoteAgentConnectionState.CONNECTED
            }

        fun mapNotSent(reason: OpenClawRpcNotSentReason): RemoteAgentFailureCode = when (reason) {
            OpenClawRpcNotSentReason.NOT_CONNECTED -> RemoteAgentFailureCode.NOT_CONNECTED
            OpenClawRpcNotSentReason.TOO_MANY_PENDING,
            OpenClawRpcNotSentReason.BACKPRESSURE,
            -> RemoteAgentFailureCode.REMOTE_UNAVAILABLE
            OpenClawRpcNotSentReason.INVALID_REQUEST -> RemoteAgentFailureCode.INTERNAL_ERROR
        }

        fun mapRejected(code: String): RemoteAgentFailureCode = when (code.uppercase(Locale.ROOT)) {
            "NOT_PAIRED", "NOT_LINKED", "FORBIDDEN", "UNAUTHORIZED", "AUTHENTICATION_FAILED" ->
                RemoteAgentFailureCode.AUTHENTICATION_FAILED
            "INVALID_REQUEST", "BAD_REQUEST", "REQUEST_REJECTED" ->
                RemoteAgentFailureCode.REQUEST_REJECTED
            "UNAVAILABLE", "BUSY", "RATE_LIMITED", "AGENT_TIMEOUT" ->
                RemoteAgentFailureCode.REMOTE_UNAVAILABLE
            else -> RemoteAgentFailureCode.INTERNAL_ERROR
        }
    }
}

private class OpenClawClientRpcPort(private val client: OpenClawRpcClient) : OpenClawGatewayRpcPort {
    override val connectionState: StateFlow<OpenClawRpcConnectionState> = client.connectionState
    override val events: Flow<OpenClawEventFrame> = client.events

    override suspend fun request(
        method: String,
        params: JsonElement?,
        timeoutMillis: Long,
    ): OpenClawRpcResult = client.request(method, params, timeoutMillis)

    override fun sealPostConnectRequests() = client.sealPostConnectRequests()

    override fun enqueueWithoutResponse(
        method: String,
        params: JsonElement?,
    ): OpenClawRpcEnqueueResult = client.enqueueWithoutResponse(method, params)
}

private fun JsonElement?.asObjectOrNull(): JsonObject? =
    this?.takeIf(JsonElement::isJsonObject)?.asJsonObject

private fun JsonObject.hasOnly(allowed: Set<String>): Boolean = keySet().all(allowed::contains)

private fun JsonObject.routingString(name: String): String? = get(name)
    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
    ?.asString
    ?.takeIf { value ->
        value.isNotEmpty() &&
            value.length <= RemoteAgentContractLimits.MAX_RUN_ID_CHARACTERS &&
            value.none(Char::isISOControl)
    }

private fun JsonObject.requiredString(name: String, maxCharacters: Int): String? = get(name)
    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
    ?.asString
    ?.takeIf { it.isNotEmpty() && it.length <= maxCharacters && it.none(Char::isISOControl) }

private fun JsonObject.requiredStringAllowEmpty(name: String): String? = get(name)
    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
    ?.asString
    ?.takeIf { '\u0000' !in it }

private fun JsonObject.optionalString(name: String, maxCharacters: Int): String? =
    if (!has(name)) {
        null
    } else {
        requiredString(name, maxCharacters)
    }

private fun JsonObject.optionalSafeString(name: String, maxCharacters: Int): Boolean =
    !has(name) || requiredString(name, maxCharacters) != null

private fun JsonObject.requiredObject(name: String): JsonObject? =
    get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

private fun JsonObject.requiredNonNegativeLong(name: String): Long? = get(name).exactLongOrNull()
    ?.takeIf { it >= 0L }

private fun JsonObject.optionalNonNegativeLong(name: String): Boolean =
    !has(name) || requiredNonNegativeLong(name) != null

private fun JsonObject.optionalBoolean(name: String): Boolean? = get(name)
    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
    ?.asBoolean

private fun JsonObject.isBoolean(name: String): Boolean = get(name)
    ?.takeIf(JsonElement::isJsonPrimitive)
    ?.asJsonPrimitive
    ?.isBoolean == true

private fun JsonObject.optionalBooleanIs(name: String, expected: Boolean): Boolean =
    !has(name) || optionalBoolean(name) == expected

private fun JsonObject.optionalObject(name: String): Boolean =
    !has(name) || get(name).isJsonObject

private fun Boolean?.isValidOptional(present: Boolean): Boolean = !present || this != null

private fun JsonElement?.exactLongOrNull(): Long? {
    val primitive = this?.takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive ?: return null
    if (!primitive.isNumber) return null
    return runCatching { BigDecimal(primitive.asString).longValueExact() }.getOrNull()
}

private fun validEnvelopeSequence(sequence: Long?): Boolean = sequence == null || sequence >= 0L

private fun JsonElement.containsToolSurface(depth: Int = 0): Boolean {
    if (depth > MAX_TOOL_SCAN_DEPTH) return true
    return when {
        isJsonArray -> asJsonArray.any { it.containsToolSurface(depth + 1) }
        isJsonObject -> asJsonObject.entrySet().any { (name, value) ->
            name in TOOL_STRUCTURE_FIELDS ||
                name == "role" && value.matchesString(TOOL_ROLE_VALUES) ||
                name == "type" && value.matchesString(TOOL_CONTENT_TYPES) ||
                value.containsToolSurface(depth + 1)
        }
        else -> false
    }
}

private fun JsonElement.matchesString(values: Set<String>): Boolean =
    isJsonPrimitive && asJsonPrimitive.isString && asString.lowercase(Locale.ROOT) in values

private const val MAX_TOOL_SCAN_DEPTH = 12
private val TOOL_STRUCTURE_FIELDS = setOf(
    "tool",
    "toolCall",
    "toolCalls",
    "toolCallId",
    "tool_call",
    "tool_calls",
    "tool_call_id",
    "toolUse",
    "toolUseId",
    "toolResult",
    "tool_use",
    "tool_use_id",
    "tool_result",
)
private val TOOL_ROLE_VALUES = setOf("tool", "function")
private val TOOL_CONTENT_TYPES = setOf(
    "tool",
    "tool_call",
    "tool_result",
    "tool_use",
    "function_call",
    "function_result",
)

private fun String?.isCancellationReason(): Boolean = when (this?.lowercase(Locale.ROOT)) {
    "superseded", "cancelled", "canceled", "aborted" -> true
    else -> false
}

private fun String?.isTimeoutReason(): Boolean = when (this?.lowercase(Locale.ROOT)) {
    "hard_timeout", "timed_out", "timeout" -> true
    else -> false
}

private fun String?.isFailureStatus(): Boolean = when (this?.lowercase(Locale.ROOT)) {
    "blocked", "abandoned", "failed", "error" -> true
    else -> false
}

private fun String?.isHardTimeoutPhase(): Boolean = this in setOf("preflight", "provider", "post_turn")

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
}
