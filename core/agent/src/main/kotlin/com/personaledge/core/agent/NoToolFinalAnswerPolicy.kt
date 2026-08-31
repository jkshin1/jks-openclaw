package com.personaledge.core.agent

import java.text.Normalizer
import java.util.Locale

/**
 * Minimal completion boundary for a model turn that executed no Tool.
 *
 * This deliberately recognizes only structurally empty output, one exact generic completion
 * receipt, and a normalized echo of the entire current user prompt. It does not estimate semantic
 * correctness, usefulness, answer length, or the presence of topic keywords.
 */
internal object NoToolFinalAnswerPolicy {
    fun accepts(currentUserPrompt: String, modelAnswer: String): Boolean {
        if (modelAnswer.isBlank()) return false
        val answerKey = comparisonKey(modelAnswer)
        if (answerKey == GENERIC_COMPLETION_KEY) return false
        return answerKey != comparisonKey(currentUserPrompt)
    }

    private fun comparisonKey(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .filterNot(Char::isWhitespace)

    private val GENERIC_COMPLETION_KEY = comparisonKey("요청 처리를 완료했습니다.")
}
