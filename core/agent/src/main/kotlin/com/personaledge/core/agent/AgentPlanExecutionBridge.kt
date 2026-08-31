package com.personaledge.core.agent

import com.personaledge.core.tools.AgentTool
import com.personaledge.core.tools.AlarmNextTool
import com.personaledge.core.tools.CalendarQueryTool
import com.personaledge.core.tools.NotificationSearchTool
import com.personaledge.core.tools.PreparationResult
import com.personaledge.core.tools.ReminderQueryTool
import com.personaledge.core.tools.RouteEstimateTool
import com.personaledge.core.tools.ToolExecutionOutcome
import com.personaledge.core.tools.ToolOrchestrator
import com.personaledge.core.tools.ToolParams
import com.personaledge.core.tools.ToolRisk
import com.personaledge.core.tools.WebSearchTool
import com.personaledge.core.tools.WeatherTool
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Raw arguments live only for one execution request and are never copied into a checkpoint. */
data class EphemeralAgentPlanArguments(
    val stepId: AgentPlanStepId,
    val toolName: AgentPlanToolName,
    val argumentsJson: String,
) {
    override fun toString(): String =
        "EphemeralAgentPlanArguments(stepId=$stepId, toolName=$toolName, argumentsJson=<redacted>)"
}

enum class AgentPlanBridgeRefusalCode {
    PLAN_REJECTED,
    PLAN_DIGEST_MISMATCH,
    ARGUMENT_BINDING_MISMATCH,
    ARGUMENT_DIGEST_MISMATCH,
    TOOL_BINDING_MISMATCH,
    TOOL_PREPARATION_REFUSED,
    CHECKPOINT_RECONCILIATION_FAILED,
    GROUNDED_RESULT_UNAVAILABLE,
}

sealed interface AgentPlanBridgeResult {
    data class Executed(val result: AgentPlanExecutionResult) : AgentPlanBridgeResult

    data class Refused(val code: AgentPlanBridgeRefusalCode) : AgentPlanBridgeResult
}

/**
 * One model-proposed READ_ONLY request. Arguments are turn-ephemeral and redacted from logs.
 * The call id is used only to validate the completed model message; it is never checkpointed.
 */
internal data class EphemeralAgentReadRequest(
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
) {
    override fun toString(): String =
        "EphemeralAgentReadRequest(callId=<redacted>, toolName=$toolName, argumentsJson=<redacted>)"
}

/** Content-free receipt surfaced by the parallel-read bridge. */
internal data class AgentPlanReadReceipt(
    val ordinal: Int,
    val toolName: String,
    val outcome: ToolExecutionOutcome,
)

/**
 * Result for the production grounded-read path. The trusted answer is deliberately excluded from
 * [toString] so provider, calendar, reminder, or place content cannot leak through diagnostics.
 */
internal sealed interface AgentPlanGroundedBatchResult {
    class Executed internal constructor(
        val execution: AgentPlanExecutionResult,
        receipts: List<AgentPlanReadReceipt>,
        val trustedAnswer: String,
        val complete: Boolean,
    ) : AgentPlanGroundedBatchResult {
        val receipts: List<AgentPlanReadReceipt> = Collections.unmodifiableList(receipts.toList())

        override fun toString(): String =
            "AgentPlanGroundedBatchResult.Executed(receiptCount=${receipts.size}, complete=$complete)"
    }

    data class Refused(val code: AgentPlanBridgeRefusalCode) : AgentPlanGroundedBatchResult
}

/**
 * Production execution boundary for the typed AgentPlan MVP.
 *
 * The bridge accepts only tools resolved from the same closed registry used by the normal
 * controller, derives risk and resources from shipped Kotlin metadata, strictly decodes each
 * ephemeral argument object, and still routes every execution through [ToolOrchestrator]. The
 * legacy [execute] entry point still drops result content. [executeGroundedReadBatch] is the
 * production model-loop entry point: it accepts only independent, renderable READ_ONLY calls,
 * keeps typed results in turn-local memory, and returns one Kotlin-authored grounded answer.
 */
