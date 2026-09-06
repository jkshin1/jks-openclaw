package com.personaledge.core.agent

import com.personaledge.core.llm.LlmRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAgentGatewayTest {
    @Test
    fun `request accepts bounded Korean prompt and opaque OpenClaw session id`() {
        val sessionId = RemoteAgentSessionId.parse("agent:personal-edge:session-01")
        val prompt = RemoteAgentPrompt.create("서울 날씨를 조사하고 결과를 정리해줘")

        assertNotNull(sessionId)
        assertNotNull(prompt)
        val request = RemoteAgentStartRequest(
            idempotencyKey = requireNotNull(RemoteAgentIdempotencyKey.parse("request-01")),
            sessionId = requireNotNull(sessionId),
            prompt = requireNotNull(prompt),
        )
        assertEquals(
            "서울 날씨를 조사하고 결과를 정리해줘",
            request.prompt.text,
        )
        assertTrue(request.prompt.utf8Bytes > request.prompt.text.length)
    }

    @Test
    fun `prompt and identifiers reject protocol smuggling and hard limit overflows`() {
        assertNull(RemoteAgentSessionId.parse("owner session\nsecret"))
        assertNull(RemoteAgentSessionId.parse("a".repeat(129)))
        assertNull(RemoteAgentRunId.parse("run/../../owner-file"))
        assertNull(RemoteAgentIdempotencyKey.parse("retry\nsecond-run"))
        assertNull(RemoteAgentIdempotencyKey.parse("a".repeat(129)))
        assertNull(RemoteAgentPrompt.create("   \n"))
        assertNull(RemoteAgentPrompt.create("owner\u0000secret"))
        assertNull(RemoteAgentPrompt.create("owner\uD800secret"))
        assertNull(
            RemoteAgentPrompt.create(
                "가".repeat(RemoteAgentContractLimits.MAX_PROMPT_UTF8_BYTES / 3 + 1),
            ),
        )
    }

    @Test
    fun `run and wait budgets have absolute ceilings`() {
        assertIllegalArgument {
            RemoteAgentRunLimits(
                maxEvents = RemoteAgentContractLimits.MAX_EVENTS_PER_RUN + 1,
            )
        }
        assertIllegalArgument {
            RemoteAgentRunLimits(
                maxOutputUtf8Bytes = RemoteAgentContractLimits.MAX_OUTPUT_UTF8_BYTES + 1,
            )
        }
        assertIllegalArgument {
            RemoteAgentRunLimits(
                timeoutMillis = RemoteAgentContractLimits.MAX_RUN_TIMEOUT_MILLIS + 1,
            )
        }
        assertIllegalArgument {
            RemoteAgentWaitTimeout(RemoteAgentContractLimits.MAX_WAIT_TIMEOUT_MILLIS + 1)
        }
    }

    @Test
    fun `events enforce per-event sequence and utf8 ceilings`() {
        val runId = requireNotNull(RemoteAgentRunId.parse("run-01"))

        assertNull(RemoteAgentEvent.TextDelta.create(runId, 0, "result"))
        assertNull(RemoteAgentEvent.TextDelta.create(runId, 1, "result\uD800"))
        assertNull(
            RemoteAgentEvent.TextDelta.create(
                runId = runId,
                sequence = 1,
                text = "가".repeat(RemoteAgentContractLimits.MAX_EVENT_TEXT_UTF8_BYTES / 3 + 1),
            ),
        )
        val event = RemoteAgentEvent.TextDelta.create(runId, 1, "bounded result")
        assertNotNull(event)
        assertIllegalArgument {
            RemoteAgentEvent.StatusChanged(
                runId = runId,
                sequence = RemoteAgentContractLimits.MAX_EVENTS_PER_RUN + 1,
                phase = RemoteAgentRunPhase.RUNNING,
            )
        }
    }

    @Test
    fun `content-bearing descriptions redact prompt session run and output text`() {
        val sensitiveSession = "owner-private-session"
        val sensitiveRun = "owner-private-run"
        val sensitivePrompt = "owner-private-prompt"
        val sensitiveOutput = "owner-private-output"
        val sessionId = requireNotNull(RemoteAgentSessionId.parse(sensitiveSession))
        val runId = requireNotNull(RemoteAgentRunId.parse(sensitiveRun))
        val prompt = requireNotNull(RemoteAgentPrompt.create(sensitivePrompt))
        val idempotencyKey = requireNotNull(RemoteAgentIdempotencyKey.parse("request-private"))
        val request = RemoteAgentStartRequest(idempotencyKey, sessionId, prompt)
        val event = requireNotNull(
            RemoteAgentEvent.TextDelta.create(runId, 1, sensitiveOutput),
        )

        val descriptions = listOf(
            sessionId.toString(),
            runId.toString(),
            prompt.toString(),
            idempotencyKey.toString(),
            request.toString(),
            event.toString(),
            RemoteAgentStartResult.Started(runId).toString(),
            RemoteAgentStartResult.OutcomeUnknown(idempotencyKey).toString(),
        )
        descriptions.forEach { description ->
            assertFalse(description.contains(sensitiveSession))
            assertFalse(description.contains(sensitiveRun))
            assertFalse(description.contains(sensitivePrompt))
            assertFalse(description.contains(sensitiveOutput))
        }
    }

    @Test
    fun `gateway exposes task lifecycle without being an LlmRuntime`() = runBlocking {
        val runId = requireNotNull(RemoteAgentRunId.parse("run-01"))
        val gateway: RemoteAgentGateway = FakeRemoteAgentGateway(runId)
        val request = RemoteAgentStartRequest(
            idempotencyKey = requireNotNull(RemoteAgentIdempotencyKey.parse("request-01")),
            sessionId = requireNotNull(RemoteAgentSessionId.parse("session-01")),
            prompt = requireNotNull(RemoteAgentPrompt.create("bounded task")),
        )

        assertFalse(LlmRuntime::class.java.isAssignableFrom(RemoteAgentGateway::class.java))
        assertEquals(RemoteAgentConnectionState.CONNECTED, gateway.connectionState.value)
        assertEquals(RemoteAgentStartResult.Started(runId), gateway.start(request))
        assertTrue(gateway.status(runId) is RemoteAgentStatusResult.Found)
        assertEquals(RemoteAgentCancelResult.Accepted, gateway.cancel(runId))
        val waited = gateway.waitForTerminal(runId, RemoteAgentWaitTimeout(1_000L))
        assertTrue(waited is RemoteAgentWaitResult.Terminal)
    }

    @Test
    fun `terminal provider timeout is distinct from caller wait timeout`() {
        val runId = requireNotNull(RemoteAgentRunId.parse("run-timeout"))
        val terminal = RemoteAgentRunStatus(
            runId = runId,
            phase = RemoteAgentRunPhase.TIMED_OUT,
            observedEvents = 2,
            observedOutputUtf8Bytes = 0,
        )

        assertTrue(terminal.phase.isTerminal)
        assertEquals(terminal, RemoteAgentWaitResult.Terminal(terminal).status)
        val callerTimedOut = RemoteAgentWaitResult.TimedOut(lastKnownStatus = null)
        assertNull(callerTimedOut.lastKnownStatus)
    }

    @Test
    fun `unknown submission outcome retains reconciliation key without inventing run id`() {
        val key = requireNotNull(RemoteAgentIdempotencyKey.parse("request-outcome-unknown"))
        val result = RemoteAgentStartResult.OutcomeUnknown(key)

        assertEquals(key, result.idempotencyKey)
        assertFalse(result.toString().contains(key.value))
        assertFalse(result.javaClass.declaredFields.any { it.name == "runId" })
    }

    @Test
    fun `known run may have unknown status without being reported not found`() {
        val result: RemoteAgentStatusResult = RemoteAgentStatusResult.Unknown(lastKnownStatus = null)

        assertTrue(result is RemoteAgentStatusResult.Unknown)
        assertFalse(result is RemoteAgentStatusResult.NotFound)
    }

    private class FakeRemoteAgentGateway(
        private val runId: RemoteAgentRunId,
    ) : RemoteAgentGateway {
        override val connectionState = MutableStateFlow(RemoteAgentConnectionState.CONNECTED)

        private val completedStatus = RemoteAgentRunStatus(
            runId = runId,
            phase = RemoteAgentRunPhase.SUCCEEDED,
            observedEvents = 1,
            observedOutputUtf8Bytes = 0,
        )

        override suspend fun start(request: RemoteAgentStartRequest): RemoteAgentStartResult =
            RemoteAgentStartResult.Started(runId)

        override fun events(runId: RemoteAgentRunId): Flow<RemoteAgentEvent> = flowOf(
            RemoteAgentEvent.StatusChanged(
                runId = runId,
                sequence = 1,
                phase = RemoteAgentRunPhase.SUCCEEDED,
            ),
        )

        override suspend fun status(runId: RemoteAgentRunId): RemoteAgentStatusResult =
            RemoteAgentStatusResult.Found(completedStatus)

        override suspend fun cancel(runId: RemoteAgentRunId): RemoteAgentCancelResult =
            RemoteAgentCancelResult.Accepted

        override suspend fun waitForTerminal(
            runId: RemoteAgentRunId,
            timeout: RemoteAgentWaitTimeout,
        ): RemoteAgentWaitResult = RemoteAgentWaitResult.Terminal(completedStatus)
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
