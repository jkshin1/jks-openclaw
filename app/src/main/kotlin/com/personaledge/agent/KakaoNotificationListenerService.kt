package com.personaledge.agent

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.personaledge.core.data.NotificationRepository
import com.personaledge.core.data.CommitmentProposalRepository
import com.personaledge.core.data.SettingsRepository
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Applies the capture gate and writes the result.
 *
 * Separated from the service so every rule — the settings gate, the allowlist, retention — is
 * testable against a real database without constructing framework objects.
 */
class NotificationCaptureSink(
    private val settings: SettingsRepository,
    private val notifications: NotificationRepository,
    private val proposals: CommitmentProposalRepository? = null,
    private val captureInterlock: NotificationCaptureInterlock = NotificationCaptureInterlock(),
    private val ownerConsentInterlock: OwnerConsentInterlock = OwnerConsentInterlock(),
    private val allowedPackages: Set<String> = NotificationCapture.DEFAULT_ALLOWED_PACKAGES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lastPruneAtEpochMillis = AtomicLong(0)

    /** Returns true when the post was stored. Every rejection path is silent by design. */
    suspend fun accept(post: NotificationPost): Boolean {
        val request = prepareCapture() ?: return false
        return accept(post, request)
    }

    /** Called synchronously in the listener callback before its IO coroutine can be reordered. */
    internal fun prepareCapture(): NotificationCaptureInterlock.CaptureRequest? =
        captureInterlock.prepareCapture()

    internal suspend fun accept(
        post: NotificationPost,
        request: NotificationCaptureInterlock.CaptureRequest,
    ): Boolean {
        return captureInterlock.withCaptureBoundary(request) {
            val current = runCatching { settings.current() }.getOrNull()
                ?: return@withCaptureBoundary false
            // Re-read on every post while holding the same boundary as setting changes.
            if (!captureInterlock.captureAllowed(current.notificationCaptureEnabled)) {
                return@withCaptureBoundary false
            }

            val draft = NotificationCapture.extract(post, allowedPackages)
                ?: return@withCaptureBoundary false
            val stored = runCatching { notifications.capture(draft) }.getOrDefault(false)
            val proposalStore = proposals
            if (stored && proposalStore != null) {
                CommitmentProposalDetector.detect(draft)?.let { proposal ->
                    val latest = runCatching { settings.current() }.getOrNull()
                    if (
                        latest != null && ownerConsentInterlock.allowed(
                            OwnerConsentFeature.COMMITMENT_PROPOSALS,
                            latest.commitmentProposalsEnabled,
                        )
                    ) {
                        runCatching { proposalStore.offer(proposal) }
                    }
                }
            }
            if (stored) pruneIfDue(current.notificationRetentionDays)
            stored
        } ?: false
    }

    /** Pruning on every message would be wasteful; hourly keeps the bound without the cost. */
    private suspend fun pruneIfDue(retentionDays: Int) {
        val now = clock()
        val last = lastPruneAtEpochMillis.get()
        if (now - last < PRUNE_INTERVAL_MILLIS) return
        if (!lastPruneAtEpochMillis.compareAndSet(last, now)) return

        runCatching { notifications.prune(retentionDays) }
    }

    private companion object {
        const val PRUNE_INTERVAL_MILLIS = 60L * 60 * 1_000
    }
}

/**
 * Captures KakaoTalk message notifications so they can be searched later.
 *
 * Notification access is a broad grant: once given, this service sees every notification on the
 * device. The narrowing happens here and in [NotificationCapture] — a package allowlist, a user
 * setting checked on every post, and no storage of anything else. Removal notifications are
 * ignored: a message that was read is still a message that arrived.
 */
class KakaoNotificationListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sink: NotificationCaptureSink? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as? PersonalEdgeApplication)?.container ?: return
        sink = NotificationCaptureSink(
            settings = container.settings,
            notifications = container.notifications,
            proposals = container.commitmentProposals,
            captureInterlock = container.notificationCaptureInterlock,
            ownerConsentInterlock = container.ownerConsentInterlock,
        )
    }

    override fun onDestroy() {
        KakaoNotificationReplyBridge.detach(this)
        scope.cancel()
        sink = null
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        KakaoNotificationReplyBridge.attach(this)
    }

    override fun onListenerDisconnected() {
        KakaoNotificationReplyBridge.detach(this)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(statusBarNotification: StatusBarNotification?) {
        // The listener grant exposes every package. Reject by package before even asking Android
        // for the Notification/Bundle so another app's text is neither materialized nor parsed.
        if (!NotificationCapture.isAllowedPackage(statusBarNotification?.packageName)) return
        val target = sink ?: return
        val captureRequest = target.prepareCapture() ?: return
        val notification = statusBarNotification?.notification ?: return
        val post = NotificationPostReader.read(
            packageName = statusBarNotification.packageName,
            sourceKey = statusBarNotification.key,
            postedAtEpochMillis = statusBarNotification.postTime,
            flags = notification.flags,
            extras = notification.extras,
        ) ?: return
        scope.launch { target.accept(post, captureRequest) }
    }
}

/**
 * Reads a posted notification's extras into plain values.
 *
 * Split out from the service so the `MessagingStyle` bundle parsing — the part most likely to be
 * wrong — is testable with a plain [Bundle]. Every field is read defensively: the bundle is built
 * by another app, and a missing or unexpectedly typed entry must read as absent rather than throw
 * inside a system callback.
 */
object NotificationPostReader {
    fun read(
        packageName: String?,
        sourceKey: String?,
        postedAtEpochMillis: Long,
        flags: Int,
        extras: Bundle?,
    ): NotificationPost? {
        if (extras == null) return null

        return NotificationPost(
            packageName = packageName.orEmpty(),
            sourceKey = sourceKey.orEmpty(),
            postedAtEpochMillis = postedAtEpochMillis,
            isGroupSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0,
            isOngoing = flags and Notification.FLAG_ONGOING_EVENT != 0,
            title = extras.charSequence(Notification.EXTRA_TITLE),
            text = extras.charSequence(Notification.EXTRA_TEXT),
            conversationTitle = extras.charSequence(Notification.EXTRA_CONVERSATION_TITLE),
            messagingSender = extras.newestMessagingField(MESSAGING_SENDER),
            messagingText = extras.newestMessagingField(MESSAGING_TEXT),
        )
    }

    private fun Bundle.charSequence(key: String): String? =
        runCatching { getCharSequence(key)?.toString() }.getOrNull()

    /** Reads the newest `MessagingStyle` entry, which is the message that just arrived. */
    private fun Bundle.newestMessagingField(field: String): String? = runCatching {
        @Suppress("DEPRECATION")
        val messages = getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        messages
            .filterIsInstance<Bundle>()
            .lastOrNull { message -> message.getCharSequence(MESSAGING_TEXT) != null }
            ?.getCharSequence(field)
            ?.toString()
    }.getOrNull()

    private const val MESSAGING_TEXT = "text"
    private const val MESSAGING_SENDER = "sender"
}
