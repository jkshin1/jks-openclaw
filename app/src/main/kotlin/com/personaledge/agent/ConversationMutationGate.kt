package com.personaledge.agent

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job

/**
 * Linearizes active-conversation mutations with the synchronous start of an agent turn.
 *
 * A lease contains no conversation or prompt data. Callers hold it only across the state/Room
 * boundary that must not race another restore, switch, delete, recovery, or turn start.
 */
internal class ConversationMutationGate {
    private val activeLease = AtomicReference<Lease?>(null)

    fun tryAcquire(): Lease? {
        val candidate = Lease(this)
        return if (activeLease.compareAndSet(null, candidate)) candidate else null
    }

    fun owns(lease: Lease): Boolean = activeLease.get() === lease && !lease.isClosed()

    private fun release(lease: Lease) {
        activeLease.compareAndSet(lease, null)
    }

    class Lease internal constructor(
        private val gate: ConversationMutationGate,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        internal fun isClosed(): Boolean = closed.get()

        override fun close() {
            if (closed.compareAndSet(false, true)) gate.release(this)
        }

        /** Also releases when a cancelled scope prevents the coroutine body from starting. */
        internal fun closeOnCompletion(job: Job): Job = job.also { launched ->
            launched.invokeOnCompletion { close() }
        }

        override fun toString(): String = "ConversationMutationGate.Lease(closed=${closed.get()})"
    }
}

/** Keeps durable write-verification obligations visible independently of transcript lifetime. */
internal object ConversationRecoveryEntryPolicy {
    fun mergeGlobalVerification(
        entries: List<ChatEntry>,
        globalVerificationEntries: List<ChatEntry>?,
    ): List<ChatEntry> {
        val retained = entries.filterNot(::isVerificationEntry)
        val verificationSource = globalVerificationEntries
            ?: entries.filter(::isVerificationEntry)
        val seen = mutableSetOf<Pair<String, String>>()
        return buildList {
            addAll(retained)
            verificationSource.forEach { entry ->
                val action = entry.recoveryAction
                    ?.takeIf { it.type == ChatRecoveryType.VERIFY_EXTERNAL_STATE }
                    ?: return@forEach
                if (seen.add(action.turnId to action.conversationId)) add(entry)
            }
        }
    }

    fun verificationOnly(
        currentEntries: List<ChatEntry>,
        globalVerificationEntries: List<ChatEntry>?,
    ): List<ChatEntry> = mergeGlobalVerification(
        entries = emptyList(),
        globalVerificationEntries = globalVerificationEntries
            ?: currentEntries.filter(::isVerificationEntry),
    )

    fun canResolve(action: ChatRecoveryAction, activeConversationId: String?): Boolean =
        action.type == ChatRecoveryType.VERIFY_EXTERNAL_STATE ||
            action.conversationId == activeConversationId

    private fun isVerificationEntry(entry: ChatEntry): Boolean =
        entry.recoveryAction?.type == ChatRecoveryType.VERIFY_EXTERNAL_STATE
}
