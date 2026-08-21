package com.personaledge.core.agent

import com.personaledge.core.llm.InferenceBackend
import com.personaledge.core.llm.LlmFailureCode
import com.personaledge.core.llm.LlmRuntime
import com.personaledge.core.llm.LlmRuntimeException
import com.personaledge.core.llm.LlmToolCall
import com.personaledge.core.llm.LlmToolDefinition
import com.personaledge.core.llm.ModelEvent
import com.personaledge.core.llm.TrustedToolResponse
import com.personaledge.core.llm.TurnId
import com.personaledge.core.llm.VerifiedInstalledModel
import com.personaledge.core.tools.AgentTool
import com.personaledge.core.tools.PreparationResult
import com.personaledge.core.tools.ToolOrchestrator
import com.personaledge.core.tools.ToolParams
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
    DEADLINE_EXCEEDED,
}

sealed interface AgentEvent {
    val turnId: TurnId

    data class TextDelta(
        override val turnId: TurnId,
        val text: String,
    ) : AgentEvent

    /** Contains only trusted registry metadata; model arguments are intentionally omitted. */
    data class ToolExecuted(
        override val turnId: TurnId,
        val toolName: String,
        val ordinal: Int,
    ) : AgentEvent

    data class Completed(
        override val turnId: TurnId,
    ) : AgentEvent

    data class Failure(
        override val turnId: TurnId,
        val code: AgentFailureCode,
        /** Safe typed runtime detail; exception text is never propagated. */
        val runtimeCode: LlmFailureCode? = null,
    ) : AgentEvent
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
    private val limits: AgentLoopLimits = AgentLoopLimits(),
    private val monotonicClockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    val toolDefinitions: List<LlmToolDefinition>
        get() = registry.definitions

    /** Initializes the runtime with the exact same closed registry used for later resolution. */
    suspend fun initialize(
        model: VerifiedInstalledModel,
        backend: InferenceBackend = InferenceBackend.CPU,
    ) {
        runtime.initialize(
            model = model,
            backend = backend,
            tools = registry.definitions,
        )
    }

    private val activeTurn = AtomicReference<ActiveTurn?>(null)

