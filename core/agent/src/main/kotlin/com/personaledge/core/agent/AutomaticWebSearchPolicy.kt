package com.personaledge.core.agent

import com.personaledge.core.tools.WebSearchTool
import java.util.Locale

/**
 * A turn-local public-web request derived only from owner-authored text.
 *
 * The constructor and content stay inside `core:agent`; callers in `app` can carry the typed value
 * without reading or rewriting its query. This prevents a rendered conversation, assistant answer,
 * summary, memory, or provider result from becoming an accidental external-transfer source.
 */
class TrustedWebSearchRequest internal constructor(
    internal val query: String,
    internal val intent: WebSearchAnswerIntent,
    internal val responseContract: WebSearchResponseContract = WebSearchResponseContract(),
    internal val requiresImmediateSearch: Boolean = false,
) {
    override fun toString(): String =
        "TrustedWebSearchRequest(intent=$intent, query=<redacted>)"
}

/**
 * Closed policy for optional public-knowledge lookup and subjectless web-search follow-ups.
 *
 * This policy does not enable networking. The existing persistent owner consent and execution-time
 * interlock still decide whether [com.personaledge.core.tools.WebSearchTool] may leave the device.
 */
object AutomaticWebSearchPolicy {
    /**
     * Resolves phrases such as "잘 모르겠으면 웹에서 찾아서 알려줘" from exactly one preceding
     * owner-authored USER request. A complete new search request never inherits the older subject.
     */
    fun contextualRequestOrNull(
        followUp: String,
        previousUserRequest: String,
    ): TrustedWebSearchRequest? {
        val normalizedFollowUp = followUp.trim()
        val previous = contextualSourceRequestOrNull(previousUserRequest) ?: return null
        if (!looksLikeContextualSearchFollowUp(normalizedFollowUp, previous)) return null
        return previous
    }

    /**
     * Resolves at most one short run of owner-authored search corrections.
     *
     * A failed read can leave a trailing USER row without an ASSISTANT row. Callers may therefore
     * provide the newest USER rows, but this policy skips only text that is itself a closed search
     * correction. An unrelated, private, or unclassifiable user request stops the walk so an old
     * public subject can never be revived accidentally.
     */
    fun contextualRequestOrNull(
        followUp: String,
        previousUserRequestsNewestFirst: List<String>,
    ): TrustedWebSearchRequest? {
        previousUserRequestsNewestFirst.take(MAX_CONTEXTUAL_USER_ROWS).forEach { previous ->
            contextualRequestOrNull(followUp, previous)?.let { return it }
            if (!looksLikeSkippableSearchCorrection(previous.trim())) return null
        }
        return null
    }

    /** The model gets one optional web Tool only for this bounded public-knowledge grammar. */
    internal fun knowledgeRequestOrNull(request: String): TrustedWebSearchRequest? {
        val normalized = request.trim()
        if (!isSafePublicRequest(normalized)) return null
        currentOfficeholderRequestOrNull(normalized)?.let { return it }
        volatilePublicFactRequestOrNull(normalized)?.let { return it }

        val relation = KNOWLEDGE_RELATION_MARKERS
            .map { marker -> marker to normalized.indexOf(marker) }
            .filter { (_, index) -> index >= MINIMUM_SUBJECT_CHARACTERS }
            .minByOrNull { (_, index) -> index }
            ?: return null
        val (marker, index) = relation
        val requestTail = normalized.substring(index + marker.length)
        if (KNOWLEDGE_ANSWER_MARKERS.none(requestTail::contains)) return null

        var query = normalized.substring(0, index).trim(*QUERY_TRIM_CHARACTERS)
        for (prefix in KNOWLEDGE_QUERY_PREFIXES) {
            if (query.startsWith(prefix)) {
                query = query.removePrefix(prefix).trimStart()
                break
            }
        }
        query = query.trim(*QUERY_TRIM_CHARACTERS)
        if (PUBLIC_KNOWLEDGE_MARKERS.none(query.lowercase(Locale.ROOT)::contains)) return null
        query = normalizePublicSubjectQuery(query)
        if (query.length !in MINIMUM_SUBJECT_CHARACTERS..WebSearchTool.MAX_QUERY_CHARACTERS ||
            query.none(Char::isLetterOrDigit) ||
            GENERIC_KNOWLEDGE_SUBJECTS.contains(query.compactKey())
        ) {
            return null
        }
        return TrustedWebSearchRequest(
            query = query,
            intent = WebSearchAnswerPolicy.intentForRequest(normalized),
            responseContract = WebSearchResponseContract.fromOwnerRequest(normalized),
        )
    }

