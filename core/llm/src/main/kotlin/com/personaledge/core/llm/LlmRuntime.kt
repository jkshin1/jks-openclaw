package com.personaledge.core.llm

import java.util.Collections
import java.util.LinkedHashSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

const val MAX_USER_PROMPT_BYTES: Int = 2 * 1024

/**
 * Shared bound for app-authored Tool payloads before LiteRT-LM reinjection.
 *
 * The runtime reserves 512 context tokens for the complete native Tool-response turn. A payload
 * can tokenize as finely as one token per UTF-8 byte, so 384 bytes leaves 128 tokens for response
 * framing, Tool names, and control markers. Callers may truncate structured results earlier for a
 * useful partial response; the runtime still rechecks the aggregate canonical payload fail-closed.
 */
object TrustedToolResponseBudget {
    const val RESERVED_CONTEXT_TOKENS: Int = 512
    const val MAX_TOTAL_PAYLOAD_UTF8_BYTES: Int = 384

    fun allows(payloadsJson: Iterable<String>): Boolean {
        var remaining = MAX_TOTAL_PAYLOAD_UTF8_BYTES
        for (payload in payloadsJson) {
            val payloadBytes = payload.toByteArray(Charsets.UTF_8).size
            if (payloadBytes > remaining) return false
            remaining -= payloadBytes
        }
        return true
    }
}

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
) {
    override fun toString(): String =
        "LlmToolCall(id=<redacted>, name=$name, argumentsJson=<redacted>)"
}

/** A trusted Kotlin boundary response: a Tool result or static pre-execution validation rejection. */
data class TrustedToolResponse(
    val callId: String,
    val name: String,
    val payloadJson: String,
) {
    override fun toString(): String =
        "TrustedToolResponse(callId=<redacted>, name=$name, payloadJson=<redacted>)"
}

/** App-owned per-turn Tool exposure. Tool names are metadata; [toString] reveals only the count. */
class LlmTurnToolScope private constructor(toolNames: Set<String>) {
    val toolNames: Set<String> = Collections.unmodifiableSet(LinkedHashSet(toolNames))

    init {
        require(this.toolNames.size <= MAX_TOOL_NAMES)
        require(this.toolNames.all(TOOL_NAME::matches))
    }

    override fun toString(): String = "LlmTurnToolScope(toolCount=${toolNames.size})"

    companion object {
        fun exact(toolNames: Set<String>): LlmTurnToolScope = LlmTurnToolScope(toolNames)
        fun none(): LlmTurnToolScope = LlmTurnToolScope(emptySet())

        private const val MAX_TOOL_NAMES = 32
        private val TOOL_NAME = Regex("[a-z][a-z0-9_]{0,63}")
    }
}

sealed interface ModelEvent {
    val turnId: TurnId

    /** Ephemeral model reasoning from LiteRT's dedicated `thought` channel. */
    data class ThoughtDelta(
        override val turnId: TurnId,
        val text: String,
    ) : ModelEvent {
        override fun toString(): String =
            "ModelEvent.ThoughtDelta(turnId=$turnId, text=<redacted>)"
    }

    data class TextDelta(
        override val turnId: TurnId,
        val text: String,
    ) : ModelEvent {
        override fun toString(): String =
            "ModelEvent.TextDelta(turnId=$turnId, text=<redacted>)"
    }

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
    INVALID_MEDIA,
    MEDIA_UNSUPPORTED,
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

    /**
     * [mediaModalities] selects which encoders the engine loads, and defaults to none.
     *
     * Loading them is not free and not local to media: on the owner's device, enabling the vision
     * and audio executors made the GPU backend unavailable for the whole engine, so every text
     * turn fell back to CPU and ran about half as fast, with roughly 1.4 GB more resident memory.
     * A default-off feature must not impose that, so the caller passes the modalities the owner
     * actually enabled rather than everything the artifact happens to declare.
     */
    suspend fun initialize(
        model: VerifiedInstalledModel,
        backend: InferenceBackend = InferenceBackend.CPU,
        tools: List<LlmToolDefinition> = emptyList(),
        mediaModalities: Set<TurnMediaKind> = emptySet(),
    )

    fun streamUserTurn(
        turnId: TurnId,
        prompt: String,
    ): Flow<ModelEvent>

    /**
     * Starts a top-level turn with a bounded native decode budget when the runtime supports it.
     * Test or alternate runtimes that do not override this overload retain the legacy contract.
     */
    fun streamUserTurn(
        turnId: TurnId,
        prompt: String,
        maxOutputTokens: Int,
    ): Flow<ModelEvent> = streamUserTurn(turnId, prompt)

    /**
     * Starts a fresh top-level conversation exposing only the app-selected Tool subset.
     * Production runtimes override this; compatibility runtimes retain the legacy all-tool path.
     */
    fun streamUserTurn(
        turnId: TurnId,
        prompt: String,
        maxOutputTokens: Int,
        toolScope: LlmTurnToolScope,
    ): Flow<ModelEvent> = streamUserTurn(turnId, prompt, maxOutputTokens)

    /**
     * Starts a fresh top-level turn that also prefills bounded, Kotlin-validated media.
     *
     * Only production runtimes override this. Every other runtime keeps the text contract and
     * fails a media request closed rather than silently dropping the attachment, which would
     * answer a question about a photo the model never received.
     */
    fun streamUserTurn(
        turnId: TurnId,
        prompt: String,
        maxOutputTokens: Int,
        toolScope: LlmTurnToolScope,
        media: List<TurnMediaAttachment>,
    ): Flow<ModelEvent> = if (media.isEmpty()) {
        streamUserTurn(turnId, prompt, maxOutputTokens, toolScope)
    } else {
        flowOf(ModelEvent.Failure(turnId, LlmFailureCode.MEDIA_UNSUPPORTED))
    }

    /**
     * Native context tokens the current conversation holds, or null when unavailable.
     *
     * Content-free by nature: a count, never the tokens. It exists so a per-modality prefill cost
     * can be measured on the device instead of being carried as documentation, and so a media
     * turn's real context footprint can be compared against the same request without media.
     */
    suspend fun contextTokenCount(): Int? = null

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
