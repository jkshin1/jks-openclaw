package com.personaledge.core.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

const val MAX_USER_PROMPT_BYTES: Int = 2 * 1024

@JvmInline
value class TurnId(val value: String)

enum class InferenceBackend {
    CPU,
    GPU,
}

data class LlmToolDefinition(
    val name: String,
    val description: String,
    val parametersJsonSchema: String,
)

data class LlmToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/** A result produced by the trusted Kotlin tool boundary, never by model text. */
data class TrustedToolResponse(
    val callId: String,
    val name: String,
    val payloadJson: String,
)

sealed interface ModelEvent {
    val turnId: TurnId

    data class TextDelta(
        override val turnId: TurnId,
        val text: String,
    ) : ModelEvent

    /** Emitted only after LiteRT reports stream completion and before Completed. */
    data class FinalToolCalls(
        override val turnId: TurnId,
        val toolCalls: List<LlmToolCall>,
    ) : ModelEvent

    data class Completed(
        override val turnId: TurnId,
    ) : ModelEvent

    data class Failure(
        override val turnId: TurnId,
        val code: LlmFailureCode,
    ) : ModelEvent
}

enum class LlmFailureCode {
    CLOSED,
    NOT_INITIALIZED,
    ALREADY_INITIALIZED,
    MODEL_REJECTED,
    CACHE_REJECTED,
    INVALID_BACKEND,
    INITIALIZATION_FAILED,
    RUNTIME_BUSY,
    INVALID_TURN_ID,
    TURN_REPLAYED,
    TURN_MISMATCH,
    INVALID_PROMPT,
    INVALID_TOOL_DEFINITION,
    INVALID_TOOL_CALL,
    INVALID_TOOL_RESPONSE,
    NO_PENDING_TOOL_CALLS,
    CONTEXT_BUDGET_EXCEEDED,
    NATIVE_FAILURE,
}

class LlmRuntimeException(
    val code: LlmFailureCode,
) : Exception(code.name)

interface LlmRuntime : AutoCloseable {
    val state: StateFlow<LlmState>

    suspend fun initialize(
        model: VerifiedInstalledModel,
        backend: InferenceBackend = InferenceBackend.CPU,
        tools: List<LlmToolDefinition> = emptyList(),
    )

    fun streamUserTurn(
        turnId: TurnId,
        prompt: String,
    ): Flow<ModelEvent>

    fun streamToolResponses(
        turnId: TurnId,
        responses: List<TrustedToolResponse>,
    ): Flow<ModelEvent>

    suspend fun cancel(turnId: TurnId)
}

sealed interface LlmState {
    data object Off : LlmState

    data object Loading : LlmState

    data class Ready(val backend: InferenceBackend) : LlmState

    data class Failed(val code: LlmFailureCode) : LlmState
}
