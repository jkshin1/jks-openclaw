package com.personaledge.core.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Absolute protocol limits for a remote agent run.
 *
 * A transport may negotiate smaller values, but must never accept or emit values above these
 * ceilings. In particular, a long-lived gateway connection does not make one run unbounded.
 */
object RemoteAgentContractLimits {
    const val MAX_SESSION_ID_CHARACTERS: Int = 128
    const val MAX_RUN_ID_CHARACTERS: Int = 128
    const val MAX_IDEMPOTENCY_KEY_CHARACTERS: Int = MAX_RUN_ID_CHARACTERS
    const val MAX_PROMPT_UTF8_BYTES: Int = 8 * 1_024
    const val MAX_EVENT_TEXT_UTF8_BYTES: Int = 16 * 1_024
    const val MAX_EVENTS_PER_RUN: Int = 2_048
    const val MAX_OUTPUT_UTF8_BYTES: Int = 256 * 1_024
    const val MAX_RUN_TIMEOUT_MILLIS: Long = 30 * 60_000L
    const val MAX_WAIT_TIMEOUT_MILLIS: Long = 60_000L
}

/**
 * Stable caller-owned identity for one submission attempt.
 *
 * Remote transports must serialize the same value on every owner-directed reconciliation retry.
 * Generating this value inside [RemoteAgentGateway.start] would make an outcome-unknown retry
 * indistinguishable from a second run.
 */
class RemoteAgentIdempotencyKey private constructor(val value: String) {
    override fun equals(other: Any?): Boolean =
        other is RemoteAgentIdempotencyKey && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "RemoteAgentIdempotencyKey(<redacted>)"

    companion object {
        fun parse(value: String): RemoteAgentIdempotencyKey? = value
            .takeIf { candidate ->
                candidate.length <= RemoteAgentContractLimits.MAX_IDEMPOTENCY_KEY_CHARACTERS &&
                    OPAQUE_ID.matches(candidate)
            }
            ?.let(::RemoteAgentIdempotencyKey)
    }
}

/** Opaque app-owned session identity. It cannot carry whitespace or arbitrary prompt content. */
class RemoteAgentSessionId private constructor(val value: String) {
    override fun equals(other: Any?): Boolean =
        other is RemoteAgentSessionId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "RemoteAgentSessionId(<redacted>)"

    companion object {
        fun parse(value: String): RemoteAgentSessionId? = value
            .takeIf { candidate ->
                candidate.length <= RemoteAgentContractLimits.MAX_SESSION_ID_CHARACTERS &&
                    OPAQUE_ID.matches(candidate)
            }
            ?.let(::RemoteAgentSessionId)
    }
}

/** Opaque provider run identity returned by [RemoteAgentGateway.start]. */
class RemoteAgentRunId private constructor(val value: String) {
    override fun equals(other: Any?): Boolean = other is RemoteAgentRunId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "RemoteAgentRunId(<redacted>)"

    companion object {
        fun parse(value: String): RemoteAgentRunId? = value
            .takeIf { candidate ->
                candidate.length <= RemoteAgentContractLimits.MAX_RUN_ID_CHARACTERS &&
                    OPAQUE_ID.matches(candidate)
            }
            ?.let(::RemoteAgentRunId)
    }
}

private val OPAQUE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")

/** Turn-ephemeral owner content with a protocol-level UTF-8 ceiling. */
class RemoteAgentPrompt private constructor(
    val text: String,
    val utf8Bytes: Int,
) {
    override fun toString(): String =
        "RemoteAgentPrompt(text=<redacted>, utf8Bytes=$utf8Bytes)"

    companion object {
        fun create(text: String): RemoteAgentPrompt? {
            val utf8Bytes = text.strictUtf8ByteCountOrNull() ?: return null
            return text
                .takeIf { candidate ->
                    candidate.isNotBlank() &&
                        '\u0000' !in candidate &&
                        utf8Bytes <= RemoteAgentContractLimits.MAX_PROMPT_UTF8_BYTES
                }
                ?.let { RemoteAgentPrompt(text = it, utf8Bytes = utf8Bytes) }
        }
    }
}

