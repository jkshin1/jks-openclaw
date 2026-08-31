package com.personaledge.agent

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.personaledge.core.data.ReminderDeliveryOutcome
import com.personaledge.core.data.AgentSettings
import com.personaledge.core.data.ReminderEntity
import com.personaledge.core.data.ReminderPrecision
import com.personaledge.core.data.ReminderRepository
import com.personaledge.core.data.ReminderScheduleState
import com.personaledge.core.data.ReminderSourceType
import com.personaledge.core.data.ReminderState
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.time.ZonedDateTime
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Keeps Room as the source of truth while treating AlarmManager and WorkManager as projections. */
class ReminderScheduler(
    context: Context,
    private val repository: ReminderRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val settingsProvider: suspend () -> AgentSettings = { AgentSettings() },
) {
    private val applicationContext = context.applicationContext
    private val alarmManager = applicationContext.getSystemService(AlarmManager::class.java)
    private val workManager = WorkManager.getInstance(applicationContext)

    suspend fun reconcileAll() {
        repository.active().forEach { reminder ->
            if (reminder.scheduleState != ReminderScheduleState.DELIVERED) schedule(reminder)
        }
    }

    suspend fun schedule(reminder: ReminderEntity) {
        if (reminder.state != ReminderState.ACTIVE ||
            reminder.scheduleState == ReminderScheduleState.DELIVERED
        ) {
            cancel(reminder.id)
            return
        }

        cancel(reminder.id)
        if (!NotificationPermissionPolicy.canPost(applicationContext)) {
            repository.markScheduled(
                reminder.id,
                reminder.scheduleVersion,
                ReminderScheduleState.BLOCKED_NOTIFICATION_PERMISSION,
            )
            return
        }

        val requestedDeliveryAt = ReminderDeliveryTimePolicy.requestedAt(
            triggerAtEpochMillis = reminder.triggerAtEpochMillis,
            snoozeUntilEpochMillis = reminder.snoozeUntilEpochMillis,
            leadTimeMinutes = reminder.leadTimeMinutes,
        )
        val deliveryAt = if (reminder.precision == ReminderPrecision.FLEXIBLE) {
            val settings = runCatching { settingsProvider() }.getOrNull()
            QuietHoursPolicy.adjustIfNeeded(
                requestedDeliveryAt,
                ZoneId.of(reminder.zoneId),
                settings?.quietHoursEnabled == true,
            )
        } else {
            requestedDeliveryAt
        }
        val delay = (deliveryAt - clock()).coerceAtLeast(0)
        val scheduledState = if (reminder.precision == ReminderPrecision.EXACT && canScheduleExact()) {
            try {
                checkNotNull(alarmManager).setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    maxOf(deliveryAt, clock() + MIN_EXACT_DELAY_MILLIS),
                    alarmIntent(reminder.id, reminder.scheduleVersion),
                )
                ReminderScheduleState.SCHEDULED_EXACT
            } catch (_: SecurityException) {
                enqueueDeliveryWork(reminder, delay)
                ReminderScheduleState.DEGRADED_TO_INEXACT
            }
        } else {
            enqueueDeliveryWork(reminder, delay)
            if (reminder.precision == ReminderPrecision.EXACT) {
                ReminderScheduleState.DEGRADED_TO_INEXACT
            } else {
                ReminderScheduleState.SCHEDULED_INEXACT
            }
        }

        if (!repository.markScheduled(reminder.id, reminder.scheduleVersion, scheduledState)) {
            cancel(reminder.id)
        }
    }

    fun cancel(reminderId: String) {
        workManager.cancelUniqueWork(workName(reminderId))
        alarmManager?.cancel(alarmIntent(reminderId, 0))
    }

    fun exactAlarmAvailable(): Boolean = canScheduleExact()

    fun requestReconcile() = ReminderWorkBootstrap.enqueueReconcile(applicationContext)

    private fun enqueueDeliveryWork(reminder: ReminderEntity, delayMillis: Long) {
        val request = OneTimeWorkRequestBuilder<ReminderDeliveryWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(reminderData(reminder.id, reminder.scheduleVersion))
            .addTag(DELIVERY_TAG)
            .build()
        workManager.enqueueUniqueWork(
            workName(reminder.id),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun alarmIntent(reminderId: String, scheduleVersion: Long): PendingIntent {
        val intent = Intent(applicationContext, ReminderAlarmReceiver::class.java)
            .setData(reminderUri(reminderId))
            .putExtra(EXTRA_REMINDER_ID, reminderId)
            .putExtra(EXTRA_SCHEDULE_VERSION, scheduleVersion)
        return PendingIntent.getBroadcast(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canScheduleExact(): Boolean = alarmManager?.canScheduleExactAlarms() == true

    companion object {
        internal const val EXTRA_REMINDER_ID = "reminder_id"
        internal const val EXTRA_SCHEDULE_VERSION = "schedule_version"
        internal const val DELIVERY_TAG = "personal-edge-reminder-delivery"
        private const val MIN_EXACT_DELAY_MILLIS = 1_000L

        internal fun workName(reminderId: String): String = "reminder-delivery-$reminderId"

        internal fun reminderData(reminderId: String, version: Long): Data = Data.Builder()
            .putString(EXTRA_REMINDER_ID, reminderId)
            .putLong(EXTRA_SCHEDULE_VERSION, version)
            .build()

        private fun reminderUri(reminderId: String): Uri = Uri.Builder()
            .scheme("personaledge")
            .authority("reminder")
            .appendPath(reminderId)
            .build()
    }
}

internal object ReminderDeliveryTimePolicy {
    private const val MILLIS_PER_MINUTE = 60_000L

    /** A snooze is already the requested delivery instant, so lead time is not applied twice. */
    fun requestedAt(
        triggerAtEpochMillis: Long,
        snoozeUntilEpochMillis: Long?,
        leadTimeMinutes: Int?,
    ): Long = snoozeUntilEpochMillis ?: triggerAtEpochMillis -
        (leadTimeMinutes ?: 0).toLong() * MILLIS_PER_MINUTE
}

internal data class ReminderNotificationAddress(
    val tag: String,
    val id: Int,
)

/**
 * Tagged addresses make the full reminder id authoritative. The injectable legacy function exists
 * only to model pre-rc11 hash collisions in tests; migration cleanup never cancels by that hash.
 */
internal class ReminderNotificationAddressPolicy(
    private val legacyIdFactory: (String) -> Int = ::legacyReminderNotificationId,
) {
    internal fun current(reminderId: String): ReminderNotificationAddress {
        require(reminderId.isNotBlank())
        return ReminderNotificationAddress(tag = reminderId, id = TAGGED_NOTIFICATION_ID)
    }

    internal fun legacyId(reminderId: String): Int {
        require(reminderId.isNotBlank())
        return legacyIdFactory(reminderId)
    }

    companion object {
        const val TAGGED_NOTIFICATION_ID = 0
    }
}

internal fun legacyReminderNotificationId(reminderId: String): Int {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest(reminderId.toByteArray(Charsets.UTF_8))
    return ByteBuffer.wrap(bytes).int and Int.MAX_VALUE
}

class ReminderDeliveryCoordinator internal constructor(
    private val context: Context,
    private val repository: ReminderRepository,
    private val scheduler: ReminderScheduler,
    private val notificationAddressPolicy: ReminderNotificationAddressPolicy,
) {
    constructor(
        context: Context,
        repository: ReminderRepository,
        scheduler: ReminderScheduler,
    ) : this(context, repository, scheduler, ReminderNotificationAddressPolicy())

    suspend fun deliver(reminderId: String, scheduleVersion: Long) {
        val reminder = repository.find(reminderId) ?: return
        if (reminder.state != ReminderState.ACTIVE || reminder.scheduleVersion != scheduleVersion) return
        if (!NotificationPermissionPolicy.canPost(context)) {
            repository.recordDeliveryFailure(
                reminderId,
                scheduleVersion,
                ReminderDeliveryOutcome.NOTIFICATION_PERMISSION_BLOCKED,
                ReminderScheduleState.BLOCKED_NOTIFICATION_PERMISSION,
            )
            return
        }

        val address = notificationAddressPolicy.current(reminder.id)
        val posted = try {
            NotificationManagerCompat.from(context).notify(
                address.tag,
                address.id,
                reminderNotification(reminder),
            )
            true
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
        if (!posted) {
            repository.recordDeliveryFailure(
                reminderId,
                scheduleVersion,
                ReminderDeliveryOutcome.FAILED,
                ReminderScheduleState.DELIVERY_FAILED,
            )
            return
        }

        repository.afterNotificationPosted(reminderId, scheduleVersion)?.let { updated ->
            if (updated.scheduleState == ReminderScheduleState.PENDING) scheduler.schedule(updated)
        }
    }

    private fun reminderNotification(reminder: ReminderEntity) = NotificationCompat.Builder(
        context,
        ReminderNotifications.CHANNEL_ID,
    )
        .setSmallIcon(R.drawable.ic_bell)
        .setContentTitle("Personal Edge 리마인더")
        .setContentText(reminder.title)
        .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.title))
        .setSubText(
            "출처 · ${ReminderSourcePresentation.label(reminder.sourceType)} · 본문을 눌러 보기",
        )
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(openAppIntent(reminder))
        .apply {
            // A recurring row advances to its next occurrence immediately after delivery. Its
            // old notification must not offer actions that would cancel or snooze that next row.
            if (reminder.recurrenceRule == null) {
                val actionVersion = reminder.scheduleVersion +
                    if (reminder.escalationPolicy == "until_completed") 1 else 0
                val offerTomorrowMorning = ReminderSnoozePolicy.shouldOfferTomorrowMorning(
                    System.currentTimeMillis(),
                    ZoneId.of(reminder.zoneId),
                )
                addAction(
                    0,
                    "완료",
                    actionIntent(reminder, actionVersion, ReminderActionReceiver.ACTION_COMPLETE, 0),
                )
                addAction(
                    0,
                    "10분 후",
                    actionIntent(reminder, actionVersion, ReminderActionReceiver.ACTION_SNOOZE, 10),
                )
                if (reminder.sourceType == ReminderSourceType.CALENDAR) {
                    // Android exposes at most three useful action buttons on this surface. For a
                    // leave-by reminder, opening the owning schedule outranks a second snooze.
                    addAction(0, "일정 열기", openCalendarIntent(reminder))
                } else {
                    addAction(
                        0,
                        if (offerTomorrowMorning) "내일 아침" else "1시간 후",
                        if (offerTomorrowMorning) {
                            actionIntent(
                                reminder,
                                actionVersion,
                                ReminderActionReceiver.ACTION_SNOOZE_TOMORROW_MORNING,
                                0,
                            )
                        } else {
                            actionIntent(
                                reminder,
                                actionVersion,
                                ReminderActionReceiver.ACTION_SNOOZE,
                                60,
                            )
                        },
                    )
                }
            }
        }
        .build()

    private fun openAppIntent(reminder: ReminderEntity): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .setData("personaledge://reminder/open/${reminder.id}".toUri()),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openCalendarIntent(reminder: ReminderEntity): PendingIntent {
        val uri = CalendarContract.CONTENT_URI.buildUpon()
            .appendPath("time")
            .appendPath(reminder.triggerAtEpochMillis.toString())
            .build()
        return PendingIntent.getActivity(
            context,
            0,
            Intent(Intent.ACTION_VIEW, uri),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(
        reminder: ReminderEntity,
        actionVersion: Long,
        action: String,
        minutes: Int,
    ): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java)
            .setAction(action)
            .setData("personaledge://reminder/action/${reminder.id}/$action/$minutes".toUri())
            .putExtra(ReminderScheduler.EXTRA_REMINDER_ID, reminder.id)
            .putExtra(ReminderScheduler.EXTRA_SCHEDULE_VERSION, actionVersion)
            .putExtra(ReminderActionReceiver.EXTRA_SNOOZE_MINUTES, minutes)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val TAGGED_NOTIFICATION_ID = ReminderNotificationAddressPolicy.TAGGED_NOTIFICATION_ID
    }
}

object ReminderNotifications {
    const val CHANNEL_ID = "personal_edge_reminders_v1"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "리마인더",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "사용자가 승인하거나 직접 만든 리마인더"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            },
        )
        // rc10 and earlier posted untagged hash IDs. This channel is reminder-exclusive, so every
        // untagged active entry on it is a legacy projection and can be removed without guessing
        // which colliding reminder owned that 31-bit slot. Room remains the source of truth.
        runCatching {
            manager.activeNotifications
                .filter { status ->
                    isLegacyReminderNotification(
                        tag = status.tag,
                        channelId = status.notification.channelId,
                    )
                }
                .forEach { status -> manager.cancel(status.id) }
        }
    }

    internal fun isLegacyReminderNotification(tag: String?, channelId: String?): Boolean =
        tag == null && channelId == CHANNEL_ID
}

class ReminderDeliveryWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(ReminderScheduler.EXTRA_REMINDER_ID) ?: return Result.failure()
        val version = inputData.getLong(ReminderScheduler.EXTRA_SCHEDULE_VERSION, -1)
        if (version < 1) return Result.failure()
        val container = applicationContext.appContainer()
        ReminderDeliveryCoordinator(
            applicationContext,
            container.reminders,
            container.reminderScheduler,
        ).deliver(id, version)
        return Result.success()
    }
}

class ReminderReconcileWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = runCatching {
        applicationContext.appContainer().reminderScheduler.reconcileAll()
    }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
}

class NotificationPruneWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = runCatching {
        val container = applicationContext.appContainer()
        container.notifications.prune(container.settings.current().notificationRetentionDays)
    }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
}

class LeaveByReminderWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = runCatching {
        val container = applicationContext.appContainer()
        val settings = container.settings.current()
        val brief = if (
            container.ownerConsentInterlock.allowed(
                OwnerConsentFeature.PROACTIVE_ROUTE_PLANNING,
                settings.proactiveRoutePlanningEnabled,
            )
        ) {
            TodayBriefEngine(container).compose(settings)
        } else {
            // The reconciler uses this only to remove its own SYSTEM+CALENDAR projection.
            TodayBrief(calendarAvailable = true)
        }
        LeaveByReminderReconciler(container).reconcile(settings, brief)
    }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
}

