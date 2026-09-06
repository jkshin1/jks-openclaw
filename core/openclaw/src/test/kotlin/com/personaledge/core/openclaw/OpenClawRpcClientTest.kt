package com.personaledge.core.openclaw

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLException

class OpenClawRpcClientTest {
    @Test
    fun `health capability is optional and absent vanilla servers are never probed`() = runBlocking {
        val transport = FakeTransport()
        val client = OpenClawRpcClient(transport)
        assertEquals(OpenClawHealthReadResult.NotConnected, client.readHealthSnapshot())
        client.connect(endpoint(), options()).getOrThrow()
        assertFalse(client.supportsHealthSnapshot)
        assertEquals(OpenClawHealthReadResult.Unsupported, client.readHealthSnapshot())
        assertEquals(listOf("connect"), transport.methods)
        client.close()
    }

    @Test
    fun `advertised health capability makes one typed parameter free read and no model run`() = runBlocking {
        val transport = FakeTransport().apply {
            advertisedMethods = OpenClawProtocol.APP_METHODS + OpenClawProtocol.METHOD_HEALTH_READ
            healthPayload = healthSnapshotPayload()
        }
        val client = OpenClawRpcClient(transport)
        client.connect(endpoint(), options()).getOrThrow()
        assertTrue(client.supportsHealthSnapshot)
        val result = client.readHealthSnapshot { CHALLENGE_TIME }
        assertTrue(result is OpenClawHealthReadResult.Success)
        assertTrue((result as OpenClawHealthReadResult.Success).snapshot.healthy)
        assertEquals(listOf("connect", OpenClawProtocol.METHOD_HEALTH_READ), transport.methods)
        assertEquals(JsonObject(), transport.requests.last()["params"])
        // The generic RPC escape hatch cannot be used to add arguments to this read method.
        assertEquals(
            OpenClawRpcResult.NotSent(OpenClawRpcNotSentReason.INVALID_REQUEST),
            client.request(OpenClawProtocol.METHOD_HEALTH_READ, JsonObject().apply {
                addProperty("path", "/private/owner-data")
            }),
        )
        assertEquals(2, transport.requests.size)
        client.close()
        assertFalse(client.supportsHealthSnapshot)
        assertEquals(OpenClawHealthReadResult.NotConnected, client.readHealthSnapshot())
    }

    @Test
    fun `health rejects stale payloads and remote prose without retaining raw content`() = runBlocking {
        val transport = FakeTransport().apply {
            advertisedMethods = OpenClawProtocol.APP_METHODS + OpenClawProtocol.METHOD_HEALTH_READ
            healthPayload = healthSnapshotPayload()
        }
        val client = OpenClawRpcClient(transport)
        client.connect(endpoint(), options()).getOrThrow()
        assertEquals(OpenClawHealthReadResult.InvalidSnapshot,
            client.readHealthSnapshot { CHALLENGE_TIME + 600_001L })
        transport.healthPayload = JsonObject().apply { addProperty("secret", "remote-detail-secret") }
        assertEquals(OpenClawHealthReadResult.InvalidSnapshot, client.readHealthSnapshot { CHALLENGE_TIME })
        transport.rejectMethod = OpenClawProtocol.METHOD_HEALTH_READ
        assertEquals(OpenClawHealthReadResult.Unavailable, client.readHealthSnapshot { CHALLENGE_TIME })
        client.close()
    }

    @Test
    fun `health timeout does not retry and reconnect cannot reuse an older advertised capability`() = runTest {
        val transport = FakeTransport().apply {
            advertisedMethods = OpenClawProtocol.APP_METHODS + OpenClawProtocol.METHOD_HEALTH_READ
            ignoreMethod = OpenClawProtocol.METHOD_HEALTH_READ
        }
        val client = OpenClawRpcClient(transport)
        client.connect(endpoint(), options()).getOrThrow()
        assertEquals(OpenClawHealthReadResult.Unavailable, client.readHealthSnapshot { CHALLENGE_TIME })
        assertEquals(listOf("connect", OpenClawProtocol.METHOD_HEALTH_READ), transport.methods)
        transport.listeners.last().onFailure(OpenClawWebSocketTransport.FailureKind.IO)
        assertFalse(client.supportsHealthSnapshot)
        transport.advertisedMethods = OpenClawProtocol.APP_METHODS
        client.connect(endpoint(), options()).getOrThrow()
        assertEquals(OpenClawHealthReadResult.Unsupported, client.readHealthSnapshot { CHALLENGE_TIME })
        assertEquals(listOf("connect", OpenClawProtocol.METHOD_HEALTH_READ, "connect"), transport.methods)
        client.close()
    }

    @Test
    fun `gateway UI presence before hello and following metadata do not break pairing`() = runBlocking {
        val transport = FakeTransport().apply {
            beforeConnectResponseEvents = listOf("presence", "health", "tick")
            afterConnectResponseEvents = listOf("presence", "tick")
        }
        val client = OpenClawRpcClient(transport)

        assertTrue(client.connectDetailed(endpoint(), options()) is OpenClawConnectResult.Connected)
        assertEquals(OpenClawRpcConnectionState.READY, client.connectionState.value)
        assertEquals(listOf("connect"), transport.methods)
        assertTrue(client.request("agent.wait", JsonObject()) is OpenClawRpcResult.Success)
        client.close()
    }

