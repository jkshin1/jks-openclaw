package com.personaledge.core.agent

import java.util.Locale

/**
 * Keeps deterministic, short operational requests from paying the thermal cost of a 1K decode.
 * This only selects a native generation ceiling; Tool parsing, confirmation and execution remain
 * on the same closed Kotlin path. Ambiguous or long-form prompts retain the full configured budget.
 */
object TurnOutputBudgetPolicy {
    const val CONCISE_REPLY_OUTPUT_TOKENS = 128
    const val SHORT_REPLY_OUTPUT_TOKENS = 256
    const val STRUCTURED_TOOL_OUTPUT_TOKENS = 384

    /** Closed signal that a trusted follow-up should retain the source request's answer depth. */
    fun requestsLongForm(prompt: String): Boolean {
        val normalized = prompt.trim().lowercase(Locale.ROOT)
        return EXPLICIT_LONG_FORM_REQUEST_TERMS.any(normalized::contains) ||
            LONG_FORM_TERMS.any(normalized::contains)
    }

    fun forPrompt(
        prompt: String,
        base: AgentLoopLimits = AgentLoopLimits(),
    ): AgentLoopLimits {
        val normalized = prompt.trim().lowercase(Locale.ROOT)
        val codePoints = normalized.codePointCount(0, normalized.length)
        val hasToolTerm = TOOL_TERMS.any(normalized::contains)
        val hasOperationalToolIntent = hasToolTerm &&
            OPERATIONAL_TOOL_INTENT_TERMS.any(normalized::contains)
        val hasExplicitLongFormRequest = EXPLICIT_LONG_FORM_REQUEST_TERMS.any(normalized::contains)
        val hasMultiConstraintReasoning = SELECTION_TERMS.any(normalized::contains) &&
            CONSTRAINT_TERMS.any(normalized::contains)
        val selected = when {
            normalized.isEmpty() || codePoints > MAX_STRUCTURED_PROMPT_CODE_POINTS ->
                base.maxOutputTokens
            // A terse final format can still require substantial private reasoning. Preserve the
            // full decode ceiling for candidate-by-criteria requests so thinking is not starved.
            hasMultiConstraintReasoning -> base.maxOutputTokens
            // Response length is an explicit user preference. This changes only the native decode
            // ceiling; step/tool/deadline/argument limits remain the caller-owned base values.
            hasExplicitLongFormRequest -> base.maxOutputTokens
            hasOperationalToolIntent -> STRUCTURED_TOOL_OUTPUT_TOKENS
            hasToolTerm -> STRUCTURED_TOOL_OUTPUT_TOKENS
            codePoints <= MAX_CONCISE_PROMPT_CODE_POINTS &&
                CONCISE_REPLY_TERMS.any(normalized::contains) -> CONCISE_REPLY_OUTPUT_TOKENS
            LONG_FORM_TERMS.any(normalized::contains) -> base.maxOutputTokens
            codePoints <= MAX_SHORT_PROMPT_CODE_POINTS &&
                SHORT_REPLY_TERMS.any(normalized::contains) -> SHORT_REPLY_OUTPUT_TOKENS
            else -> base.maxOutputTokens
        }
        return base.copy(maxOutputTokens = minOf(base.maxOutputTokens, selected))
    }

    private const val MAX_SHORT_PROMPT_CODE_POINTS = 80
    private const val MAX_CONCISE_PROMPT_CODE_POINTS = 200
    private const val MAX_STRUCTURED_PROMPT_CODE_POINTS = 200
    private val TOOL_TERMS = listOf(
        "일정", "캘린더", "알람", "리마인더", "미리 알", "완료", "미뤄", "취소",
        "경로", "길찾", "지도", "검색", "찾아", "날씨", "기온", "강수", "카카오", "알림",
        "기억해", "잊어", "calendar", "alarm", "reminder", "route", "search",
        "weather", "temperature", "remember",
    )
    private val OPERATIONAL_TOOL_INTENT_TERMS = listOf(
        "만들", "등록", "추가", "수정", "변경", "취소", "삭제", "설정", "맞춰",
        "조회", "찾아", "검색", "기억해", "잊어", "완료", "미뤄",
        "create", "add", "update", "change", "cancel", "delete", "set", "find",
        "search", "remember", "forget", "complete", "snooze",
    )
    private val SHORT_REPLY_TERMS = listOf(
        "안녕", "고마워", "됐어", "괜찮아", "예", "아니", "hello", "thanks",
    )
    private val CONCISE_REPLY_TERMS = listOf(
        "한 문장", "두 문장", "짧게", "간단히", "간결하게", "요약해서",
        "one sentence", "two sentences", "short sentence", "briefly", "concisely",
    )
    private val LONG_FORM_TERMS = listOf(
        "설명", "이유", "explain",
    )
    private val EXPLICIT_LONG_FORM_REQUEST_TERMS = listOf(
        "자세히", "상세히", "깊이 있게", "장문으로", "에세이로", "단계별로",
        "예시를 포함", "보고서 형식", "분석해", "비교해", "코드 작성",
        "deep dive", "in detail", "detailed answer", "long-form", "step by step",
        "with examples", "as a report", "compare in detail",
    )
    private val SELECTION_TERMS = listOf(
        "후보", "선택", "비교", "골라", "추천", "candidate", "choose", "select", "compare",
    )
    private val CONSTRAINT_TERMS = listOf(
        "조건", "기준", "요건", "제약", "만족", "이하", "이상", "미만", "초과",
        "constraint", "criteria", "requirement", "at least", "at most",
    )
}
