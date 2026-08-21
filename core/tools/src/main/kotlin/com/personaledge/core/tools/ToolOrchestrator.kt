package com.personaledge.core.tools

import java.security.MessageDigest
import java.util.UUID

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
}

/**
 * Capability required for side-effecting tools. Its constructor is module-internal so
 * production implementations must be reviewed and shipped from this control module.
 */
abstract class PersistentActionLedger internal constructor() : ActionLedger

class ToolOrchestrator(
    private val actionLedger: ActionLedger,
    private val userConfirmationGate: UserConfirmationGate = UserConfirmationGate { false },
    private val strongAuthenticationGate: StrongAuthenticationGate =
        StrongAuthenticationGate { false },
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
    ): R {
        check(action.issuerToken === issuerToken) { "Prepared action came from another orchestrator." }
        check(clock() <= action.expiresAtEpochMillis) { "Prepared action expired." }
        check(authorize(action)) { "Required confirmation or strong authentication failed." }
        check(clock() <= action.expiresAtEpochMillis) { "Prepared action expired during authorization." }
        check(actionLedger.claim(action.idempotencyKey)) { "Action was already claimed." }
        check(clock() <= action.expiresAtEpochMillis) { "Prepared action expired while claiming it." }

        return action.tool.execute(action.canonicalInput, action.permit)
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
}
