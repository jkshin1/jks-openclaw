package com.personaledge.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.personaledge.core.diagnostics.DiagnosticThermalStatus
import com.personaledge.core.tools.AlarmGateway
import com.personaledge.core.tools.ExecutionInterlock
import com.personaledge.core.tools.InterlockDecision
import com.personaledge.core.tools.InterlockRequest
import com.personaledge.core.tools.ToolCapability
import com.personaledge.core.tools.ToolRisk

/**
 * The last check before a tool touches the device.
 *
 * It answers three questions the tool itself cannot: is the runtime permission still granted, is
 * the phone cool enough to do more work, and is there still a calendar to write to. All three can
 * change while a confirmation dialog is on screen — a permission revoked from Settings, a thermal
 * step during a long decode, a calendar removed by its sync client — so the orchestrator asks
 * again immediately before the durable claim rather than trusting the answer from preparation.
 *
 * Blocking reasons are app-authored strings. Model output never reaches them.
 */
class DeviceExecutionInterlock(
    context: Context,
    private val thermalStatus: () -> DiagnosticThermalStatus,
    private val pinnedCalendarId: suspend () -> Long?,
    private val alarmGateway: AlarmGateway,
) : ExecutionInterlock {
    private val applicationContext = context.applicationContext

    override suspend fun evaluate(request: InterlockRequest): InterlockDecision {
        // A read is cheap and safe even when the device is warm; only new work is throttled.
        if (request.risk != ToolRisk.READ_ONLY && !ThermalTurnPolicy.canStart(thermalStatus())) {
            return InterlockDecision.Block(
                if (thermalStatus() == DiagnosticThermalStatus.UNKNOWN) {
                    "기기 열 상태를 확인할 수 없어 실행하지 않았습니다."
                } else {
                    "기기 열 보호 정책이 지금은 실행을 허용하지 않습니다."
                },
            )
        }

        request.requiredCapabilities.forEach { capability ->
            missingRequirement(capability)?.let { reason -> return InterlockDecision.Block(reason) }
        }

        return InterlockDecision.Allow
    }

    private suspend fun missingRequirement(capability: ToolCapability): String? = when (capability) {
        ToolCapability.READ_CALENDAR -> permissionReason(
            permission = Manifest.permission.READ_CALENDAR,
            reason = "캘린더 읽기 권한이 없습니다. 설정에서 허용해 주세요.",
        )
        ToolCapability.WRITE_CALENDAR -> permissionReason(
            permission = Manifest.permission.WRITE_CALENDAR,
            reason = "캘린더 쓰기 권한이 없습니다. 설정에서 허용해 주세요.",
        ) ?: pinnedCalendarReason()
        ToolCapability.SCHEDULE_ALARM -> clockAppReason()
        ToolCapability.READ_NOTIFICATIONS,
        ToolCapability.POST_NOTIFICATIONS,
        ToolCapability.NETWORK,
        // Declared but not yet wired. Refusing keeps a future tool from shipping unchecked.
        -> "이 기능은 아직 사용할 수 없습니다."
    }

    private fun permissionReason(permission: String, reason: String): String? =
        if (ContextCompat.checkSelfPermission(applicationContext, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            null
        } else {
            reason
        }

    private suspend fun pinnedCalendarReason(): String? =
        if (pinnedCalendarId() == null) "설정에서 사용할 캘린더를 먼저 선택하세요." else null

    /**
     * The clock app can be disabled or uninstalled between preparation and execution, and
     * `ACTION_SET_ALARM` gives no result, so an unhandled intent would look like success.
     */
    private suspend fun clockAppReason(): String? = try {
        if (alarmGateway.clockAppAvailable()) null else "알람을 처리할 시계 앱이 없습니다."
    } catch (_: Exception) {
        "시계 앱을 확인하지 못했습니다."
    }
}
