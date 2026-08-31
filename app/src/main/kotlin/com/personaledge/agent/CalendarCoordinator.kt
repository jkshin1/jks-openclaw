package com.personaledge.agent

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.personaledge.core.tools.CalendarAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Owns permission-aware calendar discovery and the explicit many-read/one-write scope. */
class CalendarCoordinator(
    private val application: Application,
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(CalendarSetupState())
    val state: StateFlow<CalendarSetupState> = _state.asStateFlow()

    /** Re-read on resume because permission, accounts, and synced calendars can change externally. */
    fun refresh() {
        scope.launch {
            val granted = hasReadPermission()
            val settings = runCatching { container.settings.current() }.getOrNull()

            if (!granted) {
                _state.value = CalendarSetupState(
                    permissionGranted = false,
                    pinnedCalendarId = settings?.defaultCalendarId,
                    pinnedCalendarLabel = settings?.defaultCalendarLabel,
                    readCalendarIds = settings?.readCalendarIds.orEmpty() +
                        listOfNotNull(settings?.defaultCalendarId),
                )
                return@launch
            }

            val writableIds = runCatching { container.deviceCalendars.writableCalendars() }
                .getOrDefault(emptyList())
                .map(CalendarAccount::id)
                .toSet()
            val calendars = runCatching { container.deviceCalendars.syncedCalendars() }

            _state.value = CalendarSetupState(
                permissionGranted = true,
                calendars = calendars.getOrDefault(emptyList()).map { calendar ->
                    CalendarOption(
                        id = calendar.id,
                        label = SettingsTextPolicy.sanitizeProviderLabel(calendar.displayName),
                        accountName = SettingsTextPolicy.sanitizeProviderLabel(calendar.accountName),
                        accountType = SettingsTextPolicy.sanitizeProviderLabel(calendar.accountType),
                        writable = calendar.id in writableIds,
                    )
                },
                pinnedCalendarId = settings?.defaultCalendarId,
                pinnedCalendarLabel = settings?.defaultCalendarLabel,
                readCalendarIds = settings?.readCalendarIds.orEmpty() +
                    listOfNotNull(settings?.defaultCalendarId),
                error = if (calendars.isFailure) "캘린더 목록을 읽지 못했습니다." else null,
            )
        }
    }

    fun onPermissionResult(granted: Boolean, canAskAgain: Boolean) {
        _state.update { current ->
            current.copy(permissionPermanentlyDenied = !granted && !canAskAgain)
        }
        refresh()
    }

    fun pin(option: CalendarOption) {
        if (!option.writable) {
            _state.update { current ->
                current.copy(error = "이 캘린더에는 쓸 수 없습니다. 동기화 설정을 확인하세요.")
            }
            return
        }
        scope.launch {
            runCatching {
                val current = container.settings.current()
                container.settings.setReadCalendarIds(current.readCalendarIds + option.id)
                container.settings.setDefaultCalendar(option.id, option.label)
            }
            refresh()
        }
    }

    fun unpin() {
        scope.launch {
            runCatching {
                val current = container.settings.current()
                current.defaultCalendarId?.let { pinned ->
                    container.settings.setReadCalendarIds(current.readCalendarIds + pinned)
                }
                container.settings.setDefaultCalendar(null, null)
            }
            refresh()
        }
    }

    fun setReadEnabled(calendarId: Long, enabled: Boolean) {
        scope.launch {
            val current = runCatching { container.settings.current() }.getOrNull()
                ?: return@launch
            val updated = if (enabled) {
                current.readCalendarIds + calendarId
            } else {
                current.readCalendarIds - calendarId
            } + listOfNotNull(current.defaultCalendarId)
            runCatching { container.settings.setReadCalendarIds(updated) }
            refresh()
        }
    }

    private fun hasReadPermission(): Boolean = ContextCompat.checkSelfPermission(
        application,
        Manifest.permission.READ_CALENDAR,
    ) == PackageManager.PERMISSION_GRANTED
}
