package com.personaledge.core.tools

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class ActionChallenge internal constructor(
    val actionId: String,
    val toolName: String,
    val preview: ActionPreview,
    val parameterDigest: String,
    val expiresAtEpochMillis: Long,
)

fun interface UserConfirmationGate {
    suspend fun confirm(challenge: ActionChallenge): Boolean
}

fun interface StrongAuthenticationGate {
    suspend fun authenticate(challenge: ActionChallenge): Boolean
}

/** Must persist claims across process death before any side-effecting tool is registered. */
fun interface ActionLedger {
    suspend fun claim(idempotencyKey: String): Boolean

    /**
     * Best-effort terminal-state persistence for an already claimed key.
     *
     * The default keeps lightweight test/read-only ledgers source-compatible. Production's
     * [PersistentActionLedger] overrides it so process restart retains the content-free outcome.
     */
    suspend fun recordState(
        idempotencyKey: String,
        state: ActionExecutionState,
    ): Boolean = true
}

/** Durable state only; no parameters, provider result, or user content is stored. */
enum class ActionExecutionState {
    CLAIMED,
    COMPLETED,
    REFUSED,
    UNKNOWN_AFTER_CLAIM,
}

/**
 * Capability required for side-effecting tools. Its constructor is module-internal so
 * production implementations must be reviewed and shipped from this control module.
 */
abstract class PersistentActionLedger internal constructor() : ActionLedger {
    abstract override suspend fun recordState(
        idempotencyKey: String,
        state: ActionExecutionState,
    ): Boolean
}