    @Test
    fun `metadata before challenge and model events before hello still fail closed`() = runBlocking {
        val beforeChallenge = FakeTransport().apply {
            challengeFrame = eventFrame("presence")
        }
        val first = OpenClawRpcClient(beforeChallenge)
        assertEquals(
            OpenClawConnectResult.Failed(OpenClawConnectFailureCategory.PROTOCOL),
            first.connectDetailed(endpoint(), options()),
        )
        assertTrue(beforeChallenge.requests.isEmpty())

        for (event in listOf("agent", "chat", "device.pair.requested")) {
            val transport = FakeTransport().apply { beforeConnectResponseEvents = listOf(event) }
            val client = OpenClawRpcClient(transport)
            assertEquals(
                OpenClawConnectResult.Failed(OpenClawConnectFailureCategory.PROTOCOL),
                client.connectDetailed(endpoint(), options()),
            )
            assertEquals(OpenClawRpcConnectionState.DISCONNECTED, client.connectionState.value)
        }
    }

    @Test
    fun `preauth metadata is bounded and never substitutes for a valid hello`() = runBlocking {
        val transport = FakeTransport().apply {
            beforeConnectResponseEvents = List(OpenClawProtocol.MAX_PREAUTH_METADATA_EVENTS + 1) {
                "presence"
            }
        }
        val client = OpenClawRpcClient(transport)
        assertEquals(
            OpenClawConnectResult.Failed(OpenClawConnectFailureCategory.PROTOCOL),
            client.connectDetailed(endpoint(), options()),
        )

        transport.beforeConnectResponseEvents = List(OpenClawProtocol.MAX_PREAUTH_METADATA_EVENTS) {
            "presence"
        }
        assertTrue(client.connectDetailed(endpoint(), options()) is OpenClawConnectResult.Connected)
        client.close()
        transport.malformedHello = true
        assertEquals(
            OpenClawConnectResult.Failed(OpenClawConnectFailureCategory.MALFORMED),
            client.connectDetailed(endpoint(), options()),
        )
    }

    @Test
    fun `cancelling during connect response closes socket and permits fresh handshake`() = runBlocking {
        val transport = FakeTransport().apply { ignoreMethod = "connect" }
        val client = OpenClawRpcClient(transport)
        val connecting = launch(start = CoroutineStart.UNDISPATCHED) {
            client.connectDetailed(endpoint(), options())
        }
        assertEquals(OpenClawRpcConnectionState.CONNECTING, client.connectionState.value)

        connecting.cancelAndJoin()

        assertEquals(OpenClawRpcConnectionState.DISCONNECTED, client.connectionState.value)
        assertEquals(1, transport.cancelledSockets)
        transport.ignoreMethod = null
        assertTrue(client.connectDetailed(endpoint(), options()) is OpenClawConnectResult.Connected)
        client.close()
    }

    @Test
    fun `network loss after enqueue remains unknown without replay after reconnect`() = runBlocking {
        val transport = FakeTransport().apply { ignoreMethod = "agent" }
        val client = OpenClawRpcClient(transport)
        client.connect(endpoint(), options()).getOrThrow()
        val requested = async(start = CoroutineStart.UNDISPATCHED) {
            client.request("agent", JsonObject())
        }
        val retiredListener = transport.listeners.single()
        retiredListener.onFailure(OpenClawWebSocketTransport.FailureKind.IO)
        assertEquals(OpenClawRpcResult.OutcomeUnknown, requested.await())

        client.connect(endpoint(), options()).getOrThrow()
        retiredListener.onText(eventFrame("chat"))
        retiredListener.onClosed(1_006)

        assertEquals(listOf("connect", "agent", "connect"), transport.methods)
        assertEquals(OpenClawRpcConnectionState.READY, client.connectionState.value)
        client.close()
    }

    @Test
    fun `challenge precedes byte bound signed connect and correlated request`() = runBlocking {
        val transport = FakeTransport()
        val client = OpenClawRpcClient(transport)

        val connected = client.connect(endpoint(), options()).getOrThrow()
        val result = client.request(
            "agent.wait",
            JsonObject().apply { addProperty("runId", "run-1") },
        )

        assertEquals(OpenClawRpcConnectionState.READY, client.connectionState.value)
        assertEquals(4, connected.protocol)
        assertTrue(result is OpenClawRpcResult.Success)
        assertEquals(listOf("connect", "agent.wait"), transport.methods)
        val connect = transport.requests.first()
        assertEquals(4, connect["params"].asJsonObject["minProtocol"].asInt)
        assertEquals("gateway-client", connect["params"].asJsonObject["client"].asJsonObject["id"].asString)
        val device = connect["params"].asJsonObject["device"].asJsonObject
        val identity = options().identity
        val payload = OpenClawDeviceAuth.buildPayloadV3(
            deviceId = identity.deviceId,
            role = OpenClawProtocol.ROLE,
            scopes = OpenClawProtocol.SCOPES,
            signedAtMillis = CHALLENGE_TIME,
            token = "shared-token",
            nonce = "nonce-1",
            platform = "android",
            deviceFamily = "Phone",
        )
        assertTrue(OpenClawDeviceAuth.verify(identity, payload, device["signature"].asString))
        assertFalse(connect.toString().contains(identity.privateKeyPkcs8Base64))
    }

