package com.personaledge.agent

import android.app.Application
import com.personaledge.core.data.ConversationRepository
import com.personaledge.core.data.NotificationRepository
import com.personaledge.core.data.PersonalEdgeDatabase
import com.personaledge.core.data.SecretKeyName
import com.personaledge.core.data.SecretVault
import com.personaledge.core.data.SettingsRepository
import com.personaledge.core.tools.AlarmGateway
import com.personaledge.core.tools.AndroidAlarmGateway
import com.personaledge.core.tools.AndroidCalendarGateway
import com.personaledge.core.tools.CalendarGateway
import com.personaledge.core.tools.HttpTransport
import com.personaledge.core.tools.NAVER_ALLOWED_HOSTS
import com.personaledge.core.tools.NaverSearchCredentials
import com.personaledge.core.tools.NaverRouteGateway
import com.personaledge.core.tools.NaverWebSearchGateway
import com.personaledge.core.tools.NcpCredentials
import com.personaledge.core.tools.RouteGateway
import com.personaledge.core.tools.ScopedCalendarGateway
import com.personaledge.core.tools.UrlHttpTransport
import com.personaledge.core.tools.WebSearchGateway
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

    val notifications: NotificationRepository by lazy { NotificationRepository(database) }

    /** Read side of the capture store, plus the two gates the interlock re-checks. */
    val notificationGateway: StoredNotificationGateway by lazy {
        StoredNotificationGateway(
            context = application,
            notifications = notifications,
            settings = settings,
        )
    }

    val secretVault: SecretVault by lazy { SecretVault.create(application) }

    val credentials: CredentialSettings by lazy { CredentialSettings(secretVault) }

    /** The only outbound network surface, pinned to the two NAVER hosts this app talks to. */
    val httpTransport: HttpTransport by lazy { UrlHttpTransport(NAVER_ALLOWED_HOSTS) }

    val routes: RouteGateway by lazy {
        NaverRouteGateway(httpTransport) {
            // Read per request, so deleting a key in settings takes effect immediately and the
            // plaintext lives only for the duration of the call.
            val keyId = secretVault.read(SecretKeyName.NAVER_MAP_CLIENT_ID)
            val key = secretVault.read(SecretKeyName.NAVER_MAP_CLIENT_SECRET)
            if (keyId == null || key == null) null else NcpCredentials(keyId, key)
        }
    }

    val webSearch: WebSearchGateway by lazy {
        NaverWebSearchGateway(httpTransport) {
            val clientId = secretVault.read(SecretKeyName.NAVER_SEARCH_CLIENT_ID)
            val clientSecret = secretVault.read(SecretKeyName.NAVER_SEARCH_CLIENT_SECRET)
            if (clientId == null || clientSecret == null) {
                null
            } else {
                NaverSearchCredentials(clientId, clientSecret)
            }
        }
    }

    suspend fun defaultOriginLabel(): String? = settings.settings.first().defaultOriginLabel

    /** The one durable ledger. Side-effecting tools are refused without it. */
    val actionLedger: SqliteActionLedger by lazy { SqliteActionLedger.open(application) }

    /** Unfiltered view, used only by the settings screen to list calendars the user can pin. */
    val deviceCalendars: AndroidCalendarGateway by lazy { AndroidCalendarGateway(application) }

    /**
     * What the tools see: only the pinned CalendarContract row. Whether that row can represent the
     * user's NAVER calendar must be established separately on the physical device.
     */
    val scopedCalendar: CalendarGateway by lazy {
        ScopedCalendarGateway(deviceCalendars, ::pinnedCalendarId)
    }

    /**
     * The device clock. Alarms are created through the platform `AlarmClock` intent, so there is
     * nothing to scope: the clock app owns the alarm list and exposes no way to read or edit it.
     */
    val alarms: AlarmGateway by lazy { AndroidAlarmGateway(application) }

    suspend fun pinnedCalendarId(): Long? = settings.settings.first().defaultCalendarId

    /** Re-queries CalendarContract so a removed or unreadable pinned row fails closed. */
    suspend fun calendarIsReadable(calendarId: Long): Boolean =
        deviceCalendars.syncedCalendars().any { calendar -> calendar.id == calendarId }
}

internal fun Application.appContainer(): AppContainer = (this as PersonalEdgeApplication).container
