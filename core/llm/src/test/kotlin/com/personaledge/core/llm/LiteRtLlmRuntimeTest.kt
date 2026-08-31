package com.personaledge.core.llm

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtLlmRuntimeTest {
    @Test
    fun thoughtDeltasAreEmittedSeparatelyBeforeVisibleText() = runBlocking {
        val conversation = FakeConversation(
            streams = ArrayDeque(
                listOf(
                    flowOf(
                        RuntimeChunk(
                            thoughtDeltas = listOf("요청의 핵심을 확인합니다."),
                            textDeltas = listOf("핵심 답변입니다."),
                        ),
                    ),
                ),
            ),
        )
        val runtime = createRuntime(
            factory = FakeEngineFactory(FakeEngine { conversation }),
            lease = FakeLease(),
        )

        try {
            runtime.initialize(verifiedModel())
            val turnId = TurnId("thought-stream")

            assertEquals(
                listOf(
                    ModelEvent.ThoughtDelta(turnId, "요청의 핵심을 확인합니다."),
                    ModelEvent.TextDelta(turnId, "핵심 답변입니다."),
                    ModelEvent.Completed(turnId),
                ),
                runtime.streamUserTurn(turnId, "질문").toList(),
            )
        } finally {
            runtime.close()
        }
    }

    @Test
    fun oversizedThoughtDeltaFailsBeforeRawReasoningIsExposed() = runBlocking {
        val conversation = FakeConversation(
            streams = ArrayDeque(
                listOf(
                    flowOf(
                        RuntimeChunk(thoughtDeltas = listOf("x".repeat(1_000_001))),
                    ),
                ),
            ),
        )
        val engine = FakeEngine { conversation }
        val runtime = createRuntime(
            factory = FakeEngineFactory(engine),
            lease = FakeLease(),
        )

        try {
            runtime.initialize(verifiedModel())
            val turnId = TurnId("oversized-thought")

            assertEquals(
                listOf(ModelEvent.Failure(turnId, LlmFailureCode.INVALID_TOOL_CALL)),
                runtime.streamUserTurn(turnId, "질문").toList(),
            )
            assertEquals(1, conversation.cancelCount)
            assertEquals(1, conversation.closeCount)
            assertEquals(2, engine.createConversationCount)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun perTurnToolScopeRecreatesConversationWithOnlyValidatedSubset() = runBlocking {
        val initial = FakeConversation(ArrayDeque())
        val scoped = FakeConversation(
            ArrayDeque(listOf(flowOf(RuntimeChunk(textDeltas = listOf("완료"))))),
        )
        val conversations = ArrayDeque(listOf(initial, scoped))
        val engine = FakeEngine { conversations.removeFirst() }
        val runtime = createRuntime(factory = FakeEngineFactory(engine), lease = FakeLease())
        val lookup = LlmToolDefinition(
            name = "lookup",
            description = "Read a local record",
            parametersJsonSchema = "{\"type\":\"object\",\"properties\":{}}",
        )
        val write = LlmToolDefinition(
            name = "write_record",
            description = "Write a local record",
            parametersJsonSchema = "{\"type\":\"object\",\"properties\":{}}",
        )

        try {
            runtime.initialize(verifiedModel(), tools = listOf(lookup, write))
            val events = runtime.streamUserTurn(
                turnId = TurnId("scoped-turn"),
                prompt = "조회",
                maxOutputTokens = 256,
                toolScope = LlmTurnToolScope.exact(setOf("lookup")),
            ).toList()

            assertEquals(
                listOf(setOf("lookup", "write_record"), setOf("lookup")),
                engine.toolNameSnapshots,
            )
            assertEquals(
                listOf(
                    ModelEvent.TextDelta(TurnId("scoped-turn"), "완료"),
                    ModelEvent.Completed(TurnId("scoped-turn")),
                ),
                events,
            )
            assertEquals(1, initial.closeCount)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun unknownPerTurnToolScopeFailsBeforeNativeConversationReplacement() = runBlocking {
        val initial = FakeConversation(ArrayDeque())
        val engine = FakeEngine { initial }
        val runtime = createRuntime(factory = FakeEngineFactory(engine), lease = FakeLease())
        val lookup = LlmToolDefinition(
            name = "lookup",
            description = "Read a local record",
            parametersJsonSchema = "{\"type\":\"object\",\"properties\":{}}",
        )

        try {
            runtime.initialize(verifiedModel(), tools = listOf(lookup))
            val events = runtime.streamUserTurn(
                turnId = TurnId("unknown-scope"),
                prompt = "조회",
                maxOutputTokens = 256,
                toolScope = LlmTurnToolScope.exact(setOf("unknown_tool")),
            ).toList()

            assertEquals(
                listOf(
                    ModelEvent.Failure(
                        TurnId("unknown-scope"),
                        LlmFailureCode.INVALID_TOOL_DEFINITION,
                    ),
                ),
                events,
            )
            assertEquals(1, engine.createConversationCount)
            assertTrue(initial.inputs.isEmpty())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun explicitOutputBudgetRecreatesConversationWithNativeLimit() = runBlocking {
        val initial = FakeConversation(ArrayDeque())
        val limited = FakeConversation(
            streams = ArrayDeque(listOf(flowOf(RuntimeChunk(textDeltas = listOf("짧은 답"))))),
        )
        val conversations = ArrayDeque(listOf(initial, limited))
        val engine = FakeEngine { conversations.removeFirst() }
        val runtime = createRuntime(FakeEngineFactory(engine), FakeLease())

        try {
            runtime.initialize(verifiedModel())
            val turnId = TurnId("limited-turn")
            assertEquals(
                listOf(ModelEvent.TextDelta(turnId, "짧은 답"), ModelEvent.Completed(turnId)),
                runtime.streamUserTurn(turnId, "다음 알람", maxOutputTokens = 256).toList(),
            )
            assertEquals(
                listOf(PinnedModelManifest.value.maxOutputTokens, 256),
                engine.outputTokenLimits,
            )
            assertEquals(1, initial.closeCount)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun finalToolCallsAreHeldUntilDoneAndSameNameResponsesAreInjectedExactlyOnce() = runBlocking {
        val conversation = FakeConversation(
            streams = ArrayDeque(
                listOf(
                    flowOf(
                        RuntimeChunk(textDeltas = listOf("준비")),
                        RuntimeChunk(
                            toolCalls = listOf(
                                RuntimeToolCall("lookup", "{\"query\":\"first\"}"),
                                RuntimeToolCall("lookup", "{\"query\":\"second\"}"),
                            ),
                        ),
                    ),
                    flowOf(RuntimeChunk(textDeltas = listOf("완료"))),
                ),
            ),
        )
        val engine = FakeEngine { conversation }
        val lease = FakeLease()
        val runtime = createRuntime(
            factory = FakeEngineFactory(engine = engine),
            lease = lease,
        )

        try {
            runtime.initialize(
                model = verifiedModel(),
                tools = listOf(
                    LlmToolDefinition(
                        name = "lookup",
                        description = "Look up a local record",
                        parametersJsonSchema =
                            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}",
                    ),
                ),
            )
            val turnId = TurnId("turn-1")
            val firstEvents = runtime.streamUserTurn(turnId, "찾아줘").toList()

            assertEquals(3, firstEvents.size)
            assertEquals("준비", (firstEvents[0] as ModelEvent.TextDelta).text)
            val finalCalls = (firstEvents[1] as ModelEvent.FinalToolCalls).toolCalls
            assertEquals(listOf("call-1", "call-2"), finalCalls.map { it.id })
            assertEquals(listOf("lookup", "lookup"), finalCalls.map { it.name })
            assertEquals(ModelEvent.Completed(turnId), firstEvents[2])
            assertEquals(1, conversation.inputs.size)

            val rejected = runtime.streamToolResponses(
                turnId,
                listOf(
                    TrustedToolResponse("call-1", "wrong_name", "{\"value\":1}"),
                    TrustedToolResponse("call-2", "lookup", "{\"value\":2}"),
                ),
            ).toList()
            assertEquals(
                listOf(ModelEvent.Failure(turnId, LlmFailureCode.INVALID_TOOL_RESPONSE)),
                rejected,
            )
            assertEquals(1, conversation.inputs.size)

            val accepted = runtime.streamToolResponses(
                turnId,
                listOf(
                    TrustedToolResponse("call-2", "lookup", "{\"value\":2}"),
                    TrustedToolResponse("call-1", "lookup", "{\"value\":1}"),
                ),
            ).toList()
            assertEquals(
                listOf(
                    ModelEvent.TextDelta(turnId, "완료"),
                    ModelEvent.Completed(turnId),
                ),
                accepted,
            )
            val injected = conversation.inputs[1] as RuntimeTurnInput.ToolResponses
            assertEquals(
                listOf(
                    RuntimeToolResponse("lookup", "{\"value\":1}"),
                    RuntimeToolResponse("lookup", "{\"value\":2}"),
                ),
                injected.responses,
            )

            val replay = runtime.streamToolResponses(
                turnId,
                listOf(TrustedToolResponse("call-1", "lookup", "{\"value\":1}")),
            ).toList()
            assertEquals(
                listOf(ModelEvent.Failure(turnId, LlmFailureCode.NO_PENDING_TOOL_CALLS)),
                replay,
            )
            assertEquals(2, conversation.inputs.size)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun cancelCallsNativeCancelThenDiscardsAndRecreatesConversation() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val first = FakeConversation(
            streams = ArrayDeque(
                listOf(
                    flow {
                        started.complete(Unit)
                        awaitCancellation()
                    },
                ),
            ),
        )
        val replacement = FakeConversation(ArrayDeque())
        val conversations = ArrayDeque(listOf(first, replacement))
        val engine = FakeEngine { conversations.removeFirst() }
        val runtime = createRuntime(factory = FakeEngineFactory(engine), lease = FakeLease())

        try {
            runtime.initialize(verifiedModel())
            val turnId = TurnId("cancel-turn")
            val collector = launch {
                runtime.streamUserTurn(turnId, "긴 요청").collect()
            }
            withTimeout(2_000) { started.await() }

            runtime.cancel(turnId)
            withTimeout(2_000) { collector.join() }

            assertEquals(1, first.cancelCount)
            assertEquals(1, first.closeCount)
            assertEquals(2, engine.createConversationCount)
            assertEquals(LlmState.Ready(InferenceBackend.CPU), runtime.state.value)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun gpuInitializationFallsBackToCpuWithoutChangingRequestedApi() = runBlocking {
        val engine = FakeEngine { FakeConversation(ArrayDeque()) }
        val factory = FakeEngineFactory(
            engine = engine,
            failingBackends = setOf(InferenceBackend.GPU),
        )
        val lease = FakeLease()
        val cache = FakeCacheDirectory()
        val runtime = createRuntime(factory, lease, cache)

        try {
            runtime.initialize(verifiedModel(), backend = InferenceBackend.GPU)

            assertEquals(
                listOf(InferenceBackend.GPU, InferenceBackend.CPU),
                factory.attemptedBackends,
            )
            assertEquals(3, lease.revalidationCount)
            assertEquals(3, cache.revalidationCount)
            assertEquals(LlmState.Ready(InferenceBackend.CPU), runtime.state.value)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun cancellationAfterGpuFailurePreventsCpuFallbackInitialization() = runBlocking {
        val gpuEntered = CountDownLatch(1)
        val releaseGpu = CountDownLatch(1)
        val engine = FakeEngine { FakeConversation(ArrayDeque()) }
        val factory = FakeEngineFactory(
            engine = engine,
            failingBackends = setOf(InferenceBackend.GPU),
            beforeCreate = { backend ->
                if (backend == InferenceBackend.GPU) {
                    gpuEntered.countDown()
                    check(releaseGpu.await(2, TimeUnit.SECONDS))
                }
            },
        )
        val lease = FakeLease()
        val runtime = createRuntime(factory, lease)

        try {
            val initialization = launch(start = CoroutineStart.UNDISPATCHED) {
                runtime.initialize(verifiedModel(), backend = InferenceBackend.GPU)
            }
            check(gpuEntered.await(2, TimeUnit.SECONDS))

            initialization.cancel()
            releaseGpu.countDown()
            withTimeout(2_000) { initialization.join() }

            assertEquals(listOf(InferenceBackend.GPU), factory.attemptedBackends)
            assertEquals(LlmState.Off, runtime.state.value)
            assertEquals(1, lease.closeCount)
        } finally {
            releaseGpu.countDown()
            runtime.close()
        }
    }

    @Test
    fun inconsistentNonEmptyToolCallChunksFailClosedWithoutExposure() = runBlocking {
        val conversation = FakeConversation(
            streams = ArrayDeque(
                listOf(
                    flowOf(
                        RuntimeChunk(
                            toolCalls = listOf(RuntimeToolCall("lookup", "{\"query\":\"first\"}")),
                        ),
                        RuntimeChunk(
                            toolCalls = listOf(RuntimeToolCall("lookup", "{\"query\":\"changed\"}")),
                        ),
                    ),
                ),
            ),
        )
        val engine = FakeEngine { conversation }
        val runtime = createRuntime(FakeEngineFactory(engine), FakeLease())

        try {
            runtime.initialize(
                verifiedModel(),
                tools = listOf(
                    LlmToolDefinition(
                        name = "lookup",
                        description = "Look up a local record",
                        parametersJsonSchema = "{\"type\":\"object\"}",
                    ),
                ),
            )

            val turnId = TurnId("inconsistent-calls")
            val events = runtime.streamUserTurn(turnId, "찾아줘").toList()

            assertEquals(
                listOf(ModelEvent.Failure(turnId, LlmFailureCode.INVALID_TOOL_CALL)),
                events,
            )
            assertEquals(1, conversation.cancelCount)
            assertEquals(1, conversation.closeCount)
            assertEquals(2, engine.createConversationCount)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun modelEntryChangingDuringNativeInitializeClosesEngineAndRejectsHandle() = runBlocking {
        val engine = FakeEngine { FakeConversation(ArrayDeque()) }
        val lease = FakeLease(
            paths = ArrayDeque(
                listOf(
                    "/app/no_backup/model-a.litertlm",
                    "/app/no_backup/model-b.litertlm",
                ),
            ),
        )
        val runtime = createRuntime(FakeEngineFactory(engine), lease)

        try {
            val error = try {
                runtime.initialize(verifiedModel())
                null
            } catch (caught: LlmRuntimeException) {
                caught
            }

            assertEquals(LlmFailureCode.MODEL_REJECTED, error?.code)
            assertEquals(LlmState.Failed(LlmFailureCode.MODEL_REJECTED), runtime.state.value)
            assertEquals(1, engine.closeCount)
            assertEquals(0, engine.createConversationCount)
            assertEquals(1, lease.closeCount)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun duplicateSchemaKeysFailWithTypedErrorBeforeOpeningModel() = runBlocking {
        val engine = FakeEngine { FakeConversation(ArrayDeque()) }
        val lease = FakeLease()
        val runtime = createRuntime(FakeEngineFactory(engine), lease)

        try {
            val error = try {
                runtime.initialize(
                    verifiedModel(),
                    tools = listOf(
                        LlmToolDefinition(
                            name = "lookup",
                            description = "Look up a local record",
                            parametersJsonSchema = "{\"type\":\"object\",\"type\":\"object\"}",
                        ),
                    ),
                )
                null
            } catch (caught: LlmRuntimeException) {
                caught
            }

            assertEquals(LlmFailureCode.INVALID_TOOL_DEFINITION, error?.code)
            assertEquals(LlmState.Failed(LlmFailureCode.INVALID_TOOL_DEFINITION), runtime.state.value)
            assertEquals(0, lease.revalidationCount)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun laterTopLevelUserTurnRecreatesConversationInsteadOfAccumulatingKvHistory() = runBlocking {
        val first = FakeConversation(
            streams = ArrayDeque(listOf(flowOf(RuntimeChunk(textDeltas = listOf("first"))))),
        )
        val second = FakeConversation(
            streams = ArrayDeque(listOf(flowOf(RuntimeChunk(textDeltas = listOf("second"))))),
        )
        val conversations = ArrayDeque(listOf(first, second))
        val engine = FakeEngine { conversations.removeFirst() }
        val runtime = createRuntime(FakeEngineFactory(engine), FakeLease())

        try {
            runtime.initialize(verifiedModel())

            assertEquals(
                listOf(
                    ModelEvent.TextDelta(TurnId("turn-a"), "first"),
                    ModelEvent.Completed(TurnId("turn-a")),
                ),
                runtime.streamUserTurn(TurnId("turn-a"), "first prompt").toList(),
            )
            assertEquals(
                listOf(
                    ModelEvent.TextDelta(TurnId("turn-b"), "second"),
                    ModelEvent.Completed(TurnId("turn-b")),
                ),
                runtime.streamUserTurn(TurnId("turn-b"), "second prompt").toList(),
            )

            assertEquals(1, first.closeCount)
            assertEquals(1, first.inputs.size)
            assertEquals(1, second.inputs.size)
            assertEquals(2, engine.createConversationCount)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun toolResponseIsRejectedBeforeNativeCallWhenFinalOutputReserveIsUnavailable() = runBlocking {
        val first = FakeConversation(
            streams = ArrayDeque(
                listOf(
                    flowOf(
                        RuntimeChunk(
                            toolCalls = listOf(
                                RuntimeToolCall("lookup", "{\"query\":\"first\"}"),
                            ),
                        ),
                    ),
                ),
            ),
            tokenCount = 3_000,
        )
        val replacement = FakeConversation(ArrayDeque())
        val conversations = ArrayDeque(listOf(first, replacement))
        val engine = FakeEngine { conversations.removeFirst() }
        val runtime = createRuntime(FakeEngineFactory(engine), FakeLease())

        try {
            runtime.initialize(
                verifiedModel(),
                tools = listOf(
                    LlmToolDefinition(
                        name = "lookup",
                        description = "Look up a local record",
                        parametersJsonSchema = "{\"type\":\"object\"}",
                    ),
                ),
            )
            val turnId = TurnId("context-budget")
            val calls = (
                runtime.streamUserTurn(turnId, "look up").toList()[0]
                    as ModelEvent.FinalToolCalls
                ).toolCalls

            assertEquals(
                listOf(ModelEvent.Failure(turnId, LlmFailureCode.CONTEXT_BUDGET_EXCEEDED)),
                runtime.streamToolResponses(
                    turnId,
                    listOf(
                        TrustedToolResponse(
                            callId = calls.single().id,
                            name = "lookup",
                            payloadJson = "{\"value\":1}",
                        ),
                    ),
                ).toList(),
            )
            assertEquals(1, first.inputs.size)
            assertEquals(1, first.closeCount)
            assertEquals(2, engine.createConversationCount)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun oversizedCanonicalToolResponseIsTerminalBeforeNativeReinjection() = runBlocking {
        val first = FakeConversation(
            streams = ArrayDeque(
                listOf(
                    flowOf(
                        RuntimeChunk(
                            toolCalls = listOf(
                                RuntimeToolCall("lookup", "{\"query\":\"first\"}"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val replacement = FakeConversation(ArrayDeque())
        val conversations = ArrayDeque(listOf(first, replacement))
        val engine = FakeEngine { conversations.removeFirst() }
        val runtime = createRuntime(FakeEngineFactory(engine), FakeLease())

        try {
            runtime.initialize(
                verifiedModel(),
                tools = listOf(
                    LlmToolDefinition(
                        name = "lookup",
                        description = "Look up a local record",
                        parametersJsonSchema = "{\"type\":\"object\"}",
                    ),
                ),
            )
            val turnId = TurnId("oversized-tool-response")
            val call = (
                runtime.streamUserTurn(turnId, "look up").toList()[0]
                    as ModelEvent.FinalToolCalls
                ).toolCalls.single()
            val oversized = TrustedToolResponse(
                callId = call.id,
                name = call.name,
                payloadJson =
                    "{\"value\":\"${"x".repeat(
                        TrustedToolResponseBudget.MAX_TOTAL_PAYLOAD_UTF8_BYTES,
                    )}\"}",
            )

            assertEquals(
                listOf(ModelEvent.Failure(turnId, LlmFailureCode.CONTEXT_BUDGET_EXCEEDED)),
                runtime.streamToolResponses(turnId, listOf(oversized)).toList(),
            )
            assertEquals(1, first.inputs.size)
            assertEquals(1, first.closeCount)
            assertEquals(2, engine.createConversationCount)

            assertEquals(
                listOf(ModelEvent.Failure(turnId, LlmFailureCode.NO_PENDING_TOOL_CALLS)),
                runtime.streamToolResponses(turnId, listOf(oversized)).toList(),
            )
            assertEquals(1, first.inputs.size)
            assertEquals(1, first.closeCount)
            assertEquals(2, engine.createConversationCount)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun toolResponseReserveUsesTheActiveBoundedOutputLimit() = runBlocking {
        val manifest = PinnedModelManifest.value
        val boundedOutputTokens = minOf(384, manifest.maxOutputTokens - 128)
        val tokenCountPastFullOutputReserve =
            manifest.contextTokens -
                manifest.maxOutputTokens -
                TrustedToolResponseBudget.RESERVED_CONTEXT_TOKENS +
                1
        require(boundedOutputTokens > 0)
        require(
            tokenCountPastFullOutputReserve <=
                manifest.contextTokens -
                    boundedOutputTokens -
                    TrustedToolResponseBudget.RESERVED_CONTEXT_TOKENS,
        )
        val initial = FakeConversation(ArrayDeque())
        val bounded = FakeConversation(
            streams = ArrayDeque(
                listOf(
                    flowOf(
                        RuntimeChunk(
                            toolCalls = listOf(
                                RuntimeToolCall("lookup", "{\"query\":\"first\"}"),
                            ),
                        ),
                    ),
                    flowOf(RuntimeChunk(textDeltas = listOf("완료"))),
                ),
            ),
            tokenCount = tokenCountPastFullOutputReserve,
        )
        val conversations = ArrayDeque(listOf(initial, bounded))
        val engine = FakeEngine { conversations.removeFirst() }
        val runtime = createRuntime(FakeEngineFactory(engine), FakeLease())

        try {
            runtime.initialize(
                verifiedModel(),
                tools = listOf(
                    LlmToolDefinition(
                        name = "lookup",
                        description = "Look up a local record",
                        parametersJsonSchema = "{\"type\":\"object\"}",
                    ),
                ),
            )
            val turnId = TurnId("bounded-context-budget")
            val calls = (
                runtime.streamUserTurn(
                    turnId,
                    "look up",
                    maxOutputTokens = boundedOutputTokens,
                ).toList()[0] as ModelEvent.FinalToolCalls
                ).toolCalls

            assertEquals(
                listOf(ModelEvent.TextDelta(turnId, "완료"), ModelEvent.Completed(turnId)),
                runtime.streamToolResponses(
                    turnId,
                    listOf(
                        TrustedToolResponse(
                            callId = calls.single().id,
                            name = "lookup",
                            payloadJson = "{\"value\":1}",
                        ),
                    ),
                ).toList(),
            )
            assertEquals(
                listOf(manifest.maxOutputTokens, boundedOutputTokens),
                engine.outputTokenLimits,
            )
            assertEquals(2, bounded.inputs.size)
        } finally {
            runtime.close()
        }
    }

    private fun createRuntime(
        factory: RuntimeEngineFactory,
        lease: FakeLease,
        cache: FakeCacheDirectory = FakeCacheDirectory(),
    ): LiteRtLlmRuntime {
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        var nextCallId = 0
        return LiteRtLlmRuntime(
            engineFactory = factory,
            modelLeaseOpener = RuntimeModelLeaseOpener { lease },
            cacheDirectoryProvider = cache,
            callIdFactory = CallIdFactory { "call-${++nextCallId}" },
            cpuThreadCount = null,
            executor = executor,
        )
    }

    private class FakeCacheDirectory : RuntimeCacheDirectoryProvider {
        var revalidationCount = 0

        override fun getOrCreateOpaquePath(): String {
            revalidationCount += 1
            return "/app/no_backup/litertlm-cache"
        }
    }

    private fun verifiedModel(): VerifiedInstalledModel = VerifiedInstalledModel(
        file = File("opaque-test-model.litertlm"),
        inode = 1L,
        manifest = PinnedModelManifest.value,
    )

    private class FakeLease(
        private val paths: ArrayDeque<String> = ArrayDeque(),
    ) : RuntimeModelLease {
        var revalidationCount = 0
        var closeCount = 0

        override fun revalidateAndGetOpaquePath(): String {
            revalidationCount += 1
            return if (paths.isEmpty()) {
                "/app/no_backup/model.litertlm"
            } else {
                paths.removeFirst()
            }
        }

        override fun close() {
            closeCount += 1
        }
    }

    private class FakeEngineFactory(
        private val engine: FakeEngine,
        private val failingBackends: Set<InferenceBackend> = emptySet(),
        private val beforeCreate: (InferenceBackend) -> Unit = {},
    ) : RuntimeEngineFactory {
        val attemptedBackends = mutableListOf<InferenceBackend>()

        override fun create(
            modelPath: String,
            cacheDir: String,
            manifest: ModelManifest,
            backend: InferenceBackend,
            cpuThreadCount: Int?,
        ): RuntimeEngine {
            attemptedBackends += backend
            beforeCreate(backend)
            if (backend in failingBackends) throw RuntimeDriverException()
            engine.selectedBackend = backend
            return engine
        }
    }

    private class FakeEngine(
        private val conversationFactory: () -> FakeConversation,
    ) : RuntimeEngine {
        override val backend: InferenceBackend
            get() = selectedBackend

        var selectedBackend = InferenceBackend.CPU
        var createConversationCount = 0
        val outputTokenLimits = mutableListOf<Int>()
        val toolNameSnapshots = mutableListOf<Set<String>>()
        var closeCount = 0

        override fun createConversation(
            tools: List<LlmToolDefinition>,
            maxOutputTokens: Int,
        ): RuntimeConversation {
            createConversationCount += 1
            outputTokenLimits += maxOutputTokens
            toolNameSnapshots += tools.mapTo(linkedSetOf()) { definition -> definition.name }
            return conversationFactory()
        }

        override fun close() {
            closeCount += 1
        }
    }

    private class FakeConversation(
        private val streams: ArrayDeque<Flow<RuntimeChunk>>,
        private val tokenCount: Int = 0,
    ) : RuntimeConversation {
        val inputs = mutableListOf<RuntimeTurnInput>()
        var cancelCount = 0
        var closeCount = 0

        override fun stream(input: RuntimeTurnInput): Flow<RuntimeChunk> {
            inputs += input
            return streams.removeFirst()
        }

        override fun getTokenCount(): Int = tokenCount

        override fun cancelProcess() {
            cancelCount += 1
        }

        override fun close() {
            closeCount += 1
        }
    }
}
