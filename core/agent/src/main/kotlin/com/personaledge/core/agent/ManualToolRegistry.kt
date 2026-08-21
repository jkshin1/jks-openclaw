package com.personaledge.core.agent

import com.personaledge.core.llm.LlmToolDefinition
import com.personaledge.core.tools.FakeArrivalNoticeTool

/** Closed registry for the deliberately small first manual-tool vertical slice. */
class ManualToolRegistry(
    fakeArrivalNoticeTool: FakeArrivalNoticeTool = FakeArrivalNoticeTool(),
) {
    private val toolsByName = mapOf(
        FakeArrivalNoticeTool.NAME to RegisteredManualTool.FakeArrivalNotice(
            fakeArrivalNoticeTool,
        ),
    )

    private val registeredDefinitions: List<LlmToolDefinition> = toolsByName.values
        .map(RegisteredManualTool::definition)
        .sortedBy(LlmToolDefinition::name)

    /** Returns a defensive copy so runtime registration cannot mutate registry state. */
    val definitions: List<LlmToolDefinition>
        get() = registeredDefinitions.toList()

    internal fun resolve(name: String): RegisteredManualTool? = toolsByName[name]
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
}

private const val FAKE_ARRIVAL_NOTICE_SCHEMA =
    """{"type":"object","properties":{"recipient":{"type":"string","description":"Person label for the simulated notice; 1 to 40 characters","minLength":1,"maxLength":40},"message":{"type":"string","description":"Arrival message to simulate; 1 to 240 characters","minLength":1,"maxLength":240}},"required":["recipient","message"],"additionalProperties":false}"""