    @Test
    fun `sent request timeout is outcome unknown and late response does not corrupt correlation`() =
        runBlocking {
            val transport = FakeTransport()
            val client = OpenClawRpcClient(transport)
            client.connect(endpoint(), options()).getOrThrow()
            transport.ignoreMethod = "agent"

            val result = client.request("agent", JsonObject(), timeoutMillis = 5L)

            assertEquals(OpenClawRpcResult.OutcomeUnknown, result)
            transport.respondToLast(ok = true)
            assertEquals(OpenClawRpcConnectionState.READY, client.connectionState.value)
        }

    @Test
    fun `agent accepted and terminal responses sharing one RPC id remain correlated`() = runBlocking {
        val transport = FakeTransport().apply { ignoreMethod = "agent" }
        val client = OpenClawRpcClient(transport)
        client.connect(endpoint(), options()).getOrThrow()
        val requested = async(start = CoroutineStart.UNDISPATCHED) {
            client.request("agent", JsonObject())
        }

        transport.respondAgentStatus("accepted")
        assertTrue(requested.await() is OpenClawRpcResult.Success)
        transport.respondAgentStatus("ok")
        assertEquals(OpenClawRpcConnectionState.READY, client.connectionState.value)
    }

    @Test
    fun `unknown response correlation fails connection closed`() = runBlocking {
        val transport = FakeTransport()
        val client = OpenClawRpcClient(transport)
        client.connect(endpoint(), options()).getOrThrow()

        transport.listeners.last().onText(
            """{"type":"res","id":"not-issued","ok":true,"payload":{}}""",
        )

        assertEquals(OpenClawRpcConnectionState.DISCONNECTED, client.connectionState.value)
        assertEquals(
            OpenClawRpcResult.NotSent(OpenClawRpcNotSentReason.NOT_CONNECTED),
            client.request("agent.wait"),
        )
    }

    @Test
    fun `post-connect RPC surface rejects methods outside the app allowlist`() = runBlocking {
        val transport = FakeTransport()
        val client = OpenClawRpcClient(transport)
        client.connect(endpoint(), options()).getOrThrow()

        assertEquals(
            OpenClawRpcResult.NotSent(OpenClawRpcNotSentReason.INVALID_REQUEST),
            client.request("health"),
        )
        assertEquals(listOf("connect"), transport.methods)
    }

    @Test
    fun `response independent enqueue is chat abort only and remains available after seal`() =
        runBlocking {
            val transport = FakeTransport()
            val client = OpenClawRpcClient(transport)

            assertEquals(
                OpenClawRpcEnqueueResult.NotSent(OpenClawRpcNotSentReason.NOT_CONNECTED),
                client.enqueueWithoutResponse(OpenClawProtocol.METHOD_CHAT_ABORT, JsonObject()),
            )
            client.connect(endpoint(), options()).getOrThrow()
            client.sealPostConnectRequests()

            assertEquals(
                OpenClawRpcResult.NotSent(OpenClawRpcNotSentReason.NOT_CONNECTED),
                client.request(OpenClawProtocol.METHOD_AGENT_WAIT, JsonObject()),
            )
            assertEquals(
                OpenClawRpcEnqueueResult.NotSent(OpenClawRpcNotSentReason.INVALID_REQUEST),
                client.enqueueWithoutResponse(OpenClawProtocol.METHOD_AGENT, JsonObject()),
            )
            assertEquals(
                OpenClawRpcEnqueueResult.Queued,
                client.enqueueWithoutResponse(OpenClawProtocol.METHOD_CHAT_ABORT, JsonObject()),
            )
            assertEquals(
                listOf("connect", OpenClawProtocol.METHOD_CHAT_ABORT),
                transport.methods,
            )
            assertEquals(OpenClawRpcConnectionState.READY, client.connectionState.value)
        }

    @Test
    fun `response independent abort validates payload and socket backpressure`() = runBlocking {
        val transport = FakeTransport()
        val client = OpenClawRpcClient(transport)
        client.connect(endpoint(), options()).getOrThrow()

        assertEquals(
            OpenClawRpcEnqueueResult.NotSent(OpenClawRpcNotSentReason.INVALID_REQUEST),
            client.enqueueWithoutResponse(
                OpenClawProtocol.METHOD_CHAT_ABORT,
                JsonObject().apply { addProperty("oversized", "x".repeat(1_000_000)) },
            ),
        )
        transport.queueSizeBytes = 1_000_000L
        assertEquals(
            OpenClawRpcEnqueueResult.NotSent(OpenClawRpcNotSentReason.BACKPRESSURE),
            client.enqueueWithoutResponse(OpenClawProtocol.METHOD_CHAT_ABORT, JsonObject()),
        )
        transport.queueSizeBytes = 0L
        transport.sendTextSucceeds = false
        assertEquals(
            OpenClawRpcEnqueueResult.NotSent(OpenClawRpcNotSentReason.BACKPRESSURE),
            client.enqueueWithoutResponse(OpenClawProtocol.METHOD_CHAT_ABORT, JsonObject()),
        )
        assertEquals(OpenClawRpcConnectionState.READY, client.connectionState.value)
    }

