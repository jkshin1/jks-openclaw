package com.personaledge.agent

import com.personaledge.core.data.AgentSettings
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owner-controlled features whose durable opt-in is also guarded for this process lifetime. */
internal enum class OwnerConsentFeature {
    ROUTE_LOOKUP,
    WEB_SEARCH,
    MEMORY,
    COMMITMENT_PROPOSALS,
    PROACTIVE_ROUTE_PLANNING,
    DAILY_BRIEF,
}

internal enum class OwnerConsentMutationOutcome {
    APPLIED,
    SUPERSEDED,
    FAILED,
}

/**
 * Pure process-local gate paired with the durable settings store at every use boundary.
 *
 * Durable settings remain the source of truth across process starts. This gate closes immediately
 * on an owner disable request and does not reopen after that request until the latest enable has
 * been durably written. Requests are identity-based so a slow older write cannot reopen a feature
 * after a newer disable.
 */
class OwnerConsentInterlock internal constructor() {
    private val states = EnumMap<OwnerConsentFeature, FeatureState>(OwnerConsentFeature::class.java)
        .apply {
            OwnerConsentFeature.values().forEach { feature -> put(feature, FeatureState()) }
        }

    /** Synchronous by design: callers close the gate before launching any persistence coroutine. */
    internal fun requestEnabled(feature: OwnerConsentFeature, enabled: Boolean): SettingRequest {
        val state = state(feature)
        return synchronized(state.requestLock) {
            SettingRequest(feature = feature, enabled = enabled).also { request ->
                state.latestRequest.set(request)
                // Every uncommitted mutation is fail-closed. This matters for two rapid enable
                // requests: an older successful write must not open the gate if the latest write
                // is still pending or ultimately fails.
                state.disableRequested.set(true)
            }
        }
    }

    /** Serializes writes per feature and applies only the latest request to the process gate. */
    internal suspend fun persistLatest(
        request: SettingRequest,
        persist: suspend (OwnerConsentFeature, Boolean) -> Unit,
    ): OwnerConsentMutationOutcome {
        val state = state(request.feature)
        return state.mutex.withLock {
            if (!isLatest(state, request)) return@withLock OwnerConsentMutationOutcome.SUPERSEDED
            try {
                persist(request.feature, request.enabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock if (isLatest(state, request)) {
                    OwnerConsentMutationOutcome.FAILED
                } else {
                    OwnerConsentMutationOutcome.SUPERSEDED
                }
            }
            synchronized(state.requestLock) {
                if (state.latestRequest.get() !== request) {
                    OwnerConsentMutationOutcome.SUPERSEDED
                } else {
                    state.disableRequested.set(!request.enabled)
                    OwnerConsentMutationOutcome.APPLIED
                }
            }
        }
    }

    /** Effective consent is always the intersection of durable state and the immediate gate. */
    internal fun allowed(feature: OwnerConsentFeature, durableEnabled: Boolean): Boolean =
        durableEnabled && !state(feature).disableRequested.get()

    private fun isLatest(state: FeatureState, request: SettingRequest): Boolean =
        synchronized(state.requestLock) { state.latestRequest.get() === request }

    private fun state(feature: OwnerConsentFeature): FeatureState = checkNotNull(states[feature])

    internal class SettingRequest internal constructor(
        internal val feature: OwnerConsentFeature,
        internal val enabled: Boolean,
    ) {
        override fun toString(): String = "OwnerConsentInterlock.SettingRequest"
    }

    private class FeatureState {
        val mutex = Mutex()
        val requestLock = Any()
        val disableRequested = AtomicBoolean(false)
        val latestRequest = AtomicReference<SettingRequest?>()
    }
}

/** Launches consent writes in an application-owned scope, independent of a ViewModel job. */
internal class OwnerConsentMutator(
    private val interlock: OwnerConsentInterlock,
    private val mutationScope: CoroutineScope,
    private val persist: suspend (OwnerConsentFeature, Boolean) -> Unit,
) {
    fun setEnabled(
        feature: OwnerConsentFeature,
        enabled: Boolean,
    ): Deferred<OwnerConsentMutationOutcome> {
        val request = interlock.requestEnabled(feature, enabled)
        return mutationScope.async { interlock.persistLatest(request, persist) }
    }
}

internal fun AgentSettings.ownerConsentEnabled(feature: OwnerConsentFeature): Boolean =
    when (feature) {
        OwnerConsentFeature.ROUTE_LOOKUP -> routeLookupEnabled
        OwnerConsentFeature.WEB_SEARCH -> webSearchEnabled
        OwnerConsentFeature.MEMORY -> memoryEnabled
        OwnerConsentFeature.COMMITMENT_PROPOSALS -> commitmentProposalsEnabled
        OwnerConsentFeature.PROACTIVE_ROUTE_PLANNING -> proactiveRoutePlanningEnabled
        OwnerConsentFeature.DAILY_BRIEF -> dailyBriefEnabled
    }
