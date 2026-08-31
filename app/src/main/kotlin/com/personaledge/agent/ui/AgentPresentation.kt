package com.personaledge.agent.ui

import com.personaledge.agent.ModelUiStatus
import com.personaledge.agent.ThermalTurnPolicy
import com.personaledge.core.diagnostics.DiagnosticThermalStatus

/**
 * What the redesigned screen says about state, decided in plain Kotlin so it can be unit tested
 * without a device.
 *
 * The old screen showed every card at once and left the reading to the user. This layer instead
 * answers three questions — what is the agent's state, what is the single next setup step, and why
 * the composer is blocked — and the composables render those answers. Keeping it here means a
 * wrong label is a failing JVM test rather than something noticed in a screenshot.
 */
internal enum class StatusTone {
    /** Steady state, nothing to act on. */
    NEUTRAL,

    /** Work in progress. */
    ACCENT,

    /** Ready, gate open. */
    POSITIVE,

    /** Usable, but degraded or awaiting a decision. */
    CAUTION,

    /** Blocked; inference cannot start. */
    CRITICAL,
}

internal data class StatusChip(
    val label: String,
    val tone: StatusTone,
)

internal fun modelStatusChip(status: ModelUiStatus): StatusChip = when (status) {
    ModelUiStatus.CHECKING -> StatusChip("모델 확인 중", StatusTone.ACCENT)
    ModelUiStatus.MISSING -> StatusChip("모델 없음", StatusTone.CAUTION)
    ModelUiStatus.REJECTED -> StatusChip("검증 실패", StatusTone.CRITICAL)
    ModelUiStatus.IMPORTING -> StatusChip("가져오는 중", StatusTone.ACCENT)
    ModelUiStatus.VERIFIED -> StatusChip("검증됨 · 로드 대기", StatusTone.CAUTION)
    ModelUiStatus.INITIALIZING -> StatusChip("로드 중", StatusTone.ACCENT)
    ModelUiStatus.READY -> StatusChip("준비됨", StatusTone.POSITIVE)
    ModelUiStatus.ERROR -> StatusChip("오류", StatusTone.CRITICAL)
}

/** Short enough for the app bar; [thermalDetail] keeps the sentence for the settings sheet. */
internal fun thermalShortLabel(status: DiagnosticThermalStatus): String = when (status) {
    DiagnosticThermalStatus.NONE -> "열 상태 정상"
    DiagnosticThermalStatus.LIGHT -> "열 상태 LIGHT"
    DiagnosticThermalStatus.MODERATE -> "열 상태 MODERATE"
    DiagnosticThermalStatus.SEVERE -> "열 상태 SEVERE"
    DiagnosticThermalStatus.CRITICAL -> "열 상태 CRITICAL"
    DiagnosticThermalStatus.EMERGENCY -> "열 상태 EMERGENCY"
    DiagnosticThermalStatus.SHUTDOWN -> "열 상태 SHUTDOWN"
    DiagnosticThermalStatus.UNKNOWN -> "열 상태 확인 불가"
}

internal fun thermalDetail(status: DiagnosticThermalStatus): String = when (status) {
    DiagnosticThermalStatus.NONE -> "열 상태 NONE · 추론 가능"
    DiagnosticThermalStatus.LIGHT -> "열 상태 LIGHT · 추론 계속"
    DiagnosticThermalStatus.MODERATE -> "열 상태 MODERATE · 요청에 배정된 토큰 상한을 유지하며 추론 계속"
    DiagnosticThermalStatus.SEVERE -> "열 상태 SEVERE · 요청에 배정된 토큰 상한을 유지하며 추론 계속"
    DiagnosticThermalStatus.CRITICAL -> "열 상태 CRITICAL · 새 요청 차단 및 진행 요청 취소"
    DiagnosticThermalStatus.EMERGENCY -> "열 상태 EMERGENCY · 추론 즉시 중단"
    DiagnosticThermalStatus.SHUTDOWN -> "열 상태 SHUTDOWN · 추론 즉시 중단"
    DiagnosticThermalStatus.UNKNOWN -> "열 상태 확인 불가 · 안전을 위해 추론 차단"
}

internal fun thermalTone(status: DiagnosticThermalStatus): StatusTone = when {
    !ThermalTurnPolicy.canStart(status) -> StatusTone.CRITICAL
    status == DiagnosticThermalStatus.MODERATE || status == DiagnosticThermalStatus.SEVERE ->
        StatusTone.CAUTION
    else -> StatusTone.NEUTRAL
}

/**
 * NONE and LIGHT are the normal case and stay out of the way. Warmer states remain visible as a
 * caution even while MODERATE and SEVERE keep running; blocked states use the critical tone.
 */
internal fun thermalBanner(status: DiagnosticThermalStatus): StatusChip? = when (status) {
    DiagnosticThermalStatus.NONE, DiagnosticThermalStatus.LIGHT -> null
    else -> StatusChip(thermalDetail(status), thermalTone(status))
}

internal enum class SetupStep {
    IMPORT_MODEL,
    RETRY_MODEL,
    LOAD_MODEL,
    GRANT_CALENDAR,
    PIN_CALENDAR,
}

internal data class SetupNudge(
    val step: SetupStep,
    val title: String,
    val detail: String,
    val actionLabel: String,
)

