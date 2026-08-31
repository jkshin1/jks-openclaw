package com.personaledge.core.agent

import com.personaledge.core.llm.InferenceBackend
import com.personaledge.core.llm.LlmRuntime
import com.personaledge.core.llm.LlmState
import com.personaledge.core.llm.LlmToolDefinition
import com.personaledge.core.llm.LlmTurnToolScope
import com.personaledge.core.llm.ModelEvent
import com.personaledge.core.llm.TrustedToolResponse
import com.personaledge.core.llm.TurnId
import com.personaledge.core.llm.TurnMediaAttachment
import com.personaledge.core.llm.TurnMediaKind
import com.personaledge.core.llm.VerifiedInstalledModel
import com.personaledge.core.tools.CapabilityFreeInterlock
import com.personaledge.core.tools.FakeArrivalNoticeTool
import com.personaledge.core.tools.InProcessActionLedger
import com.personaledge.core.tools.ToolOrchestrator
import com.personaledge.core.tools.UserConfirmationGate
import java.util.ArrayDeque
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The controller-side half of the media boundary.
 *
 * [TurnMediaPolicyTest] proves the app-authored request is tool-free; these prove the controller
 * cannot be talked out of that, and that an attachment reaches the runtime exactly once.
 */
class MediaTurnControllerTest {
    @Test
    fun `a media turn reaches the runtime with the attachment and no tool`() = runBlocking {
        val turnId = TurnId("turn-media-scope")
        val fixture = fixture()
        fixture.runtime.enqueueUser(
            flowOf(
                ModelEvent.TextDelta(turnId, "사진에는 흰 종이에 적힌 일정 메모가 보입니다."),
                ModelEvent.Completed(turnId),
            ),
        )
        val image = requireImage()

        val events = fixture.controller.runTurn(
            turnId = turnId,
            prompt = "사진 설명 요청",
            media = listOf(image),
        ).toList()

        val invocation = fixture.runtime.mediaInvocations.single()
        assertEquals(turnId, invocation.turnId)
        assertEquals(listOf(TurnMediaKind.IMAGE), invocation.media.map(TurnMediaAttachment::kind))
        assertTrue(
            "A media turn must be given an empty Tool scope.",
            invocation.toolScope.toolNames.isEmpty(),
        )
        assertEquals(AgentEvent.Completed(turnId), events.last())
    }

    @Test
    fun `a write-shaped caption still exposes no tool`() = runBlocking {
        // The caption reads like an operational request that would normally earn a write scope.
        val turnId = TurnId("turn-media-write-words")
        val fixture = fixture()
        fixture.runtime.enqueueUser(
            flowOf(
                ModelEvent.TextDelta(turnId, "메모에는 내일 9시 회의라고 적혀 있습니다."),
                ModelEvent.Completed(turnId),
            ),
        )

        fixture.controller.runTurn(
            turnId = turnId,
            prompt = "여기 적힌 대로 내일 아침 9시 알람 맞춰 줘",
            currentUserRequest = "여기 적힌 대로 내일 아침 9시 알람 맞춰 줘",
            media = listOf(requireImage()),
        ).toList()

        assertTrue(fixture.runtime.mediaInvocations.single().toolScope.toolNames.isEmpty())
        // The registry is not empty, so an empty scope is a decision rather than a coincidence.
        assertTrue(fixture.controller.toolDefinitions.isNotEmpty())
        assertTrue(fixture.runtime.textInvocations.isEmpty())
    }

    @Test
    fun `a media turn never runs a deterministic read`() = runBlocking {
        // "오늘 날씨" normally short-circuits into a model-free weather read before any decode.
        val turnId = TurnId("turn-media-weather-words")
        val fixture = fixture()
        fixture.runtime.enqueueUser(
            flowOf(
                ModelEvent.TextDelta(turnId, "사진 속 창밖은 흐립니다."),
                ModelEvent.Completed(turnId),
            ),
        )

        val events = fixture.controller.runTurn(
            turnId = turnId,
            prompt = "오늘 날씨 알려줘",
            currentUserRequest = "오늘 날씨 알려줘",
            media = listOf(requireImage()),
        ).toList()

        assertEquals(1, fixture.runtime.mediaInvocations.size)
        assertTrue(events.none { event -> event is AgentEvent.ToolExecuted })
        assertTrue(events.none { event -> event is AgentEvent.TrustedAnswer })
    }

    @Test
    fun `a media turn does not arm the follow-up carry-over`() = runBlocking {
        val fixture = fixture()
        fixture.runtime.enqueueUser(
            flowOf(
                ModelEvent.TextDelta(TurnId("turn-media-carry"), "사진 설명입니다."),
                ModelEvent.Completed(TurnId("turn-media-carry")),
            ),
        )

        fixture.controller.runTurn(
            turnId = TurnId("turn-media-carry"),
            prompt = "내일 일정 하나 추가해 줘",
            currentUserRequest = "내일 일정 하나 추가해 줘",
            media = listOf(requireImage()),
        ).toList()

        // A later bare reply must not inherit a scope an attachment turn never earned.
        assertEquals(null, fixture.controller.resumedRequestOrNull("리마인더"))
    }

    @Test
    fun `media cannot be combined with a recovery contract`() = runBlocking {
        val turnId = TurnId("turn-media-contract")
        val fixture = fixture()

        val events = fixture.controller.runTurn(
            turnId = turnId,
            prompt = "사진 설명 요청",
            media = listOf(requireImage()),
            executionContract = TurnExecutionContract.exactReads(listOf("web_search")),
        ).toList()

        assertEquals(
            AgentEvent.Failure(turnId, AgentFailureCode.INVALID_TURN),
            events.single(),
        )
        assertTrue(fixture.runtime.mediaInvocations.isEmpty())
    }