object ReminderWorkBootstrap {
    private const val RECONCILE_PERIODIC = "personal-edge-reminder-reconcile-periodic"
    private const val RECONCILE_NOW = "personal-edge-reminder-reconcile-now"
    private const val LEAVE_BY_RECONCILE_NOW = "personal-edge-leave-by-reconcile-now"
    private const val NOTIFICATION_PRUNE = "personal-edge-notification-prune"
    private const val DAILY_BRIEF = "personal-edge-daily-brief"
    private const val LEAVE_BY_REMINDER = "personal-edge-leave-by-reminder"

    fun start(context: Context) {
        ReminderNotifications.createChannel(context)
        DailyBriefWorker.createChannel(context)
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniquePeriodicWork(
            RECONCILE_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<ReminderReconcileWorker>(12, TimeUnit.HOURS).build(),
        )
        manager.enqueueUniquePeriodicWork(
            NOTIFICATION_PRUNE,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<NotificationPruneWorker>(24, TimeUnit.HOURS).build(),
        )
        ReceiverScope.scope.launch {
            val minutesOfDay = runCatching {
                context.applicationContext.appContainer().settings.current().dailyBriefMinutesOfDay
            }.getOrDefault(AgentSettings.DEFAULT_DAILY_BRIEF_MINUTES_OF_DAY)
            scheduleDailyBrief(context, minutesOfDay)
        }
        val now = ZonedDateTime.now()
        var nextLeaveBy = now.withHour(0).withMinute(15).withSecond(0).withNano(0)
        if (!nextLeaveBy.isAfter(now)) nextLeaveBy = nextLeaveBy.plusDays(1)
        manager.enqueueUniquePeriodicWork(
            LEAVE_BY_REMINDER,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<LeaveByReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(now, nextLeaveBy))
                .build(),
        )
        enqueueReconcile(context)
        enqueueLeaveByReconcile(context)
    }

    /** Replaces only the brief projection when the user changes its local wall-clock time. */
    fun scheduleDailyBrief(context: Context, minutesOfDay: Int) {
        val bounded = minutesOfDay.coerceIn(
            AgentSettings.MIN_MINUTES_OF_DAY,
            AgentSettings.MAX_MINUTES_OF_DAY,
        )
        val now = ZonedDateTime.now()
        val nextBrief = nextDailyBriefAt(now, bounded)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DAILY_BRIEF,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<DailyBriefWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(now, nextBrief))
                .build(),
        )
    }

    internal fun nextDailyBriefAt(now: ZonedDateTime, minutesOfDay: Int): ZonedDateTime {
        require(minutesOfDay in AgentSettings.MIN_MINUTES_OF_DAY..AgentSettings.MAX_MINUTES_OF_DAY)
        var candidate = now.withHour(minutesOfDay / 60)
            .withMinute(minutesOfDay % 60)
            .withSecond(0)
            .withNano(0)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        return candidate
    }

    fun enqueueReconcile(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            RECONCILE_NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReminderReconcileWorker>().build(),
        )
    }

    fun enqueueLeaveByReconcile(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            LEAVE_BY_RECONCILE_NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<LeaveByReminderWorker>().build(),
        )
    }
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: return
        val version = intent.getLongExtra(ReminderScheduler.EXTRA_SCHEDULE_VERSION, -1)
        if (version < 1) return
        val pending = goAsync()
        ReceiverScope.scope.launch {
            try {
                val container = context.applicationContext.appContainer()
                ReminderDeliveryCoordinator(
                    context.applicationContext,
                    container.reminders,
                    container.reminderScheduler,
                ).deliver(id, version)
            } finally {
                pending.finish()
            }
        }
    }
}

