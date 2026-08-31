package com.personaledge.core.tools

import java.text.Normalizer
import java.util.Locale

/** Content-free query/hit match counts shared by provider fallback and final evidence selection. */
class WebSearchQueryMatch internal constructor(
    val significantTermCount: Int,
    val matchedTermCount: Int,
    val titleMatchedTermCount: Int,
    val exactQueryMatched: Boolean,
) {
    override fun toString(): String =
        "WebSearchQueryMatch(terms=$significantTermCount, matched=$matchedTermCount, " +
            "titleMatched=$titleMatchedTermCount, exact=$exactQueryMatched)"
}

/**
 * Conservative lexical relevance shared across the You.com quality gate and answer policy.
 *
 * NFKC plus compact comparison handles harmless spacing variants such as `살것인가` versus
 * `살 것인가`. It never invents aliases or translations.
 */
object WebSearchQueryRelevance {
    fun analyze(query: String, hit: WebSearchHit): WebSearchQueryMatch {
        val terms = significantTerms(query)
        val title = normalize(hit.title)
        val combined = "$title ${normalize(hit.snippet)} ${normalize(hit.link)}"
        val compactTitle = title.compact()
        val compactCombined = combined.compact()
        val matched = terms.count { term -> term in combined || term.compact() in compactCombined }
        val titleMatched = terms.count { term -> term in title || term.compact() in compactTitle }
        val normalizedQuery = normalize(query)
        val exact = normalizedQuery.isNotBlank() && (
            normalizedQuery in combined || normalizedQuery.compact() in compactCombined
            )
        return WebSearchQueryMatch(
            significantTermCount = terms.size,
            matchedTermCount = matched,
            titleMatchedTermCount = titleMatched,
            exactQueryMatched = exact,
        )
    }

    /** False means a configured fallback provider should get its one existing bounded attempt. */
    fun hasRelevantHit(query: String, hits: List<WebSearchHit>): Boolean {
        if (WebSearchAnswerability.currentOfficeholderQueryOrNull(query) != null) {
            return WebSearchAnswerability.hasCurrentOfficeholderAnswer(query, hits)
        }
        val matches = hits.asSequence()
            .filter { hit -> hit.snippet.isNotBlank() }
            .map { hit -> analyze(query, hit) }
            .toList()
        val termCount = matches.firstOrNull()?.significantTermCount ?: significantTerms(query).size
        val required = when (termCount) {
            0 -> 0
            1 -> 1
            else -> MIN_MATCHED_TERMS
        }
        return matches.any { match -> match.matchedTermCount >= required }
    }

    private fun significantTerms(query: String): List<String> = QUERY_TERM.findAll(normalize(query))
        .map(MatchResult::value)
        .filter { token -> token.length >= 2 && token !in QUERY_STOP_WORDS }
        .distinct()
        .take(MAX_QUERY_TERMS)
        .toList()

    private fun normalize(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(WHITESPACE, " ")
        .trim()

    private fun String.compact(): String = filter(Char::isLetterOrDigit)

    private val QUERY_STOP_WORDS = setOf(
        "관련", "정보", "최신", "뉴스", "소식", "검색", "결과", "사람", "인물", "대해", "대한",
    )
    private val QUERY_TERM = Regex("[0-9A-Za-z가-힣&.]+")
    private val WHITESPACE = Regex("\\s+")

    private const val MAX_QUERY_TERMS = 6
    private const val MIN_MATCHED_TERMS = 2
}
