package com.personaledge.agent

import com.personaledge.core.tools.RouteEstimateTool

internal sealed interface SettingsTextValidation {
    data class Valid(val value: String) : SettingsTextValidation
    data class Invalid(val reason: String) : SettingsTextValidation
}

/** Shared validation for privacy-sensitive settings and untrusted provider labels. */
internal object SettingsTextPolicy {
    fun validateDefaultOrigin(raw: String): SettingsTextValidation {
        val value = raw.trim()
        return when {
            value.isEmpty() -> SettingsTextValidation.Invalid("기본 출발지를 입력하세요.")
            value.codePointCount(0, value.length) > RouteEstimateTool.MAX_PLACE_CHARACTERS ->
                SettingsTextValidation.Invalid(
                    "기본 출발지는 ${RouteEstimateTool.MAX_PLACE_CHARACTERS}자 이하여야 합니다.",
                )
            !isSafe(value) ->
                SettingsTextValidation.Invalid("기본 출발지에 허용되지 않는 문자가 있습니다.")
            else -> SettingsTextValidation.Valid(value)
        }
    }

    /** Removes bidi/format controls and model delimiters before a provider name reaches UI/model. */
    fun sanitizeProviderLabel(raw: String, maximumCodePoints: Int = MAX_PROVIDER_LABEL_CODE_POINTS): String {
        val cleaned = buildString(raw.length) {
            var index = 0
            while (index < raw.length) {
                val codePoint = raw.codePointAt(index)
                index += Character.charCount(codePoint)
                if (!isUnsafeCodePoint(codePoint)) appendCodePoint(codePoint)
            }
        }.replace("<|", " ")
            .replace("|>", " ")
            .trim()
        return cleaned.takeCodePoints(maximumCodePoints).ifBlank { "(이름 없음)" }
    }

    private fun isSafe(value: String): Boolean = !value.contains("<|") &&
        !value.contains("|>") && value.codePoints().noneMatch(::isUnsafeCodePoint)

    private fun isUnsafeCodePoint(codePoint: Int): Boolean =
        Character.isISOControl(codePoint) || when (Character.getType(codePoint)) {
            Character.FORMAT.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
            -> true
            else -> false
        }

    private fun String.takeCodePoints(maximum: Int): String {
        if (codePointCount(0, length) <= maximum) return this
        val end = offsetByCodePoints(0, maximum)
        return substring(0, end)
    }

    private const val MAX_PROVIDER_LABEL_CODE_POINTS = 120
}
