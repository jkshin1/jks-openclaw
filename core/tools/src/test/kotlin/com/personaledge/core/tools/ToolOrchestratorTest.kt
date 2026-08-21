package com.personaledge.core.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolOrchestratorTest {
    private data class MessageParams(var text: String) : ToolParams

    private class InMemoryLedger : ActionLedger {
        private val claims = mutableSetOf<String>()

        override suspend fun claim(idempotencyKey: String): Boolean = claims.add(idempotencyKey)
    }

    private class FakePersistentLedger : PersistentActionLedger() {
        private val claims = mutableSetOf<String>()

        override suspend fun claim(idempotencyKey: String): Boolean = claims.add(idempotencyKey)
    }

    private class RecordingMessageTool : AgentTool<MessageParams, String> {
        override val descriptor = ToolDescriptor(
            name = "send_message",
            description = "Send a message",
            risk = ToolRisk.COMMUNICATION,
        )
        var executions = 0

        override suspend fun validateAndCanonicalize(params: MessageParams): ValidationResult =
            ValidationResult.Valid(params.text.trim())

        override fun preview(input: CanonicalToolInput): ActionPreview =
            ActionPreview("메시지 전송", input.encoded)

        override suspend fun execute(
            input: CanonicalToolInput,
            permit: ExecutionPermit,
        ): String {
            executions += 1
            return input.encoded
        }
    }

    private class AliasingMessageTool : AgentTool<MessageParams, String> {
        override val descriptor = ToolDescriptor(
            name = "aliasing_message",
            description = "Exercise an adversarial mutable validation result",
            risk = ToolRisk.COMMUNICATION,
        )
        var executions = 0

        override suspend fun validateAndCanonicalize(params: MessageParams): ValidationResult =
            ValidationResult.Valid(params.text)

        override fun preview(input: CanonicalToolInput): ActionPreview =
            ActionPreview("메시지 전송", input.encoded)

        override suspend fun execute(
            input: CanonicalToolInput,
            permit: ExecutionPermit,
        ): String {
            executions += 1
            return input.encoded
        }
    }

    @Test
    fun `communication cannot execute without action-bound confirmation`() = runBlocking {
        val tool = RecordingMessageTool()
        val denyingOrchestrator = ToolOrchestrator(
            actionLedger = FakePersistentLedger(),
            clock = { 1_000 },
            idFactory = { "action-1" },
        )
        val deniedAction = denyingOrchestrator.prepare(
            tool = tool,
            params = MessageParams("  도착했어.  "),
            requestId = "request-1",
        ) as PreparationResult.Ready

        assertThrows(IllegalStateException::class.java) {
            runBlocking { denyingOrchestrator.execute(deniedAction.action) }
        }
        assertEquals(0, tool.executions)

        val approvingOrchestrator = ToolOrchestrator(
            actionLedger = FakePersistentLedger(),
            userConfirmationGate = UserConfirmationGate { challenge ->
                challenge.preview.summary == "도착했어."
            },
            clock = { 1_000 },
            idFactory = { "action-1-approved" },
        )
        val approvedAction = approvingOrchestrator.prepare(
            tool = tool,
            params = MessageParams("  도착했어.  "),
            requestId = "request-1",
        ) as PreparationResult.Ready
        val result = approvingOrchestrator.execute(approvedAction.action)
        assertEquals("도착했어.", result)
        assertEquals(1, tool.executions)
    }

    @Test
    fun `mutable parameters cannot change the canonical snapshot during confirmation`() = runBlocking {
        val tool = AliasingMessageTool()
        val params = MessageParams("original")
        val orchestrator = ToolOrchestrator(
            actionLedger = FakePersistentLedger(),
            userConfirmationGate = UserConfirmationGate {
                params.text = "tampered"
                true
            },
            clock = { 1_000 },
            idFactory = { "action-mutation" },
        )
        val prepared = orchestrator.prepare(
            tool = tool,
            params = params,
            requestId = "request-mutation",
        ) as PreparationResult.Ready

        assertEquals("original", orchestrator.execute(prepared.action))
        assertEquals("tampered", params.text)
        assertEquals(1, tool.executions)
    }

    @Test
    fun `action cannot be moved to an orchestrator with a different gate`() = runBlocking {
        val tool = RecordingMessageTool()
        val source = ToolOrchestrator(
            actionLedger = FakePersistentLedger(),
            clock = { 1_000 },
            idFactory = { "action-source" },
        )
        val prepared = source.prepare(
            tool = tool,
            params = MessageParams("hello"),
            requestId = "request-source",
        ) as PreparationResult.Ready
        val permissiveOther = ToolOrchestrator(
            actionLedger = FakePersistentLedger(),
            userConfirmationGate = UserConfirmationGate { true },
            clock = { 1_000 },
            idFactory = { "action-other" },
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { permissiveOther.execute(prepared.action) }
        }
        assertEquals(0, tool.executions)
    }

    @Test
    fun `prepared action is single use`() = runBlocking {
        val tool = RecordingMessageTool()
        val orchestrator = ToolOrchestrator(
            actionLedger = FakePersistentLedger(),
            userConfirmationGate = UserConfirmationGate { true },
            clock = { 1_000 },
            idFactory = { "action-2" },
        )
        val prepared = orchestrator.prepare(
            tool = tool,
            params = MessageParams("hello"),
            requestId = "request-2",
        ) as PreparationResult.Ready

        orchestrator.execute(prepared.action)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { orchestrator.execute(prepared.action) }
        }
        assertEquals(1, tool.executions)
    }

    @Test
    fun `side effect preparation rejects a process-local ledger`() = runBlocking {
        val result = ToolOrchestrator(
            actionLedger = InMemoryLedger(),
        ).prepare(
            tool = RecordingMessageTool(),
            params = MessageParams("hello"),
            requestId = "request-local-ledger",
        )

        assertEquals(
            "Side-effecting tools require a process-persistent action ledger.",
            (result as PreparationResult.Rejected).reason,
        )
    }

    @Test
    fun `authorization cannot outlive the prepared action`() = runBlocking {
        var now = 1_000L
        val tool = RecordingMessageTool()
        val orchestrator = ToolOrchestrator(
            actionLedger = FakePersistentLedger(),
            userConfirmationGate = UserConfirmationGate {
                now = 62_000L
                true
            },
            clock = { now },
        )
        val prepared = orchestrator.prepare(
            tool = tool,
            params = MessageParams("hello"),
            requestId = "request-expiry",
            lifetimeMillis = 60_000,
        ) as PreparationResult.Ready

        assertThrows(IllegalStateException::class.java) {
            runBlocking { orchestrator.execute(prepared.action) }
        }
        assertEquals(0, tool.executions)
    }

    @Test
    fun `ledger claim cannot outlive the prepared action`() = runBlocking {
        var now = 1_000L
        val tool = RecordingMessageTool()
        val ledger = object : PersistentActionLedger() {
            override suspend fun claim(idempotencyKey: String): Boolean {
                now = 62_000L
                return true
            }
        }
        val orchestrator = ToolOrchestrator(
            actionLedger = ledger,
            userConfirmationGate = UserConfirmationGate { true },
            clock = { now },
        )
        val prepared = orchestrator.prepare(
            tool = tool,
            params = MessageParams("hello"),
            requestId = "request-ledger-expiry",
            lifetimeMillis = 60_000,
        ) as PreparationResult.Ready

        assertThrows(IllegalStateException::class.java) {
            runBlocking { orchestrator.execute(prepared.action) }
        }
        assertEquals(0, tool.executions)
    }

    @Test
    fun `shared persistent ledger rejects a replay prepared by another orchestrator`() = runBlocking {
        val ledger = FakePersistentLedger()
        val tool = RecordingMessageTool()
        val first = ToolOrchestrator(
            actionLedger = ledger,
            userConfirmationGate = UserConfirmationGate { true },
            clock = { 1_000 },
            idFactory = { "action-first" },
        )
        val second = ToolOrchestrator(
            actionLedger = ledger,
            userConfirmationGate = UserConfirmationGate { true },
            clock = { 1_000 },
            idFactory = { "action-second" },
        )
        val firstAction = first.prepare(
            tool = tool,
            params = MessageParams("hello"),
            requestId = "stable-request",
        ) as PreparationResult.Ready
        val secondAction = second.prepare(
            tool = tool,
            params = MessageParams("hello"),
            requestId = "stable-request",
        ) as PreparationResult.Ready

        first.execute(firstAction.action)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { second.execute(secondAction.action) }
        }
        assertEquals(1, tool.executions)
    }
}
