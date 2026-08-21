package com.personaledge.agent

import com.personaledge.core.diagnostics.DiagnosticConfirmationOutcome
import com.personaledge.core.diagnostics.DiagnosticPhase
import com.personaledge.core.diagnostics.DiagnosticSink
import com.personaledge.core.diagnostics.DiagnosticToolStage
import com.personaledge.core.tools.ActionChallenge
import com.personaledge.core.tools.ActionPreview
import com.personaledge.core.tools.UserConfirmationGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class PendingConfirmation(
    val actionId: String,
    val toolName: String,
    val preview: ActionPreview,
    val parameterDigest: String,
    val expiresAtEpochMillis: Long,
)

/** Bridges the orchestrator's suspend boundary to one explicit Compose dialog. */
class ConfirmationCoordinator(
    private val clock: () -> Long = System::currentTimeMillis,
    private val diagnostics: DiagnosticSink = DiagnosticSink { false },
    private val markPhase: (DiagnosticPhase) -> Boolean = { false },
) : UserConfirmationGate {
    private enum class Decision {
        APPROVED,
        DENIED,
        CANCELLED,
    }

    private data class Request(
        val view: PendingConfirmation,
        val decision: CompletableDeferred<Decision>,
    )

    private val confirmationMutex = Mutex()
    private val stateLock = Any()
    private val _pending = MutableStateFlow<PendingConfirmation?>(null)
    private var active: Request? = null

    val pending: StateFlow<PendingConfirmation?> = _pending.asStateFlow()

    override suspend fun confirm(challenge: ActionChallenge): Boolean =
        confirmationMutex.withLock {
            val remainingMillis = challenge.expiresAtEpochMillis - clock()
            if (remainingMillis <= 0) {
                diagnostics.recordKnownToolPhase(
                    challenge.toolName,
                    DiagnosticToolStage.CONFIRMATION_RESOLVED,
                    DiagnosticConfirmationOutcome.EXPIRED,
                )
                return@withLock false
            }

            val request = Request(
                view = PendingConfirmation(
                    actionId = challenge.actionId,
                    toolName = challenge.toolName,
                    preview = challenge.preview,
                    parameterDigest = challenge.parameterDigest,
                    expiresAtEpochMillis = challenge.expiresAtEpochMillis,
                ),
                decision = CompletableDeferred(),
            )
            synchronized(stateLock) {
                active = request
                _pending.value = request.view
            }
            runCatching { markPhase(DiagnosticPhase.TOOL_CONFIRMATION) }
            diagnostics.recordKnownToolPhase(
                challenge.toolName,
                DiagnosticToolStage.CONFIRMATION_REQUESTED,
                DiagnosticConfirmationOutcome.REQUESTED,
            )

            var outcome: DiagnosticConfirmationOutcome? = null
            try {
                val decision = withTimeoutOrNull(remainingMillis) { request.decision.await() }
                outcome = when (decision) {
                    Decision.APPROVED -> DiagnosticConfirmationOutcome.APPROVED
                    Decision.DENIED -> DiagnosticConfirmationOutcome.DENIED
                    Decision.CANCELLED -> DiagnosticConfirmationOutcome.CANCELLED
                    null -> DiagnosticConfirmationOutcome.EXPIRED
                }
                decision == Decision.APPROVED
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                outcome = DiagnosticConfirmationOutcome.CANCELLED
                throw cancelled
            } finally {
                synchronized(stateLock) {
                    if (active === request) {
                        active = null
                        _pending.value = null
                    }
                }
                val resolvedOutcome = outcome ?: DiagnosticConfirmationOutcome.CANCELLED
                diagnostics.recordKnownToolPhase(
                    challenge.toolName,
                    DiagnosticToolStage.CONFIRMATION_RESOLVED,
                    resolvedOutcome,
                )
                runCatching {
                    markPhase(
                        if (resolvedOutcome == DiagnosticConfirmationOutcome.APPROVED) {
                            DiagnosticPhase.TOOL_EXECUTION
                        } else {
                            DiagnosticPhase.TURN_PROCESSING
                        },
                    )
                }
            }
        }

    fun resolve(actionId: String, approved: Boolean): Boolean {
        val request = synchronized(stateLock) {
            active?.takeIf { it.view.actionId == actionId }
        } ?: return false
        return request.decision.complete(if (approved) Decision.APPROVED else Decision.DENIED)
    }

    fun denyPending() {
        val request = synchronized(stateLock) { active }
        request?.decision?.complete(Decision.CANCELLED)
    }
}
