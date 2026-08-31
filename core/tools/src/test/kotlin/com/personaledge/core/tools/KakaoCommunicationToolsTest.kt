package com.personaledge.core.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KakaoCommunicationToolsTest {
    private class FakeShareGateway : KakaoShareGateway {
        var available = true
        var outcome: KakaoShareOutcome = KakaoShareOutcome.Opened
        val messages = mutableListOf<String>()

        override fun kakaoTalkAvailable(): Boolean = available

        override suspend fun openShare(message: String): KakaoShareOutcome {
            messages += message
            return outcome
        }
    }

    private class FakeReplyGateway : KakaoReplyGateway {
        var resolution: KakaoReplyResolution = KakaoReplyResolution.Available(
            KakaoReplyTarget(VALID_TARGET_TOKEN, "홍길동"),
        )
        var outcome: KakaoReplyOutcome = KakaoReplyOutcome.Requested
        val requests = mutableListOf<Pair<String, String>>()

        override suspend fun resolveTarget(recipient: String): KakaoReplyResolution = resolution

        override suspend fun requestReply(
            targetToken: String,
            message: String,
        ): KakaoReplyOutcome {
            requests += targetToken to message
            return outcome
        }
    }

    private fun ValidationResult.valid(): CanonicalToolInput =
        CanonicalToolInput((this as ValidationResult.Valid).canonicalParams)

    @Test
    fun `share preview and result never claim a message was sent`() = runBlocking {
        val gateway = FakeShareGateway()
        val tool = KakaoShareMessageTool(gateway)
        val input = tool.validateAndCanonicalize(
            KakaoShareMessageParams(recipient = "홍길동", message = " 안녕 "),
        ).valid()

        val preview = tool.preview(input)
        val result = tool.execute(input, ExecutionPermit("action"))

        assertTrue(preview.summary.contains("대화방을 직접 선택"))
        assertEquals(listOf("안녕"), gateway.messages)
        assertTrue(result.shareOpened)
        assertFalse(result.messageSent)
        assertTrue(result.recipientSelectionRequired)
        assertEquals(ToolExecutionOutcome.WRITE_COMPLETED, tool.executionOutcome(result))
    }

    @Test
    fun `share refuses an unavailable app before confirmation`() = runBlocking {
        val gateway = FakeShareGateway().apply { available = false }
        val result = KakaoShareMessageTool(gateway).validateAndCanonicalize(
            KakaoShareMessageParams(null, "안녕"),
        )

        assertTrue(result is ValidationResult.Invalid)
        assertTrue(gateway.messages.isEmpty())
    }

    @Test
    fun `notification reply uses the confirmed opaque target and exact message`() = runBlocking {
        val gateway = FakeReplyGateway()
        val tool = KakaoNotificationReplyTool(gateway)
        val input = tool.validateAndCanonicalize(
            KakaoNotificationReplyParams("홍길동", "곧 도착해"),
        ).valid()

        val result = tool.execute(input, ExecutionPermit("action"))

        assertEquals(listOf(VALID_TARGET_TOKEN to "곧 도착해"), gateway.requests)
        assertTrue(result.replyRequested)
        assertFalse(result.messageSent)
        assertEquals(ToolExecutionOutcome.WRITE_COMPLETED, tool.executionOutcome(result))
    }

    @Test
    fun `ambiguous notification target is refused before confirmation`() = runBlocking {
        val gateway = FakeReplyGateway().apply { resolution = KakaoReplyResolution.Ambiguous }
        val result = KakaoNotificationReplyTool(gateway).validateAndCanonicalize(
            KakaoNotificationReplyParams("가족방", "안녕"),
        )

        assertTrue(result is ValidationResult.Invalid)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun `notification reply rejects a malformed gateway token before confirmation`() = runBlocking {
        val gateway = FakeReplyGateway().apply {
            resolution = KakaoReplyResolution.Available(
                KakaoReplyTarget("opaque-target", "홍길동"),
            )
        }

        val result = KakaoNotificationReplyTool(gateway).validateAndCanonicalize(
            KakaoNotificationReplyParams("홍길동", "곧 도착해"),
        )

        assertTrue(result is ValidationResult.Invalid)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun `notification reply rejects an unsafe gateway label before preview`() = runBlocking {
        val gateway = FakeReplyGateway().apply {
            resolution = KakaoReplyResolution.Available(
                KakaoReplyTarget(VALID_TARGET_TOKEN, "홍길동\u202E"),
            )
        }

        val result = KakaoNotificationReplyTool(gateway).validateAndCanonicalize(
            KakaoNotificationReplyParams("홍길동", "곧 도착해"),
        )

        assertTrue(result is ValidationResult.Invalid)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun `both communication paths require confirmation and distinct capabilities`() {
        val share = KakaoShareMessageTool(FakeShareGateway()).descriptor
        val reply = KakaoNotificationReplyTool(FakeReplyGateway()).descriptor
        val policy = ConfirmationPolicy()

        assertEquals(
            ConfirmationRequirement.UserConfirmation,
            policy.evaluate(share.risk, share.minimumConfirmation),
        )
        assertEquals(setOf(ToolCapability.OPEN_KAKAO_SHARE), share.requiredCapabilities)
        assertEquals(setOf(ToolCapability.REPLY_KAKAO_NOTIFICATION), reply.requiredCapabilities)
    }

    private companion object {
        val VALID_TARGET_TOKEN = "ab".repeat(32)
    }
}
