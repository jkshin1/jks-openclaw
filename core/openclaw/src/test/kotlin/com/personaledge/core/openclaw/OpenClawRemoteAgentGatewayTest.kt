package com.personaledge.core.openclaw

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.personaledge.core.agent.RemoteAgentCancelResult
import com.personaledge.core.agent.RemoteAgentConnectionState
import com.personaledge.core.agent.RemoteAgentEvent
import com.personaledge.core.agent.RemoteAgentFailureCode
import com.personaledge.core.agent.RemoteAgentIdempotencyKey
import com.personaledge.core.agent.RemoteAgentPrompt
import com.personaledge.core.agent.RemoteAgentRunId
import com.personaledge.core.agent.RemoteAgentRunLimits
import com.personaledge.core.agent.RemoteAgentRunPhase
import com.personaledge.core.agent.RemoteAgentSessionId
import com.personaledge.core.agent.RemoteAgentStartRequest
import com.personaledge.core.agent.RemoteAgentStartResult
import com.personaledge.core.agent.RemoteAgentStatusResult
import com.personaledge.core.agent.RemoteAgentWaitResult
import com.personaledge.core.agent.RemoteAgentWaitTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpenClawRemoteAgentGatewayTest {
    @Test
    fun `start pins exact one-shot raw-model params without privileged overrides`() = runTest {
        val port = FakeRpcPort()
        port.responder = ::acceptAgent
        val gateway = OpenClawRemoteAgentGateway(port, this)
        runCurrent()
        val request = request(
            runId = "request-params-1",
            sessionId = "owner-session-private",
            prompt = "private prompt",
            limits = RemoteAgentRunLimits(timeoutMillis = 61_999L),
        )

        val result = gateway.start(request)

        assertTrue(result is RemoteAgentStartResult.Started)
        val call = port.calls.single()
        assertEquals(OpenClawProtocol.METHOD_AGENT, call.method)
        assertEquals(OpenClawProtocol.REQUEST_TIMEOUT_MILLIS, call.timeoutMillis)
        assertEquals(
            setOf(
                "message",
                "agentId",
                "sessionId",
                "sessionKey",
                "thinking",
                "deliver",
                "timeout",
                "modelRun",
                "promptMode",
                "disableMessageTool",
                "cleanupBundleMcpOnRunEnd",
                "idempotencyKey",
            ),
            call.params.keySet(),
        )
        assertEquals("private prompt", call.params["message"].asString)
        assertEquals("main", call.params["agentId"].asString)
        assertEquals("low", call.params["thinking"].asString)
        assertFalse(call.params["deliver"].asBoolean)
        assertEquals(61L, call.params["timeout"].asLong)
        assertTrue(call.params["modelRun"].asBoolean)
        assertEquals("none", call.params["promptMode"].asString)
        assertTrue(call.params["disableMessageTool"].asBoolean)
        assertTrue(call.params["cleanupBundleMcpOnRunEnd"].asBoolean)
        assertEquals("request-params-1", call.params["idempotencyKey"].asString)
        val providerSessionId = call.params["sessionId"].asString
        val sessionKey = call.params["sessionKey"].asString
        assertTrue(MODEL_RUN_SESSION_ID.matches(providerSessionId))
        assertEquals("agent:main:explicit:$providerSessionId", sessionKey)
        assertFalse(sessionKey.contains("owner-session-private"))
        assertFalse(call.params.has("provider"))
        assertFalse(call.params.has("model"))
        assertFalse(call.params.has("sessionEffects"))
        assertFalse(call.params.has("suppressPromptPersistence"))
        assertFalse(gateway.toString().contains("private prompt"))
        assertFalse(gateway.toString().contains("owner-session-private"))
        gateway.close()
    }

    @Test
    fun `consecutive starts use isolated explicit model-run sessions without caller-derived keys`() =
        runTest {
            val port = FakeRpcPort()
            port.responder = ::acceptAgent
            val gateway = OpenClawRemoteAgentGateway(port, this)
            runCurrent()
            val callerSession = "owner-thread-never-on-wire"
            val callerPrompt = "private repeated prompt"

            assertTrue(
                gateway.start(request("request-isolation-1", callerSession, callerPrompt)) is
                    RemoteAgentStartResult.Started,
            )
            assertTrue(
                gateway.start(request("request-isolation-2", callerSession, callerPrompt)) is
                    RemoteAgentStartResult.Started,
            )

            val providerSessionIds = port.calls.map { it.params["sessionId"].asString }
            val sessionKeys = port.calls.map { it.params["sessionKey"].asString }
            assertEquals(2, providerSessionIds.distinct().size)
            assertEquals(2, sessionKeys.distinct().size)
            providerSessionIds.zip(sessionKeys).forEach { (providerSessionId, sessionKey) ->
                // This exact CLI shape isolates the source; hidden cleanup is server-owned.
                assertTrue(MODEL_RUN_SESSION_ID.matches(providerSessionId))
                assertEquals("agent:main:explicit:$providerSessionId", sessionKey)
                assertFalse(sessionKey.contains(callerSession))
                assertFalse(sessionKey.contains(callerPrompt))
                assertFalse(sessionKey.contains("request-isolation"))
            }
            gateway.close()
        }

    @Test
    fun `outcome unknown reconciliation reuses caller key and identical wire request`() = runTest {
        val port = FakeRpcPort()
        var attempts = 0
        port.responder = { call ->
            attempts += 1
            if (attempts == 1) OpenClawRpcResult.OutcomeUnknown else acceptInFlight(call)
        }
        val gateway = OpenClawRemoteAgentGateway(port, this)
        runCurrent()
        val request = request("request-unknown", "session-unknown", "do work")

        val unknown = gateway.start(request)
        val reconciled = gateway.start(request)

        assertEquals(RemoteAgentStartResult.OutcomeUnknown(request.idempotencyKey), unknown)
        assertFalse(unknown.javaClass.declaredFields.any { it.name == "runId" })
        assertEquals(
            RemoteAgentStartResult.Started(runId("request-unknown")),
            reconciled,
        )
        assertEquals(2, port.calls.size)
        assertEquals(port.calls[0].params, port.calls[1].params)
        gateway.close()
    }

    @Test
    fun `malformed accepted response degrades and reconnect permits a clean retry`() = runTest {
        val port = FakeRpcPort()
        port.responder = { call ->
            OpenClawRpcResult.Success(JsonObject().apply {
                addProperty("runId", "wrong-provider-run")
                addProperty("sessionKey", call.params["sessionKey"].asString)
                addProperty("agentId", "main")
                addProperty("status", "accepted")
                addProperty("acceptedAt", 1_800_000_000_000L)
            })
        }
        val gateway = OpenClawRemoteAgentGateway(port, this)
        runCurrent()
        val request = request("request-malformed", "session-malformed", "work")

        assertEquals(
            RemoteAgentStartResult.Refused(RemoteAgentFailureCode.PROTOCOL_MISMATCH),
            gateway.start(request),
        )
        assertEquals(RemoteAgentConnectionState.DEGRADED, gateway.connectionState.value)
        assertEquals(
            RemoteAgentStartResult.Refused(RemoteAgentFailureCode.PROTOCOL_MISMATCH),
            gateway.start(request),
        )
        assertEquals(1, port.calls.size)

        port.state.value = OpenClawRpcConnectionState.DISCONNECTED
        runCurrent()
        port.state.value = OpenClawRpcConnectionState.READY
        runCurrent()
        port.responder = ::acceptAgent
        assertEquals(
            RemoteAgentStartResult.Started(runId("request-malformed")),
            gateway.start(request),
        )
        assertEquals(2, port.calls.size)
        gateway.close()
    }

    @Test
    fun `event before accepted acknowledgement fails closed without killing collector`() = runTest {
        val port = FakeRpcPort()
        port.responder = { call ->
            port.emit(
                chatDelta(
                    call.params["idempotencyKey"].asString,
                    call.params["sessionKey"].asString,
                    0L,
                    "too early",
                ),
            )
            yield()
            acceptAgent(call)
        }
        val gateway = OpenClawRemoteAgentGateway(port, this)
        runCurrent()

        val first = gateway.start(request("request-early", "session-early", "work"))
            as RemoteAgentStartResult.Started

        assertEquals(RemoteAgentConnectionState.DEGRADED, gateway.connectionState.value)
        assertEquals(
            RemoteAgentFailureCode.PROTOCOL_MISMATCH,
            (gateway.events(first.runId).first() as RemoteAgentEvent.Failed).code,
        )
        port.state.value = OpenClawRpcConnectionState.DISCONNECTED
        runCurrent()
        port.state.value = OpenClawRpcConnectionState.READY
        runCurrent()
        port.responder = ::acceptAgent
        val second = gateway.start(request("request-after-early", "session-after-early", "work"))
            as RemoteAgentStartResult.Started
        val secondSession = port.calls.last().params["sessionKey"].asString
        port.emit(chatDelta(second.runId.value, secondSession, 0L, "collector alive"))
        runCurrent()
        assertEquals(
            "collector alive",
            (gateway.events(second.runId).first() as RemoteAgentEvent.TextDelta).text,
        )
        gateway.close()
    }

    @Test
    fun `chat projection ignores wrong run and maps wire zero sequence to contract sequence one`() =
        runTest {
            val port = FakeRpcPort()
            port.responder = ::acceptAgent
            val gateway = OpenClawRemoteAgentGateway(port, this)
            runCurrent()
            val started = gateway.start(request("request-chat", "session-chat", "work"))
                as RemoteAgentStartResult.Started
            val sessionKey = port.calls.single().params["sessionKey"].asString

            port.emit(chatDelta("different-run", sessionKey, seq = 99L, text = "ignored"))
            port.emit(chatDelta(started.runId.value, sessionKey, seq = 0L, text = "안녕"))
            runCurrent()

            val event = gateway.events(started.runId).first() as RemoteAgentEvent.TextDelta
            assertEquals(1, event.sequence)
            assertEquals("안녕", event.text)
            assertEquals("RemoteAgentEvent.TextDelta(runId=<redacted>, sequence=1, " +
                "text=<redacted>, utf8Bytes=6)", event.toString())
            gateway.close()
        }

    @Test
    fun `replace refresh emits only suffix and rejects non-prefix refresh`() = runTest {
        val port = FakeRpcPort()
        port.responder = ::acceptAgent
        val gateway = OpenClawRemoteAgentGateway(port, this)
        runCurrent()
        val started = gateway.start(request("request-replace", "session-replace", "work"))
            as RemoteAgentStartResult.Started
        val sessionKey = port.calls.single().params["sessionKey"].asString

        port.emit(chatDelta(started.runId.value, sessionKey, 1L, "hello"))
        port.emit(chatDelta(started.runId.value, sessionKey, 2L, "hello world", replace = true))
        port.emit(chatDelta(started.runId.value, sessionKey, 3L, "rewritten", replace = true))
        runCurrent()

        val events = gateway.events(started.runId).take(3).toList()
        assertEquals("hello", (events[0] as RemoteAgentEvent.TextDelta).text)
        assertEquals(" world", (events[1] as RemoteAgentEvent.TextDelta).text)
        assertEquals(
            RemoteAgentFailureCode.PROTOCOL_MISMATCH,
            (events[2] as RemoteAgentEvent.Failed).code,
        )
        val status = gateway.status(started.runId) as RemoteAgentStatusResult.Found
        assertEquals(RemoteAgentRunPhase.FAILED, status.status.phase)
        assertEquals(11, status.status.observedOutputUtf8Bytes)
        gateway.close()
    }

    @Test
    fun `final message and wait terminal reply recover canonical output when deltas are absent`() =
        runTest {
            val port = FakeRpcPort()
            port.responder = ::acceptAgent
            val gateway = OpenClawRemoteAgentGateway(port, this)
            runCurrent()
            val chatFinalRun = gateway.start(
                request("request-final-only", "session-final-only", "work"),
            ) as RemoteAgentStartResult.Started
            val finalSession = port.calls.last().params["sessionKey"].asString

            port.emit(chatFinal(chatFinalRun.runId.value, finalSession, 1L, "final answer"))
            runCurrent()

            val finalEvent = gateway.events(chatFinalRun.runId).first()
                as RemoteAgentEvent.TextDelta
            assertEquals("final answer", finalEvent.text)
            val finalStatus = gateway.status(chatFinalRun.runId) as RemoteAgentStatusResult.Found
            assertEquals(RemoteAgentRunPhase.SUCCEEDED, finalStatus.status.phase)
            assertEquals(12, finalStatus.status.observedOutputUtf8Bytes)

            val waitRun = gateway.start(
                request("request-wait-only", "session-wait-only", "work"),
            ) as RemoteAgentStartResult.Started
            port.responder = { call ->
                OpenClawRpcResult.Success(JsonObject().apply {
                    addProperty("runId", call.params["runId"].asString)
                    addProperty("status", "ok")
                    add("terminalReply", JsonObject().apply {
                        addProperty("disposition", "visible")
                        addProperty("text", "wait answer")
                    })
                })
            }

            val waited = gateway.waitForTerminal(waitRun.runId, RemoteAgentWaitTimeout(10L))

            assertEquals(
                RemoteAgentRunPhase.SUCCEEDED,
                (waited as RemoteAgentWaitResult.Terminal).status.phase,
            )
            assertEquals(
                "wait answer",
                (gateway.events(waitRun.runId).first() as RemoteAgentEvent.TextDelta).text,
            )
            assertEquals(11, waited.status.observedOutputUtf8Bytes)
            gateway.close()
        }

    @Test
    fun `agent lifecycle end remains a hint until later chat final reconciles output`() = runTest {
        val port = FakeRpcPort()
        port.responder = ::acceptAgent
        val gateway = OpenClawRemoteAgentGateway(port, this)
        runCurrent()
        val started = gateway.start(request("request-end-first", "session-end-first", "work"))
            as RemoteAgentStartResult.Started
        val sessionKey = port.calls.single().params["sessionKey"].asString

        port.emit(agentLifecycleEnd(started.runId.value, sessionKey, 1L))
        runCurrent()
        port.emit(chatFinal(started.runId.value, sessionKey, 1L, "late final answer"))
        runCurrent()

        val output = gateway.events(started.runId).first() as RemoteAgentEvent.TextDelta
        assertEquals("late final answer", output.text)
        assertEquals(2, output.sequence)
        val status = gateway.status(started.runId) as RemoteAgentStatusResult.Found
        assertEquals(RemoteAgentRunPhase.SUCCEEDED, status.status.phase)
        assertEquals("late final answer".toByteArray().size, status.status.observedOutputUtf8Bytes)
        gateway.close()
    }

    @Test
    fun `out of order and malformed owned chat events fail closed`() = runTest {
        val port = FakeRpcPort()
        port.responder = ::acceptAgent
        val gateway = OpenClawRemoteAgentGateway(port, this)
        runCurrent()
        val first = gateway.start(request("request-order", "session-order", "work"))
            as RemoteAgentStartResult.Started
        val firstSession = port.calls.last().params["sessionKey"].asString
        port.emit(chatDelta(first.runId.value, firstSession, 4L, "a"))
        port.emit(chatDelta(first.runId.value, firstSession, 3L, "b"))
        runCurrent()
        val orderedEvents = gateway.events(first.runId).take(2).toList()
        assertTrue(orderedEvents[0] is RemoteAgentEvent.TextDelta)
        assertEquals(
            RemoteAgentFailureCode.PROTOCOL_MISMATCH,
            (orderedEvents[1] as RemoteAgentEvent.Failed).code,
        )

        port.state.value = OpenClawRpcConnectionState.DISCONNECTED
        runCurrent()
        port.state.value = OpenClawRpcConnectionState.READY
        runCurrent()
        val second = gateway.start(request("request-shape", "session-shape", "work"))
            as RemoteAgentStartResult.Started
        val secondSession = port.calls.last().params["sessionKey"].asString
        val malformed = requireNotNull(
            chatDelta(second.runId.value, secondSession, 1L, "secret").payload,
        )
            .asJsonObject
            .apply { addProperty("unexpected", "smuggled") }
        port.emit(OpenClawEventFrame("chat", malformed, 1L))
        runCurrent()
        val malformedEvent = gateway.events(second.runId).first() as RemoteAgentEvent.Failed
        assertEquals(RemoteAgentFailureCode.PROTOCOL_MISMATCH, malformedEvent.code)
        assertFalse(malformedEvent.toString().contains("secret"))
        assertFalse(malformedEvent.toString().contains("smuggled"))
        gateway.close()
    }

    @Test
    fun `cumulative output and raw event ceilings are enforced without surfacing payload`() = runTest {
        val port = FakeRpcPort()
        port.responder = ::acceptAgent
        val gateway = OpenClawRemoteAgentGateway(port, this)
        runCurrent()
        val outputRun = gateway.start(
            request(
                "request-output-limit",
                "session-output-limit",
                "work",
                RemoteAgentRunLimits(maxEvents = 3, maxOutputUtf8Bytes = 5),
            ),
        ) as RemoteAgentStartResult.Started
        val outputSession = port.calls.last().params["sessionKey"].asString
        port.emit(chatDelta(outputRun.runId.value, outputSession, 1L, "123456"))
        runCurrent()

        val outputFailure = gateway.events(outputRun.runId).first() as RemoteAgentEvent.Failed
        assertEquals(RemoteAgentFailureCode.LIMIT_EXCEEDED, outputFailure.code)
        assertFalse(outputFailure.toString().contains("123456"))
        val outputStatus = gateway.status(outputRun.runId) as RemoteAgentStatusResult.Found
        assertEquals(0, outputStatus.status.observedOutputUtf8Bytes)

        val eventRun = gateway.start(
            request(
                "request-event-limit",
                "session-event-limit",
                "work",
                RemoteAgentRunLimits(maxEvents = 1, maxOutputUtf8Bytes = 32),
            ),
        ) as RemoteAgentStartResult.Started
        val eventSession = port.calls.last().params["sessionKey"].asString
        port.emit(agentIgnoredEvent(eventRun.runId.value, eventSession, seq = 1L))
        port.emit(chatDelta(eventRun.runId.value, eventSession, seq = 1L, text = "overflow"))
        runCurrent()
        val eventStatus = gateway.status(eventRun.runId) as RemoteAgentStatusResult.Found
        assertEquals(RemoteAgentRunPhase.FAILED, eventStatus.status.phase)
        assertEquals(1, eventStatus.status.observedEvents)
        assertEquals(0, eventStatus.status.observedOutputUtf8Bytes)
        gateway.close()
    }

    @Test
    fun `tool streams session tool events and chat tool content fail closed`() = runTest {
        val variants = listOf<(String, String) -> OpenClawEventFrame>(
            { runId, sessionKey -> agentToolEvent(runId, sessionKey, 1L) },
            { runId, sessionKey -> sessionToolEvent(runId, sessionKey, 1L) },
            { runId, sessionKey -> chatToolMessage(runId, sessionKey, 1L) },
        )
        variants.forEachIndexed { index, eventFactory ->
            val port = FakeRpcPort()
            port.responder = ::acceptAgent
            val gateway = OpenClawRemoteAgentGateway(port, this)
            runCurrent()
            val started = gateway.start(
                request("request-tool-$index", "session-tool-$index", "work"),
            ) as RemoteAgentStartResult.Started
            val sessionKey = port.calls.single().params["sessionKey"].asString

            port.emit(eventFactory(started.runId.value, sessionKey))
            runCurrent()

            val failure = gateway.events(started.runId).first() as RemoteAgentEvent.Failed
            assertEquals(RemoteAgentFailureCode.PROTOCOL_MISMATCH, failure.code)
            assertEquals(RemoteAgentConnectionState.DEGRADED, gateway.connectionState.value)
            gateway.close()
        }
    }

    @Test
    fun `degraded state blocks new runs and remote reads but still permits exact abort`() = runTest {
        val port = FakeRpcPort()
        port.responder = ::acceptAgent
        val gateway = OpenClawRemoteAgentGateway(port, this)
        runCurrent()
        val started = gateway.start(request("request-degraded", "session-degraded", "work"))
            as RemoteAgentStartResult.Started
        val sessionKey = port.calls.single().params["sessionKey"].asString
        port.emit(agentToolEvent(started.runId.value, sessionKey, 1L))
        runCurrent()
        val callsBeforeBlockedOperations = port.calls.size

        assertEquals(
            RemoteAgentStartResult.Refused(RemoteAgentFailureCode.PROTOCOL_MISMATCH),
            gateway.start(request("request-blocked", "session-blocked", "new work")),
        )
        assertTrue(gateway.status(started.runId) is RemoteAgentStatusResult.Found)
        assertTrue(
            gateway.waitForTerminal(started.runId, RemoteAgentWaitTimeout(1L)) is
                RemoteAgentWaitResult.Terminal,
        )
        assertEquals(callsBeforeBlockedOperations, port.calls.size)

        port.responder = { call ->
            assertEquals(OpenClawProtocol.METHOD_CHAT_ABORT, call.method)
            abortSuccess(call.params["runId"].asString)
        }
        assertEquals(RemoteAgentCancelResult.Accepted, gateway.cancel(started.runId))
        assertEquals(callsBeforeBlockedOperations + 1, port.calls.size)
        gateway.close()
    }

    @Test
    fun `status and wait use exact agent wait params and distinguish observation timeout`() = runTest {
        val port = FakeRpcPort()
        port.responder = ::acceptAgent
        val gateway = OpenClawRemoteAgentGateway(port, this)
        runCurrent()
        val observed = gateway.start(request("request-observe", "session-observe", "work"))
            as RemoteAgentStartResult.Started
        port.responder = { call ->
            assertEquals(OpenClawProtocol.METHOD_AGENT_WAIT, call.method)
            waitPayload(
                runId = call.params["runId"].asString,
                status = "timeout",
                timeoutPhase = "queue",
                providerStarted = false,
            )
        }

        val status = gateway.status(observed.runId)
        val wait = gateway.waitForTerminal(observed.runId, RemoteAgentWaitTimeout(19L))

        assertTrue(status is RemoteAgentStatusResult.Unknown)
        assertTrue(wait is RemoteAgentWaitResult.TimedOut)
        assertEquals(0L, port.calls[1].params["timeoutMs"].asLong)
        assertEquals(19L, port.calls[2].params["timeoutMs"].asLong)
        assertEquals(
            listOf(
                OpenClawProtocol.METHOD_AGENT,
                OpenClawProtocol.METHOD_AGENT_WAIT,
                OpenClawProtocol.METHOD_AGENT_WAIT,
            ),
            port.calls.map(RpcCall::method),
        )
        gateway.close()
    }

    @Test
    fun `provider timeout is terminal while queue timeout is only caller observation timeout`() =
        runTest {
            val port = FakeRpcPort()
            port.responder = ::acceptAgent
            val gateway = OpenClawRemoteAgentGateway(port, this)
            runCurrent()
            val queued = gateway.start(request("request-queue", "session-queue", "work"))
                as RemoteAgentStartResult.Started
            val provider = gateway.start(request("request-provider", "session-provider", "work"))
                as RemoteAgentStartResult.Started

            port.responder = { call ->
                val id = call.params["runId"].asString
                if (id == queued.runId.value) {
                    waitPayload(id, "timeout", "queue", providerStarted = false)
                } else {
                    waitPayload(id, "timeout", "provider", providerStarted = true)
                }
            }
            val queueResult = gateway.waitForTerminal(queued.runId, RemoteAgentWaitTimeout(1L))
            val providerResult = gateway.waitForTerminal(provider.runId, RemoteAgentWaitTimeout(1L))

            assertTrue(queueResult is RemoteAgentWaitResult.TimedOut)
            assertEquals(
                RemoteAgentRunPhase.TIMED_OUT,
                (providerResult as RemoteAgentWaitResult.Terminal).status.phase,
            )
            gateway.close()
        }

    @Test
    fun `cancel maps exact run and session and rejects wrong run acknowledgement`() = runTest {
        val port = FakeRpcPort()
        port.responder = ::acceptAgent
        val gateway = OpenClawRemoteAgentGateway(port, this)
        runCurrent()
        val accepted = gateway.start(request("request-abort", "session-abort", "work"))
            as RemoteAgentStartResult.Started
        val expectedSession = port.calls.single().params["sessionKey"].asString
        port.responder = { call -> abortSuccess(call.params["runId"].asString) }

        assertEquals(RemoteAgentCancelResult.Accepted, gateway.cancel(accepted.runId))
        val abortCall = port.calls.last()
        assertEquals(OpenClawProtocol.METHOD_CHAT_ABORT, abortCall.method)
        assertEquals(setOf("sessionKey", "agentId", "runId"), abortCall.params.keySet())
        assertEquals(expectedSession, abortCall.params["sessionKey"].asString)
        assertEquals("main", abortCall.params["agentId"].asString)
        assertEquals(accepted.runId.value, abortCall.params["runId"].asString)

        port.responder = ::acceptAgent
        val wrong = gateway.start(request("request-abort-wrong", "session-abort-wrong", "work"))
            as RemoteAgentStartResult.Started
        port.responder = {
            OpenClawRpcResult.Success(JsonObject().apply {
                addProperty("ok", true)
                addProperty("aborted", true)
                add("runIds", JsonArray().apply { add("different-run") })
            })
        }
        assertEquals(
            RemoteAgentCancelResult.Refused(RemoteAgentFailureCode.PROTOCOL_MISMATCH),
            gateway.cancel(wrong.runId),
        )
        gateway.close()
    }

    @Test
    fun `an aborted agent terminal outranks the error the Gateway wraps it in`() = runTest {
        val port = FakeRpcPort()
        port.responder = ::acceptAgent
        val gateway = OpenClawRemoteAgentGateway(port, this)
        runCurrent()
        val accepted = gateway.start(request("request-abort-error", "session-abort-error", "work"))
            as RemoteAgentStartResult.Started
        val sessionKey = port.calls.single().params["sessionKey"].asString
        port.responder = { call -> abortSuccess(call.params["runId"].asString) }
        assertEquals(RemoteAgentCancelResult.Accepted, gateway.cancel(accepted.runId))

        // Exactly what OpenClaw 2026.8.1 sends for an externally aborted run: its agent channel
        // reports the abort, and its chat channel wraps the same interruption as an error.
        port.emit(agentAbortedEnd(accepted.runId.value, sessionKey, seq = 1))
        port.emit(chatError(accepted.runId.value, sessionKey, seq = 2))
        runCurrent()

        val events = gateway.events(accepted.runId).take(1).toList()
        assertEquals(
            RemoteAgentRunPhase.CANCELLED,
            (events.single() as RemoteAgentEvent.StatusChanged).phase,
        )
        val status = gateway.status(accepted.runId) as RemoteAgentStatusResult.Found
        assertEquals(RemoteAgentRunPhase.CANCELLED, status.status.phase)
        gateway.close()
    }

    @Test
    fun `an accepted abort also reinterprets a failure reported by the wait result`() = runTest {
        val port = FakeRpcPort()
        port.responder = ::acceptAgent
        val gateway = OpenClawRemoteAgentGateway(port, this)
        runCurrent()
        val accepted = gateway.start(request("request-abort-wait", "session-abort-wait", "work"))
            as RemoteAgentStartResult.Started
        port.responder = { call -> abortSuccess(call.params["runId"].asString) }
        assertEquals(RemoteAgentCancelResult.Accepted, gateway.cancel(accepted.runId))

        // agent.wait is the other path a terminal reaches this client, and it is the one that
        // actually carried the aborted run's failure on the owner's Gateway.
        port.responder = {
            waitPayload(
                runId = accepted.runId.value,
                status = "error",
                timeoutPhase = "provider",
                providerStarted = true,
            )
        }
        val status = gateway.status(accepted.runId) as RemoteAgentStatusResult.Found
        assertEquals(RemoteAgentRunPhase.CANCELLED, status.status.phase)
        gateway.close()
    }

    @Test
    fun `a refused abort leaves the following error terminal a failure`() = runTest {
        val port = FakeRpcPort()
        port.responder = ::acceptAgent
        val gateway = OpenClawRemoteAgentGateway(port, this)
        runCurrent()
        val accepted = gateway.start(request("request-abort-plain", "session-abort-plain", "work"))
            as RemoteAgentStartResult.Started
        val sessionKey = port.calls.single().params["sessionKey"].asString
        // The Gateway never acknowledged stopping this run, so nothing may reinterpret its
        // terminal: a real provider failure that happens to follow a cancel is still a failure.
        port.responder = { OpenClawRpcResult.Rejected("unavailable", retryable = false) }
        assertTrue(gateway.cancel(accepted.runId) is RemoteAgentCancelResult.Refused)

        port.emit(chatError(accepted.runId.value, sessionKey, seq = 1))
        runCurrent()

        val status = gateway.status(accepted.runId) as RemoteAgentStatusResult.Found
        assertEquals(RemoteAgentRunPhase.FAILED, status.status.phase)
        gateway.close()
    }

    @Test
    fun `an accepted abort does not reinterpret a timeout as a cancellation`() = runTest {
        val port = FakeRpcPort()
        port.responder = ::acceptAgent
        val gateway = OpenClawRemoteAgentGateway(port, this)
        runCurrent()
        val accepted = gateway.start(request("request-abort-timeout", "session-abort-timeout", "work"))
            as RemoteAgentStartResult.Started
        val sessionKey = port.calls.single().params["sessionKey"].asString
        port.responder = { call -> abortSuccess(call.params["runId"].asString) }
        assertEquals(RemoteAgentCancelResult.Accepted, gateway.cancel(accepted.runId))

        // Expiring is not being stopped, so a timeout keeps its own terminal.
        port.emit(chatTimeout(accepted.runId.value, sessionKey, seq = 1))
        runCurrent()

        val status = gateway.status(accepted.runId) as RemoteAgentStatusResult.Found
        assertEquals(RemoteAgentRunPhase.TIMED_OUT, status.status.phase)
        gateway.close()
    }

    @Test
    fun `an aborted terminal without a requested abort is not reinterpreted`() = runTest {
        val port = FakeRpcPort()
        port.responder = ::acceptAgent
        val gateway = OpenClawRemoteAgentGateway(port, this)
        runCurrent()
        val accepted = gateway.start(request("request-abort-none", "session-abort-none", "work"))
            as RemoteAgentStartResult.Started
        val sessionKey = port.calls.single().params["sessionKey"].asString

        // This client never asked for an abort, so the conflicting pair stays a protocol failure.
        port.emit(agentAbortedEnd(accepted.runId.value, sessionKey, seq = 1))
        port.emit(chatError(accepted.runId.value, sessionKey, seq = 2))
        runCurrent()

        val events = gateway.events(accepted.runId).take(1).toList()
        assertEquals(
            RemoteAgentFailureCode.PROTOCOL_MISMATCH,
            (events.single() as RemoteAgentEvent.Failed).code,
        )
        val status = gateway.status(accepted.runId) as RemoteAgentStatusResult.Found
        assertEquals(RemoteAgentRunPhase.FAILED, status.status.phase)
        gateway.close()
    }

    @Test
    fun `a 2026 9 error detail ends the run without degrading the connection`() = runTest {
        val port = FakeRpcPort()
        port.responder = ::acceptAgent
        val gateway = OpenClawRemoteAgentGateway(port, this)
        runCurrent()
        val accepted = gateway.start(request("request-error-detail", "session-error-detail", "work"))
            as RemoteAgentStartResult.Started
        val sessionKey = port.calls.single().params["sessionKey"].asString

        port.emit(chatErrorWithDetail(accepted.runId.value, sessionKey, seq = 1))
        runCurrent()

        assertEquals(
            RemoteAgentRunPhase.FAILED,
            (gateway.status(accepted.runId) as RemoteAgentStatusResult.Found).status.phase,
        )
        // The provider failed; this client could still read the frame. A protocol violation would
        // instead degrade the gateway and refuse every later start, so a fresh start proves the
        // added `errorDetail` was accepted rather than misread as a broken contract.
        assertTrue(
            gateway.start(request("request-after-detail", "session-after-detail", "work")) is
                RemoteAgentStartResult.Started,
        )
        gateway.close()
    }

    @Test
    fun `an unknown chat error field still fails closed and degrades`() = runTest {
        val port = FakeRpcPort()
        port.responder = ::acceptAgent
        val gateway = OpenClawRemoteAgentGateway(port, this)
        runCurrent()
        val accepted = gateway.start(request("request-error-unknown", "session-error-unknown", "work"))
            as RemoteAgentStartResult.Started
        val sessionKey = port.calls.single().params["sessionKey"].asString

        port.emit(chatErrorWithUnknownField(accepted.runId.value, sessionKey, seq = 1))
        runCurrent()

        // Accepting one named 2026.9.x field must not open the envelope to arbitrary extensions.
        assertEquals(
            RemoteAgentStartResult.Refused(RemoteAgentFailureCode.PROTOCOL_MISMATCH),
            gateway.start(request("request-after-unknown", "session-after-unknown", "work")),
        )
        gateway.close()
    }

    @Test
    fun `orderly abort seals starts and covers accepted plus outcome unknown records`() = runTest {
        val port = FakeRpcPort()
        port.responder = { call ->
            if (call.params["idempotencyKey"].asString == "orderly-accepted") {
                acceptAgent(call)
            } else {
                OpenClawRpcResult.OutcomeUnknown
            }
        }
        val gateway = OpenClawRemoteAgentGateway(port, this)
        runCurrent()

        assertTrue(
            gateway.start(request("orderly-accepted", "owner-accepted", "work")) is
                RemoteAgentStartResult.Started,
        )
        assertTrue(
            gateway.start(request("orderly-unknown", "owner-unknown", "work")) is
                RemoteAgentStartResult.OutcomeUnknown,
        )
        val sessionKeys = port.calls.associate { call ->
            call.params["idempotencyKey"].asString to call.params["sessionKey"].asString
        }

        val summary = gateway.enqueueOrderlyAborts()

        assertEquals(OpenClawOrderlyAbortSummary(attempted = 2, queued = 2), summary)
        assertEquals(2, port.enqueueCalls.size)
        port.enqueueCalls.forEach { call ->
            assertEquals(OpenClawProtocol.METHOD_CHAT_ABORT, call.method)
            assertEquals(setOf("sessionKey", "agentId", "runId"), call.params.keySet())
            assertEquals("main", call.params["agentId"].asString)
            val expectedRunId = call.params["runId"].asString
            assertEquals(sessionKeys[expectedRunId], call.params["sessionKey"].asString)
        }
        assertEquals(
            RemoteAgentStartResult.Refused(RemoteAgentFailureCode.NOT_CONNECTED),
            gateway.start(request("orderly-blocked", "owner-blocked", "must not submit")),
        )
        assertEquals(2, port.calls.size)
        assertEquals(summary, gateway.enqueueOrderlyAborts())
        assertEquals(2, port.enqueueCalls.size)
        gateway.close()
        assertEquals(2, port.enqueueCalls.size)
    }

    @Test
    fun `orderly abort snapshots a submission in progress without waiting for its response`() =
        runTest {
            val port = FakeRpcPort()
            val requestEntered = CompletableDeferred<Unit>()
            val releaseResponse = CompletableDeferred<Unit>()
            port.responder = { call ->
                requestEntered.complete(Unit)
                releaseResponse.await()
                acceptAgent(call)
            }
            val gateway = OpenClawRemoteAgentGateway(port, this)
            runCurrent()
            val submitting = async(start = CoroutineStart.UNDISPATCHED) {
                gateway.start(request("orderly-submitting", "owner-submitting", "work"))
            }
            requestEntered.await()

            val summary = gateway.enqueueOrderlyAborts()

            assertEquals(OpenClawOrderlyAbortSummary(attempted = 1, queued = 1), summary)
            val startCall = port.calls.single()
            val abortCall = port.enqueueCalls.single()
            assertEquals("orderly-submitting", abortCall.params["runId"].asString)
            assertEquals(startCall.params["sessionKey"], abortCall.params["sessionKey"])
            assertEquals(
                listOf("request:agent", "seal", "enqueue:chat.abort"),
                port.operations,
            )
            assertFalse(submitting.isCompleted)

            releaseResponse.complete(Unit)
            assertTrue(submitting.await() is RemoteAgentStartResult.Started)
            gateway.close()
        }

    @Test
    fun `orderly abort reports only local attempted and queued counts`() = runTest {
        val port = FakeRpcPort()
        port.responder = { OpenClawRpcResult.OutcomeUnknown }
        var enqueueIndex = 0
        port.enqueueResponder = {
            enqueueIndex += 1
            when (enqueueIndex) {
                1 -> OpenClawRpcEnqueueResult.Queued
                2 -> OpenClawRpcEnqueueResult.NotSent(OpenClawRpcNotSentReason.BACKPRESSURE)
                else -> OpenClawRpcEnqueueResult.NotSent(OpenClawRpcNotSentReason.NOT_CONNECTED)
            }
        }
        val gateway = OpenClawRemoteAgentGateway(port, this)
        runCurrent()
        repeat(3) { index ->
            assertTrue(
                gateway.start(request("orderly-count-$index", "owner-count-$index", "work")) is
                    RemoteAgentStartResult.OutcomeUnknown,
            )
        }

        assertEquals(
            OpenClawOrderlyAbortSummary(attempted = 3, queued = 1),
            gateway.enqueueOrderlyAborts(),
        )
        assertEquals(3, port.enqueueCalls.size)
        gateway.close()
    }

    @Test
    fun `orderly abort pass is capped at all 64 tracked reservations`() = runTest {
        val port = FakeRpcPort()
        port.responder = { OpenClawRpcResult.OutcomeUnknown }
        val gateway = OpenClawRemoteAgentGateway(port, this)
        runCurrent()
        repeat(64) { index ->
            assertTrue(
                gateway.start(request("orderly-max-$index", "owner-max-$index", "work")) is
                    RemoteAgentStartResult.OutcomeUnknown,
            )
        }

        assertEquals(
            OpenClawOrderlyAbortSummary(attempted = 64, queued = 64),
            gateway.enqueueOrderlyAborts(),
        )
        assertEquals(64, port.enqueueCalls.size)
        assertEquals(
            (0 until 64).map { "orderly-max-$it" }.toSet(),
            port.enqueueCalls.map { it.params["runId"].asString }.toSet(),
        )
        gateway.close()
    }

    private fun request(
        runId: String,
        sessionId: String,
        prompt: String,
        limits: RemoteAgentRunLimits = RemoteAgentRunLimits(),
    ): RemoteAgentStartRequest = RemoteAgentStartRequest(
        idempotencyKey = requireNotNull(RemoteAgentIdempotencyKey.parse(runId)),
        sessionId = requireNotNull(RemoteAgentSessionId.parse(sessionId)),
        prompt = requireNotNull(RemoteAgentPrompt.create(prompt)),
        limits = limits,
    )

    private fun runId(value: String): RemoteAgentRunId =
        requireNotNull(RemoteAgentRunId.parse(value))

    private fun acceptAgent(call: RpcCall): OpenClawRpcResult {
        val params = call.params
        return OpenClawRpcResult.Success(JsonObject().apply {
            addProperty("runId", params["idempotencyKey"].asString)
            addProperty("sessionKey", params["sessionKey"].asString)
            addProperty("agentId", "main")
            addProperty("status", "accepted")
            addProperty("acceptedAt", 1_800_000_000_000L)
        })
    }

    private fun acceptInFlight(call: RpcCall): OpenClawRpcResult {
        val params = call.params
        return OpenClawRpcResult.Success(JsonObject().apply {
            addProperty("runId", params["idempotencyKey"].asString)
            addProperty("sessionKey", params["sessionKey"].asString)
            addProperty("agentId", "main")
            addProperty("status", "in_flight")
            addProperty("admissionPending", true)
        })
    }

    private fun abortSuccess(runId: String): OpenClawRpcResult =
        OpenClawRpcResult.Success(JsonObject().apply {
            addProperty("ok", true)
            addProperty("aborted", true)
            add("runIds", JsonArray().apply { add(runId) })
        })

    private fun waitPayload(
        runId: String,
        status: String,
        timeoutPhase: String,
        providerStarted: Boolean,
    ): OpenClawRpcResult = OpenClawRpcResult.Success(JsonObject().apply {
        addProperty("runId", runId)
        addProperty("status", status)
        addProperty("timeoutPhase", timeoutPhase)
        addProperty("providerStarted", providerStarted)
    })

    private fun chatDelta(
        runId: String,
        sessionKey: String,
        seq: Long,
        text: String,
        replace: Boolean = false,
    ): OpenClawEventFrame = OpenClawEventFrame(
        event = "chat",
        payload = JsonObject().apply {
            addProperty("runId", runId)
            addProperty("sessionKey", sessionKey)
            addProperty("agentId", "main")
            addProperty("seq", seq)
            addProperty("state", "delta")
            addProperty("deltaText", text)
            if (replace) addProperty("replace", true)
        },
        sequence = seq,
    )

    private fun chatFinal(
        runId: String,
        sessionKey: String,
        seq: Long,
        text: String,
    ): OpenClawEventFrame = OpenClawEventFrame(
        event = "chat",
        payload = JsonObject().apply {
            addProperty("runId", runId)
            addProperty("sessionKey", sessionKey)
            addProperty("agentId", "main")
            addProperty("seq", seq)
            addProperty("state", "final")
            add("message", JsonObject().apply {
                addProperty("role", "assistant")
                add("content", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", text)
                    })
                })
                addProperty("timestamp", 1_800_000_000_000L + seq)
            })
        },
        sequence = seq,
    )

    private fun agentIgnoredEvent(
        runId: String,
        sessionKey: String,
        seq: Long,
    ): OpenClawEventFrame = OpenClawEventFrame(
        event = "agent",
        payload = JsonObject().apply {
            addProperty("runId", runId)
            addProperty("sessionKey", sessionKey)
            addProperty("agentId", "main")
            addProperty("seq", seq)
            addProperty("ts", 1_800_000_000_000L + seq)
            addProperty("stream", "assistant")
            add("data", JsonObject().apply { addProperty("delta", "not-projected") })
        },
        sequence = seq,
    )

    private fun agentToolEvent(
        runId: String,
        sessionKey: String,
        seq: Long,
    ): OpenClawEventFrame = OpenClawEventFrame(
        event = "agent",
        payload = JsonObject().apply {
            addProperty("runId", runId)
            addProperty("sessionKey", sessionKey)
            addProperty("agentId", "main")
            addProperty("seq", seq)
            addProperty("ts", 1_800_000_000_000L + seq)
            addProperty("stream", "tool")
            add("data", JsonObject().apply { addProperty("phase", "start") })
        },
        sequence = seq,
    )

    private fun agentLifecycleEnd(
        runId: String,
        sessionKey: String,
        seq: Long,
    ): OpenClawEventFrame = OpenClawEventFrame(
        event = "agent",
        payload = JsonObject().apply {
            addProperty("runId", runId)
            addProperty("sessionKey", sessionKey)
            addProperty("agentId", "main")
            addProperty("seq", seq)
            addProperty("ts", 1_800_000_000_000L + seq)
            addProperty("stream", "lifecycle")
            add("data", JsonObject().apply {
                addProperty("phase", "end")
                addProperty("status", "completed")
            })
        },
        sequence = seq,
    )

    private fun agentAbortedEnd(
        runId: String,
        sessionKey: String,
        seq: Long,
    ): OpenClawEventFrame = OpenClawEventFrame(
        event = "agent",
        payload = JsonObject().apply {
            addProperty("runId", runId)
            addProperty("sessionKey", sessionKey)
            addProperty("agentId", "main")
            addProperty("seq", seq)
            addProperty("ts", 1_800_000_000_000L + seq)
            addProperty("stream", "lifecycle")
            add("data", JsonObject().apply {
                addProperty("phase", "end")
                addProperty("status", "error")
                addProperty("aborted", true)
                addProperty("stopReason", "aborted")
            })
        },
        sequence = seq,
    )

    private fun chatTimeout(
        runId: String,
        sessionKey: String,
        seq: Long,
    ): OpenClawEventFrame = OpenClawEventFrame(
        event = "chat",
        payload = JsonObject().apply {
            addProperty("runId", runId)
            addProperty("sessionKey", sessionKey)
            addProperty("agentId", "main")
            addProperty("seq", seq)
            addProperty("state", "error")
            addProperty("errorKind", "timeout")
            addProperty("errorMessage", "timed out")
        },
        sequence = seq,
    )

    private fun chatError(
        runId: String,
        sessionKey: String,
        seq: Long,
    ): OpenClawEventFrame = OpenClawEventFrame(
        event = "chat",
        payload = JsonObject().apply {
            addProperty("runId", runId)
            addProperty("sessionKey", sessionKey)
            addProperty("agentId", "main")
            addProperty("seq", seq)
            addProperty("state", "error")
            addProperty("errorMessage", "interrupted")
        },
        sequence = seq,
    )

    /** A 2026.9.x chat error terminal, which may carry the added bounded `errorDetail` object. */
    private fun chatErrorWithDetail(
        runId: String,
        sessionKey: String,
        seq: Long,
    ): OpenClawEventFrame = OpenClawEventFrame(
        event = "chat",
        payload = JsonObject().apply {
            addProperty("runId", runId)
            addProperty("sessionKey", sessionKey)
            addProperty("agentId", "main")
            addProperty("seq", seq)
            addProperty("state", "error")
            addProperty("errorMessage", "provider failed")
            add(
                "errorDetail",
                JsonObject().apply {
                    addProperty("provider", "openrouter")
                    addProperty("model", "z-ai/glm-5.3-flash")
                    addProperty("providerErrorType", "server_error")
                    addProperty("httpStatus", 502)
                    addProperty("providerErrorMessagePreview", "remote-prose-must-not-be-retained")
                },
            )
        },
        sequence = seq,
    )

    private fun chatErrorWithUnknownField(
        runId: String,
        sessionKey: String,
        seq: Long,
    ): OpenClawEventFrame = OpenClawEventFrame(
        event = "chat",
        payload = JsonObject().apply {
            addProperty("runId", runId)
            addProperty("sessionKey", sessionKey)
            addProperty("agentId", "main")
            addProperty("seq", seq)
            addProperty("state", "error")
            addProperty("errorMessage", "provider failed")
            addProperty("providerPayload", "unbounded-extension")
        },
        sequence = seq,
    )

    private fun sessionToolEvent(
        runId: String,
        sessionKey: String,
        seq: Long,
    ): OpenClawEventFrame = OpenClawEventFrame(
        event = "session.tool",
        payload = JsonObject().apply {
            addProperty("runId", runId)
            addProperty("sessionKey", sessionKey)
            addProperty("seq", seq)
            addProperty("toolCallId", "secret-tool-call")
        },
        sequence = seq,
    )

    private fun chatToolMessage(
        runId: String,
        sessionKey: String,
        seq: Long,
    ): OpenClawEventFrame = OpenClawEventFrame(
        event = "chat",
        payload = JsonObject().apply {
            addProperty("runId", runId)
            addProperty("sessionKey", sessionKey)
            addProperty("agentId", "main")
            addProperty("seq", seq)
            addProperty("state", "delta")
            addProperty("deltaText", "must-not-surface")
            add("message", JsonObject().apply {
                addProperty("role", "assistant")
                add("content", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "tool_call")
                        addProperty("name", "dangerous")
                    })
                })
            })
        },
        sequence = seq,
    )

    private data class RpcCall(
        val method: String,
        val params: JsonObject,
        val timeoutMillis: Long,
    )

    private data class EnqueueCall(
        val method: String,
        val params: JsonObject,
    )

    private class FakeRpcPort : OpenClawGatewayRpcPort {
        val state = MutableStateFlow(OpenClawRpcConnectionState.READY)
        private val mutableEvents = MutableSharedFlow<OpenClawEventFrame>(extraBufferCapacity = 32)
        val calls = mutableListOf<RpcCall>()
        val enqueueCalls = mutableListOf<EnqueueCall>()
        val operations = mutableListOf<String>()
        var requestsSealed = false
        var responder: suspend (RpcCall) -> OpenClawRpcResult = {
            OpenClawRpcResult.NotSent(OpenClawRpcNotSentReason.NOT_CONNECTED)
        }
        var enqueueResponder: (EnqueueCall) -> OpenClawRpcEnqueueResult = {
            OpenClawRpcEnqueueResult.Queued
        }

        override val connectionState: MutableStateFlow<OpenClawRpcConnectionState> = state
        override val events: Flow<OpenClawEventFrame> = mutableEvents

        override suspend fun request(
            method: String,
            params: JsonElement?,
            timeoutMillis: Long,
        ): OpenClawRpcResult {
            if (requestsSealed) {
                return OpenClawRpcResult.NotSent(OpenClawRpcNotSentReason.NOT_CONNECTED)
            }
            val call = RpcCall(method, requireNotNull(params).asJsonObject.deepCopy(), timeoutMillis)
            calls += call
            operations += "request:$method"
            return responder(call)
        }

        override fun sealPostConnectRequests() {
            requestsSealed = true
            operations += "seal"
        }

        override fun enqueueWithoutResponse(
            method: String,
            params: JsonElement?,
        ): OpenClawRpcEnqueueResult {
            val call = EnqueueCall(method, requireNotNull(params).asJsonObject.deepCopy())
            enqueueCalls += call
            operations += "enqueue:$method"
            return when {
                method != OpenClawProtocol.METHOD_CHAT_ABORT ->
                    OpenClawRpcEnqueueResult.NotSent(OpenClawRpcNotSentReason.INVALID_REQUEST)
                state.value != OpenClawRpcConnectionState.READY ->
                    OpenClawRpcEnqueueResult.NotSent(OpenClawRpcNotSentReason.NOT_CONNECTED)
                else -> enqueueResponder(call)
            }
        }

        suspend fun emit(frame: OpenClawEventFrame) {
            mutableEvents.emit(frame)
        }
    }

    private companion object {
        val MODEL_RUN_SESSION_ID =
            Regex("^model-run-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
