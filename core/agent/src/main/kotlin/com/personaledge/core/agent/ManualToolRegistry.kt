package com.personaledge.core.agent

import com.personaledge.core.llm.LlmToolDefinition
import com.personaledge.core.tools.CalendarCreateEventTool
import com.personaledge.core.tools.CalendarQueryTool
import com.personaledge.core.tools.CalendarUpdateEventTool
import com.personaledge.core.tools.FakeArrivalNoticeTool

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
         * The calendar registry the app ships.
         *
         * The tools reach only the one calendar pinned in settings; on this device that is the
         * NAVER calendar published into `CalendarContract` by a CalDAV sync client.
         */
        fun forCalendar(
            queryTool: CalendarQueryTool,
            createEventTool: CalendarCreateEventTool,
            updateEventTool: CalendarUpdateEventTool,
        ): ManualToolRegistry = ManualToolRegistry(
            mapOf(
                CalendarQueryTool.NAME to RegisteredManualTool.CalendarQuery(queryTool),
                CalendarCreateEventTool.NAME to RegisteredManualTool.CalendarCreateEvent(
                    createEventTool,
                ),
                CalendarUpdateEventTool.NAME to RegisteredManualTool.CalendarUpdateEvent(
                    updateEventTool,
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
