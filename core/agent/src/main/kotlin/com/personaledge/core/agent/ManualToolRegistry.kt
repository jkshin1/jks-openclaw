package com.personaledge.core.agent

import com.personaledge.core.llm.LlmToolDefinition
import com.personaledge.core.tools.AlarmNextTool
import com.personaledge.core.tools.AlarmSetTool
import com.personaledge.core.tools.CalendarCreateEventTool
import com.personaledge.core.tools.CalendarQueryTool
import com.personaledge.core.tools.CalendarUpdateEventTool
import com.personaledge.core.tools.FakeArrivalNoticeTool
import com.personaledge.core.tools.NotificationSearchTool

/**
 * Closed registry. A tool the model names but this map does not contain is never executed, and
 * the same instance supplies both the runtime's tool definitions and later name resolution.
 */
class ManualToolRegistry private constructor(
    private val toolsByName: Map<String, RegisteredManualTool>,
) {
    /** Demonstration-only registry: one simulated tool that performs no side effect. */
    constructor(
        fakeArrivalNoticeTool: FakeArrivalNoticeTool = FakeArrivalNoticeTool(),
    ) : this(
        mapOf(
            FakeArrivalNoticeTool.NAME to RegisteredManualTool.FakeArrivalNotice(
                fakeArrivalNoticeTool,
            ),
        ),
    )

    private val registeredDefinitions: List<LlmToolDefinition> = toolsByName.values
        .map(RegisteredManualTool::definition)
        .sortedBy(LlmToolDefinition::name)

    /** Returns a defensive copy so runtime registration cannot mutate registry state. */
    val definitions: List<LlmToolDefinition>
        get() = registeredDefinitions.toList()

    internal fun resolve(name: String): RegisteredManualTool? = toolsByName[name]

    companion object {
        /**
         * The device registry the app ships.
         *
         * Calendar tools reach only the one CalendarContract row pinned in settings; NAVER
         * identity and remote synchronization must be qualified separately on the physical
         * device. Alarm tools go through the platform `AlarmClock` intent, which can create an
         * alarm but cannot list or edit existing ones.
         *
         * Listing every tool explicitly is the point: a tool absent from this map is never
         * executed, whatever the model names.
         */
        fun forDeviceTools(
            queryTool: CalendarQueryTool,
            createEventTool: CalendarCreateEventTool,
            updateEventTool: CalendarUpdateEventTool,
            alarmSetTool: AlarmSetTool,
            alarmNextTool: AlarmNextTool,
            notificationSearchTool: NotificationSearchTool,
        ): ManualToolRegistry = ManualToolRegistry(
            mapOf(
                CalendarQueryTool.NAME to RegisteredManualTool.CalendarQuery(queryTool),
                CalendarCreateEventTool.NAME to RegisteredManualTool.CalendarCreateEvent(
                    createEventTool,
                ),
                CalendarUpdateEventTool.NAME to RegisteredManualTool.CalendarUpdateEvent(
                    updateEventTool,
                ),
                AlarmSetTool.NAME to RegisteredManualTool.AlarmSet(alarmSetTool),
                AlarmNextTool.NAME to RegisteredManualTool.AlarmNext(alarmNextTool),
                NotificationSearchTool.NAME to RegisteredManualTool.NotificationSearch(
                    notificationSearchTool,
                ),
            ),
        )
    }
}

internal sealed interface RegisteredManualTool {
    val definition: LlmToolDefinition

    data class FakeArrivalNotice(
        val tool: FakeArrivalNoticeTool,
    ) : RegisteredManualTool {
        override val definition = LlmToolDefinition(
            name = tool.descriptor.name,
            description = tool.descriptor.description,
            parametersJsonSchema = FAKE_ARRIVAL_NOTICE_SCHEMA,
        )
    }

    data class CalendarQuery(
        val tool: CalendarQueryTool,
    ) : RegisteredManualTool {
        override val definition = LlmToolDefinition(
            name = tool.descriptor.name,
            description = tool.descriptor.description,
            parametersJsonSchema = CALENDAR_QUERY_SCHEMA,
        )
    }

    data class CalendarCreateEvent(
        val tool: CalendarCreateEventTool,
    ) : RegisteredManualTool {
        override val definition = LlmToolDefinition(
            name = tool.descriptor.name,
            description = tool.descriptor.description,
            parametersJsonSchema = CALENDAR_CREATE_EVENT_SCHEMA,
        )
    }

    data class CalendarUpdateEvent(
        val tool: CalendarUpdateEventTool,
    ) : RegisteredManualTool {
        override val definition = LlmToolDefinition(
            name = tool.descriptor.name,
            description = tool.descriptor.description,
            parametersJsonSchema = CALENDAR_UPDATE_EVENT_SCHEMA,
        )
    }

