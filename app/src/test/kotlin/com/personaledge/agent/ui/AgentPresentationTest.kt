package com.personaledge.agent.ui

import com.personaledge.agent.ModelUiStatus
import com.personaledge.core.diagnostics.DiagnosticThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPresentationTest {

    @Test
    fun `calendar setup is offered before an optional missing model`() {
        val nudge = setupNudge(
            modelStatus = ModelUiStatus.MISSING,
            calendarPermissionGranted = false,
            calendarPinned = false,
        )

        assertEquals(SetupStep.GRANT_CALENDAR, nudge?.step)
    }

    @Test
    fun `a verified model still offers deterministic calendar setup first`() {
        val nudge = setupNudge(
            modelStatus = ModelUiStatus.VERIFIED,
            calendarPermissionGranted = false,
            calendarPinned = false,
        )

        assertEquals(SetupStep.GRANT_CALENDAR, nudge?.step)
    }

    @Test
    fun `model setup appears after deterministic calendar setup is complete`() {
        assertEquals(
            SetupStep.IMPORT_MODEL,
            setupNudge(ModelUiStatus.MISSING, true, true)?.step,
        )
        assertEquals(
            SetupStep.LOAD_MODEL,
            setupNudge(ModelUiStatus.VERIFIED, true, true)?.step,
        )
    }

    @Test
    fun `a rejected or failed model routes to settings rather than a blind retry`() {
        listOf(ModelUiStatus.REJECTED, ModelUiStatus.ERROR).forEach { status ->
            assertEquals(
                "status $status",
                SetupStep.RETRY_MODEL,
                setupNudge(status, calendarPermissionGranted = true, calendarPinned = true)?.step,
            )
        }
    }

    @Test
    fun `calendar steps are independent from model readiness`() {
        assertEquals(
            SetupStep.GRANT_CALENDAR,
            setupNudge(ModelUiStatus.MISSING, calendarPermissionGranted = false, calendarPinned = false)?.step,
        )
        assertEquals(
            SetupStep.PIN_CALENDAR,
            setupNudge(ModelUiStatus.ERROR, calendarPermissionGranted = true, calendarPinned = false)?.step,
        )
    }

    @Test
    fun `a finished setup shows no banner`() {
        assertNull(
            setupNudge(ModelUiStatus.READY, calendarPermissionGranted = true, calendarPinned = true),
        )
    }

    @Test
    fun `transient model states do not flash a model banner after deterministic setup`() {
        listOf(
            ModelUiStatus.CHECKING,
            ModelUiStatus.IMPORTING,
            ModelUiStatus.INITIALIZING,
        ).forEach { status ->
            assertNull(
                "status $status",
                setupNudge(status, calendarPermissionGranted = true, calendarPinned = true),
            )
        }
    }

    @Test
    fun `a running turn explains the stop button instead of the missing model`() {
        val block = composerBlock(
            modelStatus = ModelUiStatus.READY,
            thermalStatus = DiagnosticThermalStatus.NONE,
            turnActive = true,
        )

        assertNotNull(block)
        assertEquals(false, block?.opensSettings)
    }

    @Test
    fun `thermal blocking outranks the model state`() {
        val block = composerBlock(
            modelStatus = ModelUiStatus.READY,
            thermalStatus = DiagnosticThermalStatus.CRITICAL,
            turnActive = false,
        )

        assertNotNull(block)
        // Sending the user to settings would be a dead end: nothing there cools the device.
        assertEquals(false, block?.opensSettings)
        assertTrue(block!!.message.contains("CRITICAL"))
    }

    @Test
    fun `an unloaded model offers the way to settings`() {
        val block = composerBlock(
            modelStatus = ModelUiStatus.VERIFIED,
            thermalStatus = DiagnosticThermalStatus.NONE,
            turnActive = false,
        )

        assertEquals(true, block?.opensSettings)
    }

    @Test
    fun `a ready device blocks nothing`() {
        assertNull(
            composerBlock(
                modelStatus = ModelUiStatus.READY,
                thermalStatus = DiagnosticThermalStatus.LIGHT,
                turnActive = false,
            ),
        )
    }

    @Test
    fun `a rejected input edit is explained without routing to settings`() {
        val warning = "이번 입력은 반영하지 않았습니다."

        val block = composerBlock(
            modelStatus = ModelUiStatus.VERIFIED,
            thermalStatus = DiagnosticThermalStatus.CRITICAL,
            turnActive = false,
            inputWarning = warning,
        )

        assertEquals(warning, block?.message)
        assertEquals(false, block?.opensSettings)
    }

    @Test
    fun `only a thermal state worth acting on gets a banner`() {
        assertNull(thermalBanner(DiagnosticThermalStatus.NONE))
        assertNull(thermalBanner(DiagnosticThermalStatus.LIGHT))
        assertEquals(StatusTone.CAUTION, thermalBanner(DiagnosticThermalStatus.MODERATE)?.tone)
        assertEquals(StatusTone.CRITICAL, thermalBanner(DiagnosticThermalStatus.CRITICAL)?.tone)
        assertEquals(StatusTone.CRITICAL, thermalBanner(DiagnosticThermalStatus.UNKNOWN)?.tone)
    }

    @Test
    fun `model chips separate a usable state from a blocked one`() {
        assertEquals(StatusTone.POSITIVE, modelStatusChip(ModelUiStatus.READY).tone)
        assertEquals(StatusTone.CRITICAL, modelStatusChip(ModelUiStatus.REJECTED).tone)
        assertEquals(StatusTone.CRITICAL, modelStatusChip(ModelUiStatus.ERROR).tone)
        assertEquals(StatusTone.CAUTION, modelStatusChip(ModelUiStatus.VERIFIED).tone)
    }

    @Test
    fun `every model state has a label`() {
        ModelUiStatus.entries.forEach { status ->
            assertTrue("status $status", modelStatusChip(status).label.isNotBlank())
        }
    }

    @Test
    fun `every thermal state has both a short label and a sentence`() {
        DiagnosticThermalStatus.entries.forEach { status ->
            assertTrue("status $status", thermalShortLabel(status).isNotBlank())
            assertTrue("status $status", thermalDetail(status).isNotBlank())
        }
    }

    @Test
    fun `suggestions never propose a capability the interlock would refuse`() {
        val suggestions = promptSuggestions(
            calendarReady = false,
            routeLookupEnabled = false,
            webSearchEnabled = false,
        )

        assertEquals(listOf(SuggestionKind.ALARM), suggestions.map(PromptSuggestion::kind))
    }

    @Test
    fun `enabled capabilities each contribute a suggestion`() {
        val suggestions = promptSuggestions(
            calendarReady = true,
            routeLookupEnabled = true,
            webSearchEnabled = true,
        )
        val kinds = suggestions.map(PromptSuggestion::kind)

        assertTrue(kinds.contains(SuggestionKind.CALENDAR))
        assertTrue(kinds.contains(SuggestionKind.ALARM))
        assertTrue(kinds.contains(SuggestionKind.ROUTE))
        assertTrue(kinds.contains(SuggestionKind.SEARCH))
        assertTrue(suggestions.all { it.prompt.isNotBlank() && it.label.isNotBlank() })
    }

    @Test
    fun `a route suggestion carries a full road address, matching what the tool accepts`() {
        val route = promptSuggestions(
            calendarReady = false,
            routeLookupEnabled = true,
            webSearchEnabled = false,
        ).single { it.kind == SuggestionKind.ROUTE }

        // English-formatted addresses were the pair that came back PLACE_NOT_FOUND on device, so
        // the offered example stays in the form the provider actually resolved.
        assertTrue(route.prompt.contains("서울특별시"))
    }
}
