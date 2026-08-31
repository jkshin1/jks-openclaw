package com.personaledge.core.llm

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** LiteRT-LM Conversation runtime with a single native owner thread. */
class LiteRtLlmRuntime internal constructor(
    private val engineFactory: RuntimeEngineFactory,
    private val modelLeaseOpener: RuntimeModelLeaseOpener,
    private val cacheDirectoryProvider: RuntimeCacheDirectoryProvider,
    private val callIdFactory: CallIdFactory,
    private val cpuThreadCount: Int?,
    private val executor: ExecutorService,
) : LlmRuntime {
    constructor(
        context: Context,
        cpuThreadCount: Int? = null,
    ) : this(
        engineFactory = LiteRtEngineFactory,
        modelLeaseOpener = RuntimeModelLeaseOpener { model -> model.acquireRuntimeLease() },
        cacheDirectoryProvider = AppPrivateLiteRtCacheDirectory(context),
        callIdFactory = CallIdFactory { UUID.randomUUID().toString() },
        cpuThreadCount = cpuThreadCount,
        executor = newRuntimeExecutor(),
    )

    private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
    private val closeRequested = AtomicBoolean(false)
    private val _state = MutableStateFlow<LlmState>(LlmState.Off)

    override val state: StateFlow<LlmState> = _state.asStateFlow()

    // Accessed only from dispatcher.
    private var resources: RuntimeResources? = null
    private var activeTurn: ActiveTurn? = null
    private var pendingTurn: PendingTurn? = null
    private val terminalTurnIds = LinkedHashSet<TurnId>()

    override suspend fun initialize(
        model: VerifiedInstalledModel,
        backend: InferenceBackend,
        tools: List<LlmToolDefinition>,
    ) {
        if (closeRequested.get()) fail(LlmFailureCode.CLOSED)
        withContext(dispatcher) {
            if (closeRequested.get()) fail(LlmFailureCode.CLOSED)
            if (resources != null || _state.value == LlmState.Loading) {
                fail(LlmFailureCode.ALREADY_INITIALIZED)
            }
            if (cpuThreadCount != null && cpuThreadCount !in 1..MAX_CPU_THREADS) {
                failInitialization(LlmFailureCode.INVALID_BACKEND)
            }

            val toolSnapshot = try {
                tools.toList()
            } catch (_: Exception) {
                failInitialization(LlmFailureCode.INVALID_TOOL_DEFINITION)
            }
            val validatedTools = validateToolDefinitions(toolSnapshot)
                ?: failInitialization(LlmFailureCode.INVALID_TOOL_DEFINITION)
            if (model.manifest != PinnedModelManifest.value) {
                failInitialization(LlmFailureCode.MODEL_REJECTED)
            }

            _state.value = LlmState.Loading
            val lease = try {
                modelLeaseOpener.acquire(model)
            } catch (cancelled: CancellationException) {
                _state.value = LlmState.Off
                throw cancelled
            } catch (_: Exception) {
                failInitialization(LlmFailureCode.MODEL_REJECTED)
            }

            var openedEngine: RuntimeEngine? = null
            try {
                val candidates = when (backend) {
                    InferenceBackend.CPU -> listOf(InferenceBackend.CPU)
                    InferenceBackend.GPU -> listOf(InferenceBackend.GPU, InferenceBackend.CPU)
                }
                for (candidate in candidates) {
                    // A thermal/lifecycle cancellation cannot interrupt LiteRT's synchronous
                    // Engine.initialize(), but it must prevent entry into another backend.
                    currentCoroutineContext().ensureActive()
                    val opaquePath = try {
                        lease.revalidateAndGetOpaquePath()
                    } catch (_: Exception) {
                        throw ModelLeaseRejectedException()
                    }
                    val cachePath = try {
                        cacheDirectoryProvider.getOrCreateOpaquePath()
                    } catch (_: Exception) {
                        throw CacheDirectoryRejectedException()
                    }
                    openedEngine = try {
                        engineFactory.create(
                            modelPath = opaquePath,
                            cacheDir = cachePath,
                            manifest = model.manifest,
                            backend = candidate,
                            cpuThreadCount = cpuThreadCount,
                        )
                    } catch (_: RuntimeDriverException) {
                        null
                    }
                    if (openedEngine != null) {
                        // LiteRT-LM 0.16.1 has no Android FD input and selects its loader by
                        // filename suffix. Sandwich synchronous Engine.initialize() between
                        // entry checks and keep the reference FD open for later comparisons.
                        try {
                            if (lease.revalidateAndGetOpaquePath() != opaquePath) {
                                throw ModelLeaseRejectedException()
                            }
                        } catch (_: Exception) {
                            openedEngine.safeClose()
                            openedEngine = null
                            throw ModelLeaseRejectedException()
                        }
                        try {
                            if (cacheDirectoryProvider.getOrCreateOpaquePath() != cachePath) {
                                throw CacheDirectoryRejectedException()
                            }
                        } catch (_: Exception) {
                            openedEngine.safeClose()
                            openedEngine = null
                            throw CacheDirectoryRejectedException()
                        }
                        break
                    }
                }
                val readyEngine = openedEngine
                    ?: failInitialization(LlmFailureCode.INITIALIZATION_FAILED)
                currentCoroutineContext().ensureActive()
                if (closeRequested.get()) {
                    _state.value = LlmState.Off
                    fail(LlmFailureCode.CLOSED)
                }
                val conversation = readyEngine.createConversation(
                    validatedTools,
                    model.manifest.maxOutputTokens,
                )
                try {
                    currentCoroutineContext().ensureActive()
                    if (closeRequested.get()) {
                        _state.value = LlmState.Off
                        fail(LlmFailureCode.CLOSED)
                    }
                    resources = RuntimeResources(
                        modelLease = lease,
                        engine = readyEngine,
                        conversation = conversation,
                        tools = validatedTools,
                        manifest = model.manifest,
                        conversationOutputTokenLimit = model.manifest.maxOutputTokens,
                        conversationToolNames = validatedTools.mapTo(linkedSetOf()) { it.name },
                    )
                    _state.value = LlmState.Ready(readyEngine.backend)
                } catch (error: Exception) {
                    conversation.safeClose()
                    throw error
                }
            } catch (cancelled: CancellationException) {
                openedEngine?.safeClose()
                lease.safeClose()
                _state.value = LlmState.Off
                throw cancelled
            } catch (_: ModelLeaseRejectedException) {
                openedEngine?.safeClose()
                lease.safeClose()
                failInitialization(LlmFailureCode.MODEL_REJECTED)
            } catch (_: CacheDirectoryRejectedException) {
                openedEngine?.safeClose()
                lease.safeClose()
                failInitialization(LlmFailureCode.CACHE_REJECTED)
            } catch (error: LlmRuntimeException) {
                openedEngine?.safeClose()
                lease.safeClose()
                throw error
            } catch (_: Exception) {
                openedEngine?.safeClose()
                lease.safeClose()
                failInitialization(LlmFailureCode.INITIALIZATION_FAILED)
            }
        }
    }

    override fun streamUserTurn(
        turnId: TurnId,
        prompt: String,
    ): Flow<ModelEvent> = streamUserTurnInternal(turnId, prompt, null, null)

    override fun streamUserTurn(
        turnId: TurnId,
        prompt: String,
        maxOutputTokens: Int,
    ): Flow<ModelEvent> = streamUserTurnInternal(turnId, prompt, maxOutputTokens, null)

    override fun streamUserTurn(
        turnId: TurnId,
        prompt: String,
        maxOutputTokens: Int,
        toolScope: LlmTurnToolScope,
    ): Flow<ModelEvent> = streamUserTurnInternal(
        turnId = turnId,
        prompt = prompt,
        requestedMaxOutputTokens = maxOutputTokens,
        requestedToolNames = toolScope.toolNames,
    )

    private fun streamUserTurnInternal(
        turnId: TurnId,
        prompt: String,
        requestedMaxOutputTokens: Int?,
        requestedToolNames: Set<String>?,
    ): Flow<ModelEvent> = channelFlow {
        launch(dispatcher) {
            val emit: suspend (ModelEvent) -> Unit = { event -> send(event) }
            val failure = commonTurnFailure(turnId)
            if (failure != null) {
                emit(ModelEvent.Failure(turnId, failure))
                return@launch
            }
            if (!isValidPrompt(prompt)) {
                emit(ModelEvent.Failure(turnId, LlmFailureCode.INVALID_PROMPT))
                return@launch
            }
            if (pendingTurn != null) {
                emit(ModelEvent.Failure(turnId, LlmFailureCode.RUNTIME_BUSY))
                return@launch
            }
            if (turnId in terminalTurnIds) {
                emit(ModelEvent.Failure(turnId, LlmFailureCode.TURN_REPLAYED))
                return@launch
            }
            val current = resources ?: run {
                emit(ModelEvent.Failure(turnId, LlmFailureCode.NOT_INITIALIZED))
                return@launch
            }
            val outputTokenLimit = requestedMaxOutputTokens ?: current.manifest.maxOutputTokens
            if (outputTokenLimit !in 1..current.manifest.maxOutputTokens) {
                emit(ModelEvent.Failure(turnId, LlmFailureCode.INVALID_PROMPT))
                return@launch
            }
            val scopedTools = if (requestedToolNames == null) {
                current.tools
            } else {
                val names = try {
                    requestedToolNames.toSet()
                } catch (_: Exception) {
                    emit(ModelEvent.Failure(turnId, LlmFailureCode.INVALID_TOOL_DEFINITION))
                    return@launch
                }
                val selected = current.tools.filter { definition -> definition.name in names }
                if (selected.size != names.size) {
                    emit(ModelEvent.Failure(turnId, LlmFailureCode.INVALID_TOOL_DEFINITION))
                    return@launch
                }
                selected
            }
            val scopedToolNames = scopedTools.mapTo(linkedSetOf()) { definition -> definition.name }
            // The first slice is deliberately stateless across top-level requests. This keeps
            // accumulated Conversation KV state from silently consuming the fixed 4K budget.
            if (current.hasUserTurnHistory ||
                current.conversationOutputTokenLimit != outputTokenLimit ||
                current.conversationToolNames != scopedToolNames
            ) {
                val replacementFailure = replaceConversationAfterUnsafeEnd(
                    current,
                    outputTokenLimit,
                    scopedTools,
                )
                if (replacementFailure != null) {
                    emit(ModelEvent.Failure(turnId, replacementFailure))
                    return@launch
                }
            }
            current.hasUserTurnHistory = true
            runNativeTurn(
                turnId = turnId,
                input = RuntimeTurnInput.User(prompt),
                emit = emit,
            )
        }.join()
    }

    override fun streamToolResponses(
        turnId: TurnId,
        responses: List<TrustedToolResponse>,
    ): Flow<ModelEvent> = channelFlow {
        launch(dispatcher) {
            val emit: suspend (ModelEvent) -> Unit = { event -> send(event) }
            val failure = commonTurnFailure(turnId)
            if (failure != null) {
                emit(ModelEvent.Failure(turnId, failure))
                return@launch
            }
            val pending = pendingTurn
            if (pending == null) {
                emit(ModelEvent.Failure(turnId, LlmFailureCode.NO_PENDING_TOOL_CALLS))
                return@launch
            }
            if (pending.turnId != turnId) {
                emit(ModelEvent.Failure(turnId, LlmFailureCode.TURN_MISMATCH))
                return@launch
            }
            val responseSnapshot = try {
                responses.toList()
            } catch (_: Exception) {
                emit(ModelEvent.Failure(turnId, LlmFailureCode.INVALID_TOOL_RESPONSE))
                return@launch
            }
            val validatedResponses = validateToolResponses(pending, responseSnapshot)
            if (validatedResponses == null) {
                emit(ModelEvent.Failure(turnId, LlmFailureCode.INVALID_TOOL_RESPONSE))
                return@launch
            }

            val current = resources ?: run {
                emit(ModelEvent.Failure(turnId, LlmFailureCode.NOT_INITIALIZED))
                return@launch
            }
            val tokenCount = try {
                current.conversation.getTokenCount()
            } catch (_: Exception) {
                pendingTurn = null
                rememberTerminal(turnId)
                val recoveryFailure = replaceConversationAfterUnsafeEnd(current)
                emit(ModelEvent.Failure(turnId, recoveryFailure ?: LlmFailureCode.NATIVE_FAILURE))
                return@launch
            }
            val maximumBeforeToolResponse = (
                current.manifest.contextTokens -
                    current.conversationOutputTokenLimit -
                    TOOL_RESPONSE_TOKEN_RESERVE
                ).coerceAtLeast(0)
            if (tokenCount !in 0..maximumBeforeToolResponse) {
                pendingTurn = null
                rememberTerminal(turnId)
                val recoveryFailure = replaceConversationAfterUnsafeEnd(current)
                emit(
                    ModelEvent.Failure(
                        turnId,
                        recoveryFailure ?: LlmFailureCode.CONTEXT_BUDGET_EXCEEDED,
                    ),
                )
                return@launch
            }

            // This is the last check before the native boundary. Canonicalization above can
            // change byte size, and alternate LlmRuntime callers need the same fail-closed cap as
            // the agent encoders. Consume the pending turn so an oversized response cannot be
            // retried after its Tool work has already happened.
            if (!TrustedToolResponseBudget.allows(validatedResponses.map { it.payloadJson })) {
                pendingTurn = null
                rememberTerminal(turnId)
                val recoveryFailure = replaceConversationAfterUnsafeEnd(current)
                emit(
                    ModelEvent.Failure(
                        turnId,
                        recoveryFailure ?: LlmFailureCode.CONTEXT_BUDGET_EXCEEDED,
                    ),
                )
                return@launch
            }

            // Consume before crossing the native boundary. Ambiguous native failures cannot be retried.
            pendingTurn = null
            runNativeTurn(
                turnId = turnId,
                input = RuntimeTurnInput.ToolResponses(validatedResponses),
                emit = emit,
            )
        }.join()
    }

    override suspend fun cancel(turnId: TurnId) {
        if (!isValidTurnId(turnId)) fail(LlmFailureCode.INVALID_TURN_ID)
        val plan = withContext(dispatcher) {
            if (closeRequested.get()) fail(LlmFailureCode.CLOSED)
            val active = activeTurn
            if (active != null) {
                if (active.turnId != turnId) fail(LlmFailureCode.TURN_MISMATCH)
                var cancelFailed = false
                if (!active.nativeCancelIssued) {
                    active.nativeCancelIssued = true
                    try {
                        active.conversation.cancelProcess()
                    } catch (_: Exception) {
                        cancelFailed = true
                    }
                }
                active.job.cancel(TurnCancelledException())
                return@withContext CancelPlan(active.cleanup, cancelFailed)
            }

            val pending = pendingTurn
            if (pending == null) fail(LlmFailureCode.NO_PENDING_TOOL_CALLS)
            if (pending.turnId != turnId) fail(LlmFailureCode.TURN_MISMATCH)
            pendingTurn = null
            rememberTerminal(turnId)
            val current = resources ?: fail(LlmFailureCode.NOT_INITIALIZED)
            var cancelFailed = false
            try {
                current.conversation.cancelProcess()
            } catch (_: Exception) {
                cancelFailed = true
            }
            val recoveryFailure = replaceConversationAfterUnsafeEnd(current)
            if (recoveryFailure != null) fail(recoveryFailure)
            CancelPlan(cleanup = null, nativeCancelFailed = cancelFailed)
        }
        plan.cleanup?.await()
        if (plan.nativeCancelFailed) fail(LlmFailureCode.NATIVE_FAILURE)
        when (val currentState = state.value) {
            is LlmState.Failed -> fail(currentState.code)
            else -> Unit
        }
    }

    private fun commonTurnFailure(turnId: TurnId): LlmFailureCode? {
        if (closeRequested.get()) return LlmFailureCode.CLOSED
        if (!isValidTurnId(turnId)) return LlmFailureCode.INVALID_TURN_ID
        if (resources == null || _state.value !is LlmState.Ready) {
            return LlmFailureCode.NOT_INITIALIZED
        }
        if (activeTurn != null) return LlmFailureCode.RUNTIME_BUSY
        return null
    }

    private suspend fun runNativeTurn(
        turnId: TurnId,
        input: RuntimeTurnInput,
        emit: suspend (ModelEvent) -> Unit,
    ) {
        val current = resources ?: run {
            emit(ModelEvent.Failure(turnId, LlmFailureCode.NOT_INITIALIZED))
            return
        }
        val cleanup = CompletableDeferred<Unit>()
        val active = ActiveTurn(
            turnId = turnId,
            conversation = current.conversation,
            job = checkNotNull(currentCoroutineContext()[Job]),
            cleanup = cleanup,
        )
        activeTurn = active

        var conversationIsSafe = false
        try {
            var finalNativeToolCalls: List<RuntimeToolCall>? = null
            var emittedThoughtCharacters = 0L
            var emittedTextCharacters = 0L
            current.conversation.stream(input).collect { chunk ->
                for (delta in chunk.thoughtDeltas) {
                    emittedThoughtCharacters += delta.length
                    if (emittedThoughtCharacters > MAX_TEXT_CHARACTERS) {
                        throw InvalidNativeOutputException()
                    }
                    if (delta.isNotEmpty()) emit(ModelEvent.ThoughtDelta(turnId, delta))
                }
                for (delta in chunk.textDeltas) {
                    emittedTextCharacters += delta.length
                    if (emittedTextCharacters > MAX_TEXT_CHARACTERS) {
                        throw InvalidNativeOutputException()
                    }
                    if (delta.isNotEmpty()) emit(ModelEvent.TextDelta(turnId, delta))
                }
                if (chunk.toolCalls.isNotEmpty()) {
                    // LiteRT provides no separate final aggregate callback. Hold the latest complete
                    // tool-call message and expose it only after onDone closes the native stream.
                    val normalized = normalizeNativeToolCalls(current, chunk.toolCalls)
                        ?: throw InvalidNativeOutputException()
                    if (finalNativeToolCalls != null && finalNativeToolCalls != normalized) {
                        throw InvalidNativeOutputException()
                    }
                    finalNativeToolCalls = normalized
                }
            }

            val finalToolCalls = materializeToolCalls(current, finalNativeToolCalls.orEmpty())
                ?: throw InvalidNativeOutputException()
            if (finalToolCalls.isEmpty()) {
                rememberTerminal(turnId)
                emit(ModelEvent.Completed(turnId))
            } else {
                pendingTurn = PendingTurn(
                    turnId = turnId,
                    calls = finalToolCalls.associateBy { it.id },
                )
                emit(ModelEvent.FinalToolCalls(turnId, finalToolCalls))
                emit(ModelEvent.Completed(turnId))
            }
            conversationIsSafe = true
        } catch (cancelled: CancellationException) {
            rememberTerminal(turnId)
            if (active.nativeCancelIssued || !currentCoroutineContext().isActive) {
                throw cancelled
            }
            emit(ModelEvent.Failure(turnId, LlmFailureCode.NATIVE_FAILURE))
        } catch (_: InvalidNativeOutputException) {
            rememberTerminal(turnId)
            emit(ModelEvent.Failure(turnId, LlmFailureCode.INVALID_TOOL_CALL))
        } catch (_: RuntimeDriverException) {
            rememberTerminal(turnId)
            emit(ModelEvent.Failure(turnId, LlmFailureCode.NATIVE_FAILURE))
        } catch (_: Exception) {
            rememberTerminal(turnId)
            emit(ModelEvent.Failure(turnId, LlmFailureCode.NATIVE_FAILURE))
        } finally {
            if (!conversationIsSafe) {
                withContext(NonCancellable) {
                    pendingTurn = null
                    if (!active.nativeCancelIssued) {
                        active.nativeCancelIssued = true
                        try {
                            active.conversation.cancelProcess()
                        } catch (_: Exception) {
                            // Recovery below decides whether the runtime remains usable.
                        }
                    }
                    replaceConversationAfterUnsafeEnd(current)
                }
            }
            if (activeTurn === active) activeTurn = null
            cleanup.complete(Unit)
        }
    }

    private fun materializeToolCalls(
        current: RuntimeResources,
        nativeCalls: List<RuntimeToolCall>,
    ): List<LlmToolCall>? {
        if (nativeCalls.isEmpty()) return emptyList()
        if (nativeCalls.size > MAX_TOOL_CALLS) return null
        val knownNames = current.conversationToolNames
        val ids = HashSet<String>()
        return nativeCalls.map { call ->
            if (call.name !in knownNames) return null
            val arguments = StrictJson.canonicalize(
                source = call.argumentsJson,
                requireObject = true,
                maxBytes = MAX_JSON_BYTES,
            ) ?: return null
            val id = callIdFactory.nextId()
            if (!isValidCallId(id) || !ids.add(id)) return null
            LlmToolCall(
                id = id,
                name = call.name,
                argumentsJson = arguments,
            )
        }
    }

    private fun normalizeNativeToolCalls(
        current: RuntimeResources,
        nativeCalls: List<RuntimeToolCall>,
    ): List<RuntimeToolCall>? {
        if (nativeCalls.isEmpty() || nativeCalls.size > MAX_TOOL_CALLS) return null
        val knownNames = current.conversationToolNames
        return nativeCalls.map { call ->
            if (call.name !in knownNames) return null
            val arguments = StrictJson.canonicalize(
                source = call.argumentsJson,
                requireObject = true,
                maxBytes = MAX_JSON_BYTES,
            ) ?: return null
            call.copy(argumentsJson = arguments)
        }
    }

    private fun validateToolResponses(
        pending: PendingTurn,
        responses: List<TrustedToolResponse>,
    ): List<RuntimeToolResponse>? {
        if (responses.size != pending.calls.size || responses.isEmpty()) return null
        val byId = LinkedHashMap<String, TrustedToolResponse>()
        for (response in responses) {
            if (byId.put(response.callId, response) != null) return null
        }
        if (byId.keys != pending.calls.keys) return null
        return pending.calls.values.map { expected ->
            val response = byId.getValue(expected.id)
            if (response.name != expected.name) return null
            val payload = StrictJson.canonicalize(
                source = response.payloadJson,
                requireObject = false,
                maxBytes = MAX_JSON_BYTES,
            ) ?: return null
            RuntimeToolResponse(name = expected.name, payloadJson = payload)
        }
    }

    private fun replaceConversationAfterUnsafeEnd(
        current: RuntimeResources,
        maxOutputTokens: Int = current.conversationOutputTokenLimit,
        tools: List<LlmToolDefinition> = current.tools,
    ): LlmFailureCode? {
        current.conversation.safeClose()
        if (closeRequested.get()) return null
        return try {
            current.modelLease.revalidateAndGetOpaquePath()
            current.conversation = current.engine.createConversation(
                tools,
                maxOutputTokens,
            )
            current.conversationOutputTokenLimit = maxOutputTokens
            current.conversationToolNames = tools.mapTo(linkedSetOf()) { definition ->
                definition.name
            }
            current.hasUserTurnHistory = false
            null
        } catch (_: ModelStoreException) {
            failResources(current, LlmFailureCode.MODEL_REJECTED)
            LlmFailureCode.MODEL_REJECTED
        } catch (_: Exception) {
            failResources(current, LlmFailureCode.NATIVE_FAILURE)
            LlmFailureCode.NATIVE_FAILURE
        }
    }

    private fun failResources(
        current: RuntimeResources,
        code: LlmFailureCode,
    ) {
        pendingTurn = null
        current.engine.safeClose()
        current.modelLease.safeClose()
        if (resources === current) resources = null
        _state.value = LlmState.Failed(code)
    }

    override fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        runBlocking {
            val cleanup = withContext(dispatcher) {
                val active = activeTurn
                if (active != null) {
                    if (!active.nativeCancelIssued) {
                        active.nativeCancelIssued = true
                        try {
                            active.conversation.cancelProcess()
                        } catch (_: Exception) {
                            // Closing remains fail-closed and best effort.
                        }
                    }
                    active.job.cancel(TurnCancelledException())
                    active.cleanup
                } else {
                    null
                }
            }
            cleanup?.await()
            withContext(dispatcher) {
                pendingTurn = null
                resources?.let { current ->
                    current.conversation.safeClose()
                    current.engine.safeClose()
                    current.modelLease.safeClose()
                }
                resources = null
                _state.value = LlmState.Off
            }
        }
        dispatcher.close()
        executor.shutdown()
    }

    private fun validateToolDefinitions(
        definitions: List<LlmToolDefinition>,
    ): List<LlmToolDefinition>? {
        if (definitions.size > MAX_TOOL_DEFINITIONS) return null
        val names = HashSet<String>()
        return definitions.map { definition ->
            if (
                !TOOL_NAME.matches(definition.name) ||
                !names.add(definition.name) ||
                definition.description.isBlank() ||
                definition.description.length > MAX_TOOL_DESCRIPTION_CHARACTERS ||
                '\u0000' in definition.description
            ) {
                return null
            }
            val schema = StrictJson.canonicalize(
                source = definition.parametersJsonSchema,
                requireObject = true,
                maxBytes = MAX_SCHEMA_BYTES,
            ) ?: return null
            val schemaObject = StrictJson.parseCanonicalObject(schema) ?: return null
            val schemaType = schemaObject.get("type")
            if (
                schemaType == null ||
                !schemaType.isJsonPrimitive ||
                !schemaType.asJsonPrimitive.isString ||
                schemaType.asString != "object"
            ) {
                return null
            }
            definition.copy(parametersJsonSchema = schema)
        }
    }

    private fun rememberTerminal(turnId: TurnId) {
        terminalTurnIds += turnId
        while (terminalTurnIds.size > MAX_REMEMBERED_TURNS) {
            val iterator = terminalTurnIds.iterator()
            iterator.next()
            iterator.remove()
        }
    }

    private fun failInitialization(code: LlmFailureCode): Nothing {
        _state.value = LlmState.Failed(code)
        fail(code)
    }

    private fun fail(code: LlmFailureCode): Nothing = throw LlmRuntimeException(code)

    private fun isValidTurnId(turnId: TurnId): Boolean =
        turnId.value.length in 1..MAX_TURN_ID_CHARACTERS && TURN_ID.matches(turnId.value)

    private fun isValidCallId(callId: String): Boolean =
        callId.length in 1..MAX_CALL_ID_CHARACTERS && CALL_ID.matches(callId)

    private fun isValidPrompt(prompt: String): Boolean =
        prompt.isNotBlank() &&
            '\u0000' !in prompt &&
            prompt.toByteArray(Charsets.UTF_8).size <= MAX_USER_PROMPT_BYTES

    private data class RuntimeResources(
        val modelLease: RuntimeModelLease,
        val engine: RuntimeEngine,
        var conversation: RuntimeConversation,
        val tools: List<LlmToolDefinition>,
        val manifest: ModelManifest,
        var conversationOutputTokenLimit: Int,
        var conversationToolNames: Set<String>,
        var hasUserTurnHistory: Boolean = false,
    )

    private data class ActiveTurn(
        val turnId: TurnId,
        val conversation: RuntimeConversation,
        val job: Job,
        val cleanup: CompletableDeferred<Unit>,
        var nativeCancelIssued: Boolean = false,
    )

    private data class PendingTurn(
        val turnId: TurnId,
        val calls: Map<String, LlmToolCall>,
    )

    private data class CancelPlan(
        val cleanup: CompletableDeferred<Unit>?,
        val nativeCancelFailed: Boolean,
    )

    private class InvalidNativeOutputException : Exception()

    private class ModelLeaseRejectedException : Exception()

    private class CacheDirectoryRejectedException : Exception()

    private class TurnCancelledException : CancellationException("TURN_CANCELLED")

    companion object {
        private val TURN_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
        private val CALL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]*")
        private val TOOL_NAME = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
        private const val MAX_TURN_ID_CHARACTERS = 128
        private const val MAX_CALL_ID_CHARACTERS = 128
        private const val MAX_JSON_BYTES = 64 * 1024
        private const val MAX_SCHEMA_BYTES = 32 * 1024
        private const val MAX_TOOL_DESCRIPTION_CHARACTERS = 2_048
        private const val MAX_TOOL_DEFINITIONS = 32
        private const val MAX_TOOL_CALLS = 8
        private const val MAX_CPU_THREADS = 256
        private const val MAX_TEXT_CHARACTERS = 1_000_000L
        private const val MAX_REMEMBERED_TURNS = 256
        private const val TOOL_RESPONSE_TOKEN_RESERVE =
            TrustedToolResponseBudget.RESERVED_CONTEXT_TOKENS

        private fun newRuntimeExecutor(): ExecutorService =
            Executors.newSingleThreadExecutor(RuntimeThreadFactory)
    }
}

