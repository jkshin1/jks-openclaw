package com.personaledge.core.openclaw

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.security.cert.CertificateException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

enum class OpenClawRpcConnectionState {
    DISCONNECTED,
    CONNECTING,
    READY,
}

enum class OpenClawRpcNotSentReason {
    NOT_CONNECTED,
    TOO_MANY_PENDING,
    BACKPRESSURE,
    INVALID_REQUEST,
}

sealed interface OpenClawRpcResult {
    class Success(val payload: JsonElement?) : OpenClawRpcResult {
        override fun toString(): String = "OpenClawRpcResult.Success(payload=<redacted>)"
    }

    data class Rejected(
        val code: String,
        val retryable: Boolean?,
        val retryAfterMillis: Long? = null,
        internal val malformedConnectError: Boolean = false,
    ) : OpenClawRpcResult {
        override fun toString(): String =
            "OpenClawRpcResult.Rejected(code=<redacted>, retryable=$retryable, " +
                "retryAfterMillis=$retryAfterMillis)"
    }

    data class NotSent(val reason: OpenClawRpcNotSentReason) : OpenClawRpcResult

    /** The request was accepted by the local WebSocket queue but no authoritative response arrived. */
    data object OutcomeUnknown : OpenClawRpcResult
}

/**
 * Local-only result for a response-independent RPC enqueue.
 *
 * [Queued] means only that the current WebSocket accepted the frame into its local outbound
 * queue. It is not evidence that the Gateway received, authorized, or acted on the request.
 */
sealed interface OpenClawRpcEnqueueResult {
    data object Queued : OpenClawRpcEnqueueResult

    data class NotSent(val reason: OpenClawRpcNotSentReason) : OpenClawRpcEnqueueResult
}

/** Content-free failure classes suitable for lifecycle and retry policy. */
enum class OpenClawConnectFailureCategory {
    TLS,
    IO,
    PROTOCOL,
    TIMEOUT,
    MALFORMED,
}

/**
 * Authoritative connect outcome. Remote prose, error details, credentials, and endpoints are never
 * retained here. A structured server rejection preserves only its bounded code and retry policy.
 */
sealed interface OpenClawConnectResult {
    class Connected(val hello: OpenClawHello) : OpenClawConnectResult {
        override fun toString(): String = "OpenClawConnectResult.Connected(hello=<redacted>)"
    }

    data class Rejected(
        val code: String,
        val retryable: Boolean?,
        val retryAfterMillis: Long?,
    ) : OpenClawConnectResult {
        override fun toString(): String =
            "OpenClawConnectResult.Rejected(code=<redacted>, retryable=$retryable, " +
                "retryAfterMillis=$retryAfterMillis)"
    }

    data class Failed(val category: OpenClawConnectFailureCategory) : OpenClawConnectResult
}

class OpenClawAuthToken private constructor(internal val value: String) {
    override fun toString(): String = "OpenClawAuthToken(<redacted>)"

    companion object {
        fun parse(value: String): OpenClawAuthToken? = value
            .takeIf {
                it.isNotBlank() && it.length <= 4_096 && '|' !in it && it.none(Char::isISOControl)
            }
            ?.let(::OpenClawAuthToken)
    }
}

class OpenClawConnectOptions(
    val identity: OpenClawDeviceIdentity,
    val clientVersion: String,
    val displayName: String,
    val platform: String = "android",
    val deviceFamily: String? = null,
    val locale: String = Locale.getDefault().toLanguageTag(),
    val token: OpenClawAuthToken? = null,
) {
    init {
        require(clientVersion.isSafeMetadata(96))
        require(displayName.isSafeMetadata(128))
        require(platform.isSafeMetadata(64) && '|' !in platform)
        require(
            deviceFamily == null ||
                (deviceFamily.isSafeMetadata(128) && '|' !in deviceFamily),
        )
        require(locale.length <= 64 && locale.none(Char::isISOControl))
    }

    override fun toString(): String = "OpenClawConnectOptions(identity=<redacted>, token=<redacted>)"
}

class OpenClawHello internal constructor(
    val protocol: Int,
    val serverVersion: String,
    val connectionId: String,
    val methods: Set<String>,
    val events: Set<String>,
    val grantedScopes: Set<String>,
    val negotiatedMaxPayloadBytes: Int,
    val negotiatedMaxBufferedBytes: Long,
    val issuedDeviceToken: String?,
) {
    override fun toString(): String =
        "OpenClawHello(protocol=$protocol, methods=${methods.size}, events=${events.size}, " +
            "grantedScopes=${grantedScopes.size}, issuedDeviceToken=<redacted>)"
}

/**
 * Challenge-first protocol-v4 client with bounded requests and strict response correlation.
 * It deliberately does not reconnect itself: the app lifecycle owns retry and credential policy.
 */