    @Test
    fun `quick and late abort responses are both retired correlation safe`() = runBlocking {
        val transport = FakeTransport()
        val client = OpenClawRpcClient(transport)
        client.connect(endpoint(), options()).getOrThrow()

        assertEquals(
            OpenClawRpcEnqueueResult.Queued,
            client.enqueueWithoutResponse(OpenClawProtocol.METHOD_CHAT_ABORT, JsonObject()),
        )
        assertEquals(OpenClawRpcConnectionState.READY, client.connectionState.value)

        transport.ignoreMethod = OpenClawProtocol.METHOD_CHAT_ABORT
        assertEquals(
            OpenClawRpcEnqueueResult.Queued,
            client.enqueueWithoutResponse(OpenClawProtocol.METHOD_CHAT_ABORT, JsonObject()),
        )
        transport.respondToLast(ok = true)
        assertEquals(OpenClawRpcConnectionState.READY, client.connectionState.value)
    }

    @Test
    fun `all 64 abort ids survive late preexisting request correlations`() = runBlocking {
        val transport = FakeTransport()
        val client = OpenClawRpcClient(transport)
        client.connect(endpoint(), options()).getOrThrow()

        repeat(OpenClawProtocol.MAX_PENDING_REQUESTS) { index ->
            assertTrue(
                client.request(
                    OpenClawProtocol.METHOD_AGENT_WAIT,
                    JsonObject().apply { addProperty("runId", "prefill-$index") },
                ) is OpenClawRpcResult.Success,
            )
        }
        transport.ignoreMethod = OpenClawProtocol.METHOD_AGENT_WAIT
        val preexisting = List(OpenClawProtocol.MAX_PENDING_REQUESTS) { index ->
            async(start = CoroutineStart.UNDISPATCHED) {
                client.request(
                    OpenClawProtocol.METHOD_AGENT_WAIT,
                    JsonObject().apply { addProperty("runId", "pending-$index") },
                )
            }
        }
        transport.ignoreMethod = OpenClawProtocol.METHOD_CHAT_ABORT
        repeat(OpenClawProtocol.MAX_ORDERLY_ABORT_REQUESTS) { index ->
            assertEquals(
                OpenClawRpcEnqueueResult.Queued,
                client.enqueueWithoutResponse(
                    OpenClawProtocol.METHOD_CHAT_ABORT,
                    JsonObject().apply { addProperty("runId", "abort-$index") },
                ),
            )
        }

        val pendingStart = 1 + OpenClawProtocol.MAX_PENDING_REQUESTS
        val pendingEndExclusive = pendingStart + OpenClawProtocol.MAX_PENDING_REQUESTS
        (pendingStart until pendingEndExclusive).forEach { requestIndex ->
            transport.respondToRequest(requestIndex, ok = true)
        }
        assertTrue(preexisting.awaitAll().all { it is OpenClawRpcResult.Success })

        val abortEndExclusive = pendingEndExclusive + OpenClawProtocol.MAX_ORDERLY_ABORT_REQUESTS
        (abortEndExclusive - 1 downTo pendingEndExclusive).forEach { requestIndex ->
            transport.respondToRequest(requestIndex, ok = true)
        }
        assertEquals(OpenClawRpcConnectionState.READY, client.connectionState.value)
    }

    @Test
    fun `hello rejects a server outside the pinned OpenClaw release`() = runBlocking {
        val transport = FakeTransport().apply { serverVersion = "2026.8.2" }
        val client = OpenClawRpcClient(transport)

        assertTrue(client.connect(endpoint(), options()).isFailure)
        assertEquals(OpenClawRpcConnectionState.DISCONNECTED, client.connectionState.value)
    }

    @Test
    fun `hello accepts the separately qualified 2026 9 2 release`() = runBlocking {
        // 2026.9.2 keeps wire v4 and every param schema this client sends, so it is qualified
        // alongside the deployed 2026.8.1 baseline rather than replacing it.
        val transport = FakeTransport().apply { serverVersion = "2026.9.2" }
        val client = OpenClawRpcClient(transport)

        assertTrue(client.connect(endpoint(), options()).isSuccess)
        assertEquals(OpenClawRpcConnectionState.READY, client.connectionState.value)
    }

    @Test
    fun `detailed connect classifies a valid incompatible hello as protocol failure`() = runBlocking {
        val transport = FakeTransport().apply { serverVersion = "2026.8.2" }
        val client = OpenClawRpcClient(transport)

        assertEquals(
            OpenClawConnectResult.Failed(OpenClawConnectFailureCategory.PROTOCOL),
            client.connectDetailed(endpoint(), options()),
        )
        assertEquals(OpenClawRpcConnectionState.DISCONNECTED, client.connectionState.value)
    }

