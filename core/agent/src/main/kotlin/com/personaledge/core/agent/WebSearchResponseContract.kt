package com.personaledge.core.agent

import java.util.Locale

/**
 * App-owned presentation constraints derived only from the current owner's request.
 *
 * The provider query and the presentation contract stay separate: phrases such as "영어 한
 * 문장으로" can shape the local synthesis without being transferred to the search provider.
 */
internal data class WebSearchResponseContract(
    val language: Language = Language.KOREAN,
    val exactSentenceCount: Int? = null,
    val sourcesOnly: Boolean = false,
) {
    init {
        require(exactSentenceCount == null || exactSentenceCount in 1..MAX_SENTENCES)
    }

    enum class Language {
        KOREAN,
        ENGLISH,
    }

    companion object {
        fun fromOwnerRequest(request: String): WebSearchResponseContract {
            val instruction = responseInstructionSegment(request)
            val lower = instruction.lowercase(Locale.ROOT)
            val asksForEnglish = ENGLISH_LANGUAGE.containsMatchIn(lower)
            val asksForKorean = KOREAN_LANGUAGE.containsMatchIn(lower)
            val language = when {
                asksForEnglish && !asksForKorean -> Language.ENGLISH
                else -> Language.KOREAN
            }
            val sentenceCount = SENTENCE_COUNT.find(instruction)
                ?.groupValues
                ?.get(1)
                ?.let(::sentenceCountOrNull)
            return WebSearchResponseContract(
                language = language,
                exactSentenceCount = sentenceCount,
                sourcesOnly = SOURCES_ONLY.containsMatchIn(lower),
            )
        }

        private fun responseInstructionSegment(request: String): String {
            val normalized = request.trim()
            val marker = SEARCH_ACTION.find(normalized) ?: return normalized
            return normalized.substring(marker.range.first)
        }

        private fun sentenceCountOrNull(value: String): Int? = when (value.lowercase(Locale.ROOT)) {
            "한", "하나", "1", "one" -> 1
            "두", "둘", "2", "two" -> 2
            "세", "셋", "3", "three" -> 3
            "네", "넷", "4", "four" -> 4
            else -> null
        }

        private val SEARCH_ACTION = Regex("검색|찾아|알아봐|조사")
        private val ENGLISH_LANGUAGE = Regex("(?:영어|영문)\\s*(?:로|로만)?|in\\s+english")
        private val KOREAN_LANGUAGE = Regex("(?:한국어|한글)\\s*(?:로|로만)?|in\\s+korean")
        private val SENTENCE_COUNT = Regex(
            "(?:정확히\\s*)?(한|하나|1|one|두|둘|2|two|세|셋|3|three|네|넷|4|four)" +
                "\\s*(?:개\\s*)?(?:문장|sentence(?:s)?)",
            RegexOption.IGNORE_CASE,
        )
        private val SOURCES_ONLY = Regex("(?:출처|링크|url)\\s*(?:만|만을)")
        private const val MAX_SENTENCES = 4
    }
}