    @Test
    fun `media cannot be combined with a caller-supplied tool scope`() = runBlocking {
        val turnId = TurnId("turn-media-scope-request")
        val fixture = fixture()

        val events = fixture.controller.runTurn(
            turnId = turnId,
            prompt = "사진 설명 요청",
            media = listOf(requireImage()),
            toolScope = LlmTurnToolScope.exact(setOf(FakeArrivalNoticeTool.NAME)),
        ).toList()

        assertEquals(
            AgentEvent.Failure(turnId, AgentFailureCode.INVALID_TURN),
            events.single(),
        )
        assertTrue(fixture.runtime.mediaInvocations.isEmpty())
    }

    @Test
    fun `more than one attachment is refused before any decode`() = runBlocking {
        val turnId = TurnId("turn-media-two")
        val fixture = fixture()

        val events = fixture.controller.runTurn(
            turnId = turnId,
            prompt = "사진 설명 요청",
            media = listOf(requireImage(), requireAudio()),
        ).toList()

        assertEquals(
            AgentEvent.Failure(turnId, AgentFailureCode.INVALID_TURN),
            events.single(),
        )
        assertTrue(fixture.runtime.mediaInvocations.isEmpty())
    }

    @Test
    fun `an empty media list keeps the ordinary text path`() = runBlocking {
        val turnId = TurnId("turn-media-empty")
        val fixture = fixture()
        fixture.runtime.enqueueUser(
            flowOf(
                ModelEvent.TextDelta(turnId, "평범한 답변입니다."),
                ModelEvent.Completed(turnId),
            ),
        )

        fixture.controller.runTurn(
            turnId = turnId,
            prompt = "그냥 인사",
            currentUserRequest = "안녕",
            media = emptyList(),
        ).toList()

        assertTrue(fixture.runtime.mediaInvocations.isEmpty())
        assertEquals(1, fixture.runtime.textInvocations.size)
    }

    private fun requireImage(): TurnMediaAttachment {
        val jpeg = ByteArray(4_096).also { bytes ->
            bytes[0] = 0xFF.toByte()
            bytes[1] = 0xD8.toByte()
            bytes[2] = 0xFF.toByte()
        }
        val attachment = TurnMediaAttachment.imageOrNull(jpeg)
        assertNotNull(attachment)
        return attachment!!
    }

    private fun requireAudio(): TurnMediaAttachment {
        val wav = ByteArray(32_044).also { bytes ->
            "RIFF".toByteArray().copyInto(bytes, 0)
            "WAVE".toByteArray().copyInto(bytes, 8)
        }
        val attachment = TurnMediaAttachment.audioOrNull(wav, durationMillis = 1_000)
        assertNotNull(attachment)
        return attachment!!
    }

    private fun fixture(): Fixture {
        val runtime = RecordingLlmRuntime()
        // A non-empty registry on purpose: an empty one would make "no Tool was exposed" vacuous.
        val registry = ManualToolRegistry()
        return Fixture(
            runtime = runtime,
            controller = ManualToolAgentController(
                runtime = runtime,
                registry = registry,
                orchestrator = ToolOrchestrator(
                    actionLedger = InProcessActionLedger(),
                    userConfirmationGate = UserConfirmationGate { true },
                    executionInterlock = CapabilityFreeInterlock(),
                    clock = { 10_000L },
                    idFactory = { "action-1" },
                ),
                monotonicClockMillis = { 0L },
            ),
        )
    }

    private data class Fixture(
        val runtime: RecordingLlmRuntime,
        val controller: ManualToolAgentController,
    )

    private data class MediaInvocation(
        val turnId: TurnId,
        val toolScope: LlmTurnToolScope,
        val media: List<TurnMediaAttachment>,
    )

    private class RecordingLlmRuntime : LlmRuntime {
        override val state = MutableStateFlow<LlmState>(LlmState.Ready(InferenceBackend.CPU))
        val textInvocations = mutableListOf<TurnId>()
        val mediaInvocations = mutableListOf<MediaInvocation>()
        private val userStreams = ArrayDeque<Flow<ModelEvent>>()

        fun enqueueUser(events: Flow<ModelEvent>) {
            userStreams.addLast(events)
        }

        override suspend fun initialize(
            model: VerifiedInstalledModel,
            backend: InferenceBackend,
            tools: List<LlmToolDefinition>,
            mediaModalities: Set<TurnMediaKind>,
        ) = Unit

        override fun streamUserTurn(turnId: TurnId, prompt: String): Flow<ModelEvent> {
            textInvocations += turnId
            return userStreams.removeFirst()
        }

        override fun streamUserTurn(
            turnId: TurnId,
            prompt: String,
            maxOutputTokens: Int,
            toolScope: LlmTurnToolScope,
            media: List<TurnMediaAttachment>,
        ): Flow<ModelEvent> {
            if (media.isEmpty()) return streamUserTurn(turnId, prompt)
            mediaInvocations += MediaInvocation(turnId, toolScope, media.toList())
            return userStreams.removeFirst()
        }

        override fun streamToolResponses(
            turnId: TurnId,
            responses: List<TrustedToolResponse>,
        ): Flow<ModelEvent> = flowOf(ModelEvent.Completed(turnId))

        override suspend fun cancel(turnId: TurnId) = Unit

        override fun close() = Unit
    }
}