/** Per-run budgets that every concrete gateway implementation must enforce fail-closed. */
data class RemoteAgentRunLimits(
    val maxEvents: Int = 512,
    val maxOutputUtf8Bytes: Int = 64 * 1_024,
    val timeoutMillis: Long = 10 * 60_000L,
) {
    init {
        require(maxEvents in 1..RemoteAgentContractLimits.MAX_EVENTS_PER_RUN)
        require(maxOutputUtf8Bytes in 1..RemoteAgentContractLimits.MAX_OUTPUT_UTF8_BYTES)
        require(timeoutMillis in 1_000L..RemoteAgentContractLimits.MAX_RUN_TIMEOUT_MILLIS)
    }
}

/** Bounded timeout for one wait call; callers may wait again after a typed timeout result. */
data class RemoteAgentWaitTimeout(val millis: Long) {
    init {
        require(millis in 1L..RemoteAgentContractLimits.MAX_WAIT_TIMEOUT_MILLIS)
    }
}

/**
 * One remote run request.
 *
 * The prompt remains available only so a transport can serialize it. [toString] deliberately
 * reveals neither prompt nor session identity, and implementations must not persist either in
 * diagnostics.
 */
class RemoteAgentStartRequest(
    val idempotencyKey: RemoteAgentIdempotencyKey,
    val sessionId: RemoteAgentSessionId,
    val prompt: RemoteAgentPrompt,
    val limits: RemoteAgentRunLimits = RemoteAgentRunLimits(),
) {
    override fun toString(): String =
        "RemoteAgentStartRequest(idempotencyKey=<redacted>, sessionId=<redacted>, " +
            "prompt=<redacted>, " +
            "promptUtf8Bytes=${prompt.utf8Bytes}, limits=$limits)"
}

enum class RemoteAgentConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DEGRADED,
}

enum class RemoteAgentRunPhase {
    QUEUED,
    RUNNING,
    WAITING_FOR_APPROVAL,
    SUCCEEDED,
    CANCELLED,
    TIMED_OUT,
    FAILED,
    ;

    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == CANCELLED || this == TIMED_OUT || this == FAILED
}

enum class RemoteAgentFailureCode {
    NOT_CONNECTED,
    AUTHENTICATION_FAILED,
    PROTOCOL_MISMATCH,
    REQUEST_REJECTED,
    LIMIT_EXCEEDED,
    REMOTE_UNAVAILABLE,
    INTERNAL_ERROR,
}

/** Content-free progress snapshot suitable for UI state and bounded diagnostics. */
data class RemoteAgentRunStatus(
    val runId: RemoteAgentRunId,
    val phase: RemoteAgentRunPhase,
    val observedEvents: Int,
    val observedOutputUtf8Bytes: Int,
) {
    init {
        require(observedEvents in 0..RemoteAgentContractLimits.MAX_EVENTS_PER_RUN)
        require(observedOutputUtf8Bytes in 0..RemoteAgentContractLimits.MAX_OUTPUT_UTF8_BYTES)
    }
}

sealed interface RemoteAgentStartResult {
    data class Started(val runId: RemoteAgentRunId) : RemoteAgentStartResult

    /**
     * The request was enqueued, but no authoritative acceptance response was observed.
     * The caller-owned key is retained for reconciliation; no provider run id is invented.
     */
    data class OutcomeUnknown(
        val idempotencyKey: RemoteAgentIdempotencyKey,
    ) : RemoteAgentStartResult

    data class Refused(val code: RemoteAgentFailureCode) : RemoteAgentStartResult
}

sealed interface RemoteAgentStatusResult {
    data class Found(val status: RemoteAgentRunStatus) : RemoteAgentStatusResult

    /**
     * The run is caller-owned, but the provider did not return an authoritative current state.
     * A non-blocking remote wait timeout is one example and must not be mislabeled as not-found.
     */
    data class Unknown(val lastKnownStatus: RemoteAgentRunStatus?) : RemoteAgentStatusResult

    data object NotFound : RemoteAgentStatusResult