    @Test
    fun `detailed connect preserves bounded rejection policy and no remote prose`() = runBlocking {
        val transport = FakeTransport().apply {
            rejectMethod = "connect"
            rejectionCode = "NOT_PAIRED"
            rejectionRetryable = true
            rejectionRetryAfterMillis = 2_500L
        }
        val client = OpenClawRpcClient(transport)

        val result = client.connectDetailed(endpoint(), options())

        assertEquals(
            OpenClawConnectResult.Rejected(
                code = "NOT_PAIRED",
                retryable = true,
                retryAfterMillis = 2_500L,
            ),
            result,
        )
        val rendered = result.toString()
        assertFalse(rendered.contains("NOT_PAIRED"))
        assertFalse(rendered.contains("sensitive remote text"))
        assertFalse(rendered.contains("remote-detail-secret"))
        assertFalse(rendered.contains("shared-token"))
        assertFalse(rendered.contains("gateway.example.test"))
        assertEquals(OpenClawRpcConnectionState.DISCONNECTED, client.connectionState.value)
    }

    @Test
    fun `detailed connect prefers normalized v2026 detail code over generic gateway code`() =
        runBlocking {
            val transport = FakeTransport().apply {
                rejectMethod = "connect"
                rejectionCode = "INVALID_REQUEST"
                rejectionDetails = JsonObject().apply {
                    addProperty("code", "  pairing_required  ")
                    addProperty("reason", "not-paired")
                    addProperty("remediationHint", "sensitive recovery prose")
                }
                rejectionRetryable = false
                rejectionRetryAfterMillis = 2_500L
            }

            val result = OpenClawRpcClient(transport).connectDetailed(endpoint(), options())

            assertEquals(
                OpenClawConnectResult.Rejected(
                    code = "PAIRING_REQUIRED",
                    retryable = false,
                    retryAfterMillis = 2_500L,
                ),
                result,
            )
            assertFalse(result.toString().contains("PAIRING_REQUIRED"))
            assertFalse(result.toString().contains("sensitive recovery prose"))
        }

    @Test
    fun `detailed connect keeps top level retry policy with nested rate limit code`() = runBlocking {
        val transport = FakeTransport().apply {
            rejectMethod = "connect"
            rejectionCode = "INVALID_REQUEST"
            rejectionDetails = JsonObject().apply {
                addProperty("code", "AUTH_RATE_LIMITED")
                addProperty("authReason", "rate_limited")
                addProperty("recommendedNextStep", "wait_then_retry")
            }
            rejectionRetryable = true
            rejectionRetryAfterMillis = 297_000L
        }

        assertEquals(
            OpenClawConnectResult.Rejected(
                code = "AUTH_RATE_LIMITED",
                retryable = true,
                retryAfterMillis = 297_000L,
            ),
            OpenClawRpcClient(transport).connectDetailed(endpoint(), options()),
        )
    }

    @Test
    fun `detailed connect falls back to normalized gateway code when detail code is missing`() =
        runBlocking {
            val detailsWithoutCode = FakeTransport().apply {
                rejectMethod = "connect"
                rejectionCode = " not_paired "
                rejectionDetails = JsonObject().apply { addProperty("reason", "not-paired") }
            }
            val absentDetails = FakeTransport().apply {
                rejectMethod = "connect"
                rejectionCode = " unavailable "
                rejectionDetails = null
            }

            assertEquals(
                OpenClawConnectResult.Rejected("NOT_PAIRED", false, null),
                OpenClawRpcClient(detailsWithoutCode).connectDetailed(endpoint(), options()),
            )
            assertEquals(
                OpenClawConnectResult.Rejected("UNAVAILABLE", false, null),
                OpenClawRpcClient(absentDetails).connectDetailed(endpoint(), options()),
            )
        }

    @Test
    fun `detailed connect rejects malformed or extended detail code shapes`() = runBlocking {
        val invalidDetails = listOf<JsonElement>(
            JsonPrimitive("PAIRING_REQUIRED"),
            JsonNull.INSTANCE,
            JsonObject().apply { addProperty("code", 7) },
            JsonObject().apply { addProperty("code", "A".repeat(129)) },
            JsonObject().apply { addProperty("code", "PAIRING\nREQUIRED") },
            JsonObject().apply {
                addProperty("code", "PAIRING_REQUIRED")
                addProperty("credential", "must-not-survive")
            },
        )

        invalidDetails.forEach { details ->
            val transport = FakeTransport().apply {
                rejectMethod = "connect"
                rejectionCode = "INVALID_REQUEST"
                rejectionDetails = details
            }
            val result = OpenClawRpcClient(transport).connectDetailed(endpoint(), options())

            assertEquals(
                OpenClawConnectResult.Failed(OpenClawConnectFailureCategory.MALFORMED),
                result,
            )
            assertFalse(result.toString().contains("must-not-survive"))
        }
    }

    @Test
    fun `detailed connect rejects malformed top level code and unrecognized error fields`() =
        runBlocking {
            val mutations = listOf<(JsonObject) -> Unit>(
                { error -> error.addProperty("code", 7) },
                { error -> error.addProperty("code", "A".repeat(129)) },
                { error -> error.addProperty("code", "INVALID\nREQUEST") },
                { error -> error.addProperty("credential", "must-not-survive") },
            )

            mutations.forEach { mutation ->
                val transport = FakeTransport().apply {
                    rejectMethod = "connect"
                    rejectionCode = "INVALID_REQUEST"
                    rejectionDetails = JsonObject().apply {
                        addProperty("code", "PAIRING_REQUIRED")
                    }
                    rejectionErrorMutation = mutation
                }
                val result = OpenClawRpcClient(transport).connectDetailed(endpoint(), options())

                assertEquals(
                    OpenClawConnectResult.Failed(OpenClawConnectFailureCategory.MALFORMED),
                    result,
                )
                assertFalse(result.toString().contains("must-not-survive"))
            }
        }

