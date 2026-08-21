package com.personaledge.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationPolicyTest {
    private val policy = ConfirmationPolicy()

    @Test
    fun `read-only tool does not require confirmation`() {
        assertEquals(
            ConfirmationRequirement.NotRequired,
            policy.evaluate(ToolRisk.READ_ONLY),
        )
    }

    @Test
    fun `communication and vehicle control require confirmation`() {
        assertEquals(
            ConfirmationRequirement.UserConfirmation,
            policy.evaluate(ToolRisk.COMMUNICATION),
        )
        assertEquals(
            ConfirmationRequirement.UserConfirmation,
            policy.evaluate(ToolRisk.VEHICLE_CONTROL),
        )
    }

    @Test
    fun `high risk requires strong authentication`() {
        assertEquals(
            ConfirmationRequirement.StrongAuthentication,
            policy.evaluate(ToolRisk.HIGH_RISK),
        )
    }

    @Test
    fun `local writes require confirmation by default and can be explicitly relaxed`() {
        assertEquals(
            ConfirmationRequirement.UserConfirmation,
            policy.evaluate(ToolRisk.LOCAL_WRITE),
        )

        val relaxedPolicy = ConfirmationPolicy(ConfirmationSettings(confirmLocalWrites = false))
        assertEquals(
            ConfirmationRequirement.NotRequired,
            relaxedPolicy.evaluate(ToolRisk.LOCAL_WRITE),
        )
    }

    @Test
    fun `tool minimum can only strengthen the risk requirement`() {
        assertEquals(
            ConfirmationRequirement.UserConfirmation,
            policy.evaluate(
                risk = ToolRisk.READ_ONLY,
                minimum = ConfirmationRequirement.UserConfirmation,
            ),
        )
        assertEquals(
            ConfirmationRequirement.StrongAuthentication,
            policy.evaluate(
                risk = ToolRisk.HIGH_RISK,
                minimum = ConfirmationRequirement.NotRequired,
            ),
        )
    }
}
