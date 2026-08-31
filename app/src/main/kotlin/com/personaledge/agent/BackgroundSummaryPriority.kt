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

    fun cancelForThermalPolicy(summaryJob: Job?): Job? = summaryJob
        ?.takeIf(Job::isActive)
        ?.also { job ->
            job.cancel(CancellationException("Thermal policy stopped background summary."))
        }

    fun mayAccept(
        completed: Boolean,
        toolAttemptedOrFailed: Boolean,
        observation: ThermalObservation,
    ): Boolean = completed &&
        !toolAttemptedOrFailed &&
        PredictiveThermalPolicy.allowBackgroundSummary(observation)

    suspend fun awaitRelease(cancelledSummary: Job?) {
        cancelledSummary?.join()
    }
}
