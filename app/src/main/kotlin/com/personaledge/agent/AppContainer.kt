package com.personaledge.agent

import android.app.Application
import android.content.Context
import com.personaledge.core.agent.AgentPlanCheckpointSink
import com.personaledge.core.data.AgentPlanCheckpointRepository
import com.personaledge.core.data.ConversationRepository
import com.personaledge.core.data.CommitmentProposalRepository
import com.personaledge.core.data.NotificationRepository
import com.personaledge.core.data.MemoryRepository
import com.personaledge.core.data.PersonalEdgeDatabase
import com.personaledge.core.data.SecretKeyName
import com.personaledge.core.data.SecretVault
import com.personaledge.core.data.SettingsRepository
import com.personaledge.core.data.UserDataTransferRepository
import com.personaledge.core.data.TurnOutcomeRepository
import com.personaledge.core.data.ReminderRepository
import com.personaledge.core.tools.AlarmGateway
import com.personaledge.core.tools.AndroidAlarmGateway
import com.personaledge.core.tools.AndroidCalendarGateway
import com.personaledge.core.tools.CalendarGateway
import com.personaledge.core.tools.HttpTransport
import com.personaledge.core.tools.NaverRouteGateway
import com.personaledge.core.tools.NcpCredentials
import com.personaledge.core.tools.OpenMeteoWeatherGateway
import com.personaledge.core.tools.OUTBOUND_NETWORK_ALLOWED_HOSTS
import com.personaledge.core.tools.RouteGateway
import com.personaledge.core.tools.ScopedCalendarGateway
import com.personaledge.core.tools.TavilyApiKeyVerification
import com.personaledge.core.tools.TavilyApiKeyVerifier
import com.personaledge.core.tools.TavilyWebSearchGateway
import com.personaledge.core.tools.ToolExecutionException
import com.personaledge.core.tools.ToolFailureCode
import com.personaledge.core.tools.UrlHttpTransport
import com.personaledge.core.tools.WebSearchGateway
import com.personaledge.core.tools.WeatherGateway
import com.personaledge.core.tools.YouKeylessMcpWebSearchGateway
import com.personaledge.core.tools.YouTavilyWebSearchGateway
import com.personaledge.core.tools.SqliteActionLedger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    val turnOutcomes: TurnOutcomeRepository by lazy { TurnOutcomeRepository(database) }

    /** Durable, no-backup, content-free sink. It does not activate AgentPlan execution. */
    val agentPlanCheckpoints: AgentPlanCheckpointRepository by lazy {
        AgentPlanCheckpointRepository(database)
    }

    val agentPlanCheckpointSink: AgentPlanCheckpointSink by lazy {
        RoomAgentPlanCheckpointSink(agentPlanCheckpoints)
    }

    val memories: MemoryRepository by lazy { MemoryRepository(database) }

    val memoryGateway: RepositoryMemoryGateway by lazy { RepositoryMemoryGateway(memories) }

    val notifications: NotificationRepository by lazy { NotificationRepository(database) }

    internal val notificationCaptureInterlock by lazy { NotificationCaptureInterlock() }

    /** Notification privacy mutations survive Activity/ViewModel recreation until persisted. */
    internal val notificationMutationScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    internal val ownerConsentInterlock by lazy { OwnerConsentInterlock() }

    /** Owner-consent writes outlive Activity/ViewModel recreation and remain ordered per feature. */
    internal val ownerConsentMutationScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    internal val ownerConsentMutator by lazy {
        OwnerConsentMutator(
            interlock = ownerConsentInterlock,
            mutationScope = ownerConsentMutationScope,
            persist = { feature, enabled ->
                when (feature) {
                    OwnerConsentFeature.ROUTE_LOOKUP -> settings.setRouteLookupEnabled(enabled)
                    OwnerConsentFeature.WEB_SEARCH -> settings.setWebSearchEnabled(enabled)
                    OwnerConsentFeature.MEMORY -> settings.setMemoryEnabled(enabled)
                    OwnerConsentFeature.COMMITMENT_PROPOSALS ->
                        settings.setCommitmentProposalsEnabled(enabled)
                    OwnerConsentFeature.PROACTIVE_ROUTE_PLANNING ->
                        settings.setProactiveRoutePlanningEnabled(enabled)
                    OwnerConsentFeature.DAILY_BRIEF -> settings.setDailyBriefEnabled(enabled)
                }
            },
        )
    }

    val commitmentProposals: CommitmentProposalRepository by lazy {
        CommitmentProposalRepository(database)
    }

    val commitmentProposalGateway: RepositoryCommitmentProposalGateway by lazy {
        RepositoryCommitmentProposalGateway(commitmentProposals)
    }

    val userDataTransfer: UserDataTransferRepository by lazy {
        UserDataTransferRepository(database)
    }

    val reminders: ReminderRepository by lazy { ReminderRepository(database) }

    val reminderScheduler: ReminderScheduler by lazy {
        ReminderScheduler(application, reminders, settingsProvider = { settings.current() })
    }

    val reminderGateway: RepositoryReminderGateway by lazy {
        RepositoryReminderGateway(reminders, reminderScheduler)
    }

    /** Read side of the capture store, plus the two gates the interlock re-checks. */
    val notificationGateway: StoredNotificationGateway by lazy {
        StoredNotificationGateway(
            context = application,
            notifications = notifications,
            settings = settings,
            captureInterlock = notificationCaptureInterlock,
        )
    }

    val kakaoShareGateway by lazy { AndroidKakaoShareGateway(application) }

    val kakaoReplyGateway by lazy { AndroidKakaoReplyGateway() }

    val secretVault: SecretVault by lazy { SecretVault.create(application) }

    /** The app-owned outbound surface, pinned to Maps, search, and Open-Meteo hosts. */
    val httpTransport: HttpTransport by lazy { UrlHttpTransport(OUTBOUND_NETWORK_ALLOWED_HOSTS) }

    private val tavilyApiKeyVerifier: TavilyApiKeyVerifier by lazy {
        TavilyApiKeyVerifier(httpTransport)
    }

    val credentials: CredentialSettings by lazy {
        CredentialSettings(
            vault = secretVault,
            verifier = CredentialVerifier { slot, candidate ->
                when (slot) {
                    CredentialSlot.TAVILY_API_KEY -> verifyTavilyCredential(candidate)
                    CredentialSlot.NAVER_MAP_CLIENT_ID,
                    CredentialSlot.NAVER_MAP_CLIENT_SECRET,
                    -> CredentialVerification.Accepted
                }
            },
        )
    }

    private suspend fun verifyTavilyCredential(candidate: String): CredentialVerification = try {
        when (tavilyApiKeyVerifier.verify(candidate)) {
            is TavilyApiKeyVerification.Valid -> CredentialVerification.Accepted
            TavilyApiKeyVerification.Invalid -> CredentialVerification.Rejected(
                CredentialVerificationFailure.INVALID_CREDENTIAL,
            )
            TavilyApiKeyVerification.RateLimited -> CredentialVerification.Rejected(
                CredentialVerificationFailure.RATE_LIMITED,
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: ToolExecutionException) {
        CredentialVerification.Rejected(
            when (failure.failureCode) {
                ToolFailureCode.AUTHENTICATION_FAILED,
                ToolFailureCode.PERMISSION_DENIED,
                -> CredentialVerificationFailure.INVALID_CREDENTIAL
                ToolFailureCode.RATE_LIMITED -> CredentialVerificationFailure.RATE_LIMITED
                ToolFailureCode.NETWORK_FAILURE -> CredentialVerificationFailure.NETWORK_UNAVAILABLE
                else -> CredentialVerificationFailure.PROVIDER_UNAVAILABLE
            },
        )
    } catch (_: Exception) {
        CredentialVerification.Rejected(CredentialVerificationFailure.PROVIDER_UNAVAILABLE)
    }

    val routes: RouteGateway by lazy {
        val consentTransport = OwnerConsentHttpTransport(httpTransport) {
            ownerConsentAllowed(OwnerConsentFeature.ROUTE_LOOKUP)
        }
        val delegate = NaverRouteGateway(consentTransport) {
            // Read per request, so deleting a key in settings takes effect immediately and the
            // plaintext lives only for the duration of the call.
            val keyId = secretVault.read(SecretKeyName.NAVER_MAP_CLIENT_ID)
            val key = secretVault.read(SecretKeyName.NAVER_MAP_CLIENT_SECRET)
            if (keyId == null || key == null) null else NcpCredentials(keyId, key)
        }
        OwnerConsentRouteGateway(delegate) {
            ownerConsentAllowed(OwnerConsentFeature.ROUTE_LOOKUP)
        }
    }

    val webSearch: WebSearchGateway by lazy {
        val consentTransport = OwnerConsentHttpTransport(httpTransport) {
            ownerConsentAllowed(OwnerConsentFeature.WEB_SEARCH)
        }
        val delegate = YouTavilyWebSearchGateway(
            youGateway = YouKeylessMcpWebSearchGateway(consentTransport),
            tavilyGateway = TavilyWebSearchGateway(consentTransport) {
                secretVault.read(SecretKeyName.TAVILY_API_KEY)
            },
        )
        OwnerConsentWebSearchGateway(delegate) {
            ownerConsentAllowed(OwnerConsentFeature.WEB_SEARCH)
        }
    }

    val weatherLocation by lazy { AndroidWeatherLocationGateway(application) }

    val weather: WeatherGateway by lazy {
        val consentTransport = OwnerConsentHttpTransport(httpTransport) {
            ownerConsentAllowed(OwnerConsentFeature.WEB_SEARCH)
        }
        val delegate = OpenMeteoWeatherGateway(
            transport = consentTransport,
            locationGateway = weatherLocation,
        )
        OwnerConsentWeatherGateway(delegate) {
            ownerConsentAllowed(OwnerConsentFeature.WEB_SEARCH)
        }
    }

    suspend fun defaultOriginLabel(): String? = settings.settings.first().defaultOriginLabel

    private suspend fun ownerConsentAllowed(feature: OwnerConsentFeature): Boolean {
        val current = settings.current()
        return ownerConsentInterlock.allowed(feature, current.ownerConsentEnabled(feature))
    }

    /** The one durable ledger. Side-effecting tools are refused without it. */
    val actionLedger: SqliteActionLedger by lazy { SqliteActionLedger.open(application) }

    /** Unfiltered view, used only by the settings screen to list calendars the user can pin. */
    val deviceCalendars: AndroidCalendarGateway by lazy { AndroidCalendarGateway(application) }

    /**
     * What the tools see: only the pinned CalendarContract row. The owner selected and physically
     * qualified the standard Samsung Account row on the Fold8.
     */
    val scopedCalendar: CalendarGateway by lazy {
        ScopedCalendarGateway(deviceCalendars, ::pinnedCalendarId, ::readCalendarIds)
    }

    /**
     * The device clock. Alarms are created through the platform `AlarmClock` intent, so there is
     * nothing to scope: the clock app owns the alarm list and exposes no way to read or edit it.
     */
    val alarms: AlarmGateway by lazy { AndroidAlarmGateway(application) }

    suspend fun pinnedCalendarId(): Long? = settings.settings.first().defaultCalendarId

    suspend fun readCalendarIds(): Set<Long> = settings.settings.first().let { current ->
        current.readCalendarIds + listOfNotNull(current.defaultCalendarId)
    }

    /** Re-queries CalendarContract so a removed or unreadable pinned row fails closed. */
    suspend fun calendarIsReadable(calendarId: Long): Boolean =
        deviceCalendars.syncedCalendars().any { calendar -> calendar.id == calendarId }
}

internal fun Context.appContainer(): AppContainer =
    (applicationContext as PersonalEdgeApplication).container
