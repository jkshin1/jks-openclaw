package com.personaledge.agent

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.service.notification.StatusBarNotification
import com.personaledge.core.tools.KakaoReplyGateway
import com.personaledge.core.tools.KakaoReplyOutcome
import com.personaledge.core.tools.KakaoReplyResolution
import com.personaledge.core.tools.KakaoReplyTarget
import com.personaledge.core.tools.KakaoShareGateway
import com.personaledge.core.tools.KakaoShareOutcome
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidKakaoShareGateway(context: Context) : KakaoShareGateway {
    private val applicationContext = context.applicationContext

    override fun kakaoTalkAvailable(): Boolean = runCatching {
        shareIntent("").resolveActivity(applicationContext.packageManager) != null
    }.getOrDefault(false)

    override suspend fun openShare(message: String): KakaoShareOutcome =
        withContext(Dispatchers.Main.immediate) {
            val intent = shareIntent(message)
            if (intent.resolveActivity(applicationContext.packageManager) == null) {
                return@withContext KakaoShareOutcome.KakaoTalkUnavailable
            }
            try {
                applicationContext.startActivity(intent)
                KakaoShareOutcome.Opened
            } catch (_: SecurityException) {
                KakaoShareOutcome.StartBlocked
            } catch (_: RuntimeException) {
                KakaoShareOutcome.StartBlocked
            }
        }

    private fun shareIntent(message: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        setPackage(KAKAO_TALK_PACKAGE)
        putExtra(Intent.EXTRA_TEXT, message)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private companion object {
        const val KAKAO_TALK_PACKAGE = "com.kakao.talk"
    }
}

/** Process-local bridge to the one system-owned NotificationListenerService instance. */
object KakaoNotificationReplyBridge {
    private val service = AtomicReference<KakaoNotificationListenerService?>(null)

    internal fun attach(listener: KakaoNotificationListenerService) {
        service.set(listener)
    }

    internal fun detach(listener: KakaoNotificationListenerService) {
        service.compareAndSet(listener, null)
    }

    fun available(): Boolean = service.get() != null

    internal fun currentService(): KakaoNotificationListenerService? = service.get()
}

class AndroidKakaoReplyGateway : KakaoReplyGateway {
    private val resolvedTarget = AtomicReference<ResolvedKakaoReplyTarget?>(null)

    override suspend fun resolveTarget(recipient: String): KakaoReplyResolution =
        withContext(Dispatchers.Main.immediate) {
            val listener = KakaoNotificationReplyBridge.currentService()
                ?: return@withContext KakaoReplyResolution.Unavailable.also {
                    resolvedTarget.set(null)
                }
            val candidates = listener.activeKakaoReplyCandidates()
                ?: return@withContext KakaoReplyResolution.Unavailable.also {
                    resolvedTarget.set(null)
                }
            val normalizedRecipient = KakaoReplyCandidatePolicy.normalizedLabel(recipient)
                ?: return@withContext KakaoReplyResolution.NotFound.also {
                    resolvedTarget.set(null)
                }
            val matches = candidates.filter { candidate ->
                normalizedRecipient in candidate.normalizedLabels
            }
            when (matches.size) {
                0 -> KakaoReplyResolution.NotFound.also { resolvedTarget.set(null) }
                1 -> matches.single().let { match ->
                    resolvedTarget.set(
                        ResolvedKakaoReplyTarget(
                            token = match.token,
                            actionIntent = match.action.actionIntent,
                        ),
                    )
                    KakaoReplyResolution.Available(
                        KakaoReplyTarget(
                            token = match.token,
                            displayLabel = match.displayLabel,
                        ),
                    )
                }
                else -> KakaoReplyResolution.Ambiguous.also { resolvedTarget.set(null) }
            }
        }

    override suspend fun requestReply(
        targetToken: String,
        message: String,
    ): KakaoReplyOutcome = withContext(Dispatchers.Main.immediate) {
        val expected = resolvedTarget.getAndSet(null)
        if (!KakaoReplyCandidatePolicy.isValidToken(targetToken)) {
            return@withContext KakaoReplyOutcome.TargetGone
        }
        if (expected == null || expected.token != targetToken) {
            return@withContext KakaoReplyOutcome.TargetGone
        }
        val listener = KakaoNotificationReplyBridge.currentService()
            ?: return@withContext KakaoReplyOutcome.TargetGone
        listener.requestKakaoReply(targetToken, message, expected.actionIntent)
    }
}

private data class ResolvedKakaoReplyTarget(
    val token: String,
    val actionIntent: PendingIntent,
)

internal data class ActiveKakaoReplyCandidate(
    val token: String,
    val displayLabel: String,
    val normalizedLabels: Set<String>,
    val action: Notification.Action,
    val remoteInputs: Array<RemoteInput>,
)

/**
 * Content-free identity of the exact notification reply action shown at confirmation time.
 *
 * A notification key survives an in-place update. Binding the system post time, normalized labels,
 * RemoteInput keys, and PendingIntent/action identity makes an update invalidate the confirmation
 * instead of redirecting approval to whatever action happens to be current at execution time.
 */
internal data class KakaoReplyCandidateIdentity(
    val notificationKey: String,
    val postedAtEpochMillis: Long,
    val labels: List<String>,
    val remoteInputResultKeys: List<String>,
    val actionCreatorPackage: String,
    val actionCreatorUid: Int,
    val actionSemanticAction: Int,
    val actionToken: Int,
)

internal data class KakaoReplyCandidateBinding(
    val token: String,
    val displayLabel: String,
    val normalizedLabels: Set<String>,
)

/** Pure policy kept independent of Android objects so hostile identity tests run on the JVM. */
internal object KakaoReplyCandidatePolicy {
    private const val FINGERPRINT_DOMAIN =
        "com.personaledge.agent/kakao-reply-candidate/v1"
    private val TOKEN = Regex("[0-9a-f]{64}")

    fun bind(identity: KakaoReplyCandidateIdentity): KakaoReplyCandidateBinding? {
        if (
            identity.notificationKey.length > MAX_NOTIFICATION_KEY_CHARACTERS ||
            identity.notificationKey.isBlank() ||
            !identity.notificationKey.isSafeIdentityText() ||
            identity.postedAtEpochMillis <= 0L ||
            identity.actionCreatorPackage != KAKAO_TALK_PACKAGE ||
            identity.actionCreatorUid < 0 ||
            identity.labels.isEmpty() ||
            identity.remoteInputResultKeys.isEmpty()
        ) {
            return null
        }

        val labels = identity.labels.map { label ->
            val normalized = normalizedLabel(label) ?: return null
            val trimmed = label.trim()
            trimmed to normalized
        }
        val normalizedLabels = labels.mapTo(sortedSetOf()) { (_, normalized) -> normalized }
        if (normalizedLabels.isEmpty()) return null

        val resultKeys = identity.remoteInputResultKeys.map { key ->
            key.takeIf {
                it.length <= MAX_REMOTE_INPUT_KEY_CHARACTERS &&
                    it.isNotBlank() &&
                    it.isSafeIdentityText()
            } ?: return null
        }.distinct().sorted()
        if (resultKeys.isEmpty()) return null

        val token = FramedSha256(FINGERPRINT_DOMAIN).apply {
            field("notificationKey", identity.notificationKey)
            field("postedAtEpochMillis", identity.postedAtEpochMillis.toString())
            values("normalizedLabels", normalizedLabels.toList())
            values("remoteInputResultKeys", resultKeys)
            field("actionCreatorPackage", identity.actionCreatorPackage)
            field("actionCreatorUid", identity.actionCreatorUid.toString())
            field("actionSemanticAction", identity.actionSemanticAction.toString())
            field("actionToken", identity.actionToken.toString())
        }.finish()
        return KakaoReplyCandidateBinding(
            token = token,
            displayLabel = labels.first().first,
            normalizedLabels = normalizedLabels,
        )
    }

    fun normalizedLabel(value: String): String? {
        if (value.length > MAX_LABEL_UTF16_CODE_UNITS) return null
        val trimmed = value.trim()
        if (
            trimmed.isEmpty() ||
            trimmed.codePointCount(0, trimmed.length) > MAX_LABEL_CODE_POINTS ||
            !trimmed.isSafeIdentityText()
        ) {
            return null
        }
        val normalized = Normalizer.normalize(trimmed, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        return normalized.takeIf { candidate ->
            candidate.isNotEmpty() &&
                candidate.codePointCount(0, candidate.length) <= MAX_LABEL_CODE_POINTS &&
                candidate.isSafeIdentityText()
        }
    }

    fun isValidToken(value: String): Boolean = TOKEN.matches(value)

    private fun String.isSafeIdentityText(): Boolean =
        !contains(MODEL_CONTROL_TOKEN_OPEN) &&
            !contains(MODEL_CONTROL_TOKEN_CLOSE) &&
            none(Char::isISOControl) &&
            codePoints().noneMatch { codePoint ->
                when (Character.getType(codePoint)) {
                    Character.FORMAT.toInt(),
                    Character.LINE_SEPARATOR.toInt(),
                    Character.PARAGRAPH_SEPARATOR.toInt(),
                    -> true
                    else -> false
                }
            }

    private class FramedSha256(domain: String) {
        private val digest = MessageDigest.getInstance("SHA-256")

        init {
            frame("domain")
            frame(domain)
        }

        fun field(label: String, value: String) {
            frame(label)
            frame(value)
        }

        fun values(label: String, values: List<String>) {
            frame(label)
            updateLength(values.size)
            values.forEach(::frame)
        }

        fun finish(): String = digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }

        private fun frame(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            updateLength(bytes.size)
            digest.update(bytes)
        }

        private fun updateLength(length: Int) {
            require(length >= 0)
            digest.update(
                byteArrayOf(
                    (length ushr 24).toByte(),
                    (length ushr 16).toByte(),
                    (length ushr 8).toByte(),
                    length.toByte(),
                ),
            )
        }
    }

    private const val MAX_LABEL_CODE_POINTS = 120
    private const val MAX_LABEL_UTF16_CODE_UNITS = MAX_LABEL_CODE_POINTS * 4
    private const val MAX_NOTIFICATION_KEY_CHARACTERS = 1_024
    private const val MAX_REMOTE_INPUT_KEY_CHARACTERS = 256
    private const val MODEL_CONTROL_TOKEN_OPEN = "<|"
    private const val MODEL_CONTROL_TOKEN_CLOSE = "|>"
}

internal fun KakaoNotificationListenerService.activeKakaoReplyCandidates(): List<ActiveKakaoReplyCandidate>? {
    val active = runCatching { activeNotifications?.toList() }.getOrNull() ?: return null
    return active.mapNotNull { notification ->
        runCatching { toKakaoReplyCandidate(notification) }.getOrNull()
    }
}

internal fun KakaoNotificationListenerService.requestKakaoReply(
    targetToken: String,
    message: String,
    expectedActionIntent: PendingIntent,
): KakaoReplyOutcome {
    if (!KakaoReplyCandidatePolicy.isValidToken(targetToken)) {
        return KakaoReplyOutcome.TargetGone
    }
    val active = runCatching { activeNotifications?.toList() }.getOrNull()
        ?: return KakaoReplyOutcome.TargetGone
    // Rebuild the full candidate immediately before sending. A key-only lookup would let an
    // in-place notification update inherit an approval granted to the previous recipient/action.
    val candidate = active.mapNotNull { notification ->
        runCatching { toKakaoReplyCandidate(notification) }.getOrNull()
    }.singleOrNull { current -> current.token == targetToken }
        ?: return KakaoReplyOutcome.TargetGone
    if (candidate.action.actionIntent != expectedActionIntent) {
        return KakaoReplyOutcome.TargetGone
    }
    val fillInIntent = Intent()
    val results = android.os.Bundle().apply {
        candidate.remoteInputs.forEach { input -> putCharSequence(input.resultKey, message) }
    }
    return try {
        RemoteInput.addResultsToIntent(candidate.remoteInputs, fillInIntent, results)
        RemoteInput.setResultsSource(fillInIntent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        candidate.action.actionIntent.send(this, 0, fillInIntent)
        KakaoReplyOutcome.Requested
    } catch (_: PendingIntent.CanceledException) {
        KakaoReplyOutcome.RequestBlocked
    } catch (_: SecurityException) {
        KakaoReplyOutcome.RequestBlocked
    } catch (_: RuntimeException) {
        KakaoReplyOutcome.RequestBlocked
    }
}

private fun toKakaoReplyCandidate(
    status: StatusBarNotification,
): ActiveKakaoReplyCandidate? {
    if (status.packageName != KAKAO_TALK_PACKAGE) return null
    val replyActions = status.notification.actions.orEmpty().mapNotNull { action ->
        val freeFormInputs = action.remoteInputs.orEmpty()
            .filter { input -> !input.isDataOnly && input.allowFreeFormInput }
            .toTypedArray()
        action.takeIf { freeFormInputs.isNotEmpty() }?.let { it to freeFormInputs }
    }
    if (replyActions.size != 1) return null
    val labels = buildList {
        val extras = status.notification.extras
        extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.let(::add)
        extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.let(::add)
        extras?.newestKakaoMessagingSender()?.let(::add)
    }.filter(String::isNotEmpty)
    if (labels.isEmpty()) return null
    val (action, remoteInputs) = replyActions.single()
    val binding = KakaoReplyCandidatePolicy.bind(
        KakaoReplyCandidateIdentity(
            notificationKey = status.key,
            postedAtEpochMillis = status.postTime,
            labels = labels.toList(),
            remoteInputResultKeys = remoteInputs.map { input -> input.resultKey },
            actionCreatorPackage = action.actionIntent.creatorPackage.orEmpty(),
            actionCreatorUid = action.actionIntent.creatorUid,
            actionSemanticAction = action.semanticAction,
            // PendingIntent equality/hashCode are backed by the system operation token. This is
            // process-local and never persisted outside the confirmation's canonical input.
            actionToken = action.actionIntent.hashCode(),
        ),
    ) ?: return null
    return ActiveKakaoReplyCandidate(
        token = binding.token,
        displayLabel = binding.displayLabel,
        normalizedLabels = binding.normalizedLabels,
        action = action,
        remoteInputs = remoteInputs,
    )
}

private fun android.os.Bundle.newestKakaoMessagingSender(): String? = runCatching {
    @Suppress("DEPRECATION")
    val messages = getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
    messages.filterIsInstance<android.os.Bundle>()
        .lastOrNull { bundle -> bundle.getCharSequence("text") != null }
        ?.getCharSequence("sender")
        ?.toString()
}.getOrNull()

private const val KAKAO_TALK_PACKAGE = "com.kakao.talk"
