package com.personaledge.agent

import com.personaledge.core.agent.TurnMediaPolicy
import com.personaledge.core.diagnostics.DiagnosticThermalStatus
import com.personaledge.core.llm.TurnMediaKind

/** Why the composer is not offering attachments right now. */
internal enum class MediaUnavailableReason {
    /** The owner has not turned multimodal input on. */
    DISABLED,

    /** The pinned artifact declares no such modality. */
    UNSUPPORTED_BY_MODEL,

    /** The model is still being verified, imported, or initialized. */
    MODEL_NOT_READY,

    /**
     * Media was enabled after the runtime started, so the engine holds no encoder for it.
     *
     * The engine loads its vision and audio executors at initialization, exactly like its
     * backend choice, and this app initializes once per process.
     */
    RUNTIME_RELOAD_REQUIRED,

    /** A turn or a recording is already running. */
    BUSY,

    /** One attachment per turn; the composer already holds one. */
    ALREADY_ATTACHED,

    /** The thermal policy is refusing new model work. */
    THERMAL,
}

/**
 * When the composer may offer, keep, and send an attachment.
 *
 * Pure so the whole availability matrix — setting, declared modality, model state, busy state,
 * existing attachment, thermal boundary — can be exercised on the host. The ViewModel only asks
 * and reports; it makes none of these decisions itself.
 */
internal object MediaComposerPolicy {
    /**
     * Returns null when [kind] may be attached now, otherwise the first reason it may not.
     *
     * Order matters: the owner should hear the durable, fixable reason ("설정에서 켜세요") before a
     * transient one ("지금은 응답 중입니다").
     */
    fun unavailableReason(
        kind: TurnMediaKind,
        enabled: Boolean,
        modelSupportsKind: Boolean,
        modelStatus: ModelUiStatus,
        runtimeLoadedKind: Boolean,
        turnActive: Boolean,
        recording: Boolean,
        hasAttachment: Boolean,
        thermalStatus: DiagnosticThermalStatus,
    ): MediaUnavailableReason? = when {
        !enabled -> MediaUnavailableReason.DISABLED
        !modelSupportsKind -> MediaUnavailableReason.UNSUPPORTED_BY_MODEL
        // Media has no deterministic non-model path: an image or a clip is only ever understood by
        // the model, so unlike a text turn there is nothing to fall back to while it loads.
        modelStatus != ModelUiStatus.READY -> MediaUnavailableReason.MODEL_NOT_READY
        !runtimeLoadedKind -> MediaUnavailableReason.RUNTIME_RELOAD_REQUIRED
        turnActive || recording -> MediaUnavailableReason.BUSY
        hasAttachment -> MediaUnavailableReason.ALREADY_ATTACHED
        !ThermalTurnPolicy.canStart(thermalStatus) -> MediaUnavailableReason.THERMAL
        else -> null
    }

    fun message(reason: MediaUnavailableReason): String = when (reason) {
        MediaUnavailableReason.DISABLED ->
            "사진과 음성 입력은 설정에서 켠 뒤에 사용할 수 있습니다."

        MediaUnavailableReason.UNSUPPORTED_BY_MODEL ->
            "설치된 모델이 이 입력 형식을 지원하지 않습니다."

        MediaUnavailableReason.MODEL_NOT_READY ->
            "모델이 준비된 뒤에 사진과 음성을 보낼 수 있습니다."

        MediaUnavailableReason.RUNTIME_RELOAD_REQUIRED ->
            "사진·음성 입력을 켠 설정은 앱을 다시 시작한 뒤부터 적용됩니다."

        MediaUnavailableReason.BUSY ->
            "지금은 다른 요청을 처리하고 있습니다."

        MediaUnavailableReason.ALREADY_ATTACHED ->
            "첨부는 한 번에 하나만 보낼 수 있습니다. 기존 첨부를 지운 뒤 다시 선택해 주세요."

        MediaUnavailableReason.THERMAL ->
            "기기 열 상태 때문에 새 요청을 시작하지 않았습니다."
    }

    /**
     * True when a turn carrying [attachment] may be sent.
     *
     * The typed line may be empty — a photo on its own is a complete request — but it must fit the
     * shorter media budget, because the app-authored template also has to fit the turn envelope.
     */
    fun canSendWithAttachment(
        attachment: PendingMediaAttachment?,
        prompt: String,
        modelStatus: ModelUiStatus,
        turnActive: Boolean,
        recording: Boolean,
        thermalStatus: DiagnosticThermalStatus,
    ): Boolean {
        if (attachment == null) return false
        if (modelStatus != ModelUiStatus.READY) return false
        if (turnActive || recording) return false
        if (!ThermalTurnPolicy.canStart(thermalStatus)) return false
        return TurnMediaPolicy.isAcceptedOwnerText(prompt.trim())
    }

    /** The app-authored notice for a capture that produced nothing usable. */
    fun rejectionMessage(kind: TurnMediaKind): String = when (kind) {
        TurnMediaKind.IMAGE ->
            "이 사진은 사용할 수 없습니다. 다른 사진을 선택하거나 다시 촬영해 주세요."

        TurnMediaKind.AUDIO ->
            "녹음을 사용할 수 없습니다. 다시 시도해 주세요."
    }

    const val RECORDING_TOO_SHORT_MESSAGE: String =
        "녹음이 너무 짧습니다. 조금 더 길게 말한 뒤 멈춰 주세요."

    const val RECORDING_SILENT_MESSAGE: String =
        "녹음에서 목소리를 찾지 못했습니다. 마이크에 가까이서 다시 말해 주세요."

    const val RECORDING_UNAVAILABLE_MESSAGE: String =
        "마이크를 사용할 수 없습니다. 권한과 다른 앱의 마이크 사용을 확인해 주세요."

    const val MICROPHONE_PERMISSION_MESSAGE: String =
        "음성 입력을 사용하려면 마이크 권한이 필요합니다."

    const val TRANSCRIPTION_EMPTY_MESSAGE: String =
        "받아쓸 내용을 찾지 못했습니다."

    const val TRANSCRIPTION_TOO_LONG_MESSAGE: String =
        "받아쓴 내용이 입력 가능한 길이를 넘어 일부만 넣었습니다."
}
