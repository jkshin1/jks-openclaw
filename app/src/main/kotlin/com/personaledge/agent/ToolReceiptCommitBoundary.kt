package com.personaledge.agent

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Once a Tool outcome exists, its app-authored receipt is part of the durable execution result.
 * Parent cancellation may stop later model work, but it must not interrupt the UI/history commit
 * and leave the user unsure whether a write happened.
 */
internal object ToolReceiptCommitBoundary {
    suspend fun <T> commit(block: suspend () -> T): T =
        withContext(NonCancellable) { block() }
}
