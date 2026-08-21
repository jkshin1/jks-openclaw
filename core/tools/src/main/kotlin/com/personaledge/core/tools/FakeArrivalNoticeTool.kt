package com.personaledge.core.tools

import java.util.Base64

data class FakeArrivalNoticeParams(
    val recipient: String,
    val message: String,
) : ToolParams

data class FakeArrivalNoticeResult(
    val simulated: Boolean = true,
)

/**
 * Confirmation-path demonstration only. It performs no external or local side effect,
 * so READ_ONLY is truthful while [minimumConfirmation] still exercises explicit consent.
 */
class FakeArrivalNoticeTool : AgentTool<FakeArrivalNoticeParams, FakeArrivalNoticeResult> {
    override val descriptor = ToolDescriptor(
        name = NAME,
        description = "Simulate an arrival notice without sending a real message",
        risk = ToolRisk.READ_ONLY,
        minimumConfirmation = ConfirmationRequirement.UserConfirmation,
    )

    override suspend fun validateAndCanonicalize(
        params: FakeArrivalNoticeParams,
    ): ValidationResult {
        val recipient = params.recipient.trim()
        val message = params.message.trim()

        if (recipient.isEmpty() || recipient.codePointLength() > MAX_RECIPIENT_LENGTH) {
            return ValidationResult.Invalid("Recipient must contain 1 to 40 characters.")
        }
        if (message.isEmpty() || message.codePointLength() > MAX_MESSAGE_LENGTH) {
            return ValidationResult.Invalid("Message must contain 1 to 240 characters.")
        }
        if (recipient.any(Char::isISOControl) || message.any(Char::isISOControl)) {
            return ValidationResult.Invalid("Control characters are not allowed.")
        }
        if (recipient.hasPinnedModelControlDelimiter() || message.hasPinnedModelControlDelimiter()) {
            return ValidationResult.Invalid("Model control-token delimiters are not allowed.")
        }

        return ValidationResult.Valid(encode(recipient, message))
    }

    override fun preview(input: CanonicalToolInput): ActionPreview {
        val (recipient, message) = decode(input)
        return ActionPreview(
            title = "가짜 도착 알림",
            summary = "$recipient 님에게 \"$message\" 전송을 시뮬레이션할까요?",
        )
    }

    override suspend fun execute(
        input: CanonicalToolInput,
        permit: ExecutionPermit,
    ): FakeArrivalNoticeResult {
        decode(input)
        return FakeArrivalNoticeResult()
    }

    private fun String.codePointLength(): Int = codePointCount(0, length)

    private fun String.hasPinnedModelControlDelimiter(): Boolean =
        contains(MODEL_CONTROL_TOKEN_OPEN) || contains(MODEL_CONTROL_TOKEN_CLOSE)

    private fun encode(recipient: String, message: String): String = listOf(recipient, message)
        .joinToString(SEPARATOR) { value ->
            Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
        }

    private fun decode(input: CanonicalToolInput): Pair<String, String> {
        val parts = input.encoded.split(SEPARATOR, limit = 3)
        check(parts.size == 2) { "Invalid canonical fake-tool input." }
        return parts[0].decodeBase64() to parts[1].decodeBase64()
    }

    private fun String.decodeBase64(): String = String(
        Base64.getUrlDecoder().decode(this),
        Charsets.UTF_8,
    )

    companion object {
        const val NAME = "fake_arrival_notice"
        const val MAX_RECIPIENT_LENGTH = 40
        const val MAX_MESSAGE_LENGTH = 240
        private const val SEPARATOR = "."
        private const val MODEL_CONTROL_TOKEN_OPEN = "<|"
        private const val MODEL_CONTROL_TOKEN_CLOSE = "|>"
    }
}
