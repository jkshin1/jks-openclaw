package com.personaledge.core.diagnostics

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

fun interface DiagnosticSink {
    fun record(event: DiagnosticEvent): Boolean
}

sealed interface DiagnosticEvent {
    data object ProcessStarted : DiagnosticEvent

    data object SessionStarted : DiagnosticEvent

    data class HistoricalExit(
        val exit: HistoricalExitRecord,
    ) : DiagnosticEvent

    data class ModelInspected(
        val sizeBytes: Long,
        val durationMillis: Long,
        val result: DiagnosticResult,
        val errorCode: DiagnosticErrorCode? = null,
        val failure: DiagnosticFailure? = null,
    ) : DiagnosticEvent

    data class ModelImported(
        val sizeBytes: Long,
        val durationMillis: Long,
        val result: DiagnosticResult,
        val errorCode: DiagnosticErrorCode? = null,
        val failure: DiagnosticFailure? = null,
    ) : DiagnosticEvent

    data class RuntimeInitialized(
        val requestedBackend: DiagnosticBackend,
        val activeBackend: DiagnosticBackend?,
        val durationMillis: Long,
        val result: DiagnosticResult,
        val errorCode: DiagnosticErrorCode? = null,
        val failure: DiagnosticFailure? = null,
    ) : DiagnosticEvent

    data class TurnStarted(
        val promptByteCount: Int,
    ) : DiagnosticEvent

    data class TurnFirstToken(
        val ttftMillis: Long,
    ) : DiagnosticEvent

    data class TurnCompleted(
        val durationMillis: Long,
        val deltaCount: Int,
        val deltaByteCount: Long,
    ) : DiagnosticEvent

    data class TurnCancelled(
        val durationMillis: Long,
        val deltaCount: Int,
        val deltaByteCount: Long,
        val cause: DiagnosticTurnCancellationCause,
        val thermalStatus: DiagnosticThermalStatus? = null,
    ) : DiagnosticEvent

    data class TurnFailed(
        val durationMillis: Long,
        val deltaCount: Int,
        val deltaByteCount: Long,
        val errorCode: DiagnosticErrorCode,
        val failure: DiagnosticFailure? = null,
    ) : DiagnosticEvent

    data class ToolPhase(
        val name: DiagnosticToolName,
        val stage: DiagnosticToolStage,
        val risk: DiagnosticToolRisk,
        val confirmationOutcome: DiagnosticConfirmationOutcome,
    ) : DiagnosticEvent

    data class ResourceSnapshot(
        val metrics: ResourceMetrics,
    ) : DiagnosticEvent

    /** Content-free evidence of the Android thermal policy boundary. */
    data class ThermalGuard(
        val status: DiagnosticThermalStatus,
        val action: DiagnosticThermalAction,
    ) : DiagnosticEvent
}

enum class DiagnosticPhase {
    PROCESS_START,
    SESSION_START,
    MODEL_INSPECT,
    MODEL_IMPORT,
    RUNTIME_INITIALIZATION,
    TURN_PROCESSING,
    TOOL_CONFIRMATION,
    TOOL_EXECUTION,
    IDLE,
    SHUTDOWN,
}

enum class DiagnosticBackend {
    CPU,
    GPU,
}

enum class DiagnosticResult {
    SUCCESS,
    MISSING,
    FAILURE,
    CANCELLED,
    REJECTED,
}

enum class DiagnosticToolRisk {
    READ_ONLY,
    LOCAL_WRITE,
    DATA_WRITE,
    COMMUNICATION,
    VEHICLE_CONTROL,
    HIGH_RISK,
}

enum class DiagnosticToolStage {
    CONFIRMATION_REQUESTED,
    CONFIRMATION_RESOLVED,
    EXECUTED,
}

enum class DiagnosticConfirmationOutcome {
    NOT_REQUIRED,
    REQUESTED,
    APPROVED,
    DENIED,
    AUTHENTICATION_FAILED,
    EXPIRED,
    CANCELLED,
}

enum class DiagnosticThermalStatus {
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN,
    UNKNOWN,
}

enum class DiagnosticThermalAction {
    STATUS_OBSERVED,
    RUNTIME_INITIALIZATION_REJECTED,
    RUNTIME_INITIALIZATION_CANCEL_REQUESTED,
    TURN_REJECTED,
    COOPERATIVE_CANCEL_REQUESTED,
    IMMEDIATE_ABORT_REQUESTED,
}

