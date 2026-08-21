package com.personaledge.core.tools

import java.util.Calendar

/**
 * A weekday for a repeating alarm.
 *
 * [token] is what crosses the model boundary; [calendarField] is what `AlarmClock.EXTRA_DAYS`
 * expects. Declaration order is the display and canonical order, so two requests naming the same
 * days always produce the same canonical snapshot and the same idempotency key.
 */
enum class AlarmDay(
    val token: String,
    val korean: String,
    internal val calendarField: Int,
) {
    MONDAY("mon", "월", Calendar.MONDAY),
    TUESDAY("tue", "화", Calendar.TUESDAY),
    WEDNESDAY("wed", "수", Calendar.WEDNESDAY),
    THURSDAY("thu", "목", Calendar.THURSDAY),
    FRIDAY("fri", "금", Calendar.FRIDAY),
    SATURDAY("sat", "토", Calendar.SATURDAY),
    SUNDAY("sun", "일", Calendar.SUNDAY),
    ;

    companion object {
        fun fromToken(token: String): AlarmDay? =
            entries.firstOrNull { day -> day.token == token.trim().lowercase() }
    }
}

data class AlarmRequest(
    val hour: Int,
    val minute: Int,
    val label: String?,
    /** Empty means a one-shot alarm at the next occurrence of that time. */
    val days: Set<AlarmDay>,
) {
    init {
        require(hour in 0..23)
        require(minute in 0..59)
    }
}

/** The next alarm clock scheduled on the device, whichever app set it. */
data class NextAlarm(
    val triggerAtEpochMillis: Long,
)

enum class AlarmRefusal {
    /** No installed activity handles `AlarmClock.ACTION_SET_ALARM`. */
    NO_CLOCK_APP,

    /** The platform refused the activity start, typically a background-start restriction. */
    START_BLOCKED,
}

sealed interface AlarmOutcome {
    /**
     * The request reached the clock app. It is deliberately not called "created": the
     * `ACTION_SET_ALARM` intent returns no result, so the clock app remains the only authority
     * on whether the alarm exists.
     */
    data object Delivered : AlarmOutcome

    data class Refused(val refusal: AlarmRefusal) : AlarmOutcome
}

/**
 * The device clock as the tools see it.
 *
 * Android exposes no public API to enumerate or edit alarms owned by the clock app, so this
 * interface is deliberately asymmetric: alarms can be requested and the single next alarm can be
 * read, but nothing can list or modify existing ones.
 */
interface AlarmGateway {
    /** False when nothing handles the alarm intent; the tools refuse rather than fail silently. */
    suspend fun clockAppAvailable(): Boolean

    suspend fun requestAlarm(request: AlarmRequest): AlarmOutcome

    /** Null when no alarm clock is scheduled. */
    suspend fun nextAlarm(): NextAlarm?
}