    /**
     * The first local decode is buffered, so an explicit knowledge gap can be replaced before the
     * user sees it. Safety/policy refusals are intentionally not classified as knowledge gaps.
     */
    internal fun fallbackRequestOrNull(
        userRequest: String,
        modelAnswer: String,
    ): TrustedWebSearchRequest? {
        if (modelAnswer.isBlank() || modelAnswer.length > MAX_MODEL_ANSWER_CHARACTERS ||
            MODEL_KNOWLEDGE_GAP_MARKERS.none(modelAnswer.lowercase(Locale.ROOT)::contains)
        ) {
            return null
        }
        return knowledgeRequestOrNull(userRequest)
            ?.takeUnless(TrustedWebSearchRequest::requiresImmediateSearch)
    }

    /** Rejects generic condition text before it can become a literal provider query. */
    internal fun isSubjectlessQueryCandidate(query: String): Boolean {
        var key = query.compactKey()
        SUBJECTLESS_QUERY_DECORATORS.forEach { decorator -> key = key.replace(decorator, "") }
        return key in SUBJECTLESS_QUERY_CANDIDATES || key in GENERIC_KNOWLEDGE_SUBJECTS
    }

    /** Content-free predicate used by history to skip only failed search-correction USER rows. */
    fun isContextualSearchCorrection(request: String): Boolean =
        looksLikeSkippableSearchCorrection(request.trim())

    /** Meta-instructions are conversation control, never literal provider queries. */
    internal fun isBoundedMetaSearchCorrection(request: String): Boolean {
        val value = request.trim()
        if (!isSafeSearchCorrection(value)) return false
        val compact = value.compactKey()
        if (compact in SEARCH_CAPABILITY_CORRECTIONS || PRIOR_QUESTION_MARKERS.any(value::contains)) {
            return true
        }
        if (SEARCH_QUALITY_RETRY_MARKERS.none(value::contains)) return false
        val candidate = explicitWebSearchQueryCandidateOrNull(value)
        return candidate == null || candidate.compactKey() in META_SEARCH_QUERY_CANDIDATES
    }

    internal fun isLiteralSearchCorrection(request: String): Boolean {
        val value = request.trim()
        if (!isSafeSearchCorrection(value)) return false
        return isBoundedMetaSearchCorrection(value) || isEntitylessOfficeholderCorrection(value)
    }

    /** Shared external-transfer gate for deterministic explicit-search parsing. */
    internal fun isSafePublicWebTransfer(request: String): Boolean =
        isSafePublicRequest(request.trim())

    private fun looksLikeContextualSearchFollowUp(
        value: String,
        previous: TrustedWebSearchRequest,
    ): Boolean {
        if (!isSafeSearchCorrection(value)) return false
        if (isBoundedMetaSearchCorrection(value)) return true
        if (previous.intent == WebSearchAnswerIntent.CURRENT_OFFICEHOLDER &&
            isEntitylessOfficeholderCorrection(value)
        ) {
            return true
        }

        // A complete, unrelated standalone query always wins over conversation carry-over.
        val standalone = explicitWebSearchQueryOrNull(value)
        if (standalone != null && !isSubjectlessQueryCandidate(standalone)) return false
        return looksLikeSkippableSearchCorrection(value)
    }

