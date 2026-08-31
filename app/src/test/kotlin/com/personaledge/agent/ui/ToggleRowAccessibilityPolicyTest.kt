package com.personaledge.agent.ui

import com.personaledge.agent.ui.components.ToggleRowAccessibilityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToggleRowAccessibilityPolicyTest {
    @Test
    fun oneRowActionOwnsTheNextValueAndSpokenState() {
        assertTrue(ToggleRowAccessibilityPolicy.nextValue(checked = false))
        assertFalse(ToggleRowAccessibilityPolicy.nextValue(checked = true))
        assertEquals(
            ToggleRowAccessibilityPolicy.ON_STATE_DESCRIPTION,
            ToggleRowAccessibilityPolicy.stateDescription(checked = true),
        )
        assertEquals(
            ToggleRowAccessibilityPolicy.OFF_STATE_DESCRIPTION,
            ToggleRowAccessibilityPolicy.stateDescription(checked = false),
        )
        assertNotEquals(
            ToggleRowAccessibilityPolicy.ON_STATE_DESCRIPTION,
            ToggleRowAccessibilityPolicy.OFF_STATE_DESCRIPTION,
        )
    }
}
