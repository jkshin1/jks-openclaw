package com.personaledge.agent

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.personaledge.core.data.CapturedMessage
import com.personaledge.core.data.NotificationRepository
import com.personaledge.core.data.SettingsRepository
import com.personaledge.core.tools.CapturedMessageSummary
import com.personaledge.core.tools.NotificationGateway
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first

/**
 * Serves the search tool from the local capture store.
 *
 * The package allowlist is applied again at read time. Capture already filters, but a package
 * removed from the allowlist later must also stop being readable, and the stored rows outlive that
 * change.
 */
class StoredNotificationGateway(
    context: Context,
    private val notifications: NotificationRepository,
    private val settings: SettingsRepository,
    private val captureInterlock: NotificationCaptureInterlock = NotificationCaptureInterlock(),
    private val allowedPackages: Set<String> = NotificationCapture.DEFAULT_ALLOWED_PACKAGES,
    private val zoneProvider: () -> ZoneId = ZoneId::systemDefault,
) : NotificationGateway {
    private val applicationContext = context.applicationContext

    /** True when the user granted notification access to this app in system settings. */
    fun accessGranted(): Boolean = runCatching {
        applicationContext.packageName in
            NotificationManagerCompat.getEnabledListenerPackages(applicationContext)
    }.getOrDefault(false)

    suspend fun captureEnabled(): Boolean = captureInterlock.withMutationBoundary {
        runCatching {
            captureInterlock.captureAllowed(settings.settings.first().notificationCaptureEnabled)
        }.getOrDefault(false)
    }

    /**
     * This is read on every settings-screen resume, so it also clears rows that expired while the
     * listener was idle. A settings failure returns no count rather than guessing a wider window
     * or exposing a stale count; maintenance resumes when the settings store recovers.
     */
    suspend fun storedCount(): Long = runCatching {
        notifications.prune(settings.current().notificationRetentionDays)
        notifications.count()
    }.getOrDefault(0)

    suspend fun deleteAll(): Int = runCatching { notifications.deleteAll() }.getOrDefault(0)

    override suspend fun search(
        query: String?,
        postedAtOrAfter: Long,
        limit: Int,
    ): List<CapturedMessageSummary> = captureInterlock.withMutationBoundary {
        val current = settings.current()
        if (!captureInterlock.captureAllowed(current.notificationCaptureEnabled)) {
            return@withMutationBoundary emptyList()
        }
        notifications.search(
            packageNames = allowedPackages.toList(),
            query = query,
            postedAtOrAfter = postedAtOrAfter,
            retentionDays = current.notificationRetentionDays,
            limit = limit,
        ).map { message -> message.toSummary(zoneProvider()) }
    }
}

private val RECEIVED_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun CapturedMessage.toSummary(zone: ZoneId) = CapturedMessageSummary(
    conversation = conversationTitle.ifBlank { "(대화방 이름 없음)" },
    sender = sender.takeIf(String::isNotBlank),
    text = text,
    receivedAt = Instant.ofEpochMilli(postedAtEpochMillis).atZone(zone).format(RECEIVED_AT_FORMAT),
)