    private fun looksLikeSkippableSearchCorrection(value: String): Boolean {
        if (!isSafeSearchCorrection(value)) return false
        if (isBoundedMetaSearchCorrection(value) || isEntitylessOfficeholderCorrection(value)) return true
        val standalone = explicitWebSearchQueryOrNull(value)
        return standalone == null || isSubjectlessQueryCandidate(standalone)
    }

    private fun contextualSourceRequestOrNull(value: String): TrustedWebSearchRequest? {
        knowledgeRequestOrNull(value)?.let { return it }
        val normalized = value.trim()
        if (!isSafePublicRequest(normalized)) return null
        val query = explicitWebSearchQueryOrNull(normalized) ?: return null
        if (isSubjectlessQueryCandidate(query)) return null
        return TrustedWebSearchRequest(
            query = query,
            intent = WebSearchAnswerPolicy.intentForRequest(normalized),
            responseContract = WebSearchResponseContract.fromOwnerRequest(normalized),
        )
    }

    /**
     * Parses one owner-authored public subject followed by one closed volatile fact kind.
     *
     * The provider query is assembled only from those two captured spans. Condition text,
     * conversation references, writes, private context, and a second subject never enter it.
     */
    private fun volatilePublicFactRequestOrNull(value: String): TrustedWebSearchRequest? {
        val compactWhitespace = value.replace(WHITESPACE, " ").trim(*QUERY_TRIM_CHARACTERS)
        val match = VOLATILE_PUBLIC_FACT_REQUEST.matchEntire(compactWhitespace) ?: return null
        val subject = match.groupValues[1]
            .trim(*QUERY_TRIM_CHARACTERS)
            .removeSuffix("의")
            .trimEnd()
        val fact = canonicalVolatileFact(match.groupValues[2]) ?: return null
        val compactSubject = subject.compactKey()
        if (subject.length !in MINIMUM_SUBJECT_CHARACTERS..MAX_VOLATILE_SUBJECT_CHARACTERS ||
            subject.none(Char::isLetterOrDigit) ||
            compactSubject in GENERIC_VOLATILE_SUBJECTS ||
            VOLATILE_COMPOUND_MARKERS.any(subject::contains) ||
            VOLATILE_FACT_COMPACT_MARKERS.any(compactSubject::contains)
        ) {
            return null
        }
        val query = "$subject $fact"
        if (query.length > WebSearchTool.MAX_QUERY_CHARACTERS) return null
        return TrustedWebSearchRequest(
            query = query,
            intent = WebSearchAnswerPolicy.intentForRequest(value),
            responseContract = WebSearchResponseContract.fromOwnerRequest(value),
            requiresImmediateSearch = true,
        )
    }

    private fun canonicalVolatileFact(value: String): String? = when (value.compactKey()) {
        "최신뉴스" -> "최신 뉴스"
        "최근뉴스" -> "최근 뉴스"
        "오늘뉴스" -> "오늘 뉴스"
        "실시간뉴스" -> "실시간 뉴스"
        "시세" -> "시세"
        "현재시세" -> "현재 시세"
        "실시간시세" -> "실시간 시세"
        "환율" -> "환율"
        "현재환율" -> "현재 환율"
        "실시간환율" -> "실시간 환율"
        "기준금리" -> "기준금리"
        "현재기준금리" -> "현재 기준금리"
        "순위" -> "순위"
        "현재순위" -> "현재 순위"
        "최신순위" -> "최신 순위"
        "최근실적" -> "최근 실적"
        "최신실적" -> "최신 실적"
        "최근분기실적" -> "최근 분기 실적"
        "주가" -> "주가"
        "현재주가" -> "현재 주가"
        "실시간주가" -> "실시간 주가"
        "현재가격" -> "현재 가격"
        else -> null
    }

