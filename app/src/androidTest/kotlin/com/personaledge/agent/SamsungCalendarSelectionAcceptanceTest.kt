package com.personaledge.agent

import android.Manifest
import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.tools.CalendarAccount
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the one unambiguous Samsung Account calendar without reading any event content.
 *
 * This is physical-device acceptance, not a normal suite test. It changes only this app's scoped
 * calendar setting, never provider rows. Samsung Experience Service can expose a separate shared
 * calendar that is also marked primary, so the default here means the single Samsung Account row
 * (`com.osp.app.signin`), not the Mobile Service sharing row. Ambiguity still fails closed.
 */
@RunWith(AndroidJUnit4::class)
class SamsungCalendarSelectionAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun ownerSelectedSamsungAccountCalendarBecomesTheOnlyToolScope() = runBlocking {
        assertTrue(
            "Set -e selectSamsungCalendar true only after the owner chooses Samsung Calendar.",
            InstrumentationRegistry.getArguments().getString("selectSamsungCalendar") == "true",
        )

        listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }

        val container = (context.applicationContext as Application).appContainer()
        val writable = container.deviceCalendars.writableCalendars()
        val samsung = writable.filter { calendar -> calendar.accountType in SAMSUNG_ACCOUNT_TYPES }
        val currentId = container.settings.current().defaultCalendarId
        val current = samsung.firstOrNull { calendar -> calendar.id == currentId }
        val samsungAccount = samsung.filter { calendar ->
            calendar.accountType == SAMSUNG_ACCOUNT_TYPE
        }
        val selected = current ?: samsungAccount.singleOrNull()

        println("privacy_samsung_calendar_candidates=${samsung.size}")
        println("privacy_samsung_account_calendar_candidates=${samsungAccount.size}")
        println("privacy_samsung_calendar_primary_candidates=${samsung.count(CalendarAccount::isPrimary)}")
        assertTrue(
            "No unambiguous Samsung Account calendar is available: " +
                "candidates=${samsung.size}, account_rows=${samsungAccount.size}, " +
                "primary=${samsung.count(CalendarAccount::isPrimary)}, " +
                "current_is_samsung=${current != null}.",
            selected != null,
        )

        if (current == null) {
            container.settings.setDefaultCalendar(
                calendarId = selected!!.id,
                label = SettingsTextPolicy.sanitizeProviderLabel(selected.displayName),
            )
        }

        val stored = container.settings.current()
        val scoped = container.scopedCalendar.writableCalendars()
        assertEquals(selected!!.id, stored.defaultCalendarId)
        assertEquals(listOf(selected.id), scoped.map(CalendarAccount::id))
        assertTrue(selected.accountType in SAMSUNG_ACCOUNT_TYPES)

        println("privacy_samsung_calendar_selected=true")
        println("privacy_samsung_calendar_primary=${selected.isPrimary}")
        println("privacy_samsung_calendar_switch_performed=${current == null}")
        println("privacy_samsung_calendar_scope_count=${scoped.size}")
    }

    private companion object {
        const val SAMSUNG_ACCOUNT_TYPE = "com.osp.app.signin"
        val SAMSUNG_ACCOUNT_TYPES = setOf(
            SAMSUNG_ACCOUNT_TYPE,
            "com.samsung.android.mobileservice",
        )
    }
}
