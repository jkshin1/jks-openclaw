package com.personaledge.core.tools

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class ActionChallenge internal constructor(
    val actionId: String,
    val toolName: String,
    val preview: ActionPreview,
    /** Legacy canonical-input digest retained for existing confirmation presentation. */
    val parameterDigest: String,
    /** V2 identity covering the shown preview, trusted metadata, action identity, and expiry. */
    val challengeDigest: String,
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
        require(REQUEST_ID.matches(requestId)) {
            "Request identity must be bounded ASCII without unsafe delimiters."
        }
        require(lifetimeMillis in 1_000..300_000)

        val descriptor = tool.descriptor
        require(TOOL_NAME.matches(descriptor.name)) {
            "Tool name must match [a-z][a-z0-9_]{0,63}."
        }
        if (descriptor.risk != ToolRisk.READ_ONLY && actionLedger !is PersistentActionLedger) {
            return PreparationResult.Rejected(
                "Side-effecting tools require a process-persistent action ledger.",
            )
        }

        val capabilities = Collections.unmodifiableSet(descriptor.requiredCapabilities.toSet())
        val preparationDecision = executionInterlock.evaluate(
            InterlockRequest(
                toolName = descriptor.name,
                risk = descriptor.risk,
                requiredCapabilities = capabilities,
                phase = InterlockPhase.PREPARE,
            ),
        )
        if (preparationDecision is InterlockDecision.Block) {
            return PreparationResult.Rejected(preparationDecision.reason)
        }

        return when (val validation = tool.validateAndCanonicalize(params)) {
            is ValidationResult.Invalid -> PreparationResult.Rejected(
                reason = validation.reason,
                failureCode = validation.failureCode,
            )
            is ValidationResult.Valid -> {
                val canonicalParams = validation.canonicalParams
                val canonicalInput = CanonicalToolInput(canonicalParams)
                val parameterDigest = ToolActionIdentityV2.canonicalInputDigest(canonicalParams)
                // Freeze exactly one preview for the confirmation UI. Later policy boundaries
                // recompute it through the trusted Tool and compare only content-free digests.
                val preview = tool.preview(canonicalInput)
                val previewDigest = ToolActionIdentityV2.previewDigest(preview)
                val actionId = idFactory()
                check(actionId.isNotBlank()) { "Action identity must not be blank." }
                val expiresAtEpochMillis = Math.addExact(clock(), lifetimeMillis)
                val replayIdentityDigest = ToolActionIdentityV2.replayIdentityDigest(
                    requestId = requestId,
                    toolName = descriptor.name,
                    canonicalInputDigest = parameterDigest,
                    risk = descriptor.risk,
                    requiredCapabilities = capabilities,
                )
                // Keep the shipped ledger identity stable so pre-v2 durable claims still block
                // replay after an upgrade. The v2 challenge binds this digest together with the
                // stronger length-framed replay identity below.
                val idempotencyKey = ToolActionIdentityV2.legacyLedgerIdentityDigest(
                    requestId = requestId,
                    toolName = descriptor.name,
                    canonicalInput = canonicalParams,
                )
                val challengeDigest = ToolActionIdentityV2.challengeDigest(
                    requestId = requestId,
                    actionId = actionId,
                    toolName = descriptor.name,
                    canonicalInputDigest = parameterDigest,
                    previewDigest = previewDigest,
                    risk = descriptor.risk,
                    requiredCapabilities = capabilities,
                    expiresAtEpochMillis = expiresAtEpochMillis,
                    replayIdentityDigest = replayIdentityDigest,
                    ledgerIdentityDigest = idempotencyKey,
                )
                PreparationResult.Ready(
                    PreparedAction(
                        actionId = actionId,
                        toolName = descriptor.name,
                        preview = preview,
                        expiresAtEpochMillis = expiresAtEpochMillis,
                        confirmation = confirmationPolicy.evaluate(
                            risk = descriptor.risk,
                            minimum = descriptor.minimumConfirmation,
                        ),
                        parameterDigest = parameterDigest,
                        challengeDigest = challengeDigest,
                        previewDigest = previewDigest,
                        idempotencyKey = idempotencyKey,
                        replayIdentityDigest = replayIdentityDigest,
                        requiredCapabilities = capabilities,
                        risk = descriptor.risk,
                        requestId = requestId,
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
        /**
         * Cancellable, content-free hook for an app-owned turn gate. It runs only for a trusted
         * side-effect descriptor, after authorization and the execution interlock, but before the
         * durable claim and Tool invocation. A false result or exception therefore fails closed
         * without spending the idempotency key.
         */
        beforeSideEffectExecution: suspend (toolName: String, risk: ToolRisk) -> Boolean =
            { _, _ -> true },
        /** Non-suspending hook invoked after execution and terminal-state recording. */
        onExecuted: (ToolExecutionReceipt<R>) -> Unit = {},
    ): ToolExecutionReceipt<R> {
        check(action.issuerToken === issuerToken) { "Prepared action came from another orchestrator." }
        verifyPreparedActionIdentity(action)
        check(clock() < action.expiresAtEpochMillis) { "Prepared action expired." }
        check(authorize(action)) { "Required confirmation or strong authentication failed." }
        check(clock() < action.expiresAtEpochMillis) { "Prepared action expired during authorization." }

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

        if (action.risk != ToolRisk.READ_ONLY) {
            check(beforeSideEffectExecution(action.toolName, action.risk)) {
                "Side-effect execution gate denied the action."
            }
        }

        // Recompute after every cancellable authorization boundary. This is deliberately the last
        // synchronous policy check before the durable claim, so mutation or expiry cannot spend a
        // key or reach a Tool.
        verifyPreparedActionIdentity(action)
        check(clock() < action.expiresAtEpochMillis) {
            "Prepared action expired before the durable claim."
        }

        // Claimed before the side effect, so an interrupted action is never retried. Blocking
        // after this point would spend the key, which is why both execution gates run first.
        check(actionLedger.claim(action.idempotencyKey)) { "Action was already claimed." }
        if (clock() >= action.expiresAtEpochMillis) {
            recordStateBestEffort(
                idempotencyKey = action.idempotencyKey,
                state = if (action.risk == ToolRisk.READ_ONLY) {
                    ActionExecutionState.REFUSED
                } else {
                    ActionExecutionState.UNKNOWN_AFTER_CLAIM
                },
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
            val result = try {
                action.tool.execute(action.canonicalInput, action.permit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ToolExecutionException) {
                // Strip provider/user/credential text and mark that the classified exception came
                // from the resolved Tool execution boundary, not from policy or ledger code.
                throw ToolInvocationFailureException(failure.failureCode)
            }
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
            } else {
                // Cancellation is terminal for this claimed read and cannot hide an external
                // write. Finish only the content-free ledger update non-cancellably; the network
                // operation itself remains normally cancellable.
                recordStateBestEffort(
                    idempotencyKey = action.idempotencyKey,
                    state = ActionExecutionState.REFUSED,
                    deferCancellation = true,
                )
            }
            throw cancelled
        } catch (failure: Exception) {
            recordStateBestEffort(
                idempotencyKey = action.idempotencyKey,
                // A READ_ONLY Tool cannot have committed an external write, so an exception is a
                // terminal refusal to produce a trusted result rather than an ambiguous side
                // effect. Side-effecting Tools retain the conservative unknown state: a provider
                // may have committed immediately before the exception reached this boundary.
                state = if (action.risk == ToolRisk.READ_ONLY) {
                    ActionExecutionState.REFUSED
                } else {
                    ActionExecutionState.UNKNOWN_AFTER_CLAIM
                },
                // Once a durable claim exists, enriching it with the terminal state must not race
                // caller cancellation. Only this short content-free ledger write is shielded;
                // READ_ONLY provider work itself remains normally cancellable.
                deferCancellation = true,
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
        challengeDigest = challengeDigest,
        expiresAtEpochMillis = expiresAtEpochMillis,
    )

    private fun verifyPreparedActionIdentity(action: PreparedAction<*, *>) {
        val descriptor = action.tool.descriptor
        check(action.toolName == descriptor.name) { "Prepared Tool identity changed." }
        check(action.risk == descriptor.risk) { "Prepared Tool risk changed." }
        check(action.requiredCapabilities == descriptor.requiredCapabilities) {
            "Prepared Tool capabilities changed."
        }
        check(action.permit.actionId == action.actionId) { "Execution permit identity changed." }
        check(action.confirmation == confirmationPolicy.evaluate(action.risk, descriptor.minimumConfirmation)) {
            "Prepared confirmation requirement changed."
        }

        val canonicalInputDigest = ToolActionIdentityV2.canonicalInputDigest(
            action.canonicalInput.encoded,
        )
        check(digestsEqual(action.parameterDigest, canonicalInputDigest)) {
            "Prepared canonical input digest changed."
        }
        val displayedPreviewDigest = ToolActionIdentityV2.previewDigest(action.preview)
        check(digestsEqual(action.previewDigest, displayedPreviewDigest)) {
            "Prepared confirmation preview changed."
        }
        val trustedPreviewDigest = ToolActionIdentityV2.previewDigest(
            action.tool.preview(action.canonicalInput),
        )
        check(digestsEqual(action.previewDigest, trustedPreviewDigest)) {
            "Trusted Tool confirmation preview changed."
        }
        val replayIdentityDigest = ToolActionIdentityV2.replayIdentityDigest(
            requestId = action.requestId,
            toolName = action.toolName,
            canonicalInputDigest = canonicalInputDigest,
            risk = action.risk,
            requiredCapabilities = action.requiredCapabilities,
        )
        check(digestsEqual(action.replayIdentityDigest, replayIdentityDigest)) {
            "Prepared replay identity changed."
        }
        val ledgerIdentityDigest = ToolActionIdentityV2.legacyLedgerIdentityDigest(
            requestId = action.requestId,
            toolName = action.toolName,
            canonicalInput = action.canonicalInput.encoded,
        )
        check(digestsEqual(action.idempotencyKey, ledgerIdentityDigest)) {
            "Prepared ledger identity changed."
        }
        val challengeDigest = ToolActionIdentityV2.challengeDigest(
            requestId = action.requestId,
            actionId = action.actionId,
            toolName = action.toolName,
            canonicalInputDigest = canonicalInputDigest,
            previewDigest = trustedPreviewDigest,
            risk = action.risk,
            requiredCapabilities = action.requiredCapabilities,
            expiresAtEpochMillis = action.expiresAtEpochMillis,
            replayIdentityDigest = replayIdentityDigest,
            ledgerIdentityDigest = ledgerIdentityDigest,
        )
        check(digestsEqual(action.challengeDigest, challengeDigest)) {
            "Prepared challenge identity changed."
        }
    }

    private fun digestsEqual(first: String, second: String): Boolean = MessageDigest.isEqual(
        first.toByteArray(StandardCharsets.US_ASCII),
        second.toByteArray(StandardCharsets.US_ASCII),
    )

    private fun ToolExecutionOutcome.toActionExecutionState(): ActionExecutionState = when (this) {
        ToolExecutionOutcome.READ_COMPLETED,
        ToolExecutionOutcome.WRITE_COMPLETED,
        -> ActionExecutionState.COMPLETED
        ToolExecutionOutcome.WRITE_REFUSED -> ActionExecutionState.REFUSED
    }

    private companion object {
        val TOOL_NAME = Regex("[a-z][a-z0-9_]{0,63}")
        val REQUEST_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,191}")
    }
}

/**
 * V2 action identities use labeled, length-framed UTF-8 fields rather than delimiter joining.
 * Only [canonicalInputDigest] and [previewDigest], never canonical input or preview text, enter the
 * v2 challenge. [legacyLedgerIdentityDigest] reproduces the shipped hash solely to preserve
 * existing durable replay claims; only its resulting digest is bound into v2 or sent to the
 * ledger.
 */
internal object ToolActionIdentityV2 {
    private const val CHALLENGE_DOMAIN = "com.personaledge.core.tools/action-challenge/v2"
    private const val PREVIEW_DOMAIN = "com.personaledge.core.tools/action-preview/v2"
    private const val REPLAY_DOMAIN = "com.personaledge.core.tools/replay-identity/v2"

    fun canonicalInputDigest(canonicalInput: String): String = sha256Utf8(canonicalInput)

    fun previewDigest(preview: ActionPreview): String = FramedSha256(PREVIEW_DOMAIN).apply {
        field("title", preview.title)
        field("summary", preview.summary)
    }.finish()

    /** Shipped v1 key retained only so existing durable claims remain replay-blocking. */
    fun legacyLedgerIdentityDigest(
        requestId: String,
        toolName: String,
        canonicalInput: String,
    ): String = sha256Utf8("$requestId|$toolName|$canonicalInput")

    fun replayIdentityDigest(
        requestId: String,
        toolName: String,
        canonicalInputDigest: String,
        risk: ToolRisk,
        requiredCapabilities: Set<ToolCapability>,
    ): String = FramedSha256(REPLAY_DOMAIN).apply {
        field("requestId", requestId)
        field("toolName", toolName)
        field("canonicalInputSha256", canonicalInputDigest)
        field("risk", risk.name)
        values("requiredCapabilities", requiredCapabilities.map { it.name }.sorted())
    }.finish()

    fun challengeDigest(
        requestId: String,
        actionId: String,
        toolName: String,
        canonicalInputDigest: String,
        previewDigest: String,
        risk: ToolRisk,
        requiredCapabilities: Set<ToolCapability>,
        expiresAtEpochMillis: Long,
        replayIdentityDigest: String,
        ledgerIdentityDigest: String,
    ): String = FramedSha256(CHALLENGE_DOMAIN).apply {
        field("requestId", requestId)
        field("actionId", actionId)
        field("toolName", toolName)
        field("canonicalInputSha256", canonicalInputDigest)
        field("previewSha256", previewDigest)
        field("risk", risk.name)
        values("requiredCapabilities", requiredCapabilities.map { it.name }.sorted())
        field("expiresAtEpochMillis", expiresAtEpochMillis.toString())
        field("replayIdentitySha256", replayIdentityDigest)
        field("ledgerIdentitySha256", ledgerIdentityDigest)
    }.finish()

    private class FramedSha256(domain: String) {
        private val digest = MessageDigest.getInstance("SHA-256")

        init {
            frame("domain")
            frame(domain)
        }

        fun field(label: String, value: String) {
            frame(label)
            frame(value)
        }

        fun values(label: String, values: List<String>) {
            frame(label)
            updateLength(values.size)
            values.forEach(::frame)
        }

        fun finish(): String = sha256Hex(digest.digest())

        private fun frame(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            updateLength(bytes.size)
            digest.update(bytes)
        }

        private fun updateLength(length: Int) {
            require(length >= 0)
            digest.update(
                byteArrayOf(
                    (length ushr 24).toByte(),
                    (length ushr 16).toByte(),
                    (length ushr 8).toByte(),
                    length.toByte(),
                ),
            )
        }
    }

    private fun sha256Utf8(value: String): String = sha256Hex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

    private fun sha256Hex(value: ByteArray): String = buildString(value.size * 2) {
        value.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(HEX[unsigned ushr 4])
            append(HEX[unsigned and 0x0f])
        }
    }

    private const val HEX = "0123456789abcdef"
}
