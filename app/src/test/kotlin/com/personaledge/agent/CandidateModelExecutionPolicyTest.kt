package com.personaledge.agent

import com.personaledge.core.tools.ToolRisk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateModelExecutionPolicyTest {
    @Test
    fun `candidate lab allows only read-only tool risk`() {
        assertNull(
            SideEffectingToolPolicy.blockReason(
                sideEffectingToolsEnabled = false,
                risk = ToolRisk.READ_ONLY,
            ),
        )

        ToolRisk.entries.filterNot { it == ToolRisk.READ_ONLY }.forEach { risk ->
            assertNotNull(
                risk.name,
                SideEffectingToolPolicy.blockReason(
                    sideEffectingToolsEnabled = false,
                    risk = risk,
                ),
            )
        }
    }

    @Test
    fun `production variants retain the existing risk policy`() {
        ToolRisk.entries.forEach { risk ->
            assertNull(
                risk.name,
                SideEffectingToolPolicy.blockReason(
                    sideEffectingToolsEnabled = true,
                    risk = risk,
                ),
            )
        }
    }

    @Test
    fun `candidate lab disables provider integrations`() {
        assertFalse(CandidateProcessPolicy.providerIntegrationsEnabled(candidateModelLab = true))
        assertTrue(CandidateProcessPolicy.providerIntegrationsEnabled(candidateModelLab = false))
    }
}
