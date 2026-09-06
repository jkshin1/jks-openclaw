package com.personaledge.core.tools

import java.net.URI
import java.text.Normalizer
import java.util.Locale

/** App-canonical current-officeholder query. Content stays ephemeral and is never diagnostic data. */
class CurrentOfficeholderQuery internal constructor(
    val entity: String,
    val role: String,
) {
    override fun toString(): String = "CurrentOfficeholderQuery(entity=<redacted>, role=<redacted>)"
}

/** Direct role-to-name assertion extracted from one bounded search hit. */
class CurrentOfficeholderEvidence internal constructor(
    val name: String,
    val authoritative: Boolean,
) {
    override fun toString(): String =
        "CurrentOfficeholderEvidence(authoritative=$authoritative, name=<redacted>)"
}

/** App-canonical latest-release query: one public subject plus an explicit release marker. */
class LatestVersionQuery internal constructor(
    val subject: String,
) {
    override fun toString(): String = "LatestVersionQuery(subject=<redacted>)"
}

/** A release identifier one bounded hit directly attributes to the requested subject. */
class LatestVersionEvidence internal constructor(
    val version: String,
) {
    override fun toString(): String = "LatestVersionEvidence(version=<redacted>)"
}

/**
 * Answerability checks shared by provider fallback and final answer selection.
 *
 * Topic overlap is not enough for a `who currently holds this office` question. A usable hit must
 * directly connect a plausible person name to the requested role and must explicitly say that it
 * is current. A government host raises authority only after that freshness gate; it never makes a
 * stale undated page current by itself. Generic constitution, election, and term pages fail.
 */
object WebSearchAnswerability {
    fun currentOfficeholderQueryOrNull(query: String): CurrentOfficeholderQuery? {
        val match = CURRENT_OFFICEHOLDER_QUERY.matchEntire(normalize(query)) ?: return null
        val entity = match.groupValues[1].trim()
        val role = match.groupValues[2].trim()
        if (entity.length !in 2..48 || role !in OFFICEHOLDER_ROLES) return null
        return CurrentOfficeholderQuery(entity = entity, role = role)
    }

    fun currentOfficeholderEvidenceOrNull(
        query: String,
        hit: WebSearchHit,
    ): CurrentOfficeholderEvidence? {
        val request = currentOfficeholderQueryOrNull(query) ?: return null
        val displayText = normalizePreservingCase("${hit.title} ${hit.snippet}")
        val combined = displayText.lowercase(Locale.ROOT)
        if (HISTORICAL_MARKERS.any(combined::contains) ||
            NEGATED_CURRENT_MARKERS.any(combined::contains) ||
            CURRENT_EVIDENCE_MARKERS.none(combined::contains)
        ) {
            return null
        }
        val authoritative = isGovernmentSource(hit.link)
        if (normalize(request.entity).compact() !in combined.compact()) return null

        val role = "(?i:${Regex.escape(normalize(request.role))})"
        val patterns = listOf(
            // Prefer the less ambiguous `role -> name` shape. Requiring a particle, colon, or
            // whitespace prevents the copula in `한강시 시장입니다` from becoming the "name".
            Regex(
                "(?<![0-9A-Za-z\uAC00-\uD7A3])$role" +
                    "(?:(?:은|는|이|가|으로|로|에는|:)\\s*|\\s+)" +
                    "([\uAC00-\uD7A3]{2,4})(?=\\s*(?:입니다|이다|이며|이고|[,.:;]|$))",
            ),
            // Transliterated names such as `샘 올트먼` are allowed only after the requested role.
            Regex(
                "(?<![0-9A-Za-z\uAC00-\uD7A3])$role" +
                    "(?:(?:은|는|이|가|으로|로|에는|:)\\s*|\\s+(?:is\\s+)?)" +
                    "([\uAC00-\uD7A3]{1,4}(?:\\s+[\uAC00-\uD7A3]{1,4}){1,2})" +
                    "(?=\\s*(?:입니다|이다|이며|이고|[,.:;]|$))",
            ),
            // Preserve capitalization and require two to four Latin name tokens. This supports
            // `CEO is Sam Altman` without accepting a single entity or background noun as a name.
            Regex(
                "(?<![0-9A-Za-z\uAC00-\uD7A3])$role" +
                    "(?:(?:은|는|이|가|으로|로|에는|:)\\s*|\\s+(?:is\\s+)?)" +
                    "([A-Z][A-Za-z.'’\\-]{0,39}(?:\\s+[A-Z][A-Za-z.'’\\-]{0,39}){1,3})" +
                    "(?=\\s*(?:입니다|이다|이며|이고|[,.:;]|$))",
            ),
            // Keep direct `name -> role` assertions, but never start inside a longer Hangul token
            // and never treat a concatenated entity such as `한강시장` as a person's name.
            Regex(
                "(?<![\uAC00-\uD7A3])([\uAC00-\uD7A3]{2,4})" +
                    "(?:(?:은|는|이|가)\\s*|\\s+)$role" +
                    "(?:은|는|이|가|입니다|이다|으로|로|[,.:;]|\\s|$)",
            ),
        )
        val requestedEntity = normalize(request.entity).compact()
        val name = patterns.asSequence()
            .flatMap { pattern -> pattern.findAll(displayText).map { match -> match.groupValues[1] } }
            .map { candidate ->
                candidate.replace(WHITESPACE, " ").trim().trimEnd('.', ',', ':', ';')
            }
            .map(::stripCandidateParticle)
            .firstOrNull { candidate ->
                isPlausiblePersonName(candidate) && candidate.compact() != requestedEntity
            }
            ?: return null
        return CurrentOfficeholderEvidence(name = name, authoritative = authoritative)
    }

