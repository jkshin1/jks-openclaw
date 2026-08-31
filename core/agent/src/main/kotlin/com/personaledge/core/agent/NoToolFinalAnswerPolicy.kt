package com.personaledge.core.agent

import java.text.Normalizer
import java.util.Locale

/**
 * Minimal completion boundary for a model turn that executed no Tool.
 *
 * This deliberately recognizes only structurally empty output, one exact generic completion
 * receipt, a normalized echo of the entire current user prompt, and a fail-closed no-execution
 * contract when a scoped write Tool was offered but never called. Such a turn may only ask a
 * bounded clarification question or explicitly say that it did not execute the write. It does not
 * estimate general semantic correctness, usefulness, answer length, or topic coverage.
 */
internal object NoToolFinalAnswerPolicy {
    fun accepts(
        currentUserPrompt: String,
        modelAnswer: String,
        unexecutedWriteScope: Boolean = false,
    ): Boolean {
        if (modelAnswer.isBlank()) return false
        val answerKey = comparisonKey(modelAnswer)
        if (answerKey == GENERIC_COMPLETION_KEY) return false
        if (unexecutedWriteScope) {
            if (SUCCESS_CLAIM_MARKERS.any(answerKey::contains)) return false
            if (!isClarificationQuestion(modelAnswer, answerKey) &&
                NON_EXECUTION_MARKERS.none(answerKey::contains)
            ) {
                return false
            }
        }
        return answerKey != comparisonKey(currentUserPrompt)
    }

    private fun isClarificationQuestion(value: String, answerKey: String): Boolean =
        value.trimEnd().lastOrNull() in QUESTION_TERMINATORS &&
            CLARIFICATION_MARKERS.any(answerKey::contains)

    private fun comparisonKey(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .filterNot(Char::isWhitespace)

    private val GENERIC_COMPLETION_KEY = comparisonKey("요청 처리를 완료했습니다.")
    private val CLARIFICATION_MARKERS = listOf(
        "언제", "어디", "어느", "무엇", "뭘", "누구", "몇", "어떤", "어떻게",
        "할까요", "인가요", "맞나요", "어떤가요", "when", "where", "which", "what",
        "who", "how", "wouldyou", "shouldi",
    ).map(::comparisonKey)
    private val NON_EXECUTION_MARKERS = listOf(
        "실행하지않았습니다", "처리하지않았습니다", "등록하지않았습니다",
        "추가하지않았습니다", "생성하지않았습니다", "설정하지않았습니다",
        "변경하지않았습니다", "수정하지않았습니다", "삭제하지않았습니다",
        "취소하지않았습니다", "저장하지않았습니다", "기억하지않았습니다",
        "보내지않았습니다", "전송하지않았습니다", "공유하지않았습니다",
        "답장하지않았습니다", "할수없습니다", "진행할수없습니다", "권한이필요합니다",
        "확인이필요합니다", "정보가필요합니다", "didnot", "wasnot", "cannot", "can't",
        "needpermission", "needconfirmation", "needmoreinformation",
    ).map(::comparisonKey)
    private val SUCCESS_CLAIM_MARKERS = listOf(
        "등록했", "등록됐", "등록해두었", "추가했", "추가됐", "생성했", "생성됐",
        "설정했", "설정됐", "변경했", "변경됐", "수정했", "수정됐", "삭제했", "삭제됐",
        "취소했", "취소됐", "저장했", "저장됐", "기억했", "기억됐", "보냈", "전송했",
        "공유했", "답장했", "열었", "만들었", "완료했", "예약됐", "드렸",
        "created", "saved", "sent", "updated", "deleted", "cancelled", "canceled",
        "scheduled", "completed",
    ).map(::comparisonKey)
    private val QUESTION_TERMINATORS = setOf('?', '？')
}
