package com.personaledge.core.llm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.tool
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** The only production bridge to LiteRT-LM 0.16.1. */
internal object LiteRtEngineFactory : RuntimeEngineFactory {
    override fun create(
        modelPath: String,
        cacheDir: String,
        manifest: ModelManifest,
        backend: InferenceBackend,
        cpuThreadCount: Int?,
        mediaModalities: Set<TurnMediaKind>,
    ): RuntimeEngine {
        val nativeBackend = when (backend) {
            InferenceBackend.CPU -> Backend.CPU(threadCount = cpuThreadCount)
            InferenceBackend.GPU -> Backend.GPU()
        }
        val engine = try {
            Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = nativeBackend,
                    // Without these the engine never loads its vision or audio executor, and a
                    // media turn fails deep in native with "Vision executor should not be null"
                    // *after* the image has already been decoded and patched — a failure that
                    // looks like a bad attachment rather than an unconfigured engine.
                    //
                    // They follow what the caller enabled, not what the artifact declares. On the
                    // owner's device, loading these executors made the GPU backend unavailable
                    // for the entire engine, so leaving them on unconditionally would have made
                    // every text turn about half as fast for a default-off feature.
                    visionBackend = nativeBackend
                        .takeIf { TurnMediaKind.IMAGE in mediaModalities },
                    audioBackend = nativeBackend
                        .takeIf { TurnMediaKind.AUDIO in mediaModalities },
                    maxNumTokens = manifest.contextTokens,
                    maxNumImages = TurnMediaBudget.MAX_ATTACHMENTS_PER_TURN
                        .takeIf { TurnMediaKind.IMAGE in mediaModalities },
                    cacheDir = cacheDir,
                ),
            )
        } catch (_: Exception) {
            throw RuntimeDriverException()
        }
        try {
            engine.initialize()
        } catch (_: Exception) {
            try {
                if (engine.isInitialized()) engine.close()
            } catch (_: Exception) {
                // The failed native handle is terminal and no details leave this boundary.
            }
            throw RuntimeDriverException()
        }
        return LiteRtRuntimeEngine(
            delegate = engine,
            backend = backend,
            maxOutputTokens = manifest.maxOutputTokens,
            mediaModalities = mediaModalities,
        )
    }
}

private class LiteRtRuntimeEngine(
    private val delegate: Engine,
    override val backend: InferenceBackend,
    private val maxOutputTokens: Int,
    override val mediaModalities: Set<TurnMediaKind>,
) : RuntimeEngine {
    private val closed = AtomicBoolean(false)

    override fun createConversation(
        tools: List<LlmToolDefinition>,
        maxOutputTokens: Int,
    ): RuntimeConversation {
        if (closed.get() || !delegate.isInitialized()) throw RuntimeDriverException()
        if (maxOutputTokens !in 1..this.maxOutputTokens) throw RuntimeDriverException()
        val providers = try {
            tools.map { definition -> tool(ManualOnlyOpenApiTool(definition)) }
        } catch (_: Exception) {
            throw RuntimeDriverException()
        }
        val conversation = try {
            delegate.createConversation(
                LiteRtConversationPolicy.createConfig(
                    tools = providers,
                    maxOutputTokens = maxOutputTokens,
                ),
            )
        } catch (_: Exception) {
            throw RuntimeDriverException()
        }
        return LiteRtRuntimeConversation(conversation)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            if (delegate.isInitialized()) delegate.close()
        } catch (_: Exception) {
            throw RuntimeDriverException()
        }
    }
}

private class LiteRtRuntimeConversation(
    private val delegate: Conversation,
) : RuntimeConversation {
    private val closed = AtomicBoolean(false)

    override fun stream(input: RuntimeTurnInput): Flow<RuntimeChunk> {
        if (closed.get() || !delegate.isAlive) throw RuntimeDriverException()
        val nativeFlow = try {
            when (input) {
                is RuntimeTurnInput.User -> delegate.sendMessageAsync(input.prompt)
                is RuntimeTurnInput.UserWithMedia ->
                    delegate.sendMessageAsync(Message.user(input.toNativeContents()))
                is RuntimeTurnInput.ToolResponses -> {
                    val contents = input.responses.map { response ->
                        Content.ToolResponse(
                            name = response.name,
                            response = JsonParser.parseString(response.payloadJson),
                        )
                    }
                    delegate.sendMessageAsync(Message.tool(Contents.of(contents)))
                }
            }
        } catch (_: Exception) {
            throw RuntimeDriverException()
        }
        return nativeFlow
            .map(Message::toRuntimeChunk)
            .catch { error ->
                if (error is CancellationException) throw error
                throw RuntimeDriverException()
            }
    }

    override fun getTokenCount(): Int {
        if (closed.get() || !delegate.isAlive) throw RuntimeDriverException()
        return try {
            delegate.getTokenCount()
        } catch (_: Exception) {
            throw RuntimeDriverException()
        }
    }

    override fun cancelProcess() {
        if (closed.get() || !delegate.isAlive) throw RuntimeDriverException()
        try {
            delegate.cancelProcess()
        } catch (_: Exception) {
            throw RuntimeDriverException()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            if (delegate.isAlive) delegate.close()
        } catch (_: Exception) {
            throw RuntimeDriverException()
        }
    }
}

/**
 * Media first, then the request text.
 *
 * The artifact's own chat template renders an `image` item as `<|image|>` and an `audio` item as
 * `<|audio|>` inline, in list order, so ordering here is what the model actually sees. Putting the
 * attachment ahead of the instruction matches how the template's examples read and keeps the
 * app-authored task sentence as the last thing before generation begins.
 *
 * Bytes are copied out of the validated attachment at this boundary and handed straight to the
 * native content type; nothing here logs, hashes, or retains them.
 */
private fun RuntimeTurnInput.UserWithMedia.toNativeContents(): Contents {
    val contents = buildList {
        for (attachment in media) {
            add(
                when (attachment.kind) {
                    TurnMediaKind.IMAGE -> Content.ImageBytes(attachment.copyBytes())
                    TurnMediaKind.AUDIO -> Content.AudioBytes(attachment.copyBytes())
                },
            )
        }
        add(Content.Text(prompt))
    }
    return Contents.of(contents)
}

private class ManualOnlyOpenApiTool(
    definition: LlmToolDefinition,
) : OpenApiTool {
    private val descriptionJson: String = JsonObject().apply {
        addProperty("name", definition.name)
        addProperty("description", definition.description)
        add("parameters", JsonParser.parseString(definition.parametersJsonSchema))
    }.toString()

    override fun getToolDescriptionJsonString(): String = descriptionJson

    override fun execute(paramsJsonString: String): String {
        throw IllegalStateException("MANUAL_TOOL_EXECUTION_ONLY")
    }
}

/** Keeps model reasoning separate from user-visible content and Tool calls. */
internal fun Message.toRuntimeChunk(): RuntimeChunk = RuntimeChunk(
    thoughtDeltas = channels[THOUGHT_CHANNEL]
        ?.takeIf(String::isNotEmpty)
        ?.let(::listOf)
        .orEmpty(),
    textDeltas = contents.contents.mapNotNull { content ->
        (content as? Content.Text)?.text
    },
    toolCalls = toolCalls.map { call ->
        RuntimeToolCall(
            name = call.name,
            argumentsJson = GsonHolder.instance.toJson(call.arguments),
        )
    },
)

private const val THOUGHT_CHANNEL = "thought"

private object GsonHolder {
    val instance = Gson()
}
