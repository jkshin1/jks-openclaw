package com.personaledge.core.agent

import com.personaledge.core.llm.InferenceBackend
import com.personaledge.core.llm.LlmFailureCode
import com.personaledge.core.llm.LlmRuntime
import com.personaledge.core.llm.LlmRuntimeException
import com.personaledge.core.llm.LlmState
import com.personaledge.core.llm.LlmToolCall
import com.personaledge.core.llm.LlmToolDefinition
import com.personaledge.core.llm.LlmTurnToolScope
import com.personaledge.core.llm.ModelEvent
import com.personaledge.core.llm.TrustedToolResponse
import com.personaledge.core.llm.TurnId
import com.personaledge.core.llm.TurnMediaAttachment
import com.personaledge.core.llm.TurnMediaBudget
import com.personaledge.core.llm.TurnMediaKind
import com.personaledge.core.llm.VerifiedInstalledModel
import com.personaledge.core.tools.AgentTool
import com.personaledge.core.tools.AlarmNextTool
import com.personaledge.core.tools.AlarmSetTool
import com.personaledge.core.tools.CalendarCreateEventTool
import com.personaledge.core.tools.CalendarUpdateEventTool
import com.personaledge.core.tools.CommitmentProposalTool
import com.personaledge.core.tools.KakaoNotificationReplyTool
import com.personaledge.core.tools.KakaoShareMessageTool
import com.personaledge.core.tools.MemoryRememberTool
import com.personaledge.core.tools.NotificationSearchTool
import com.personaledge.core.tools.PreparationResult
import com.personaledge.core.tools.ReminderCreateParams
import com.personaledge.core.tools.ReminderCreateTool
import com.personaledge.core.tools.ReminderCancelTool
import com.personaledge.core.tools.ReminderQueryTool
import com.personaledge.core.tools.ReminderUpdateParams
import com.personaledge.core.tools.ReminderUpdateTool
import com.personaledge.core.tools.ToolOrchestrator
import com.personaledge.core.tools.ToolExecutionOutcome
import com.personaledge.core.tools.ToolFailureCode
import com.personaledge.core.tools.ToolInvocationFailureException
import com.personaledge.core.tools.ToolParams
import com.personaledge.core.tools.ToolRisk
import com.personaledge.core.tools.WeatherParams
import com.personaledge.core.tools.WeatherResult
import com.personaledge.core.tools.WeatherTool
import com.personaledge.core.tools.WebSearchParams
import com.personaledge.core.tools.WebSearchTool
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class AgentFailureCode {
    BUSY,
    INVALID_TURN,
    TURN_MISMATCH,
    INVALID_MODEL_SEQUENCE,
    MODEL_FAILURE,
    STEP_LIMIT_EXCEEDED,
    TOOL_CALL_LIMIT_EXCEEDED,
    UNKNOWN_TOOL,
    INVALID_TOOL_CALL,
    TOOL_NOT_EXECUTED,
    TOOL_FAILED,
    DEADLINE_EXCEEDED,
}

/** App-classified failure metadata; [toolName] comes only from the resolved closed registry. */
data class ToolFailureDetail(
    val toolName: String,
    val failureCode: ToolFailureCode,
)

/** Only a trusted READ_ONLY descriptor can turn an exception into a known no-result failure. */
internal fun readOnlyToolFailureDetailOrNull(
    toolName: String,
    risk: ToolRisk,
    failureCode: ToolFailureCode,
): ToolFailureDetail? = if (risk == ToolRisk.READ_ONLY) {
    ToolFailureDetail(toolName = toolName, failureCode = failureCode)
} else {
    null
}

/** A closed validation failure before execution is known-safe for every Tool risk. */
internal fun preparationToolFailureDetail(
    toolName: String,
    failureCode: ToolFailureCode,
): ToolFailureDetail = ToolFailureDetail(toolName = toolName, failureCode = failureCode)

/**
 * App-owned, content-free authorization for one trusted side-effect execution boundary.
 *
 * [toolName] and [risk] come from the resolved Tool descriptor, never model output. Returning
 * false or throwing prevents both the durable ledger claim and Tool invocation.
 */
fun interface SideEffectTurnGate {
    suspend fun allow(
        turnId: TurnId,
        toolName: String,
        risk: ToolRisk,
    ): Boolean
}

sealed interface AgentEvent {
    val turnId: TurnId

    /** Ephemeral model reasoning for presentation only; never a final answer or Tool input. */
    data class ThoughtDelta(
        override val turnId: TurnId,
        val text: String,
    ) : AgentEvent {
        override fun toString(): String =
            "AgentEvent.ThoughtDelta(turnId=$turnId, text=<redacted>)"
    }

    data class TextDelta(
        override val turnId: TurnId,
        val text: String,
    ) : AgentEvent {
        override fun toString(): String =
            "AgentEvent.TextDelta(turnId=$turnId, text=<redacted>)"
    }

    /** Complete app-authored answer derived only from a validated typed Tool result. */
    data class TrustedAnswer(
        override val turnId: TurnId,
        val text: String,
    ) : AgentEvent {
        init {
            require(text.isNotEmpty())
        }

        override fun toString(): String =
            "AgentEvent.TrustedAnswer(turnId=$turnId, text=<redacted>)"
    }

    /** Contains only trusted registry metadata; model arguments are intentionally omitted. */
    data class ToolExecuted(
        override val turnId: TurnId,
        val toolName: String,
        val ordinal: Int,
        val outcome: ToolExecutionOutcome,
    ) : AgentEvent

    data class Completed(
        override val turnId: TurnId,
    ) : AgentEvent

    data class Failure(
        override val turnId: TurnId,
        val code: AgentFailureCode,
        /** Safe typed runtime detail; exception text is never propagated. */
        val runtimeCode: LlmFailureCode? = null,
        /** Safe typed Tool detail; provider/user text and exception messages are never propagated. */
        val toolFailure: ToolFailureDetail? = null,
    ) : AgentEvent {
        init {
            require((code == AgentFailureCode.TOOL_FAILED) == (toolFailure != null))
            require(runtimeCode == null || code == AgentFailureCode.MODEL_FAILURE)
            require(toolFailure == null || runtimeCode == null)
        }
    }
}

/**
 * Serial, confirmation-gated tool loop.
 *
 * The controller treats model output as untrusted until registry resolution, strict decoding,
 * tool validation and confirmation all succeed. It never retries a tool-response injection.
 */
