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
                    maxNumTokens = manifest.contextTokens,
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
        )
    }
}

private class LiteRtRuntimeEngine(
    private val delegate: Engine,
    override val backend: InferenceBackend,
    private val maxOutputTokens: Int,
) : RuntimeEngine {
    private val closed = AtomicBoolean(false)

    override fun createConversation(
        tools: List<LlmToolDefinition>,
    ): RuntimeConversation {
        if (closed.get() || !delegate.isInitialized()) throw RuntimeDriverException()
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

private fun Message.toRuntimeChunk(): RuntimeChunk = RuntimeChunk(
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

private object GsonHolder {
    val instance = Gson()
}
