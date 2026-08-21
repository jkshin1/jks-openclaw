package com.personaledge.core.tools

import java.time.ZoneId

data class CapturedMessageSummary(
    val conversation: String,
    val sender: String?,
    val text: String,
    val receivedAt: String,
)

/** Read-only view of what the notification listener captured. */
interface NotificationGateway {
    suspend fun search(
        query: String?,
        postedAtOrAfter: Long,
        limit: Int,
    ): List<CapturedMessageSummary>
}

data class NotificationSearchParams(
    val query: String?,
    val withinDays: String?,
) : ToolParams

data class NotificationSearchResult(
    val messages: List<CapturedMessageSummary>,
    val truncated: Boolean,
)

/**
 * Searches previously captured KakaoTalk message notifications.
 *
 * This reads a local cache of notifications, not KakaoTalk itself. It therefore sees only what
 * arrived as a notification while capture was enabled — not chat history, not muted rooms, and
 * nothing from before the feature was turned on. The tool description says so, so the model does
 * not present an empty result as "you have no messages".
 *
 * READ_ONLY is truthful: nothing is written and no message is ever sent. Sending a KakaoTalk
 * message remains out of scope entirely.
 */
class NotificationSearchTool(
    private val gateway: NotificationGateway,
    private val zoneProvider: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis,
) : AgentTool<NotificationSearchParams, NotificationSearchResult> {

    override val descriptor = ToolDescriptor(
        name = NAME,
        description = "Search KakaoTalk message notifications captured on this device",
        risk = ToolRisk.READ_ONLY,
        requiredCapabilities = setOf(ToolCapability.READ_NOTIFICATIONS),
    )

    override suspend fun validateAndCanonicalize(
        params: NotificationSearchParams,
    ): ValidationResult {
        val query = params.query?.trim().orEmpty()
        if (query.isNotEmpty()) {
            if (CalendarText.codePointLength(query) > MAX_QUERY_CHARACTERS) {
                return ValidationResult.Invalid("검색어는 60자 이하여야 합니다.")
            }
            if (!CalendarText.isSafeText(query)) {
                return ValidationResult.Invalid("검색어에 허용되지 않는 문자가 있습니다.")
            }
        }

        val withinDays = when (val raw = params.withinDays?.trim()) {
            null, "" -> DEFAULT_WITHIN_DAYS
            else -> raw.toIntOrNull()?.takeIf { days -> days in 1..MAX_WITHIN_DAYS }
                ?: return ValidationResult.Invalid("기간은 1일에서 ${MAX_WITHIN_DAYS}일 사이여야 합니다.")
        }

        return ValidationResult.Valid(
            CanonicalFields.encode(
                buildMap {
                    put(FIELD_WITHIN_DAYS, withinDays.toString())
                    if (query.isNotEmpty()) put(FIELD_QUERY, query)
                },
            ),
        )
    }

    override fun preview(input: CanonicalToolInput): ActionPreview {
        val fields = CanonicalFields.decode(input)
        val query = fields[FIELD_QUERY]
        return ActionPreview(
            title = "알림 검색",
            summary = if (query == null) {
                "최근 ${fields.requiredLong(FIELD_WITHIN_DAYS)}일 간 수집된 카카오톡 알림을 읽습니다."
            } else {
                "최근 ${fields.requiredLong(FIELD_WITHIN_DAYS)}일 간 알림에서 \"$query\"를 찾습니다."
            },
        )
    }

    override suspend fun execute(
        input: CanonicalToolInput,
        permit: ExecutionPermit,
    ): NotificationSearchResult {
        val fields = CanonicalFields.decode(input)
        val since = clock() - fields.requiredLong(FIELD_WITHIN_DAYS) * MILLIS_PER_DAY

        // One extra row separates "exactly at the cap" from "there is more".
        val found = gateway.search(
            query = fields[FIELD_QUERY],
            postedAtOrAfter = since,
            limit = MAX_MESSAGES + 1,
        )

        return NotificationSearchResult(
            messages = found.take(MAX_MESSAGES),
            truncated = found.size > MAX_MESSAGES,
        )
    }

    companion object {
        const val NAME = "kakao_notification_search"
        const val MAX_MESSAGES = 15
        const val MAX_QUERY_CHARACTERS = 60
        const val MAX_WITHIN_DAYS = 30
        const val DEFAULT_WITHIN_DAYS = 3
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1_000
        internal const val FIELD_QUERY = "query"
        internal const val FIELD_WITHIN_DAYS = "within_days"
    }
}