enum class ReminderNotificationActionOutcome {
    COMPLETED,
    SNOOZED,
    STALE_REFUSED,
    NOT_FOUND,
    INVALID,
}

/** Deterministic notification-action boundary; the BroadcastReceiver only owns async lifetime. */
class ReminderNotificationActionCoordinator internal constructor(
    context: Context,
    private val repository: ReminderRepository,
    private val scheduler: ReminderScheduler,
    private val notificationAddressPolicy: ReminderNotificationAddressPolicy,
) {
    constructor(
        context: Context,
        repository: ReminderRepository,
        scheduler: ReminderScheduler,
    ) : this(context, repository, scheduler, ReminderNotificationAddressPolicy())

    private val applicationContext = context.applicationContext

    suspend fun handle(
        action: String?,
        reminderId: String?,
        scheduleVersion: Long,
        snoozeMinutes: Int = -1,
    ): ReminderNotificationActionOutcome {
        if (action !in setOf(
                ReminderActionReceiver.ACTION_COMPLETE,
                ReminderActionReceiver.ACTION_SNOOZE,
                ReminderActionReceiver.ACTION_SNOOZE_TOMORROW_MORNING,
            ) ||
            reminderId.isNullOrBlank() || scheduleVersion < 1
        ) return ReminderNotificationActionOutcome.INVALID

        val before = repository.find(reminderId)
            ?: return ReminderNotificationActionOutcome.NOT_FOUND
        if (before.state != ReminderState.ACTIVE || before.scheduleVersion != scheduleVersion) {
            repository.recordStaleAction(reminderId, scheduleVersion)
            return ReminderNotificationActionOutcome.STALE_REFUSED
        }

        val outcome = when (action) {
            ReminderActionReceiver.ACTION_COMPLETE -> {
                val completed = repository.complete(reminderId, scheduleVersion)
                if (completed == null) {
                    repository.recordStaleAction(reminderId, scheduleVersion)
                    return ReminderNotificationActionOutcome.STALE_REFUSED
                }
                repository.recordDelivery(before, ReminderDeliveryOutcome.COMPLETED_FROM_NOTIFICATION)
                scheduler.cancel(reminderId)
                ReminderNotificationActionOutcome.COMPLETED
            }
            ReminderActionReceiver.ACTION_SNOOZE,
            ReminderActionReceiver.ACTION_SNOOZE_TOMORROW_MORNING,
            -> {
                val snoozeUntil = if (action == ReminderActionReceiver.ACTION_SNOOZE) {
                    if (snoozeMinutes !in setOf(10, 60, 24 * 60)) {
                        return ReminderNotificationActionOutcome.INVALID
                    }
                    System.currentTimeMillis() + Duration.ofMinutes(snoozeMinutes.toLong()).toMillis()
                } else {
                    ReminderSnoozePolicy.tomorrowMorning(
                        nowEpochMillis = System.currentTimeMillis(),
                        zone = runCatching { ZoneId.of(before.zoneId) }.getOrNull()
                            ?: return ReminderNotificationActionOutcome.INVALID,
                    ) ?: return ReminderNotificationActionOutcome.INVALID
                }
                val snoozed = repository.snooze(
                    reminderId,
                    snoozeUntil,
                    scheduleVersion,
                )
                if (snoozed == null) {
                    repository.recordStaleAction(reminderId, scheduleVersion)
                    return ReminderNotificationActionOutcome.STALE_REFUSED
                }
                repository.recordDelivery(before, ReminderDeliveryOutcome.SNOOZED_FROM_NOTIFICATION)
                scheduler.schedule(snoozed)
                ReminderNotificationActionOutcome.SNOOZED
            }
            else -> return ReminderNotificationActionOutcome.INVALID
        }
        val notifications = NotificationManagerCompat.from(applicationContext)
        val address = notificationAddressPolicy.current(reminderId)
        notifications.cancel(address.tag, address.id)
        return outcome
    }
}

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(
                ACTION_COMPLETE,
                ACTION_SNOOZE,
                ACTION_SNOOZE_TOMORROW_MORNING,
            )
        ) return
        val id = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: return
        val version = intent.getLongExtra(ReminderScheduler.EXTRA_SCHEDULE_VERSION, -1)
        if (version < 1) return
        val pending = goAsync()
        ReceiverScope.scope.launch {
            try {
                val container = context.applicationContext.appContainer()
                ReminderNotificationActionCoordinator(
                    context,
                    container.reminders,
                    container.reminderScheduler,
                ).handle(
                    action = intent.action,
                    reminderId = id,
                    scheduleVersion = version,
                    snoozeMinutes = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, -1),
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_COMPLETE = "com.personaledge.agent.reminder.COMPLETE"
        const val ACTION_SNOOZE = "com.personaledge.agent.reminder.SNOOZE"
        const val ACTION_SNOOZE_TOMORROW_MORNING =
            "com.personaledge.agent.reminder.SNOOZE_TOMORROW_MORNING"
        const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
    }
}