class OpenClawRpcClient(
    private val transport: OpenClawWebSocketTransport,
    private val codec: OpenClawFrameCodec = OpenClawFrameCodec(),
) {
    private val mutableState = MutableStateFlow(OpenClawRpcConnectionState.DISCONNECTED)
    private val mutableEvents = MutableSharedFlow<OpenClawEventFrame>(extraBufferCapacity = 64)
    private val connectMutex = Mutex()
    private val socket = AtomicReference<OpenClawWebSocketTransport.Socket?>(null)
    private val challenge = AtomicReference<CompletableDeferred<ConnectChallenge>?>(null)
    private val nextRequestId = AtomicLong(0L)
    private val activeConnection = AtomicLong(0L)
    private val lastConnectFailure = AtomicReference<ConnectFailureRecord?>(null)
    private val outboundLock = Any()
    private val correlationLock = Any()
    private val pending = mutableMapOf<String, PendingRpc>()
    private val awaitingAgentTerminalIds = mutableSetOf<String>()
    private val retiredIds = BoundedRetiredIds()
    private val preauthMetadataEvents = AtomicLong(0L)

    // Guarded by outboundLock.
    private var postConnectRequestsSealed = true

    @Volatile
    private var maxPayloadBytes = OpenClawProtocol.MAX_PREAUTH_PAYLOAD_BYTES

    @Volatile
    private var maxBufferedBytes = OpenClawProtocol.MAX_LOCAL_BUFFERED_BYTES

    @Volatile
    private var advertisedHealthConnection = 0L

    val connectionState: StateFlow<OpenClawRpcConnectionState> = mutableState.asStateFlow()
    val events: Flow<OpenClawEventFrame> = mutableEvents.asSharedFlow()

    val supportsHealthSnapshot: Boolean
        get() = mutableState.value == OpenClawRpcConnectionState.READY &&
            advertisedHealthConnection == activeConnection.get()

    suspend fun connect(
        endpoint: GatewayEndpoint,
        options: OpenClawConnectOptions,
    ): Result<OpenClawHello> = when (val result = connectDetailed(endpoint, options)) {
        is OpenClawConnectResult.Connected -> Result.success(result.hello)
        is OpenClawConnectResult.Failed,
        is OpenClawConnectResult.Rejected,
        -> Result.failure(OpenClawConnectException())
    }

    /**
     * Connects once and preserves enough content-free failure information for an owning lifecycle
     * coordinator to choose pairing, retry, or terminal protocol handling.
     */
    suspend fun connectDetailed(
        endpoint: GatewayEndpoint,
        options: OpenClawConnectOptions,
    ): OpenClawConnectResult = connectMutex.withLock {
        closeInternal()
        synchronized(outboundLock) { postConnectRequestsSealed = false }
        val connection = activeConnection.incrementAndGet()
        lastConnectFailure.set(null)
        mutableState.value = OpenClawRpcConnectionState.CONNECTING
        maxPayloadBytes = OpenClawProtocol.MAX_PREAUTH_PAYLOAD_BYTES
        maxBufferedBytes = OpenClawProtocol.MAX_LOCAL_BUFFERED_BYTES
        preauthMetadataEvents.set(0L)
        val challengeDeferred = CompletableDeferred<ConnectChallenge>()
        challenge.set(challengeDeferred)
        val openedSocket = try {
            transport.open(endpoint, TransportListener(connection))
        } catch (cancelled: CancellationException) {
            failConnection(OpenClawConnectFailureCategory.IO, connection)
            throw cancelled
        } catch (failure: Throwable) {
            val category = connectFailureFor(connection) ?: failure.toConnectFailureCategory()
            failConnection(category, connection)
            return@withLock OpenClawConnectResult.Failed(category)
        }
        if (activeConnection.get() != connection ||
            mutableState.value != OpenClawRpcConnectionState.CONNECTING
        ) {
            openedSocket.cancel()
            return@withLock OpenClawConnectResult.Failed(
                connectFailureFor(connection) ?: OpenClawConnectFailureCategory.IO,
            )
        }
        socket.set(openedSocket)

        val challengeValue = try {
            withTimeout(OpenClawProtocol.PREAUTH_TIMEOUT_MILLIS) { challengeDeferred.await() }
        } catch (_: TimeoutCancellationException) {
            return@withLock failDetailedConnect(
                connection,
                OpenClawConnectFailureCategory.TIMEOUT,
            )
        } catch (cancelled: CancellationException) {
            failConnection(OpenClawConnectFailureCategory.IO, connection)
            throw cancelled
        } catch (_: Throwable) {
            return@withLock failDetailedConnect(
                connection,
                connectFailureFor(connection) ?: OpenClawConnectFailureCategory.PROTOCOL,
            )
        }

        val params = runCatching { buildConnectParams(options, challengeValue) }
            .getOrElse {
                return@withLock failDetailedConnect(
                    connection,
                    OpenClawConnectFailureCategory.PROTOCOL,
                )
            }
        val response = try {
            requestInternal(
                method = METHOD_CONNECT,
                params = params,
                timeoutMillis = OpenClawProtocol.PREAUTH_TIMEOUT_MILLIS,
                allowConnecting = true,
            )
        } catch (cancelled: CancellationException) {
            failConnection(OpenClawConnectFailureCategory.IO, connection)
            throw cancelled
        }
        val connectResult = when (response) {
            is OpenClawRpcResult.Success -> when (val parsed = parseHello(response.payload)) {
                is ParsedHello.Valid -> OpenClawConnectResult.Connected(parsed.hello)
                ParsedHello.Malformed ->
                    OpenClawConnectResult.Failed(OpenClawConnectFailureCategory.MALFORMED)
                ParsedHello.ProtocolMismatch ->
                    OpenClawConnectResult.Failed(OpenClawConnectFailureCategory.PROTOCOL)
            }
            is OpenClawRpcResult.Rejected -> if (response.malformedConnectError) {
                OpenClawConnectResult.Failed(OpenClawConnectFailureCategory.MALFORMED)
            } else {
                OpenClawConnectResult.Rejected(
                    code = response.code,
                    retryable = response.retryable,
                    retryAfterMillis = response.retryAfterMillis,
                )
            }
            OpenClawRpcResult.OutcomeUnknown -> OpenClawConnectResult.Failed(
                connectFailureFor(connection) ?: OpenClawConnectFailureCategory.TIMEOUT,
            )
            is OpenClawRpcResult.NotSent -> OpenClawConnectResult.Failed(
                connectFailureFor(connection) ?: response.reason.toConnectFailureCategory(),
            )
        }
        if (connectResult !is OpenClawConnectResult.Connected) {
            val category = (connectResult as? OpenClawConnectResult.Failed)?.category
                ?: OpenClawConnectFailureCategory.PROTOCOL
            failConnection(category, connection)
            return@withLock connectResult
        }
        if (activeConnection.get() != connection ||
            mutableState.value != OpenClawRpcConnectionState.CONNECTING
        ) {
            return@withLock failDetailedConnect(
                connection,
                connectFailureFor(connection) ?: OpenClawConnectFailureCategory.IO,
            )
        }
        val hello = connectResult.hello
        maxPayloadBytes = hello.negotiatedMaxPayloadBytes
        maxBufferedBytes = hello.negotiatedMaxBufferedBytes
        advertisedHealthConnection = if (OpenClawProtocol.METHOD_HEALTH_READ in hello.methods) {
            connection
        } else {
            0L
        }
        challenge.set(null)
        mutableState.value = OpenClawRpcConnectionState.READY
        connectResult
    }

    /**
     * Low-level, bounded call restricted to the three methods used by the app adapter. For
     * `agent`, this returns the first accepted response and consumes the later same-id terminal
     * response only for correlation; callers obtain terminal state through `agent.wait`.
     */
    suspend fun request(
        method: String,
        params: JsonElement? = null,
        timeoutMillis: Long = OpenClawProtocol.REQUEST_TIMEOUT_MILLIS,
    ): OpenClawRpcResult {
        if (method !in OpenClawProtocol.APP_METHODS || !METHOD_NAME.matches(method)) {
            return OpenClawRpcResult.NotSent(OpenClawRpcNotSentReason.INVALID_REQUEST)
        }
        if (timeoutMillis !in 1L..OpenClawProtocol.REQUEST_TIMEOUT_MILLIS) {
            return OpenClawRpcResult.NotSent(OpenClawRpcNotSentReason.INVALID_REQUEST)
        }
        return requestInternal(method, params, timeoutMillis, allowConnecting = false)
    }

    /**
     * One explicitly requested, parameter-free read. No model run, background polling, retry or
     * arbitrary method/argument escape is added. A vanilla Gateway simply reports Unsupported.
     */
    suspend fun readHealthSnapshot(
        nowEpochMillis: () -> Long = System::currentTimeMillis,
    ): OpenClawHealthReadResult {
        if (mutableState.value != OpenClawRpcConnectionState.READY) {
            return OpenClawHealthReadResult.NotConnected
        }
        if (!supportsHealthSnapshot) return OpenClawHealthReadResult.Unsupported
        return when (val result = requestInternal(
            method = OpenClawProtocol.METHOD_HEALTH_READ,
            params = JsonObject(),
            timeoutMillis = 5_000L,
            allowConnecting = false,
        )) {
            is OpenClawRpcResult.Success -> OpenClawHealthSnapshot.parse(result.payload, nowEpochMillis())
                ?.let(OpenClawHealthReadResult::Success)
                ?: OpenClawHealthReadResult.InvalidSnapshot
            is OpenClawRpcResult.NotSent -> if (result.reason == OpenClawRpcNotSentReason.NOT_CONNECTED) {
                OpenClawHealthReadResult.NotConnected
            } else {
                OpenClawHealthReadResult.Unavailable
            }
            is OpenClawRpcResult.Rejected,
            OpenClawRpcResult.OutcomeUnknown,
            -> OpenClawHealthReadResult.Unavailable
        }
    }

    /**
     * Permanently blocks ordinary post-connect requests on the current connection.
     *
     * The gate shares the short transport-send critical section. Once this returns, every ordinary
     * request was either already handed to the socket queue or will be rejected locally. A later
     * successful [connectDetailed] starts a fresh, unsealed connection epoch.
     */
    fun sealPostConnectRequests() {
        synchronized(outboundLock) { postConnectRequestsSealed = true }
    }

    /**
     * Enqueues one response-independent request. The exact allowlist is only `chat.abort`.
     *
     * This function performs no response wait and does not synchronously infer remote
     * cancellation. Its generated request id is retired before socket enqueue so a response that
     * arrives inline or later remains correlation-safe.
     */
    fun enqueueWithoutResponse(
        method: String,
        params: JsonElement? = null,
    ): OpenClawRpcEnqueueResult {
        if (method != OpenClawProtocol.METHOD_CHAT_ABORT || !METHOD_NAME.matches(method)) {
            return OpenClawRpcEnqueueResult.NotSent(OpenClawRpcNotSentReason.INVALID_REQUEST)
        }
        if (mutableState.value != OpenClawRpcConnectionState.READY) {
            return OpenClawRpcEnqueueResult.NotSent(OpenClawRpcNotSentReason.NOT_CONNECTED)
        }
        val currentSocket = socket.get()
            ?: return OpenClawRpcEnqueueResult.NotSent(OpenClawRpcNotSentReason.NOT_CONNECTED)
        val id = "android-${nextRequestId.incrementAndGet()}"
        val encoded = try {
            codec.encodeRequest(id, method, params, maxPayloadBytes)
        } catch (_: IllegalArgumentException) {
            return OpenClawRpcEnqueueResult.NotSent(OpenClawRpcNotSentReason.INVALID_REQUEST)
        }
        val encodedBytes = encoded.toByteArray(Charsets.UTF_8).size.toLong()
        return synchronized(outboundLock) {
            if (mutableState.value != OpenClawRpcConnectionState.READY ||
                socket.get() !== currentSocket
            ) {
                return@synchronized OpenClawRpcEnqueueResult.NotSent(
                    OpenClawRpcNotSentReason.NOT_CONNECTED,
                )
            }
            val queuedBytes = currentSocket.queueSizeBytes
            if (queuedBytes !in 0L..maxBufferedBytes ||
                encodedBytes > maxBufferedBytes - queuedBytes
            ) {
                return@synchronized OpenClawRpcEnqueueResult.NotSent(
                    OpenClawRpcNotSentReason.BACKPRESSURE,
                )
            }
            synchronized(correlationLock) { retiredIds.add(id) }
            if (currentSocket.sendText(encoded)) {
                OpenClawRpcEnqueueResult.Queued
            } else {
                OpenClawRpcEnqueueResult.NotSent(OpenClawRpcNotSentReason.BACKPRESSURE)
            }
        }
    }

    fun close() = closeInternal()

    private suspend fun requestInternal(
        method: String,
        params: JsonElement?,
        timeoutMillis: Long,
        allowConnecting: Boolean,
    ): OpenClawRpcResult {
        val expectedState = mutableState.value
        if (expectedState != OpenClawRpcConnectionState.READY &&
            !(allowConnecting && expectedState == OpenClawRpcConnectionState.CONNECTING)
        ) {
            return OpenClawRpcResult.NotSent(OpenClawRpcNotSentReason.NOT_CONNECTED)
        }
        val currentSocket = socket.get()
            ?: return OpenClawRpcResult.NotSent(OpenClawRpcNotSentReason.NOT_CONNECTED)
        val id = "android-${nextRequestId.incrementAndGet()}"
        val encoded = try {
            codec.encodeRequest(id, method, params, maxPayloadBytes)
        } catch (_: IllegalArgumentException) {
            return OpenClawRpcResult.NotSent(OpenClawRpcNotSentReason.INVALID_REQUEST)
        }
        val encodedBytes = encoded.toByteArray(Charsets.UTF_8).size.toLong()
        val response = CompletableDeferred<OpenClawResponseFrame>()
        val registrationFailure: OpenClawRpcNotSentReason? = synchronized(outboundLock) {
            if (!allowConnecting && postConnectRequestsSealed) {
                OpenClawRpcNotSentReason.NOT_CONNECTED
            } else if (mutableState.value != expectedState || socket.get() !== currentSocket) {
                OpenClawRpcNotSentReason.NOT_CONNECTED
            } else if (method == OpenClawProtocol.METHOD_HEALTH_READ &&
                advertisedHealthConnection != activeConnection.get()
            ) {
                OpenClawRpcNotSentReason.INVALID_REQUEST
            } else {
                val queuedBytes = currentSocket.queueSizeBytes
                if (queuedBytes !in 0L..maxBufferedBytes ||
                    encodedBytes > maxBufferedBytes - queuedBytes
                ) {
                    OpenClawRpcNotSentReason.BACKPRESSURE
                } else {
                    val pendingFailure = synchronized(correlationLock) {
                        when {
                            pending.size >= OpenClawProtocol.MAX_PENDING_REQUESTS ->
                                OpenClawRpcNotSentReason.TOO_MANY_PENDING
                            method == OpenClawProtocol.METHOD_AGENT &&
                                awaitingAgentTerminalIds.size + pending.values.count {
                                    it.method == OpenClawProtocol.METHOD_AGENT
                                } >= OpenClawProtocol.MAX_PENDING_REQUESTS ->
                                OpenClawRpcNotSentReason.TOO_MANY_PENDING
                            pending.containsKey(id) -> OpenClawRpcNotSentReason.INVALID_REQUEST
                            else -> {
                                pending[id] = PendingRpc(method, response)
                                null
                            }
                        }
                    }
                    if (pendingFailure != null) {
                        pendingFailure
                    } else if (!currentSocket.sendText(encoded)) {
                        removePending(id, response, retire = false)
                        OpenClawRpcNotSentReason.BACKPRESSURE
                    } else {
                        null
                    }
                }
            }
        }
        if (registrationFailure != null) {
            return OpenClawRpcResult.NotSent(registrationFailure)
        }
        val received = try {
            withTimeout(timeoutMillis) { response.await() }
        } catch (_: TimeoutCancellationException) {
            removePending(id, response, retire = true)
            return OpenClawRpcResult.OutcomeUnknown
        } catch (cancelled: CancellationException) {
            removePending(id, response, retire = true)
            throw cancelled
        } catch (_: Throwable) {
            removePending(id, response, retire = true)
            return OpenClawRpcResult.OutcomeUnknown
        }
        return if (received.ok) {
            OpenClawRpcResult.Success(received.payload)
        } else {
            val error = checkNotNull(received.error)
            if (method == METHOD_CONNECT) {
                val gatewayCode = normalizeConnectErrorCode(error.code)
                if (!error.connectDetailsValid || gatewayCode == null) {
                    return OpenClawRpcResult.Rejected(
                        code = "UNKNOWN",
                        retryable = null,
                        retryAfterMillis = null,
                        malformedConnectError = true,
                    )
                }
                return OpenClawRpcResult.Rejected(
                    // v2026.8.1 uses a generic gateway code in error.code and carries the
                    // actionable connect discriminator in error.details.code.
                    code = error.connectDetailCode ?: gatewayCode,
                    retryable = error.retryable,
                    retryAfterMillis = error.retryAfterMillis,
                )
            }
            OpenClawRpcResult.Rejected(
                code = error.code,
                retryable = error.retryable,
                retryAfterMillis = error.retryAfterMillis,
            )
        }
    }

    private fun buildConnectParams(
        options: OpenClawConnectOptions,
        challenge: ConnectChallenge,
    ): JsonObject {
        val token = options.token?.value
        val payload = OpenClawDeviceAuth.buildPayloadV3(
            deviceId = options.identity.deviceId,
            role = OpenClawProtocol.ROLE,
            scopes = OpenClawProtocol.SCOPES,
            signedAtMillis = challenge.issuedAtMillis,
            token = token,
            nonce = challenge.nonce,
            platform = options.platform,
            deviceFamily = options.deviceFamily,
        )
        val client = JsonObject().apply {
            addProperty("id", OpenClawProtocol.CLIENT_ID)
            addProperty("displayName", options.displayName)
            addProperty("version", options.clientVersion)
            addProperty("platform", options.platform)
            addProperty("mode", OpenClawProtocol.CLIENT_MODE)
            options.deviceFamily?.let { addProperty("deviceFamily", it) }
        }
        val device = JsonObject().apply {
            addProperty("id", options.identity.deviceId)
            addProperty("publicKey", OpenClawDeviceAuth.publicKeyBase64Url(options.identity))
            addProperty("signature", OpenClawDeviceAuth.signPayload(options.identity, payload))
            addProperty("signedAt", challenge.issuedAtMillis)
            addProperty("nonce", challenge.nonce)
        }
        return JsonObject().apply {
            addProperty("minProtocol", OpenClawProtocol.MIN_CLIENT_WIRE_VERSION)
            addProperty("maxProtocol", OpenClawProtocol.WIRE_VERSION)
            add("client", client)
            add("caps", JsonArray())
            addProperty("role", OpenClawProtocol.ROLE)
            add("scopes", JsonArray().apply {
                OpenClawProtocol.SCOPES.forEach { add(JsonPrimitive(it)) }
            })
            if (token != null) add("auth", JsonObject().apply { addProperty("token", token) })
            add("device", device)
            if (options.locale.isNotBlank()) addProperty("locale", options.locale)
        }
    }

    private fun parseHello(payload: JsonElement?): ParsedHello = try {
        val root = payload?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return ParsedHello.Malformed
        if (root.requiredString("type", 32) != "hello-ok") return ParsedHello.Malformed
        val protocol = root.requiredPositiveInt("protocol")
        if (protocol != OpenClawProtocol.WIRE_VERSION) return ParsedHello.ProtocolMismatch
        val server = root.requiredObject("server")
        val features = root.requiredObject("features")
        val auth = root.requiredObject("auth")
        val policy = root.requiredObject("policy")
        root.requiredObject("snapshot")
        val serverVersion = server.requiredString("version", 96)
        if (serverVersion !in OpenClawProtocol.QUALIFIED_RELEASE_VERSIONS) {
            return ParsedHello.ProtocolMismatch
        }
        val connectionId = server.requiredString("connId", 128)
        val methods = features.requiredStringSet("methods")
        val events = features.requiredStringSet("events")
        if (!methods.containsAll(OpenClawProtocol.APP_METHODS)) {
            return ParsedHello.ProtocolMismatch
        }
        if (!events.containsAll(OpenClawProtocol.APP_EVENTS)) {
            return ParsedHello.ProtocolMismatch
        }
        if (auth.requiredString("role", 64) != OpenClawProtocol.ROLE) {
            return ParsedHello.ProtocolMismatch
        }
        val scopes = auth.requiredStringSet("scopes")
        if (!scopes.containsAll(OpenClawProtocol.SCOPES)) {
            return ParsedHello.ProtocolMismatch
        }
        val serverMaxPayload = policy.requiredPositiveInt("maxPayload")
        val serverMaxBuffered = policy.requiredPositiveLong("maxBufferedBytes")
        policy.requiredPositiveLong("tickIntervalMs")
        val issuedDeviceToken = auth.optionalString("deviceToken", 4_096)
        ParsedHello.Valid(
            OpenClawHello(
                protocol = protocol,
                serverVersion = serverVersion,
                connectionId = connectionId,
                methods = methods,
                events = events,
                grantedScopes = scopes,
                negotiatedMaxPayloadBytes = serverMaxPayload.coerceAtMost(
                    OpenClawProtocol.MAX_LOCAL_FRAME_BYTES,
                ),
                negotiatedMaxBufferedBytes = serverMaxBuffered.coerceAtMost(
                    OpenClawProtocol.MAX_LOCAL_BUFFERED_BYTES,
                ),
                issuedDeviceToken = issuedDeviceToken,
            ),
        )
    } catch (_: Throwable) {
        ParsedHello.Malformed
    }

    private fun receiveText(text: String) {
        val inboundLimit = if (mutableState.value == OpenClawRpcConnectionState.CONNECTING) {
            OpenClawProtocol.MAX_PREAUTH_PAYLOAD_BYTES
        } else {
            OpenClawProtocol.MAX_LOCAL_FRAME_BYTES
        }
        val frame = try {
            codec.decodeServerFrame(text, inboundLimit)
        } catch (_: OpenClawFrameException) {
            failConnection(OpenClawConnectFailureCategory.MALFORMED)
            return
        }
        when (frame) {
            is OpenClawResponseFrame -> {
                val (deferred, wasRecognized) = synchronized(correlationLock) {
                    val matched = pending.remove(frame.id)
                    when {
                        matched != null -> {
                            if (matched.method == OpenClawProtocol.METHOD_AGENT &&
                                frame.isAgentAccepted()
                            ) {
                                awaitingAgentTerminalIds += frame.id
                            } else {
                                retiredIds.add(frame.id)
                            }
                            matched.deferred to true
                        }
                        frame.id in awaitingAgentTerminalIds -> {
                            if (!frame.isAgentAccepted()) {
                                awaitingAgentTerminalIds -= frame.id
                                retiredIds.add(frame.id)
                            }
                            null to true
                        }
                        retiredIds.contains(frame.id) -> null to true
                        else -> null to false
                    }
                }
                when {
                    deferred != null -> deferred.complete(frame)
                    wasRecognized -> Unit
                    else -> failConnection(OpenClawConnectFailureCategory.PROTOCOL)
                }
            }
            is OpenClawEventFrame -> receiveEvent(frame)
        }
    }

    private fun receiveEvent(frame: OpenClawEventFrame) {
        if (frame.event == EVENT_CONNECT_CHALLENGE) {
            val challengeDeferred = challenge.get()
            if (mutableState.value != OpenClawRpcConnectionState.CONNECTING ||
                challengeDeferred == null || challengeDeferred.isCompleted
            ) {
                failConnection(OpenClawConnectFailureCategory.PROTOCOL)
                return
            }
            val parsed = parseChallenge(frame.payload)
            if (parsed == null) {
                failConnection(OpenClawConnectFailureCategory.MALFORMED)
            } else {
                challengeDeferred.complete(parsed)
            }
            return
        }
        if (mutableState.value != OpenClawRpcConnectionState.READY) {
            // v2026.8.1 registers an authenticated UI client and broadcasts presence before
            // sending hello-ok. Metadata may also follow hello before connect resumes. Discard
            // this bounded, unused metadata without exposing it or treating it as authentication.
            // Model events still fail closed until the exact hello contract has been validated.
            if (mutableState.value == OpenClawRpcConnectionState.CONNECTING &&
                challenge.get()?.isCompleted == true && frame.event in PREAUTH_METADATA_EVENTS &&
                preauthMetadataEvents.incrementAndGet() <= OpenClawProtocol.MAX_PREAUTH_METADATA_EVENTS
            ) {
                return
            }
            failConnection(OpenClawConnectFailureCategory.PROTOCOL)
            return
        }
        if (!mutableEvents.tryEmit(frame)) {
            failConnection(OpenClawConnectFailureCategory.PROTOCOL)
        }
    }

    private fun parseChallenge(payload: JsonElement?): ConnectChallenge? = runCatching {
        val obj = payload?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        if (obj.keySet().any { it !in CHALLENGE_FIELDS }) return null
        val nonce = obj.requiredString("nonce", 512)
        if ('|' in nonce) return null
        ConnectChallenge(nonce, obj.requiredNonNegativeLong("ts"))
    }.getOrNull()

    private fun failDetailedConnect(
        connection: Long,
        category: OpenClawConnectFailureCategory,
    ): OpenClawConnectResult.Failed {
        failConnection(category, connection)
        return OpenClawConnectResult.Failed(category)
    }

    private fun connectFailureFor(connection: Long): OpenClawConnectFailureCategory? =
        lastConnectFailure.get()?.takeIf { it.connection == connection }?.category

    private fun failConnection(
        category: OpenClawConnectFailureCategory,
        connection: Long = activeConnection.get(),
    ) {
        if (activeConnection.get() != connection) return
        lastConnectFailure.set(ConnectFailureRecord(connection, category))
        advertisedHealthConnection = 0L
        socket.getAndSet(null)?.cancel()
        mutableState.value = OpenClawRpcConnectionState.DISCONNECTED
        challenge.getAndSet(null)?.completeExceptionally(OpenClawConnectionFailedException(category))
        drainPending().forEach { deferred ->
            deferred.completeExceptionally(OpenClawConnectionFailedException(category))
        }
    }

    private fun closeInternal() {
        synchronized(outboundLock) {
            postConnectRequestsSealed = true
            advertisedHealthConnection = 0L
            activeConnection.incrementAndGet()
            socket.getAndSet(null)?.close()
            mutableState.value = OpenClawRpcConnectionState.DISCONNECTED
            challenge.getAndSet(null)?.completeExceptionally(OpenClawConnectionClosedException())
            drainPending().forEach { deferred ->
                deferred.completeExceptionally(OpenClawConnectionClosedException())
            }
        }
    }

    private fun removePending(
        id: String,
        expected: CompletableDeferred<OpenClawResponseFrame>,
        retire: Boolean,
    ) {
        synchronized(correlationLock) {
            val registered = pending[id]
            if (registered?.deferred !== expected) return@synchronized
            pending.remove(id)
            if (retire) {
                if (registered.method == OpenClawProtocol.METHOD_AGENT) {
                    awaitingAgentTerminalIds += id
                } else {
                    retiredIds.add(id)
                }
            }
        }
    }

    private fun drainPending(): List<CompletableDeferred<OpenClawResponseFrame>> =
        synchronized(correlationLock) {
            val drained = pending.map { (id, registered) ->
                retiredIds.add(id)
                registered.deferred
            }
            pending.clear()
            awaitingAgentTerminalIds.forEach(retiredIds::add)
            awaitingAgentTerminalIds.clear()
            drained
        }

    private inner class TransportListener(
        private val connection: Long,
    ) : OpenClawWebSocketTransport.Listener {
        private fun ifCurrent(block: () -> Unit) {
            if (activeConnection.get() == connection) block()
        }

        override fun onOpen() = Unit

        override fun onText(text: String) = ifCurrent { receiveText(text) }

        override fun onBinary(byteCount: Int) =
            ifCurrent { failConnection(OpenClawConnectFailureCategory.PROTOCOL, connection) }

        override fun onClosing(code: Int) = Unit

        override fun onClosed(code: Int) =
            ifCurrent { failConnection(OpenClawConnectFailureCategory.IO, connection) }

        override fun onFailure(kind: OpenClawWebSocketTransport.FailureKind) =
            ifCurrent { failConnection(kind.toConnectFailureCategory(), connection) }
    }

    private data class ConnectChallenge(val nonce: String, val issuedAtMillis: Long)

    private data class ConnectFailureRecord(
        val connection: Long,
        val category: OpenClawConnectFailureCategory,
    )

    private sealed interface ParsedHello {
        class Valid(val hello: OpenClawHello) : ParsedHello

        data object Malformed : ParsedHello

        data object ProtocolMismatch : ParsedHello
    }

    private data class PendingRpc(
        val method: String,
        val deferred: CompletableDeferred<OpenClawResponseFrame>,
    )

    private class BoundedRetiredIds {
        private val values = LinkedHashSet<String>()

        @Synchronized
        fun add(value: String) {
            values += value
            while (values.size > MAX_RETIRED_IDS) values.remove(values.first())
        }

        @Synchronized
        operator fun contains(value: String): Boolean = value in values
    }

    private companion object {
        const val METHOD_CONNECT = "connect"
        const val EVENT_CONNECT_CHALLENGE = "connect.challenge"
        const val MAX_RETIRED_IDS =
            OpenClawProtocol.MAX_PENDING_REQUESTS * 2 +
                OpenClawProtocol.MAX_ORDERLY_ABORT_REQUESTS
        val METHOD_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val CHALLENGE_FIELDS = setOf("nonce", "ts")
        val PREAUTH_METADATA_EVENTS = setOf("presence", "health", "tick")
    }
}

