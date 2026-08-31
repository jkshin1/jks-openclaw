package com.personaledge.core.agent

import com.personaledge.core.tools.AlarmNextResult
import com.personaledge.core.tools.AlarmNextTool
import com.personaledge.core.tools.CalendarQueryResult
import com.personaledge.core.tools.CalendarQueryTool
import com.personaledge.core.tools.ReminderQueryTool
import com.personaledge.core.tools.ReminderSummary
import com.personaledge.core.tools.RouteEstimateResult
import com.personaledge.core.tools.RouteEstimateTool
import com.personaledge.core.tools.WeatherResult
import com.personaledge.core.tools.WeatherTool
import com.personaledge.core.tools.WebSearchResult
import com.personaledge.core.tools.WebSearchTool
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale

/**
 * One ephemeral, typed READ_ONLY result captured only after ToolOrchestrator execution.
 *
 * Raw values intentionally have no diagnostic or persistence representation. [toString] exposes
 * only closed metadata so accidental controller logging cannot serialize a calendar title,
 * reminder, place, route, weather value, or web result.
 */
internal sealed class GroundedReadEvidence(
    val ordinal: Int,
    val toolName: String,
) {
    init {
        require(ordinal > 0)
    }

    final override fun toString(): String =
        "GroundedReadEvidence(ordinal=$ordinal, toolName=$toolName)"

    class CalendarQuery(
        ordinal: Int,
        result: CalendarQueryResult,
    ) : GroundedReadEvidence(ordinal, CalendarQueryTool.NAME) {
        internal val result = result.copy(events = result.events.toList())
    }

    class AlarmNext(
        ordinal: Int,
        internal val result: AlarmNextResult,
    ) : GroundedReadEvidence(ordinal, AlarmNextTool.NAME)

    class RouteEstimate(
        ordinal: Int,
        internal val result: RouteEstimateResult,
    ) : GroundedReadEvidence(ordinal, RouteEstimateTool.NAME)

    class ReminderQuery(
        ordinal: Int,
        reminders: List<ReminderSummary>,
    ) : GroundedReadEvidence(ordinal, ReminderQueryTool.NAME) {
        internal val reminders: List<ReminderSummary> =
            Collections.unmodifiableList(reminders.toList())
    }

    class Weather(
        ordinal: Int,
        internal val requestedLocation: String,
        internal val result: WeatherResult,
    ) : GroundedReadEvidence(ordinal, WeatherTool.NAME)

    class WebSearch(
        ordinal: Int,
        internal val query: String,
        result: WebSearchResult,
        internal val intent: WebSearchAnswerIntent = WebSearchAnswerIntent.GENERAL,
    ) : GroundedReadEvidence(ordinal, WebSearchTool.NAME) {
        internal val result = result.copy(hits = result.hits.toList())

        internal fun answerPlan(): WebSearchAnswerPlan = WebSearchAnswerPolicy.prepare(
            query = query,
            result = result,
            intent = intent,
        )
    }
}

/**
 * Turn-local evidence accumulator. The ordinary serial loop keeps its two-read default while the
 * verified AgentPlan path can opt into the shipped four-read ceiling. Evidence is sorted by
 * execution ordinal at render time, independent of callback ordering.
 */
internal class GroundedEvidenceSet(
    private val maximumEvidence: Int = DEFAULT_MAX_EVIDENCE,
) {
    private val evidenceByOrdinal = sortedMapOf<Int, GroundedReadEvidence>()

    init {
        require(maximumEvidence in 1..MAX_EVIDENCE)
    }

    val isEmpty: Boolean
        get() = evidenceByOrdinal.isEmpty()

    fun add(evidence: GroundedReadEvidence): Boolean {
        if (evidence.ordinal > maximumEvidence) return false
        if (evidenceByOrdinal.size >= maximumEvidence) return false
        if (evidenceByOrdinal.containsKey(evidence.ordinal)) return false
        evidenceByOrdinal[evidence.ordinal] = evidence
        return true
    }

    fun clear() {
        evidenceByOrdinal.clear()
    }

    fun renderTrustedAnswer(): String {
        check(evidenceByOrdinal.isNotEmpty())
        return GroundedEvidenceRenderer.render(evidenceByOrdinal.values.toList())
    }

    fun singleWebSearchOrNull(): GroundedReadEvidence.WebSearch? =
        evidenceByOrdinal.values.singleOrNull() as? GroundedReadEvidence.WebSearch

    override fun toString(): String = buildString {
        append("GroundedEvidenceSet(count=").append(evidenceByOrdinal.size).append(", ordinals=[")
        append(evidenceByOrdinal.keys.joinToString(","))
        append("])")
    }

    companion object {
        const val DEFAULT_MAX_EVIDENCE = 2
        const val MAX_EVIDENCE = 4
    }
}

