package com.personaledge.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/** User turns supersede best-effort summaries that share the single-owner model controller. */
internal object BackgroundSummaryPriority {
    fun cancelForUserTurn(summaryJob: Job?): Job? = summaryJob
        ?.takeIf(Job::isActive)
        ?.also { job ->
            job.cancel(CancellationException("User turn superseded background summary."))
        }

    suspend fun awaitRelease(cancelledSummary: Job?) {
        cancelledSummary?.join()
    }
}
