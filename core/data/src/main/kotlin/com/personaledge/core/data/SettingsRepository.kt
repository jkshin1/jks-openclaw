package com.personaledge.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Every field has a safe default, so a missing or unreadable store degrades to the most
 * conservative behavior rather than to an unconfirmed side effect.
 */
data class AgentSettings(
    /** Default calendar for new events. Null means the tool must ask which calendar to use. */
    val defaultCalendarId: Long? = null,
    val defaultCalendarLabel: String? = null,
    val readCalendarIds: Set<Long> = emptySet(),
    /** Home address used as the default route origin. */
    val defaultOriginLabel: String? = null,
    val notificationCaptureEnabled: Boolean = false,
    /** Separately permits confirmed RemoteInput replies to active KakaoTalk notifications. */
    val kakaoNotificationReplyEnabled: Boolean = false,
    /** Locally derives review-only commitment candidates from captured KakaoTalk posts. */
    val commitmentProposalsEnabled: Boolean = false,
    val notificationRetentionDays: Int = DEFAULT_NOTIFICATION_RETENTION_DAYS,
    val routeLookupEnabled: Boolean = false,
    val webSearchEnabled: Boolean = false,
    /** Allows approved cross-thread memory capture and recall. Stored memories remain when off. */
    val memoryEnabled: Boolean = false,
    /** Posts one deterministic, model-free morning summary. Off until explicitly enabled. */
    val dailyBriefEnabled: Boolean = false,
    /** Local wall-clock minute for the deterministic brief; 08:00 by default. */
    val dailyBriefMinutesOfDay: Int = DEFAULT_DAILY_BRIEF_MINUTES_OF_DAY,
    /** Separately permits background place transmission for a leave-by estimate. */
    val proactiveRoutePlanningEnabled: Boolean = false,
    val quietHoursEnabled: Boolean = false,
    val weekendBriefEnabled: Boolean = true,
    val recentMessageWindow: Int = ConversationRepository.DEFAULT_RECENT_MESSAGES,
) {
    init {
        require(notificationRetentionDays in MIN_NOTIFICATION_RETENTION_DAYS..MAX_NOTIFICATION_RETENTION_DAYS)
        require(recentMessageWindow in 1..ConversationRepository.MAX_MESSAGES_PER_READ)
        require(dailyBriefMinutesOfDay in MIN_MINUTES_OF_DAY..MAX_MINUTES_OF_DAY)
    }

    companion object {
        const val DEFAULT_NOTIFICATION_RETENTION_DAYS = 14
        const val MIN_NOTIFICATION_RETENTION_DAYS = 1
        const val MAX_NOTIFICATION_RETENTION_DAYS = 180
        const val DEFAULT_DAILY_BRIEF_MINUTES_OF_DAY = 8 * 60
        const val MIN_MINUTES_OF_DAY = 0
        const val MAX_MINUTES_OF_DAY = 24 * 60 - 1
    }
}

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AgentSettings> = dataStore.data
        // A corrupt or unreadable store must not stop the app from launching into a safe state.
        .catch { failure -> if (failure is IOException) emit(emptyPreferences()) else throw failure }
        .map(::readSettings)

    suspend fun current(): AgentSettings = settings.first()

    suspend fun setDefaultCalendar(calendarId: Long?, label: String?) {
        dataStore.edit { preferences ->
            if (calendarId == null) {
                preferences.remove(KEY_DEFAULT_CALENDAR_ID)
                preferences.remove(KEY_DEFAULT_CALENDAR_LABEL)
            } else {
                preferences[KEY_DEFAULT_CALENDAR_ID] = calendarId
                preferences[KEY_DEFAULT_CALENDAR_LABEL] = label.orEmpty().takeCodePoints(MAX_LABEL_CHARACTERS)
            }
        }
    }

    suspend fun setReadCalendarIds(calendarIds: Set<Long>) {
        val canonical = calendarIds
            .filter { it > 0 }
            .take(MAX_READ_CALENDARS)
            .map(Long::toString)
            .toSet()
        dataStore.edit { preferences ->
            if (canonical.isEmpty()) preferences.remove(KEY_READ_CALENDAR_IDS)
            else preferences[KEY_READ_CALENDAR_IDS] = canonical
        }
    }

    suspend fun setDefaultOriginLabel(label: String?) {
        dataStore.edit { preferences ->
            val trimmed = label?.trim().orEmpty()
            require(trimmed.isEmpty() || isSafeDefaultOrigin(trimmed)) {
                "Default origin contains unsupported text."
            }
            if (trimmed.isEmpty()) {
                preferences.remove(KEY_DEFAULT_ORIGIN_LABEL)
            } else {
                preferences[KEY_DEFAULT_ORIGIN_LABEL] = trimmed
            }
        }
    }

    suspend fun setNotificationCaptureEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[KEY_NOTIFICATION_CAPTURE] = enabled }
    }

    suspend fun setKakaoNotificationReplyEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[KEY_KAKAO_NOTIFICATION_REPLY] = enabled }
    }

    suspend fun setCommitmentProposalsEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[KEY_COMMITMENT_PROPOSALS] = enabled }
    }

    suspend fun setNotificationRetentionDays(days: Int) {
        val bounded = days.coerceIn(
            AgentSettings.MIN_NOTIFICATION_RETENTION_DAYS,
            AgentSettings.MAX_NOTIFICATION_RETENTION_DAYS,
        )
        dataStore.edit { preferences -> preferences[KEY_NOTIFICATION_RETENTION_DAYS] = bounded }
    }

    suspend fun setWebSearchEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[KEY_WEB_SEARCH] = enabled }
    }

    suspend fun setRouteLookupEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[KEY_ROUTE_LOOKUP] = enabled }
    }

    suspend fun setMemoryEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[KEY_MEMORY_ENABLED] = enabled }
    }

    suspend fun setDailyBriefEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[KEY_DAILY_BRIEF_ENABLED] = enabled }
    }

    suspend fun setDailyBriefMinutesOfDay(minutesOfDay: Int) {
        require(minutesOfDay in AgentSettings.MIN_MINUTES_OF_DAY..AgentSettings.MAX_MINUTES_OF_DAY)
        dataStore.edit { preferences -> preferences[KEY_DAILY_BRIEF_MINUTES_OF_DAY] = minutesOfDay }
    }

    suspend fun setProactiveRoutePlanningEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[KEY_PROACTIVE_ROUTE_PLANNING] = enabled }
    }

    suspend fun setQuietHoursEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[KEY_QUIET_HOURS_ENABLED] = enabled }
    }

    suspend fun setWeekendBriefEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[KEY_WEEKEND_BRIEF_ENABLED] = enabled }
    }

    suspend fun setRecentMessageWindow(messages: Int) {
        val bounded = messages.coerceIn(1, ConversationRepository.MAX_MESSAGES_PER_READ)
        dataStore.edit { preferences -> preferences[KEY_RECENT_MESSAGE_WINDOW] = bounded }
    }

    /** Used by the settings screen's "reset everything" path. */
    suspend fun clear() {
        dataStore.edit(MutablePreferences::clear)
    }

    private fun readSettings(preferences: Preferences): AgentSettings = AgentSettings(
        defaultCalendarId = preferences[KEY_DEFAULT_CALENDAR_ID],
        defaultCalendarLabel = preferences[KEY_DEFAULT_CALENDAR_LABEL]?.takeIf(String::isNotBlank),
        readCalendarIds = preferences[KEY_READ_CALENDAR_IDS]
            .orEmpty()
            .mapNotNull(String::toLongOrNull)
            .filter { it > 0 }
            .take(MAX_READ_CALENDARS)
            .toSet(),
        defaultOriginLabel = preferences[KEY_DEFAULT_ORIGIN_LABEL]
            ?.trim()
            ?.takeIf(::isSafeDefaultOrigin),
        notificationCaptureEnabled = preferences[KEY_NOTIFICATION_CAPTURE] ?: false,
        kakaoNotificationReplyEnabled = preferences[KEY_KAKAO_NOTIFICATION_REPLY] ?: false,
        commitmentProposalsEnabled = preferences[KEY_COMMITMENT_PROPOSALS] ?: false,
        notificationRetentionDays = (
            preferences[KEY_NOTIFICATION_RETENTION_DAYS]
                ?: AgentSettings.DEFAULT_NOTIFICATION_RETENTION_DAYS
            ).coerceIn(
            AgentSettings.MIN_NOTIFICATION_RETENTION_DAYS,
            AgentSettings.MAX_NOTIFICATION_RETENTION_DAYS,
        ),
        routeLookupEnabled = preferences[KEY_ROUTE_LOOKUP] ?: false,
        webSearchEnabled = preferences[KEY_WEB_SEARCH] ?: false,
        memoryEnabled = preferences[KEY_MEMORY_ENABLED] ?: false,
        dailyBriefEnabled = preferences[KEY_DAILY_BRIEF_ENABLED] ?: false,
        dailyBriefMinutesOfDay = (
            preferences[KEY_DAILY_BRIEF_MINUTES_OF_DAY]
                ?: AgentSettings.DEFAULT_DAILY_BRIEF_MINUTES_OF_DAY
            ).coerceIn(AgentSettings.MIN_MINUTES_OF_DAY, AgentSettings.MAX_MINUTES_OF_DAY),
        proactiveRoutePlanningEnabled = preferences[KEY_PROACTIVE_ROUTE_PLANNING] ?: false,
        quietHoursEnabled = preferences[KEY_QUIET_HOURS_ENABLED] ?: false,
        weekendBriefEnabled = preferences[KEY_WEEKEND_BRIEF_ENABLED] ?: true,
        recentMessageWindow = (
            preferences[KEY_RECENT_MESSAGE_WINDOW]
                ?: ConversationRepository.DEFAULT_RECENT_MESSAGES
            ).coerceIn(1, ConversationRepository.MAX_MESSAGES_PER_READ),
    )

    companion object {
        const val STORE_FILE_NAME = "agent-settings.preferences_pb"
        private const val MAX_LABEL_CHARACTERS = 120
        private const val MAX_DEFAULT_ORIGIN_CODE_POINTS = 80
        const val MAX_READ_CALENDARS = 12

        private val KEY_DEFAULT_CALENDAR_ID = longPreferencesKey("default_calendar_id")
        private val KEY_DEFAULT_CALENDAR_LABEL = stringPreferencesKey("default_calendar_label")
        private val KEY_READ_CALENDAR_IDS = stringSetPreferencesKey("read_calendar_ids")
        private val KEY_DEFAULT_ORIGIN_LABEL = stringPreferencesKey("default_origin_label")
        private val KEY_NOTIFICATION_CAPTURE = booleanPreferencesKey("notification_capture_enabled")
        private val KEY_KAKAO_NOTIFICATION_REPLY =
            booleanPreferencesKey("kakao_notification_reply_enabled")
        private val KEY_COMMITMENT_PROPOSALS = booleanPreferencesKey("commitment_proposals_enabled")
        private val KEY_NOTIFICATION_RETENTION_DAYS = intPreferencesKey("notification_retention_days")
        private val KEY_ROUTE_LOOKUP = booleanPreferencesKey("route_lookup_enabled")
        private val KEY_WEB_SEARCH = booleanPreferencesKey("web_search_enabled")
        private val KEY_MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
        private val KEY_DAILY_BRIEF_ENABLED = booleanPreferencesKey("daily_brief_enabled")
        private val KEY_DAILY_BRIEF_MINUTES_OF_DAY = intPreferencesKey("daily_brief_minutes_of_day")
        private val KEY_PROACTIVE_ROUTE_PLANNING = booleanPreferencesKey("proactive_route_planning_enabled")
        private val KEY_QUIET_HOURS_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
        private val KEY_WEEKEND_BRIEF_ENABLED = booleanPreferencesKey("weekend_brief_enabled")
        private val KEY_RECENT_MESSAGE_WINDOW = intPreferencesKey("recent_message_window")

        private fun isSafeDefaultOrigin(value: String): Boolean =
            value.codePointCount(0, value.length) in 1..MAX_DEFAULT_ORIGIN_CODE_POINTS &&
                !value.contains("<|") && !value.contains("|>") &&
                value.codePoints().noneMatch { codePoint ->
                    Character.isISOControl(codePoint) || when (Character.getType(codePoint)) {
                        Character.FORMAT.toInt(),
                        Character.LINE_SEPARATOR.toInt(),
                        Character.PARAGRAPH_SEPARATOR.toInt(),
                        -> true
                        else -> false
                    }
                }

        /** The settings file also stays outside the backup set; it names a calendar and a home area. */
        fun storeFile(context: Context): File =
            File(context.applicationContext.noBackupFilesDir, STORE_FILE_NAME)

        /**
         * Creates the single process-wide store. DataStore enforces one active instance per file,
         * so the application object must hold the result rather than calling this per screen.
         */
        fun create(
            context: Context,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ): SettingsRepository = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { storeFile(context) },
        )
    }
}