    data class Unavailable(val code: RemoteAgentFailureCode) : RemoteAgentStatusResult
}

sealed interface RemoteAgentCancelResult {
    data object Accepted : RemoteAgentCancelResult

    data object AlreadyTerminal : RemoteAgentCancelResult

    data object NotFound : RemoteAgentCancelResult

    data class Refused(val code: RemoteAgentFailureCode) : RemoteAgentCancelResult
}

sealed interface RemoteAgentWaitResult {
    data class Terminal(val status: RemoteAgentRunStatus) : RemoteAgentWaitResult {
        init {
            require(status.phase.isTerminal)
        }
    }

    data class TimedOut(val lastKnownStatus: RemoteAgentRunStatus?) : RemoteAgentWaitResult

    data object NotFound : RemoteAgentWaitResult

    data class Unavailable(val code: RemoteAgentFailureCode) : RemoteAgentWaitResult
}

/**
 * Bounded, typed events from a remote run. Provider messages and exception strings are absent.
 */
sealed interface RemoteAgentEvent {
    val runId: RemoteAgentRunId
    val sequence: Int

    data class StatusChanged(
        override val runId: RemoteAgentRunId,
        override val sequence: Int,
        val phase: RemoteAgentRunPhase,
    ) : RemoteAgentEvent {
        init {
            require(sequence in 1..RemoteAgentContractLimits.MAX_EVENTS_PER_RUN)
        }
    }

    class TextDelta private constructor(
        override val runId: RemoteAgentRunId,
        override val sequence: Int,
        val text: String,
        val utf8Bytes: Int,
    ) : RemoteAgentEvent {
        override fun toString(): String =
            "RemoteAgentEvent.TextDelta(runId=<redacted>, sequence=$sequence, " +
                "text=<redacted>, utf8Bytes=$utf8Bytes)"

        companion object {
            fun create(
                runId: RemoteAgentRunId,
                sequence: Int,
                text: String,
            ): TextDelta? {
                val utf8Bytes = text.strictUtf8ByteCountOrNull() ?: return null
                return text
                    .takeIf { candidate ->
                        sequence in 1..RemoteAgentContractLimits.MAX_EVENTS_PER_RUN &&
                            candidate.isNotEmpty() &&
                            '\u0000' !in candidate &&
                            utf8Bytes <= RemoteAgentContractLimits.MAX_EVENT_TEXT_UTF8_BYTES
                    }
                    ?.let {
                        TextDelta(
                            runId = runId,
                            sequence = sequence,
                            text = it,
                            utf8Bytes = utf8Bytes,
                        )
                    }
            }
        }
    }

    data class Failed(
        override val runId: RemoteAgentRunId,
        override val sequence: Int,
        val code: RemoteAgentFailureCode,
    ) : RemoteAgentEvent {
        init {
            require(sequence in 1..RemoteAgentContractLimits.MAX_EVENTS_PER_RUN)
        }
    }
}

/**
 * Remote long-running run boundary, intentionally separate from the one-turn local `LlmRuntime`.
 *
 * Concrete OpenClaw or other gateway clients own serialization and authentication outside this
 * module. They must enforce request budgets, monotonically increasing event sequences, cumulative
 * event/output limits, and map remote/provider failures only to the closed codes above.
 */
interface RemoteAgentGateway {
    val connectionState: StateFlow<RemoteAgentConnectionState>

    suspend fun start(request: RemoteAgentStartRequest): RemoteAgentStartResult

    fun events(runId: RemoteAgentRunId): Flow<RemoteAgentEvent>

    suspend fun status(runId: RemoteAgentRunId): RemoteAgentStatusResult

    suspend fun cancel(runId: RemoteAgentRunId): RemoteAgentCancelResult

    suspend fun waitForTerminal(
        runId: RemoteAgentRunId,
        timeout: RemoteAgentWaitTimeout,
    ): RemoteAgentWaitResult
}

private fun String.strictUtf8ByteCountOrNull(): Int? = runCatching {
    Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(this))
        .remaining()
}.getOrNull()