/** Deterministic Kotlin renderer; model-authored prose never enters any rendering branch. */
internal object GroundedEvidenceRenderer {
    fun render(evidence: List<GroundedReadEvidence>): String {
        require(evidence.isNotEmpty())
        require(evidence.size <= GroundedEvidenceSet.MAX_EVIDENCE)
        val ordered = evidence.sortedBy(GroundedReadEvidence::ordinal)
        require(ordered.map(GroundedReadEvidence::ordinal).distinct().size == ordered.size)
        if (ordered.size == 1) return renderOne(ordered.single())
        return ordered.joinToString("\n\n") { item ->
            "조회 ${item.ordinal} · ${label(item)}\n${renderOne(item)}"
        }
    }

    private fun renderOne(evidence: GroundedReadEvidence): String = when (evidence) {
        is GroundedReadEvidence.CalendarQuery -> renderCalendar(evidence.result)
        is GroundedReadEvidence.AlarmNext -> renderAlarm(evidence.result)
        is GroundedReadEvidence.RouteEstimate -> renderRoute(evidence.result)
        is GroundedReadEvidence.ReminderQuery -> renderReminders(evidence.reminders)
        is GroundedReadEvidence.Weather -> weatherAnswerText(
            requestedLocation = evidence.requestedLocation,
            result = evidence.result,
        )
        is GroundedReadEvidence.WebSearch -> webSearchAnswerText(
            plan = evidence.answerPlan(),
        )
    }

    private fun label(evidence: GroundedReadEvidence): String = when (evidence) {
        is GroundedReadEvidence.CalendarQuery -> "일정"
        is GroundedReadEvidence.AlarmNext -> "다음 알람"
        is GroundedReadEvidence.RouteEstimate -> "이동 경로"
        is GroundedReadEvidence.ReminderQuery -> "리마인더"
        is GroundedReadEvidence.Weather -> "날씨"
        is GroundedReadEvidence.WebSearch -> "웹 검색"
    }

    private fun renderCalendar(result: CalendarQueryResult): String = buildString {
        val shown = result.events.take(MAX_RENDERED_CALENDAR_EVENTS)
        if (shown.isEmpty()) {
            if (result.truncated) {
                append("일정 결과가 더 있지만 현재 표시할 수 있는 항목은 없습니다.")
            } else {
                append("조회 범위에 일정이 없습니다.")
            }
            return@buildString
        }
        append("일정 조회 결과입니다.")
        shown.forEachIndexed { index, event ->
            append("\n").append(index + 1).append(". ").append(event.title)
            append("\n   ").append(event.start).append(" ~ ").append(event.end)
            if (event.allDay) append(" (종일)")
            event.location?.takeIf(String::isNotBlank)?.let { location ->
                append("\n   장소: ").append(location)
            }
            append("\n   캘린더: ").append(event.calendar)
            append(" · 이벤트 ID: ").append(event.eventId)
        }
        if (result.truncated || result.events.size > shown.size) {
            append("\n일정 결과가 더 있어 일부만 표시했습니다.")
        }
    }

    private fun renderAlarm(result: AlarmNextResult): String = when {
        !result.hasAlarm -> "기기에 예정된 다음 알람이 없습니다."
        result.triggerAt == null -> "다음 알람은 있지만 예정 시각을 확인하지 못했습니다."
        else -> "기기에 예정된 다음 알람은 ${result.triggerAt}입니다. " +
            "Android 공개 API에서는 알람 이름과 반복 여부를 확인할 수 없습니다."
    }

    private fun renderRoute(result: RouteEstimateResult): String =
        "확인된 자동차 경로는 ${result.origin} → ${result.destination}이며, " +
            "예상 시간은 ${result.durationMinutes}분, 거리는 ${result.distanceKilometres}km입니다."

    private fun renderReminders(reminders: List<ReminderSummary>): String = buildString {
        val shown = reminders.take(MAX_RENDERED_REMINDERS)
        if (shown.isEmpty()) {
            append("예정된 앱 리마인더가 없습니다.")
            return@buildString
        }
        append("예정된 앱 리마인더입니다.")
        shown.forEachIndexed { index, reminder ->
            append("\n").append(index + 1).append(". ").append(reminder.title)
            append("\n   시각: ").append(reminderTriggerText(reminder))
            reminder.recurrenceRule?.let { recurrence ->
                append("\n   반복: ").append(recurrence)
            }
            append("\n   정밀도: ").append(reminder.precision.name.lowercase(Locale.ROOT))
            append(" · ID: ").append(reminder.reminderId)
            append(" · 버전: ").append(reminder.scheduleVersion)
        }
        if (reminders.size > shown.size) {
            append("\n리마인더 결과가 더 있어 일부만 표시했습니다.")
        }
    }

    private fun reminderTriggerText(reminder: ReminderSummary): String = runCatching {
        REMINDER_DATE_TIME_FORMATTER.format(
            Instant.ofEpochMilli(reminder.triggerAtEpochMillis).atZone(ZoneId.of(reminder.zoneId)),
        )
    }.getOrElse {
        "${reminder.triggerAtEpochMillis}ms (${reminder.zoneId})"
    }

    private const val MAX_RENDERED_CALENDAR_EVENTS = 8
    private const val MAX_RENDERED_REMINDERS = 8
    private val REMINDER_DATE_TIME_FORMATTER =
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm VV", Locale.ROOT)
}
