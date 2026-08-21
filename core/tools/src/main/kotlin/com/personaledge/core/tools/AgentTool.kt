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
}

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

    data class Invalid(val reason: String) : ValidationResult
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
    val parameterDigest: String,
    val idempotencyKey: String,
    internal val canonicalInput: CanonicalToolInput,
    internal val tool: AgentTool<P, R>,
    internal val permit: ExecutionPermit,
    internal val issuerToken: Any,
)

sealed interface PreparationResult<out P : ToolParams, out R : Any> {
    data class Ready<P : ToolParams, R : Any>(
        val action: PreparedAction<P, R>,
    ) : PreparationResult<P, R>

    data class Rejected(val reason: String) : PreparationResult<Nothing, Nothing>
}