class AgentPlanExecutionBridge(
    registry: ManualToolRegistry,
    private val orchestrator: ToolOrchestrator,
    private val checkpointSink: AgentPlanCheckpointSink,
    private val limits: AgentPlanBridgeLimits = AgentPlanBridgeLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val reconcileInterruptedPlans: (suspend () -> Unit)? = null,
) {
    private val bindings: Map<AgentPlanToolName, TrustedReadBinding> = registry.registeredTools()
        .mapNotNull { registered ->
            trustedReadBindingOrNull(registered, limits.maxToolArgumentBytes)
        }
        .associateBy(TrustedReadBinding::toolName)
        .let { byName -> Collections.unmodifiableMap(LinkedHashMap(byName)) }

    private val verifier = PlanVerifier(
        catalog = AgentPlanPolicyCatalog.create(bindings.values.map(TrustedReadBinding::policy)),
        limits = PlanVerifierLimits(
            maxSteps = limits.maxSteps,
            maxDependenciesPerStep = limits.maxSteps - 1,
            maxResourcesPerStep = 16,
            maxLifetimeMillis = limits.maxLifetimeMillis,
        ),
        clock = clock,
    )
    private val reconciliationMutex = Mutex()
    @Volatile
    private var interruptedPlansReconciled = reconcileInterruptedPlans == null

    init {
        require(bindings.isNotEmpty()) { "AgentPlan bridge requires at least one trusted read Tool." }
    }

    /**
     * Returns the Kotlin-recomputed digest only for a currently valid, all-read plan. This digest
     * covers each catalog-resolved Tool identity/risk and canonical-argument digest.
     *
     * A caller can pin this value before crossing an asynchronous boundary and pass it back to
     * [execute]. No arguments or results are returned by this verification helper.
     */
    fun verifiedPlanDigest(proposal: AgentPlan): AgentPlanDigest? =
        (verifier.verify(proposal) as? PlanVerificationResult.Accepted)?.plan?.planDigest

    suspend fun execute(
        proposal: AgentPlan,
        expectedPlanDigest: AgentPlanDigest,
        arguments: List<EphemeralAgentPlanArguments>,
    ): AgentPlanBridgeResult = executeInternal(
        proposal = proposal,
        expectedPlanDigest = expectedPlanDigest,
        arguments = arguments,
        onReadCompleted = {},
    )

    /**
     * Converts one completed model message containing independent reads into a typed plan.
     *
     * Every call is strictly parsed and prepared once before any dispatch. A second preparation
     * at the execution boundary must reproduce the exact canonical digest, so a changed setting,
     * permission, interlock, or Tool implementation fails closed. Writes, notification content,
     * and other non-renderable reads are rejected as a whole before a plan checkpoint is opened.
     */
    internal suspend fun executeGroundedReadBatch(
        objective: String,
        requests: List<EphemeralAgentReadRequest>,
        lifetimeMillis: Long,
        onReadCompleted: (AgentPlanReadReceipt) -> Unit = {},
    ): AgentPlanGroundedBatchResult {
        if (requests.size !in 2..limits.maxSteps ||
            lifetimeMillis !in MIN_ACTION_LIFETIME..limits.maxLifetimeMillis ||
            objective.toByteArray(Charsets.UTF_8).size > MAX_OBJECTIVE_BYTES ||
            requests.map(EphemeralAgentReadRequest::callId).distinct().size != requests.size ||
            requests.any { request -> !SAFE_CALL_ID.matches(request.callId) }
        ) {
            return AgentPlanGroundedBatchResult.Refused(
                AgentPlanBridgeRefusalCode.PLAN_REJECTED,
            )
        }
        if (!ensureInterruptedPlansReconciled()) {
            return AgentPlanGroundedBatchResult.Refused(
                AgentPlanBridgeRefusalCode.CHECKPOINT_RECONCILIATION_FAILED,
            )
        }

        val planId = AgentPlanId.random()
        val expiresAt = runCatching { Math.addExact(clock(), lifetimeMillis) }.getOrNull()
            ?: return AgentPlanGroundedBatchResult.Refused(
                AgentPlanBridgeRefusalCode.PLAN_REJECTED,
            )
        val arguments = ArrayList<EphemeralAgentPlanArguments>(requests.size)
        val steps = ArrayList<AgentPlanStep>(requests.size)
        for ((index, request) in requests.withIndex()) {
            val toolName = AgentPlanToolName.parse(request.toolName)
                ?: return AgentPlanGroundedBatchResult.Refused(
                    AgentPlanBridgeRefusalCode.TOOL_BINDING_MISMATCH,
                )
            val binding = bindings[toolName]
                ?.takeIf(TrustedReadBinding::supportsGroundedEvidence)
                ?: return AgentPlanGroundedBatchResult.Refused(
                    AgentPlanBridgeRefusalCode.GROUNDED_RESULT_UNAVAILABLE,
                )
            val stepId = AgentPlanStepId(index + 1)
            val preflight = when (val preparation = binding.prepare(
                argumentsJson = request.argumentsJson,
                orchestrator = orchestrator,
                requestId = "agent-plan:${planId.value}:preflight:${stepId.value}",
                lifetimeMillis = lifetimeMillis,
                ordinal = stepId.value,
                onReadCompleted = {},
            )) {
                is ReadBindingPreparation.Ready -> preparation.binding
                ReadBindingPreparation.InvalidArguments -> {
                    return AgentPlanGroundedBatchResult.Refused(
                        AgentPlanBridgeRefusalCode.ARGUMENT_BINDING_MISMATCH,
                    )
                }
                ReadBindingPreparation.ToolRefused -> {
                    return AgentPlanGroundedBatchResult.Refused(
                        AgentPlanBridgeRefusalCode.TOOL_PREPARATION_REFUSED,
                    )
                }
            }
            arguments += EphemeralAgentPlanArguments(
                stepId = stepId,
                toolName = toolName,
                argumentsJson = request.argumentsJson,
            )
            steps += AgentPlanStep(
                id = stepId,
                toolName = toolName,
                argumentDigest = preflight.canonicalArgumentDigest,
                dependsOn = emptySet(),
                readSet = binding.policy.readableResourceKinds.mapTo(linkedSetOf()) { kind ->
                    AgentPlanResource(kind = kind, scopeDigest = preflight.canonicalArgumentDigest)
                },
                writeSet = emptySet(),
                declaredRisk = AgentPlanRisk.READ_ONLY,
            )
        }

        val proposal = AgentPlan(
            id = planId,
            objectiveDigest = AgentPlanDigest.sha256("agent-plan-objective-v1\n$objective"),
            expiresAtEpochMillis = expiresAt,
            steps = steps,
        )
        val expectedDigest = verifiedPlanDigest(proposal)
            ?: return AgentPlanGroundedBatchResult.Refused(
                AgentPlanBridgeRefusalCode.PLAN_REJECTED,
            )
        val observations = ConcurrentHashMap<Int, ReadExecutionObservation>()
        val bridgeResult = executeInternal(
            proposal = proposal,
            expectedPlanDigest = expectedDigest,
            arguments = arguments,
            onReadCompleted = { observation ->
                if (observations.putIfAbsent(observation.receipt.ordinal, observation) == null) {
                    onReadCompleted(observation.receipt)
                }
            },
        )
        if (bridgeResult is AgentPlanBridgeResult.Refused) {
            return AgentPlanGroundedBatchResult.Refused(bridgeResult.code)
        }
        val execution = (bridgeResult as AgentPlanBridgeResult.Executed).result
        val ordered = observations.values.sortedBy { observation -> observation.receipt.ordinal }
        if (ordered.isEmpty()) {
            return AgentPlanGroundedBatchResult.Refused(
                AgentPlanBridgeRefusalCode.GROUNDED_RESULT_UNAVAILABLE,
            )
        }
        val complete = execution.checkpoint.executionState == AgentPlanExecutionState.COMPLETED &&
            ordered.size == requests.size
        val answer = GroundedEvidenceRenderer.render(
            ordered.map(ReadExecutionObservation::evidence),
        ) + if (complete) {
            ""
        } else {
            "\n\n일부 조회를 완료하지 못했습니다. 완료된 결과만 표시했습니다."
        }
        return AgentPlanGroundedBatchResult.Executed(
            execution = execution,
            receipts = ordered.map(ReadExecutionObservation::receipt),
            trustedAnswer = answer,
            complete = complete,
        )
    }

    private suspend fun executeInternal(
        proposal: AgentPlan,
        expectedPlanDigest: AgentPlanDigest,
        arguments: List<EphemeralAgentPlanArguments>,
        onReadCompleted: (ReadExecutionObservation) -> Unit,
    ): AgentPlanBridgeResult {
        if (!ensureInterruptedPlansReconciled()) {
            return AgentPlanBridgeResult.Refused(
                AgentPlanBridgeRefusalCode.CHECKPOINT_RECONCILIATION_FAILED,
            )
        }
        val verified = when (val verification = verifier.verify(proposal)) {
            is PlanVerificationResult.Accepted -> verification.plan
            is PlanVerificationResult.Rejected -> {
                return AgentPlanBridgeResult.Refused(AgentPlanBridgeRefusalCode.PLAN_REJECTED)
            }
        }
        if (!digestsEqual(verified.planDigest, expectedPlanDigest)) {
            return AgentPlanBridgeResult.Refused(
                AgentPlanBridgeRefusalCode.PLAN_DIGEST_MISMATCH,
            )
        }

        val argumentsByStep = arguments.associateBy(EphemeralAgentPlanArguments::stepId)
        if (arguments.size != argumentsByStep.size ||
            argumentsByStep.keys != verified.steps.map(VerifiedAgentPlanStep::id).toSet()
        ) {
            return AgentPlanBridgeResult.Refused(
                AgentPlanBridgeRefusalCode.ARGUMENT_BINDING_MISMATCH,
            )
        }

        val preparedBindings = LinkedHashMap<AgentPlanStepId, PreparedReadBinding>()
        for (step in verified.steps) {
            val ephemeral = argumentsByStep.getValue(step.id)
            val binding = bindings[step.toolName]
                ?: return AgentPlanBridgeResult.Refused(
                    AgentPlanBridgeRefusalCode.TOOL_BINDING_MISMATCH,
                )
            if (ephemeral.toolName != step.toolName || binding.toolName != step.toolName ||
                step.risk != AgentPlanRisk.READ_ONLY || binding.descriptorRisk != ToolRisk.READ_ONLY
            ) {
                return AgentPlanBridgeResult.Refused(
                    AgentPlanBridgeRefusalCode.TOOL_BINDING_MISMATCH,
                )
            }
            val remainingLifetime = remainingLifetimeOrNull(verified)
                ?: return AgentPlanBridgeResult.Refused(
                    AgentPlanBridgeRefusalCode.PLAN_REJECTED,
                )
            val prepared = when (val preparation = binding.prepare(
                argumentsJson = ephemeral.argumentsJson,
                orchestrator = orchestrator,
                requestId = "agent-plan:${verified.id.value}:step:${step.id.value}",
                lifetimeMillis = remainingLifetime,
                ordinal = step.id.value,
                onReadCompleted = onReadCompleted,
            )) {
                is ReadBindingPreparation.Ready -> preparation.binding
                ReadBindingPreparation.InvalidArguments -> {
                    return AgentPlanBridgeResult.Refused(
                        AgentPlanBridgeRefusalCode.ARGUMENT_BINDING_MISMATCH,
                    )
                }
                ReadBindingPreparation.ToolRefused -> {
                    return AgentPlanBridgeResult.Refused(
                        AgentPlanBridgeRefusalCode.TOOL_PREPARATION_REFUSED,
                    )
                }
            }
            if (!digestsEqual(step.argumentDigest, prepared.canonicalArgumentDigest)) {
                return AgentPlanBridgeResult.Refused(
                    AgentPlanBridgeRefusalCode.ARGUMENT_DIGEST_MISMATCH,
                )
            }
            preparedBindings[step.id] = prepared
        }

        val executor = AgentPlanExecutor(
            runner = AgentPlanReadStepRunner { step ->
                val prepared = preparedBindings[step.id]
                    ?: return@AgentPlanReadStepRunner AgentPlanReadOutcome.REFUSED
                if (prepared.toolName != step.toolName ||
                    !digestsEqual(prepared.canonicalArgumentDigest, step.argumentDigest)
                ) {
                    return@AgentPlanReadStepRunner AgentPlanReadOutcome.REFUSED
                }
                prepared.execute()
            },
            checkpointSink = checkpointSink,
            limits = AgentPlanExecutorLimits(maxParallelReads = limits.maxParallelReads),
            clock = clock,
        )
        return AgentPlanBridgeResult.Executed(executor.execute(verified))
    }

    private suspend fun ensureInterruptedPlansReconciled(): Boolean {
        if (interruptedPlansReconciled) return true
        return reconciliationMutex.withLock {
            if (interruptedPlansReconciled) return@withLock true
            val reconcile = reconcileInterruptedPlans ?: return@withLock true
            try {
                reconcile()
                interruptedPlansReconciled = true
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun remainingLifetimeOrNull(plan: VerifiedAgentPlan): Long? {
        val remaining = plan.expiresAtEpochMillis - clock()
        if (remaining < MIN_ACTION_LIFETIME) return null
        return minOf(remaining, MAX_ACTION_LIFETIME)
    }

    private fun digestsEqual(first: AgentPlanDigest, second: AgentPlanDigest): Boolean =
        MessageDigest.isEqual(
            first.hex.toByteArray(StandardCharsets.US_ASCII),
            second.hex.toByteArray(StandardCharsets.US_ASCII),
        )

    private companion object {
        const val MIN_ACTION_LIFETIME = 1_000L
        const val MAX_ACTION_LIFETIME = 300_000L
        const val MAX_OBJECTIVE_BYTES = 8 * 1_024
        val SAFE_CALL_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
    }
}

data class AgentPlanBridgeLimits(
    val maxSteps: Int = 4,
    val maxParallelReads: Int = 2,
    val maxToolArgumentBytes: Int = 2_048,
    val maxLifetimeMillis: Long = 5 * 60_000L,
) {
    init {
        require(maxSteps in 1..4)
        require(maxParallelReads in 1..2)
        require(maxToolArgumentBytes in 64..16_384)
        require(maxLifetimeMillis in 1_000L..5 * 60_000L)
    }
}

private sealed interface TrustedReadBinding {
    val toolName: AgentPlanToolName
    val descriptorRisk: ToolRisk
    val policy: TrustedAgentPlanToolPolicy
    val supportsGroundedEvidence: Boolean

    suspend fun prepare(
        argumentsJson: String,
        orchestrator: ToolOrchestrator,
        requestId: String,
        lifetimeMillis: Long,
        ordinal: Int,
        onReadCompleted: (ReadExecutionObservation) -> Unit,
    ): ReadBindingPreparation
}

private class TypedTrustedReadBinding<P : ToolParams, R : Any>(
    private val tool: AgentTool<P, R>,
    override val policy: TrustedAgentPlanToolPolicy,
    private val parser: (String) -> ToolArgumentsParseResult<P>,
    private val evidenceFactory: ((ordinal: Int, params: P, result: R) -> GroundedReadEvidence)? = null,
) : TrustedReadBinding {
    override val toolName: AgentPlanToolName = policy.toolName
    override val descriptorRisk: ToolRisk = tool.descriptor.risk
    override val supportsGroundedEvidence: Boolean = evidenceFactory != null

    init {
        require(descriptorRisk == ToolRisk.READ_ONLY)
        require(tool.descriptor.name == toolName.value)
    }

    override suspend fun prepare(
        argumentsJson: String,
        orchestrator: ToolOrchestrator,
        requestId: String,
        lifetimeMillis: Long,
        ordinal: Int,
        onReadCompleted: (ReadExecutionObservation) -> Unit,
    ): ReadBindingPreparation {
        if (tool.descriptor.risk != ToolRisk.READ_ONLY ||
            tool.descriptor.name != toolName.value ||
            argumentsJson.toByteArray(Charsets.UTF_8).size > 16_384
        ) {
            return ReadBindingPreparation.ToolRefused
        }
        val params = when (val parsed = parser(argumentsJson)) {
            is ToolArgumentsParseResult.Valid -> parsed.params
            is ToolArgumentsParseResult.Invalid -> return ReadBindingPreparation.InvalidArguments
        }
        val prepared = try {
            orchestrator.prepare(
                tool = tool,
                params = params,
                requestId = requestId,
                lifetimeMillis = lifetimeMillis,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ReadBindingPreparation.ToolRefused
        }
        val action = (prepared as? PreparationResult.Ready)?.action
            ?: return ReadBindingPreparation.ToolRefused
        if (action.risk != ToolRisk.READ_ONLY || action.toolName != toolName.value) {
            return ReadBindingPreparation.ToolRefused
        }
        val digest = AgentPlanDigest.parse(action.parameterDigest)
            ?: return ReadBindingPreparation.ToolRefused
        return ReadBindingPreparation.Ready(
            PreparedReadBinding(
                toolName = toolName,
                canonicalArgumentDigest = digest,
                executeRead = {
                    val receipt = orchestrator.executeWithReceipt(action)
                    if (receipt.outcome == ToolExecutionOutcome.READ_COMPLETED) {
                        evidenceFactory?.invoke(ordinal, params, receipt.result)?.let { evidence ->
                            onReadCompleted(
                                ReadExecutionObservation(
                                    receipt = AgentPlanReadReceipt(
                                        ordinal = ordinal,
                                        toolName = toolName.value,
                                        outcome = receipt.outcome,
                                    ),
                                    evidence = evidence,
                                ),
                            )
                        }
                        AgentPlanReadOutcome.SUCCEEDED
                    } else {
                        AgentPlanReadOutcome.REFUSED
                    }
                },
            ),
        )
    }
}

private data class ReadExecutionObservation(
    val receipt: AgentPlanReadReceipt,
    val evidence: GroundedReadEvidence,
)

private sealed interface ReadBindingPreparation {
    data class Ready(val binding: PreparedReadBinding) : ReadBindingPreparation

    data object InvalidArguments : ReadBindingPreparation

    data object ToolRefused : ReadBindingPreparation
}

private data class PreparedReadBinding(
    val toolName: AgentPlanToolName,
    val canonicalArgumentDigest: AgentPlanDigest,
    private val executeRead: suspend () -> AgentPlanReadOutcome,
) {
    suspend fun execute(): AgentPlanReadOutcome = try {
        executeRead()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        AgentPlanReadOutcome.REFUSED
    }
}

private fun trustedReadBindingOrNull(
    registered: RegisteredManualTool,
    maxArgumentBytes: Int,
): TrustedReadBinding? {
    fun policy(
        toolName: String,
        readable: Set<AgentPlanResourceKind>,
    ): TrustedAgentPlanToolPolicy = TrustedAgentPlanToolPolicy.create(
        toolName = requireNotNull(AgentPlanToolName.parse(toolName)),
        risk = AgentPlanRisk.READ_ONLY,
        readableResourceKinds = readable,
    )

    return when (registered) {
        is RegisteredManualTool.FakeArrivalNotice -> TypedTrustedReadBinding(
            tool = registered.tool,
            policy = policy(registered.tool.descriptor.name, setOf(AgentPlanResourceKind.APP_STATE)),
            parser = { json -> FakeArrivalNoticeArgumentsParser(maxArgumentBytes).parse(json) },
        )
        is RegisteredManualTool.CalendarQuery -> TypedTrustedReadBinding(
            tool = registered.tool,
            policy = policy(CalendarQueryTool.NAME, setOf(AgentPlanResourceKind.CALENDAR)),
            parser = { json -> CalendarQueryArgumentsParser(maxArgumentBytes).parse(json) },
            evidenceFactory = { ordinal, _, result ->
                GroundedReadEvidence.CalendarQuery(ordinal, result)
            },
        )
        is RegisteredManualTool.AlarmNext -> TypedTrustedReadBinding(
            tool = registered.tool,
            policy = policy(AlarmNextTool.NAME, setOf(AgentPlanResourceKind.ALARM)),
            parser = { json -> AlarmNextArgumentsParser(maxArgumentBytes).parse(json) },
            evidenceFactory = { ordinal, _, result ->
                GroundedReadEvidence.AlarmNext(ordinal, result)
            },
        )
        is RegisteredManualTool.NotificationSearch -> TypedTrustedReadBinding(
            tool = registered.tool,
            policy = policy(
                NotificationSearchTool.NAME,
                setOf(AgentPlanResourceKind.NOTIFICATION),
            ),
            parser = { json -> NotificationSearchArgumentsParser(maxArgumentBytes).parse(json) },
        )
        is RegisteredManualTool.RouteEstimate -> TypedTrustedReadBinding(
            tool = registered.tool,
            policy = policy(RouteEstimateTool.NAME, setOf(AgentPlanResourceKind.NETWORK)),
            parser = { json -> RouteEstimateArgumentsParser(maxArgumentBytes).parse(json) },
            evidenceFactory = { ordinal, _, result ->
                GroundedReadEvidence.RouteEstimate(ordinal, result)
            },
        )
        is RegisteredManualTool.WebSearch -> TypedTrustedReadBinding(
            tool = registered.tool,
            policy = policy(WebSearchTool.NAME, setOf(AgentPlanResourceKind.NETWORK)),
            parser = { json -> WebSearchArgumentsParser(maxArgumentBytes).parse(json) },
            evidenceFactory = { ordinal, params, result ->
                GroundedReadEvidence.WebSearch(ordinal, params.query, result)
            },
        )
        is RegisteredManualTool.Weather -> TypedTrustedReadBinding(
            tool = registered.tool,
            policy = policy(WeatherTool.NAME, setOf(AgentPlanResourceKind.NETWORK)),
            parser = { json -> WeatherArgumentsParser(maxArgumentBytes).parse(json) },
            evidenceFactory = { ordinal, params, result ->
                GroundedReadEvidence.Weather(ordinal, params.location, result)
            },
        )
        is RegisteredManualTool.ReminderQuery -> TypedTrustedReadBinding(
            tool = registered.tool,
            policy = policy(ReminderQueryTool.NAME, setOf(AgentPlanResourceKind.REMINDER)),
            parser = { json -> ReminderQueryArgumentsParser(maxArgumentBytes).parse(json) },
            evidenceFactory = { ordinal, _, result ->
                GroundedReadEvidence.ReminderQuery(ordinal, result)
            },
        )
        is RegisteredManualTool.CalendarCreateEvent,
        is RegisteredManualTool.CalendarUpdateEvent,
        is RegisteredManualTool.AlarmSet,
        is RegisteredManualTool.KakaoShareMessage,
        is RegisteredManualTool.KakaoNotificationReply,
        is RegisteredManualTool.MemoryRemember,
        is RegisteredManualTool.CommitmentProposal,
        is RegisteredManualTool.ReminderCreate,
        is RegisteredManualTool.ReminderUpdate,
        is RegisteredManualTool.ReminderCancel,
        -> null
    }
}