private object RuntimeThreadFactory : ThreadFactory {
    override fun newThread(runnable: Runnable): Thread =
        Thread(runnable, "personal-edge-litert").apply { isDaemon = true }
}

internal fun interface RuntimeModelLeaseOpener {
    fun acquire(model: VerifiedInstalledModel): RuntimeModelLease
}

internal interface RuntimeModelLease : AutoCloseable {
    fun revalidateAndGetOpaquePath(): String
}

internal fun interface CallIdFactory {
    fun nextId(): String
}

internal fun interface RuntimeEngineFactory {
    fun create(
        modelPath: String,
        cacheDir: String,
        manifest: ModelManifest,
        backend: InferenceBackend,
        cpuThreadCount: Int?,
    ): RuntimeEngine
}

internal interface RuntimeEngine : AutoCloseable {
    val backend: InferenceBackend

    fun createConversation(
        tools: List<LlmToolDefinition>,
        maxOutputTokens: Int,
    ): RuntimeConversation
}

internal interface RuntimeConversation : AutoCloseable {
    fun stream(input: RuntimeTurnInput): Flow<RuntimeChunk>

    fun getTokenCount(): Int

    fun cancelProcess()
}

internal sealed interface RuntimeTurnInput {
    data class User(val prompt: String) : RuntimeTurnInput {
        override fun toString(): String = "RuntimeTurnInput.User(prompt=<redacted>)"
    }

