package com.personaledge.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanVerifierTest {
    private val now = 100_000L
    private val readTool = toolName("calendar_query")
    private val writeTool = toolName("reminder_create")
    private val catalog = AgentPlanPolicyCatalog.create(
        listOf(
            TrustedAgentPlanToolPolicy.create(
                toolName = readTool,
                risk = AgentPlanRisk.READ_ONLY,
                readableResourceKinds = setOf(AgentPlanResourceKind.CALENDAR),
            ),
            TrustedAgentPlanToolPolicy.create(
                toolName = writeTool,
                risk = AgentPlanRisk.LOCAL_WRITE,
                writableResourceKinds = setOf(AgentPlanResourceKind.REMINDER),
            ),
        ),
    )
    private val verifier = PlanVerifier(catalog = catalog, clock = { now })

    @Test
    fun `verification and digest are deterministic across proposal collection order`() {
        val calendarA = AgentPlanResource(AgentPlanResourceKind.CALENDAR, digest('d'))
        val calendarB = AgentPlanResource(AgentPlanResourceKind.CALENDAR, digest('e'))
        val first = readStep(
            id = 1,
            argument = 'b',
            reads = linkedSetOf(calendarB, calendarA),
        )
        val second = readStep(id = 2, argument = 'c', dependsOn = linkedSetOf(stepId(1)))
        val forward = plan(steps = listOf(first, second))
        val reordered = plan(
            steps = listOf(
                second.copy(dependsOn = linkedSetOf(stepId(1))),
                first.copy(readSet = linkedSetOf(calendarA, calendarB)),
            ),
        )

        val acceptedForward = verifier.verify(forward) as PlanVerificationResult.Accepted
        val acceptedReordered = verifier.verify(reordered) as PlanVerificationResult.Accepted

        assertEquals(acceptedForward.plan.planDigest, acceptedReordered.plan.planDigest)
        assertEquals(listOf(stepId(1), stepId(2)), acceptedForward.plan.steps.map { it.id })
        assertEquals(AgentPlanRisk.READ_ONLY, acceptedForward.plan.steps.first().risk)
    }

    @Test
    fun `verified plan is a defensive immutable scheduling copy`() {
        val dependencies = mutableSetOf<AgentPlanStepId>()
        val reads = mutableSetOf(
            AgentPlanResource(AgentPlanResourceKind.CALENDAR, digest('d')),
        )
        val steps = mutableListOf(readStep(1, 'b', dependencies, reads))
        val accepted = verifier.verify(plan(steps)) as PlanVerificationResult.Accepted

        dependencies += stepId(2)
        reads.clear()
        steps.clear()

        assertEquals(1, accepted.plan.steps.size)
        assertTrue(accepted.plan.steps.single().dependsOn.isEmpty())
        assertEquals(1, accepted.plan.steps.single().readSet.size)
    }

    @Test
    fun `catalog risk is authoritative and rejections do not echo model tool text`() {
        val mismatched = readStep(1, 'b').copy(declaredRisk = AgentPlanRisk.COMMUNICATION)
        val unknown = readStep(2, 'c').copy(toolName = toolName("secret_exfiltration"))

        val rejected = verifier.verify(plan(listOf(mismatched, unknown)))
            as PlanVerificationResult.Rejected

        assertEquals(
            listOf(PlanViolationCode.RISK_MISMATCH, PlanViolationCode.UNKNOWN_TOOL),
            rejected.violations.map { violation -> violation.code },
        )
        assertFalse(rejected.toString().contains("secret_exfiltration"))
    }

    @Test
    fun `unknown self and cyclic dependencies fail closed`() {
        val unknown = verifier.verify(
            plan(listOf(readStep(1, 'b', setOf(stepId(9))))),
        ) as PlanVerificationResult.Rejected
        val self = verifier.verify(
            plan(listOf(readStep(1, 'b', setOf(stepId(1))))),
        ) as PlanVerificationResult.Rejected
        val cycle = verifier.verify(
            plan(
                listOf(
                    readStep(1, 'b', setOf(stepId(2))),
                    readStep(2, 'c', setOf(stepId(1))),
                ),
            ),
        ) as PlanVerificationResult.Rejected

        assertEquals(
            listOf(PlanViolationCode.UNKNOWN_DEPENDENCY),
            unknown.violations.map { violation -> violation.code },
        )
        assertEquals(
            listOf(PlanViolationCode.SELF_DEPENDENCY),
            self.violations.map { violation -> violation.code },
        )
        assertEquals(
            listOf(PlanViolationCode.DEPENDENCY_CYCLE, PlanViolationCode.DEPENDENCY_CYCLE),
            cycle.violations.map { violation -> violation.code },
        )
    }

    @Test
    fun `read only write sets and disallowed resource kinds are rejected`() {
        val reminder = AgentPlanResource(AgentPlanResourceKind.REMINDER, digest('d'))
        val invalidWrite = readStep(1, 'b').copy(writeSet = setOf(reminder))
        val invalidRead = readStep(2, 'c').copy(readSet = setOf(reminder))

        val rejected = verifier.verify(plan(listOf(invalidWrite, invalidRead)))
            as PlanVerificationResult.Rejected

        assertEquals(
            listOf(
                PlanViolationCode.READ_ONLY_WRITE_SET,
                PlanViolationCode.READ_RESOURCE_NOT_ALLOWED,
            ),
            rejected.violations.map { violation -> violation.code },
        )
    }

    @Test
    fun `expiry lifetime and step bounds are deterministic`() {
        val expired = verifier.verify(
            plan(listOf(readStep(1, 'b')), expiresAt = now),
        ) as PlanVerificationResult.Rejected
        val tooLong = verifier.verify(
            plan(listOf(readStep(1, 'b')), expiresAt = now + 5 * 60_000L + 1L),
        ) as PlanVerificationResult.Rejected
        val boundedVerifier = PlanVerifier(
            catalog = catalog,
            limits = PlanVerifierLimits(maxSteps = 1),
            clock = { now },
        )
        val tooMany = boundedVerifier.verify(
            plan(listOf(readStep(1, 'b'), readStep(2, 'c'))),
        ) as PlanVerificationResult.Rejected

        assertEquals(PlanViolationCode.EXPIRED, expired.violations.single().code)
        assertEquals(PlanViolationCode.LIFETIME_TOO_LONG, tooLong.violations.single().code)
        assertEquals(PlanViolationCode.TOO_MANY_STEPS, tooMany.violations.single().code)
    }

    private fun plan(
        steps: List<AgentPlanStep>,
        expiresAt: Long = now + 60_000L,
    ): AgentPlan = AgentPlan(
        id = requireNotNull(AgentPlanId.parse("00000000-0000-0000-0000-000000000001")),
        objectiveDigest = digest('a'),
        expiresAtEpochMillis = expiresAt,
        steps = steps,
    )

    private fun readStep(
        id: Int,
        argument: Char,
        dependsOn: Set<AgentPlanStepId> = emptySet(),
        reads: Set<AgentPlanResource> = emptySet(),
    ): AgentPlanStep = AgentPlanStep(
        id = stepId(id),
        toolName = readTool,
        argumentDigest = digest(argument),
        dependsOn = dependsOn,
        readSet = reads,
        declaredRisk = AgentPlanRisk.READ_ONLY,
    )

    private fun stepId(value: Int): AgentPlanStepId = AgentPlanStepId(value)

    private fun digest(character: Char): AgentPlanDigest = requireNotNull(
        AgentPlanDigest.parse(character.toString().repeat(64)),
    )

    private fun toolName(value: String): AgentPlanToolName = requireNotNull(
        AgentPlanToolName.parse(value),
    )
}
