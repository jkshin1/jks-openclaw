package com.personaledge.agent

import com.personaledge.core.llm.MAX_USER_PROMPT_BYTES

/** Keeps model input bounded while retaining a reasonably sized oversized draft for editing. */
internal object PromptInputPolicy {
    private const val TURN_CONTEXT_RESERVE_BYTES = 640

    const val MAX_ACCEPTED_BYTES: Int = MAX_USER_PROMPT_BYTES - TURN_CONTEXT_RESERVE_BYTES
    const val MAX_VISIBLE_DRAFT_BYTES: Int = 64 * 1024
    const val LIMIT_WARNING: String =
        "전송 가능한 입력 길이를 넘었습니다. 내용은 유지됩니다. 전송하려면 내용을 줄여 주세요."
    const val VISIBLE_LIMIT_WARNING: String =
        "표시 가능한 입력 크기까지 넘어 이번 편집이나 붙여넣기를 반영하지 않았습니다. " +
            "직전 초안은 그대로 유지됩니다."

    fun isAccepted(value: String): Boolean =
        value.toByteArray(Charsets.UTF_8).size <= MAX_ACCEPTED_BYTES

    fun apply(currentPrompt: String, candidate: String): PromptInputUpdate {
        val candidateBytes = candidate.toByteArray(Charsets.UTF_8).size
        return when {
            candidateBytes <= MAX_ACCEPTED_BYTES ->
                PromptInputUpdate(prompt = candidate, warning = null)
            candidateBytes <= MAX_VISIBLE_DRAFT_BYTES ->
                PromptInputUpdate(prompt = candidate, warning = LIMIT_WARNING)
            else ->
                PromptInputUpdate(prompt = currentPrompt, warning = VISIBLE_LIMIT_WARNING)
        }
    }
}

internal data class PromptInputUpdate(
    val prompt: String,
    val warning: String?,
)
