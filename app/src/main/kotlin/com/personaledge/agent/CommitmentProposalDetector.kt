package com.personaledge.agent

import com.personaledge.core.data.CapturedNotificationDraft
import com.personaledge.core.data.CommitmentProposalDraft
import com.personaledge.core.data.takeCodePoints
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Conservative, model-free proposal detector.
 *
 * It only creates an inbox candidate. Ambiguous Korean time such as "7시" is never interpreted as
 * AM or PM, and no reminder/calendar side effect occurs here.
 */
object CommitmentProposalDetector {
    /**
     * A direct Inbox entry does not need keyword classification because the user explicitly chose
     * to capture it. A unique date and time may be suggested, but this still only writes a review
     * candidate; ambiguous or date-less text stays undated and asks once in the Inbox UI.
     */
    fun draftForDirectInbox(
        text: String,
        sourceRefHash: String,
        zone: ZoneId = ZoneId.systemDefault(),
        now: ZonedDateTime = ZonedDateTime.now(zone),
    ): CommitmentProposalDraft? {
        val summary = text.trim().takeCodePoints(MAX_SUMMARY_CODE_POINTS)
        if (summary.isEmpty()) return null
        val date = resolveDate(summary, now.toLocalDate())
        val time = resolveUnambiguousTime(summary)
        val dueAt = if (date != null && time != null) {
            resolveUniqueFutureInstant(date, time, zone, now)
        } else {
            null
        }
        return CommitmentProposalDraft(
            summary = summary,
            proposedDueAtEpochMillis = dueAt,
            zoneId = dueAt?.let { zone.id },
            sourcePackage = null,
            sourceRefHash = sourceRefHash,
            confidence = if (dueAt == null) AMBIGUOUS_CONFIDENCE else RESOLVED_CONFIDENCE,
        )
    }

    fun detect(
        draft: CapturedNotificationDraft,
        zone: ZoneId = ZoneId.systemDefault(),
        now: ZonedDateTime = Instant.ofEpochMilli(draft.postedAtEpochMillis).atZone(zone),
    ): CommitmentProposalDraft? {
        val text = draft.text.trim()
        if (text.isEmpty() || !COMMITMENT_TERMS.any(text::contains)) return null
        if (!DATE_TERMS.any(text::contains) && !DATE_PATTERN.containsMatchIn(text) &&
            !TIME_PATTERN.containsMatchIn(text)
        ) {
            return null
        }

        val date = resolveDate(text, now.toLocalDate())
        val time = resolveUnambiguousTime(text)
        val dueAt = if (date != null && time != null) {
            resolveUniqueFutureInstant(date, time, zone, now)
        } else {
            null
        }
        val source = sequenceOf(draft.sender, draft.conversationTitle)
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
        val summary = buildString {
            if (source != null) append(source).append(": ")
            append(text)
        }.takeCodePoints(MAX_SUMMARY_CODE_POINTS)

        return CommitmentProposalDraft(
            summary = summary,
            proposedDueAtEpochMillis = dueAt,
            zoneId = dueAt?.let { zone.id },
            sourcePackage = draft.packageName,
            sourceRefHash = sha256(draft.sourceKey),
            confidence = if (dueAt == null) AMBIGUOUS_CONFIDENCE else RESOLVED_CONFIDENCE,
        )
    }

    private fun resolveDate(text: String, today: LocalDate): LocalDate? {
        if ("오늘" in text) return today
        if ("모레" in text) return today.plusDays(2)
        if ("내일" in text) return today.plusDays(1)

        DATE_PATTERN.find(text)?.let { match ->
            val year = match.groups[1]?.value?.toIntOrNull()
            val month = match.groups[2]?.value?.toIntOrNull() ?: return@let
            val day = match.groups[3]?.value?.toIntOrNull() ?: return@let
            return try {
                if (year != null) {
                    LocalDate.of(year, month, day)
                } else {
                    val thisYear = LocalDate.of(today.year, month, day)
                    if (thisYear < today) thisYear.plusYears(1) else thisYear
                }
            } catch (_: DateTimeException) {
                null
            }
        }

        val dayOfWeek = WEEKDAYS.entries.firstOrNull { (token, _) -> token in text }?.value
            ?: return null
        var candidate = today.with(TemporalAdjusters.nextOrSame(dayOfWeek))
        if ("다음 주" in text || "다음주" in text) {
            candidate = candidate.plusWeeks(1)
        } else if (candidate == today) {
            candidate = candidate.plusWeeks(1)
        }
        return candidate
    }

    private fun resolveUnambiguousTime(text: String): LocalTime? {
        MERIDIEM_TIME.find(text)?.let { match ->
            val meridiem = match.groupValues[1]
            val rawHour = match.groupValues[2].toIntOrNull() ?: return null
            val minute = match.groups[3]?.value?.toIntOrNull() ?: 0
            if (rawHour !in 1..12 || minute !in 0..59) return null
            val hour = when {
                meridiem == "오전" && rawHour == 12 -> 0
                meridiem == "오후" && rawHour != 12 -> rawHour + 12
                else -> rawHour
            }
            return LocalTime.of(hour, minute)
        }
        CLOCK_TIME.find(text)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            return if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
        }
        return null
    }

    private fun resolveUniqueFutureInstant(
        date: LocalDate,
        time: LocalTime,
        zone: ZoneId,
        now: ZonedDateTime,
    ): Long? {
        val local = LocalDateTime.of(date, time)
        val offsets = zone.rules.getValidOffsets(local)
        if (offsets.size != 1) return null
        val instant = local.toInstant(offsets.single())
        return instant.toEpochMilli().takeIf { it > now.toInstant().toEpochMilli() }
    }

    private fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }

    private const val MAX_SUMMARY_CODE_POINTS = 180
    private const val AMBIGUOUS_CONFIDENCE = 65
    private const val RESOLVED_CONFIDENCE = 90
    private val COMMITMENT_TERMS = setOf(
        "보자", "만나", "약속", "회의", "미팅", "보내", "제출", "예약", "가자", "할까", "까지",
    )
    private val DATE_TERMS = setOf(
        "오늘", "내일", "모레", "이번 주", "이번주", "다음 주", "다음주",
        "월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일",
    )
    private val DATE_PATTERN = Regex("(?:(\\d{4})[년./-]\\s*)?(\\d{1,2})[월./-]\\s*(\\d{1,2})일?")
    private val MERIDIEM_TIME = Regex("(오전|오후)\\s*(\\d{1,2})시(?:\\s*(\\d{1,2})분)?")
    private val CLOCK_TIME = Regex("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)")
    private val TIME_PATTERN = Regex("(?:오전|오후)?\\s*\\d{1,2}(?:시|:[0-5]\\d)")
    private val WEEKDAYS = mapOf(
        "월요일" to DayOfWeek.MONDAY,
        "화요일" to DayOfWeek.TUESDAY,
        "수요일" to DayOfWeek.WEDNESDAY,
        "목요일" to DayOfWeek.THURSDAY,
        "금요일" to DayOfWeek.FRIDAY,
        "토요일" to DayOfWeek.SATURDAY,
        "일요일" to DayOfWeek.SUNDAY,
    )
}