private fun OpenClawResponseFrame.isAgentAccepted(): Boolean =
    ok && payload?.takeIf(JsonElement::isJsonObject)?.asJsonObject
        ?.get("status")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString == "accepted"

class OpenClawConnectException : IllegalStateException("OpenClaw connect failed")

private class OpenClawConnectionClosedException : IllegalStateException("OpenClaw connection closed")

private class OpenClawConnectionFailedException(
    val category: OpenClawConnectFailureCategory,
) : IllegalStateException("OpenClaw connection failed")

private fun OpenClawWebSocketTransport.FailureKind.toConnectFailureCategory():
    OpenClawConnectFailureCategory = when (this) {
        OpenClawWebSocketTransport.FailureKind.TLS -> OpenClawConnectFailureCategory.TLS
        OpenClawWebSocketTransport.FailureKind.IO -> OpenClawConnectFailureCategory.IO
        OpenClawWebSocketTransport.FailureKind.PROTOCOL -> OpenClawConnectFailureCategory.PROTOCOL
    }

private fun OpenClawRpcNotSentReason.toConnectFailureCategory(): OpenClawConnectFailureCategory =
    when (this) {
        OpenClawRpcNotSentReason.NOT_CONNECTED,
        OpenClawRpcNotSentReason.BACKPRESSURE,
        -> OpenClawConnectFailureCategory.IO
        OpenClawRpcNotSentReason.TOO_MANY_PENDING,
        OpenClawRpcNotSentReason.INVALID_REQUEST,
        -> OpenClawConnectFailureCategory.PROTOCOL
    }