class ToolOrchestrator(
    private val actionLedger: ActionLedger,
    private val userConfirmationGate: UserConfirmationGate = UserConfirmationGate { false },
    private val strongAuthenticationGate: StrongAuthenticationGate =
        StrongAuthenticationGate { false },
    private val executionInterlock: ExecutionInterlock = CapabilityFreeInterlock(),
    private val confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val issuerToken = Any()

    suspend fun <P : ToolParams, R : Any> prepare(
        tool: AgentTool<P, R>,
        params: P,
        requestId: String,
        lifetimeMillis: Long = 60_000,
    ): PreparationResult<P, R> {
        require(requestId.isNotBlank())
        require(lifetimeMillis in 1_000..300_000)

        if (tool.descriptor.risk != ToolRisk.READ_ONLY && actionLedger !is PersistentActionLedger) {
            return PreparationResult.Rejected(
                "Side-effecting tools require a process-persistent action ledger.",
            )
        }

        val capabilities = tool.descriptor.requiredCapabilities
        val preparationDecision = executionInterlock.evaluate(
            InterlockRequest(
                toolName = tool.descriptor.name,
                risk = tool.descriptor.risk,
                requiredCapabilities = capabilities,
                phase = InterlockPhase.PREPARE,
            ),
        )
        if (preparationDecision is InterlockDecision.Block) {
            return PreparationResult.Rejected(preparationDecision.reason)
        }

        return when (val validation = tool.validateAndCanonicalize(params)) {
            is ValidationResult.Invalid -> PreparationResult.Rejected(validation.reason)
            is ValidationResult.Valid -> {
                val canonicalParams = validation.canonicalParams
                val canonicalInput = CanonicalToolInput(canonicalParams)
                val parameterDigest = sha256(canonicalParams)
                val actionId = idFactory()
                PreparationResult.Ready(
                    PreparedAction(
                        actionId = actionId,
                        toolName = tool.descriptor.name,
                        preview = tool.preview(canonicalInput),
                        expiresAtEpochMillis = clock() + lifetimeMillis,
                        confirmation = confirmationPolicy.evaluate(
                            risk = tool.descriptor.risk,
                            minimum = tool.descriptor.minimumConfirmation,
                        ),
                        parameterDigest = parameterDigest,
                        idempotencyKey = sha256("$requestId|${tool.descriptor.name}|$canonicalParams"),
                        requiredCapabilities = capabilities,
                        risk = tool.descriptor.risk,
                        canonicalInput = canonicalInput,
                        tool = tool,
                        permit = ExecutionPermit(actionId),
                        issuerToken = issuerToken,
                    ),
                )
            }
        }
    }

    suspend fun <P : ToolParams, R : Any> execute(
        action: PreparedAction<P, R>,
    ): R = executeWithReceipt(action).result

    /**
     * Executes exactly once and returns a content-free outcome alongside the trusted result.
     *
     * Authorization and the final interlock stay cancellable. For a side-effecting risk, once the
     * durable claim succeeds, execution and terminal-state recording run non-cancellably:
     * abandoning that boundary could hide a completed write and tempt an automatic retry. A
     * caller cancellation is observed again as soon as this method returns. READ_ONLY work has no
     * duplicate-write ambiguity and therefore remains normally cancellable throughout.
     */
    suspend fun <P : ToolParams, R : Any> executeWithReceipt(
        action: PreparedAction<P, R>,
        /** Non-suspending hook invoked after execution and terminal-state recording. */
        onExecuted: (ToolExecutionReceipt<R>) -> Unit = {},
    ): ToolExecutionReceipt<R> {
        check(action.issuerToken === issuerToken) { "Prepared action came from another orchestrator." }
        check(clock() <= action.expiresAtEpochMillis) { "Prepared action expired." }
        check(authorize(action)) { "Required confirmation or strong authentication failed." }
        check(clock() <= action.expiresAtEpochMillis) { "Prepared action expired during authorization." }

        // Permissions, thermal state, and target accounts can all change while the confirmation
        // dialog is on screen, so the preparation-time decision is never reused here.
        val decision = executionInterlock.evaluate(
            InterlockRequest(
                toolName = action.toolName,
                risk = action.risk,
                requiredCapabilities = action.requiredCapabilities,
                phase = InterlockPhase.EXECUTE,
            ),
        )
        check(decision is InterlockDecision.Allow) {
            (decision as InterlockDecision.Block).reason
        }

        // Claimed before the side effect, so an interrupted action is never retried. Blocking
        // after this point would spend the key, which is why the interlock runs first.
        check(actionLedger.claim(action.idempotencyKey)) { "Action was already claimed." }
        if (clock() > action.expiresAtEpochMillis) {
            recordStateBestEffort(
                idempotencyKey = action.idempotencyKey,
                state = ActionExecutionState.UNKNOWN_AFTER_CLAIM,
            )
            error("Prepared action expired while claiming it.")
        }

        return if (action.risk == ToolRisk.READ_ONLY) {
            executeClaimed(
                action = action,
                deferCancellation = false,
                onExecuted = onExecuted,
            )
        } else {
            withContext(NonCancellable) {
                executeClaimed(
                    action = action,
                    deferCancellation = true,
                    onExecuted = onExecuted,
                )
            }
        }
    }

    private suspend fun <P : ToolParams, R : Any> executeClaimed(
        action: PreparedAction<P, R>,
        deferCancellation: Boolean,
        onExecuted: (ToolExecutionReceipt<R>) -> Unit,
    ): ToolExecutionReceipt<R> {
        val receipt = try {
            val result = action.tool.execute(action.canonicalInput, action.permit)
            val outcome = action.tool.executionOutcome(result)
            recordStateBestEffort(
                idempotencyKey = action.idempotencyKey,
                state = outcome.toActionExecutionState(),
                deferCancellation = deferCancellation,
            )
            ToolExecutionReceipt(result = result, outcome = outcome)
        } catch (cancelled: CancellationException) {
            if (deferCancellation) {
                recordStateBestEffort(
                    idempotencyKey = action.idempotencyKey,
                    state = ActionExecutionState.UNKNOWN_AFTER_CLAIM,
                    deferCancellation = true,
                )
            }
            throw cancelled
        } catch (failure: Exception) {
            recordStateBestEffort(
                idempotencyKey = action.idempotencyKey,
                state = ActionExecutionState.UNKNOWN_AFTER_CLAIM,
                deferCancellation = deferCancellation,
            )
            throw failure
        }

        // This hook is deliberately outside the execution catch: a caller bug cannot relabel a
        // known provider outcome as unknown. For side effects it runs inside NonCancellable, so a
        // controller can retain the receipt before cancellation resumes at the context boundary.
        onExecuted(receipt)
        return receipt
    }

    private suspend fun recordStateBestEffort(
        idempotencyKey: String,
        state: ActionExecutionState,
        deferCancellation: Boolean = true,
    ) {
        if (deferCancellation) {
            withContext(NonCancellable) {
                recordStateIgnoringStorageFailure(idempotencyKey, state)
            }
        } else {
            try {
                actionLedger.recordState(idempotencyKey, state)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The original claim still blocks replay.
            }
        }
    }

    private suspend fun recordStateIgnoringStorageFailure(
        idempotencyKey: String,
        state: ActionExecutionState,
    ) {
        try {
            actionLedger.recordState(idempotencyKey, state)
        } catch (_: Exception) {
            // The original durable claim still blocks replay. Leaving it CLAIMED is the honest
            // representation when a terminal-state update itself cannot be persisted.
        }
    }

    private suspend fun authorize(
        action: PreparedAction<*, *>,
    ): Boolean = when (action.confirmation) {
        ConfirmationRequirement.NotRequired -> true
        ConfirmationRequirement.UserConfirmation -> userConfirmationGate.confirm(action.challenge())
        ConfirmationRequirement.StrongAuthentication ->
            strongAuthenticationGate.authenticate(action.challenge())
    }

    private fun PreparedAction<*, *>.challenge(): ActionChallenge = ActionChallenge(
        actionId = actionId,
        toolName = toolName,
        preview = preview,
        parameterDigest = parameterDigest,
        expiresAtEpochMillis = expiresAtEpochMillis,
    )

    private fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun ToolExecutionOutcome.toActionExecutionState(): ActionExecutionState = when (this) {
        ToolExecutionOutcome.READ_COMPLETED,
        ToolExecutionOutcome.WRITE_COMPLETED,
        -> ActionExecutionState.COMPLETED
        ToolExecutionOutcome.WRITE_REFUSED -> ActionExecutionState.REFUSED
    }
}
