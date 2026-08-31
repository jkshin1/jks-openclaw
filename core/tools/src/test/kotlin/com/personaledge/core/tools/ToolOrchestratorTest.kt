package com.personaledge.core.tools

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

        override suspend fun recordState(
            idempotencyKey: String,
            state: ActionExecutionState,
        ): Boolean = idempotencyKey in claims
    }

    private class StateRecordingPersistentLedger : PersistentActionLedger() {
        val states = mutableMapOf<String, ActionExecutionState>()
        var beforeTerminalRecord: suspend () -> Unit = {}

        override suspend fun claim(idempotencyKey: String): Boolean {
            if (idempotencyKey in states) return false
            states[idempotencyKey] = ActionExecutionState.CLAIMED
            return true
        }

        override suspend fun recordState(
            idempotencyKey: String,
            state: ActionExecutionState,
        ): Boolean {
            beforeTerminalRecord()
            if (states[idempotencyKey] != ActionExecutionState.CLAIMED) return false
            states[idempotencyKey] = state
            return true
        }
    }

    private class CountingPersistentLedger(
        private val onClaim: () -> Unit = {},
    ) : PersistentActionLedger() {
        var claimCount = 0

        override suspend fun claim(idempotencyKey: String): Boolean {
            onClaim()
            claimCount += 1
            return true
        }

        override suspend fun recordState(
            idempotencyKey: String,
            state: ActionExecutionState,
        ): Boolean = true
    }

    private class RecordingMessageTool(
        private val outcome: ToolExecutionOutcome = ToolExecutionOutcome.WRITE_COMPLETED,
        name: String = "send_message",
        risk: ToolRisk = ToolRisk.COMMUNICATION,
        requiredCapabilities: Set<ToolCapability> = emptySet(),
        private val onExecute: () -> Unit = {},
    ) : AgentTool<MessageParams, String> {
        override val descriptor = ToolDescriptor(
            name = name,
            description = "Send a message",
            risk = risk,
            requiredCapabilities = requiredCapabilities,
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
            onExecute()
            executions += 1
            return input.encoded
        }

        override fun executionOutcome(result: String): ToolExecutionOutcome = outcome
    }

    private class MutableDescriptorMessageTool : AgentTool<MessageParams, String> {
        var requiredCapabilities: Set<ToolCapability> = setOf(ToolCapability.NETWORK)
        var executions = 0

        override val descriptor: ToolDescriptor
            get() = ToolDescriptor(
                name = "mutable_descriptor_message",
                description = "Exercise descriptor mutation at the pre-claim boundary",
                risk = ToolRisk.COMMUNICATION,
                requiredCapabilities = requiredCapabilities,
            )

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

    private class MutablePreviewMessageTool : AgentTool<MessageParams, String> {
        var previewTitle = "메시지 전송"
        var previewCalls = 0
        var executions = 0

        override val descriptor = ToolDescriptor(
            name = "mutable_preview_message",
            description = "Exercise preview mutation at authorization boundaries",
            risk = ToolRisk.COMMUNICATION,
        )

        override suspend fun validateAndCanonicalize(params: MessageParams): ValidationResult =
            ValidationResult.Valid(params.text.trim())

        override fun preview(input: CanonicalToolInput): ActionPreview {
            previewCalls += 1
            return ActionPreview(previewTitle, input.encoded)
        }

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

    private class ThrowingTool(
        risk: ToolRisk,
        private val failure: Exception,
    ) : AgentTool<MessageParams, String> {
        override val descriptor = ToolDescriptor(
            name = "throwing_tool",
            description = "Throw after the action claim",
            risk = risk,
        )
        var executions = 0

        override suspend fun validateAndCanonicalize(params: MessageParams): ValidationResult =
            ValidationResult.Valid(params.text)

        override fun preview(input: CanonicalToolInput): ActionPreview =
            ActionPreview("실패 테스트", "실패 테스트")

        override suspend fun execute(
            input: CanonicalToolInput,
            permit: ExecutionPermit,
        ): String {
            executions += 1
            throw failure
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
        val ledger = CountingPersistentLedger()
        val orchestrator = ToolOrchestrator(
            actionLedger = ledger,
            userConfirmationGate = UserConfirmationGate {
                now = 61_000L
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
        assertEquals(0, ledger.claimCount)
        assertEquals(0, tool.executions)
    }

    @Test
    fun `ledger claim cannot outlive the prepared action`() = runBlocking {
        var now = 1_000L
        val tool = RecordingMessageTool()
        var terminalState: ActionExecutionState? = null
        val ledger = object : PersistentActionLedger() {
            override suspend fun claim(idempotencyKey: String): Boolean {
                now = 61_000L
                return true
            }

            override suspend fun recordState(
                idempotencyKey: String,
                state: ActionExecutionState,
            ): Boolean {
                terminalState = state
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
        assertEquals(ActionExecutionState.UNKNOWN_AFTER_CLAIM, terminalState)
    }

    @Test
    fun `exact expiry before authorization does not confirm claim or execute`() = runBlocking {
        var now = 1_000L
        var confirmations = 0
        val ledger = CountingPersistentLedger()
        val tool = RecordingMessageTool()
        val orchestrator = ToolOrchestrator(
            actionLedger = ledger,
            userConfirmationGate = UserConfirmationGate {
                confirmations += 1
                true
            },
            clock = { now },
        )
        val prepared = orchestrator.prepare(
            tool = tool,
            params = MessageParams("hello"),
            requestId = "exact-pre-auth-expiry",
            lifetimeMillis = 1_000L,
        ) as PreparationResult.Ready
        now = 2_000L

        assertThrows(IllegalStateException::class.java) {
            runBlocking { orchestrator.executeWithReceipt(prepared.action) }
        }

        assertEquals(0, confirmations)
        assertEquals(0, ledger.claimCount)
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

    @Test
    fun `v2 challenge identity is deterministic domain separated and replay stable`() = runBlocking {
        val capabilities = linkedSetOf(
            ToolCapability.WRITE_MEMORY,
            ToolCapability.NETWORK,
        )
        suspend fun prepared(actionId: String, requestId: String) =
            ToolOrchestrator(
                actionLedger = FakePersistentLedger(),
                executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
                clock = { 5_000L },
                idFactory = { actionId },
            ).prepare(
                tool = RecordingMessageTool(requiredCapabilities = capabilities),
                params = MessageParams("canonical payload"),
                requestId = requestId,
                lifetimeMillis = 20_000L,
            ) as PreparationResult.Ready

        val first = prepared(actionId = "action-stable", requestId = "request-stable").action
        val same = prepared(actionId = "action-stable", requestId = "request-stable").action
        val anotherAction = prepared(
            actionId = "action-other",
            requestId = "request-stable",
        ).action
        val anotherRequest = prepared(
            actionId = "action-stable",
            requestId = "request-other",
        ).action

        assertEquals(first.parameterDigest, same.parameterDigest)
        assertEquals(first.challengeDigest, same.challengeDigest)
        assertEquals(first.idempotencyKey, same.idempotencyKey)
        assertEquals(first.replayIdentityDigest, same.replayIdentityDigest)
        assertEquals(
            "4a64e29359c7d3f9be9aa5118f928b72226ff181b3123da6cea94b4ef8a1d993",
            first.parameterDigest,
        )
        assertEquals(
            "353b1e2bb78fd19c9daf1179c104404e3cf793e7da103a5939ca73ab550bcf66",
            first.idempotencyKey,
        )
        assertEquals(
            "7d38865b4826af7f4206721f98ccebaa97e8f60ac1b09de42b742c203bf6926f",
            first.replayIdentityDigest,
        )
        assertEquals(
            "b2116edf4dc82cfa5853fd8ffa025852b18f3d0abfd589d92a08d1f0777e5c56",
            first.challengeDigest,
        )
        assertEquals(
            "0c11fcd121831bb26aa273b6bb58e08ed6a109d48dd86cb1233d1d7420582cdd",
            first.previewDigest,
        )
        assertTrue(first.challengeDigest.matches(Regex("[0-9a-f]{64}")))
        assertTrue(first.idempotencyKey.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(first.challengeDigest, first.idempotencyKey)
        assertNotEquals(first.challengeDigest, first.replayIdentityDigest)
        assertNotEquals(first.challengeDigest, anotherAction.challengeDigest)
        assertEquals(first.idempotencyKey, anotherAction.idempotencyKey)
        assertEquals(first.replayIdentityDigest, anotherAction.replayIdentityDigest)
        assertNotEquals(first.challengeDigest, anotherRequest.challengeDigest)
        assertNotEquals(first.idempotencyKey, anotherRequest.idempotencyKey)
        assertNotEquals(first.replayIdentityDigest, anotherRequest.replayIdentityDigest)
    }

    @Test
    fun `v2 capability identity is independent of set iteration order`() = runBlocking {
        val firstCapabilities = linkedSetOf(
            ToolCapability.WRITE_CALENDAR,
            ToolCapability.NETWORK,
            ToolCapability.POST_NOTIFICATIONS,
        )
        val reversedCapabilities = linkedSetOf(
            ToolCapability.POST_NOTIFICATIONS,
            ToolCapability.NETWORK,
            ToolCapability.WRITE_CALENDAR,
        )
        suspend fun prepared(capabilities: Set<ToolCapability>) =
            ToolOrchestrator(
                actionLedger = FakePersistentLedger(),
                executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
                clock = { 8_000L },
                idFactory = { "ordered-action" },
            ).prepare(
                tool = RecordingMessageTool(requiredCapabilities = capabilities),
                params = MessageParams("same"),
                requestId = "ordered-request",
            ) as PreparationResult.Ready

        val first = prepared(firstCapabilities).action
        val reversed = prepared(reversedCapabilities).action

        assertEquals(first.idempotencyKey, reversed.idempotencyKey)
        assertEquals(first.replayIdentityDigest, reversed.replayIdentityDigest)
        assertEquals(first.challengeDigest, reversed.challengeDigest)
    }

    @Test
    fun `length framing prevents delimiter boundary collisions`() {
        val canonicalInput = "same"
        val canonicalDigest = ToolActionIdentityV2.canonicalInputDigest(canonicalInput)
        val previewDigest = ToolActionIdentityV2.previewDigest(ActionPreview("same", "preview"))
        val capabilities = emptySet<ToolCapability>()
        // The legacy ledger identity collapses to `a|b|c|same`; v2 still distinguishes them.
        val firstLedger = ToolActionIdentityV2.legacyLedgerIdentityDigest("a|b", "c", canonicalInput)
        val secondLedger = ToolActionIdentityV2.legacyLedgerIdentityDigest("a", "b|c", canonicalInput)
        val firstReplay = ToolActionIdentityV2.replayIdentityDigest(
            "a|b",
            "c",
            canonicalDigest,
            ToolRisk.READ_ONLY,
            capabilities,
        )
        val secondReplay = ToolActionIdentityV2.replayIdentityDigest(
            "a",
            "b|c",
            canonicalDigest,
            ToolRisk.READ_ONLY,
            capabilities,
        )
        val firstChallenge = ToolActionIdentityV2.challengeDigest(
            requestId = "a|b",
            actionId = "same-action",
            toolName = "c",
            canonicalInputDigest = canonicalDigest,
            previewDigest = previewDigest,
            risk = ToolRisk.READ_ONLY,
            requiredCapabilities = capabilities,
            expiresAtEpochMillis = 2_000L,
            replayIdentityDigest = firstReplay,
            ledgerIdentityDigest = firstLedger,
        )
        val secondChallenge = ToolActionIdentityV2.challengeDigest(
            requestId = "a",
            actionId = "same-action",
            toolName = "b|c",
            canonicalInputDigest = canonicalDigest,
            previewDigest = previewDigest,
            risk = ToolRisk.READ_ONLY,
            requiredCapabilities = capabilities,
            expiresAtEpochMillis = 2_000L,
            replayIdentityDigest = secondReplay,
            ledgerIdentityDigest = secondLedger,
        )

        assertEquals(firstLedger, secondLedger)
        assertNotEquals(firstReplay, secondReplay)
        assertNotEquals(firstChallenge, secondChallenge)
        assertNotEquals(
            ToolActionIdentityV2.previewDigest(ActionPreview("a", "bc")),
            ToolActionIdentityV2.previewDigest(ActionPreview("ab", "c")),
        )
    }

    @Test
    fun `prepare rejects unsafe legacy ledger identity components`() {
        val orchestrator = ToolOrchestrator(actionLedger = FakePersistentLedger())
        listOf(
            "contains|pipe",
            "contains whitespace",
            "contains\nnewline",
            "x".repeat(193),
        ).forEach { requestId ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    orchestrator.prepare(
                        RecordingMessageTool(),
                        MessageParams("same"),
                        requestId,
                    )
                }
            }
        }
        listOf("bad|tool", "bad-tool", "Uppercase", "a".repeat(65)).forEach { toolName ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    orchestrator.prepare(
                        RecordingMessageTool(name = toolName),
                        MessageParams("same"),
                        "safe-request",
                    )
                }
            }
        }

        val manualTurn = runBlocking {
            orchestrator.prepare(
                RecordingMessageTool(),
                MessageParams("same"),
                "turn.A_B-1:tool:10",
            )
        }
        val agentPlan = runBlocking {
            orchestrator.prepare(
                RecordingMessageTool(),
                MessageParams("same"),
                "agent-plan:123e4567-e89b-12d3-a456-426614174000:step:10000",
            )
        }
        assertTrue(manualTurn is PreparationResult.Ready)
        assertTrue(agentPlan is PreparationResult.Ready)
    }

    @Test
    fun `tampering any v2-bound prepared field fails before authorization or claim`() =
        runBlocking {
            val ledger = CountingPersistentLedger()
            val tool = RecordingMessageTool()
            var confirmations = 0
            val orchestrator = ToolOrchestrator(
                actionLedger = ledger,
                userConfirmationGate = UserConfirmationGate {
                    confirmations += 1
                    true
                },
                clock = { 1_000L },
                idFactory = { "tamper-action" },
            )
            val original = (orchestrator.prepare(
                tool = tool,
                params = MessageParams("private canonical input"),
                requestId = "tamper-request",
            ) as PreparationResult.Ready).action
            val zeroDigest = "0".repeat(64)
            val tampered = listOf(
                copyAction(original, toolName = "another_tool"),
                copyAction(
                    original,
                    preview = original.preview.copy(title = "다른 제목"),
                ),
                copyAction(
                    original,
                    preview = original.preview.copy(summary = "다른 요약"),
                ),
                copyAction(original, parameterDigest = zeroDigest),
                copyAction(original, challengeDigest = zeroDigest),
                copyAction(original, previewDigest = zeroDigest),
                copyAction(original, challengeDigest = original.idempotencyKey),
                copyAction(original, idempotencyKey = zeroDigest),
                copyAction(original, idempotencyKey = original.challengeDigest),
                copyAction(original, replayIdentityDigest = zeroDigest),
                copyAction(
                    original,
                    replayIdentityDigest = original.challengeDigest,
                ),
                copyAction(original, requestId = "another-request"),
                copyAction(original, actionId = "another-action"),
                copyAction(
                    original,
                    confirmation = ConfirmationRequirement.NotRequired,
                ),
                copyAction(original, risk = ToolRisk.DATA_WRITE),
                copyAction(
                    original,
                    requiredCapabilities = setOf(ToolCapability.NETWORK),
                ),
                copyAction(
                    original,
                    expiresAtEpochMillis = original.expiresAtEpochMillis + 1,
                ),
                copyAction(original, canonicalInput = CanonicalToolInput("tampered input")),
            )

            tampered.forEach { action ->
                assertThrows(IllegalStateException::class.java) {
                    runBlocking { orchestrator.executeWithReceipt(action) }
                }
            }

            assertEquals(0, confirmations)
            assertEquals(0, ledger.claimCount)
            assertEquals(0, tool.executions)
        }

    @Test
    fun `trusted preview is recomputed after confirmation before claim`() = runBlocking {
        val ledger = CountingPersistentLedger()
        val tool = MutablePreviewMessageTool()
        val orchestrator = ToolOrchestrator(
            actionLedger = ledger,
            userConfirmationGate = UserConfirmationGate {
                tool.previewTitle = "확인 뒤 변경된 제목"
                true
            },
            clock = { 1_000L },
        )
        val prepared = orchestrator.prepare(
            tool = tool,
            params = MessageParams("hello"),
            requestId = "preview-recompute",
        ) as PreparationResult.Ready
        assertEquals(1, tool.previewCalls)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { orchestrator.executeWithReceipt(prepared.action) }
        }

        assertEquals(3, tool.previewCalls)
        assertEquals(0, ledger.claimCount)
        assertEquals(0, tool.executions)
    }

    @Test
    fun `trusted preview is recomputed before authorization`() = runBlocking {
        val ledger = CountingPersistentLedger()
        val tool = MutablePreviewMessageTool()
        var confirmations = 0
        val orchestrator = ToolOrchestrator(
            actionLedger = ledger,
            userConfirmationGate = UserConfirmationGate {
                confirmations += 1
                true
            },
            clock = { 1_000L },
        )
        val prepared = orchestrator.prepare(
            tool = tool,
            params = MessageParams("hello"),
            requestId = "preview-pre-auth-recompute",
        ) as PreparationResult.Ready
        assertEquals(1, tool.previewCalls)
        tool.previewTitle = "실행 전 변경된 제목"

        assertThrows(IllegalStateException::class.java) {
            runBlocking { orchestrator.executeWithReceipt(prepared.action) }
        }

        assertEquals(2, tool.previewCalls)
        assertEquals(0, confirmations)
        assertEquals(0, ledger.claimCount)
        assertEquals(0, tool.executions)
    }

    @Test
    fun `claim-time recomputation catches descriptor mutation after the side effect hook`() =
        runBlocking {
            val ledger = CountingPersistentLedger()
            val tool = MutableDescriptorMessageTool()
            val orchestrator = ToolOrchestrator(
                actionLedger = ledger,
                userConfirmationGate = UserConfirmationGate { true },
                executionInterlock = ExecutionInterlock { InterlockDecision.Allow },
                clock = { 1_000L },
            )
            val prepared = orchestrator.prepare(
                tool = tool,
                params = MessageParams("hello"),
                requestId = "claim-time-tamper",
            ) as PreparationResult.Ready

            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    orchestrator.executeWithReceipt(
                        action = prepared.action,
                        beforeSideEffectExecution = { _, _ ->
                            tool.requiredCapabilities = setOf(
                                ToolCapability.NETWORK,
                                ToolCapability.WRITE_MEMORY,
                            )
                            true
                        },
                    )
                }
            }

            assertEquals(0, ledger.claimCount)
            assertEquals(0, tool.executions)
        }

    @Test
    fun `expiry after execution interlock fails before the ledger claim`() = runBlocking {
        var now = 1_000L
        val ledger = CountingPersistentLedger()
        val tool = RecordingMessageTool()
        val orchestrator = ToolOrchestrator(
            actionLedger = ledger,
            userConfirmationGate = UserConfirmationGate { true },
            executionInterlock = ExecutionInterlock { request ->
                if (request.phase == InterlockPhase.EXECUTE) now = 2_000L
                InterlockDecision.Allow
            },
            clock = { now },
        )
        val prepared = orchestrator.prepare(
            tool = tool,
            params = MessageParams("hello"),
            requestId = "pre-claim-expiry",
            lifetimeMillis = 1_000L,
        ) as PreparationResult.Ready

        assertThrows(IllegalStateException::class.java) {
            runBlocking { orchestrator.executeWithReceipt(prepared.action) }
        }

        assertEquals(0, ledger.claimCount)
        assertEquals(0, tool.executions)
    }

    @Test
    fun `authorization and ledger receive only content-free v2 digests`() = runBlocking {
        val rawCanonicalInput = "private recipient|private message"
        var claimedKey: String? = null
        var observedChallenge: ActionChallenge? = null
        val ledger = object : PersistentActionLedger() {
            override suspend fun claim(idempotencyKey: String): Boolean {
                claimedKey = idempotencyKey
                return true
            }

            override suspend fun recordState(
                idempotencyKey: String,
                state: ActionExecutionState,
            ): Boolean = true
        }
        val orchestrator = ToolOrchestrator(
            actionLedger = ledger,
            userConfirmationGate = UserConfirmationGate { challenge ->
                observedChallenge = challenge
                true
            },
            clock = { 1_000L },
            idFactory = { "content-free-action" },
        )
        val prepared = orchestrator.prepare(
            tool = RecordingMessageTool(),
            params = MessageParams(rawCanonicalInput),
            requestId = "content-free-request",
        ) as PreparationResult.Ready

        orchestrator.executeWithReceipt(prepared.action)

        assertEquals(prepared.action.idempotencyKey, claimedKey)
        assertEquals(prepared.action.challengeDigest, observedChallenge?.challengeDigest)
        assertEquals(prepared.action.parameterDigest, observedChallenge?.parameterDigest)
        assertTrue(requireNotNull(claimedKey).matches(Regex("[0-9a-f]{64}")))
        assertFalse(requireNotNull(claimedKey).contains(rawCanonicalInput))
        assertFalse(prepared.action.challengeDigest.contains(rawCanonicalInput))
    }

    @Test
    fun `side effect gate runs after authorization and execution interlock before claim`() =
        runBlocking {
            val events = mutableListOf<String>()
            val ledger = CountingPersistentLedger { events += "claim" }
            val tool = RecordingMessageTool(onExecute = { events += "execute" })
            val orchestrator = ToolOrchestrator(
                actionLedger = ledger,
                userConfirmationGate = UserConfirmationGate {
                    events += "authorize"
                    true
                },
                executionInterlock = ExecutionInterlock { request ->
                    if (request.phase == InterlockPhase.EXECUTE) events += "interlock"
                    InterlockDecision.Allow
                },
                clock = { 1_000L },
            )
            val prepared = orchestrator.prepare(
                tool = tool,
                params = MessageParams("hello"),
                requestId = "gate-order",
            ) as PreparationResult.Ready

            orchestrator.executeWithReceipt(
                action = prepared.action,
                beforeSideEffectExecution = { toolName, risk ->
                    assertEquals(tool.descriptor.name, toolName)
                    assertEquals(tool.descriptor.risk, risk)
                    events += "side-effect-gate"
                    true
                },
            )

            assertEquals(
                listOf("authorize", "interlock", "side-effect-gate", "claim", "execute"),
                events,
            )
        }

    @Test
    fun `side effect gate denial does not claim or invoke the tool`() = runBlocking {
        val ledger = CountingPersistentLedger()
        val tool = RecordingMessageTool()
        val orchestrator = ToolOrchestrator(
            actionLedger = ledger,
            userConfirmationGate = UserConfirmationGate { true },
            clock = { 1_000L },
        )
        val prepared = orchestrator.prepare(
            tool = tool,
            params = MessageParams("hello"),
            requestId = "gate-denial",
        ) as PreparationResult.Ready

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                orchestrator.executeWithReceipt(
                    action = prepared.action,
                    beforeSideEffectExecution = { _, _ -> false },
                )
            }
        }

        assertEquals(0, ledger.claimCount)
        assertEquals(0, tool.executions)
    }

    @Test
    fun `side effect gate exception does not claim or invoke the tool`() = runBlocking {
        val ledger = CountingPersistentLedger()
        val tool = RecordingMessageTool()
        val orchestrator = ToolOrchestrator(
            actionLedger = ledger,
            userConfirmationGate = UserConfirmationGate { true },
            clock = { 1_000L },
        )
        val prepared = orchestrator.prepare(
            tool = tool,
            params = MessageParams("hello"),
            requestId = "gate-exception",
        ) as PreparationResult.Ready

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                orchestrator.executeWithReceipt(
                    action = prepared.action,
                    beforeSideEffectExecution = { _, _ ->
                        throw IllegalArgumentException("closed")
                    },
                )
            }
        }

        assertEquals(0, ledger.claimCount)
        assertEquals(0, tool.executions)
    }

    @Test
    fun `read-only execution never invokes the side effect gate`() = runBlocking {
        val tool = RecordingMessageTool(
            outcome = ToolExecutionOutcome.READ_COMPLETED,
            risk = ToolRisk.READ_ONLY,
        )
        val orchestrator = ToolOrchestrator(
            actionLedger = InMemoryLedger(),
            clock = { 1_000L },
        )
        val prepared = orchestrator.prepare(
            tool = tool,
            params = MessageParams("read"),
            requestId = "read-does-not-call-gate",
        ) as PreparationResult.Ready
        var gateCalls = 0

        val receipt = orchestrator.executeWithReceipt(
            action = prepared.action,
            beforeSideEffectExecution = { _, _ ->
                gateCalls += 1
                false
            },
        )

        assertEquals(ToolExecutionOutcome.READ_COMPLETED, receipt.outcome)
        assertEquals(0, gateCalls)
        assertEquals(1, tool.executions)
    }

    @Test
    fun `cancellation after a side effect retains its terminal receipt before resuming`() =
        runBlocking {
            val terminalRecordStarted = CompletableDeferred<Unit>()
            val releaseTerminalRecord = CompletableDeferred<Unit>()
            val ledger = StateRecordingPersistentLedger().apply {
                beforeTerminalRecord = {
                    terminalRecordStarted.complete(Unit)
                    releaseTerminalRecord.await()
                }
            }
            val tool = RecordingMessageTool()
            val orchestrator = ToolOrchestrator(
                actionLedger = ledger,
                userConfirmationGate = UserConfirmationGate { true },
                clock = { 1_000L },
            )
            val prepared = orchestrator.prepare(
                tool = tool,
                params = MessageParams("hello"),
                requestId = "cancel-after-write",
            ) as PreparationResult.Ready
            val retained = AtomicReference<ToolExecutionReceipt<String>?>(null)
            val execution = launch {
                orchestrator.executeWithReceipt(
                    action = prepared.action,
                    onExecuted = retained::set,
                )
            }

            terminalRecordStarted.await()
            execution.cancel()
            releaseTerminalRecord.complete(Unit)
            execution.join()

            assertTrue(execution.isCancelled)
            assertEquals(1, tool.executions)
            assertEquals(ToolExecutionOutcome.WRITE_COMPLETED, retained.get()?.outcome)
            assertEquals(ActionExecutionState.COMPLETED, ledger.states.values.single())
            assertThrows(IllegalStateException::class.java) {
                runBlocking { orchestrator.execute(prepared.action) }
            }
            assertEquals(1, tool.executions)
        }

    @Test
    fun `a normal refused result is durably distinct from a completed write`() = runBlocking {
        val ledger = StateRecordingPersistentLedger()
        val tool = RecordingMessageTool(outcome = ToolExecutionOutcome.WRITE_REFUSED)
        val orchestrator = ToolOrchestrator(
            actionLedger = ledger,
            userConfirmationGate = UserConfirmationGate { true },
            clock = { 1_000L },
        )
        val prepared = orchestrator.prepare(
            tool = tool,
            params = MessageParams("provider refuses"),
            requestId = "refused-write",
        ) as PreparationResult.Ready

        val receipt = orchestrator.executeWithReceipt(prepared.action)

        assertEquals(ToolExecutionOutcome.WRITE_REFUSED, receipt.outcome)
        assertEquals(ActionExecutionState.REFUSED, ledger.states.values.single())
    }

    @Test
    fun `a read-only exception is terminally refused rather than unknown`() = runBlocking {
        val ledger = StateRecordingPersistentLedger()
        val failure = IllegalStateException("sensitive provider detail")
        val tool = ThrowingTool(ToolRisk.READ_ONLY, failure)
        val orchestrator = ToolOrchestrator(
            actionLedger = ledger,
            clock = { 1_000L },
        )
        val prepared = orchestrator.prepare(
            tool = tool,
            params = MessageParams("private query"),
            requestId = "failed-read",
        ) as PreparationResult.Ready

        val observed = assertThrows(IllegalStateException::class.java) {
            runBlocking { orchestrator.executeWithReceipt(prepared.action) }
        }

        assertEquals(failure.message, observed.message)
        assertEquals(1, tool.executions)
        assertEquals(ActionExecutionState.REFUSED, ledger.states.values.single())
    }

    @Test
    fun `cancellation cannot interrupt terminal recording after a read-only failure`() = runBlocking {
        val terminalRecordStarted = CompletableDeferred<Unit>()
        val releaseTerminalRecord = CompletableDeferred<Unit>()
        var recordedState: ActionExecutionState? = null
        val ledger = object : PersistentActionLedger() {
            override suspend fun claim(idempotencyKey: String): Boolean = true

            override suspend fun recordState(
                idempotencyKey: String,
                state: ActionExecutionState,
            ): Boolean {
                terminalRecordStarted.complete(Unit)
                releaseTerminalRecord.await()
                recordedState = state
                return true
            }
        }
        val tool = ThrowingTool(
            risk = ToolRisk.READ_ONLY,
            failure = IllegalStateException("sensitive provider detail"),
        )
        val orchestrator = ToolOrchestrator(actionLedger = ledger, clock = { 1_000L })
        val prepared = orchestrator.prepare(
            tool = tool,
            params = MessageParams("private query"),
            requestId = "cancel-failed-read",
        ) as PreparationResult.Ready
        val execution = launch {
            runCatching { orchestrator.executeWithReceipt(prepared.action) }
        }

        terminalRecordStarted.await()
        execution.cancel()
        releaseTerminalRecord.complete(Unit)
        execution.join()

        assertEquals(ActionExecutionState.REFUSED, recordedState)
        assertEquals(1, tool.executions)
    }

    @Test
    fun `a side-effecting exception remains unknown after its durable claim`() = runBlocking {
        val ledger = StateRecordingPersistentLedger()
        val failure = IllegalStateException("provider may have committed")
        val tool = ThrowingTool(ToolRisk.COMMUNICATION, failure)
        val orchestrator = ToolOrchestrator(
            actionLedger = ledger,
            userConfirmationGate = UserConfirmationGate { true },
            clock = { 1_000L },
        )
        val prepared = orchestrator.prepare(
            tool = tool,
            params = MessageParams("private write"),
            requestId = "failed-write",
        ) as PreparationResult.Ready

        val observed = assertThrows(IllegalStateException::class.java) {
            runBlocking { orchestrator.executeWithReceipt(prepared.action) }
        }

        assertEquals(failure.message, observed.message)
        assertEquals(1, tool.executions)
        assertEquals(ActionExecutionState.UNKNOWN_AFTER_CLAIM, ledger.states.values.single())
    }

    @Test
    fun `read-only execution remains cancellable after its claim`() = runBlocking {
        val executionStarted = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        val callbackReceipt = AtomicReference<ToolExecutionReceipt<String>?>(null)
        val readTool = object : AgentTool<MessageParams, String> {
            override val descriptor = ToolDescriptor(
                name = "slow_read",
                description = "Slow cancellable read",
                risk = ToolRisk.READ_ONLY,
            )

            override suspend fun validateAndCanonicalize(params: MessageParams): ValidationResult =
                ValidationResult.Valid(params.text)

            override fun preview(input: CanonicalToolInput): ActionPreview =
                ActionPreview("읽기", input.encoded)

            override suspend fun execute(
                input: CanonicalToolInput,
                permit: ExecutionPermit,
            ): String {
                executionStarted.complete(Unit)
                neverRelease.await()
                return input.encoded
            }
        }
        val ledger = StateRecordingPersistentLedger()
        val orchestrator = ToolOrchestrator(actionLedger = ledger, clock = { 1_000L })
        val prepared = orchestrator.prepare(
            tool = readTool,
            params = MessageParams("hello"),
            requestId = "cancel-read",
        ) as PreparationResult.Ready
        val execution = launch {
            orchestrator.executeWithReceipt(
                action = prepared.action,
                onExecuted = callbackReceipt::set,
            )
        }

        executionStarted.await()
        execution.cancel()
        execution.join()

        assertTrue(execution.isCancelled)
        assertNull(callbackReceipt.get())
        assertEquals(ActionExecutionState.REFUSED, ledger.states.values.single())
    }

    private fun copyAction(
        original: PreparedAction<MessageParams, String>,
        actionId: String = original.actionId,
        toolName: String = original.toolName,
        expiresAtEpochMillis: Long = original.expiresAtEpochMillis,
        confirmation: ConfirmationRequirement = original.confirmation,
        parameterDigest: String = original.parameterDigest,
        challengeDigest: String = original.challengeDigest,
        preview: ActionPreview = original.preview,
        previewDigest: String = original.previewDigest,
        idempotencyKey: String = original.idempotencyKey,
        replayIdentityDigest: String = original.replayIdentityDigest,
        requiredCapabilities: Set<ToolCapability> = original.requiredCapabilities,
        risk: ToolRisk = original.risk,
        requestId: String = original.requestId,
        canonicalInput: CanonicalToolInput = original.canonicalInput,
    ): PreparedAction<MessageParams, String> = PreparedAction(
        actionId = actionId,
        toolName = toolName,
        preview = preview,
        expiresAtEpochMillis = expiresAtEpochMillis,
        confirmation = confirmation,
        parameterDigest = parameterDigest,
        challengeDigest = challengeDigest,
        previewDigest = previewDigest,
        idempotencyKey = idempotencyKey,
        replayIdentityDigest = replayIdentityDigest,
        requiredCapabilities = requiredCapabilities,
        risk = risk,
        requestId = requestId,
        canonicalInput = canonicalInput,
        tool = original.tool,
        permit = original.permit,
        issuerToken = original.issuerToken,
    )
}
