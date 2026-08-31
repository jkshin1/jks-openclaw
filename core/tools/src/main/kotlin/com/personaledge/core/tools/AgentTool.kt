package com.personaledge.core.tools

/** Typed, model-originated input. It is validated but never retained for execution. */
interface ToolParams

/** A typed boundary between model-selected intent and deterministic execution. */
interface AgentTool<P : ToolParams, R : Any> {
    val descriptor: ToolDescriptor

    /** Validate and return one immutable snapshot containing every effect-bearing field. */
    suspend fun validateAndCanonicalize(params: P): ValidationResult

    /** User-visible description derived only from the immutable canonical snapshot. */
    fun preview(input: CanonicalToolInput): ActionPreview

    /** [ExecutionPermit] can only be issued by [ToolOrchestrator]. */
    suspend fun execute(input: CanonicalToolInput, permit: ExecutionPermit): R

    /**
     * Reduces a trusted result to content-free execution evidence.
     *
     * Implementations that can return a normal value while refusing a requested write must
     * override this method. The result itself is never retained in the action ledger.
     */
    fun executionOutcome(result: R): ToolExecutionOutcome =
        if (descriptor.risk == ToolRisk.READ_ONLY) {
            ToolExecutionOutcome.READ_COMPLETED
        } else {
            ToolExecutionOutcome.WRITE_COMPLETED
        }
}

/** Small, content-free receipt safe to retain and surface outside the trusted tool boundary. */
enum class ToolExecutionOutcome {
    READ_COMPLETED,
    WRITE_COMPLETED,
    WRITE_REFUSED,
}

/** A tool result paired with the outcome durably recorded after a successful claim. */
data class ToolExecutionReceipt<out R : Any>(
    val result: R,
    val outcome: ToolExecutionOutcome,
)

/** Immutable snapshot shared by confirmation UI and execution. */
@JvmInline
value class CanonicalToolInput internal constructor(
    val encoded: String,
)

data class ToolDescriptor(
    val name: String,
    val description: String,
    val risk: ToolRisk,
    /** A tool may strengthen, but never weaken, the risk-derived requirement. */
    val minimumConfirmation: ConfirmationRequirement = ConfirmationRequirement.NotRequired,
    /** Runtime preconditions the [ExecutionInterlock], not the tool itself, must re-verify. */
    val requiredCapabilities: Set<ToolCapability> = emptySet(),
)

enum class ToolRisk {
    READ_ONLY,
    LOCAL_WRITE,
    DATA_WRITE,
    COMMUNICATION,
    VEHICLE_CONTROL,
    HIGH_RISK,
}

sealed interface ValidationResult {
    data class Valid(val canonicalParams: String) : ValidationResult

    /** [failureCode] is optional and must never encode model, provider, or credential text. */
    data class Invalid(
        val reason: String,
        val failureCode: ToolFailureCode? = null,
    ) : ValidationResult
}

class ExecutionPermit internal constructor(
    internal val actionId: String,
)

data class ActionPreview(
    val title: String,
    val summary: String,
)

class PreparedAction<P : ToolParams, R : Any> internal constructor(
    val actionId: String,
    val toolName: String,
    val preview: ActionPreview,
    val expiresAtEpochMillis: Long,
    val confirmation: ConfirmationRequirement,
    /** SHA-256 of the canonical input retained for compatibility with existing confirmation UI. */
    val parameterDigest: String,
    /** Domain-separated v2 digest binding the complete trusted authorization identity. */
    val challengeDigest: String,
    /** Content-free digest binding the exact title and summary presented for confirmation. */
    internal val previewDigest: String,
    /** Legacy-stable replay key; the durable ledger receives only this content-free digest. */
    val idempotencyKey: String,
    /** Length-framed v2 replay identity bound into [challengeDigest]. */
    internal val replayIdentityDigest: String,
    val requiredCapabilities: Set<ToolCapability>,
    val risk: ToolRisk,
    internal val requestId: String,
    internal val canonicalInput: CanonicalToolInput,
    internal val tool: AgentTool<P, R>,
    internal val permit: ExecutionPermit,
    internal val issuerToken: Any,
)

sealed interface PreparationResult<out P : ToolParams, out R : Any> {
    data class Ready<P : ToolParams, R : Any>(
        val action: PreparedAction<P, R>,
    ) : PreparationResult<P, R>

    /** Closed failure metadata may cross layers; [reason] remains internal to the Tool boundary. */
    data class Rejected(
        val reason: String,
        val failureCode: ToolFailureCode? = null,
    ) : PreparationResult<Nothing, Nothing>
}
