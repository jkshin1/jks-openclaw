package com.personaledge.agent

import com.personaledge.core.agent.TurnMediaPolicy
import com.personaledge.core.diagnostics.DiagnosticThermalStatus
import com.personaledge.core.llm.TurnMediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaComposerPolicyTest {
    @Test
    fun `an enabled ready idle composer offers both modalities`() {
        assertNull(reason(TurnMediaKind.IMAGE))
        assertNull(reason(TurnMediaKind.AUDIO))
    }

    @Test
    fun `the durable setting is reported before any transient reason`() {
        // The owner should hear the reason they can act on, not "지금은 응답 중입니다".
        assertEquals(
            MediaUnavailableReason.DISABLED,
            reason(
                TurnMediaKind.IMAGE,
                enabled = false,
                turnActive = true,
                thermalStatus = DiagnosticThermalStatus.CRITICAL,
            ),
        )
    }

    @Test
    fun `a modality the artifact does not declare is refused`() {
        assertEquals(
            MediaUnavailableReason.UNSUPPORTED_BY_MODEL,
            reason(TurnMediaKind.AUDIO, modelSupportsKind = false),
        )
    }

    @Test
    fun `media waits for the model rather than falling back`() {
        // Unlike a text turn there is no deterministic non-model path for an image or a clip.
        for (status in listOf(
            ModelUiStatus.CHECKING,
            ModelUiStatus.MISSING,
            ModelUiStatus.IMPORTING,
            ModelUiStatus.INITIALIZING,
            ModelUiStatus.ERROR,
        )) {
            assertEquals(
                "$status must not offer media.",
                MediaUnavailableReason.MODEL_NOT_READY,
                reason(TurnMediaKind.IMAGE, modelStatus = status),
            )
        }
    }

    @Test
    fun `enabling media after the runtime started asks for a restart`() {
        // The engine loads its encoders at initialization, so the setting alone is not enough.
        assertEquals(
            MediaUnavailableReason.RUNTIME_RELOAD_REQUIRED,
            reason(TurnMediaKind.IMAGE, runtimeLoadedKind = false),
        )
        // A durable or model-level reason still outranks it.
        assertEquals(
            MediaUnavailableReason.DISABLED,
            reason(TurnMediaKind.IMAGE, enabled = false, runtimeLoadedKind = false),
        )
        assertEquals(
            MediaUnavailableReason.MODEL_NOT_READY,
            reason(
                TurnMediaKind.IMAGE,
                modelStatus = ModelUiStatus.INITIALIZING,
                runtimeLoadedKind = false,
            ),
        )
    }

    @Test
    fun `a running turn or recording refuses a new attachment`() {
        assertEquals(
            MediaUnavailableReason.BUSY,
            reason(TurnMediaKind.IMAGE, turnActive = true),
        )
        assertEquals(
            MediaUnavailableReason.BUSY,
            reason(TurnMediaKind.IMAGE, recording = true),
        )
    }

    @Test
    fun `only one attachment may be staged`() {
        assertEquals(
            MediaUnavailableReason.ALREADY_ATTACHED,
            reason(TurnMediaKind.IMAGE, hasAttachment = true),
        )
    }

    @Test
    fun `the thermal boundary is the same one text turns use`() {
        assertEquals(
            MediaUnavailableReason.THERMAL,
            reason(TurnMediaKind.IMAGE, thermalStatus = DiagnosticThermalStatus.CRITICAL),
        )
        assertEquals(
            MediaUnavailableReason.THERMAL,
            reason(TurnMediaKind.IMAGE, thermalStatus = DiagnosticThermalStatus.UNKNOWN),
        )
        // Media adds no extra restriction below CRITICAL; SEVERE still starts, as for text.
        assertNull(reason(TurnMediaKind.IMAGE, thermalStatus = DiagnosticThermalStatus.SEVERE))
        assertNull(reason(TurnMediaKind.IMAGE, thermalStatus = DiagnosticThermalStatus.MODERATE))
    }

    @Test
    fun `every reason has an owner-facing message`() {
        for (value in MediaUnavailableReason.entries) {
            assertTrue(
                "$value has no message.",
                MediaComposerPolicy.message(value).isNotBlank(),
            )
        }
    }

    @Test
    fun `an attachment alone is a complete request`() {
        assertTrue(canSend(prompt = ""))
        assertTrue(canSend(prompt = "이거 뭐야?"))
    }

    @Test
    fun `a typed line over the media budget blocks the send`() {
        val tooLong = "가".repeat(TurnMediaPolicy.MAX_OWNER_TEXT_BYTES / 3 + 1)

        assertFalse(canSend(prompt = tooLong))
    }

    @Test
    fun `no attachment means this path never allows a send`() {
        assertFalse(
            MediaComposerPolicy.canSendWithAttachment(
                attachment = null,
                prompt = "이거 뭐야?",
                modelStatus = ModelUiStatus.READY,
                turnActive = false,
                recording = false,
                thermalStatus = DiagnosticThermalStatus.NONE,
            ),
        )
    }

    @Test
    fun `an attachment cannot be sent while busy or too hot`() {
        assertFalse(canSend(turnActive = true))
        assertFalse(canSend(recording = true))
        assertFalse(canSend(modelStatus = ModelUiStatus.INITIALIZING))
        assertFalse(canSend(thermalStatus = DiagnosticThermalStatus.CRITICAL))
    }

    private fun reason(
        kind: TurnMediaKind,
        enabled: Boolean = true,
        modelSupportsKind: Boolean = true,
        modelStatus: ModelUiStatus = ModelUiStatus.READY,
        runtimeLoadedKind: Boolean = true,
        turnActive: Boolean = false,
        recording: Boolean = false,
        hasAttachment: Boolean = false,
        thermalStatus: DiagnosticThermalStatus = DiagnosticThermalStatus.NONE,
    ): MediaUnavailableReason? = MediaComposerPolicy.unavailableReason(
        kind = kind,
        enabled = enabled,
        modelSupportsKind = modelSupportsKind,
        modelStatus = modelStatus,
        runtimeLoadedKind = runtimeLoadedKind,
        turnActive = turnActive,
        recording = recording,
        hasAttachment = hasAttachment,
        thermalStatus = thermalStatus,
    )

    private fun canSend(
        prompt: String = "",
        modelStatus: ModelUiStatus = ModelUiStatus.READY,
        turnActive: Boolean = false,
        recording: Boolean = false,
        thermalStatus: DiagnosticThermalStatus = DiagnosticThermalStatus.NONE,
    ): Boolean = MediaComposerPolicy.canSendWithAttachment(
        attachment = PendingMediaAttachment(
            id = "attachment-1",
            kind = TurnMediaKind.IMAGE,
            source = MediaAttachmentSource.CAMERA,
            byteCount = 120_000,
        ),
        prompt = prompt,
        modelStatus = modelStatus,
        turnActive = turnActive,
        recording = recording,
        thermalStatus = thermalStatus,
    )
}
