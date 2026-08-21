package com.personaledge.agent

import android.app.Application
import com.personaledge.core.data.ConversationRepository
import com.personaledge.core.data.PersonalEdgeDatabase
import com.personaledge.core.data.SecretVault
import com.personaledge.core.data.SettingsRepository
import com.personaledge.core.tools.AndroidCalendarGateway
import com.personaledge.core.tools.CalendarGateway
import com.personaledge.core.tools.ScopedCalendarGateway
import com.personaledge.core.tools.SqliteActionLedger
import kotlinx.coroutines.flow.first

/**
 * Process-wide singletons.
 *
 * DataStore allows one active instance per file and the action ledger must be the same object for
 * every turn, so these cannot be created per ViewModel. Everything is lazy: a user who never
 * grants calendar access never opens the calendar provider.
 */
class AppContainer(application: Application) {
    val settings: SettingsRepository by lazy { SettingsRepository.create(application) }

    val database: PersonalEdgeDatabase by lazy { PersonalEdgeDatabase.open(application) }

    val conversations: ConversationRepository by lazy { ConversationRepository(database) }

    val secretVault: SecretVault by lazy { SecretVault.create(application) }

    /** The one durable ledger. Side-effecting tools are refused without it. */
    val actionLedger: SqliteActionLedger by lazy { SqliteActionLedger.open(application) }

    /** Unfiltered view, used only by the settings screen to list calendars the user can pin. */
    val deviceCalendars: AndroidCalendarGateway by lazy { AndroidCalendarGateway(application) }

    /**
     * What the tools see: only the pinned calendar. On this device that is NAVER Calendar as
     * published into `CalendarContract` by a CalDAV sync client.
     */
    val scopedCalendar: CalendarGateway by lazy {
        ScopedCalendarGateway(deviceCalendars, ::pinnedCalendarId)
    }

    suspend fun pinnedCalendarId(): Long? = settings.settings.first().defaultCalendarId
}

internal fun Application.appContainer(): AppContainer = (this as PersonalEdgeApplication).container