    private fun isEntitylessOfficeholderCorrection(value: String): Boolean =
        SEARCH_QUALITY_RETRY_MARKERS.any(value::contains) &&
            CURRENT_OFFICEHOLDER_ROLES.any(value::contains) &&
            CURRENT_PERSON_ANSWER_MARKERS.any(value::contains) &&
            currentOfficeholderRequestOrNull(value) == null

    private fun isSafeSearchCorrection(value: String): Boolean =
        value.length in 2..MAX_FOLLOW_UP_CHARACTERS &&
            SEARCH_REQUEST_MARKERS.any(value::contains) &&
            isSafePublicRequest(value)

    /** Closed volatile-fact grammar; the role and entity both come from owner-authored text. */
    private fun currentOfficeholderRequestOrNull(value: String): TrustedWebSearchRequest? {
        if (HISTORICAL_OFFICEHOLDER_MARKERS.any(value::contains)) return null
        val roleMatch = CURRENT_OFFICEHOLDER_ROLES
            .map { role -> role to value.indexOf(role, ignoreCase = true) }
            .filter { (_, index) -> index > 0 }
            .minByOrNull { (_, index) -> index }
            ?: return null
        val (role, roleIndex) = roleMatch
        val afterRole = value.substring(roleIndex + role.length)
        val lower = value.lowercase(Locale.ROOT)
        if (OFFICEHOLDER_FOREIGN_DOMAIN_MARKERS.any(lower::contains) ||
            OFFICEHOLDER_COMPOUND_TAIL_MARKERS.any(afterRole::contains) ||
            CURRENT_OFFICEHOLDER_ROLES.any { candidate ->
                afterRole.contains(candidate, ignoreCase = true)
            }
        ) {
            return null
        }
        if (CURRENT_PERSON_ANSWER_MARKERS.none(afterRole::contains) &&
            CURRENT_PERSON_ANSWER_MARKERS.none(value::contains)
        ) {
            return null
        }

        var entity = value.substring(0, roleIndex)
        CURRENT_TIME_MARKERS.forEach { marker -> entity = entity.replace(marker, " ") }
        entity = entity
            .replace(WHITESPACE, " ")
            .trim(*QUERY_TRIM_CHARACTERS)
        CURRENT_OFFICEHOLDER_ENTITY_PREFIXES.firstOrNull(entity::startsWith)?.let { prefix ->
            entity = entity.removePrefix(prefix).trimStart()
        }
        entity = entity
            .removeSuffix("의")
            .trim(*QUERY_TRIM_CHARACTERS)
        if (entity.length !in MINIMUM_SUBJECT_CHARACTERS..MAX_OFFICEHOLDER_ENTITY_CHARACTERS ||
            entity.none(Char::isLetterOrDigit) ||
            GENERIC_OFFICEHOLDER_ENTITIES.contains(entity.compactKey())
        ) {
            return null
        }

        val query = "$entity 현직 $role 이름 공식"
        if (query.length > WebSearchTool.MAX_QUERY_CHARACTERS) return null
        return TrustedWebSearchRequest(
            query = query,
            intent = WebSearchAnswerIntent.CURRENT_OFFICEHOLDER,
            responseContract = WebSearchResponseContract.fromOwnerRequest(value),
            requiresImmediateSearch = true,
        )
    }

    private fun isSafePublicRequest(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        val compact = lower.filter(Char::isLetterOrDigit)
        return value.length in 4..MAX_KNOWLEDGE_REQUEST_CHARACTERS &&
            value.none(::isUnsafeCharacter) &&
            EXTERNAL_TRANSFER_BLOCK_MARKERS.none(lower::contains) &&
            PRIVATE_CONTEXT_MARKERS.none(lower::contains) &&
            PRIVATE_CONTEXT_COMPACT_MARKERS.none(compact::contains) &&
            !SENSITIVE_VALUE_PATTERN.containsMatchIn(value)
    }

