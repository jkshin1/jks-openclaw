package com.personaledge.agent

import com.personaledge.core.data.AgentSettings
import com.personaledge.core.data.OpenClawGatewaySettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerConsentInterlockTest {
    @Test
    fun `openclaw gateway consent is default off and closes immediately`() {
        val feature = OwnerConsentFeature.OPENCLAW_GATEWAY
        val defaults = AgentSettings()
        val configured = AgentSettings(
            openClawGateway = OpenClawGatewaySettings(
                enabled = true,
                endpointUrl = "wss://personal-edge.example.test/",
            ),
        )
        val interlock = OwnerConsentInterlock()

        assertFalse(defaults.ownerConsentEnabled(feature))
        assertTrue(configured.ownerConsentEnabled(feature))
        assertTrue(interlock.allowed(feature, configured.ownerConsentEnabled(feature)))

        interlock.requestEnabled(feature, enabled = false)

        assertFalse(interlock.allowed(feature, configured.ownerConsentEnabled(feature)))
    }

    @Test
    fun `durable false is off by default and a failed disable stays closed`() = runBlocking {
        val interlock = OwnerConsentInterlock()
        val feature = OwnerConsentFeature.MEMORY

        assertFalse(interlock.allowed(feature, durableEnabled = false))
        assertTrue(interlock.allowed(feature, durableEnabled = true))

        val disable = interlock.requestEnabled(feature, enabled = false)
        assertFalse(interlock.allowed(feature, durableEnabled = true))
        assertEquals(
            OwnerConsentMutationOutcome.FAILED,
            interlock.persistLatest(disable) { _, _ -> error("durable write failed") },
        )
        assertFalse(interlock.allowed(feature, durableEnabled = true))
    }

    @Test
    fun `enable opens only after the latest durable write succeeds`() = runBlocking {
        val interlock = OwnerConsentInterlock()
        val feature = OwnerConsentFeature.WEB_SEARCH
        val disable = interlock.requestEnabled(feature, enabled = false)
        assertEquals(
            OwnerConsentMutationOutcome.APPLIED,
            interlock.persistLatest(disable) { _, _ -> },
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val enable = interlock.requestEnabled(feature, enabled = true)
        val persistence = async {
            interlock.persistLatest(enable) { _, _ ->
                entered.complete(Unit)
                release.await()
            }
        }

        entered.await()
        assertFalse(interlock.allowed(feature, durableEnabled = true))
        release.complete(Unit)
        assertEquals(OwnerConsentMutationOutcome.APPLIED, persistence.await())
        assertTrue(interlock.allowed(feature, durableEnabled = true))
    }

    @Test
    fun `rapid enable then disable keeps the latest request closed`() = runBlocking {
        val interlock = OwnerConsentInterlock()
        val feature = OwnerConsentFeature.ROUTE_LOOKUP
        val initialDisable = interlock.requestEnabled(feature, enabled = false)
        interlock.persistLatest(initialDisable) { _, _ -> }
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val persisted = mutableListOf<Boolean>()
        val enable = interlock.requestEnabled(feature, enabled = true)
        val first = async {
            interlock.persistLatest(enable) { _, value ->
                firstEntered.complete(Unit)
                releaseFirst.await()
                persisted += value
            }
        }
        firstEntered.await()

        val disable = interlock.requestEnabled(feature, enabled = false)
        val second = async {
            interlock.persistLatest(disable) { _, value -> persisted += value }
        }
        assertFalse(interlock.allowed(feature, durableEnabled = true))
        releaseFirst.complete(Unit)

        assertEquals(OwnerConsentMutationOutcome.SUPERSEDED, first.await())
        assertEquals(OwnerConsentMutationOutcome.APPLIED, second.await())
        assertEquals(listOf(true, false), persisted)
        assertFalse(interlock.allowed(feature, durableEnabled = true))
    }

    @Test
    fun `superseded enable cannot open the gate when the latest enable fails`() = runBlocking {
        val interlock = OwnerConsentInterlock()
        val feature = OwnerConsentFeature.PROACTIVE_ROUTE_PLANNING
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstEnable = interlock.requestEnabled(feature, enabled = true)
        val first = async {
            interlock.persistLatest(firstEnable) { _, _ ->
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val latestEnable = interlock.requestEnabled(feature, enabled = true)
        val latest = async {
            interlock.persistLatest(latestEnable) { _, _ -> error("latest write failed") }
        }

        releaseFirst.complete(Unit)

        assertEquals(OwnerConsentMutationOutcome.SUPERSEDED, first.await())
        assertEquals(OwnerConsentMutationOutcome.FAILED, latest.await())
        assertFalse(interlock.allowed(feature, durableEnabled = true))
    }

    @Test
    fun `process mutation survives cancellation of a view model observer`() = runBlocking {
        val interlock = OwnerConsentInterlock()
        val mutationJob = SupervisorJob()
        val mutationScope = CoroutineScope(mutationJob + Dispatchers.Default)
        val persistenceEntered = CompletableDeferred<Unit>()
        val releasePersistence = CompletableDeferred<Unit>()
        var durable = true
        val mutator = OwnerConsentMutator(interlock, mutationScope) { _, enabled ->
            persistenceEntered.complete(Unit)
            releasePersistence.await()
            durable = enabled
        }
        val observerJob = Job()
        val observerScope = CoroutineScope(observerJob + Dispatchers.Default)

        val mutation = mutator.setEnabled(OwnerConsentFeature.DAILY_BRIEF, enabled = false)
        val observer = observerScope.launch { mutation.await() }
        persistenceEntered.await()
        observerJob.cancel()
        observer.join()
        releasePersistence.complete(Unit)

        assertEquals(OwnerConsentMutationOutcome.APPLIED, mutation.await())
        assertFalse(durable)
        assertFalse(
            interlock.allowed(OwnerConsentFeature.DAILY_BRIEF, durableEnabled = true),
        )
        mutationScope.cancel()
    }

    @Test
    fun `cancelled enable persistence never reopens a closed gate`() = runBlocking {
        val interlock = OwnerConsentInterlock()
        val feature = OwnerConsentFeature.COMMITMENT_PROPOSALS
        val disable = interlock.requestEnabled(feature, enabled = false)
        interlock.persistLatest(disable) { _, _ -> }
        val enable = interlock.requestEnabled(feature, enabled = true)

        try {
            interlock.persistLatest(enable) { _, _ -> throw CancellationException("cancelled") }
            error("expected cancellation")
        } catch (_: CancellationException) {
            // Cancellation is propagated, while the last applied disable remains effective.
        }

        assertFalse(interlock.allowed(feature, durableEnabled = true))
    }
}