    data class ToolResponses(val responses: List<RuntimeToolResponse>) : RuntimeTurnInput {
        override fun toString(): String =
            "RuntimeTurnInput.ToolResponses(responseCount=${responses.size})"
    }
}

internal data class RuntimeToolResponse(
    val name: String,
    val payloadJson: String,
) {
    override fun toString(): String =
        "RuntimeToolResponse(name=$name, payloadJson=<redacted>)"
}

internal data class RuntimeChunk(
    val thoughtDeltas: List<String> = emptyList(),
    val textDeltas: List<String> = emptyList(),
    val toolCalls: List<RuntimeToolCall> = emptyList(),
) {
    override fun toString(): String =
        "RuntimeChunk(thoughtDeltaCount=${thoughtDeltas.size}, " +
            "textDeltaCount=${textDeltas.size}, toolCallCount=${toolCalls.size})"
}

internal data class RuntimeToolCall(
    val name: String,
    val argumentsJson: String,
) {
    override fun toString(): String =
        "RuntimeToolCall(name=$name, argumentsJson=<redacted>)"
}

internal class RuntimeDriverException : Exception()

private fun AutoCloseable.safeClose() {
    try {
        close()
    } catch (_: Exception) {
        // Native handles are terminal and exceptions must not leak filesystem or prompt data.
    }
}