class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in SUPPORTED_ACTIONS) ReminderWorkBootstrap.start(context)
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}

private object ReceiverScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

object NotificationPermissionPolicy {
    fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    fun canPost(context: Context): Boolean = canPost(
        runtimePermissionGranted = isGranted(context),
        notificationsEnabled = runCatching {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(false),
    )

    internal fun canPost(
        runtimePermissionGranted: Boolean,
        notificationsEnabled: Boolean,
    ): Boolean = runtimePermissionGranted && notificationsEnabled
}

object QuietHoursPolicy {
    private const val QUIET_START_HOUR = 22
    private const val QUIET_END_HOUR = 7

    fun adjustIfNeeded(epochMillis: Long, zone: ZoneId, enabled: Boolean): Long {
        if (!enabled) return epochMillis
        val local = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val adjusted = when {
            local.hour >= QUIET_START_HOUR -> local.toLocalDate().plusDays(1).atTime(QUIET_END_HOUR, 0)
            local.hour < QUIET_END_HOUR -> local.toLocalDate().atTime(QUIET_END_HOUR, 0)
            else -> return epochMillis
        }
        return adjusted.atZone(zone).toInstant().toEpochMilli()
    }
}

/** Pure local-time policy shared by notification presentation and its action receiver. */
object ReminderSnoozePolicy {
    private const val TOMORROW_MORNING_HOUR = 9
    private const val OFFER_TOMORROW_AFTER_HOUR = 18

    fun shouldOfferTomorrowMorning(nowEpochMillis: Long, zone: ZoneId): Boolean =
        Instant.ofEpochMilli(nowEpochMillis).atZone(zone).hour >= OFFER_TOMORROW_AFTER_HOUR

    fun tomorrowMorning(nowEpochMillis: Long, zone: ZoneId): Long? {
        val local = Instant.ofEpochMilli(nowEpochMillis)
            .atZone(zone)
            .toLocalDate()
            .plusDays(1)
            .atTime(TOMORROW_MORNING_HOUR, 0)
        val offsets = zone.rules.getValidOffsets(local)
        if (offsets.size != 1) return null
        return local.toInstant(offsets.single()).toEpochMilli()
    }
}