    private fun String.compactKey(): String = lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private fun normalizePublicSubjectQuery(value: String): String =
        PUBLIC_SUBJECT_SUFFIX.replace(value) { match -> match.groupValues[1] }
            .trim(*QUERY_TRIM_CHARACTERS)

    private fun isUnsafeCharacter(character: Char): Boolean =
        character.isISOControl() || Character.getType(character) == Character.FORMAT.toInt()

    private val KNOWLEDGE_RELATION_MARKERS = listOf(
        "에 대해서", "에 대해", "에 대한", "에 관해서", "에 관해", "에 관한",
    )
    private val KNOWLEDGE_ANSWER_MARKERS = listOf(
        "알려", "설명", "소개", "정리", "말해", "궁금", "무엇", "뭐야", "어떤",
    )
    private val KNOWLEDGE_QUERY_PREFIXES = listOf("혹시 ", "가능하면 ", "아는 범위에서 ")
    private val SEARCH_REQUEST_MARKERS = listOf(
        "웹에서", "인터넷에서", "온라인에서", "검색", "찾아", "알아봐", "조사",
    )
    private val CURRENT_OFFICEHOLDER_ROLES = listOf(
        "국무총리", "최고경영자", "대표이사", "대통령", "도지사", "총리", "수상", "CEO", "시장", "회장",
    )
    private val CURRENT_PERSON_ANSWER_MARKERS = listOf(
        "누구", "이름", "누구야", "누구예요", "누구인가", "who", "name",
    )
    private val CURRENT_TIME_MARKERS = listOf("현재", "지금", "현직", "요즘", "오늘")
    private val CURRENT_OFFICEHOLDER_ENTITY_PREFIXES = listOf(
        "웹에서 ", "인터넷에서 ", "온라인에서 ", "혹시 ", "가능하면 ", "알려줘 ",
    )
    private val HISTORICAL_OFFICEHOLDER_MARKERS = listOf(
        "초대", "역대", "당시", "전임", "전 대통령", "전직", "예전", "언제", "몇 대",
    )
    private val GENERIC_OFFICEHOLDER_ENTITIES = setOf(
        "우리", "우리나라", "이국가", "그국가", "해당국가", "정부", "회사", "그회사", "해당회사",
    )
    private val PRIOR_QUESTION_MARKERS = listOf(
        "첫질문", "첫 질문", "첫번째 질문", "첫 번째 질문", "처음 질문", "원래 질문",
        "앞 질문", "위 질문", "그 질문", "방금 질문", "이전 질문",
    )
    private val SEARCH_QUALITY_RETRY_MARKERS = listOf(
        "더 잘", "제대로", "정확히", "다시", "재검색", "검색어를 바꾸", "검색어를 바꿔",
        "다른 검색어",
    )
    private val SEARCH_CAPABILITY_CORRECTIONS = setOf(
        "웹검색할수있잖아", "웹검색할수있잖아요", "웹검색할수있지", "웹검색가능하잖아",
        "인터넷검색할수있잖아", "검색할수있잖아", "검색하면되잖아",
    )
    private val EXTERNAL_TRANSFER_BLOCK_MARKERS = listOf(
        "카카오", "카톡", "메시지 보내", "보낼 메시지", "전송해", "공유해", "답장해",
        "캘린더", "일정", "알람", "리마인더", "기온", "예보", "경로", "길찾기",
        "기억해", "저장해", "예약해", "삭제해", "만들어", "생성해", "등록해", "추가해",
        "수정해", "변경해", "취소해", "설정해", "맞춰", "보내줘",
        "번역", "문장을", "문구를", "따라 말", "그대로 반복",
        "비밀번호", "암호", "인증번호", "보안코드", "api 키", "api key", "토큰",
        "주민등록", "주민번호", "계좌번호", "카드번호", "개인정보", "진단 기록", "건강 기록",
        "병력", "처방전", "복용 기록", "내 기록", "나의 기록", "내 주소", "우리 집 주소",
        "폭탄", "폭발물", "무기 제조", "마약 제조", "독극물", "악성코드", "랜섬웨어",
        "피싱", "계정 침입", "자살 방법", "살해 방법",
    )
    private val PUBLIC_KNOWLEDGE_MARKERS = listOf(
        "영화", "애니메이션", "드라마", "다큐멘터리", "책", "소설", "작품", "만화",
        "음악", "노래", "앨범", "게임", "회사", "기업", "브랜드", "제품", "기술",
        "프로그래밍", "역사", "사건", "국가", "나라", "도시", "지역", "행성", "동물",
        "식물", "과학", "수학", "철학", "미술", "건축", "박물관", "대학", "스포츠",
        "축구", "야구", "농구", "배우", "감독", "작가", "가수", "선수",
    )
    private val PRIVATE_CONTEXT_MARKERS = listOf(
        "내 ", "나의 ", "저의 ", "제 ", "내가 ", "제가 ", "우리 회사", "우리 기업",
        "우리 학교", "우리 가족",
        "우리 팀", "사내", "미공개", "비공개", "기밀", "초안", "관람 기록", "구매 기록",
        "검색 기록", "업무 기록", "회의록", "my ", "our company", "confidential", "private",
    )
    // Whitespace is not a security boundary. Keep this list narrow enough not to reject ordinary
    // public names such as `제주도`, while covering the owner-relative forms seen in Korean input.
    private val PRIVATE_CONTEXT_COMPACT_MARKERS = listOf(
        "내프로젝트", "내회사", "내기업", "내학교", "내가족", "내팀", "우리회사",
        "우리기업", "우리학교", "우리가족", "우리팀", "사내", "미공개", "비공개", "기밀",
        "myproject", "mycompany", "ourcompany", "confidential", "private",
    )
    private val OFFICEHOLDER_FOREIGN_DOMAIN_MARKERS = listOf(
        "날씨", "기온", "예보", "일정", "캘린더", "알람", "리마인더", "경로", "길찾기",
        "시세", "환율", "기준금리", "주가", "실적", "최신 뉴스", "최근 뉴스",
    )
    private val OFFICEHOLDER_COMPOUND_TAIL_MARKERS = listOf(
        " 그리고 ", " 및 ", "와 ", "과 ", ",", ";", " / ", "누구고", "누구랑",
        "도 알려", "도 보여", "도 찾아", "도 검색", "도 확인",
    )
    private val SUBJECTLESS_QUERY_CANDIDATES = setOf(
        "잘모르겠으면", "모르겠으면", "잘모르면", "모르면", "모르는내용이면",
        "확실하지않으면", "정보가없으면", "정보를모르면", "알수없으면",
        "잘모르겠으니", "모르겠으니", "잘모르니", "모르니", "모르는거면",
    )
    private val SUBJECTLESS_QUERY_DECORATORS = listOf(
        "인터넷에서", "온라인에서", "웹에서", "인터넷", "온라인", "웹",
        "한번더", "다시", "직접", "한번", "제발", "좀", "그렇다면", "그러면", "그럼",
    )
    private val GENERIC_KNOWLEDGE_SUBJECTS = setOf(
        "그것", "그거", "이것", "이거", "그내용", "이내용", "그질문", "방금질문",
        "그영화", "이영화", "해당영화", "그작품", "이작품", "해당작품",
        "웹", "인터넷", "온라인", "검색", "검색어",
    )
    private val META_SEARCH_QUERY_CANDIDATES = setOf(
        "웹", "인터넷", "온라인", "검색", "검색어", "웹검색", "인터넷검색", "온라인검색",
        "정확히", "제대로", "더잘", "다른검색어", "다른검색어로", "검색어를바꿔서",
        "검색어를바꾸어서", "검색어를바꿔", "검색어를바꾸어",
    )
    private val VOLATILE_FACT_COMPACT_MARKERS = listOf(
        "최신뉴스", "최근뉴스", "오늘뉴스", "실시간뉴스", "시세", "환율", "기준금리",
        "순위", "최근실적", "최신실적", "최근분기실적", "주가", "현재가격",
    )
    private val VOLATILE_COMPOUND_MARKERS = listOf(
        " 그리고 ", " 및 ", "와 ", "과 ", ",", ";", " / ", "도 알려", "도 보여",
    )
    private val GENERIC_VOLATILE_SUBJECTS = GENERIC_KNOWLEDGE_SUBJECTS + setOf(
        "현재", "지금", "오늘", "요즘", "최신", "최근", "실시간", "가격", "시세", "환율",
        "금리", "기준금리", "순위", "실적", "주가", "뉴스",
    )
    private val MODEL_KNOWLEDGE_GAP_MARKERS = listOf(
        "가지고 있는 정보로는", "제가 아는 정보로는", "제가 알고 있는 정보로는",
        "잘 모르겠습니다", "잘 모르겠어요", "알지 못합니다", "알고 있지 않습니다",
        "정보가 부족합니다", "정보가 충분하지 않습니다",
        "i don't know", "i do not know", "i don't have enough information",
    )
    private val PUBLIC_SUBJECT_SUFFIX = Regex(
        "^(.{2,}?)(?:이란|이라는|라는)\\s*(?:사람|인물|영화|작품|책|소설|회사|기업|" +
            "감독|배우|작가|가수|선수)$",
    )
    private val SENSITIVE_VALUE_PATTERN = Regex(
        "(?i)(?:[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|" +
            "(?:\\+?82[- ]?)?0?1[016789][- ]?\\d{3,4}[- ]?\\d{4}|\\d{8,})",
    )
    private val VOLATILE_PUBLIC_FACT_REQUEST = Regex(
        "^(.{2,80}?)(?:의\\s*)?(" +
            "최근\\s*분기\\s*실적|최신\\s*뉴스|최근\\s*뉴스|오늘\\s*뉴스|실시간\\s*뉴스|" +
            "현재\\s*시세|실시간\\s*시세|현재\\s*환율|실시간\\s*환율|" +
            "현재\\s*기준\\s*금리|기준\\s*금리|현재\\s*순위|최신\\s*순위|" +
            "최근\\s*실적|최신\\s*실적|현재\\s*주가|실시간\\s*주가|현재\\s*가격|" +
            "시세|환율|순위|주가" +
            ")(?:\\s*(?:을|를|이|가|은|는)?\\s*(?:좀\\s*)?(?:" +
            "알려\\s*줘|알려\\s*주세요|보여\\s*줘|보여\\s*주세요|" +
            "확인해\\s*줘|확인해\\s*주세요|검색해\\s*줘|검색해\\s*주세요|" +
            "찾아\\s*줘|찾아\\s*주세요|뭐야|어때|얼마야|몇\\s*위야" +
            "))?$",
        RegexOption.IGNORE_CASE,
    )
    private val WHITESPACE = Regex("\\s+")
    private val QUERY_TRIM_CHARACTERS = charArrayOf(
        ' ', '\t', '\n', '\r', '.', ',', ':', '：', '!', '?', '。', '，', '！', '？',
        '"', '\'', '(', ')', '[', ']',
    )

    private const val MINIMUM_SUBJECT_CHARACTERS = 2
    private const val MAX_KNOWLEDGE_REQUEST_CHARACTERS = 500
    private const val MAX_FOLLOW_UP_CHARACTERS = 160
    private const val MAX_MODEL_ANSWER_CHARACTERS = 4_096
    private const val MAX_OFFICEHOLDER_ENTITY_CHARACTERS = 48
    private const val MAX_VOLATILE_SUBJECT_CHARACTERS = 80
    private const val MAX_CONTEXTUAL_USER_ROWS = 3
}