    data class AlarmSet(
        val tool: AlarmSetTool,
    ) : RegisteredManualTool {
        override val definition = LlmToolDefinition(
            name = tool.descriptor.name,
            description = tool.descriptor.description,
            parametersJsonSchema = ALARM_SET_SCHEMA,
        )
    }

    data class AlarmNext(
        val tool: AlarmNextTool,
    ) : RegisteredManualTool {
        override val definition = LlmToolDefinition(
            name = tool.descriptor.name,
            description = tool.descriptor.description,
            parametersJsonSchema = ALARM_NEXT_SCHEMA,
        )
    }

    data class NotificationSearch(
        val tool: NotificationSearchTool,
    ) : RegisteredManualTool {
        override val definition = LlmToolDefinition(
            name = tool.descriptor.name,
            description = tool.descriptor.description,
            parametersJsonSchema = NOTIFICATION_SEARCH_SCHEMA,
        )
    }
}

private const val FAKE_ARRIVAL_NOTICE_SCHEMA =
    """{"type":"object","properties":{"recipient":{"type":"string","description":"Person label for the simulated notice; 1 to 40 characters","minLength":1,"maxLength":40},"message":{"type":"string","description":"Arrival message to simulate; 1 to 240 characters","minLength":1,"maxLength":240}},"required":["recipient","message"],"additionalProperties":false}"""

// Times are local wall clock without a zone: the device zone is applied by the tool. Ids are
// decimal strings so a JSON number cannot round-trip through a float and change an event.
private const val CALENDAR_QUERY_SCHEMA =
    """{"type":"object","properties":{"start":{"type":"string","description":"Window start as local date-time, for example 2026-08-21T09:00","pattern":"^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$"},"end":{"type":"string","description":"Window end as local date-time; at most 60 days after start","pattern":"^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$"}},"required":["start","end"],"additionalProperties":false}"""

private const val CALENDAR_CREATE_EVENT_SCHEMA =
    """{"type":"object","properties":{"title":{"type":"string","description":"Event title; 1 to 120 characters","minLength":1,"maxLength":120},"start":{"type":"string","description":"Start as local date-time, for example 2026-08-21T09:00","pattern":"^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$"},"end":{"type":"string","description":"End as local date-time; at least one minute after start","pattern":"^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$"},"location":{"type":"string","description":"Optional place name; up to 200 characters","maxLength":200}},"required":["title","start","end"],"additionalProperties":false}"""

private const val CALENDAR_UPDATE_EVENT_SCHEMA =
    """{"type":"object","properties":{"event_id":{"type":"string","description":"Event id exactly as returned by calendar_query","pattern":"^[1-9][0-9]{0,18}$"},"title":{"type":"string","description":"Optional replacement title; 1 to 120 characters","minLength":1,"maxLength":120},"start":{"type":"string","description":"Optional replacement start as local date-time","pattern":"^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$"},"end":{"type":"string","description":"Optional replacement end as local date-time","pattern":"^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$"},"location":{"type":"string","description":"Optional replacement place name; up to 200 characters","maxLength":200}},"required":["event_id"],"additionalProperties":false}"""

// Weekday tokens rather than numbers: a number would have to encode a locale-dependent week start.
private const val ALARM_SET_SCHEMA =
    """{"type":"object","properties":{"time":{"type":"string","description":"Alarm time as 24-hour local HH:mm, for example 07:30","pattern":"^([01]\\d|2[0-3]):[0-5]\\d$"},"label":{"type":"string","description":"Optional alarm name; 1 to 60 characters","minLength":1,"maxLength":60},"days":{"type":"string","description":"Optional repeat days as comma-separated tokens from mon,tue,wed,thu,fri,sat,sun. Omit for a one-shot alarm.","pattern":"^(mon|tue|wed|thu|fri|sat|sun)(,(mon|tue|wed|thu|fri|sat|sun))*$"}},"required":["time"],"additionalProperties":false}"""

// The device exposes one next alarm, so there is nothing to select and no argument to take.
private const val ALARM_NEXT_SCHEMA =
    """{"type":"object","properties":{},"required":[],"additionalProperties":false}"""

// The description states the boundary explicitly: this is a local cache of notifications, not
// KakaoTalk history, so an empty result means "nothing was captured", not "no messages exist".
private const val NOTIFICATION_SEARCH_SCHEMA =
    """{"type":"object","properties":{"query":{"type":"string","description":"Optional text to match against sender, room name, or message body. Omit to list the most recent captured notifications.","maxLength":60},"within_days":{"type":"string","description":"How many days back to search, as a decimal string from 1 to 30. Defaults to 3.","pattern":"^([1-9]|[12][0-9]|30)$"}},"required":[],"additionalProperties":false}"""