enum class DiagnosticTurnCancellationCause {
    USER,
    THERMAL,
    LIFECYCLE,
    UNKNOWN,
}

enum class DiagnosticExitReason {
    UNKNOWN,
    EXIT_SELF,
    SIGNALED,
    LOW_MEMORY,
    CRASH,
    CRASH_NATIVE,
    ANR,
    INITIALIZATION_FAILURE,
    PERMISSION_CHANGE,
    EXCESSIVE_RESOURCE_USAGE,
    USER_REQUESTED,
    USER_STOPPED,
    DEPENDENCY_DIED,
    OTHER,
    FREEZER,
    PACKAGE_STATE_CHANGE,
    PACKAGE_UPDATED,
}

enum class DiagnosticErrorCode {
    UNKNOWN,
    INVALID_INPUT,
    SOURCE_UNAVAILABLE,
    INSUFFICIENT_STORAGE,
    INVALID_SIZE,
    INVALID_DIGEST,
    INVALID_FILE_TYPE,
    STORAGE_FAILURE,
    CLOSED,
    NOT_INITIALIZED,
    ALREADY_INITIALIZED,
    MODEL_REJECTED,
    CACHE_REJECTED,
    INVALID_BACKEND,
    INITIALIZATION_FAILED,
    RUNTIME_BUSY,
    INVALID_TURN,
    TURN_REPLAYED,
    TURN_MISMATCH,
    INVALID_PROMPT,
    INVALID_TOOL_DEFINITION,
    INVALID_TOOL_CALL,
    INVALID_TOOL_RESPONSE,
    NO_PENDING_TOOL_CALLS,
    CONTEXT_BUDGET_EXCEEDED,
    NATIVE_FAILURE,
    DEADLINE_EXCEEDED,
    INVALID_MODEL_SEQUENCE,
    STEP_LIMIT_EXCEEDED,
    TOOL_CALL_LIMIT_EXCEEDED,
    UNKNOWN_TOOL,
    TOOL_NOT_EXECUTED,
    MODEL_FAILURE,
    INSPECTION_FAILED,
    IMPORT_FAILED,
    TOOL_FAILED,
    RESOURCE_UNAVAILABLE,
}

data class HistoricalExitRecord(
    val reason: DiagnosticExitReason,
    val status: Int,
    val importance: Int,
    val pssBytes: Long,
    val rssBytes: Long,
    val timestampMillis: Long,
    val phase: DiagnosticPhase?,
)

data class ResourceMetrics(
    val pssBytes: Long,
    val javaHeapBytes: Long,
    val thermalStatus: DiagnosticThermalStatus,
)

@JvmInline
value class DiagnosticToolName private constructor(
    val value: String,
) {
    companion object {
        private val VALID_NAME = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")

        fun parse(value: String): DiagnosticToolName? =
            value.takeIf(VALID_NAME::matches)?.let(::DiagnosticToolName)
    }
}

/**
 * Redacted failure metadata. The original message and stack text are never retained.
 * The fingerprint hashes type/frame identifiers and line numbers, but never file names.
 */
class DiagnosticFailure private constructor(
    val throwableType: String,
    val stackFingerprintSha256: String,
) {
    companion object {
        fun from(throwable: Throwable): DiagnosticFailure {
            val digest = MessageDigest.getInstance("SHA-256")
            var current: Throwable? = throwable
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                digest.update(current.javaClass.name.toByteArray(StandardCharsets.UTF_8))
                digest.update(0.toByte())
                current.stackTrace.take(MAX_FRAMES_PER_THROWABLE).forEach { frame ->
                    digest.update(frame.className.toByteArray(StandardCharsets.UTF_8))
                    digest.update(0.toByte())
                    digest.update(frame.methodName.toByteArray(StandardCharsets.UTF_8))
                    digest.update(0.toByte())
                    digest.update(frame.lineNumber.toString().toByteArray(StandardCharsets.US_ASCII))
                    digest.update(if (frame.isNativeMethod) 1.toByte() else 0.toByte())
                }
                current = current.cause
                depth += 1
            }
            return DiagnosticFailure(
                throwableType = throwable.javaClass.name.take(MAX_THROWABLE_TYPE_BYTES),
                stackFingerprintSha256 = digest.digest().joinToString("") { "%02x".format(it) },
            )
        }

        private const val MAX_CAUSE_DEPTH = 8
        private const val MAX_FRAMES_PER_THROWABLE = 128
        private const val MAX_THROWABLE_TYPE_BYTES = 160
    }
}