    /**
     * Parses `<subject> 최신 버전`-shaped queries into their subject.
     *
     * Deliberately narrow. The marker must name a release, so ordinary "최신 소식" topics never
     * enter this path, and a subject that is only a first-person app reference is refused because
     * no public source can answer it.
     */
    fun latestVersionQueryOrNull(query: String): LatestVersionQuery? {
        val normalized = normalizePreservingCase(query)
        val match = LATEST_VERSION_QUERY.matchEntire(normalized) ?: return null
        val subject = match.groupValues[1].trim().trimEnd('의')
        if (subject.length !in 2..48) return null
        if (PRIVATE_SUBJECT_TERMS.any { term -> subject.compact().contains(term) }) return null
        return LatestVersionQuery(subject = subject)
    }

    /**
     * Returns the release this hit attributes to the requested subject, or null.
     *
     * A page that merely repeats the subject and the word "버전" is help prose — "설정 앱에서
     * 버전을 확인할 수 있습니다" answers a different question — so a usable hit has to place a
     * version number directly after the subject.
     */
    fun latestVersionEvidenceOrNull(query: String, hit: WebSearchHit): LatestVersionEvidence? {
        val subject = latestVersionQueryOrNull(query)?.subject ?: return null
        val text = normalizePreservingCase("${hit.title} ${hit.snippet}")
        val pattern = Regex(
            "${Regex.escape(subject)}\\s+v?(\\d+(?:\\.\\d+){0,3})",
            RegexOption.IGNORE_CASE,
        )
        val version = pattern.find(text)?.groupValues?.get(1) ?: return null
        if (version.length > 24) return null
        return LatestVersionEvidence(version = version)
    }

    fun hasCurrentOfficeholderAnswer(query: String, hits: List<WebSearchHit>): Boolean =
        currentOfficeholderQueryOrNull(query) != null &&
            hits.any { hit -> currentOfficeholderEvidenceOrNull(query, hit) != null }

