package com.personaledge.agent

import com.personaledge.core.agent.RemoteAgentCancelResult
import com.personaledge.core.agent.RemoteAgentConnectionState
import com.personaledge.core.agent.RemoteAgentEvent
import com.personaledge.core.agent.RemoteAgentFailureCode
import com.personaledge.core.agent.RemoteAgentGateway
import com.personaledge.core.agent.RemoteAgentRunId
import com.personaledge.core.agent.RemoteAgentRunPhase
import com.personaledge.core.agent.RemoteAgentRunStatus
import com.personaledge.core.agent.RemoteAgentStartRequest
import com.personaledge.core.agent.RemoteAgentStartResult
import com.personaledge.core.agent.RemoteAgentStatusResult
import com.personaledge.core.agent.RemoteAgentWaitResult
import com.personaledge.core.agent.RemoteAgentWaitTimeout
import com.personaledge.core.data.OpenClawGatewaySettings
import com.personaledge.core.data.ConversationContext
import com.personaledge.core.data.MemoryEntity
import com.personaledge.core.data.MessageRole
import com.personaledge.core.data.StoredMessage
import com.personaledge.core.openclaw.GatewayEndpoint
import com.personaledge.core.openclaw.OpenClawSecretRecordCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpenClawRemoteConversationTest {
    @Test
    fun `default local does not connect even when durable configuration exists`() = runTest {
        val backend = FakeBackend()
        val conversation = OpenClawRemoteConversation(backend, backgroundScope)
        conversation.setForeground(true)
        runCurrent()
        assertFalse(conversation.state.value.selected)
        assertFalse(backend.visible)
        assertEquals(0, backend.gateway.starts.size)
        assertTrue(backend.enableRequests.isEmpty())
    }

    @Test
    fun `configuration binds masked credential to canonical TLS endpoint`() {
        val configuration = requireNotNull(OpenClawRemoteConfiguration.parse(
            "https://MAC.example:443", "owner-gateway-token", "",
        ))
        assertEquals("wss://mac.example/", configuration.policy.endpointUrl)
        assertNotNull(OpenClawSecretRecordCodec.restoreGatewayCredential(
            configuration.endpoint, configuration.credentialRecord,
        ))
        assertNull(OpenClawSecretRecordCodec.restoreGatewayCredential(
            requireNotNull(GatewayEndpoint.parseRelease("wss://other.example/")),
            configuration.credentialRecord,
        ))
        assertFalse(configuration.toString().contains("owner-gateway-token"))
        assertFalse(configuration.toString().contains("mac.example"))
        listOf("http://mac.example", "ws://127.0.0.1", "https://user:pass@mac.example", "https://mac.example/?token=secret")
            .forEach { assertNull(OpenClawRemoteConfiguration.parse(it, "token", "")) }
        assertNull(OpenClawRemoteConfiguration.parse("https://mac.example", "token", "bad-fingerprint"))
    }

    @Test
    fun `endpoint change resets identity while canonical same endpoint preserves pairing`() {
        val endpoint = requireNotNull(GatewayEndpoint.parseRelease("wss://mac.example/"))
        assertFalse(OpenClawRemoteIdentityPolicy.resetForEndpoint("wss://MAC.example:443", endpoint))
        assertFalse(OpenClawRemoteIdentityPolicy.resetForEndpoint("wss://mac.example/", endpoint))
        assertTrue(OpenClawRemoteIdentityPolicy.resetForEndpoint("wss://other.example/", endpoint))
        assertTrue(OpenClawRemoteIdentityPolicy.resetForEndpoint("wss://mac.example/other/", endpoint))
        assertTrue(OpenClawRemoteIdentityPolicy.resetForEndpoint(null, endpoint))
    }

    @Test
    fun `connect needs selected visible screen and durable owner consent completion`() = runTest {
        val backend = FakeBackend()
        val conversation = OpenClawRemoteConversation(backend, backgroundScope)
        runCurrent()
        conversation.connect()
        assertTrue(backend.enableRequests.isEmpty())
        conversation.selectRemote(true)
        conversation.setForeground(true)
        val pending = CompletableDeferred<OwnerConsentMutationOutcome>()
        backend.nextEnablement = pending
        conversation.connect()
        runCurrent()
        assertFalse(backend.visible)
        assertEquals(0, backend.gateway.starts.size)
        pending.complete(OwnerConsentMutationOutcome.APPLIED)
        runCurrent()
        assertTrue(backend.visible)
        assertEquals(OpenClawGatewayUiState.CONNECTED, conversation.state.value.connection)
        assertEquals(0, backend.gateway.starts.size)
    }

    @Test
    fun `background invalidates pending consent without reconnect on return`() = runTest {
        val backend = FakeBackend()
        val conversation = OpenClawRemoteConversation(backend, backgroundScope)
        runCurrent()
        conversation.selectRemote(true)
        conversation.setForeground(true)
        val pending = CompletableDeferred<OwnerConsentMutationOutcome>()
        backend.nextEnablement = pending
        conversation.connect()
        conversation.setForeground(false)
        pending.complete(OwnerConsentMutationOutcome.APPLIED)
        runCurrent()
        conversation.setForeground(true)
        runCurrent()
        assertFalse(backend.visible)
        assertFalse(backend.enabled)
        assertEquals(0, backend.gateway.starts.size)
    }

    @Test
    fun `one send starts exactly the visible question once without history or automatic retry`() = runTest {
        val (conversation, backend) = connected()
        conversation.send()
        runCurrent()
        assertEquals(0, backend.gateway.starts.size)
        conversation.updatePrompt("서울의 봄 날씨를 설명해줘")
        val exact = conversation.state.value.prompt
        conversation.send()
        conversation.send()
        runCurrent()
        assertEquals(1, backend.gateway.starts.size)
        val request = backend.gateway.starts.single()
        assertEquals(exact, request.prompt.text)
        assertEquals(180_000L, request.limits.timeoutMillis)
        assertEquals(32 * 1_024, request.limits.maxOutputUtf8Bytes)
        assertTrue(conversation.state.value.running)
        assertFalse(conversation.state.value.toString().contains(exact))
    }

    @Test
    fun `Korean response is displayed only for the accepted run and completed terminal`() = runTest {
        val (conversation, backend) = connected()
        conversation.updatePrompt("안녕")
        conversation.send()
        runCurrent()
        backend.gateway.eventFlow.emit(requireNotNull(RemoteAgentEvent.TextDelta.create(
            requireNotNull(RemoteAgentRunId.parse("another-run")), 1, "다른 실행",
        )))
        backend.gateway.eventFlow.emit(requireNotNull(RemoteAgentEvent.TextDelta.create(RUN_ID, 1, "안녕하세요.")))
        backend.gateway.eventFlow.emit(RemoteAgentEvent.StatusChanged(RUN_ID, 2, RemoteAgentRunPhase.SUCCEEDED))
        runCurrent()
        assertEquals("안녕하세요.", conversation.state.value.answer)
        assertEquals("답변을 받았습니다.", conversation.state.value.notice)
        assertFalse(conversation.state.value.running)
    }

    @Test
    fun `a remote turn is stored in the shared conversation exactly like a local one`() = runTest {
        val transcript = FakeTranscript()
        val (conversation, backend) = connected(transcript = transcript)
        conversation.updatePrompt("서울 날씨 알려줘")
        conversation.send()
        runCurrent()
        // Stored before the run starts, so an interrupted turn still leaves the question behind.
        assertEquals(listOf("서울 날씨 알려줘"), transcript.questions)
        assertTrue(conversation.state.value.questionStored)
        assertTrue(transcript.answers.isEmpty())
        assertFalse(conversation.state.value.answerStored)
        backend.gateway.eventFlow.emit(requireNotNull(RemoteAgentEvent.TextDelta.create(RUN_ID, 1, "맑습니다.")))
        backend.gateway.eventFlow.emit(RemoteAgentEvent.StatusChanged(RUN_ID, 2, RemoteAgentRunPhase.SUCCEEDED))
        runCurrent()
        assertEquals(listOf("conversation-1" to "맑습니다."), transcript.answers)
        assertTrue(conversation.state.value.answerStored)
        assertEquals("맑습니다.", conversation.state.value.answer)
    }

    @Test
    fun `an interrupted run stores the partial answer it already showed`() = runTest {
        val transcript = FakeTranscript()
        val (conversation, backend) = connected(transcript = transcript)
        conversation.updatePrompt("긴 설명 부탁해")
        conversation.send()
        runCurrent()
        backend.gateway.eventFlow.emit(requireNotNull(RemoteAgentEvent.TextDelta.create(RUN_ID, 1, "부분 답변")))
        runCurrent()
        backend.connection.value = OpenClawGatewayUiState.DEGRADED
        runCurrent()
        assertEquals(listOf("conversation-1" to "부분 답변"), transcript.answers)
        assertEquals(1, backend.gateway.starts.size)
    }

    @Test
    fun `unavailable storage reports the failure and keeps the turn on screen`() = runTest {
        val transcript = FakeTranscript()
        transcript.conversationId = null
        val (conversation, backend) = connected(transcript = transcript)
        conversation.updatePrompt("저장이 막힌 질문")
        conversation.send()
        runCurrent()
        assertEquals(1, backend.gateway.starts.size) // The send is not cancelled by a failed write.
        assertFalse(conversation.state.value.questionStored)
        assertTrue(conversation.state.value.notice.contains("저장하지 못했습니다"))
        backend.gateway.eventFlow.emit(requireNotNull(RemoteAgentEvent.TextDelta.create(RUN_ID, 1, "답변")))
        backend.gateway.eventFlow.emit(RemoteAgentEvent.StatusChanged(RUN_ID, 2, RemoteAgentRunPhase.SUCCEEDED))
        runCurrent()
        // No conversation was ever claimed, so nothing may be written against a guessed one.
        assertTrue(transcript.answers.isEmpty())
        assertFalse(conversation.state.value.answerStored)
        assertEquals("답변", conversation.state.value.answer)
    }

    @Test
    fun `a rejected answer write is reported instead of being claimed as stored`() = runTest {
        val transcript = FakeTranscript()
        transcript.answerStorable = false
        val (conversation, backend) = connected(transcript = transcript)
        conversation.updatePrompt("답변 저장 실패")
        conversation.send()
        runCurrent()
        backend.gateway.eventFlow.emit(requireNotNull(RemoteAgentEvent.TextDelta.create(RUN_ID, 1, "답변 본문")))
        backend.gateway.eventFlow.emit(RemoteAgentEvent.StatusChanged(RUN_ID, 2, RemoteAgentRunPhase.SUCCEEDED))
        runCurrent()
        assertEquals(listOf("conversation-1" to "답변 본문"), transcript.answers)
        assertFalse(conversation.state.value.answerStored)
        assertTrue(conversation.state.value.notice.contains("저장하지 못했습니다"))
    }

    @Test
    fun `accepted cancel is pending until authoritative terminal arrives`() = runTest {
        val (conversation, backend) = connected()
        conversation.updatePrompt("오래 생각해줘")
        conversation.send()
        runCurrent()
        conversation.cancel()
        runCurrent()
        assertEquals(1, backend.gateway.cancelCalls)
        assertTrue(conversation.state.value.running)
        assertTrue(conversation.state.value.notice.contains("종료를 확인"))
        conversation.cancel()
        assertEquals(1, backend.gateway.cancelCalls)
        backend.gateway.eventFlow.emit(RemoteAgentEvent.StatusChanged(RUN_ID, 1, RemoteAgentRunPhase.CANCELLED))
        runCurrent()
        assertFalse(conversation.state.value.running)
        assertEquals("원격 실행 취소가 확인되었습니다.", conversation.state.value.notice)
    }

    @Test
    fun `a cancel tapped before the run id exists is held and sent once it arrives`() = runTest {
        val (conversation, backend) = connected()
        val accepted = CompletableDeferred<RemoteAgentStartResult>()
        backend.gateway.startOverride = { withContext(NonCancellable) { accepted.await() } }
        conversation.updatePrompt("바로 취소")
        conversation.send()
        runCurrent()
        assertTrue(conversation.state.value.running)

        conversation.cancel()
        runCurrent()
        // The connection must stay open and the turn must stay running: closing here would leave
        // the run costing until its own timeout with an unknown outcome.
        assertTrue(conversation.state.value.cancellationRequested)
        assertTrue(conversation.state.value.running)
        assertEquals(OpenClawGatewayUiState.CONNECTED, conversation.state.value.connection)
        assertEquals(0, backend.gateway.cancelCalls)
        assertTrue(conversation.state.value.notice.contains("실행이 시작되는 즉시 중단"))

        accepted.complete(RemoteAgentStartResult.Started(RUN_ID))
        runCurrent()
        assertEquals(1, backend.gateway.cancelCalls)
        backend.gateway.eventFlow.emit(RemoteAgentEvent.StatusChanged(RUN_ID, 1, RemoteAgentRunPhase.CANCELLED))
        runCurrent()
        assertFalse(conversation.state.value.running)
        assertEquals("원격 실행 취소가 확인되었습니다.", conversation.state.value.notice)
    }

    @Test
    fun `a held cancel does not leak into the next turn`() = runTest {
        val (conversation, backend) = connected()
        val accepted = CompletableDeferred<RemoteAgentStartResult>()
        backend.gateway.startOverride = { withContext(NonCancellable) { accepted.await() } }
        conversation.updatePrompt("첫 질문")
        conversation.send()
        runCurrent()
        conversation.cancel()
        runCurrent()
        accepted.complete(RemoteAgentStartResult.Refused(RemoteAgentFailureCode.REMOTE_UNAVAILABLE))
        runCurrent()
        assertFalse(conversation.state.value.running)
        assertFalse(conversation.state.value.cancellationRequested)

        backend.gateway.startOverride = null
        conversation.updatePrompt("다음 질문")
        conversation.send()
        runCurrent()
        assertTrue(conversation.state.value.running)
        assertFalse(conversation.state.value.cancellationRequested)
        assertEquals(0, backend.gateway.cancelCalls)
    }

    @Test
    fun `network loss seals the run and ignores late answer without replay`() = runTest {
        val (conversation, backend) = connected()
        conversation.updatePrompt("연결 확인")
        conversation.send()
        runCurrent()
        backend.connection.value = OpenClawGatewayUiState.DEGRADED
        runCurrent()
        assertFalse(conversation.state.value.running)
        assertTrue(conversation.state.value.notice.contains("과금 여부"))
        backend.gateway.eventFlow.emit(requireNotNull(RemoteAgentEvent.TextDelta.create(RUN_ID, 1, "늦은 답변")))
        advanceTimeBy(220_000)
        runCurrent()
        assertEquals("", conversation.state.value.answer)
        assertEquals(1, backend.gateway.starts.size)
        assertFalse(backend.visible)
    }

    @Test
    fun `background during noncooperative start cannot publish a stale run`() = runTest {
        val (conversation, backend) = connected()
        val accepted = CompletableDeferred<RemoteAgentStartResult>()
        backend.gateway.startOverride = { withContext(NonCancellable) { accepted.await() } }
        conversation.updatePrompt("진행 중")
        conversation.send()
        runCurrent()
        conversation.setForeground(false)
        accepted.complete(RemoteAgentStartResult.Started(RUN_ID))
        runCurrent()
        assertFalse(conversation.state.value.running)
        assertFalse(backend.visible)
        assertEquals(0, backend.gateway.waitCalls)
        assertTrue(conversation.state.value.notice.contains("취소 완료는 확인되지"))
    }

    @Test
    fun `unknown start is not retried or described as failure before dispatch`() = runTest {
        val (conversation, backend) = connected()
        backend.gateway.startOverride = { RemoteAgentStartResult.OutcomeUnknown(it.idempotencyKey) }
        conversation.updatePrompt("한 번만 전송")
        conversation.send()
        runCurrent()
        advanceTimeBy(220_000)
        runCurrent()
        assertEquals(1, backend.gateway.starts.size)
        assertFalse(conversation.state.value.running)
        assertTrue(conversation.state.value.notice.contains("자동으로 재전송하지"))
    }

    @Test
    fun `setting replacement waits for durable revocation and never implicitly enables`() = runTest {
        val (conversation, backend) = connected()
        backend.saved.clear()
        val revoke = CompletableDeferred<OwnerConsentMutationOutcome>()
        backend.nextRevocation = revoke
        conversation.configure("https://new.example/", "new-token", "")
        runCurrent()
        assertFalse(backend.visible)
        assertTrue(backend.saved.isEmpty())
        revoke.complete(OwnerConsentMutationOutcome.APPLIED)
        runCurrent()
        assertEquals(1, backend.saved.size)
        assertFalse(backend.enabled)
    }

    @Test
    fun `recreated controller retains no remote conversation or selected mode`() = runTest {
        val (conversation, backend) = connected()
        conversation.updatePrompt("비공개 임시 초안")
        conversation.close()
        val recreated = OpenClawRemoteConversation(backend, backgroundScope)
        recreated.setForeground(true)
        runCurrent()
        assertFalse(recreated.state.value.selected)
        assertEquals("", recreated.state.value.prompt)
        assertEquals("", recreated.state.value.sentPrompt)
        assertEquals("", recreated.state.value.answer)
        assertFalse(backend.visible)
    }

    @Test
    fun `disconnect failure reports durable consent uncertainty while connection stays closed`() = runTest {
        val (conversation, backend) = connected()
        backend.nextRevocation = CompletableDeferred(OwnerConsentMutationOutcome.FAILED)
        conversation.disconnect()
        assertFalse(conversation.state.value.canSend)
        assertEquals(OpenClawGatewayUiState.DISCONNECTED, conversation.state.value.connection)
        runCurrent()
        assertFalse(backend.visible)
        assertTrue(conversation.state.value.notice.contains("동의 해제를 저장하지 못해"))
    }

    @Test
    fun `background superseding setting revocation prevents stale credential save`() = runTest {
        val (conversation, backend) = connected()
        val pending = CompletableDeferred<OwnerConsentMutationOutcome>()
        backend.nextRevocation = pending
        conversation.configure("https://new.example", "new-token", "")
        conversation.setForeground(false)
        pending.complete(OwnerConsentMutationOutcome.APPLIED)
        runCurrent()
        assertTrue(backend.saved.isEmpty())
        assertFalse(backend.visible)
        assertFalse(backend.enabled)
    }

    @Test
    fun `observation timeout keeps partial text but never claims confirmed remote cancellation`() = runTest {
        val (conversation, backend) = connected()
        conversation.updatePrompt("시간 제한")
        conversation.send()
        runCurrent()
        backend.gateway.eventFlow.emit(requireNotNull(RemoteAgentEvent.TextDelta.create(RUN_ID, 1, "부분 답변")))
        runCurrent()
        advanceTimeBy(200_001)
        runCurrent()
        assertEquals("부분 답변", conversation.state.value.answer)
        assertFalse(conversation.state.value.running)
        assertFalse(backend.visible)
        assertTrue(conversation.state.value.notice.contains("과금 여부는 확인되지"))
        assertEquals(1, backend.gateway.starts.size)
    }

    @Test
    fun `Korean prompt byte bound refuses oversized text and empty terminal stays unaccepted`() = runTest {
        val (conversation, backend) = connected()
        val oversized = "가".repeat(3_000)
        conversation.updatePrompt(oversized)
        conversation.send()
        runCurrent()
        assertEquals(0, backend.gateway.starts.size)
        conversation.updatePrompt("짧은 질문")
        conversation.send()
        runCurrent()
        backend.gateway.eventFlow.emit(RemoteAgentEvent.StatusChanged(RUN_ID, 1, RemoteAgentRunPhase.SUCCEEDED))
        runCurrent()
        assertEquals("", conversation.state.value.answer)
        assertTrue(conversation.state.value.notice.contains("표시할 답변을 받지 못했습니다"))
    }

    @Test
    fun `context requires explicit loading and no item is selected automatically`() = runTest {
        val source = FakeContextSource()
        val (conversation, backend) = connected(source)
        conversation.updatePrompt("나의 서울 계획은?")
        assertEquals(0, source.reads)
        conversation.loadContextOptions()
        runCurrent()
        assertEquals(1, source.reads)
        assertTrue(conversation.state.value.selectedContextKeys.isEmpty())
        conversation.finishContextSelection()
        conversation.send()
        runCurrent()
        assertEquals(0, source.validations)
        assertEquals("나의 서울 계획은?", backend.gateway.starts.single().prompt.text)
    }

    @Test
    fun `explicit selected context sends exactly the ticked quotes once`() = runTest {
        val source = FakeContextSource()
        val transcript = FakeTranscript()
        val (conversation, backend) = connected(source, transcript = transcript)
        selectContext(conversation)
        conversation.send()
        conversation.send()
        runCurrent()
        assertEquals(1, source.validations)
        val sent = backend.gateway.starts.single().prompt.text
        assertTrue(sent.contains("서울로 이사할 계획"))
        assertTrue(sent.contains("지하철을 선호합니다"))
        assertFalse(sent.contains("선택하지 않은 비밀"))
        assertFalse(sent.contains("attachment"))
        assertTrue(conversation.state.value.selectedContextKeys.isEmpty())
        assertTrue(conversation.state.value.contextItems.isEmpty())
        // The quotes leave the device, but the transcript keeps only the owner's own question:
        // every quote is already a row of its own and would otherwise be duplicated into history.
        assertEquals("나의 서울 계획은?", conversation.state.value.sentPrompt)
        assertEquals(listOf("나의 서울 계획은?"), transcript.questions)
    }

    @Test
    fun `changed or deleted source fails closed even before an observer notification arrives`() = runTest {
        for (change in listOf<(OpenClawRemoteContextSnapshot) -> OpenClawRemoteContextSnapshot>(
            { old -> OpenClawRemoteContextSnapshot(old.query, old.conversation, emptyList(), true) },
            { old -> OpenClawRemoteContextSnapshot(old.query, old.conversation,
                old.memories.map { it.copy(content = "same timestamp but edited") }, true) },
            { old -> OpenClawRemoteContextSnapshot(old.query, old.conversation?.copy(summary = "new summary"), old.memories, true) },
            { old -> OpenClawRemoteContextSnapshot(old.query, null, old.memories, true) },
            { old -> OpenClawRemoteContextSnapshot(old.query, old.conversation, old.memories, false) },
        )) {
            val source = FakeContextSource()
            val (conversation, backend) = connected(source)
            selectContext(conversation)
            source.snapshot = change(source.snapshot)
            conversation.send()
            runCurrent()
            assertTrue(backend.gateway.starts.isEmpty())
            assertTrue(conversation.state.value.selectedContextKeys.isEmpty())
            assertTrue(conversation.state.value.notice.contains("다시 확인"))
            conversation.close()
        }
    }

    @Test
    fun `prompt endpoint and background changes stop a send that is already validating`() = runTest {
        for (kind in 0..2) {
            val source = FakeContextSource()
            val (conversation, backend) = connected(source)
            selectContext(conversation)
            val validation = CompletableDeferred<Boolean>()
            source.validationOverride = { withContext(NonCancellable) { validation.await() } }
            conversation.send()
            runCurrent()
            when (kind) {
                0 -> conversation.updatePrompt("수정한 질문")
                1 -> backend.settings.value = OpenClawGatewaySettings(endpointUrl = "wss://other.example/")
                2 -> conversation.setForeground(false)
            }
            validation.complete(true)
            runCurrent()
            assertTrue(backend.gateway.starts.isEmpty())
            assertFalse(conversation.state.value.contextBusy)
            conversation.close()
        }
    }

    @Test
    fun `an expired selected memory stops the send until the owner selects again`() = runTest {
        val source = FakeContextSource()
        source.snapshot = OpenClawRemoteContextSnapshot(
            source.snapshot.query, source.snapshot.conversation,
            source.snapshot.memories.map {
                it.copy(validUntilEpochMillis = TEST_WALL_CLOCK + testScheduler.currentTime + 500)
            },
            true,
        )
        val (conversation, backend) = connected(source)
        selectContext(conversation)
        advanceTimeBy(501)
        conversation.send()
        runCurrent()
        assertTrue(backend.gateway.starts.isEmpty())
        assertTrue(conversation.state.value.notice.contains("유효기간이 지났습니다"))
        assertTrue(conversation.state.value.selectedContextKeys.isEmpty())
    }

    @Test
    fun `late freshness validation cannot send after owner invalidation`() = runTest {
        val source = FakeContextSource()
        val (conversation, backend) = connected(source)
        selectContext(conversation)
        val pending = CompletableDeferred<Boolean>()
        source.validationOverride = { withContext(NonCancellable) { pending.await() } }
        conversation.send()
        runCurrent()
        assertTrue(conversation.state.value.contextBusy)
        conversation.invalidateContext()
        pending.complete(true)
        runCurrent()
        assertTrue(backend.gateway.starts.isEmpty())
        assertFalse(conversation.state.value.contextBusy)
    }

    @Test
    fun `owner mutation between final validation and gateway lookup cannot claim a new send generation`() = runTest {
        val source = FakeContextSource()
        val (conversation, backend) = connected(source)
        selectContext(conversation)
        backend.beforeGatewayLookup = source.invalidate
        conversation.send()
        runCurrent()
        assertEquals(1, source.validations)
        assertTrue(backend.gateway.starts.isEmpty())
        assertFalse(conversation.state.value.contextBusy)
        assertTrue(conversation.state.value.contextItems.isEmpty())
    }

    @Test
    fun `late context read cannot restore data after prompt edit`() = runTest {
        val source = FakeContextSource()
        val (conversation, backend) = connected(source)
        val pending = CompletableDeferred<OpenClawRemoteContextSnapshot>()
        source.loadOverride = { withContext(NonCancellable) { pending.await() } }
        conversation.updatePrompt("나의 서울 계획은?")
        conversation.loadContextOptions()
        runCurrent()
        conversation.updatePrompt("다른 질문")
        pending.complete(source.snapshot)
        runCurrent()
        assertTrue(conversation.state.value.contextItems.isEmpty())
        assertFalse(conversation.state.value.contextPickerOpen)
        assertTrue(backend.gateway.starts.isEmpty())
    }

    @Test
    fun `oversized selected context is refused without silent truncation`() = runTest {
        val source = FakeContextSource()
        source.snapshot = OpenClawRemoteContextSnapshot(source.snapshot.query, source.snapshot.conversation,
            source.snapshot.memories.map { it.copy(content = "한".repeat(3_000)) }, true)
        val (conversation, backend) = connected(source)
        conversation.updatePrompt(source.snapshot.query)
        conversation.loadContextOptions()
        runCurrent()
        conversation.selectContextItem("memory:memory-1", true)
        conversation.finishContextSelection()
        conversation.send()
        runCurrent()
        assertTrue(conversation.state.value.notice.contains("8 KiB"))
        assertTrue(backend.gateway.starts.isEmpty())
    }

    @Test
    fun `health read requires an advertised connected capability and never invokes a model`() = runTest {
        val (unsupported, unsupportedBackend) = connected()
        unsupported.readMacHealth()
        runCurrent()
        assertEquals(0, unsupportedBackend.healthReads)
        val (conversation, backend) = connected(supportsHealth = true)
        assertTrue(conversation.state.value.healthSupported)
        assertEquals(0, backend.healthReads)
        backend.healthResult = OpenClawRemoteHealthResult.Observation(TEST_WALL_CLOCK, true, true, true, true)
        conversation.readMacHealth()
        conversation.readMacHealth()
        runCurrent()
        assertEquals(1, backend.healthReads)
        assertTrue(backend.gateway.starts.isEmpty())
        assertTrue(conversation.state.value.healthNotice.contains("Docker 정상"))
        assertTrue(conversation.state.value.healthNotice.contains("실시간 점검이 아닙니다"))
        advanceTimeBy(600_001)
        runCurrent()
        assertTrue(conversation.state.value.healthNotice.contains("유효시간이 지났습니다"))
        assertEquals(1, backend.healthReads)
    }

    @Test
    fun `health UI preserves individual warning flags and rejects stale or malformed snapshots`() = runTest {
        val (conversation, backend) = connected(supportsHealth = true)
        backend.healthResult = OpenClawRemoteHealthResult.Observation(TEST_WALL_CLOCK, true, false, true, false)
        conversation.readMacHealth()
        runCurrent()
        assertTrue(conversation.state.value.healthNotice.contains("Docker 확인 필요"))
        assertTrue(conversation.state.value.healthNotice.contains("비밀정보 검사 확인 필요"))
        backend.healthResult = OpenClawRemoteHealthResult.Observation(TEST_WALL_CLOCK - 600_001, true, true, true, true)
        conversation.readMacHealth()
        runCurrent()
        assertTrue(conversation.state.value.healthNotice.contains("표시하지 않습니다"))
        assertFalse(conversation.state.value.healthNotice.contains("정상"))
        backend.healthResult = OpenClawRemoteHealthResult.Invalid
        conversation.readMacHealth()
        runCurrent()
        assertTrue(conversation.state.value.healthNotice.contains("표시하지 않습니다"))
        assertTrue(backend.gateway.starts.isEmpty())
    }

    @Test
    fun `late health response cannot survive background or restore stale capability`() = runTest {
        val (conversation, backend) = connected(supportsHealth = true)
        val pending = CompletableDeferred<OpenClawRemoteHealthResult>()
        backend.healthOverride = { withContext(NonCancellable) { pending.await() } }
        conversation.readMacHealth()
        runCurrent()
        conversation.setForeground(false)
        pending.complete(OpenClawRemoteHealthResult.Observation(TEST_WALL_CLOCK, true, true, true, true))
        runCurrent()
        conversation.setForeground(true)
        runCurrent()
        assertEquals("", conversation.state.value.healthNotice)
        assertFalse(conversation.state.value.healthSupported)
        assertFalse(conversation.state.value.healthBusy)
        assertTrue(backend.gateway.starts.isEmpty())
    }

    @Test
    fun `health failure is fixed prose and unsupported responses disable repeated calls`() = runTest {
        val (conversation, backend) = connected(supportsHealth = true)
        backend.healthOverride = { error("private host path or secret in error") }
        conversation.readMacHealth()
        runCurrent()
        assertTrue(conversation.state.value.healthNotice.contains("읽지 못했습니다"))
        assertFalse(conversation.state.value.healthNotice.contains("private"))
        backend.healthOverride = null
        backend.healthResult = OpenClawRemoteHealthResult.Unsupported
        conversation.readMacHealth()
        runCurrent()
        assertFalse(conversation.state.value.healthSupported)
        conversation.readMacHealth()
        runCurrent()
        assertEquals(2, backend.healthReads)
        assertTrue(backend.gateway.starts.isEmpty())
    }

    private fun TestScope.selectContext(conversation: OpenClawRemoteConversation) {
        conversation.updatePrompt("나의 서울 계획은?")
        conversation.loadContextOptions()
        runCurrent()
        conversation.selectContextItem("message:message-1", true)
        conversation.selectContextItem("memory:memory-1", true)
        conversation.finishContextSelection()
    }

    private class FakeContextSource : OpenClawRemoteContextSource {
        var reads = 0
        var validations = 0
        var invalidate: () -> Unit = {}
        var loadOverride: (suspend () -> OpenClawRemoteContextSnapshot)? = null
        var validationOverride: (suspend () -> Boolean)? = null
        var snapshot = OpenClawRemoteContextSnapshot(
            query = "나의 서울 계획은?",
            conversation = ConversationContext("local-1", null, listOf(
                StoredMessage("message-1", 1, MessageRole.USER, "서울로 이사할 계획", 1, "attachment should not export"),
                StoredMessage("message-2", 2, MessageRole.ASSISTANT, "선택하지 않은 비밀", 2),
            )),
            memories = listOf(MemoryEntity("memory-1", "지하철을 선호합니다", "normalized",
                TEST_WALL_CLOCK, TEST_WALL_CLOCK)),
            memoryAllowed = true,
        )
        override suspend fun load(query: String): OpenClawRemoteContextSnapshot {
            reads++
            return loadOverride?.invoke() ?: snapshot
        }
        override suspend fun validate(snapshot: OpenClawRemoteContextSnapshot, selected: Set<String>): Boolean {
            validations++
            return validationOverride?.invoke() ?: snapshot.matches(this.snapshot, selected)
        }
        override fun observeInvalidations(invalidate: () -> Unit): AutoCloseable {
            this.invalidate = invalidate
            return AutoCloseable { this.invalidate = {} }
        }
    }

    private fun TestScope.connected(
        contextSource: OpenClawRemoteContextSource? = null,
        supportsHealth: Boolean = false,
        transcript: OpenClawRemoteTranscript? = null,
    ): Pair<OpenClawRemoteConversation, FakeBackend> {
        val backend = FakeBackend()
        backend.supportsHealthSnapshot = supportsHealth
        val conversation = OpenClawRemoteConversation(backend, backgroundScope, contextSource, transcript,
            monotonicMillis = { testScheduler.currentTime }, wallClockMillis = { TEST_WALL_CLOCK + testScheduler.currentTime })
        runCurrent()
        conversation.selectRemote(true)
        conversation.setForeground(true)
        conversation.connect()
        runCurrent()
        assertEquals(OpenClawGatewayUiState.CONNECTED, conversation.state.value.connection)
        return conversation to backend
    }

    private class FakeTranscript : OpenClawRemoteTranscript {
        var conversationId: String? = "conversation-1"
        var answerStorable = true
        val questions = mutableListOf<String>()
        val answers = mutableListOf<Pair<String, String>>()
        override suspend fun recordQuestion(question: String): String? {
            questions += question
            return conversationId
        }
        override suspend fun recordAnswer(conversationId: String, answer: String): Boolean {
            answers += conversationId to answer
            return answerStorable
        }
    }

    private class FakeBackend : OpenClawRemoteBackend {
        val gateway = FakeGateway()
        override val settings = MutableStateFlow(OpenClawGatewaySettings(endpointUrl = "wss://mac.example/"))
        override val connection = MutableStateFlow(OpenClawGatewayUiState.DISCONNECTED)
        var visible = false
        var enabled = false
        val enableRequests = mutableListOf<Boolean>()
        val saved = mutableListOf<OpenClawRemoteConfiguration>()
        var nextEnablement: Deferred<OwnerConsentMutationOutcome>? = null
        var nextRevocation: Deferred<OwnerConsentMutationOutcome>? = null
        var beforeGatewayLookup: (() -> Unit)? = null
        override var supportsHealthSnapshot = false
        var healthReads = 0
        var healthResult: OpenClawRemoteHealthResult = OpenClawRemoteHealthResult.Unavailable
        var healthOverride: (suspend () -> OpenClawRemoteHealthResult)? = null
        override suspend fun readHealthSnapshot(): OpenClawRemoteHealthResult {
            healthReads++
            return healthOverride?.invoke() ?: healthResult
        }
        override fun setForeground(visible: Boolean) {
            this.visible = visible
            connection.value = if (visible && enabled) OpenClawGatewayUiState.CONNECTED else OpenClawGatewayUiState.DISCONNECTED
        }
        override fun activeGateway(): FakeGateway? {
            beforeGatewayLookup?.invoke()
            return gateway.takeIf { visible && enabled }
        }
        override fun setEnabled(enabled: Boolean): Deferred<OwnerConsentMutationOutcome> {
            this.enabled = enabled
            enableRequests += enabled
            return if (enabled) nextEnablement ?: CompletableDeferred(OwnerConsentMutationOutcome.APPLIED)
            else nextRevocation ?: CompletableDeferred(OwnerConsentMutationOutcome.APPLIED)
        }
        override suspend fun save(configuration: OpenClawRemoteConfiguration) {
            saved += configuration
            settings.value = OpenClawGatewaySettings(endpointUrl = configuration.endpoint.url)
        }
        override suspend fun forget() { settings.value = OpenClawGatewaySettings() }
        override fun close() { setForeground(false) }
    }

    private class FakeGateway : RemoteAgentGateway {
        override val connectionState = MutableStateFlow(RemoteAgentConnectionState.CONNECTED)
        val starts = mutableListOf<RemoteAgentStartRequest>()
        val eventFlow = MutableSharedFlow<RemoteAgentEvent>(replay = 32)
        var startOverride: (suspend (RemoteAgentStartRequest) -> RemoteAgentStartResult)? = null
        var cancelCalls = 0
        var waitCalls = 0
        override suspend fun start(request: RemoteAgentStartRequest): RemoteAgentStartResult {
            starts += request
            return startOverride?.invoke(request) ?: RemoteAgentStartResult.Started(RUN_ID)
        }
        override fun events(runId: RemoteAgentRunId) = eventFlow
        override suspend fun status(runId: RemoteAgentRunId) = RemoteAgentStatusResult.Found(
            RemoteAgentRunStatus(runId, RemoteAgentRunPhase.RUNNING, 0, 0),
        )
        override suspend fun cancel(runId: RemoteAgentRunId): RemoteAgentCancelResult {
            cancelCalls++
            return RemoteAgentCancelResult.Accepted
        }
        override suspend fun waitForTerminal(runId: RemoteAgentRunId, timeout: RemoteAgentWaitTimeout): RemoteAgentWaitResult {
            waitCalls++
            return CompletableDeferred<RemoteAgentWaitResult>().await()
        }
    }

    companion object {
        private const val TEST_WALL_CLOCK = 1_800_000_000_000L
        private val RUN_ID = requireNotNull(RemoteAgentRunId.parse("remote-run-1"))
    }
}
