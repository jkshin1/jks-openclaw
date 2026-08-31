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
        if (!looksLikeSubjectlessSearchFollowUp(normalizedFollowUp)) return null
        // A complete standalone query always wins over conversation carry-over.
        if (explicitWebSearchQueryOrNull(normalizedFollowUp) != null) return null
        return knowledgeRequestOrNull(previousUserRequest)
    }

    /** The model gets one optional web Tool only for this bounded public-knowledge grammar. */
    internal fun knowledgeRequestOrNull(request: String): TrustedWebSearchRequest? {
        val normalized = request.trim()
        if (normalized.length !in 4..MAX_KNOWLEDGE_REQUEST_CHARACTERS ||
            normalized.any(::isUnsafeCharacter) ||
            EXTERNAL_TRANSFER_BLOCK_MARKERS.any(normalized::contains) ||
            PRIVATE_CONTEXT_MARKERS.any(normalized::contains) ||
            SENSITIVE_VALUE_PATTERN.containsMatchIn(normalized)
        ) {
            return null
        }
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
    }

    /** Rejects generic condition text before it can become a literal provider query. */
    internal fun isSubjectlessQueryCandidate(query: String): Boolean {
        var key = query.compactKey()
        SUBJECTLESS_QUERY_DECORATORS.forEach { decorator -> key = key.replace(decorator, "") }
        return key in SUBJECTLESS_QUERY_CANDIDATES || key in GENERIC_KNOWLEDGE_SUBJECTS
    }

    private fun looksLikeSubjectlessSearchFollowUp(value: String): Boolean {
        if (value.length !in 2..MAX_FOLLOW_UP_CHARACTERS || value.any(::isUnsafeCharacter) ||
            EXTERNAL_TRANSFER_BLOCK_MARKERS.any(value::contains)
        ) {
            return false
        }
        if (SEARCH_REQUEST_MARKERS.none(value::contains)) return false
        val standalone = explicitWebSearchQueryOrNull(value)
        return standalone == null || isSubjectlessQueryCandidate(standalone)
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
    private val EXTERNAL_TRANSFER_BLOCK_MARKERS = listOf(
        "카카오", "카톡", "메시지 보내", "보낼 메시지", "전송해", "공유해", "답장해",
        "캘린더", "일정", "알람", "리마인더", "기온", "예보", "경로", "길찾기",
        "기억해", "저장해", "예약해", "삭제해",
        "번역", "문장을", "문구를", "따라 말", "그대로 반복",
        "비밀번호", "암호", "인증번호", "보안코드", "API 키", "api key", "토큰",
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
        "내 ", "나의 ", "저의 ", "제 ", "우리 회사", "우리 기업", "우리 학교", "우리 가족",
        "우리 팀", "사내", "미공개", "비공개", "기밀", "초안", "관람 기록", "구매 기록",
        "검색 기록", "업무 기록", "회의록",
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
    private val QUERY_TRIM_CHARACTERS = charArrayOf(
        ' ', '\t', '\n', '\r', '.', ',', ':', '：', '!', '?', '。', '，', '！', '？',
        '"', '\'', '(', ')', '[', ']',
    )

    private const val MINIMUM_SUBJECT_CHARACTERS = 2
    private const val MAX_KNOWLEDGE_REQUEST_CHARACTERS = 500
    private const val MAX_FOLLOW_UP_CHARACTERS = 160
    private const val MAX_MODEL_ANSWER_CHARACTERS = 4_096
}
