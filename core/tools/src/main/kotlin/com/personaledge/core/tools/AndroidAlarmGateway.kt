package com.personaledge.core.tools

import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Creates alarms through the platform's standard `AlarmClock` intent.
 *
 * This is the only supported way for a third-party app to add an alarm to the user's clock app,
 * and it is fire-and-forget: the intent carries no result, so a delivered request is not proof
 * that an alarm now exists. Reading is equally limited — `getNextAlarmClock` returns one trigger
 * time, not a list — so this gateway never claims to enumerate or edit existing alarms.
 *
 * Starting the clock activity requires a foreground app. The tools run immediately after a
 * confirmation dialog, which satisfies that, and a blocked start is reported rather than swallowed.
 */
class AndroidAlarmGateway(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AlarmGateway {
    private val applicationContext = context.applicationContext
    private val alarmManager =
        applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override suspend fun clockAppAvailable(): Boolean = withContext(ioDispatcher) {
        // Requires the matching <queries> entry in the manifest on API 30+.
        applicationContext.packageManager
            .queryIntentActivities(Intent(AlarmClock.ACTION_SET_ALARM), 0)
            .isNotEmpty()
    }

    override suspend fun requestAlarm(request: AlarmRequest): AlarmOutcome =
        withContext(Dispatchers.Main) {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(AlarmClock.EXTRA_HOUR, request.hour)
                putExtra(AlarmClock.EXTRA_MINUTES, request.minute)
                request.label?.let { label -> putExtra(AlarmClock.EXTRA_MESSAGE, label) }
                if (request.days.isNotEmpty()) {
                    putExtra(
                        AlarmClock.EXTRA_DAYS,
                        ArrayList(
                            AlarmDay.entries
                                .filter { day -> day in request.days }
                                .map(AlarmDay::calendarField),
                        ),
                    )
                }
                // A hint only: the clock app may still show its editor. Either way the user
                // already approved the exact values in this app's confirmation dialog.
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }

            try {
                applicationContext.startActivity(intent)
                AlarmOutcome.Delivered
            } catch (_: ActivityNotFoundException) {
                AlarmOutcome.Refused(AlarmRefusal.NO_CLOCK_APP)
            } catch (_: SecurityException) {
                AlarmOutcome.Refused(AlarmRefusal.START_BLOCKED)
            } catch (_: IllegalStateException) {
                // Android throws this when a background activity start is disallowed.
                AlarmOutcome.Refused(AlarmRefusal.START_BLOCKED)
            }
        }

    override suspend fun nextAlarm(): NextAlarm? = withContext(ioDispatcher) {
        alarmManager.nextAlarmClock?.let { info -> NextAlarm(info.triggerTime) }
    }
}