    @Test
    fun `detailed connect maps transport failure kinds without throwable text`() = runBlocking {
        val expected = mapOf(
            OpenClawWebSocketTransport.FailureKind.TLS to OpenClawConnectFailureCategory.TLS,
            OpenClawWebSocketTransport.FailureKind.IO to OpenClawConnectFailureCategory.IO,
            OpenClawWebSocketTransport.FailureKind.PROTOCOL to OpenClawConnectFailureCategory.PROTOCOL,
        )

        expected.forEach { (transportKind, connectCategory) ->
            val client = OpenClawRpcClient(
                FakeTransport().apply { connectFailureKind = transportKind },
            )
            val result = client.connectDetailed(endpoint(), options())

            assertEquals(OpenClawConnectResult.Failed(connectCategory), result)
            assertFalse(result.toString().contains("shared-token"))
            assertFalse(result.toString().contains("gateway.example.test"))
        }
    }

    @Test
    fun `detailed connect classifies thrown open failures without retaining messages`() = runBlocking {
        val cases = listOf(
            SSLException("tls-sensitive-text") to OpenClawConnectFailureCategory.TLS,
            IOException("io-sensitive-text") to OpenClawConnectFailureCategory.IO,
            IllegalStateException("protocol-sensitive-text") to
                OpenClawConnectFailureCategory.PROTOCOL,
        )

        cases.forEach { (failure, expected) ->
            val result = OpenClawRpcClient(
                FakeTransport().apply { openFailure = failure },
            ).connectDetailed(endpoint(), options())

            assertEquals(OpenClawConnectResult.Failed(expected), result)
            assertFalse(result.toString().contains("sensitive-text"))
        }
    }

    @Test
    fun `detailed connect distinguishes malformed challenge and hello`() = runBlocking {
        val malformedChallenge = OpenClawRpcClient(
            FakeTransport().apply {
                challengeFrame =
                    """{"type":"event","event":"connect.challenge","payload":{"nonce":7}}"""
            },
        ).connectDetailed(endpoint(), options())
        val malformedHello = OpenClawRpcClient(
            FakeTransport().apply { malformedHello = true },
        ).connectDetailed(endpoint(), options())

        assertEquals(
            OpenClawConnectResult.Failed(OpenClawConnectFailureCategory.MALFORMED),
            malformedChallenge,
        )
        assertEquals(
            OpenClawConnectResult.Failed(OpenClawConnectFailureCategory.MALFORMED),
            malformedHello,
        )
    }

    @Test
    fun `detailed connect classifies binary and unknown correlation as protocol failures`() =
        runBlocking {
            val binary = OpenClawRpcClient(
                FakeTransport().apply { binaryBeforeChallenge = true },
            ).connectDetailed(endpoint(), options())
            val unknownCorrelation = OpenClawRpcClient(
                FakeTransport().apply { unknownConnectResponse = true },
            ).connectDetailed(endpoint(), options())

            assertEquals(
                OpenClawConnectResult.Failed(OpenClawConnectFailureCategory.PROTOCOL),
                binary,
            )
            assertEquals(
                OpenClawConnectResult.Failed(OpenClawConnectFailureCategory.PROTOCOL),
                unknownCorrelation,
            )
        }

    @Test
    fun `detailed connect distinguishes challenge and response timeouts`() = runTest {
        val challengeTimeout = OpenClawRpcClient(
            FakeTransport().apply { challengeFrame = null },
        ).connectDetailed(endpoint(), options())
        val responseTimeout = OpenClawRpcClient(
            FakeTransport().apply { ignoreMethod = "connect" },
        ).connectDetailed(endpoint(), options())

        assertEquals(
            OpenClawConnectResult.Failed(OpenClawConnectFailureCategory.TIMEOUT),
            challengeTimeout,
        )
        assertEquals(
            OpenClawConnectResult.Failed(OpenClawConnectFailureCategory.TIMEOUT),
            responseTimeout,
        )
    }

    @Test
    fun `hello rejects a server that cannot expose chat and agent safety events`() = runBlocking {
        OpenClawProtocol.APP_EVENTS.forEach { missingEvent ->
            val transport = FakeTransport().apply {
                advertisedEvents = OpenClawProtocol.APP_EVENTS - missingEvent
            }
            val client = OpenClawRpcClient(transport)

            assertTrue(client.connect(endpoint(), options()).isFailure)
            assertEquals(OpenClawRpcConnectionState.DISCONNECTED, client.connectionState.value)
        }
    }

    @Test
    fun `pending request ceiling is atomic across concurrent callers`() = runBlocking {
        val transport = FakeTransport().apply { ignoreMethod = "agent" }
        val client = OpenClawRpcClient(transport)
        client.connect(endpoint(), options()).getOrThrow()
        val pending = List(OpenClawProtocol.MAX_PENDING_REQUESTS) {
            async(start = CoroutineStart.UNDISPATCHED) {
                client.request("agent", JsonObject())
            }
        }

        assertEquals(
            OpenClawRpcResult.NotSent(OpenClawRpcNotSentReason.TOO_MANY_PENDING),
            client.request("agent", JsonObject()),
        )
        client.close()
        assertTrue(pending.awaitAll().all { it == OpenClawRpcResult.OutcomeUnknown })
    }