/**
 * The one next step, never a checklist.
 *
 * Deterministic assistant surfaces come first. Calendar and reminder UI remain useful when the
 * model is missing, unloaded, hot, or failed; the model is only their optional natural-language
 * interface. Transient model states return null once the deterministic setup is complete.
 */
internal fun setupNudge(
    modelStatus: ModelUiStatus,
    calendarPermissionGranted: Boolean,
    calendarPinned: Boolean,
): SetupNudge? = when {
    !calendarPermissionGranted -> SetupNudge(
        step = SetupStep.GRANT_CALENDAR,
        title = "캘린더 권한이 필요합니다",
        detail = "오늘 일정과 충돌을 모델 없이 보려면 권한을 허용하세요. 리마인더는 권한 없이도 동작합니다.",
        actionLabel = "허용",
    )
    !calendarPinned -> SetupNudge(
        step = SetupStep.PIN_CALENDAR,
        title = "캘린더 범위를 선택하세요",
        detail = "쓸 캘린더 하나와 조회할 캘린더를 직접 선택합니다. 앱이 자동 선택하지 않습니다.",
        actionLabel = "선택",
    )
    modelStatus == ModelUiStatus.MISSING -> SetupNudge(
        step = SetupStep.IMPORT_MODEL,
        title = "모델을 가져오세요",
        detail = "고정된 Gemma 파일을 선택하면 SHA-256을 검증한 뒤 앱 전용 저장소로 복사합니다.",
        actionLabel = "가져오기",
    )
    modelStatus == ModelUiStatus.REJECTED || modelStatus == ModelUiStatus.ERROR -> SetupNudge(
        step = SetupStep.RETRY_MODEL,
        title = "모델을 사용할 수 없습니다",
        detail = "설정에서 상태를 확인하고 다시 검사하거나 파일을 다시 가져오세요.",
        actionLabel = "설정 열기",
    )
    modelStatus == ModelUiStatus.VERIFIED -> SetupNudge(
        step = SetupStep.LOAD_MODEL,
        title = "모델을 불러오세요",
        detail = "GPU로 먼저 시도합니다. 실패하면 설정에서 CPU로 다시 불러올 수 있습니다.",
        actionLabel = "GPU로 불러오기",
    )
    else -> null
}

/** Why the composer will not send, phrased as the thing to do about it. */
internal data class ComposerBlock(
    val message: String,
    val opensSettings: Boolean,
)

internal fun composerBlock(
    modelStatus: ModelUiStatus,
    thermalStatus: DiagnosticThermalStatus,
    turnActive: Boolean,
    inputWarning: String? = null,
): ComposerBlock? = when {
    inputWarning != null -> ComposerBlock(
        message = inputWarning,
        opensSettings = false,
    )
    turnActive -> ComposerBlock(
        message = "답변을 생성하는 중입니다. 취소하면 즉시 중단합니다.",
        opensSettings = false,
    )
    // Thermal outranks the model: a blocked device cannot start a turn even when everything is
    // loaded, and telling the user to press "불러오기" there would be a dead end.
    !ThermalTurnPolicy.canStart(thermalStatus) -> ComposerBlock(
        message = "${thermalShortLabel(thermalStatus)} · 기기가 식을 때까지 새 요청을 시작할 수 없습니다.",
        opensSettings = false,
    )
    modelStatus != ModelUiStatus.READY -> ComposerBlock(
        message = "온디바이스 모델을 준비해야 요청할 수 있습니다.",
        opensSettings = true,
    )
    else -> null
}

internal enum class SuggestionKind {
    CALENDAR,
    ALARM,
    ROUTE,
    SEARCH,
}

internal data class PromptSuggestion(
    val kind: SuggestionKind,
    val label: String,
    val prompt: String,
)

/**
 * Starter prompts for the empty transcript, filtered to what is actually wired up right now.
 *
 * Suggesting a route lookup while the network consent is off would spend a whole turn to reach a
 * refusal, so a capability the interlock would stop is not offered. The alarm read needs no setup
 * and is always available, which keeps the list from ever being empty.
 */
internal fun promptSuggestions(
    calendarReady: Boolean,
    routeLookupEnabled: Boolean,
    webSearchEnabled: Boolean,
): List<PromptSuggestion> = buildList {
    if (calendarReady) {
        add(
            PromptSuggestion(
                kind = SuggestionKind.CALENDAR,
                label = "일정 등록",
                prompt = "내일 오후 3시에 치과 일정 넣어줘",
            ),
        )
        add(
            PromptSuggestion(
                kind = SuggestionKind.CALENDAR,
                label = "일정 확인",
                prompt = "이번 주 일정 알려줘",
            ),
        )
    }
    add(
        PromptSuggestion(
            kind = SuggestionKind.ALARM,
            label = "다음 알람",
            prompt = "다음 알람 언제야?",
        ),
    )
    if (routeLookupEnabled) {
        add(
            PromptSuggestion(
                kind = SuggestionKind.ROUTE,
                label = "경로 조회",
                prompt = "서울특별시 중구 세종대로 110에서 서울특별시 강남구 테헤란로 152까지 얼마나 걸려?",
            ),
        )
    }
    if (webSearchEnabled) {
        add(
            PromptSuggestion(
                kind = SuggestionKind.SEARCH,
                label = "웹 검색",
                prompt = "오늘 서울 날씨 검색해줘",
            ),
        )
    }
}
