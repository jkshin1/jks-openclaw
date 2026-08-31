package com.personaledge.agent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.personaledge.core.data.AgentSettings
import com.personaledge.core.data.ReminderEntity
import com.personaledge.core.data.takeCodePoints
import com.personaledge.core.tools.CalendarEvent
import java.time.Instant
import java.time.ZoneId
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.util.Locale

data class TodayBriefItem(
    val timeText: String,
    val title: String,
    val location: String?,
)

data class TodayBrief(
    val events: List<TodayBriefItem> = emptyList(),
    val totalEventCount: Int = events.size,
    val nextEvent: TodayBriefItem? = null,
    val nextEventCountdownText: String? = null,
    val dueReminderCount: Int = 0,
    val overdueReminderCount: Int = 0,
    val yesterdaySnoozedCount: Int = 0,
    val conflictCount: Int = 0,
    val leaveByText: String? = null,
    val routeStatus: String? = null,
    val leaveByPlan: LeaveByPlan? = null,
    val calendarAvailable: Boolean = true,
) {
    fun notificationText(): String = buildList {
        add("오늘 일정 ${totalEventCount}개")
        nextEvent?.let { event ->
            add(
                listOfNotNull(
                    "${event.timeText} ${event.title}",
                    nextEventCountdownText,
                ).joinToString(" · "),
            )
        }
        if (dueReminderCount > 0) add("오늘 리마인더 ${dueReminderCount}개")
        if (overdueReminderCount > 0) add("미완료·지연 ${overdueReminderCount}개")
        if (yesterdaySnoozedCount > 0) add("어제 미룬 항목 ${yesterdaySnoozedCount}개")
        if (conflictCount > 0) add("겹치는 일정 ${conflictCount}쌍")
        leaveByText?.let(::add)
    }.joinToString(" · ")
}

/** Structured calendar-owned fact that may be projected into one app reminder after opt-in. */
data class LeaveByPlan(
    val calendarId: Long,
    val eventId: Long,
    val eventTitle: String,
    val eventStartEpochMillis: Long,
    val leaveAtEpochMillis: Long,
    val zoneId: String,
    val usedFallback: Boolean,
)

private data class LeaveByComputation(
    val text: String?,
    val status: String?,
    val plan: LeaveByPlan?,
)

