package com.personaledge.agent

import com.personaledge.core.data.CapturedNotificationDraft
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitmentProposalDetectorTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = ZonedDateTime.of(2026, 8, 23, 10, 0, 0, 0, zone)

    @Test
    fun `tomorrow afternoon commitment resolves without a model`() {
        val proposal = CommitmentProposalDetector.detect(
            draft("내일 오후 7시에 강남에서 보자"),
            zone,
            now,
        )

        assertNotNull(proposal)
        val due = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(requireNotNull(proposal?.proposedDueAtEpochMillis)),
            zone,
        )
        assertEquals(24, due.dayOfMonth)
        assertEquals(19, due.hour)
        assertEquals(90, proposal.confidence)
    }

    @Test
    fun `ambiguous seven oclock remains an undated proposal`() {
        val proposal = CommitmentProposalDetector.detect(draft("금요일 7시에 만나자"), zone, now)

        assertNotNull(proposal)
        assertNull(proposal?.proposedDueAtEpochMillis)
        assertEquals(65, proposal?.confidence)
    }

    @Test
    fun `ordinary message and time without commitment are ignored`() {
        assertNull(CommitmentProposalDetector.detect(draft("오늘 날씨 좋다"), zone, now))
        assertNull(CommitmentProposalDetector.detect(draft("오후 7시 뉴스"), zone, now))
    }

    @Test
    fun `source key is stored only as a hash`() {
        val proposal = CommitmentProposalDetector.detect(draft("내일 19:00에 회의하자"), zone, now)

        assertEquals(64, proposal?.sourceRefHash?.length)
        assertTrue(proposal?.sourceRefHash != "private-source-key")
    }

    @Test
    fun `direct inbox accepts text without classifier keywords and keeps it undated`() {
        val proposal = CommitmentProposalDetector.draftForDirectInbox(
            "집에 가면 세탁기 돌리기",
            "b".repeat(64),
            zone,
            now,
        )

        assertNotNull(proposal)
        assertNull(proposal?.proposedDueAtEpochMillis)
        assertNull(proposal?.sourcePackage)
    }

    @Test
    fun `direct inbox suggests an explicit future date and time without scheduling`() {
        val proposal = CommitmentProposalDetector.draftForDirectInbox(
            "내일 오전 9시에 자료 보내기",
            "c".repeat(64),
            zone,
            now,
        )

        val due = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(requireNotNull(proposal?.proposedDueAtEpochMillis)),
            zone,
        )
        assertEquals(24, due.dayOfMonth)
        assertEquals(9, due.hour)
    }

    private fun draft(text: String) = CapturedNotificationDraft(
        sourceKey = "private-source-key",
        packageName = NotificationCapture.KAKAO_TALK_PACKAGE,
        conversationTitle = "프로젝트방",
        sender = "철수",
        text = text,
        postedAtEpochMillis = now.toInstant().toEpochMilli(),
    )
}
