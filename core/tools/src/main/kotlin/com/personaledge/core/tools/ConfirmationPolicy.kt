package com.personaledge.core.tools

sealed interface ConfirmationRequirement {
    data object NotRequired : ConfirmationRequirement

    data object UserConfirmation : ConfirmationRequirement

    data object StrongAuthentication : ConfirmationRequirement
}

class ConfirmationPolicy {
    fun evaluate(
        risk: ToolRisk,
        minimum: ConfirmationRequirement = ConfirmationRequirement.NotRequired,
    ): ConfirmationRequirement = strongest(riskRequirement(risk), minimum)

    private fun riskRequirement(risk: ToolRisk): ConfirmationRequirement = when (risk) {
        ToolRisk.READ_ONLY -> ConfirmationRequirement.NotRequired
        ToolRisk.LOCAL_WRITE -> ConfirmationRequirement.UserConfirmation
        ToolRisk.DATA_WRITE -> ConfirmationRequirement.UserConfirmation
        ToolRisk.COMMUNICATION -> ConfirmationRequirement.UserConfirmation
        ToolRisk.VEHICLE_CONTROL -> ConfirmationRequirement.UserConfirmation
        ToolRisk.HIGH_RISK -> ConfirmationRequirement.StrongAuthentication
    }

    private fun strongest(
        first: ConfirmationRequirement,
        second: ConfirmationRequirement,
    ): ConfirmationRequirement = if (rank(first) >= rank(second)) first else second

    private fun rank(requirement: ConfirmationRequirement): Int = when (requirement) {
        ConfirmationRequirement.NotRequired -> 0
        ConfirmationRequirement.UserConfirmation -> 1
        ConfirmationRequirement.StrongAuthentication -> 2
    }
}
