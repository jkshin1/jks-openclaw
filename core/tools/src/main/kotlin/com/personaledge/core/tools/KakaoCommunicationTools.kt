package com.personaledge.core.tools

data class KakaoShareMessageParams(
    val recipient: String?,
    val message: String,
) : ToolParams

data class KakaoShareMessageResult(
    val shareOpened: Boolean,
    /** A share sheet cannot prove that the user pressed KakaoTalk's final send button. */
    val messageSent: Boolean = false,
    val recipientSelectionRequired: Boolean = true,
    val reason: String? = null,
)

sealed interface KakaoShareOutcome {
    data object Opened : KakaoShareOutcome
    data object KakaoTalkUnavailable : KakaoShareOutcome
    data object StartBlocked : KakaoShareOutcome
}

interface KakaoShareGateway {
    fun kakaoTalkAvailable(): Boolean

    suspend fun openShare(message: String): KakaoShareOutcome
}

/**
 * Opens KakaoTalk's own share target picker with an exact message after confirmation.
 *
 * Android's general share contract cannot select a friend or prove final delivery. The optional
 * recipient is therefore a confirmation hint only; KakaoTalk remains responsible for target
 * selection and the final send button.
 */
class KakaoShareMessageTool(
    private val gateway: KakaoShareGateway,
) : AgentTool<KakaoShareMessageParams, KakaoShareMessageResult> {
    override val descriptor = ToolDescriptor(
        name = NAME,
        description = "Open KakaoTalk's target picker with an exact message. The user must choose " +
            "the recipient and press KakaoTalk's final send button.",
        risk = ToolRisk.COMMUNICATION,
        requiredCapabilities = setOf(ToolCapability.OPEN_KAKAO_SHARE),
    )

    override suspend fun validateAndCanonicalize(params: KakaoShareMessageParams): ValidationResult {
        val recipient = params.recipient?.trim()?.takeIf(String::isNotEmpty)
        val message = params.message.trim()
        if (recipient != null && CalendarText.codePointLength(recipient) > MAX_RECIPIENT_CHARACTERS) {
            return ValidationResult.Invalid("받는 사람 힌트는 ${MAX_RECIPIENT_CHARACTERS}자 이하여야 합니다.")
        }
        if (message.isEmpty() || CalendarText.codePointLength(message) > MAX_MESSAGE_CHARACTERS) {
            return ValidationResult.Invalid("메시지는 1자 이상 ${MAX_MESSAGE_CHARACTERS}자 이하여야 합니다.")
        }
        if ((recipient != null && !CalendarText.isSafeText(recipient)) ||
            !CalendarText.isSafeText(message)
        ) {
            return ValidationResult.Invalid("받는 사람 또는 메시지에 허용되지 않는 문자가 있습니다.")
        }
        if (!gateway.kakaoTalkAvailable()) {
            return ValidationResult.Invalid("카카오톡 앱을 찾을 수 없습니다.")
        }
        return ValidationResult.Valid(
            CanonicalFields.encode(
                buildMap {
                    recipient?.let { put(FIELD_RECIPIENT, it) }
                    put(FIELD_MESSAGE, message)
                },
            ),
        )
    }

    override fun preview(input: CanonicalToolInput): ActionPreview {
        val fields = CanonicalFields.decode(input)
        return ActionPreview(
            title = "카카오톡 공유 열기",
            summary = buildString {
                fields[FIELD_RECIPIENT]?.let { append("받는 사람 힌트: $it\n") }
                append("메시지: \"")
                append(fields.requiredString(FIELD_MESSAGE))
                append("\"\n카카오톡에서 대화방을 직접 선택하고 전송해야 합니다.")
            },
        )
    }

    override suspend fun execute(
        input: CanonicalToolInput,
        permit: ExecutionPermit,
    ): KakaoShareMessageResult {
        val message = CanonicalFields.decode(input).requiredString(FIELD_MESSAGE)
        return when (gateway.openShare(message)) {
            KakaoShareOutcome.Opened -> KakaoShareMessageResult(shareOpened = true)
            KakaoShareOutcome.KakaoTalkUnavailable -> KakaoShareMessageResult(
                shareOpened = false,
                reason = "kakaotalk_unavailable",
            )
            KakaoShareOutcome.StartBlocked -> KakaoShareMessageResult(
                shareOpened = false,
                reason = "start_blocked",
            )
        }
    }

    override fun executionOutcome(result: KakaoShareMessageResult): ToolExecutionOutcome =
        if (result.shareOpened) ToolExecutionOutcome.WRITE_COMPLETED
        else ToolExecutionOutcome.WRITE_REFUSED

    companion object {
        const val NAME = "kakao_share_message"
        const val MAX_RECIPIENT_CHARACTERS = 120
        const val MAX_MESSAGE_CHARACTERS = 500
        internal const val FIELD_RECIPIENT = "recipient"
        internal const val FIELD_MESSAGE = "message"
    }
}

data class KakaoNotificationReplyParams(
    val recipient: String,
    val message: String,
) : ToolParams

data class KakaoNotificationReplyResult(
    /** PendingIntent accepted the RemoteInput request; this is not a delivery/read receipt. */
    val replyRequested: Boolean,
    val messageSent: Boolean = false,
    val reason: String? = null,
)

data class KakaoReplyTarget(
    /** Opaque, process-local SHA-256 fingerprint of the exact Android reply candidate. */
    val token: String,
    val displayLabel: String,
)

sealed interface KakaoReplyResolution {
    data class Available(val target: KakaoReplyTarget) : KakaoReplyResolution
    data object NotFound : KakaoReplyResolution
    data object Ambiguous : KakaoReplyResolution
    data object Unavailable : KakaoReplyResolution
}

