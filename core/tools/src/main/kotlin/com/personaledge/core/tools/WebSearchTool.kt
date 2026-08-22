package com.personaledge.core.tools

data class WebSearchParams(
    val query: String,
) : ToolParams

data class WebSearchResult(
    val hits: List<WebSearchHit>,
)

/**
 * Searches the web through NAVER.
 *
 * Two things leave the device and one hostile thing comes back. The query is sent to NAVER, and
 * the results are text written by strangers that will be read into a model prompt.
 *
 * That returning text is data, never instruction, and the architecture is what guarantees it: a
 * tool result cannot invoke a tool, the model cannot call anything without the orchestrator, and
 * every side effect needs the user's confirmation against an immutable snapshot. The outbound
 * query itself also requires confirmation because it discloses user text to NAVER. A page that
 * says "book a flight" changes nothing on its own. [UntrustedText] adds the narrower guarantee
 * that such text cannot impersonate the Gemma template or render as something it is not.
 */
class WebSearchTool(
    private val gateway: WebSearchGateway,
) : AgentTool<WebSearchParams, WebSearchResult> {

    override val descriptor = ToolDescriptor(
        name = NAME,
        description = "Search the web for current information and return titles, links, and snippets",
        risk = ToolRisk.READ_ONLY,
        minimumConfirmation = ConfirmationRequirement.UserConfirmation,
        requiredCapabilities = setOf(ToolCapability.NETWORK),
    )

    override suspend fun validateAndCanonicalize(params: WebSearchParams): ValidationResult {
        val query = params.query.trim()
        if (query.isEmpty() || CalendarText.codePointLength(query) > MAX_QUERY_CHARACTERS) {
            return ValidationResult.Invalid("검색어는 1자 이상 ${MAX_QUERY_CHARACTERS}자 이하여야 합니다.")
        }
        if (!CalendarText.isSafeText(query)) {
            return ValidationResult.Invalid("검색어에 허용되지 않는 문자가 있습니다.")
        }
        if (!gateway.credentialsPresent()) {
            return ValidationResult.Invalid("설정에서 네이버 검색 키를 먼저 입력하세요.")
        }

        return ValidationResult.Valid(CanonicalFields.encode(mapOf(FIELD_QUERY to query)))
    }

    override fun preview(input: CanonicalToolInput): ActionPreview {
        val fields = CanonicalFields.decode(input)
        return ActionPreview(
            title = "웹 검색",
            summary = "\"${fields.requiredString(FIELD_QUERY)}\"를 네이버에서 검색합니다.",
        )
    }

    override suspend fun execute(
        input: CanonicalToolInput,
        permit: ExecutionPermit,
    ): WebSearchResult {
        val fields = CanonicalFields.decode(input)
        return WebSearchResult(
            hits = gateway.search(
                query = fields.requiredString(FIELD_QUERY),
                limit = MAX_HITS,
            ),
        )
    }

    companion object {
        const val NAME = "web_search"
        const val MAX_HITS = 5
        const val MAX_QUERY_CHARACTERS = 100
        internal const val FIELD_QUERY = "query"
    }
}