class ManualToolAgentController(
    private val runtime: LlmRuntime,
    private val registry: ManualToolRegistry,
    private val orchestrator: ToolOrchestrator,
    private val agentPlanBridge: AgentPlanExecutionBridge? = null,
    private val limits: AgentLoopLimits = AgentLoopLimits(),
    private val forceExplicitToolScope: Boolean = false,
    private val monotonicClockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sideEffectTurnGate: SideEffectTurnGate =
        SideEffectTurnGate { _, _, _ -> true },
) {
    /**
     * Budget for a turn that must not touch a tool.
     *
     * `maxSteps = 1` is what enforces it: the loop aborts as soon as a completed step contains a
     * tool call, before the tool is prepared, so no confirmation dialog can appear behind a
     * background summarization and no side effect is possible.
     */
    val toolFreeLimits: AgentLoopLimits = AgentLoopLimits(
        maxSteps = 1,
        deadlineMillis = limits.deadlineMillis,
        maxOutputTokens = TOOL_FREE_OUTPUT_TOKENS,
        maxToolCalls = 1,
        maxToolArgumentBytes = limits.maxToolArgumentBytes,
    )
    val toolDefinitions: List<LlmToolDefinition>
        get() = registry.definitions

    fun limitsForPrompt(
        prompt: String,
        inheritLongFormRequest: Boolean = false,
    ): AgentLoopLimits = if (inheritLongFormRequest) {
        limits
    } else {
        TurnOutputBudgetPolicy.forPrompt(prompt, limits)
    }

    /** Initializes the runtime with the exact same closed registry used for later resolution. */
    suspend fun initialize(
        model: VerifiedInstalledModel,
        backend: InferenceBackend = InferenceBackend.CPU,
        mediaModalities: Set<TurnMediaKind> = emptySet(),
    ) {
        runtime.initialize(
            model = model,
            backend = backend,
            tools = registry.definitions,
            mediaModalities = mediaModalities,
        )
    }

    private val activeTurn = AtomicReference<ActiveTurn?>(null)
    private val retainedExecutions = AtomicReference<RetainedExecutions?>(null)

    /**
     * The previous automatically scoped request of the conversation the caller is showing.
     *
     * This is transient in-process state, never a durable authorization: it only lets a reply
     * that carries no domain of its own reuse the scope its own request already earned. The
     * caller must clear it whenever the visible conversation changes, and process death simply
     * returns the next reply to normal classification.
     */
    private val pendingFollowUp = AtomicReference<PendingTurnFollowUp?>(null)

    /** Drops the carry-over when the caller switches, replaces, or deletes the conversation. */
    fun clearFollowUpContext() {
        pendingFollowUp.set(null)
    }

    /**
     * Returns the request [reply] would resume, without consuming the carry-over.
     *
     * The caller uses this to tell the model the owner has already answered, so a small local
     * model repeats neither the question nor the request. The turn itself resolves the carry-over
     * again; both go through the same pure policy and therefore agree.
     */
    fun resumedRequestOrNull(reply: String): String? {
        val pending = pendingFollowUp.get() ?: return null
        val availableToolNames = registry.definitions.mapTo(linkedSetOf()) { it.name }
        val scoped = TurnToolScopePolicy.forPrompt(reply, availableToolNames)
        return resumeOrNull(reply, pending, scoped, availableToolNames)?.requestText
    }

    /**
     * An answer to this app's own question wins over classifying the answer as a request:
     * "리마인더 알림" names a domain but asks for nothing on its own. Without a question pending,
     * only a reply that carries no domain at all may inherit the previous scope.
     */
    private fun resumeOrNull(
        reply: String,
        pending: PendingTurnFollowUp,
        scoped: ScopedTurnTools?,
        availableToolNames: Set<String>,
    ): ResumedFollowUp? {
        if (scoped == null) return null
        val eligible = pending.clarification != null ||
            scoped.scope.toolNames.isEmpty() && scoped.clarification == null
        if (!eligible) return null
        return TurnToolScopePolicy.resumeFollowUp(
            reply = reply,
            pending = pending,
            availableToolNames = availableToolNames,
        )
    }

    /**
     * Content-free receipts retained even when caller cancellation prevents Flow delivery.
     *
     * Only the most recently started accepted turn is retained, bounded by [AgentLoopLimits].
     * This is in-process recovery; the persistent action ledger remains the process-death replay
     * authority and intentionally stores no model response or user content.
     *
     * Caller contract: in the turn collector's `finally`, read this snapshot before accepting the
     * next turn, merge it with delivered [AgentEvent.ToolExecuted] values by `ordinal`, and surface
     * any missing outcome without invoking [runTurn] again. An empty snapshot is not proof that a
     * side effect did not happen after process death; the durable ledger remains fail-closed.
     */
    fun retainedToolExecutions(turnId: TurnId): List<AgentEvent.ToolExecuted> =
        retainedExecutions.get()
            ?.takeIf { retained -> retained.turnId == turnId }
            ?.events
            .orEmpty()

    fun runTurn(
        turnId: TurnId,
        prompt: String,
        turnLimits: AgentLoopLimits = limits,
        reminderDateTimeHint: ReminderDateTimeHint? = null,
        currentUserRequest: String? = null,
        contextualWebSearchRequest: TrustedWebSearchRequest? = null,
        recentWeatherRead: Boolean = false,
        readOnlyToolsOnly: Boolean = false,
        requireReadTool: Boolean = false,
        executionContract: TurnExecutionContract? = null,
        toolScope: LlmTurnToolScope? = null,
        media: List<TurnMediaAttachment> = emptyList(),
    ): Flow<AgentEvent> = flow {
        if (!isValidTurnId(turnId)) {
            emit(AgentEvent.Failure(turnId, AgentFailureCode.INVALID_TURN))
            return@flow
        }
        val mediaAttachments = media.toList()
        if (mediaAttachments.isNotEmpty()) {
            // A media turn is app-authored end to end. Automatic scoping, the follow-up
            // carry-over, deterministic read routing, and recovery contracts all classify typed
            // Korean prose, and none of them describes a photo or a recording. Combining them
            // with an attachment could only widen exposure, so a media turn refuses the
            // combination outright instead of resolving it.
            if (executionContract != null || contextualWebSearchRequest != null ||
                toolScope?.toolNames?.isNotEmpty() == true ||
                !TurnMediaBudget.allows(mediaAttachments)
            ) {
                emit(AgentEvent.Failure(turnId, AgentFailureCode.INVALID_TURN))
                return@flow
            }
        }

        val job = currentCoroutineContext()[Job]
            ?: error("A coroutine Job is required to run an agent turn.")
        val requestedText = currentUserRequest ?: prompt
        val availableToolNames = registry.definitions.mapTo(linkedSetOf()) { it.name }
        val automaticScopeRequested = executionContract == null && toolScope == null &&
            mediaAttachments.isEmpty()
        // Consumed unconditionally: a classified new request must not leave an older question
        // armed, and only this turn may answer it.
        val carriedFollowUp = if (automaticScopeRequested) {
            pendingFollowUp.getAndSet(null)
        } else {
            null
        }
        var automaticallyScoped = if (automaticScopeRequested) {
            TurnToolScopePolicy.forPrompt(
                prompt = requestedText,
                availableToolNames = availableToolNames,
            )
        } else {
            null
        }
        var scopedRequestText = requestedText
        if (carriedFollowUp != null) {
            resumeOrNull(
                reply = requestedText,
                pending = carriedFollowUp,
                scoped = automaticallyScoped,
                availableToolNames = availableToolNames,
            )?.let { resumed ->
                automaticallyScoped = resumed.scoped
                scopedRequestText = resumed.requestText
            }
        }
        if (automaticScopeRequested) {
            pendingFollowUp.set(
                PendingTurnFollowUp(
                    requestText = scopedRequestText,
                    scope = automaticallyScoped?.scope,
                    requiresGroundedRead = automaticallyScoped?.requiresGroundedRead == true,
                    clarification = automaticallyScoped?.clarification,
                ),
            )
        }
        // A resumed request keeps the original text for date, location, and grounding hints; the
        // model still receives the caller's prompt, which already contains the reply.
        val resumedRequest = scopedRequestText.takeIf { text -> text != requestedText }
        val effectiveUserRequest = resumedRequest ?: currentUserRequest
        val effectiveReminderHint = resumedRequest
            ?.let(ReminderDateTimeHint::fromCurrentUserPrompt)
            ?: reminderDateTimeHint
        val recoveryWebSearchRequest = if (
            contextualWebSearchRequest == null &&
            executionContract?.expectedReadTools == listOf(WebSearchTool.NAME)
        ) {
            effectiveUserRequest?.let(AutomaticWebSearchPolicy::knowledgeRequestOrNull)
        } else {
            null
        }
        val effectiveContextualWebSearchRequest =
            contextualWebSearchRequest ?: recoveryWebSearchRequest
        val effectiveToolScope: LlmTurnToolScope? = when {
            mediaAttachments.isNotEmpty() -> LlmTurnToolScope.none()
            executionContract != null -> LlmTurnToolScope.exact(
                executionContract.expectedReadTools.toSet(),
            )
            toolScope != null -> toolScope
            effectiveContextualWebSearchRequest != null ->
                LlmTurnToolScope.exact(setOf(WebSearchTool.NAME))
            else -> automaticallyScoped?.scope
        }
        val active = ActiveTurn(
            turnId = turnId,
            job = job,
            reminderDateTimeHint = effectiveReminderHint,
            readOnlyToolsOnly = readOnlyToolsOnly || executionContract != null ||
                effectiveContextualWebSearchRequest != null || mediaAttachments.isNotEmpty(),
            requireReadTool = mediaAttachments.isEmpty() &&
                (
                    requireReadTool || executionContract != null ||
                        automaticallyScoped?.requiresGroundedRead == true ||
                        effectiveContextualWebSearchRequest != null
                    ),
            executionContract = executionContract?.newState(),
            toolScope = effectiveToolScope,
            media = mediaAttachments,
            enforceToolScope = mediaAttachments.isNotEmpty() || toolScope != null ||
                effectiveContextualWebSearchRequest != null ||
                automaticallyScoped?.scope?.toolNames?.isNotEmpty() == true ||
                PriorWebResultFollowUpPolicy.matches(requestedText),
        )
        if (!activeTurn.compareAndSet(null, active)) {
            emit(AgentEvent.Failure(turnId, AgentFailureCode.BUSY))
            return@flow
        }
        retainedExecutions.set(RetainedExecutions(turnId = turnId, events = emptyList()))

        try {
            val clarification = automaticallyScoped?.clarification
            if (clarification != null) {
                // App-authored and model-free. An ambiguous write must not reach a Tool schema,
                // and the owner's answer resumes the original request on the next turn.
                emit(AgentEvent.TrustedAnswer(turnId, clarification.question))
                emit(AgentEvent.Completed(turnId))
                return@flow
            }
            withTimeout(turnLimits.deadlineMillis) {
                processTurn(
                    active = active,
                    prompt = prompt,
                    currentUserPrompt = requestedText,
                    turnLimits = turnLimits,
                    currentUserRequest = effectiveUserRequest,
                    contextualWebSearchRequest = effectiveContextualWebSearchRequest,
                    recentWeatherRead = recentWeatherRead,
                )
            }
        } catch (_: TimeoutCancellationException) {
            cancelRuntimeOnce(active)
            emit(AgentEvent.Failure(turnId, AgentFailureCode.DEADLINE_EXCEEDED))
        } catch (abort: TurnAbort) {
            cancelRuntimeOnce(active)
            emit(AgentEvent.Failure(turnId, abort.code, abort.runtimeCode, abort.toolFailure))
        } catch (cancelled: CancellationException) {
            active.state.compareAndSet(STATE_RUNNING, STATE_CANCELLING)
            cancelRuntimeOnce(active)
            throw cancelled
        } catch (unexpected: Exception) {
            cancelRuntimeOnce(active)
            throw unexpected
        } finally {
            active.state.compareAndSet(STATE_RUNNING, STATE_FINISHED)
            activeTurn.compareAndSet(active, null)
        }
    }

    /** Cancels only the matching active turn and never redirects cancellation to another turn. */
    suspend fun cancel(turnId: TurnId): Boolean {
        val active = activeTurn.get() ?: return false
        if (active.turnId != turnId) return false
        if (!active.state.compareAndSet(STATE_RUNNING, STATE_CANCELLING)) return false

        active.job.cancel(CancellationException("Agent turn cancelled."))
        cancelRuntimeOnce(active)
        return true
    }

    private suspend fun FlowCollector<AgentEvent>.processTurn(
        active: ActiveTurn,
        prompt: String,
        currentUserPrompt: String,
        turnLimits: AgentLoopLimits,
        currentUserRequest: String?,
        contextualWebSearchRequest: TrustedWebSearchRequest?,
        recentWeatherRead: Boolean,
    ) {
        val startedAt = monotonicClockMillis()
        val deadline = saturatedAdd(startedAt, turnLimits.deadlineMillis)
        val seenCallIds = mutableSetOf<String>()
        var stepCount = 0
        var toolCallCount = 0
        val weatherLocationHint = currentUserRequest?.let(::weatherLocationHintOrNull)
        val requestedText = currentUserRequest.orEmpty()
        val webSearchRequestHint = currentUserRequest
            ?.let(AutomaticWebSearchPolicy::knowledgeRequestOrNull)
        val webSearchQueryHint = webSearchRequestHint?.query
        val groundedEvidence = GroundedEvidenceSet()
        var groundedAnswerEligible = true

        fun recordGroundedEvidence(evidence: GroundedReadEvidence) {
            if (!groundedAnswerEligible || !groundedEvidence.add(evidence)) {
                groundedEvidence.clear()
                groundedAnswerEligible = false
            }
        }

        fun invalidateGroundedAnswer() {
            groundedEvidence.clear()
            groundedAnswerEligible = false
        }

        suspend fun executeDirectWebSearch(
            request: TrustedWebSearchRequest,
            callId: String = DIRECT_WEB_CALL_ID,
        ) {
            val registered = registry.resolve(WebSearchTool.NAME)
                as? RegisteredManualTool.WebSearch
                ?: abort(AgentFailureCode.UNKNOWN_TOOL)
            runTool(
                active = active,
                call = LlmToolCall(
                    id = callId,
                    name = WebSearchTool.NAME,
                    argumentsJson = webSearchArgumentsJson(request.query),
                ),
                tool = registered.tool,
                toolName = registered.definition.name,
                requestOrdinal = 1,
                deadline = deadline,
                parse = { json ->
                    WebSearchArgumentsParser(turnLimits.maxToolArgumentBytes).parse(json)
                },
                encode = TrustedToolResultJson::encode,
                onResult = { params, result ->
                    recordGroundedEvidence(
                        GroundedReadEvidence.WebSearch(
                            ordinal = 1,
                            query = params.query,
                            result = result,
                            intent = request.intent,
                            responseContract = request.responseContract,
                        ),
                    )
                },
            )
        }

        val deterministicRead = if (active.media.isNotEmpty()) {
            null
        } else {
            contextualWebSearchRequest ?: currentUserRequest?.let { request ->
                DeterministicReadRouter.classify(request, recentWeatherRead)
            }
        }
        when (val directRead = deterministicRead) {
            is TrustedWebSearchRequest -> executeDirectWebSearch(directRead)
            is DeterministicReadRequest.Weather -> {
                val registered = registry.resolve(WeatherTool.NAME)
                    as? RegisteredManualTool.Weather
                    ?: abort(AgentFailureCode.UNKNOWN_TOOL)
                runTool(
                    active = active,
                    call = LlmToolCall(
                        id = DIRECT_WEATHER_CALL_ID,
                        name = WeatherTool.NAME,
                        argumentsJson = weatherArgumentsJson(directRead.location),
                    ),
                    tool = registered.tool,
                    toolName = registered.definition.name,
                    requestOrdinal = 1,
                    deadline = deadline,
                    parse = { json ->
                        WeatherArgumentsParser(turnLimits.maxToolArgumentBytes).parse(json)
                    },
                    encode = TrustedToolResultJson::encode,
                    onResult = { params, result ->
                        recordGroundedEvidence(
                            GroundedReadEvidence.Weather(
                                ordinal = 1,
                                requestedLocation = params.location,
                                result = result,
                            ),
                        )
                    },
                )
            }
            is DeterministicReadRequest.WebSearch -> {
                executeDirectWebSearch(
                    TrustedWebSearchRequest(
                        query = directRead.query,
                        intent = directRead.intent,
                        responseContract = directRead.responseContract,
                    ),
                )
            }
            DeterministicReadRequest.AlarmNext -> {
                val registered = registry.resolve(AlarmNextTool.NAME)
                    as? RegisteredManualTool.AlarmNext
                    ?: abort(AgentFailureCode.UNKNOWN_TOOL)
                runTool(
                    active = active,
                    call = LlmToolCall(DIRECT_ALARM_CALL_ID, AlarmNextTool.NAME, "{}"),
                    tool = registered.tool,
                    toolName = registered.definition.name,
                    requestOrdinal = 1,
                    deadline = deadline,
                    parse = { json ->
                        AlarmNextArgumentsParser(turnLimits.maxToolArgumentBytes).parse(json)
                    },
                    encode = TrustedToolResultJson::encode,
                    onResult = { _, result ->
                        recordGroundedEvidence(GroundedReadEvidence.AlarmNext(1, result))
                    },
                )
            }
            DeterministicReadRequest.ReminderQuery -> {
                val registered = registry.resolve(ReminderQueryTool.NAME)
                    as? RegisteredManualTool.ReminderQuery
                    ?: abort(AgentFailureCode.UNKNOWN_TOOL)
                runTool(
                    active = active,
                    call = LlmToolCall(DIRECT_REMINDER_CALL_ID, ReminderQueryTool.NAME, "{}"),
                    tool = registered.tool,
                    toolName = registered.definition.name,
                    requestOrdinal = 1,
                    deadline = deadline,
                    parse = { json ->
                        ReminderQueryArgumentsParser(turnLimits.maxToolArgumentBytes).parse(json)
                    },
                    encode = TrustedToolResultJson::encode,
                    onResult = { _, result ->
                        recordGroundedEvidence(GroundedReadEvidence.ReminderQuery(1, result))
                    },
                )
            }
            null -> Unit
        }
        if (!groundedEvidence.isEmpty) {
            ensureBeforeDeadline(deadline)
            val webSearch = groundedEvidence.singleWebSearchOrNull()
            if (webSearch == null) {
                emit(AgentEvent.TrustedAnswer(active.turnId, groundedEvidence.renderTrustedAnswer()))
            } else {
                emitDirectWebSearchAnswer(active, webSearch, deadline, turnLimits)
            }
            emit(AgentEvent.Completed(active.turnId))
            return
        }

        var nextStream: Flow<ModelEvent> = modelStream {
            val scope = active.toolScope ?: if (forceExplicitToolScope) {
                LlmTurnToolScope.none()
            } else {
                null
            }
            when {
                active.media.isNotEmpty() -> runtime.streamUserTurn(
                    active.turnId,
                    prompt,
                    turnLimits.maxOutputTokens,
                    scope ?: LlmTurnToolScope.none(),
                    active.media,
                )

                scope == null ->
                    runtime.streamUserTurn(active.turnId, prompt, turnLimits.maxOutputTokens)

                else -> runtime.streamUserTurn(
                    active.turnId,
                    prompt,
                    turnLimits.maxOutputTokens,
                    scope,
                )
            }
        }

        while (true) {
            currentCoroutineContext().ensureActive()
            ensureBeforeDeadline(deadline)
            stepCount++
            if (stepCount > turnLimits.maxSteps) {
                abort(AgentFailureCode.STEP_LIMIT_EXCEEDED)
            }

            val step = collectCompletedStep(
                expectedTurnId = active.turnId,
                events = nextStream,
                deadline = deadline,
                maxBufferedTextBytes = minOf(
                    MAX_BUFFERED_MODEL_STEP_TEXT_BYTES,
                    turnLimits.maxOutputTokens * MODEL_STEP_BUFFER_BYTES_PER_OUTPUT_TOKEN,
                ),
            )
            if (step.toolCalls.isEmpty()) {
                ensureBeforeDeadline(deadline)
                if (active.executionContract != null &&
                    !active.executionContract.isSatisfied()
                ) {
                    abort(AgentFailureCode.TOOL_NOT_EXECUTED)
                }
                if (active.requireReadTool && active.completedReadExecutions.get() == 0) {
                    abort(AgentFailureCode.TOOL_NOT_EXECUTED)
                }
                val automaticWebFallback = if (
                    active.executionContract == null &&
                    active.completedReadExecutions.get() == 0 &&
                    groundedEvidence.isEmpty
                ) {
                    AutomaticWebSearchPolicy.fallbackRequestOrNull(
                        userRequest = requestedText,
                        modelAnswer = step.answerText,
                    )
                } else {
                    null
                }
                if (automaticWebFallback != null) {
                    // The local answer is still buffered and has never reached the UI. Run exactly
                    // one owner-consented READ_ONLY lookup, then expose only grounded evidence.
                    executeDirectWebSearch(automaticWebFallback)
                    ensureBeforeDeadline(deadline)
                    val webEvidence = groundedEvidence.singleWebSearchOrNull()
                        ?: abort(AgentFailureCode.INVALID_MODEL_SEQUENCE)
                    emitDirectWebSearchAnswer(active, webEvidence, deadline, turnLimits)
                    emit(AgentEvent.Completed(active.turnId))
                    return
                }
                if (groundedAnswerEligible && !groundedEvidence.isEmpty) {
                    val webSearch = groundedEvidence.singleWebSearchOrNull()
                    val synthesizedWebAnswer = webSearch?.let { evidence ->
                        WebSearchAnswerPolicy.answerFromModelOrNull(
                            plan = evidence.answerPlan(),
                            modelText = step.answerText,
                        )
                    }
                    if (synthesizedWebAnswer == null) {
                        emit(
                            AgentEvent.TrustedAnswer(
                                active.turnId,
                                groundedEvidence.renderTrustedAnswer(),
                            ),
                        )
                    } else {
                        // The model contributes only validated prose. Kotlin owns selected sources.
                        emit(AgentEvent.TextDelta(active.turnId, synthesizedWebAnswer))
                    }
                } else {
                    if (toolCallCount == 0 &&
                        groundedEvidence.isEmpty &&
                        !NoToolFinalAnswerPolicy.accepts(
                            currentUserPrompt = currentUserPrompt,
                            modelAnswer = step.answerText,
                            unexecutedWriteScope = active.toolScope?.toolNames
                                ?.any(UNEXECUTED_WRITE_TOOL_NAMES::contains) == true,
                        )
                    ) {
                        abort(AgentFailureCode.INVALID_MODEL_SEQUENCE)
                    }
                    if (step.answerText.isNotEmpty()) {
                        emit(AgentEvent.TextDelta(active.turnId, step.answerText))
                    }
                }
                emit(AgentEvent.Completed(active.turnId))
                return
            }
            if (step.toolCalls.size > 1) {
                if (toolCallCount != 0 || !groundedEvidence.isEmpty) {
                    abort(AgentFailureCode.INVALID_MODEL_SEQUENCE)
                }
                val bridge = agentPlanBridge
                    ?: abort(AgentFailureCode.INVALID_MODEL_SEQUENCE)
                if (step.toolCalls.size > MAX_PARALLEL_READ_PLAN_CALLS) {
                    abort(AgentFailureCode.TOOL_CALL_LIMIT_EXCEEDED)
                }
                val calls = step.toolCalls.toList()
                if (calls.any { call -> !isValidCallId(call.id) } ||
                    calls.map(LlmToolCall::id).distinct().size != calls.size
                ) {
                    abort(AgentFailureCode.INVALID_TOOL_CALL)
                }
                if (active.enforceToolScope && active.toolScope != null &&
                    calls.any { call -> call.name !in active.toolScope.toolNames }
                ) {
                    abort(AgentFailureCode.INVALID_MODEL_SEQUENCE)
                }
                if (active.executionContract != null &&
                    !active.executionContract.reserveAll(calls.map(LlmToolCall::name))
                ) {
                    abort(AgentFailureCode.TOOL_NOT_EXECUTED)
                }
                val batch = bridge.executeGroundedReadBatch(
                    objective = prompt,
                    requests = calls.map { call ->
                        EphemeralAgentReadRequest(
                            callId = call.id,
                            toolName = call.name,
                            argumentsJson = call.argumentsJson,
                        )
                    },
                    lifetimeMillis = remainingMillis(deadline)
                        .coerceAtMost(MAXIMUM_ACTION_LIFETIME_MILLIS),
                    onReadCompleted = { planReceipt ->
                        val receipt = AgentEvent.ToolExecuted(
                            turnId = active.turnId,
                            toolName = planReceipt.toolName,
                            ordinal = planReceipt.ordinal,
                            outcome = planReceipt.outcome,
                        )
                        retainExecution(receipt)
                        active.completedReadExecutions.incrementAndGet()
                        if (active.executionContract != null &&
                            !active.executionContract.complete(planReceipt.toolName)
                        ) {
                            throw IllegalStateException("Read execution contract drifted.")
                        }
                    },
                )
                when (batch) {
                    is AgentPlanGroundedBatchResult.Refused -> {
                        abort(AgentFailureCode.TOOL_NOT_EXECUTED)
                    }
                    is AgentPlanGroundedBatchResult.Executed -> {
                        if (active.executionContract != null &&
                            !active.executionContract.isSatisfied()
                        ) {
                            abort(AgentFailureCode.TOOL_NOT_EXECUTED)
                        }
                        // The native conversation is waiting for Tool responses. The app already
                        // owns the complete grounded answer, so close that pending model turn
                        // without a second decode before exposing receipts and the answer.
                        cancelRuntimeOnce(active)
                        batch.receipts.sortedBy(AgentPlanReadReceipt::ordinal).forEach { receipt ->
                            emit(
                                AgentEvent.ToolExecuted(
                                    turnId = active.turnId,
                                    toolName = receipt.toolName,
                                    ordinal = receipt.ordinal,
                                    outcome = receipt.outcome,
                                ),
                            )
                        }
                        emit(AgentEvent.TrustedAnswer(active.turnId, batch.trustedAnswer))
                        emit(AgentEvent.Completed(active.turnId))
                        return
                    }
                }
            }
            if (stepCount >= turnLimits.maxSteps) {
                abort(AgentFailureCode.STEP_LIMIT_EXCEEDED)
            }

            toolCallCount++
            if (toolCallCount > turnLimits.maxToolCalls) {
                abort(AgentFailureCode.TOOL_CALL_LIMIT_EXCEEDED)
            }
            val call = step.toolCalls.single()
            if (!isValidCallId(call.id) || !seenCallIds.add(call.id)) {
                abort(AgentFailureCode.INVALID_TOOL_CALL)
            }
            if (active.enforceToolScope && active.toolScope != null &&
                call.name !in active.toolScope.toolNames
            ) {
                abort(AgentFailureCode.INVALID_MODEL_SEQUENCE)
            }

            val resolved = registry.resolve(call.name)
                ?: abort(AgentFailureCode.UNKNOWN_TOOL)
            if (resolved !is RegisteredManualTool.CalendarQuery &&
                resolved !is RegisteredManualTool.AlarmNext &&
                resolved !is RegisteredManualTool.RouteEstimate &&
                resolved !is RegisteredManualTool.WebSearch &&
                resolved !is RegisteredManualTool.Weather &&
                resolved !is RegisteredManualTool.ReminderQuery
            ) {
                // Keep write and privacy-sensitive notification answer behavior unchanged.
                invalidateGroundedAnswer()
            }
            val trustedResponse = when (resolved) {
                is RegisteredManualTool.FakeArrivalNotice -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json -> FakeArrivalNoticeArgumentsParser(turnLimits.maxToolArgumentBytes).parse(json) },
                    encode = TrustedToolResultJson::encode,
                )
                is RegisteredManualTool.CalendarQuery -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json -> CalendarQueryArgumentsParser(turnLimits.maxToolArgumentBytes).parse(json) },
                    encode = TrustedToolResultJson::encode,
                    onResult = { _, result ->
                        recordGroundedEvidence(
                            GroundedReadEvidence.CalendarQuery(toolCallCount, result),
                        )
                    },
                )
                is RegisteredManualTool.CalendarCreateEvent -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json -> CalendarCreateEventArgumentsParser(turnLimits.maxToolArgumentBytes).parse(json) },
                    encode = TrustedToolResultJson::encode,
                )
                is RegisteredManualTool.CalendarUpdateEvent -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json -> CalendarUpdateEventArgumentsParser(turnLimits.maxToolArgumentBytes).parse(json) },
                    encode = TrustedToolResultJson::encode,
                )
                is RegisteredManualTool.AlarmSet -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json -> AlarmSetArgumentsParser(turnLimits.maxToolArgumentBytes).parse(json) },
                    encode = TrustedToolResultJson::encode,
                )
                is RegisteredManualTool.AlarmNext -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json -> AlarmNextArgumentsParser(turnLimits.maxToolArgumentBytes).parse(json) },
                    encode = TrustedToolResultJson::encode,
                    onResult = { _, result ->
                        recordGroundedEvidence(
                            GroundedReadEvidence.AlarmNext(toolCallCount, result),
                        )
                    },
                )
                is RegisteredManualTool.RouteEstimate -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json -> RouteEstimateArgumentsParser(turnLimits.maxToolArgumentBytes).parse(json) },
                    encode = TrustedToolResultJson::encode,
                    onResult = { _, result ->
                        recordGroundedEvidence(
                            GroundedReadEvidence.RouteEstimate(toolCallCount, result),
                        )
                    },
                )
                is RegisteredManualTool.WebSearch -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json ->
                        webSearchArgumentsWithQueryHint(
                            parsed = WebSearchArgumentsParser(turnLimits.maxToolArgumentBytes)
                                .parse(json),
                            queryHint = webSearchQueryHint,
                        )
                    },
                    encode = TrustedToolResultJson::encode,
                    onResult = { params, result ->
                        recordGroundedEvidence(
                            GroundedReadEvidence.WebSearch(
                                ordinal = toolCallCount,
                                query = params.query,
                                result = result,
                                intent = webSearchRequestHint?.intent
                                    ?: WebSearchAnswerPolicy.intentForRequest(requestedText),
                                responseContract = webSearchRequestHint?.responseContract
                                    ?: WebSearchResponseContract.fromOwnerRequest(requestedText),
                            ),
                        )
                    },
                )
                is RegisteredManualTool.Weather -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json ->
                        weatherArgumentsWithLocationHint(
                            parsed = WeatherArgumentsParser(turnLimits.maxToolArgumentBytes)
                                .parse(json),
                            locationHint = weatherLocationHint,
                        )
                    },
                    encode = TrustedToolResultJson::encode,
                    onResult = { params, result ->
                        recordGroundedEvidence(
                            GroundedReadEvidence.Weather(
                                ordinal = toolCallCount,
                                requestedLocation = params.location,
                                result = result,
                            ),
                        )
                    },
                )
                is RegisteredManualTool.KakaoShareMessage -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json ->
                        KakaoShareMessageArgumentsParser(turnLimits.maxToolArgumentBytes).parse(json)
                    },
                    encode = TrustedToolResultJson::encode,
                )
                is RegisteredManualTool.KakaoNotificationReply -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json ->
                        KakaoNotificationReplyArgumentsParser(turnLimits.maxToolArgumentBytes)
                            .parse(json)
                    },
                    encode = TrustedToolResultJson::encode,
                )
                is RegisteredManualTool.NotificationSearch -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json ->
                        NotificationSearchArgumentsParser(turnLimits.maxToolArgumentBytes).parse(json)
                    },
                    encode = TrustedToolResultJson::encode,
                )
                is RegisteredManualTool.MemoryRemember -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json ->
                        MemoryRememberArgumentsParser(turnLimits.maxToolArgumentBytes).parse(json)
                    },
                    encode = TrustedToolResultJson::encode,
                )
                is RegisteredManualTool.CommitmentProposal -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json ->
                        CommitmentProposalArgumentsParser(turnLimits.maxToolArgumentBytes).parse(json)
                    },
                    encode = TrustedToolResultJson::encode,
                )
                is RegisteredManualTool.ReminderCreate -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json ->
                        ReminderCreateArgumentsParser(turnLimits.maxToolArgumentBytes).parse(json)
                    },
                    encode = TrustedToolResultJson::encode,
                )
                is RegisteredManualTool.ReminderUpdate -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json ->
                        ReminderUpdateArgumentsParser(turnLimits.maxToolArgumentBytes).parse(json)
                    },
                    encode = TrustedToolResultJson::encode,
                )
                is RegisteredManualTool.ReminderCancel -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json ->
                        ReminderCancelArgumentsParser(turnLimits.maxToolArgumentBytes).parse(json)
                    },
                    encode = TrustedToolResultJson::encode,
                )
                is RegisteredManualTool.ReminderQuery -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json ->
                        ReminderQueryArgumentsParser(turnLimits.maxToolArgumentBytes).parse(json)
                    },
                    encode = TrustedToolResultJson::encode,
                    onResult = { _, result ->
                        recordGroundedEvidence(
                            GroundedReadEvidence.ReminderQuery(toolCallCount, result),
                        )
                    },
                )
            }

            currentCoroutineContext().ensureActive()
            ensureBeforeDeadline(deadline)

            val executedReceipt = retainedToolExecutions(active.turnId)
                .firstOrNull { event -> event.ordinal == toolCallCount }
            if (executedReceipt?.outcome == ToolExecutionOutcome.READ_COMPLETED &&
                active.executionContract?.isSatisfied() == true &&
                groundedAnswerEligible && !groundedEvidence.isEmpty
            ) {
                cancelRuntimeOnce(active)
                emit(
                    AgentEvent.TrustedAnswer(
                        active.turnId,
                        groundedEvidence.renderTrustedAnswer(),
                    ),
                )
                emit(AgentEvent.Completed(active.turnId))
                return
            }
            if (executedReceipt != null &&
                executedReceipt.outcome != ToolExecutionOutcome.READ_COMPLETED
            ) {
                // A trusted write/refusal receipt is already the terminal truth. Do not spend a
                // second decode asking the model to restate it or risk contradictory prose.
                cancelRuntimeOnce(active)
                emit(
                    AgentEvent.TrustedAnswer(
                        active.turnId,
                        trustedWriteTerminalAnswer(
                            toolName = executedReceipt.toolName,
                            outcome = executedReceipt.outcome,
                        ),
                    ),
                )
                emit(AgentEvent.Completed(active.turnId))
                return
            }

            val completedWebSearch = groundedEvidence.singleWebSearchOrNull()
            if (executedReceipt?.outcome == ToolExecutionOutcome.READ_COMPLETED &&
                completedWebSearch != null
            ) {
                val plan = completedWebSearch.answerPlan()
                if (plan.hits.isEmpty()) {
                    emit(
                        AgentEvent.TrustedAnswer(
                            active.turnId,
                            WebSearchAnswerPolicy.fallbackAnswer(plan),
                        ),
                    )
                    emit(AgentEvent.Completed(active.turnId))
                    return
                }
                val filteredResponse = trustedResponse.copy(
                    payloadJson = TrustedToolResultJson.encode(
                        completedWebSearch.result.copy(hits = plan.hits),
                    ),
                )
                val modelBody = collectOptionalWebSearchSynthesis(
                    active = active,
                    deadline = deadline,
                ) {
                    runtime.streamToolResponses(
                        turnId = active.turnId,
                        responses = listOf(filteredResponse),
                    )
                }
                val synthesized = modelBody?.let { body ->
                    WebSearchAnswerPolicy.answerFromModelOrNull(plan, body)
                }
                if (synthesized == null) {
                    emit(AgentEvent.TrustedAnswer(active.turnId, WebSearchAnswerPolicy.fallbackAnswer(plan)))
                } else {
                    emit(AgentEvent.TextDelta(active.turnId, synthesized))
                }
                emit(AgentEvent.Completed(active.turnId))
                return
            }

            // Non-web Tools continue through the ordinary single reinjection site. Do not retry it
            // after an exception or cancellation.
            nextStream = modelStream {
                runtime.streamToolResponses(
                    turnId = active.turnId,
                    responses = listOf(trustedResponse),
                )
            }
        }
    }

    /**
     * Explicit searches run before any model turn. If the local runtime is ready, spend exactly one
     * tool-free decode on the already-filtered evidence. Any protocol, content, timeout, or runtime
     * failure falls back to the deterministic extractive answer without another network request.
     */
    private suspend fun FlowCollector<AgentEvent>.emitDirectWebSearchAnswer(
        active: ActiveTurn,
        evidence: GroundedReadEvidence.WebSearch,
        deadline: Long,
        turnLimits: AgentLoopLimits,
    ) {
        val plan = evidence.answerPlan()
        val synthesisPrompt = WebSearchAnswerPolicy.synthesisPromptOrNull(plan)
        if (synthesisPrompt == null || runtime.state.value !is LlmState.Ready) {
            emit(AgentEvent.TrustedAnswer(active.turnId, WebSearchAnswerPolicy.fallbackAnswer(plan)))
            return
        }
        val modelBody = collectOptionalWebSearchSynthesis(
            active = active,
            deadline = deadline,
        ) {
            runtime.streamUserTurn(
                turnId = active.turnId,
                prompt = synthesisPrompt,
                maxOutputTokens = minOf(turnLimits.maxOutputTokens, WEB_SYNTHESIS_OUTPUT_TOKENS),
                toolScope = LlmTurnToolScope.none(),
            )
        }
        val synthesized = modelBody?.let { body ->
            WebSearchAnswerPolicy.answerFromModelOrNull(plan, body)
        }
        if (synthesized == null) {
            emit(AgentEvent.TrustedAnswer(active.turnId, WebSearchAnswerPolicy.fallbackAnswer(plan)))
        } else {
            // The source list and caveat are Kotlin-owned, but the prose body remains model text.
            emit(AgentEvent.TextDelta(active.turnId, synthesized))
        }
    }

    private suspend fun FlowCollector<AgentEvent>.collectOptionalWebSearchSynthesis(
        active: ActiveTurn,
        deadline: Long,
        createEvents: () -> Flow<ModelEvent>,
    ): String? {
        val buffer = BoundedModelStepTextBuffer(MAX_WEB_SYNTHESIS_TEXT_BYTES)
        val thoughtBudget = BoundedModelStepTextBudget(MAX_WEB_SYNTHESIS_THOUGHT_BYTES)
        var completed = false
        var invalid = false
        val timeoutMillis = minOf(remainingMillis(deadline), MAX_WEB_SYNTHESIS_MILLIS)
        try {
            withTimeout(timeoutMillis) {
                createEvents().collect { event ->
                    if (event.turnId != active.turnId || completed) {
                        invalid = true
                        return@collect
                    }
                    when (event) {
                        is ModelEvent.ThoughtDelta -> {
                            if (!thoughtBudget.accept(event.text)) {
                                invalid = true
                            } else if (event.text.isNotEmpty()) {
                                emit(AgentEvent.ThoughtDelta(active.turnId, event.text))
                            }
                        }
                        is ModelEvent.TextDelta -> {
                            if (!buffer.append(event.text)) invalid = true
                        }
                        is ModelEvent.FinalToolCalls -> {
                            if (event.toolCalls.isNotEmpty()) invalid = true
                        }
                        is ModelEvent.Completed -> completed = true
                        is ModelEvent.Failure -> invalid = true
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            cancelRuntimeOnce(active)
            return null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            cancelRuntimeOnce(active)
            return null
        }
        if (invalid || !completed || buffer.value().isBlank()) {
            cancelRuntimeOnce(active)
            return null
        }
        return buffer.value()
    }

    private suspend fun FlowCollector<AgentEvent>.collectCompletedStep(
        expectedTurnId: TurnId,
        events: Flow<ModelEvent>,
        deadline: Long,
        maxBufferedTextBytes: Int,
    ): CompletedModelStep {
        var completed = false
        var finalToolCalls: List<LlmToolCall>? = null
        val answerBuffer = BoundedModelStepTextBuffer(maxBufferedTextBytes)
        val thoughtBudget = BoundedModelStepTextBudget(maxBufferedTextBytes)

        events.catch { failure ->
            if (failure is CancellationException) throw failure
            abort(
                code = AgentFailureCode.MODEL_FAILURE,
                runtimeCode = (failure as? LlmRuntimeException)?.code
                    ?: LlmFailureCode.NATIVE_FAILURE,
            )
        }.collect { event ->
            currentCoroutineContext().ensureActive()
            ensureBeforeDeadline(deadline)
            if (event.turnId != expectedTurnId) {
                abort(AgentFailureCode.TURN_MISMATCH)
            }
            if (completed) {
                abort(AgentFailureCode.INVALID_MODEL_SEQUENCE)
            }
            if (finalToolCalls != null && event !is ModelEvent.Completed) {
                abort(AgentFailureCode.INVALID_MODEL_SEQUENCE)
            }

            when (event) {
                is ModelEvent.ThoughtDelta -> {
                    if (!thoughtBudget.accept(event.text)) {
                        abort(AgentFailureCode.INVALID_MODEL_SEQUENCE)
                    }
                    if (event.text.isNotEmpty()) {
                        emit(AgentEvent.ThoughtDelta(expectedTurnId, event.text))
                    }
                }
                is ModelEvent.TextDelta -> {
                    if (!answerBuffer.append(event.text)) {
                        abort(AgentFailureCode.INVALID_MODEL_SEQUENCE)
                    }
                }
                is ModelEvent.FinalToolCalls -> {
                    val calls = event.toolCalls.toList()
                    if (finalToolCalls != null || calls.isEmpty() ||
                        calls.size > MAX_PARALLEL_READ_PLAN_CALLS
                    ) {
                        abort(AgentFailureCode.INVALID_MODEL_SEQUENCE)
                    }
                    finalToolCalls = calls
                }
                is ModelEvent.Completed -> completed = true
                is ModelEvent.Failure -> abort(
                    code = AgentFailureCode.MODEL_FAILURE,
                    runtimeCode = event.code,
                )
            }
        }

        if (!completed) {
            abort(AgentFailureCode.INVALID_MODEL_SEQUENCE)
        }
        ensureBeforeDeadline(deadline)
        return CompletedModelStep(
            toolCalls = finalToolCalls.orEmpty(),
            answerText = answerBuffer.value(),
        )
    }

    /**
     * One shared path for every registered tool: strict decode, prepare, confirm, execute, and
     * encode a trusted result. Keeping it single-instance means a new tool cannot accidentally
     * skip the orchestrator, and the reinjected payload is always app-authored.
     */
    private suspend fun <P : ToolParams, R : Any> FlowCollector<AgentEvent>.runTool(
        active: ActiveTurn,
        call: LlmToolCall,
        tool: AgentTool<P, R>,
        toolName: String,
        requestOrdinal: Int,
        deadline: Long,
        parse: (String) -> ToolArgumentsParseResult<P>,
        encode: (R) -> String,
        onResult: (P, R) -> Unit = { _, _ -> },
    ): TrustedToolResponse {
        if (active.readOnlyToolsOnly && tool.descriptor.risk != ToolRisk.READ_ONLY) {
            abort(AgentFailureCode.TOOL_NOT_EXECUTED)
        }
        if (tool.descriptor.risk == ToolRisk.READ_ONLY &&
            active.executionContract != null &&
            !active.executionContract.reserve(toolName)
        ) {
            abort(AgentFailureCode.TOOL_NOT_EXECUTED)
        }

        val params = when (val parsed = parse(call.argumentsJson)) {
            is ToolArgumentsParseResult.Valid -> parsed.params
            is ToolArgumentsParseResult.Invalid -> abort(AgentFailureCode.INVALID_TOOL_CALL)
        }

        val remainingMillis = remainingMillis(deadline)
        if (remainingMillis < MINIMUM_ACTION_LIFETIME_MILLIS) {
            abort(AgentFailureCode.DEADLINE_EXCEEDED)
        }
        suspend fun prepare(candidate: P): PreparationResult<P, R> = orchestrator.prepare(
            tool = tool,
            params = candidate,
            requestId = "${active.turnId.value}:tool:$requestOrdinal",
            lifetimeMillis = remainingMillis.coerceAtMost(MAXIMUM_ACTION_LIFETIME_MILLIS),
        )
        var prepared = try {
            prepare(params)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            abort(AgentFailureCode.TOOL_NOT_EXECUTED)
        }
        if (prepared is PreparationResult.Rejected) {
            val repairedParams = reminderDateTimeRepairedParamsOrNull(
                params = params,
                failureCode = prepared.failureCode,
                hint = active.reminderDateTimeHint,
            )
            if (repairedParams != null) {
                prepared = try {
                    prepare(repairedParams)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    abort(AgentFailureCode.TOOL_NOT_EXECUTED)
                }
            }
        }
        val action = when (prepared) {
            is PreparationResult.Ready -> prepared.action
            is PreparationResult.Rejected -> {
                val failureCode = prepared.failureCode
                if (failureCode != null) {
                    active.validationRepair.payloadOrNull(toolName, failureCode)?.let { payload ->
                        return TrustedToolResponse(
                            callId = call.id,
                            name = toolName,
                            payloadJson = payload,
                        )
                    }
                    abort(
                        code = AgentFailureCode.TOOL_FAILED,
                        toolFailure = preparationToolFailureDetail(toolName, failureCode),
                    )
                }
                abort(AgentFailureCode.TOOL_NOT_EXECUTED)
            }
        }

        currentCoroutineContext().ensureActive()
        ensureBeforeDeadline(deadline)
        val retainedReceipt = AtomicReference<AgentEvent.ToolExecuted?>(null)
        val execution = try {
            orchestrator.executeWithReceipt(
                action = action,
                beforeSideEffectExecution = { trustedToolName, trustedRisk ->
                    sideEffectTurnGate.allow(active.turnId, trustedToolName, trustedRisk)
                },
                onExecuted = { trustedExecution ->
                    val receipt = AgentEvent.ToolExecuted(
                        turnId = active.turnId,
                        toolName = toolName,
                        ordinal = requestOrdinal,
                        outcome = trustedExecution.outcome,
                    )
                    retainExecution(receipt)
                    retainedReceipt.set(receipt)
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ToolInvocationFailureException) {
            abortExecutionToolFailure(toolName, action.risk, failure.failureCode)
        } catch (_: Exception) {
            abort(AgentFailureCode.TOOL_NOT_EXECUTED)
        }

        // The callback retained this before leaving the claimed-execution boundary. If the tool
        // crossed the deadline, the known result cannot be erased and this turn never retries it.
        val receipt = checkNotNull(retainedReceipt.get())
        emit(receipt)
        if (action.risk == ToolRisk.READ_ONLY) {
            active.completedReadExecutions.incrementAndGet()
            if (active.executionContract != null &&
                !active.executionContract.complete(toolName)
            ) {
                abort(AgentFailureCode.INVALID_MODEL_SEQUENCE)
            }
        }
        onResult(params, execution.result)

        return TrustedToolResponse(
            callId = call.id,
            name = toolName,
            payloadJson = encode(execution.result),
        )
    }

    private fun retainExecution(receipt: AgentEvent.ToolExecuted) {
        while (true) {
            val current = retainedExecutions.get()
            if (current == null || current.turnId != receipt.turnId) return
            if (current.events.any { event -> event.ordinal == receipt.ordinal }) return
            val updated = current.copy(events = current.events + receipt)
            if (retainedExecutions.compareAndSet(current, updated)) return
        }
    }

    private fun remainingMillis(deadline: Long): Long {
        val now = monotonicClockMillis()
        if (now >= deadline) abort(AgentFailureCode.DEADLINE_EXCEEDED)
        return deadline - now
    }

    private fun ensureBeforeDeadline(deadline: Long) {
        remainingMillis(deadline)
    }

    private suspend fun cancelRuntimeOnce(active: ActiveTurn) {
        if (!active.runtimeCancelIssued.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            runCatching { runtime.cancel(active.turnId) }
        }
    }

    private fun modelStream(create: () -> Flow<ModelEvent>): Flow<ModelEvent> = try {
        create()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: LlmRuntimeException) {
        abort(AgentFailureCode.MODEL_FAILURE, failure.code)
    } catch (_: Exception) {
        abort(AgentFailureCode.MODEL_FAILURE, LlmFailureCode.NATIVE_FAILURE)
    }

    private fun isValidTurnId(turnId: TurnId): Boolean = isValidIdentifier(
        value = turnId.value,
        maximumLength = MAX_TURN_ID_CHARACTERS,
    )

    private fun isValidCallId(callId: String): Boolean = isValidIdentifier(
        value = callId,
        maximumLength = MAX_CALL_ID_CHARACTERS,
    )

    private fun isValidIdentifier(value: String, maximumLength: Int): Boolean =
        value.length in 1..maximumLength &&
            value.first().isAsciiLetterOrDigit() &&
            value.all { character ->
                character.isAsciiLetterOrDigit() || character == '-' || character == '_' ||
                    character == '.' || character == ':'
            }

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun abort(
        code: AgentFailureCode,
        runtimeCode: LlmFailureCode? = null,
        toolFailure: ToolFailureDetail? = null,
    ): Nothing = throw TurnAbort(code, runtimeCode, toolFailure)

    private fun abortExecutionToolFailure(
        toolName: String,
        risk: ToolRisk,
        failureCode: ToolFailureCode,
    ): Nothing {
        val detail = readOnlyToolFailureDetailOrNull(toolName, risk, failureCode)
        if (detail != null) {
            abort(
                code = AgentFailureCode.TOOL_FAILED,
                toolFailure = detail,
            )
        }
        // A provider/transport exception cannot prove whether a write committed before it
        // surfaced. Keep side-effecting Tools on the existing fail-closed unknown path.
        abort(AgentFailureCode.TOOL_NOT_EXECUTED)
    }

    private data class CompletedModelStep(
        val toolCalls: List<LlmToolCall>,
        val answerText: String,
    )

    private data class ActiveTurn(
        val turnId: TurnId,
        val job: Job,
        val reminderDateTimeHint: ReminderDateTimeHint?,
        val readOnlyToolsOnly: Boolean,
        val requireReadTool: Boolean,
        val executionContract: TurnExecutionContractState?,
        val toolScope: LlmTurnToolScope?,
        val media: List<TurnMediaAttachment>,
        val enforceToolScope: Boolean,
        val state: AtomicInteger = AtomicInteger(STATE_RUNNING),
        val runtimeCancelIssued: AtomicBoolean = AtomicBoolean(false),
        val completedReadExecutions: AtomicInteger = AtomicInteger(0),
        val validationRepair: ReminderValidationRepairGate = ReminderValidationRepairGate(),
    )

    private data class RetainedExecutions(
        val turnId: TurnId,
        val events: List<AgentEvent.ToolExecuted>,
    )

    private class TurnAbort(
        val code: AgentFailureCode,
        val runtimeCode: LlmFailureCode?,
        val toolFailure: ToolFailureDetail?,
    ) : RuntimeException(null, null, false, false)

    private companion object {
        const val TOOL_FREE_OUTPUT_TOKENS = 256
        const val MAX_TURN_ID_CHARACTERS = 128
        const val MAX_CALL_ID_CHARACTERS = 128
        const val MINIMUM_ACTION_LIFETIME_MILLIS = 1_000L
        const val MAXIMUM_ACTION_LIFETIME_MILLIS = 300_000L
        const val DIRECT_WEATHER_CALL_ID = "direct-weather-1"
        const val DIRECT_WEB_CALL_ID = "direct-web-1"
        const val DIRECT_ALARM_CALL_ID = "direct-alarm-1"
        const val DIRECT_REMINDER_CALL_ID = "direct-reminder-1"
        const val MAX_PARALLEL_READ_PLAN_CALLS = 4
        const val MAX_BUFFERED_MODEL_STEP_TEXT_BYTES = 64 * 1_024
        const val MODEL_STEP_BUFFER_BYTES_PER_OUTPUT_TOKEN = 32
        const val STATE_RUNNING = 0
        const val STATE_CANCELLING = 1
        const val STATE_FINISHED = 2
        val UNEXECUTED_WRITE_TOOL_NAMES = setOf(
            CalendarCreateEventTool.NAME,
            CalendarUpdateEventTool.NAME,
            AlarmSetTool.NAME,
            KakaoShareMessageTool.NAME,
            KakaoNotificationReplyTool.NAME,
            MemoryRememberTool.NAME,
            CommitmentProposalTool.NAME,
            ReminderCreateTool.NAME,
            ReminderUpdateTool.NAME,
            ReminderCancelTool.NAME,
        )
    }
}

/** Turn-local buffer whose metadata-only [toString] cannot expose discarded model prose. */
private class BoundedModelStepTextBuffer(
    private val maximumUtf8Bytes: Int,
) {
    private val text = StringBuilder()
    private var utf8Bytes = 0

    init {
        require(maximumUtf8Bytes > 0)
    }

    fun append(delta: String): Boolean {
        val remaining = maximumUtf8Bytes - utf8Bytes
        if (delta.length > remaining) return false
        val deltaBytes = delta.toByteArray(Charsets.UTF_8).size
        if (deltaBytes > remaining) return false
        text.append(delta)
        utf8Bytes += deltaBytes
        return true
    }

    fun value(): String = text.toString()

    override fun toString(): String = "BoundedModelStepTextBuffer(utf8Bytes=$utf8Bytes)"
}

/** Counts untrusted streamed text without retaining a second copy of it. */
private class BoundedModelStepTextBudget(
    private val maximumUtf8Bytes: Int,
) {
    private var utf8Bytes = 0

    init {
        require(maximumUtf8Bytes > 0)
    }

    fun accept(delta: String): Boolean {
        val remaining = maximumUtf8Bytes - utf8Bytes
        if (delta.length > remaining) return false
        val deltaBytes = delta.toByteArray(Charsets.UTF_8).size
        if (deltaBytes > remaining) return false
        utf8Bytes += deltaBytes
        return true
    }

    override fun toString(): String = "BoundedModelStepTextBudget(utf8Bytes=$utf8Bytes)"
}

/**
 * Closed, model-free routing policy for reads whose complete answer is owned by Kotlin.
 *
 * The public predicate is intentionally content-free so the app can decide whether a missing or
 * rejected model blocks Send. [classify] remains module-internal and returns redacted request
 * types used by the controller. Ambiguous and compound requests always fall back to the model.
 */
object DeterministicReadRouter {
    fun canRunWithoutModel(prompt: String): Boolean = classify(prompt, recentWeatherRead = false) != null

    internal fun classify(
        prompt: String,
        recentWeatherRead: Boolean,
    ): DeterministicReadRequest? {
        val normalized = prompt.trim()
        if (normalized.isEmpty() || normalized.length > MAX_DIRECT_PROMPT_CHARACTERS ||
            normalized.any { character -> character.code < 0x20 } ||
            normalized.codePoints().anyMatch { codePoint ->
                Character.getType(codePoint) == Character.FORMAT.toInt()
            }
        ) {
            return null
        }

        val alarm = normalized.compactDirectIntent().takeIf(ALARM_READ_INTENTS::contains)
            ?.let { DeterministicReadRequest.AlarmNext }
        val reminder = normalized.compactDirectIntent().takeIf(REMINDER_READ_INTENTS::contains)
            ?.let { DeterministicReadRequest.ReminderQuery }
        val weatherLocation = weatherLocationHintOrNull(normalized)
            ?.takeIf(::isSingleDirectLocationCandidate)
            ?.takeIf {
                WEATHER_KEYWORDS.any(normalized::contains) ||
                    recentWeatherRead && WEATHER_CORRECTION_MARKERS.any(normalized::contains)
            }
            ?.takeIf {
                countOccurrences(normalized, WEATHER_KEYWORDS) == 1 ||
                    WEATHER_CORRECTION_MARKERS.any(normalized::contains)
            }
            ?.takeUnless {
                containsForeignReadDomain(normalized, DirectReadDomain.WEATHER)
            }
            ?.let(DeterministicReadRequest::Weather)
        val immediateWebRequest = AutomaticWebSearchPolicy.knowledgeRequestOrNull(normalized)
            ?.takeIf(TrustedWebSearchRequest::requiresImmediateSearch)
            ?.takeUnless { containsForeignReadDomain(normalized, DirectReadDomain.WEB) }
        val webQuery = immediateWebRequest?.let { request ->
            DeterministicReadRequest.WebSearch(
                query = request.query,
                intent = request.intent,
                responseContract = request.responseContract,
            )
        } ?: explicitWebSearchQueryOrNull(normalized)
            ?.takeUnless { containsForeignReadDomain(normalized, DirectReadDomain.WEB) }
            ?.let { query ->
                DeterministicReadRequest.WebSearch(
                    query = query,
                    intent = WebSearchAnswerPolicy.intentForRequest(normalized),
                    responseContract = WebSearchResponseContract.fromOwnerRequest(normalized),
                )
            }
        return listOfNotNull(alarm, reminder, weatherLocation, webQuery).singleOrNull()
    }

    private fun containsForeignReadDomain(
        prompt: String,
        selected: DirectReadDomain,
    ): Boolean {
        val hasWeather = WEATHER_KEYWORDS.any(prompt::contains)
        val hasWeb = WEB_SEARCH_MARKERS.any(prompt::contains)
        val hasAlarm = "알람" in prompt || "alarm" in prompt.lowercase(Locale.ROOT)
        val hasReminder = "리마인더" in prompt || "reminder" in prompt.lowercase(Locale.ROOT)
        val hasCalendar = CALENDAR_DOMAIN_MARKERS.any(prompt::contains)
        val hasRoute = ROUTE_DOMAIN_MARKERS.any(prompt::contains)
        return when (selected) {
            DirectReadDomain.WEATHER -> hasWeb || hasAlarm || hasReminder || hasCalendar || hasRoute
            DirectReadDomain.WEB -> hasWeather || hasAlarm || hasReminder || hasCalendar || hasRoute
        }
    }

    private fun String.compactDirectIntent(): String = lowercase(Locale.ROOT)
        .filter { character -> character.isLetterOrDigit() }

    private fun isSingleDirectLocationCandidate(location: String): Boolean =
        DIRECT_LOCATION_CONJUNCTIONS.none(location::contains)

    private fun countOccurrences(value: String, markers: List<String>): Int = markers.sumOf { marker ->
        var count = 0
        var offset = 0
        while (true) {
            val found = value.indexOf(marker, startIndex = offset)
            if (found < 0) break
            count++
            offset = found + marker.length
        }
        count
    }

    private const val MAX_DIRECT_PROMPT_CHARACTERS = 1_024
    private val DIRECT_LOCATION_CONJUNCTIONS = listOf("과 ", "와 ", " 그리고 ", " 및 ", ",", ";")
}

/** High-confidence schema reduction; unclassified prose receives no Tool schema. */
internal object TurnToolScopePolicy {
    fun forPrompt(
        prompt: String,
        availableToolNames: Set<String>,
    ): ScopedTurnTools? {
        val normalized = prompt.trim()
        if (normalized.isEmpty() || normalized.any(::isUnsafeScopeCharacter)) {
            return ScopedTurnTools(
                scope = LlmTurnToolScope.none(),
                requiresGroundedRead = false,
            )
        }
        if (PriorWebResultFollowUpPolicy.matches(normalized)) {
            // This is a transformation of already-visible conversation data, not a new network
            // request. Keeping the scope empty also prevents the model from repeating the search.
            return ScopedTurnTools(
                scope = LlmTurnToolScope.none(),
                requiresGroundedRead = false,
            )
        }
        val lower = normalized.lowercase(Locale.ROOT)
        val optionalKnowledgeSearch = AutomaticWebSearchPolicy.knowledgeRequestOrNull(normalized)
        val domains = mutableListOf<TurnToolDomain>()
        if (CALENDAR_DOMAIN_MARKERS.any(normalized::contains)) domains += TurnToolDomain.CALENDAR
        if (
            optionalKnowledgeSearch == null &&
            (WEATHER_KEYWORDS.any(normalized::contains) || "weather" in lower)
        ) {
            domains += TurnToolDomain.WEATHER
        }
        if ("알람" in normalized || "alarm" in lower) {
            // `alarm_set` refuses a named calendar date by contract, so a dated alarm request is a
            // reminder request. Routing it to the clock domain would expose the one Tool that is
            // required to reject it and leave the turn with no way to finish.
            domains += if (
                namesCalendarDate(normalized) &&
                ALARM_WRITE_MARKERS.any(normalized::contains)
            ) {
                TurnToolDomain.REMINDER
            } else {
                TurnToolDomain.ALARM
            }
        }
        if ("리마인더" in normalized || "reminder" in lower) domains += TurnToolDomain.REMINDER
        if (ROUTE_DOMAIN_MARKERS.any(normalized::contains)) domains += TurnToolDomain.ROUTE
        val kakaoNotificationContext = KAKAO_DOMAIN_MARKERS.any(normalized::contains) &&
            KAKAO_NOTIFICATION_CONTEXT_MARKERS.any(normalized::contains)
        val explicitWebIntent = !kakaoNotificationContext &&
            explicitWebSearchQueryOrNull(normalized) != null
        if (explicitWebIntent) {
            domains += TurnToolDomain.WEB
        }
        if (KAKAO_DOMAIN_MARKERS.any(normalized::contains)) domains += TurnToolDomain.KAKAO
        if (MEMORY_WRITE_MARKERS.any(normalized::contains)) domains += TurnToolDomain.MEMORY
        if (domains.isEmpty() && optionalKnowledgeSearch != null) {
            // The model may answer from local knowledge or propose this one read. If it explicitly
            // reports a knowledge gap, the buffered-answer fallback executes the same trusted query.
            domains += TurnToolDomain.WEB
        }
        val distinctDomains = domains.distinct()
        val writeIntent = WRITE_INTENT_MARKERS.any(normalized::contains)
        if (distinctDomains.isEmpty()) {
            return ScopedTurnTools(
                scope = LlmTurnToolScope.none(),
                requiresGroundedRead = false,
            )
        }
        val writableDomains = if (writeIntent) {
            distinctDomains.filter { domain -> canWrite(domain, availableToolNames) }
        } else {
            emptyList()
        }
        if (writableDomains.size > 1) {
            // A single global write marker cannot safely authorize one domain over another. The
            // owner picks the target in the next turn; no side-effect schema is exposed until
            // then. Requiring a Tool here instead would only fail a turn nothing could satisfy.
            return ScopedTurnTools(
                scope = LlmTurnToolScope.none(),
                requiresGroundedRead = false,
                clarification = TurnDomainClarification(
                    requestText = normalized,
                    choices = writableDomains.map(TurnToolDomain::token),
                ),
            )
        }
        val names = distinctDomains.flatMapTo(linkedSetOf()) { domain ->
            toolNamesFor(domain = domain, writeIntent = domain in writableDomains)
        }.intersect(availableToolNames)
        if (names.isEmpty()) {
            return ScopedTurnTools(
                scope = LlmTurnToolScope.none(),
                requiresGroundedRead = true,
            )
        }
        val optionalKnowledgeWebOnly = optionalKnowledgeSearch != null &&
            !optionalKnowledgeSearch.requiresImmediateSearch &&
            !explicitWebIntent &&
            distinctDomains == listOf(TurnToolDomain.WEB)
        val readOnlyGroundingRequired = writableDomains.isEmpty() &&
            distinctDomains.all(READ_GROUNDED_DOMAINS::contains) &&
            !optionalKnowledgeWebOnly
        return ScopedTurnTools(
            scope = LlmTurnToolScope.exact(names),
            requiresGroundedRead = readOnlyGroundingRequired,
        )
    }

    /**
     * Rebuilds the previous request's scope for a reply that carries no domain of its own.
     *
     * The reply is only ever a selector: it either names one of the choices this app offered or
     * confirms the previous request. It never widens the scope, and an unrecognized reply keeps
     * the empty schema rather than guessing a write target.
     */
    fun resumeFollowUp(
        reply: String,
        pending: PendingTurnFollowUp,
        availableToolNames: Set<String>,
    ): ResumedFollowUp? {
        val normalized = reply.trim()
        if (normalized.isEmpty() || normalized.any(::isUnsafeScopeCharacter)) return null

        val clarification = pending.clarification
        if (clarification != null) {
            val chosen = resolveChoice(normalized, clarification.choices) ?: return null
            val domain = TurnToolDomain.fromToken(chosen) ?: return null
            val names = toolNamesFor(domain = domain, writeIntent = true)
                .intersect(availableToolNames)
            if (names.isEmpty()) return null
            return ResumedFollowUp(
                scoped = ScopedTurnTools(
                    scope = LlmTurnToolScope.exact(names),
                    requiresGroundedRead = false,
                ),
                requestText = clarification.requestText,
            )
        }

        val previousScope = pending.scope ?: return null
        if (previousScope.toolNames.isEmpty()) return null
        if (!isFollowUpConfirmation(normalized)) return null
        return ResumedFollowUp(
            scoped = ScopedTurnTools(
                scope = previousScope,
                requiresGroundedRead = pending.requiresGroundedRead,
            ),
            requestText = pending.requestText,
        )
    }

    /** Matches only replies that select one offered choice and nothing else. */
    private fun resolveChoice(reply: String, choices: List<String>): String? {
        val matched = choices.filter { token ->
            TurnToolDomain.fromToken(token)
                ?.let { domain -> CHOICE_MARKERS.getValue(domain).any(reply::contains) } == true
        }
        return matched.singleOrNull()
    }

    /**
     * A confirmation is a reply with no content of its own. Anything longer is a new request and
     * is classified normally, so a real instruction never silently inherits an older scope.
     */
    private fun isFollowUpConfirmation(reply: String): Boolean {
        val compact = reply
            .filterNot { character -> character.isWhitespace() || character in FOLLOW_UP_PUNCTUATION }
            .lowercase(Locale.ROOT)
        return compact.isNotEmpty() && compact in FOLLOW_UP_CONFIRMATIONS
    }

    private fun namesCalendarDate(prompt: String): Boolean =
        CALENDAR_DATE_WORDS.any(prompt::contains) || CALENDAR_DATE_PATTERN.containsMatchIn(prompt)

    private fun canWrite(domain: TurnToolDomain, availableToolNames: Set<String>): Boolean =
        domain.writable &&
            toolNamesFor(domain = domain, writeIntent = true).any(availableToolNames::contains)

    private fun toolNamesFor(
        domain: TurnToolDomain,
        writeIntent: Boolean,
    ): Set<String> = when (domain) {
            TurnToolDomain.CALENDAR -> if (writeIntent) {
                setOf("calendar_query", "calendar_create_event", "calendar_update_event")
            } else {
                setOf("calendar_query")
            }
            TurnToolDomain.WEATHER -> setOf("weather_current")
            TurnToolDomain.ALARM -> if (writeIntent) {
                setOf("alarm_next", "alarm_set")
            } else {
                setOf("alarm_next")
            }
            TurnToolDomain.REMINDER -> if (writeIntent) {
                setOf(
                    "reminder_query",
                    "reminder_create",
                    "reminder_update",
                    "reminder_cancel",
                )
            } else {
                setOf("reminder_query")
            }
            TurnToolDomain.ROUTE -> setOf("route_estimate")
            TurnToolDomain.WEB -> setOf("web_search")
            TurnToolDomain.KAKAO -> if (writeIntent) {
                setOf(
                    NotificationSearchTool.NAME,
                    "kakao_share_message",
                    "kakao_notification_reply",
                )
            } else {
                setOf(NotificationSearchTool.NAME)
            }
            TurnToolDomain.MEMORY -> setOf("memory_remember")
        }

    /**
     * Pasted requests legitimately contain line breaks and tabs, so treating them as unsafe would
     * silently drop the Tool schema for every quoted mail or multi-line note. Every other control
     * or format character stays unsafe, so bidi-override and zero-width tricks still clear scope.
     */
    private fun isUnsafeScopeCharacter(character: Char): Boolean = when (character) {
        '\n', '\r', '\t' -> false
        else -> character.isISOControl() ||
            Character.getType(character) == Character.FORMAT.toInt()
    }

    private val READ_GROUNDED_DOMAINS = setOf(
        TurnToolDomain.CALENDAR,
        TurnToolDomain.WEATHER,
        TurnToolDomain.ALARM,
        TurnToolDomain.REMINDER,
        TurnToolDomain.ROUTE,
        TurnToolDomain.WEB,
        TurnToolDomain.KAKAO,
    )
    private val KAKAO_DOMAIN_MARKERS = listOf("카카오", "카톡", "kakao")
    private val KAKAO_NOTIFICATION_CONTEXT_MARKERS = listOf("알림", "notification")
    private val MEMORY_WRITE_MARKERS = listOf("기억해", "기억해 줘", "기억해줘", "remember this")
    private val WRITE_INTENT_MARKERS = listOf(
        "만들", "생성", "등록", "추가", "수정", "변경", "삭제", "취소", "설정", "맞춰",
        "보내", "전송", "공유", "답장", "저장", "기억해",
    )
    private val CALENDAR_DATE_WORDS = listOf("오늘", "내일", "모레", "글피")
    private val CALENDAR_DATE_PATTERN = Regex("""\d{1,2}\s*월\s*\d{1,2}\s*일|\d{4}-\d{2}-\d{2}|\d{1,2}/\d{1,2}""")
    private val FOLLOW_UP_PUNCTUATION = setOf('.', ',', '!', '?', '~', '。', '·')
    private val FOLLOW_UP_CONFIRMATIONS = setOf(
        "응", "웅", "어", "네", "넵", "예", "그래", "그래요", "좋아", "좋아요", "ㅇㅇ", "ㅇ",
        "해줘", "해주세요", "등록해줘", "등록해주세요", "진행해줘", "진행해주세요", "부탁해",
        "부탁해요", "그렇게해줘", "맞아", "맞아요", "ok", "okay", "yes", "y", "네네",
    )
    private val CHOICE_MARKERS = mapOf(
        TurnToolDomain.CALENDAR to listOf("일정", "캘린더", "달력", "calendar", "schedule"),
        TurnToolDomain.ALARM to listOf("알람", "시계", "alarm", "clock"),
        TurnToolDomain.REMINDER to listOf("리마인더", "알림", "알람", "reminder"),
        TurnToolDomain.KAKAO to listOf("카카오", "카톡", "메시지", "kakao"),
        TurnToolDomain.MEMORY to listOf("기억", "메모리", "memory"),
    )
}

/**
 * One deterministic question this app asks before any write schema exists.
 *
 * The text is app-authored so an ambiguous write never reaches the model as an open instruction,
 * and [requestText] preserves the original request so the answer re-runs it rather than the reply.
 */
class TurnDomainClarification internal constructor(
    val requestText: String,
    val choices: List<String>,
) {
    val question: String = buildString {
        val labels = choices.mapNotNull { token -> TurnToolDomain.fromToken(token)?.label }
        append("이 요청에는 ")
        append(labels.joinToString("과(와) "))
        append("이(가) 함께 있어 한쪽을 임의로 고르지 않았습니다. 어디에 등록할까요? ")
        append(labels.joinToString(" 또는 "))
        append(" 중 하나로 답해 주세요.")
    }

    override fun toString(): String =
        "TurnDomainClarification(choices=$choices, requestText=<redacted>)"
}

/** In-process carry-over from the previous automatically scoped turn of the same conversation. */
class PendingTurnFollowUp internal constructor(
    internal val requestText: String,
    internal val scope: LlmTurnToolScope?,
    internal val requiresGroundedRead: Boolean,
    internal val clarification: TurnDomainClarification?,
) {
    override fun toString(): String =
        "PendingTurnFollowUp(hasClarification=${clarification != null}, requestText=<redacted>)"
}

internal data class ResumedFollowUp(
    val scoped: ScopedTurnTools,
    val requestText: String,
)

internal data class ScopedTurnTools(
    val scope: LlmTurnToolScope,
    val requiresGroundedRead: Boolean,
    /** Set only when the request names more than one writable domain; no schema is exposed. */
    val clarification: TurnDomainClarification? = null,
)

private enum class TurnToolDomain(
    val token: String,
    val label: String,
    val writable: Boolean,
) {
    CALENDAR("calendar", "캘린더 일정", writable = true),
    WEATHER("weather", "날씨", writable = false),
    ALARM("alarm", "시계 알람", writable = true),
    REMINDER("reminder", "리마인더 알림", writable = true),
    ROUTE("route", "경로", writable = false),
    WEB("web", "웹 검색", writable = false),
    KAKAO("kakao", "카카오톡 메시지", writable = true),
    MEMORY("memory", "기억 저장", writable = true),
    ;

    companion object {
        fun fromToken(token: String): TurnToolDomain? =
            entries.firstOrNull { domain -> domain.token == token }
    }
}

internal sealed interface DeterministicReadRequest {
    class Weather(val location: String) : DeterministicReadRequest {
        override fun toString(): String = "DeterministicReadRequest.Weather(location=<redacted>)"
    }

    class WebSearch(
        val query: String,
        val intent: WebSearchAnswerIntent,
        val responseContract: WebSearchResponseContract = WebSearchResponseContract(),
    ) : DeterministicReadRequest {
        override fun toString(): String = "DeterministicReadRequest.WebSearch(query=<redacted>)"
    }

    data object AlarmNext : DeterministicReadRequest
    data object ReminderQuery : DeterministicReadRequest
}

private enum class DirectReadDomain {
    WEATHER,
    WEB,
}

/** Complete deterministic weather answer; model-authored post-Tool text is never mixed into it. */
internal fun weatherAnswerText(
    requestedLocation: String,
    result: WeatherResult,
): String = buildString {
    append(requestedLocation)
        .append("의 현재 날씨는 ")
        .append(result.condition)
        .append("이고, ")
        .append(result.temperatureCelsius)
        .append("°C입니다 (체감 ")
        .append(result.apparentTemperatureCelsius)
        .append("°C). 습도 ")
        .append(result.relativeHumidityPercent)
        .append("%, 강수 ")
        .append(result.precipitationMillimetres)
        .append("mm, 바람 ")
        .append(result.windSpeedKilometresPerHour)
        .append("km/h입니다. 오늘 최저 ")
        .append(result.todayMinimumCelsius)
        .append("°C, 최고 ")
        .append(result.todayMaximumCelsius)
        .append("°C, 최대 강수확률 ")
        .append(result.todayPrecipitationProbabilityPercent)
        .append("%입니다.\n확인 위치: ")
        .append(result.location)
        .append("\n기준 시각: ")
        .append(result.currentAt)
        .append("\n출처: ")
        .append(result.sourceName)
        .append(' ')
        .append(result.sourceUrl)
}

/**
 * Extracts only high-confidence weather places from the current user request. This prevents a
 * model-proposed `서울` argument from replacing an explicit `동탄` request. Ambiguous prompts keep
 * the strictly parsed model argument rather than guessing.
 */
internal fun weatherLocationHintOrNull(prompt: String): String? {
    val normalized = prompt.trim()
    if (normalized.isEmpty()) return null

    val correctionMarker = WEATHER_CORRECTION_MARKERS
        .map { marker -> marker to normalized.lastIndexOf(marker) }
        .filter { (_, index) -> index >= 0 }
        .maxByOrNull { (_, index) -> index }
    if (correctionMarker != null) {
        val (marker, index) = correctionMarker
        cleanWeatherLocationCandidate(normalized.substring(index + marker.length))?.let {
            return it
        }
    }

    val keywordMatch = WEATHER_KEYWORDS
        .map { keyword -> keyword to normalized.indexOf(keyword) }
        .filter { (_, index) -> index >= 0 }
        .minByOrNull { (_, index) -> index }
        ?: return null
    val (keyword, keywordIndex) = keywordMatch
    val before = cleanWeatherLocationCandidate(normalized.substring(0, keywordIndex))
    var afterText = normalized.substring(keywordIndex + keyword.length)
        .trim(*WEATHER_LOCATION_TRIM_CHARACTERS)
    var changed: Boolean
    do {
        changed = false
        for (prefix in WEATHER_COMMAND_PREFIXES) {
            if (afterText.startsWith(prefix)) {
                afterText = afterText.removePrefix(prefix).trim(*WEATHER_LOCATION_TRIM_CHARACTERS)
                changed = true
                break
            }
        }
    } while (changed)
    val after = cleanWeatherLocationCandidate(afterText)
    return listOfNotNull(before, after).distinct().singleOrNull()
}

internal fun weatherArgumentsWithLocationHint(
    parsed: ToolArgumentsParseResult<WeatherParams>,
    locationHint: String?,
): ToolArgumentsParseResult<WeatherParams> = when (parsed) {
    is ToolArgumentsParseResult.Valid -> if (locationHint == null) {
        parsed
    } else {
        ToolArgumentsParseResult.Valid(parsed.params.copy(location = locationHint))
    }
    is ToolArgumentsParseResult.Invalid -> parsed
}

/** A model may choose the lookup, but it cannot replace the subject parsed from owner text. */
internal fun webSearchArgumentsWithQueryHint(
    parsed: ToolArgumentsParseResult<WebSearchParams>,
    queryHint: String?,
): ToolArgumentsParseResult<WebSearchParams> = when (parsed) {
    is ToolArgumentsParseResult.Valid -> if (queryHint == null) {
        parsed
    } else {
        ToolArgumentsParseResult.Valid(parsed.params.copy(query = queryHint))
    }
    is ToolArgumentsParseResult.Invalid -> parsed
}

/** High-confidence explicit public-web searches execute as a Kotlin-owned READ_ONLY request. */
internal fun explicitWebSearchQueryOrNull(request: String): String? {
    if (AutomaticWebSearchPolicy.isLiteralSearchCorrection(request)) return null
    return explicitWebSearchQueryCandidateOrNull(request)
}

/** Raw standalone-query parser used by the meta-correction policy without recursive classification. */
internal fun explicitWebSearchQueryCandidateOrNull(request: String): String? {
    val normalized = request.trim()
    if (normalized.isEmpty() || !AutomaticWebSearchPolicy.isSafePublicWebTransfer(normalized) ||
        PriorWebResultFollowUpPolicy.matches(normalized) ||
        WEB_WRITE_MARKERS.any(normalized::contains)
    ) {
        return null
    }
    val searchText = WEB_ASSISTED_SEARCH_PREFIX.matchEntire(normalized)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?: normalized
    val locatedQuery = WEB_LOCATED_SEARCH.matchEntire(searchText)
        ?.groupValues
        ?.get(1)
        ?.trim(*WEB_QUERY_TRIM_CHARACTERS)
        ?.let(::removeTrailingObjectParticle)
    val commandPrefix = WEB_COMMAND_PREFIXES.firstOrNull(searchText::startsWith)
    val markerIndex = WEB_SEARCH_MARKERS
        .map(searchText::indexOf)
        .filter { index -> index > 0 }
        .minOrNull()
    var query = when {
        locatedQuery != null -> locatedQuery
        commandPrefix != null -> searchText.removePrefix(commandPrefix)
            .trim(*WEB_QUERY_TRIM_CHARACTERS)
        markerIndex != null -> searchText.substring(0, markerIndex)
            .trim(*WEB_QUERY_TRIM_CHARACTERS)
        else -> return null
    }
    WEB_QUERY_LEADING_WORDS.forEach { prefix ->
        if (query.startsWith(prefix)) query = query.removePrefix(prefix).trimStart()
    }
    if (locatedQuery == null) {
        var changed: Boolean
        do {
            changed = false
            for (suffix in WEB_QUERY_TRAILING_WORDS) {
                if (query.endsWith(suffix)) {
                    query = query.dropLast(suffix.length).trimEnd()
                    changed = true
                    break
                }
            }
        } while (changed)
    }
    query = query.trim(*WEB_QUERY_TRIM_CHARACTERS)
    if (AutomaticWebSearchPolicy.isSubjectlessQueryCandidate(query)) return null
    if (query.length !in 2..WebSearchTool.MAX_QUERY_CHARACTERS) return null
    if (query.none(Char::isLetterOrDigit)) return null
    return query
}

private fun removeTrailingObjectParticle(value: String): String = when {
    value.endsWith("을") || value.endsWith("를") -> value.dropLast(1).trimEnd()
    else -> value
}

/** Closed grammar for requests that transform the preceding web answer without new network I/O. */
object PriorWebResultFollowUpPolicy {
    fun matches(request: String): Boolean {
        val normalized = request.trim()
        if (normalized.isEmpty() || EXPLICIT_RESEARCH_MARKERS.any(normalized::contains) ||
            EXPLICIT_WEB_RESEARCH.containsMatchIn(normalized)
        ) {
            return false
        }
        return PRIOR_RESULT_MARKERS.any(normalized::contains) &&
            RESULT_TRANSFORM_MARKERS.any(normalized::contains)
    }

    private val PRIOR_RESULT_MARKERS = listOf(
        "검색결과", "검색 결과", "검색한 결과", "찾은 결과", "조사한 결과",
        "위 결과", "앞의 결과", "방금 결과", "그 결과", "방금 찾은 내용", "위 내용",
    )
    private val RESULT_TRANSFORM_MARKERS = listOf(
        "요약", "정리", "핵심", "간단", "짧게", "비교", "분석", "설명", "출처만",
    )
    private val EXPLICIT_RESEARCH_MARKERS = listOf(
        "다시 검색", "재검색", "새로 검색", "다시 찾아", "새로 찾아", "다시 조사", "새로 조사",
    )
    private val EXPLICIT_WEB_RESEARCH = Regex(
        "(?:웹|인터넷|온라인)(?:에서|\\s+)\\s*(?:직접\\s+)?(?:검색|찾아|알아봐|조사)",
    )
}

/** Deterministic fallback for paths that cannot spend the optional tool-free synthesis decode. */
internal fun webSearchAnswerText(plan: WebSearchAnswerPlan): String =
    WebSearchAnswerPolicy.fallbackAnswer(plan)

/** App-authored terminal truth for a completed or definitively refused side effect. */
internal fun trustedWriteTerminalAnswer(
    toolName: String,
    outcome: ToolExecutionOutcome,
): String {
    require(outcome != ToolExecutionOutcome.READ_COMPLETED)
    if (outcome == ToolExecutionOutcome.WRITE_REFUSED) {
        return when (toolName) {
            "calendar_create_event" -> "일정을 등록하지 않은 것으로 확인했습니다."
            "calendar_update_event" -> "일정을 수정하지 않은 것으로 확인했습니다."
            "alarm_set" -> "시계 앱이 알람 요청을 받아들이지 않아 추가되지 않았습니다."
            "kakao_share_message" -> "카카오톡 공유 화면을 열지 못해 전송되지 않았습니다."
            "kakao_notification_reply" -> "카카오톡 알림 답장을 요청하지 못했습니다."
            "memory_remember" -> "장기 기억에 저장하지 않았습니다."
            "commitment_proposal" -> "일정 후보 제안함에 저장하지 않았습니다."
            "reminder_create" -> "로컬 리마인더를 만들지 않았습니다."
            "reminder_update" -> "로컬 리마인더를 변경하지 않았습니다."
            "reminder_cancel" -> "로컬 리마인더를 취소하지 않았습니다."
            else -> "요청한 변경을 완료하지 않은 것으로 확인했습니다."
        }
    }
    return when (toolName) {
        "calendar_create_event" -> "캘린더에 일정을 등록했습니다."
        "calendar_update_event" -> "캘린더 일정을 수정했습니다."
        "alarm_set" -> "시계 앱에 알람 추가를 요청했습니다. 다음 알람 조회로 확인할 수 있습니다."
        "kakao_share_message" ->
            "카카오톡 공유 화면을 열었습니다. 대상 선택과 실제 전송은 카카오톡에서 확인하세요."
        "kakao_notification_reply" ->
            "카카오톡 알림에 답장을 요청했습니다. 실제 전송 결과는 카카오톡에서 확인하세요."
        "memory_remember" -> "승인한 내용을 장기 기억에 저장했습니다."
        "commitment_proposal" ->
            "일정 후보 제안함에 저장했습니다. 아직 예약된 알림은 없습니다."
        "reminder_create" -> "로컬 리마인더를 만들었습니다."
        "reminder_update" -> "로컬 리마인더를 변경했습니다."
        "reminder_cancel" -> "로컬 리마인더를 취소했습니다."
        else -> "요청한 변경을 완료했습니다."
    }
}

private fun webSearchArgumentsJson(query: String): String =
    "{\"query\":" + strictJsonQuote(query) + "}"

private fun weatherArgumentsJson(location: String): String =
    "{\"location\":" + strictJsonQuote(location) + "}"

private fun strictJsonQuote(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u").append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}

private fun cleanWeatherLocationCandidate(value: String): String? {
    var candidate = value.trim(*WEATHER_LOCATION_TRIM_CHARACTERS)
    WEATHER_KEYWORDS
        .map(candidate::indexOf)
        .filter { index -> index > 0 }
        .minOrNull()
        ?.let { index -> candidate = candidate.substring(0, index).trimEnd() }
    WEATHER_LEADING_WORDS.forEach { word ->
        if (candidate == word) return@forEach
        if (candidate.startsWith("$word ")) candidate = candidate.removePrefix(word).trimStart()
    }
    var changed: Boolean
    do {
        changed = false
        for (suffix in WEATHER_TRAILING_WORDS) {
            if (candidate.endsWith(suffix)) {
                candidate = candidate.dropLast(suffix.length).trimEnd()
                changed = true
                break
            }
        }
    } while (changed)
    candidate = candidate.trim(*WEATHER_LOCATION_TRIM_CHARACTERS)
    if (candidate.length !in 1..WeatherTool.MAX_LOCATION_CHARACTERS) return null
    if (candidate in WEATHER_NON_LOCATION_WORDS) return null
    if (candidate.none(Char::isLetterOrDigit)) return null
    return candidate
}

private val WEATHER_CORRECTION_MARKERS = listOf("아니라", "말고")
private val WEATHER_KEYWORDS = listOf("날씨", "기온", "예보")
private val WEATHER_LEADING_WORDS = listOf("오늘", "지금", "현재")
private val WEATHER_COMMAND_PREFIXES = listOf(
    "를 ", "을 ", "좀 ",
    "알려 주세요", "알려주세요", "알려 줘", "알려줘", "확인해 주세요", "확인해주세요",
    "확인해 줘", "확인해줘", "보여 주세요", "보여주세요", "보여 줘", "보여줘", ":",
)
private val WEATHER_TRAILING_WORDS = listOf("의 현재", "의 오늘", "에서", "지역", "의", "은", "는", "이", "가")
private val WEATHER_NON_LOCATION_WORDS = setOf("오늘", "지금", "현재", "날씨", "기온", "예보")
private val WEATHER_LOCATION_TRIM_CHARACTERS = charArrayOf(
    ' ', '\t', '\n', '\r', '.', ',', '!', '?', '。', '，', '！', '？', '"', '\'', '(', ')', '[', ']',
)
private val WEB_SEARCH_MARKERS = listOf("검색", "찾아", "알아봐", "조사해")
private val WEB_ASSISTED_SEARCH_PREFIX = Regex(
    "^(?:웹|인터넷|온라인)\\s*검색(?:을|를)?\\s*(?:활용|이용)(?:해서|하여|해)?\\s+(.+)$",
)
private val WEB_LOCATED_SEARCH = Regex(
    "^(.+?)\\s+(?:웹|인터넷|온라인)에서\\s*(?:검색|찾아|알아봐|조사해).*$",
)
private val WEB_COMMAND_PREFIXES = listOf(
    "검색해 주세요", "검색해주세요", "검색해 줘", "검색해줘", "검색:", "검색 ",
    "찾아 주세요", "찾아주세요", "찾아 줘", "찾아줘", "찾아봐 주세요", "찾아봐줘",
    "조사해 주세요", "조사해주세요", "조사해 줘", "조사해줘",
)
private val WEB_WRITE_MARKERS = listOf(
    "보내", "전송", "공유", "답장", "등록", "추가", "수정", "변경", "삭제", "저장", "기억",
    "예약", "알람", "리마인더", "일정 만들어", "일정 생성",
)
private val WEB_QUERY_LEADING_WORDS = listOf("웹에서 ", "인터넷에서 ", "온라인에서 ")
// Longest first: the loop strips one suffix per pass, so a phrase must be removable before the
// particle inside it is. Missing one form is not cosmetic — the leftover words are sent to the
// provider as part of the literal query and can return nothing for an otherwise common subject.
private val WEB_QUERY_TRAILING_WORDS = listOf(
    "이라는 사람에 대해서", "이란 사람에 대해서", "라는 사람에 대해서", "란 사람에 대해서",
    "이라는 사람에 대해", "이란 사람에 대해", "라는 사람에 대해", "란 사람에 대해",
    "이라는 사람에 대한", "이란 사람에 대한", "라는 사람에 대한", "란 사람에 대한",
    "이라는 사람에 관한", "이란 사람에 관한", "라는 사람에 관한", "란 사람에 관한",
    "이라는 사람", "이란 사람", "라는 사람", "란 사람",
    "에 대해서", "에 대해", "에 대한", "에 관해서", "에 관해", "에 관한",
    "웹에서", "인터넷에서", "온라인에서",
    "관련 정보", "관련 자료", "관련 내용", "관련해서", "관련해", "관련",
    "자세한 내용", "자세히", "정보를", "정보", "자료를", "자료", "내용을", "내용",
    // Bare particles stay limited to the two that were already safe here: "은/는/이/가" also end
    // ordinary nouns such as "주가" or "국가", and stripping those would corrupt the query.
    "을", "를",
    "다시", "새로",
)
private val WEB_QUERY_TRIM_CHARACTERS = charArrayOf(
    ' ', '\t', '\n', '\r', '.', ',', ':', '：', '!', '?', '。', '，', '！', '？', '"', '\'', '(', ')', '[', ']',
)
private val ALARM_READ_INTENTS = setOf(
    "다음알람", "다음알람언제", "다음알람언제야", "다음알람언제인가요", "다음알람확인",
    "다음알람알려줘", "다음알람보여줘", "알람확인", "알람언제", "알람언제야",
    "nextalarm", "nextalarmtime", "whatisthenextalarm", "showmethenextalarm",
)
private val REMINDER_READ_INTENTS = setOf(
    "리마인더", "리마인더목록", "리마인더확인", "리마인더보여줘", "예정된리마인더",
    "예정된리마인더목록", "예정된리마인더목록보여줘", "예정된리마인더확인", "예정된리마인더보여줘",
    "예정된앱리마인더", "예정된앱리마인더목록", "upcomingreminders", "showmyreminders",
)
private val CALENDAR_DOMAIN_MARKERS = listOf("일정", "캘린더", "약속", "calendar", "schedule")
private val ROUTE_DOMAIN_MARKERS = listOf(
    "경로", "길찾기", "길을 찾아", "길 찾아", "얼마나 걸", "도착", "출발", "route",
)
private val ALARM_WRITE_MARKERS = listOf("맞춰", "설정", "켜", "만들", "추가", "등록")
private const val WEB_SYNTHESIS_OUTPUT_TOKENS = 1_024
private const val MAX_WEB_SYNTHESIS_TEXT_BYTES = 3_072
private const val MAX_WEB_SYNTHESIS_THOUGHT_BYTES = 32 * 1_024
private const val MAX_WEB_SYNTHESIS_MILLIS = 30_000L

private const val REMINDER_DATE_TIME_REPAIR_JSON =
    "{\"ok\":false,\"error\":\"invalid_trigger_at\",\"retry\":\"once\",\"instruction\":" +
        "\"Retry the same reminder tool now. Copy the complete numeric local timestamp from the " +
        "current user request into trigger_at without adding or removing characters. Copy the " +
        "trusted IANA time-zone name into zone_id. Output only the tool call.\"}"

/** Returns only static app-authored guidance; model arguments and user text never enter it. */
internal fun reminderValidationRepairPayloadOrNull(
    toolName: String,
    failureCode: ToolFailureCode,
): String? {
    if (toolName != ReminderCreateTool.NAME && toolName != ReminderUpdateTool.NAME) return null
    if (!failureCode.name.startsWith("REMINDER_INVALID_DATE_TIME")) return null
    return REMINDER_DATE_TIME_REPAIR_JSON
}

/** Allows one static, pre-execution reminder date-time correction per model turn. */
internal class ReminderValidationRepairGate {
    private val issued = AtomicBoolean(false)

    fun payloadOrNull(
        toolName: String,
        failureCode: ToolFailureCode,
    ): String? {
        val payload = reminderValidationRepairPayloadOrNull(toolName, failureCode) ?: return null
        return payload.takeIf { issued.compareAndSet(false, true) }
    }
}

/** One exact local timestamp extracted only from the current typed user request. */
class ReminderDateTimeHint private constructor(val triggerAt: String) {
    companion object {
        private val EXACT_LOCAL_DATE_TIME = Regex(
            """(?<![0-9A-Za-z])\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?![0-9:A-Za-z+\-])""",
        )

        fun fromCurrentUserPrompt(prompt: String): ReminderDateTimeHint? {
            val matches = EXACT_LOCAL_DATE_TIME.findAll(prompt)
                .map(MatchResult::value)
                .distinct()
                .take(2)
                .toList()
            return matches.singleOrNull()?.let(::ReminderDateTimeHint)
        }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun <P : ToolParams> reminderDateTimeRepairedParamsOrNull(
    params: P,
    failureCode: ToolFailureCode?,
    hint: ReminderDateTimeHint?,
): P? {
    if (failureCode == null || hint == null) return null
    if (!failureCode.name.startsWith("REMINDER_INVALID_DATE_TIME")) return null
    return when (params) {
        is ReminderCreateParams -> params.copy(triggerAt = hint.triggerAt) as P
        is ReminderUpdateParams -> params.copy(triggerAt = hint.triggerAt) as P
        else -> null
    }
}