    private fun isGovernmentSource(link: String): Boolean {
        val host = runCatching { URI(link).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return false
        return host == "korea.kr" || host.isDomainOrSubdomainOf("go.kr") ||
            host.isDomainOrSubdomainOf("gov")
    }

    private fun String.isDomainOrSubdomainOf(domain: String): Boolean =
        this == domain || endsWith(".$domain")

    private fun stripCandidateParticle(value: String): String {
        var candidate = value
        for (suffix in NAME_PARTICLES) {
            if (candidate.endsWith(suffix) && candidate.length - suffix.length >= 2) {
                candidate = candidate.dropLast(suffix.length)
                break
            }
        }
        return candidate
    }

    private fun isPlausiblePersonName(candidate: String): Boolean {
        val koreanParts = candidate.split(' ')
        if (koreanParts.all { part -> part.isNotEmpty() && part.all { it in '\uAC00'..'\uD7A3' } }) {
            val compact = candidate.replace(" ", "")
            return koreanParts.size in 1..3 && compact.length in 2..8 &&
                koreanParts.none { part -> part in NON_PERSON_TERMS } && compact !in NON_PERSON_TERMS
        }
        val latinParts = candidate.split(' ')
        return latinParts.size in 2..4 && latinParts.all(LATIN_NAME_TOKEN::matches) &&
            latinParts.none { part -> part.lowercase(Locale.ROOT).trim('.', '\'', '’') in NON_PERSON_LATIN_TERMS }
    }

    private fun normalize(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(WHITESPACE, " ")
        .trim()

    private fun normalizePreservingCase(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .replace(WHITESPACE, " ")
        .trim()

    private fun String.compact(): String = filter(Char::isLetterOrDigit)

    private val OFFICEHOLDER_ROLES = setOf(
        "국무총리", "최고경영자", "대표이사", "대통령", "도지사", "총리", "수상", "ceo", "시장", "회장",
    )
    // Event and present-tense words such as `당선`, `취임`, `재임`, `이끌고`, and `맡고` do
    // not prove freshness: archived pages use all of them for former officeholders. Require an
    // explicit current-time marker before a role-to-name assertion can answer a volatile question.
    private val CURRENT_EVIDENCE_MARKERS = listOf(
        "현재", "현직", "지금", "오늘", "current", "currently", "incumbent",
    )
    private val HISTORICAL_MARKERS = listOf(
        "역대", "초대", "전직", "전임", "당시", "예전", "연표", "역사",
        "former", "previous", "historical",
    )
    private val NEGATED_CURRENT_MARKERS = listOf(
        "현재가 아닌", "현재는 아닌", "현직이 아닌", "현직은 아닌", "not current", "no longer",
    )
    private val NON_PERSON_TERMS = setOf(
        "대통령", "국무총리", "총리", "수상", "대표이사", "도지사", "시장", "회장",
        "대한민국", "대통령실", "국가원수", "국가", "정부", "국민", "현재", "현직",
        "선거", "선출", "헌법", "임기", "권한", "업무", "역할", "제도", "취임", "당선",
        "소개", "정보", "공식", "직책", "사람", "누구", "이름", "자료", "내용", "선거로",
    )
    private val NAME_PARTICLES = listOf("으로", "에게", "이며", "이고", "은", "는", "이", "가", "을", "를", "의", "에", "로")
    private val NON_PERSON_LATIN_TERMS = setOf(
        "chief", "company", "corporation", "current", "currently", "executive", "information",
        "not", "official", "officer", "office", "president", "responsible", "role", "unknown",
        "unavailable", "available",
    )
    private val LATIN_NAME_TOKEN = Regex("[A-Za-z][A-Za-z.'’\\-]{0,39}")
    private val CURRENT_OFFICEHOLDER_QUERY = Regex("^(.+?)\\s+현직\\s+(.+?)\\s+이름\\s+공식$")
    private val LATEST_VERSION_QUERY = Regex(
        "^(.+?)\\s*(?:최신\\s*안정\\s*버전|최신\\s*버전|최신\\s*릴리스|최근\\s*릴리스)\\s*[?!.]*$",
    )
    private val PRIVATE_SUBJECT_TERMS = setOf("내앱", "우리앱", "앱")
    private val WHITESPACE = Regex("\\s+")
}
