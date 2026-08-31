package com.personaledge.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KakaoReplyCandidatePolicyTest {
    private fun identity(
        notificationKey: String = "0|com.kakao.talk|42|null|10001",
        postedAtEpochMillis: Long = 1_777_000_000_000L,
        labels: List<String> = listOf("가족방", "홍길동"),
        remoteInputResultKeys: List<String> = listOf("reply_text"),
        actionCreatorPackage: String = "com.kakao.talk",
        actionCreatorUid: Int = 10_001,
        actionSemanticAction: Int = 1,
        actionToken: Int = 123_456,
    ): KakaoReplyCandidateIdentity = KakaoReplyCandidateIdentity(
        notificationKey = notificationKey,
        postedAtEpochMillis = postedAtEpochMillis,
        labels = labels,
        remoteInputResultKeys = remoteInputResultKeys,
        actionCreatorPackage = actionCreatorPackage,
        actionCreatorUid = actionCreatorUid,
        actionSemanticAction = actionSemanticAction,
        actionToken = actionToken,
    )

    @Test
    fun `fingerprint is normalized sorted and domain length framed`() {
        val first = requireNotNull(
            KakaoReplyCandidatePolicy.bind(
                identity(labels = listOf(" 가족방 ", "ＡLICE"), remoteInputResultKeys = listOf("z", "a")),
            ),
        )
        val reordered = requireNotNull(
            KakaoReplyCandidatePolicy.bind(
                identity(labels = listOf("alice", "가족방"), remoteInputResultKeys = listOf("a", "z")),
            ),
        )

        assertEquals(first.token, reordered.token)
        assertEquals(setOf("alice", "가족방"), first.normalizedLabels)
        assertEquals("가족방", first.displayLabel)
        assertTrue(KakaoReplyCandidatePolicy.isValidToken(first.token))

        val firstFraming = requireNotNull(
            KakaoReplyCandidatePolicy.bind(
                identity(remoteInputResultKeys = listOf("a", "bc")),
            ),
        )
        val secondFraming = requireNotNull(
            KakaoReplyCandidatePolicy.bind(
                identity(remoteInputResultKeys = listOf("ab", "c")),
            ),
        )
        assertNotEquals(firstFraming.token, secondFraming.token)
    }

    @Test
    fun `every notification update and action identity component is bound`() {
        val baseline = requireNotNull(KakaoReplyCandidatePolicy.bind(identity())).token
        val changed = listOf(
            identity(notificationKey = "different-key"),
            identity(postedAtEpochMillis = 1_777_000_000_001L),
            identity(labels = listOf("다른 방")),
            identity(remoteInputResultKeys = listOf("different_result")),
            identity(actionCreatorUid = 10_002),
            identity(actionSemanticAction = 0),
            identity(actionToken = 123_457),
        )

        changed.forEach { candidate ->
            assertNotEquals(baseline, requireNotNull(KakaoReplyCandidatePolicy.bind(candidate)).token)
        }
        assertNull(
            KakaoReplyCandidatePolicy.bind(identity(actionCreatorPackage = "com.example.proxy")),
        )
    }

    @Test
    fun `unsafe control bidi and model delimiter labels fail closed`() {
        listOf(
            "가족\n방",
            "가족\u202E방",
            "<|tool|>",
        ).forEach { unsafe ->
            assertNull(KakaoReplyCandidatePolicy.normalizedLabel(unsafe))
            assertNull(KakaoReplyCandidatePolicy.bind(identity(labels = listOf(unsafe))))
            assertNull(
                KakaoReplyCandidatePolicy.bind(identity(labels = listOf("가족방", unsafe))),
            )
        }
    }

    @Test
    fun `unsafe or missing candidate identity fails closed`() {
        assertNull(KakaoReplyCandidatePolicy.bind(identity(notificationKey = "")))
        assertNull(KakaoReplyCandidatePolicy.bind(identity(postedAtEpochMillis = 0L)))
        assertNull(KakaoReplyCandidatePolicy.bind(identity(labels = emptyList())))
        assertNull(KakaoReplyCandidatePolicy.bind(identity(remoteInputResultKeys = emptyList())))
        assertNull(
            KakaoReplyCandidatePolicy.bind(
                identity(remoteInputResultKeys = listOf("reply\u202Etext")),
            ),
        )
    }

    @Test
    fun `only canonical lowercase sha256 tokens are accepted`() {
        val valid = "ab".repeat(32)

        assertTrue(KakaoReplyCandidatePolicy.isValidToken(valid))
        assertFalse(KakaoReplyCandidatePolicy.isValidToken(valid.uppercase()))
        assertFalse(KakaoReplyCandidatePolicy.isValidToken("a".repeat(63)))
        assertFalse(KakaoReplyCandidatePolicy.isValidToken("opaque-target"))
    }
}
