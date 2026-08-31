package com.personaledge.core.agent

import com.personaledge.core.llm.MAX_USER_PROMPT_BYTES
import com.personaledge.core.llm.TurnMediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnMediaPolicyTest {
    @Test
    fun `every media plan exposes no tool at all`() {
        for (intent in TurnMediaIntent.entries) {
            val plan = requirePlan(
                TurnMediaPolicy.planOrNull(intent.kind, ownerTextFor(intent), intent),
            )
            assertTrue(
                "$intent must not expose a Tool.",
                plan.toolScope.toolNames.isEmpty(),
            )
        }
    }

    @Test
    fun `an attachment alone falls back to its modality default`() {
        assertEquals(
            TurnMediaIntent.IMAGE_DESCRIBE,
            TurnMediaPolicy.resolveIntent(TurnMediaKind.IMAGE, ownerText = ""),
        )
        assertEquals(
            TurnMediaIntent.AUDIO_QUESTION,
            TurnMediaPolicy.resolveIntent(TurnMediaKind.AUDIO, ownerText = ""),
        )
    }

    @Test
    fun `typed wording selects the image task`() {
        fun intent(text: String) = TurnMediaPolicy.resolveIntent(TurnMediaKind.IMAGE, text)

        assertEquals(TurnMediaIntent.IMAGE_DESCRIBE, intent("이 사진 뭐야?"))
        assertEquals(TurnMediaIntent.IMAGE_DESCRIBE, intent("사진 설명해 줘"))
        assertEquals(TurnMediaIntent.IMAGE_TEXT_EXTRACT, intent("여기 적힌 글자 그대로 옮겨줘"))
        assertEquals(TurnMediaIntent.IMAGE_TEXT_EXTRACT, intent("뭐라고 쓰여 있어?"))
        assertEquals(TurnMediaIntent.IMAGE_DOCUMENT, intent("이 영수증 내용 정리해 줘"))
        assertEquals(TurnMediaIntent.IMAGE_DOCUMENT, intent("문서 내용 확인해 줘"))
        assertEquals(TurnMediaIntent.IMAGE_TRANSLATE, intent("이거 번역해 줘"))
        assertEquals(TurnMediaIntent.IMAGE_TRANSLATE, intent("무슨 뜻이야?"))
        // Anything else with a typed line is a question about the image, not a description.
        assertEquals(TurnMediaIntent.IMAGE_QUESTION, intent("이 커피 카페인 얼마나 들었을까?"))
    }

    @Test
    fun `typed wording selects the audio task`() {
        fun intent(text: String) = TurnMediaPolicy.resolveIntent(TurnMediaKind.AUDIO, text)

        assertEquals(TurnMediaIntent.AUDIO_TRANSCRIBE, intent("받아쓰기 해 줘"))
        assertEquals(TurnMediaIntent.AUDIO_SUMMARIZE, intent("무슨 얘기였는지 요약해 줘"))
        assertEquals(TurnMediaIntent.AUDIO_QUESTION, intent("여기서 말한 장소가 어디야?"))
    }

    @Test
    fun `typed wording cannot cross modality`() {
        // "번역" is an image task word; on an audio clip it must stay an audio intent.
        val resolved = TurnMediaPolicy.resolveIntent(TurnMediaKind.AUDIO, "번역해 줘")

        assertEquals(TurnMediaKind.AUDIO, resolved.kind)
    }

    @Test
    fun `a requested intent from another modality is discarded rather than honoured`() {
        val resolved = TurnMediaPolicy.resolveIntent(
            kind = TurnMediaKind.IMAGE,
            ownerText = "",
            requestedIntent = TurnMediaIntent.AUDIO_TRANSCRIBE,
        )

        assertEquals(TurnMediaIntent.IMAGE_DESCRIBE, resolved)
    }

    @Test
    fun `dictation stays dictation whatever the owner typed`() {
        // The caller routes a transcription into the composer instead of the transcript, so text
        // must not silently turn it back into an ordinary answer.
        val resolved = TurnMediaPolicy.resolveIntent(
            kind = TurnMediaKind.AUDIO,
            ownerText = "요약해 줘",
            requestedIntent = TurnMediaIntent.AUDIO_TRANSCRIBE,
        )

        assertEquals(TurnMediaIntent.AUDIO_TRANSCRIBE, resolved)
        assertTrue(resolved.deliversEditableDraft)
        assertFalse(TurnMediaIntent.AUDIO_QUESTION.deliversEditableDraft)
        assertFalse(TurnMediaIntent.IMAGE_DESCRIBE.deliversEditableDraft)
    }

    @Test
    fun `the owner's words reach the model inside a delimited block`() {
        val plan = requirePlan(
            TurnMediaPolicy.planOrNull(TurnMediaKind.IMAGE, "이 표에서 합계가 얼마야?"),
        )

        assertTrue("[사용자 입력]" in plan.prompt)
        assertTrue("이 표에서 합계가 얼마야?" in plan.prompt)
    }

    @Test
    fun `an empty typed line leaves out the owner block entirely`() {
        val plan = requirePlan(TurnMediaPolicy.planOrNull(TurnMediaKind.IMAGE, "   "))

        assertFalse("[사용자 입력]" in plan.prompt)
        assertEquals(TurnMediaIntent.IMAGE_DESCRIBE, plan.intent)
    }

    @Test
    fun `every prompt states that the attachment is data and that no tool exists`() {
        for (intent in TurnMediaIntent.entries) {
            val plan = requirePlan(
                TurnMediaPolicy.planOrNull(intent.kind, ownerTextFor(intent), intent),
            )

            assertTrue("$intent lost the observation framing.", "관찰 대상" in plan.prompt)
            assertTrue("$intent lost the tool-free constraint.", "도구가 없습니다" in plan.prompt)
        }
    }

    @Test
    fun `an over-long typed line is refused rather than truncated`() {
        val tooLong = "가".repeat(TurnMediaPolicy.MAX_OWNER_TEXT_BYTES / 3 + 1)

        assertFalse(TurnMediaPolicy.isAcceptedOwnerText(tooLong))
        assertNull(TurnMediaPolicy.planOrNull(TurnMediaKind.IMAGE, tooLong))
    }

    @Test
    fun `the longest accepted media turn still fits the runtime prompt envelope`() {
        // Korean code points cost three UTF-8 bytes, so this is the worst case the composer allows.
        val longest = "가".repeat(TurnMediaPolicy.MAX_OWNER_TEXT_BYTES / 3)
        assertTrue(TurnMediaPolicy.isAcceptedOwnerText(longest))

        for (intent in TurnMediaIntent.entries) {
            val plan = requirePlan(TurnMediaPolicy.planOrNull(intent.kind, longest, intent))
            val bytes = plan.prompt.toByteArray(Charsets.UTF_8).size

            assertTrue(
                "$intent produced a $bytes-byte prompt, over the runtime envelope.",
                bytes <= MAX_USER_PROMPT_BYTES,
            )
        }
    }

    @Test
    fun `decode ceilings match the depth each task needs`() {
        fun ceiling(intent: TurnMediaIntent) =
            requirePlan(TurnMediaPolicy.planOrNull(intent.kind, "", intent)).maxOutputTokens

        assertEquals(TurnMediaPolicy.DESCRIBE_OUTPUT_TOKENS, ceiling(TurnMediaIntent.IMAGE_DESCRIBE))
        assertEquals(TurnMediaPolicy.DESCRIBE_OUTPUT_TOKENS, ceiling(TurnMediaIntent.IMAGE_QUESTION))
        assertEquals(TurnMediaPolicy.DESCRIBE_OUTPUT_TOKENS, ceiling(TurnMediaIntent.AUDIO_QUESTION))
        assertEquals(TurnMediaPolicy.DETAILED_OUTPUT_TOKENS, ceiling(TurnMediaIntent.IMAGE_DOCUMENT))
        assertEquals(
            TurnMediaPolicy.DETAILED_OUTPUT_TOKENS,
            ceiling(TurnMediaIntent.IMAGE_TEXT_EXTRACT),
        )
        assertEquals(
            TurnMediaPolicy.DETAILED_OUTPUT_TOKENS,
            ceiling(TurnMediaIntent.IMAGE_TRANSLATE),
        )
        assertEquals(
            TurnMediaPolicy.DETAILED_OUTPUT_TOKENS,
            ceiling(TurnMediaIntent.AUDIO_SUMMARIZE),
        )
        assertEquals(
            TurnMediaPolicy.TRANSCRIBE_OUTPUT_TOKENS,
            ceiling(TurnMediaIntent.AUDIO_TRANSCRIBE),
        )
    }

    @Test
    fun `a plan never renders the request text`() {
        val rendered = requirePlan(
            TurnMediaPolicy.planOrNull(TurnMediaKind.IMAGE, "여권 번호가 보이니?"),
        ).toString()

        assertTrue("prompt=<redacted>" in rendered)
        assertFalse("여권" in rendered)
    }

    private fun ownerTextFor(intent: TurnMediaIntent): String = when (intent) {
        TurnMediaIntent.IMAGE_DESCRIBE -> "설명해 줘"
        TurnMediaIntent.IMAGE_DOCUMENT -> "문서 정리해 줘"
        TurnMediaIntent.IMAGE_TEXT_EXTRACT -> "그대로 옮겨 줘"
        TurnMediaIntent.IMAGE_TRANSLATE -> "번역해 줘"
        TurnMediaIntent.IMAGE_QUESTION -> "이게 안전한가?"
        TurnMediaIntent.AUDIO_TRANSCRIBE -> "받아쓰기 해 줘"
        TurnMediaIntent.AUDIO_SUMMARIZE -> "요약해 줘"
        TurnMediaIntent.AUDIO_QUESTION -> "언제 만나자고 했지?"
    }

    private fun requirePlan(plan: TurnMediaPlan?): TurnMediaPlan {
        assertNotNull("Expected a plan for an accepted owner line.", plan)
        return plan!!
    }
}