private object StrictJson {
    private const val MAX_DEPTH = 32
    private const val MAX_CONTAINER_ENTRIES = 1_024

    fun canonicalize(
        source: String,
        requireObject: Boolean,
        maxBytes: Int,
    ): String? {
        if (source.isEmpty() || source.toByteArray(Charsets.UTF_8).size > maxBytes) return null
        return try {
            val reader = JsonReader(StringReader(source)).apply {
                strictness = Strictness.STRICT
            }
            val element = readElement(reader, depth = 0)
            if (reader.peek() != JsonToken.END_DOCUMENT) return null
            if (requireObject && !element.isJsonObject) return null
            element.toString()
        } catch (_: Exception) {
            null
        }
    }

    fun parseCanonicalObject(source: String): JsonObject? = try {
        val reader = JsonReader(StringReader(source)).apply {
            strictness = Strictness.STRICT
        }
        val element = readElement(reader, depth = 0)
        if (reader.peek() != JsonToken.END_DOCUMENT || !element.isJsonObject) {
            null
        } else {
            element.asJsonObject
        }
    } catch (_: Exception) {
        null
    }

    private fun readElement(reader: JsonReader, depth: Int): JsonElement {
        if (depth > MAX_DEPTH) throw IllegalArgumentException()
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val result = JsonObject()
                val names = HashSet<String>()
                var entries = 0
                reader.beginObject()
                while (reader.hasNext()) {
                    if (++entries > MAX_CONTAINER_ENTRIES) throw IllegalArgumentException()
                    val name = reader.nextName()
                    if (!names.add(name)) throw IllegalArgumentException()
                    result.add(name, readElement(reader, depth + 1))
                }
                reader.endObject()
                result
            }

            JsonToken.BEGIN_ARRAY -> {
                val result = JsonArray()
                var entries = 0
                reader.beginArray()
                while (reader.hasNext()) {
                    if (++entries > MAX_CONTAINER_ENTRIES) throw IllegalArgumentException()
                    result.add(readElement(reader, depth + 1))
                }
                reader.endArray()
                result
            }

            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> {
                reader.nextNull()
                JsonNull.INSTANCE
            }

            else -> throw IllegalArgumentException()
        }
    }
}
