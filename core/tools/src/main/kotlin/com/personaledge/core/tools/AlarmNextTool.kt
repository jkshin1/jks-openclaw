package com.personaledge.core.tools

import java.time.ZoneId

/** The tool takes no arguments; the device has exactly one "next alarm" to report. */
data object AlarmNextParams : ToolParams

data class AlarmNextResult(
    val hasAlarm: Boolean,
    val triggerAt: String?,
)

/**
 * Reports the device's next scheduled alarm clock.
 *
 * Android has no public API to list the alarms in the clock app, so this is the whole of what
 * "알람 조회" can honestly mean: one trigger time, for whichever app scheduled it, with no label
 * and no way to tell a repeating alarm from a one-shot. The tool is named and described so the
 * model cannot mistake it for a list.
 */
class AlarmNextTool(
    private val gateway: AlarmGateway,
    private val zoneProvider: () -> ZoneId = ZoneId::systemDefault,
) : AgentTool<AlarmNextParams, AlarmNextResult> {

    override val descriptor = ToolDescriptor(
        name = NAME,
        description = "Read the single next scheduled alarm time on the device, if any",
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun validateAndCanonicalize(params: AlarmNextParams): ValidationResult =
        ValidationResult.Valid(CanonicalFields.encode(emptyMap()))

    override fun preview(input: CanonicalToolInput): ActionPreview = ActionPreview(
        title = "다음 알람 확인",
        summary = "기기에 예정된 다음 알람 시각을 읽습니다.",
    )

    override suspend fun execute(
        input: CanonicalToolInput,
        permit: ExecutionPermit,
    ): AlarmNextResult {
        val next = gateway.nextAlarm()
        return AlarmNextResult(
            hasAlarm = next != null,
            triggerAt = next?.let { alarm ->
                CalendarText.formatLocalDateTime(alarm.triggerAtEpochMillis, zoneProvider())
            },
        )
    }

    companion object {
        const val NAME = "alarm_next"
    }
}