    @Test
    fun `retired socket callbacks cannot close replacement connection`() = runBlocking {
        val transport = FakeTransport()
        val client = OpenClawRpcClient(transport)
        client.connect(endpoint(), options()).getOrThrow()
        val retiredListener = transport.listeners.single()

        client.connect(endpoint(), options()).getOrThrow()
        retiredListener.onClosed(1_000)

        assertEquals(OpenClawRpcConnectionState.READY, client.connectionState.value)
        assertEquals(2, transport.listeners.size)
    }

    @Test
    fun `remote structured rejection exposes code but redacts response payload`() = runBlocking {
        val transport = FakeTransport().apply {
            rejectMethod = "chat.abort"
            rejectionRetryAfterMillis = 750L
        }
        val client = OpenClawRpcClient(transport)
        val hello = client.connect(endpoint(), options()).getOrThrow()

        val result = client.request("chat.abort", JsonObject())

        assertEquals(OpenClawRpcResult.Rejected("UNAUTHORIZED", false, 750L), result)
        assertFalse(result.toString().contains("UNAUTHORIZED"))
        assertFalse(result.toString().contains("remote-detail-secret"))
        assertNotNull(hello.issuedDeviceToken)
        assertFalse(hello.toString().contains("issued-secret"))
    }

    @Test
    fun `credentials and connect metadata reject control characters`() {
        assertEquals(null, OpenClawAuthToken.parse("secret\nsecond"))
        assertEquals(null, OpenClawAuthToken.parse("secret|second"))
        assertEquals(null, OpenClawAuthToken.parse("secret\u0000second"))
        val identity = options().identity
        val failure = runCatching {
            OpenClawConnectOptions(
                identity = identity,
                clientVersion = "1.0\rforged",
                displayName = "Personal Edge",
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            runCatching {
                OpenClawConnectOptions(
                    identity = identity,
                    clientVersion = "1.0",
                    displayName = "Personal Edge",
                    platform = "android|forged",
                )
            }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    private fun endpoint(): GatewayEndpoint = requireNotNull(
        GatewayEndpoint.parseRelease("wss://gateway.example.test/socket"),
    )

    private fun options(): OpenClawConnectOptions {
        val identity = requireNotNull(
            OpenClawDeviceIdentity.createForTest(
                ByteArray(32) { index -> (index + 1).toByte() },
            ),
        )
        return OpenClawConnectOptions(
            identity = identity,
            clientVersion = "1.0-test",
            displayName = "Personal Edge",
            platform = "android",
            deviceFamily = "Phone",
            locale = "ko-KR",
            token = requireNotNull(OpenClawAuthToken.parse("shared-token")),
        )
    }

    private class FakeTransport : OpenClawWebSocketTransport {
        val listeners = mutableListOf<OpenClawWebSocketTransport.Listener>()
        val requests = mutableListOf<JsonObject>()
        val methods: List<String>
            get() = requests.map { it["method"].asString }
        var ignoreMethod: String? = null
        var rejectMethod: String? = null
        var rejectionCode: String = "UNAUTHORIZED"
        var rejectionRetryable: Boolean? = false
        var rejectionRetryAfterMillis: Long? = null
        var rejectionDetails: JsonElement? = JsonObject().apply {
            addProperty("remediationHint", "remote-detail-secret")
        }
        var rejectionErrorMutation: ((JsonObject) -> Unit)? = null
        var serverVersion: String = OpenClawProtocol.RELEASE_VERSION
        var advertisedEvents: Set<String> = OpenClawProtocol.APP_EVENTS
        var advertisedMethods: Set<String> = OpenClawProtocol.APP_METHODS
        var healthPayload: JsonElement = JsonObject()
        var connectFailureKind: OpenClawWebSocketTransport.FailureKind? = null
        var openFailure: Throwable? = null
        var challengeFrame: String? =
            """{"type":"event","event":"connect.challenge","payload":{"nonce":"nonce-1","ts":$CHALLENGE_TIME}}"""
        var binaryBeforeChallenge: Boolean = false
        var malformedHello: Boolean = false
        var unknownConnectResponse: Boolean = false
        var queueSizeBytes: Long = 0L
        var sendTextSucceeds: Boolean = true
        var beforeConnectResponseEvents: List<String> = emptyList()
        var afterConnectResponseEvents: List<String> = emptyList()
        var cancelledSockets: Int = 0

        override fun open(
            endpoint: GatewayEndpoint,
            listener: OpenClawWebSocketTransport.Listener,
        ): OpenClawWebSocketTransport.Socket {
            openFailure?.let { throw it }
            listeners += listener
            listener.onOpen()
            connectFailureKind?.let { kind ->
                listener.onFailure(kind)
                return FakeSocket(listener, this)
            }
            if (binaryBeforeChallenge) listener.onBinary(1)
            challengeFrame?.let(listener::onText)
            return FakeSocket(listener, this)
        }

        fun respondToLast(ok: Boolean) {
            respondToRequest(requests.lastIndex, ok)
        }

        fun respondToRequest(index: Int, ok: Boolean) {
            val request = requests[index]
            val response = if (ok) {
                successResponse(request["id"].asString, JsonObject())
            } else {
                errorResponse(
                    id = request["id"].asString,
                    code = rejectionCode,
                    retryable = rejectionRetryable,
                    retryAfterMillis = rejectionRetryAfterMillis,
                    details = rejectionDetails,
                    mutateError = rejectionErrorMutation,
                )
            }
            listeners.last().onText(response)
        }

        fun respondAgentStatus(status: String) {
            val request = requests.last()
            listeners.last().onText(
                successResponse(
                    request["id"].asString,
                    JsonObject().apply {
                        addProperty("runId", request["params"].asJsonObject["idempotencyKey"]?.asString ?: "run-1")
                        addProperty("status", status)
                    },
                ),
            )
        }

        private class FakeSocket(
            private val listener: OpenClawWebSocketTransport.Listener,
            private val owner: FakeTransport,
        ) : OpenClawWebSocketTransport.Socket {
            override val queueSizeBytes: Long
                get() = owner.queueSizeBytes

            override fun sendText(text: String): Boolean {
                if (!owner.sendTextSucceeds) return false
                val request = JsonParser.parseString(text).asJsonObject
                owner.requests += request
                val method = request["method"].asString
                if (method == owner.ignoreMethod) return true
                val response = when {
                    method == owner.rejectMethod -> errorResponse(
                        id = request["id"].asString,
                        code = owner.rejectionCode,
                        retryable = owner.rejectionRetryable,
                        retryAfterMillis = owner.rejectionRetryAfterMillis,
                        details = owner.rejectionDetails,
                        mutateError = owner.rejectionErrorMutation,
                    )
                    method == "connect" -> successResponse(
                        if (owner.unknownConnectResponse) "not-issued" else request["id"].asString,
                        if (owner.malformedHello) {
                            JsonObject()
                        } else {
                            helloPayload(owner.serverVersion, owner.advertisedEvents, owner.advertisedMethods)
                        },
                    )
                    method == OpenClawProtocol.METHOD_HEALTH_READ -> successResponse(
                        request["id"].asString,
                        owner.healthPayload,
                    )
                    else -> successResponse(
                        request["id"].asString,
                        JsonObject().apply { addProperty("ok", true) },
                    )
                }
                if (method == "connect") {
                    owner.beforeConnectResponseEvents.forEach { listener.onText(eventFrame(it)) }
                }
                listener.onText(response)
                if (method == "connect") {
                    owner.afterConnectResponseEvents.forEach { listener.onText(eventFrame(it)) }
                }
                return true
            }

            override fun close(code: Int): Boolean = true

            override fun cancel() {
                owner.cancelledSockets++
            }
        }

        companion object {
            private fun successResponse(id: String, payload: JsonElement): String = JsonObject().apply {
                addProperty("type", "res")
                addProperty("id", id)
                addProperty("ok", true)
                add("payload", payload)
            }.toString()

            private fun errorResponse(
                id: String,
                code: String,
                retryable: Boolean?,
                retryAfterMillis: Long?,
                details: JsonElement?,
                mutateError: ((JsonObject) -> Unit)?,
            ): String = JsonObject().apply {
                addProperty("type", "res")
                addProperty("id", id)
                addProperty("ok", false)
                add("error", JsonObject().apply {
                    addProperty("code", code)
                    addProperty("message", "sensitive remote text")
                    details?.let { add("details", it) }
                    retryable?.let { addProperty("retryable", it) }
                    retryAfterMillis?.let { addProperty("retryAfterMs", it) }
                    mutateError?.invoke(this)
                })
            }.toString()

            private fun helloPayload(
                serverVersion: String,
                advertisedEvents: Set<String>,
                advertisedMethods: Set<String>,
            ): JsonObject = JsonParser.parseString(
                """{
                    "type":"hello-ok",
                    "protocol":4,
                    "server":{"version":"$serverVersion","connId":"conn-1"},
                    "features":{
                      "methods":${advertisedMethods.joinToString(
                          prefix = "[",
                          postfix = "]",
                      ) { method -> "\"$method\"" }},
                      "events":${advertisedEvents.joinToString(
                          prefix = "[",
                          postfix = "]",
                      ) { event -> "\"$event\"" }}
                    },
                    "snapshot":{},
                    "auth":{
                      "role":"operator",
                      "scopes":["operator.read","operator.write"],
                      "deviceToken":"issued-secret"
                    },
                    "policy":{"maxPayload":1000000,"maxBufferedBytes":1000000,"tickIntervalMs":30000}
                }""".trimIndent(),
            ).asJsonObject
        }
    }

    private companion object {
        const val CHALLENGE_TIME = 1_700_000_000_000L

        fun eventFrame(event: String): String =
            """{"type":"event","event":"$event","payload":{}}"""

        fun healthSnapshotPayload(): JsonObject = JsonObject().apply {
            addProperty("schemaVersion", 1)
            addProperty("observedAtEpochMillis", CHALLENGE_TIME)
            addProperty("gatewayHealthy", true)
            addProperty("dockerHealthy", true)
            addProperty("policyValid", true)
            addProperty("secretsClean", true)
        }
    }
}
