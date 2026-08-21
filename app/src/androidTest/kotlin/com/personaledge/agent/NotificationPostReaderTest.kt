package com.personaledge.agent

import android.app.Notification
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented because `Bundle` is a framework class. Everything asserted here is about reading a
 * bundle another app built, so the cases that matter are the malformed ones.
 */
@RunWith(AndroidJUnit4::class)
class NotificationPostReaderTest {
    private fun read(
        extras: Bundle?,
        flags: Int = 0,
        packageName: String? = NotificationCapture.KAKAO_TALK_PACKAGE,
        sourceKey: String? = "0|com.kakao.talk|1|null|10123",
        postedAtEpochMillis: Long = 1_700_000_000_000,
    ) = NotificationPostReader.read(
        packageName = packageName,
        sourceKey = sourceKey,
        postedAtEpochMillis = postedAtEpochMillis,
        flags = flags,
        extras = extras,
    )

    private fun messagingExtras(vararg messages: Pair<String?, String?>) = Bundle().apply {
        putCharSequence(Notification.EXTRA_TITLE, "점심 모임")
        putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, "점심 모임")
        putParcelableArray(
            Notification.EXTRA_MESSAGES,
            messages
                .map { (sender, text) ->
                    Bundle().apply {
                        sender?.let { value -> putCharSequence("sender", value) }
                        text?.let { value -> putCharSequence("text", value) }
                    }
                }
                .toTypedArray(),
        )
    }

    @Test
    fun aPlainNotificationReadsTitleAndText() {
        val extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE, "김철수")
            putCharSequence(Notification.EXTRA_TEXT, "저녁에 시간 돼?")
        }

        val post = read(extras)!!

        assertEquals("김철수", post.title)
        assertEquals("저녁에 시간 돼?", post.text)
        assertNull(post.messagingText)
        assertFalse(post.isGroupSummary)
        assertFalse(post.isOngoing)
    }

    @Test
    fun theNewestMessagingEntryIsTheOneRead() {
        val post = read(
            messagingExtras(
                "김철수" to "먼저 온 메시지",
                "이영희" to "방금 온 메시지",
            ),
        )!!

        assertEquals("이영희", post.messagingSender)
        assertEquals("방금 온 메시지", post.messagingText)
        assertEquals("점심 모임", post.conversationTitle)
    }

    @Test
    fun anEntryWithoutTextIsSkippedRatherThanChosen() {
        // Some apps append a trailing marker entry that carries no message body.
        val post = read(
            messagingExtras(
                "이영희" to "실제 메시지",
                null to null,
            ),
        )!!

        assertEquals("실제 메시지", post.messagingText)
        assertEquals("이영희", post.messagingSender)
    }

    @Test
    fun aMessagingEntryWithNoSenderStillYieldsItsText() {
        val post = read(messagingExtras(null to "보낸이 없는 메시지"))!!

        assertEquals("보낸이 없는 메시지", post.messagingText)
        assertNull(post.messagingSender)
    }

    @Test
    fun aWronglyTypedMessagesArrayIsTreatedAsAbsent() {
        val extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE, "김철수")
            putCharSequence(Notification.EXTRA_TEXT, "폴백 텍스트")
            // Not an array of Bundles, which is what MessagingStyle would put here.
            putString(Notification.EXTRA_MESSAGES, "이건 배열이 아닙니다")
        }

        val post = read(extras)!!

        assertNull(post.messagingText)
        assertEquals("폴백 텍스트", post.text)
    }

    @Test
    fun anEmptyBundleReadsAsAllAbsentRatherThanThrowing() {
        val post = read(Bundle())!!

        assertNull(post.title)
        assertNull(post.text)
        assertNull(post.messagingText)
    }

    @Test
    fun missingExtrasYieldNoPost() {
        assertNull(read(extras = null))
    }

    @Test
    fun summaryAndOngoingFlagsAreCarriedThrough() {
        val extras = Bundle().apply { putCharSequence(Notification.EXTRA_TEXT, "새 메시지 3개") }

        assertTrue(read(extras, flags = Notification.FLAG_GROUP_SUMMARY)!!.isGroupSummary)
        assertTrue(read(extras, flags = Notification.FLAG_ONGOING_EVENT)!!.isOngoing)
    }

    @Test
    fun aReadPostStillGoesThroughTheCaptureRules() {
        // The reader is deliberately permissive; the allowlist and content rules live downstream.
        val fromAnotherApp = read(
            extras = Bundle().apply { putCharSequence(Notification.EXTRA_TEXT, "잔액 안내") },
            packageName = "com.example.bank",
        )

        assertNotNull(fromAnotherApp)
        assertNull(NotificationCapture.extract(fromAnotherApp!!))
    }
}
