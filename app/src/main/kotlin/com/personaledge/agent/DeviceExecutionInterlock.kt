package com.personaledge.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
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
    private val calendarIsReadable: suspend (Long) -> Boolean,
    private val alarmGateway: AlarmGateway,
    private val notificationGateway: StoredNotificationGateway,
    private val networkConsent: suspend (String) -> Boolean,
    private val memoryConsent: suspend () -> Boolean = { false },
    private val proposalConsent: suspend () -> Boolean = { false },
    private val kakaoShareAvailable: () -> Boolean = { false },
    private val kakaoReplyEnabled: suspend () -> Boolean = { false },
    private val kakaoReplyAvailable: () -> Boolean = { false },
    private val readCalendarIds: suspend () -> Set<Long> = {
        pinnedCalendarId()?.let(::setOf).orEmpty()
    },
    private val sideEffectingToolsEnabled: Boolean,
) : ExecutionInterlock {
    private val applicationContext = context.applicationContext

    override suspend fun evaluate(request: InterlockRequest): InterlockDecision {
        SideEffectingToolPolicy.blockReason(sideEffectingToolsEnabled, request.risk)?.let { reason ->
            return InterlockDecision.Block(reason)
        }

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
            missingRequirement(capability, request.toolName)?.let { reason ->
                return InterlockDecision.Block(reason)
            }
        }

        return InterlockDecision.Allow
    }

    private suspend fun missingRequirement(
        capability: ToolCapability,
        toolName: String,
    ): String? = when (capability) {
        ToolCapability.READ_CALENDAR -> permissionReason(
            permission = Manifest.permission.READ_CALENDAR,
            reason = "캘린더 읽기 권한이 없습니다. 설정에서 허용해 주세요.",
        ) ?: calendarReadScopeReason()
        ToolCapability.WRITE_CALENDAR -> permissionReason(
            permission = Manifest.permission.WRITE_CALENDAR,
            reason = "캘린더 쓰기 권한이 없습니다. 설정에서 허용해 주세요.",
        ) ?: pinnedCalendarReason()
        ToolCapability.SCHEDULE_ALARM -> clockAppReason()
        ToolCapability.READ_NOTIFICATIONS -> notificationCaptureReason()
        ToolCapability.NETWORK -> networkReason(toolName)
        ToolCapability.WRITE_MEMORY -> memoryReason()
        ToolCapability.WRITE_PROPOSALS -> proposalReason()
        ToolCapability.POST_NOTIFICATIONS -> notificationPostingReason()
        ToolCapability.OPEN_KAKAO_SHARE -> kakaoShareReason()
        ToolCapability.REPLY_KAKAO_NOTIFICATION -> kakaoReplyReason()
    }

    private fun permissionReason(permission: String, reason: String): String? =
        if (ContextCompat.checkSelfPermission(applicationContext, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            null
        } else {
            reason
        }

    private suspend fun pinnedCalendarReason(): String? {
        val pinned = try {
            pinnedCalendarId()
        } catch (_: Exception) {
            return "선택한 캘린더 상태를 확인하지 못했습니다. 설정에서 다시 선택하세요."
        } ?: return "설정에서 사용할 캘린더를 먼저 선택하세요."

        val readable = try {
            calendarIsReadable(pinned)
        } catch (_: Exception) {
            false
        }
        return if (readable) {
            null
        } else {
            "선택한 캘린더를 더 이상 읽을 수 없습니다. 설정에서 다시 선택하세요."
        }
    }

    private suspend fun calendarReadScopeReason(): String? {
        val ids = try {
            readCalendarIds().filter { it > 0 }.toSet()
        } catch (_: Exception) {
            return "캘린더 읽기 범위를 확인하지 못했습니다. 설정에서 다시 선택하세요."
        }
        if (ids.isEmpty()) return "설정에서 읽을 캘린더를 먼저 선택하세요."
        val allReadable = ids.all { id ->
            runCatching { calendarIsReadable(id) }.getOrDefault(false)
        }
        return if (allReadable) null else {
            "읽기 범위의 캘린더 하나 이상을 사용할 수 없습니다. 설정에서 다시 선택하세요."
        }
    }

    /**
     * The clock app can be disabled or uninstalled between preparation and execution, and
     * `ACTION_SET_ALARM` gives no result, so an unhandled intent would look like success.
     */
    /**
     * Two separate gates. Notification access can stay granted long after the user turns capture
     * off, and reading a stale store in that state would leak messages the user asked to stop
     * collecting.
     */
    private suspend fun notificationCaptureReason(): String? = when {
        !notificationGateway.accessGranted() ->
            "알림 접근 권한이 없습니다. 설정에서 허용해 주세요."
        !notificationGateway.captureEnabled() ->
            "알림 수집이 꺼져 있습니다. 설정에서 켜 주세요."
        else -> null
    }

    private fun kakaoShareReason(): String? =
        if (runCatching(kakaoShareAvailable).getOrDefault(false)) null
        else "카카오톡 공유 화면을 열 수 없습니다. 카카오톡 설치 상태를 확인해 주세요."

    private suspend fun kakaoReplyReason(): String? = when {
        !notificationGateway.accessGranted() ->
            "알림 접근 권한이 없습니다. 설정에서 허용해 주세요."
        !runCatching { kakaoReplyEnabled() }.getOrDefault(false) ->
            "카카오톡 알림 답장이 꺼져 있습니다. 설정에서 먼저 켜 주세요."
        !runCatching(kakaoReplyAvailable).getOrDefault(false) ->
            "카카오톡 알림 연결을 확인할 수 없습니다. 알림 접근 설정을 다시 확인해 주세요."
        else -> null
    }

    /**
     * Refuses while offline rather than letting the request time out.
     *
     * This checks reachability, not credentials: each network tool verifies its own key during
     * validation, because a missing key is a settings problem with a different remedy.
     */
    private suspend fun networkReason(toolName: String): String? {
        val consented = runCatching { networkConsent(toolName) }.getOrDefault(false)
        if (!consented) return NetworkToolConsent.disabledReason(toolName)

        val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return "네트워크 상태를 확인할 수 없습니다."
        val capabilities = connectivity.activeNetwork
            ?.let(connectivity::getNetworkCapabilities)
            ?: return "네트워크에 연결되어 있지 않습니다."

        val reachability = NetworkReachabilityPolicy.evaluate(
            hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            isNotSuspended = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED),
        )
        return when (reachability) {
            NetworkReachability.UNAVAILABLE -> "네트워크에 연결되어 있지 않습니다."
            NetworkReachability.UNVALIDATED ->
                "인터넷 연결이 확인되지 않았습니다. 로그인 화면이나 캡티브 포털을 확인해 주세요."
            NetworkReachability.SUSPENDED -> "네트워크 연결이 일시 중지되어 있습니다."
            NetworkReachability.USABLE -> null
        }
    }

    private suspend fun clockAppReason(): String? = try {
        if (alarmGateway.clockAppAvailable()) null else "알람을 처리할 시계 앱이 없습니다."
    } catch (_: Exception) {
        "시계 앱을 확인하지 못했습니다."
    }

    private suspend fun memoryReason(): String? =
        if (runCatching { memoryConsent() }.getOrDefault(false)) {
            null
        } else {
            "장기 기억이 꺼져 있습니다. 설정에서 먼저 켜 주세요."
        }

    private suspend fun proposalReason(): String? =
        if (runCatching { proposalConsent() }.getOrDefault(false)) {
            null
        } else {
            "일정 후보 제안함이 꺼져 있습니다. 리마인더 설정에서 먼저 켜 주세요."
        }

    private fun notificationPostingReason(): String? = when {
        !NotificationPermissionPolicy.isGranted(applicationContext) ->
            "리마인더 알림 권한이 없습니다. 설정에서 알림을 허용해 주세요."
        !NotificationManagerCompat.from(applicationContext).areNotificationsEnabled() ->
            "앱 알림이 시스템 설정에서 꺼져 있습니다. 리마인더 알림을 허용해 주세요."
        else -> null
    }
}

/** Pure, variant-fed policy so the candidate write prohibition also has host regression coverage. */
internal object SideEffectingToolPolicy {
    private const val BLOCK_REASON =
        "후보 모델 검증 빌드는 읽기 전용 도구만 허용합니다."

    fun blockReason(sideEffectingToolsEnabled: Boolean, risk: ToolRisk): String? =
        if (!sideEffectingToolsEnabled && risk != ToolRisk.READ_ONLY) BLOCK_REASON else null
}

internal enum class NetworkReachability {
    UNAVAILABLE,
    UNVALIDATED,
    SUSPENDED,
    USABLE,
}

/** Pure policy kept separate so captive-portal and suspended states have host regression coverage. */
internal object NetworkReachabilityPolicy {
    fun evaluate(
        hasInternet: Boolean,
        isValidated: Boolean,
        isNotSuspended: Boolean,
    ): NetworkReachability = when {
        !hasInternet -> NetworkReachability.UNAVAILABLE
        !isValidated -> NetworkReachability.UNVALIDATED
        !isNotSuspended -> NetworkReachability.SUSPENDED
        else -> NetworkReachability.USABLE
    }
}
