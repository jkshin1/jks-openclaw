package com.personaledge.core.tools

import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Shared validation for every calendar tool.
 *
 * Local wall-clock text is the only accepted time format: the model cannot reliably produce epoch
 * milliseconds, and an offset it invented would silently move an appointment across time zones.
 * Conversion uses the device zone at validation time, and the resulting instant is what both the
 * confirmation preview and the write use.
 */
internal object CalendarText {
    /** `2026-08-21T14:30`, minute precision, no zone. Seconds would imply accuracy we do not have. */
    private val LOCAL_DATE_TIME = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""")
    private val DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    const val MAX_TITLE_CHARACTERS = 120
    const val MAX_LOCATION_CHARACTERS = 200
    const val MIN_DURATION_MILLIS = 60_000L
    const val MAX_DURATION_MILLIS = 30L * 24 * 60 * 60 * 1_000
    const val MAX_QUERY_WINDOW_MILLIS = 60L * 24 * 60 * 60 * 1_000
    const val MAX_PAST_MILLIS = 365L * 24 * 60 * 60 * 1_000
    const val MAX_FUTURE_MILLIS = 730L * 24 * 60 * 60 * 1_000

    private const val MODEL_CONTROL_TOKEN_OPEN = "<|"
    private const val MODEL_CONTROL_TOKEN_CLOSE = "|>"

    fun parseLocalDateTime(value: String, zone: ZoneId): Long? {
        val trimmed = value.trim()
        if (!LOCAL_DATE_TIME.matches(trimmed)) return null
        return try {
            val local = LocalDateTime.parse(trimmed)
            val offsets = zone.rules.getValidOffsets(local)
            if (offsets.size != 1) return null
            local.toInstant(offsets.single()).toEpochMilli()
        } catch (_: DateTimeException) {
            null
        }
    }

    fun formatLocalDateTime(epochMillis: Long, zone: ZoneId): String = Instant
        .ofEpochMilli(epochMillis)
        .atZone(zone)
        .format(DISPLAY_FORMAT)

    /** Keeps every tool inside one plausible window, so a mistyped year cannot reach the provider. */
    fun isWithinSupportedRange(epochMillis: Long, now: Long): Boolean =
        epochMillis in (now - MAX_PAST_MILLIS)..(now + MAX_FUTURE_MILLIS)

    /**
     * Rejects text that could break out of the Gemma tool-response template or hide its real
     * content. Applied to model-supplied fields before they are stored, previewed, or written.
     */
    fun isSafeText(value: String): Boolean = !value.contains(MODEL_CONTROL_TOKEN_OPEN) &&
        !value.contains(MODEL_CONTROL_TOKEN_CLOSE) &&
        value.none(Char::isISOControl) &&
        value.codePoints().noneMatch { codePoint ->
            when (Character.getType(codePoint)) {
                Character.FORMAT.toInt(),
                Character.LINE_SEPARATOR.toInt(),
                Character.PARAGRAPH_SEPARATOR.toInt(),
                -> true
                else -> false
            }
        }

    /**
     * Calendar text comes from the device, not the model, but it still enters a model template.
     * Strip anything that could act as a delimiter and cap the length.
     */
    fun sanitizeForModel(value: String, maximumCharacters: Int): String {
        require(maximumCharacters >= 0)
        val withoutInvisibleText = buildString(value.length) {
            var index = 0
            while (index < value.length) {
                val codePoint = value.codePointAt(index)
                index += Character.charCount(codePoint)
                val type = Character.getType(codePoint)
                val unsafe = Character.isISOControl(codePoint) || when (type) {
                    Character.FORMAT.toInt(),
                    Character.LINE_SEPARATOR.toInt(),
                    Character.PARAGRAPH_SEPARATOR.toInt(),
                    -> true
                    else -> false
                }
                when {
                    !unsafe -> appendCodePoint(codePoint)
                    Character.isWhitespace(codePoint) || Character.isISOControl(codePoint) -> append(' ')
                    else -> Unit
                }
            }
        }
        // Delimiter replacement must follow unsafe-character removal. Otherwise an input such as
        // `<\u202E|tool|\u202E>` would acquire complete delimiters only after the filter had run.
        val safe = withoutInvisibleText
            .replace(MODEL_CONTROL_TOKEN_OPEN, " ")
            .replace(MODEL_CONTROL_TOKEN_CLOSE, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (safe.codePointCount(0, safe.length) <= maximumCharacters) return safe
        return safe.substring(0, safe.offsetByCodePoints(0, maximumCharacters))
    }

    fun codePointLength(value: String): Int = value.codePointCount(0, value.length)

    /**
     * Identity of an event as it was when the user confirmed a change. Re-checked immediately
     * before the write so a background sync that moved the event aborts the update instead of
     * overwriting something the user never saw.
     */
    fun eventDigest(event: CalendarEvent): String = sha256(
        listOf(
            event.eventId,
            event.calendarId,
            event.title,
            event.startEpochMillis,
            event.endEpochMillis,
            event.allDay,
            event.recurring,
            event.location.orEmpty(),
            // NUL separator: it cannot appear in a calendar title or location, so no field can
            // impersonate another by embedding the delimiter. Written as an escape because a raw
            // NUL in source is invisible in every editor and diff.
        ).joinToString("\u0000"),
    )

    private fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte) }
}
