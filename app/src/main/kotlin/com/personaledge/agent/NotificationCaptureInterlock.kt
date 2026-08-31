package com.personaledge.agent

import com.personaledge.core.data.SettingsRepository
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-local linearization point for owner notification privacy settings and their effects. */
class NotificationCaptureInterlock internal constructor() {
    private val mutex = Mutex()
    private val replyMutex = Mutex()
    private val requestLock = Any()
    private val disableRequested = AtomicBoolean(false)
    private val replyDisableRequested = AtomicBoolean(false)
    private val latestSettingRequest = AtomicReference<SettingRequest?>()
    private val latestReplySettingRequest = AtomicReference<ReplySettingRequest?>()
    private val captureGeneration = AtomicReference<Any>(Any())

    /** Stops newly arriving posts synchronously, before the DataStore update coroutine runs. */
    internal fun requestCaptureEnabled(enabled: Boolean): SettingRequest = synchronized(requestLock) {
        val request = SettingRequest(enabled)
        latestSettingRequest.set(request)
        if (!enabled) disableRequested.set(true)
        captureGeneration.set(Any())
        request
    }

    internal suspend fun setCaptureEnabled(
        settings: SettingsRepository,
        request: SettingRequest,
    ): Boolean = setCaptureEnabled(request) { enabled ->
        settings.setNotificationCaptureEnabled(enabled)
    }

    internal suspend fun setCaptureEnabled(
        request: SettingRequest,
        persist: suspend (Boolean) -> Unit,
    ): Boolean = mutex.withLock {
        if (!isLatest(request)) return@withLock false
        persist(request.enabled)
        // Enabling becomes visible only after durable settings accepted it. A failed disable
        // deliberately leaves the process gate closed until an explicit retry or restart.
        synchronized(requestLock) {
            if (latestSettingRequest.get() !== request) {
                false
            } else {
                disableRequested.set(!request.enabled)
                true
            }
        }
    }

    /** Captures the listener-callback generation before its coroutine can be reordered. */
    internal fun prepareCapture(): CaptureRequest? {
        if (disableRequested.get()) return null
        val request = CaptureRequest(captureGeneration.get())
        return request.takeIf { isCurrent(it) }
    }

    internal suspend fun <T> withCaptureBoundary(block: suspend () -> T): T? {
        val request = prepareCapture() ?: return null
        return withCaptureBoundary(request, block)
    }

    internal suspend fun <T> withCaptureBoundary(
        request: CaptureRequest,
        block: suspend () -> T,
    ): T? {
        if (!isCurrent(request)) return null
        return mutex.withLock {
            if (!isCurrent(request)) null else block()
        }
    }

    /** Invalidates listener callbacks queued before an owner delete or setting request. */
    internal fun invalidatePendingCaptures() {
        synchronized(requestLock) { captureGeneration.set(Any()) }
    }

    internal suspend fun <T> withMutationBoundary(block: suspend () -> T): T =
        mutex.withLock { block() }

    /** Stops newly confirmed replies synchronously, before the DataStore coroutine can run. */
    internal fun requestReplyEnabled(enabled: Boolean): ReplySettingRequest =
        synchronized(requestLock) {
            val request = ReplySettingRequest(enabled)
            latestReplySettingRequest.set(request)
            if (!enabled) replyDisableRequested.set(true)
            request
        }

    internal suspend fun setReplyEnabled(
        settings: SettingsRepository,
        request: ReplySettingRequest,
    ): Boolean = setReplyEnabled(request) { enabled ->
        settings.setKakaoNotificationReplyEnabled(enabled)
    }

    internal suspend fun setReplyEnabled(
        request: ReplySettingRequest,
        persist: suspend (Boolean) -> Unit,
    ): Boolean = replyMutex.withLock {
        if (!isLatestReply(request)) return@withLock false
        persist(request.enabled)
        // A pending enable never re-opens execution. The gate changes only after its durable write
        // completed and only if no newer owner request superseded it.
        synchronized(requestLock) {
            if (latestReplySettingRequest.get() !== request) {
                false
            } else {
                replyDisableRequested.set(!request.enabled)
                true
            }
        }
    }

    /** Combines durable consent with the synchronous process-local owner-disable gate. */
    internal fun captureAllowed(durableEnabled: Boolean): Boolean =
        durableEnabled && !disableRequested.get()

    /** Combines durable consent with the synchronous process-local owner-disable gate. */
    internal fun replyAllowed(durableEnabled: Boolean): Boolean =
        durableEnabled && !replyDisableRequested.get()

    private fun isCurrent(request: CaptureRequest): Boolean =
        !disableRequested.get() && captureGeneration.get() === request.generationToken

    private fun isLatest(request: SettingRequest): Boolean = synchronized(requestLock) {
        latestSettingRequest.get() === request
    }

    private fun isLatestReply(request: ReplySettingRequest): Boolean = synchronized(requestLock) {
        latestReplySettingRequest.get() === request
    }

    internal class CaptureRequest internal constructor(
        internal val generationToken: Any,
    ) {
        override fun toString(): String = "NotificationCaptureInterlock.CaptureRequest"
    }

    internal class SettingRequest internal constructor(internal val enabled: Boolean) {
        override fun toString(): String = "NotificationCaptureInterlock.SettingRequest"
    }

    internal class ReplySettingRequest internal constructor(internal val enabled: Boolean) {
        override fun toString(): String = "NotificationCaptureInterlock.ReplySettingRequest"
    }
}