sealed interface KakaoReplyOutcome {
    data object Requested : KakaoReplyOutcome
    data object TargetGone : KakaoReplyOutcome
    data object ReplyActionGone : KakaoReplyOutcome
    data object RequestBlocked : KakaoReplyOutcome
}

interface KakaoReplyGateway {
    suspend fun resolveTarget(recipient: String): KakaoReplyResolution

    /** Must re-resolve [targetToken] against active notifications immediately before sending. */
    suspend fun requestReply(targetToken: String, message: String): KakaoReplyOutcome
}

/** Replies only through a currently active KakaoTalk notification with one free-form reply action. */
class KakaoNotificationReplyTool(
    private val gateway: KakaoReplyGateway,
) : AgentTool<KakaoNotificationReplyParams, KakaoNotificationReplyResult> {
    override val descriptor = ToolDescriptor(
        name = NAME,
        description = "Reply to an exact currently active KakaoTalk conversation notification. " +
            "Use only when the user explicitly asks to reply to that conversation.",
        risk = ToolRisk.COMMUNICATION,
        requiredCapabilities = setOf(ToolCapability.REPLY_KAKAO_NOTIFICATION),
    )

    override suspend fun validateAndCanonicalize(
        params: KakaoNotificationReplyParams,
    ): ValidationResult {
        val recipient = params.recipient.trim()
        val message = params.message.trim()
        if (recipient.isEmpty() ||
            CalendarText.codePointLength(recipient) > MAX_RECIPIENT_CHARACTERS
        ) {
            return ValidationResult.Invalid(
                "받는 사람은 1자 이상 ${MAX_RECIPIENT_CHARACTERS}자 이하여야 합니다.",
            )
        }
        if (message.isEmpty() || CalendarText.codePointLength(message) > MAX_MESSAGE_CHARACTERS) {
            return ValidationResult.Invalid("메시지는 1자 이상 ${MAX_MESSAGE_CHARACTERS}자 이하여야 합니다.")
        }
        if (!CalendarText.isSafeText(recipient) || !CalendarText.isSafeText(message)) {
            return ValidationResult.Invalid("받는 사람 또는 메시지에 허용되지 않는 문자가 있습니다.")
        }
        return when (val resolution = gateway.resolveTarget(recipient)) {
            is KakaoReplyResolution.Available -> {
                val targetToken = resolution.target.token
                val displayLabel = resolution.target.displayLabel.trim()
                if (
                    !TARGET_TOKEN.matches(targetToken) ||
                    displayLabel.isEmpty() ||
                    CalendarText.codePointLength(displayLabel) > MAX_RECIPIENT_CHARACTERS ||
                    !CalendarText.isSafeText(displayLabel)
                ) {
                    ValidationResult.Invalid("답장 대상 알림의 신원을 안전하게 확인할 수 없습니다.")
                } else {
                    ValidationResult.Valid(
                        CanonicalFields.encode(
                            mapOf(
                                FIELD_TARGET_TOKEN to targetToken,
                                FIELD_DISPLAY_LABEL to displayLabel,
                                FIELD_MESSAGE to message,
                            ),
                        ),
                    )
                }
            }
            KakaoReplyResolution.NotFound ->
                ValidationResult.Invalid("답장 가능한 해당 카카오톡 알림을 찾지 못했습니다.")
            KakaoReplyResolution.Ambiguous ->
                ValidationResult.Invalid("같은 이름의 답장 가능한 카카오톡 알림이 여러 개입니다.")
            KakaoReplyResolution.Unavailable ->
                ValidationResult.Invalid("카카오톡 알림 답장 상태를 확인할 수 없습니다.")
        }
    }

    override fun preview(input: CanonicalToolInput): ActionPreview {
        val fields = CanonicalFields.decode(input)
        return ActionPreview(
            title = "카카오톡 알림에 답장",
            summary = "${fields.requiredString(FIELD_DISPLAY_LABEL)}에게 " +
                "\"${fields.requiredString(FIELD_MESSAGE)}\" 답장을 요청할까요?",
        )
    }

    override suspend fun execute(
        input: CanonicalToolInput,
        permit: ExecutionPermit,
    ): KakaoNotificationReplyResult {
        val fields = CanonicalFields.decode(input)
        return when (
            gateway.requestReply(
                targetToken = fields.requiredString(FIELD_TARGET_TOKEN),
                message = fields.requiredString(FIELD_MESSAGE),
            )
        ) {
            KakaoReplyOutcome.Requested -> KakaoNotificationReplyResult(replyRequested = true)
            KakaoReplyOutcome.TargetGone -> KakaoNotificationReplyResult(
                replyRequested = false,
                reason = "target_gone",
            )
            KakaoReplyOutcome.ReplyActionGone -> KakaoNotificationReplyResult(
                replyRequested = false,
                reason = "reply_action_gone",
            )
            KakaoReplyOutcome.RequestBlocked -> KakaoNotificationReplyResult(
                replyRequested = false,
                reason = "request_blocked",
            )
        }
    }

    override fun executionOutcome(result: KakaoNotificationReplyResult): ToolExecutionOutcome =
        if (result.replyRequested) ToolExecutionOutcome.WRITE_COMPLETED
        else ToolExecutionOutcome.WRITE_REFUSED

    companion object {
        const val NAME = "kakao_notification_reply"
        const val MAX_RECIPIENT_CHARACTERS = 120
        const val MAX_MESSAGE_CHARACTERS = 500
        private val TARGET_TOKEN = Regex("[0-9a-f]{64}")
        internal const val FIELD_TARGET_TOKEN = "target_token"
        internal const val FIELD_DISPLAY_LABEL = "display_label"
        internal const val FIELD_MESSAGE = "message"
    }
}