    fun runTurn(
        turnId: TurnId,
        prompt: String,
    ): Flow<AgentEvent> = flow {
        if (!isValidTurnId(turnId)) {
            emit(AgentEvent.Failure(turnId, AgentFailureCode.INVALID_TURN))
            return@flow
        }

        val job = currentCoroutineContext()[Job]
            ?: error("A coroutine Job is required to run an agent turn.")
        val active = ActiveTurn(turnId = turnId, job = job)
        if (!activeTurn.compareAndSet(null, active)) {
            emit(AgentEvent.Failure(turnId, AgentFailureCode.BUSY))
            return@flow
        }

        try {
            withTimeout(limits.deadlineMillis) {
                processTurn(active, prompt)
            }
        } catch (_: TimeoutCancellationException) {
            cancelRuntimeOnce(active)
            emit(AgentEvent.Failure(turnId, AgentFailureCode.DEADLINE_EXCEEDED))
        } catch (abort: TurnAbort) {
            cancelRuntimeOnce(active)
            emit(AgentEvent.Failure(turnId, abort.code, abort.runtimeCode))
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
    ) {
        val startedAt = monotonicClockMillis()
        val deadline = saturatedAdd(startedAt, limits.deadlineMillis)
        val seenCallIds = mutableSetOf<String>()
        var stepCount = 0
        var toolCallCount = 0
        var nextStream: Flow<ModelEvent> = modelStream {
            runtime.streamUserTurn(active.turnId, prompt)
        }

        while (true) {
            currentCoroutineContext().ensureActive()
            ensureBeforeDeadline(deadline)
            stepCount++
            if (stepCount > limits.maxSteps) {
                abort(AgentFailureCode.STEP_LIMIT_EXCEEDED)
            }

            val step = collectCompletedStep(active.turnId, nextStream, deadline)
            if (step.toolCalls.isEmpty()) {
                ensureBeforeDeadline(deadline)
                emit(AgentEvent.Completed(active.turnId))
                return
            }
            if (step.toolCalls.size != 1) {
                abort(AgentFailureCode.INVALID_MODEL_SEQUENCE)
            }
            if (stepCount >= limits.maxSteps) {
                abort(AgentFailureCode.STEP_LIMIT_EXCEEDED)
            }

            toolCallCount++
            if (toolCallCount > limits.maxToolCalls) {
                abort(AgentFailureCode.TOOL_CALL_LIMIT_EXCEEDED)
            }
            val call = step.toolCalls.single()
            if (!isValidCallId(call.id) || !seenCallIds.add(call.id)) {
                abort(AgentFailureCode.INVALID_TOOL_CALL)
            }

            val resolved = registry.resolve(call.name)
                ?: abort(AgentFailureCode.UNKNOWN_TOOL)
            val trustedResponse = when (resolved) {
                is RegisteredManualTool.FakeArrivalNotice -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json -> FakeArrivalNoticeArgumentsParser(limits.maxToolArgumentBytes).parse(json) },
                    encode = TrustedToolResultJson::encode,
                )
                is RegisteredManualTool.CalendarQuery -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json -> CalendarQueryArgumentsParser(limits.maxToolArgumentBytes).parse(json) },
                    encode = TrustedToolResultJson::encode,
                )
                is RegisteredManualTool.CalendarCreateEvent -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json -> CalendarCreateEventArgumentsParser(limits.maxToolArgumentBytes).parse(json) },
                    encode = TrustedToolResultJson::encode,
                )
                is RegisteredManualTool.CalendarUpdateEvent -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json -> CalendarUpdateEventArgumentsParser(limits.maxToolArgumentBytes).parse(json) },
                    encode = TrustedToolResultJson::encode,
                )
                is RegisteredManualTool.AlarmSet -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json -> AlarmSetArgumentsParser(limits.maxToolArgumentBytes).parse(json) },
                    encode = TrustedToolResultJson::encode,
                )
                is RegisteredManualTool.AlarmNext -> runTool(
                    active = active,
                    call = call,
                    tool = resolved.tool,
                    toolName = resolved.definition.name,
                    requestOrdinal = toolCallCount,
                    deadline = deadline,
                    parse = { json -> AlarmNextArgumentsParser(limits.maxToolArgumentBytes).parse(json) },
                    encode = TrustedToolResultJson::encode,
                )
            }

            currentCoroutineContext().ensureActive()
            ensureBeforeDeadline(deadline)

            // This is the sole reinjection site. Do not retry it after an exception/cancellation.
            nextStream = modelStream {
                runtime.streamToolResponses(
                    turnId = active.turnId,
                    responses = listOf(trustedResponse),
                )
            }
            emit(
                AgentEvent.ToolExecuted(
                    turnId = active.turnId,
                    toolName = resolved.definition.name,
                    ordinal = toolCallCount,
                ),
            )
        }
    }

    private suspend fun FlowCollector<AgentEvent>.collectCompletedStep(
        expectedTurnId: TurnId,
        events: Flow<ModelEvent>,
        deadline: Long,
    ): CompletedModelStep {
        var completed = false
        var finalToolCalls: List<LlmToolCall>? = null

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
                is ModelEvent.TextDelta -> emit(
                    AgentEvent.TextDelta(
                        turnId = expectedTurnId,
                        text = event.text,
                    ),
                )
                is ModelEvent.FinalToolCalls -> {
                    val singleCall = event.toolCalls.singleOrNull()
                    if (finalToolCalls != null || singleCall == null) {
                        abort(AgentFailureCode.INVALID_MODEL_SEQUENCE)
                    }
                    finalToolCalls = listOf(singleCall)
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
        return CompletedModelStep(finalToolCalls.orEmpty())
    }

    /**
     * One shared path for every registered tool: strict decode, prepare, confirm, execute, and
     * encode a trusted result. Keeping it single-instance means a new tool cannot accidentally
     * skip the orchestrator, and the reinjected payload is always app-authored.
     */
    private suspend fun <P : ToolParams, R : Any> runTool(
        active: ActiveTurn,
        call: LlmToolCall,
        tool: AgentTool<P, R>,
        toolName: String,
        requestOrdinal: Int,
        deadline: Long,
        parse: (String) -> ToolArgumentsParseResult<P>,
        encode: (R) -> String,
    ): TrustedToolResponse {
        val params = when (val parsed = parse(call.argumentsJson)) {
            is ToolArgumentsParseResult.Valid -> parsed.params
            is ToolArgumentsParseResult.Invalid -> abort(AgentFailureCode.INVALID_TOOL_CALL)
        }

        val remainingMillis = remainingMillis(deadline)
        if (remainingMillis < MINIMUM_ACTION_LIFETIME_MILLIS) {
            abort(AgentFailureCode.DEADLINE_EXCEEDED)
        }
        val prepared = try {
            orchestrator.prepare(
                tool = tool,
                params = params,
                requestId = "${active.turnId.value}:tool:$requestOrdinal",
                lifetimeMillis = remainingMillis.coerceAtMost(MAXIMUM_ACTION_LIFETIME_MILLIS),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            abort(AgentFailureCode.TOOL_NOT_EXECUTED)
        }
        val action = when (prepared) {
            is PreparationResult.Ready -> prepared.action
            is PreparationResult.Rejected -> abort(AgentFailureCode.TOOL_NOT_EXECUTED)
        }

        currentCoroutineContext().ensureActive()
        ensureBeforeDeadline(deadline)
        val result: R = try {
            orchestrator.execute(action)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            abort(AgentFailureCode.TOOL_NOT_EXECUTED)
        }
        currentCoroutineContext().ensureActive()
        ensureBeforeDeadline(deadline)

        return TrustedToolResponse(
            callId = call.id,
            name = toolName,
            payloadJson = encode(result),
        )
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
    ): Nothing = throw TurnAbort(code, runtimeCode)

    private data class CompletedModelStep(
        val toolCalls: List<LlmToolCall>,
    )

    private data class ActiveTurn(
        val turnId: TurnId,
        val job: Job,
        val state: AtomicInteger = AtomicInteger(STATE_RUNNING),
        val runtimeCancelIssued: AtomicBoolean = AtomicBoolean(false),
    )

    private class TurnAbort(
        val code: AgentFailureCode,
        val runtimeCode: LlmFailureCode?,
    ) : RuntimeException(null, null, false, false)

    private companion object {
        const val MAX_TURN_ID_CHARACTERS = 128
        const val MAX_CALL_ID_CHARACTERS = 128
        const val MINIMUM_ACTION_LIFETIME_MILLIS = 1_000L
        const val MAXIMUM_ACTION_LIFETIME_MILLIS = 300_000L
        const val STATE_RUNNING = 0
        const val STATE_CANCELLING = 1
        const val STATE_FINISHED = 2
    }
}