private fun Throwable.toConnectFailureCategory(): OpenClawConnectFailureCategory = when (this) {
    is SSLPeerUnverifiedException,
    is SSLHandshakeException,
    is SSLException,
    is CertificateException,
    -> OpenClawConnectFailureCategory.TLS
    is IOException -> OpenClawConnectFailureCategory.IO
    else -> OpenClawConnectFailureCategory.PROTOCOL
}

private fun JsonObject.requiredObject(name: String): JsonObject =
    get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject
        ?: throw OpenClawConnectException()

private fun JsonObject.requiredString(name: String, maxCharacters: Int): String {
    val element = get(name)
    if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
        throw OpenClawConnectException()
    }
    return element.asString
        .takeIf {
            it.isNotBlank() && it.length <= maxCharacters && it.none(Char::isISOControl)
        }
        ?: throw OpenClawConnectException()
}

private fun JsonObject.optionalString(name: String, maxCharacters: Int): String? {
    val element = get(name) ?: return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
        throw OpenClawConnectException()
    }
    return element.asString.takeIf {
        it.isNotBlank() && it.length <= maxCharacters && it.none(Char::isISOControl)
    }
        ?: throw OpenClawConnectException()
}

private fun JsonObject.requiredStringSet(name: String): Set<String> {
    val array = get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray
        ?: throw OpenClawConnectException()
    if (array.size() > 512) throw OpenClawConnectException()
    return array.map { element ->
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            throw OpenClawConnectException()
        }
        element.asString.takeIf {
            it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl)
        }
            ?: throw OpenClawConnectException()
    }.toSet()
}

private fun JsonObject.requiredPositiveInt(name: String): Int {
    val value = requiredNonNegativeLong(name)
    if (value !in 1L..Int.MAX_VALUE.toLong()) throw OpenClawConnectException()
    return value.toInt()
}

private fun JsonObject.requiredPositiveLong(name: String): Long =
    requiredNonNegativeLong(name).takeIf { it > 0L } ?: throw OpenClawConnectException()

private fun JsonObject.requiredNonNegativeLong(name: String): Long {
    val element = get(name)
    if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
        throw OpenClawConnectException()
    }
    return runCatching { element.asBigDecimal.longValueExact() }
        .getOrNull()
        ?.takeIf { it >= 0L }
        ?: throw OpenClawConnectException()
}

private fun String.isSafeMetadata(maxCharacters: Int): Boolean =
    isNotBlank() && length <= maxCharacters && none(Char::isISOControl)