/** Calendar, reminder and route facts are composed without loading or consulting the LLM. */
class TodayBriefEngine(
    private val container: AppContainer,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zoneProvider: () -> ZoneId = ZoneId::systemDefault,
) {
    suspend fun compose(providedSettings: AgentSettings? = null): TodayBrief {
        val settings = providedSettings ?: container.settings.current()
        val zone = zoneProvider()
        val now = clock()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val calendarResult = runCatching {
            container.scopedCalendar.queryEvents(start, end, MAX_CALENDAR_EVENTS)
        }
        val events = calendarResult.getOrDefault(emptyList()).sortedBy(CalendarEvent::startEpochMillis)
        val reminders = runCatching { container.reminders.active() }.getOrDefault(emptyList())
        val dueToday = reminders.filter { it.effectiveTriggerAtEpochMillis in start until end }
        val overdue = reminders.filter { it.effectiveTriggerAtEpochMillis < now }
        val yesterdayStart = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val yesterdaySnoozed = runCatching {
            container.reminders.countActiveSnoozedBetween(yesterdayStart, start)
        }.getOrDefault(0)
        val leaveBy = routePlan(events, now, zone)
        val visibleEvents = events.take(MAX_VISIBLE_EVENTS).map { event -> event.toBriefItem(zone) }
        val nextRawEvent = events.firstOrNull { event -> event.startEpochMillis > now }
        return TodayBrief(
            events = visibleEvents,
            totalEventCount = events.size,
            nextEvent = nextRawEvent?.toBriefItem(zone),
            nextEventCountdownText = nextRawEvent?.let { event ->
                countdownText(event.startEpochMillis - now)
            },
            dueReminderCount = dueToday.size,
            overdueReminderCount = overdue.size,
            yesterdaySnoozedCount = yesterdaySnoozed,
            conflictCount = conflictCount(events),
            leaveByText = leaveBy?.text,
            routeStatus = leaveBy?.status,
            leaveByPlan = leaveBy?.plan,
            calendarAvailable = calendarResult.isSuccess,
        )
    }

    private suspend fun routePlan(
        events: List<CalendarEvent>,
        now: Long,
        zone: ZoneId,
    ): LeaveByComputation? {
        val event = events.firstOrNull { event ->
            event.startEpochMillis > now && safePlace(event.location) != null
        } ?: return null
        val destination = safePlace(event.location) ?: return null
        val current = runCatching { container.settings.current() }.getOrNull() ?: return null
        if (
            !container.ownerConsentInterlock.allowed(
                OwnerConsentFeature.PROACTIVE_ROUTE_PLANNING,
                current.proactiveRoutePlanningEnabled,
            )
        ) return null
        if (
            !container.ownerConsentInterlock.allowed(
                OwnerConsentFeature.ROUTE_LOOKUP,
                current.routeLookupEnabled,
            )
        ) {
            return LeaveByComputation(null, "경로 조회 동의가 꺼져 있습니다.", null)
        }
        val origin = current.defaultOriginLabel ?: return fallbackPlan(
            event = event,
            zone = zone,
            now = now,
            status = "기본 출발지가 없어 고정 안전 버퍼를 적용했습니다.",
        )
        val estimate = runCatching {
            if (!container.routes.credentialsPresent()) error("credentials")
            val executionSettings = container.settings.current()
            check(
                container.ownerConsentInterlock.allowed(
                    OwnerConsentFeature.PROACTIVE_ROUTE_PLANNING,
                    executionSettings.proactiveRoutePlanningEnabled,
                ) && container.ownerConsentInterlock.allowed(
                    OwnerConsentFeature.ROUTE_LOOKUP,
                    executionSettings.routeLookupEnabled,
                ),
            ) { "owner consent changed" }
            container.routes.estimate(origin, destination)
        }.getOrElse {
            val latest = runCatching { container.settings.current() }.getOrNull()
            if (
                latest == null ||
                !container.ownerConsentInterlock.allowed(
                    OwnerConsentFeature.PROACTIVE_ROUTE_PLANNING,
                    latest.proactiveRoutePlanningEnabled,
                ) ||
                !container.ownerConsentInterlock.allowed(
                    OwnerConsentFeature.ROUTE_LOOKUP,
                    latest.routeLookupEnabled,
                )
            ) return null
            return fallbackPlan(
                event = event,
                zone = zone,
                now = now,
                status = "실시간 교통을 확인하지 못해 고정 안전 버퍼를 적용했습니다.",
            )
        }
        val leaveAt = LeaveByPlanner.recommendedDeparture(
            eventStartEpochMillis = event.startEpochMillis,
            travelMinutes = estimate.durationMinutes,
        )
        return LeaveByComputation(
            text = departureText(leaveAt, now, zone, "${estimate.durationMinutes}분"),
            status = null,
            plan = event.toLeaveByPlan(leaveAt, zone, usedFallback = false),
        )
    }

    private fun fallbackPlan(
        event: CalendarEvent,
        zone: ZoneId,
        now: Long,
        status: String,
    ): LeaveByComputation {
        val leaveAt = LeaveByPlanner.fallbackDeparture(event.startEpochMillis)
        return LeaveByComputation(
            text = departureText(
                leaveAt,
                now,
                zone,
                "안전 버퍼 ${LeaveByPlanner.FALLBACK_TOTAL_BUFFER_MINUTES}분",
            ),
            status = status,
            plan = event.toLeaveByPlan(leaveAt, zone, usedFallback = true),
        )
    }

    private fun departureText(leaveAt: Long, now: Long, zone: ZoneId, detail: String): String =
        if (leaveAt <= now) {
            "지금 출발 권장 · $detail"
        } else {
            "${Instant.ofEpochMilli(leaveAt).atZone(zone).format(TIME_FORMAT)} 출발 권장 · $detail"
        }

    private fun CalendarEvent.toLeaveByPlan(
        leaveAt: Long,
        zone: ZoneId,
        usedFallback: Boolean,
    ): LeaveByPlan = LeaveByPlan(
        calendarId = calendarId,
        eventId = eventId,
        eventTitle = sanitize(title, 80).ifEmpty { "일정" },
        eventStartEpochMillis = startEpochMillis,
        leaveAtEpochMillis = leaveAt,
        zoneId = zone.id,
        usedFallback = usedFallback,
    )

    private fun CalendarEvent.toBriefItem(zone: ZoneId): TodayBriefItem = TodayBriefItem(
        timeText = Instant.ofEpochMilli(startEpochMillis).atZone(zone).format(TIME_FORMAT),
        title = sanitize(title, 80).ifEmpty { "(제목 없음)" },
        location = location?.let { sanitize(it, 80) }?.takeIf(String::isNotEmpty),
    )

    private fun countdownText(remainingMillis: Long): String {
        val minutes = ((remainingMillis.coerceAtLeast(1) + 59_999L) / 60_000L).coerceAtLeast(1)
        val hours = minutes / 60
        val remainder = minutes % 60
        return when {
            hours == 0L -> "${minutes}분 후"
            remainder == 0L -> "${hours}시간 후"
            else -> "${hours}시간 ${remainder}분 후"
        }
    }

    private fun conflictCount(events: List<CalendarEvent>): Int {
        var conflicts = 0
        events.indices.forEach { left ->
            ((left + 1) until events.size).forEach { right ->
                if (events[left].startEpochMillis < events[right].endEpochMillis &&
                    events[left].endEpochMillis > events[right].startEpochMillis
                ) conflicts++
            }
        }
        return conflicts
    }

    private fun safePlace(value: String?): String? {
        val sanitized = value?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (sanitized.codePointCount(0, sanitized.length) !in 1..80) return null
        if (sanitized.contains("<|") || sanitized.contains("|>")) return null
        if (sanitized.codePoints().anyMatch { codePoint ->
                Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT.toInt()
            }
        ) return null
        return sanitized
    }

    private fun sanitize(value: String, maximumCodePoints: Int): String = value
        .replace(Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]+"), " ")
        .replace("<|", " ")
        .replace("|>", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .takeCodePoints(maximumCodePoints)

    companion object {
        private const val MAX_CALENDAR_EVENTS = 100
        private const val MAX_VISIBLE_EVENTS = 8
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    }
}

object LeaveByPlanner {
    private const val DEFAULT_PREPARATION_MINUTES = 15
    const val FALLBACK_TOTAL_BUFFER_MINUTES = 60

    fun recommendedDeparture(
        eventStartEpochMillis: Long,
        travelMinutes: Int,
        preparationMinutes: Int = DEFAULT_PREPARATION_MINUTES,
    ): Long {
        require(travelMinutes in 0..24 * 60)
        require(preparationMinutes in 0..24 * 60)
        return eventStartEpochMillis - (travelMinutes + preparationMinutes).toLong() * 60_000
    }

    /** Conservative deterministic fallback; it never reuses a stale traffic estimate. */
    fun fallbackDeparture(
        eventStartEpochMillis: Long,
        totalBufferMinutes: Int = FALLBACK_TOTAL_BUFFER_MINUTES,
    ): Long {
        require(totalBufferMinutes in 1..24 * 60)
        return eventStartEpochMillis - totalBufferMinutes.toLong() * 60_000
    }
}

class DailyBriefWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = runCatching {
        val container = applicationContext.appContainer()
        val settings = container.settings.current()
        val today = java.time.LocalDate.now()
        val weekendBlocked = !settings.weekendBriefEnabled &&
            today.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        if (!container.ownerConsentInterlock.allowed(
                OwnerConsentFeature.DAILY_BRIEF,
                settings.dailyBriefEnabled,
            ) || weekendBlocked ||
            !NotificationPermissionPolicy.isGranted(applicationContext) ||
            !NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()
        ) return Result.success()

        val brief = TodayBriefEngine(container).compose(settings)
        val content = brief.notificationText().ifBlank { "오늘 예정된 일정과 리마인더가 없습니다." }
        val executionSettings = container.settings.current()
        if (!container.ownerConsentInterlock.allowed(
                OwnerConsentFeature.DAILY_BRIEF,
                executionSettings.dailyBriefEnabled,
            )
        ) return Result.success()
        try {
            NotificationManagerCompat.from(applicationContext).notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_calendar)
                    .setContentTitle("오늘 브리핑")
                    .setContentText(content)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setAutoCancel(true)
                    .setContentIntent(
                        PendingIntent.getActivity(
                            applicationContext,
                            0,
                            Intent(applicationContext, MainActivity::class.java)
                                .setAction("com.personaledge.agent.OPEN_TODAY_BRIEF"),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                    .build(),
            )
            Result.success()
        } catch (_: SecurityException) {
            // Permission can be revoked after the check. This is user state, not retryable work.
            Result.success()
        }
    }.getOrElse { Result.retry() }

    companion object {
        const val CHANNEL_ID = "personal_edge_today_brief_v1"
        private const val NOTIFICATION_ID = 0x504542

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "오늘 브리핑",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "사용자가 켠 모델 없는 일정·리마인더 아침 요약"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                },
            )
        }
    }
}
