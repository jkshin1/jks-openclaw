package com.personaledge.agent

import com.personaledge.core.data.AgentSettings
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Owns the two-gate notification-capture state and retention reconciliation. */
class NotificationCaptureCoordinator(
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(NotificationSetupState())
    val state: StateFlow<NotificationSetupState> = _state.asStateFlow()
    private val refreshSequence = AtomicLong(0)

    /** Re-read on resume because notification-listener access can be revoked outside the app. */
    fun refresh() {
        val sequence = refreshSequence.incrementAndGet()
        scope.launch {
            val settings = runCatching { container.settings.current() }.getOrNull()
            val storedCount = settings?.let { current ->
                runCatching {
                    container.notificationCaptureInterlock.withMutationBoundary {
                        container.notifications.prune(current.notificationRetentionDays)
                        container.notifications.count()
                    }
                }.getOrDefault(0)
            } ?: 0
            val refreshed = NotificationSetupState(
                accessGranted = container.notificationGateway.accessGranted(),
                captureEnabled = settings?.let { current ->
                    container.notificationCaptureInterlock.captureAllowed(
                        current.notificationCaptureEnabled,
                    )
                } ?: false,
                replyEnabled = settings?.let { current ->
                    container.notificationCaptureInterlock.replyAllowed(
                        current.kakaoNotificationReplyEnabled,
                    )
                } ?: false,
                storedCount = storedCount,
                retentionDays = settings?.notificationRetentionDays
                    ?: AgentSettings.DEFAULT_NOTIFICATION_RETENTION_DAYS,
            )
            if (refreshSequence.get() == sequence) _state.value = refreshed
        }
    }

    fun setCaptureEnabled(enabled: Boolean) {
        val request = container.notificationCaptureInterlock.requestCaptureEnabled(enabled)
        if (!enabled) {
            refreshSequence.incrementAndGet()
            _state.value = _state.value.copy(captureEnabled = false)
        }
        container.notificationMutationScope.launch {
            runCatching {
                container.notificationCaptureInterlock.setCaptureEnabled(
                    settings = container.settings,
                    request = request,
                )
            }
            refresh()
        }
    }

    fun setReplyEnabled(enabled: Boolean) {
        val request = container.notificationCaptureInterlock.requestReplyEnabled(enabled)
        if (!enabled) {
            refreshSequence.incrementAndGet()
            _state.value = _state.value.copy(replyEnabled = false)
        }
        container.notificationMutationScope.launch {
            runCatching {
                container.notificationCaptureInterlock.setReplyEnabled(
                    settings = container.settings,
                    request = request,
                )
            }
            refresh()
        }
    }

    /** Delete captured rows only; the Android listener grant is deliberately unchanged. */
    fun deleteCaptured() {
        // Listener callbacks already queued before this owner request may not repopulate the store
        // after deletion. An in-flight capture linearized earlier completes before deleteAll().
        container.notificationCaptureInterlock.invalidatePendingCaptures()
        container.notificationMutationScope.launch {
            container.notificationCaptureInterlock.withMutationBoundary {
                container.notificationGateway.deleteAll()
            }
            refresh()
        }
    }
}
