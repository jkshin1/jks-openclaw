package com.personaledge.agent

import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpenClawGatewayConsentManagerTest {
    @Test
    fun `platform 0771 root is allowed but child directory and marker remain owner-only`() {
        assertTrue(
            OpenClawGatewayRevocationFilePolicy.isTrustedNoBackupRoot(
                isDirectory = true,
                actualUid = 10,
                ownerUid = 10,
                mode = 0x1C0,
            ),
        )
        // ContextImpl.ensurePrivateDirExists() creates no_backup as 0771. The platform root is
        // UID/SELinux-private; marker state is one fixed 0700 child below it.
        assertTrue(
            OpenClawGatewayRevocationFilePolicy.isTrustedNoBackupRoot(
                isDirectory = true,
                actualUid = 10,
                ownerUid = 10,
                mode = 0x1F9,
            ),
        )
        assertFalse(
            OpenClawGatewayRevocationFilePolicy.isTrustedNoBackupRoot(
                isDirectory = true,
                actualUid = 10,
                ownerUid = 10,
                mode = 0x1FB,
            ),
        )
        assertFalse(
            OpenClawGatewayRevocationFilePolicy.isTrustedNoBackupRoot(
                isDirectory = true,
                actualUid = 10,
                ownerUid = 10,
                mode = 0x1FF,
            ),
        )
        assertFalse(
            OpenClawGatewayRevocationFilePolicy.isTrustedNoBackupRoot(
                isDirectory = true,
                actualUid = 11,
                ownerUid = 10,
                mode = 0x1F9,
            ),
        )
        assertTrue(
            OpenClawGatewayRevocationFilePolicy.isPrivateStateDirectory(
                isDirectory = true,
                actualUid = 10,
                ownerUid = 10,
                mode = 0x1C0,
            ),
        )
        assertFalse(
            OpenClawGatewayRevocationFilePolicy.isPrivateStateDirectory(
                isDirectory = true,
                actualUid = 10,
                ownerUid = 10,
                mode = 0x1F9,
            ),
        )
        assertTrue(
            OpenClawGatewayRevocationFilePolicy.isPrivateRegularFile(
                isRegularFile = true,
                linkCount = 1L,
                actualUid = 10,
                ownerUid = 10,
                mode = 0x180,
            ),
        )
        assertFalse(
            OpenClawGatewayRevocationFilePolicy.isPrivateRegularFile(
                isRegularFile = true,
                linkCount = 2L,
                actualUid = 10,
                ownerUid = 10,
                mode = 0x180,
            ),
        )
        assertFalse(
            OpenClawGatewayRevocationFilePolicy.isPrivateRegularFile(
                isRegularFile = true,
                linkCount = 1L,
                actualUid = 10,
                ownerUid = 10,
                mode = 0x184,
            ),
        )
    }

    @Test
    fun `marker codec has one exact bounded content-free representation`() {
        val intent = OpenClawGatewayRevocationIntent.generate()
        val encoded = intent.encode()
        val rendered = encoded.toString(Charsets.US_ASCII)
        val token = rendered.lineSequence().last { it.isNotEmpty() }

        assertEquals(OpenClawGatewayRevocationIntent.ENCODED_BYTES, encoded.size)
        assertEquals(intent, OpenClawGatewayRevocationIntent.decode(encoded))
        assertNull(OpenClawGatewayRevocationIntent.decode(encoded + byteArrayOf(0)))
        assertNull(
            OpenClawGatewayRevocationIntent.decode(
                encoded.copyOf().also { it[it.lastIndex - 1] = '\n'.code.toByte() },
            ),
        )
        assertFalse(intent.toString().contains(token))
        assertFalse(OpenClawGatewayRevocationSnapshot.Present(intent).toString().contains(token))
        assertFalse(rendered.contains("wss://"))
        assertFalse(rendered.contains("credential"))
    }

    @Test
    fun `default false startup clears only the recovery barrier without a write`() = runTest {
        val interlock = OwnerConsentInterlock()
        val settings = FakeSettingsStore(enabled = false)
        val journal = FakeJournal()
        val manager = manager(interlock, settings, journal, this)

        assertEquals(OwnerConsentMutationOutcome.APPLIED, manager.recoverBeforeGateway().await())
        assertEquals(OpenClawGatewayRevocationBarrierState.CLEAR, manager.revocationBarrierState.value)
        assertFalse(interlock.allowed(OwnerConsentFeature.OPENCLAW_GATEWAY, durableEnabled = true))
        assertTrue(settings.writes.isEmpty())
        assertNull(journal.current)
    }

    @Test
    fun `startup marker stays until false commit succeeds then barrier clears`() = runTest {
        val marker = OpenClawGatewayRevocationIntent.generate()
        val enteredWrite = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val settings = FakeSettingsStore(enabled = true).apply {
            beforeWrite = { enabled ->
                if (!enabled) {
                    enteredWrite.complete(Unit)
                    releaseWrite.await()
                }
            }
        }
        val journal = FakeJournal(current = marker)
        val interlock = OwnerConsentInterlock()
        val manager = manager(interlock, settings, journal, this)

        enteredWrite.await()
        assertEquals(OpenClawGatewayRevocationBarrierState.BLOCKED, manager.revocationBarrierState.value)
        assertEquals(marker, journal.current)
        assertTrue(journal.clears.isEmpty())
        assertFalse(interlock.allowed(OwnerConsentFeature.OPENCLAW_GATEWAY, durableEnabled = true))

        releaseWrite.complete(Unit)
        assertEquals(OwnerConsentMutationOutcome.APPLIED, manager.recoverBeforeGateway().await())
        assertFalse(settings.enabled)
        assertNull(journal.current)
        assertEquals(listOf("snapshot", "write:false", "clear"), settingsAndJournalEvents(settings, journal))
        assertEquals(OpenClawGatewayRevocationBarrierState.CLEAR, manager.revocationBarrierState.value)
    }

    @Test
    fun `startup false recovery failure retains marker and remains blocked`() = runTest {
        val marker = OpenClawGatewayRevocationIntent.generate()
        val settings = FakeSettingsStore(enabled = true).apply { failFalseWrite = true }
        val journal = FakeJournal(current = marker)
        val interlock = OwnerConsentInterlock()
        val manager = manager(interlock, settings, journal, this)

        assertEquals(OwnerConsentMutationOutcome.FAILED, manager.recoverBeforeGateway().await())
        assertTrue(settings.enabled)
        assertEquals(marker, journal.current)
        assertTrue(journal.clears.isEmpty())
        assertEquals(OpenClawGatewayRevocationBarrierState.BLOCKED, manager.revocationBarrierState.value)
        assertFalse(interlock.allowed(OwnerConsentFeature.OPENCLAW_GATEWAY, durableEnabled = true))
    }

    @Test
    fun `unclear startup state attempts false repair but never opens or clears untrusted marker`() =
        runTest {
            val settings = FakeSettingsStore(enabled = true).apply { failRead = true }
            val journal = FakeJournal(
                forcedSnapshot = OpenClawGatewayRevocationSnapshot.Unclear,
            ).apply { failPublish = true }
            val interlock = OwnerConsentInterlock()
            val manager = manager(interlock, settings, journal, this)

            assertEquals(OwnerConsentMutationOutcome.FAILED, manager.recoverBeforeGateway().await())
            assertFalse(settings.enabled)
            assertEquals(listOf(false), settings.writes)
            assertTrue(journal.clears.isEmpty())
            assertEquals(
                OpenClawGatewayRevocationBarrierState.BLOCKED,
                manager.revocationBarrierState.value,
            )
            assertFalse(interlock.allowed(OwnerConsentFeature.OPENCLAW_GATEWAY, true))
        }

    @Test
    fun `strict read failure is resolved only by marker protected false commit`() = runTest {
        val settings = FakeSettingsStore(enabled = true).apply { failRead = true }
        val journal = FakeJournal()
        val interlock = OwnerConsentInterlock()
        val manager = manager(interlock, settings, journal, this)

        assertEquals(OwnerConsentMutationOutcome.APPLIED, manager.recoverBeforeGateway().await())
        assertEquals(listOf(false), settings.writes)
        assertEquals(1, journal.publishes.size)
        assertEquals(journal.publishes, journal.clears)
        assertNull(journal.current)
        assertFalse(settings.enabled)
        assertFalse(interlock.allowed(OwnerConsentFeature.OPENCLAW_GATEWAY, true))
        assertEquals(OpenClawGatewayRevocationBarrierState.CLEAR, manager.revocationBarrierState.value)
    }

    @Test
    fun `marker generation failure still attempts false recovery and stays blocked`() = runTest {
        val settings = FakeSettingsStore(enabled = true).apply { failRead = true }
        val journal = FakeJournal()
        val interlock = OwnerConsentInterlock()
        val manager = manager(
            interlock = interlock,
            settings = settings,
            journal = journal,
            scope = this,
            intentFactory = { error("content-free test entropy failure") },
        )

        assertEquals(OwnerConsentMutationOutcome.FAILED, manager.recoverBeforeGateway().await())
        assertFalse(settings.enabled)
        assertEquals(listOf(false), settings.writes)
        assertTrue(journal.publishes.isEmpty())
        assertEquals(OpenClawGatewayRevocationBarrierState.BLOCKED, manager.revocationBarrierState.value)
        assertFalse(interlock.allowed(OwnerConsentFeature.OPENCLAW_GATEWAY, true))
    }

    @Test
    fun `durable true startup uses a temporary latch before reopening`() = runTest {
        val settings = FakeSettingsStore(enabled = true)
        val journal = FakeJournal()
        val interlock = OwnerConsentInterlock()
        val manager = manager(interlock, settings, journal, this)

        assertEquals(OwnerConsentMutationOutcome.APPLIED, manager.recoverBeforeGateway().await())
        assertEquals(listOf(true), settings.writes)
        assertEquals(1, journal.publishes.size)
        assertEquals(journal.publishes.single(), journal.clears.single())
        assertNull(journal.current)
        assertTrue(interlock.allowed(OwnerConsentFeature.OPENCLAW_GATEWAY, durableEnabled = true))
        assertEquals(OpenClawGatewayRevocationBarrierState.CLEAR, manager.revocationBarrierState.value)
    }

    @Test
    fun `disable closes synchronously and removes its marker only after false is durable`() = runTest {
        val settings = FakeSettingsStore(enabled = false)
        val journal = FakeJournal()
        val interlock = OwnerConsentInterlock()
        val manager = manager(interlock, settings, journal, this)
        manager.recoverBeforeGateway().await()
        assertEquals(OwnerConsentMutationOutcome.APPLIED, manager.setEnabled(true).await())

        val enteredWrite = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        settings.beforeWrite = { enabled ->
            if (!enabled) {
                enteredWrite.complete(Unit)
                releaseWrite.await()
            }
        }
        settings.events.clear()
        journal.events.clear()
        journal.clears.clear()

        val disable = manager.setEnabled(false)
        assertFalse(interlock.allowed(OwnerConsentFeature.OPENCLAW_GATEWAY, durableEnabled = true))
        assertEquals(OpenClawGatewayRevocationBarrierState.BLOCKED, manager.revocationBarrierState.value)
        enteredWrite.await()
        val marker = journal.current
        assertNotNull(marker)
        assertTrue(journal.clears.isEmpty())
        assertTrue(settings.enabled)

        releaseWrite.complete(Unit)
        assertEquals(OwnerConsentMutationOutcome.APPLIED, disable.await())
        assertFalse(settings.enabled)
        assertNull(journal.current)
        assertEquals(marker, journal.clears.single())
        assertEquals(listOf("publish", "write:false", "clear"), settingsAndJournalEvents(settings, journal))
        assertEquals(OpenClawGatewayRevocationBarrierState.CLEAR, manager.revocationBarrierState.value)
    }

    @Test
    fun `explicit disable repairs durable false but stays blocked when marker publish fails`() =
        runTest {
            val settings = FakeSettingsStore(enabled = false)
            val journal = FakeJournal()
            val interlock = OwnerConsentInterlock()
            val manager = manager(interlock, settings, journal, this)
            manager.recoverBeforeGateway().await()
            assertEquals(OwnerConsentMutationOutcome.APPLIED, manager.setEnabled(true).await())

            settings.writes.clear()
            settings.events.clear()
            journal.events.clear()
            journal.clears.clear()
            journal.beforePublish = { intent ->
                // Model a journal that placed the exact marker but could not prove its final
                // durability. The manager must neither clear it nor report disable as applied.
                journal.current = intent
            }
            journal.failPublish = true

            val disable = manager.setEnabled(false)

            assertFalse(interlock.allowed(OwnerConsentFeature.OPENCLAW_GATEWAY, true))
            assertEquals(
                OpenClawGatewayRevocationBarrierState.BLOCKED,
                manager.revocationBarrierState.value,
            )
            assertEquals(OwnerConsentMutationOutcome.FAILED, disable.await())
            assertFalse(settings.enabled)
            assertEquals(listOf(false), settings.writes)
            assertNotNull(journal.current)
            assertTrue(journal.clears.isEmpty())
            assertEquals(listOf("publish", "write:false"), settingsAndJournalEvents(settings, journal))
            assertEquals(
                OpenClawGatewayRevocationBarrierState.BLOCKED,
                manager.revocationBarrierState.value,
            )
        }

    @Test
    fun `explicit disable repairs durable false but stays blocked when marker creation fails`() =
        runTest {
            val settings = FakeSettingsStore(enabled = false)
            val journal = FakeJournal()
            val interlock = OwnerConsentInterlock()
            var failIntentCreation = false
            val manager = manager(
                interlock = interlock,
                settings = settings,
                journal = journal,
                scope = this,
                intentFactory = {
                    if (failIntentCreation) error("content-free test entropy failure")
                    OpenClawGatewayRevocationIntent.generate()
                },
            )
            manager.recoverBeforeGateway().await()
            assertEquals(OwnerConsentMutationOutcome.APPLIED, manager.setEnabled(true).await())

            settings.writes.clear()
            journal.publishes.clear()
            journal.clears.clear()
            failIntentCreation = true

            val disable = manager.setEnabled(false)

            assertFalse(interlock.allowed(OwnerConsentFeature.OPENCLAW_GATEWAY, true))
            assertEquals(OwnerConsentMutationOutcome.FAILED, disable.await())
            assertFalse(settings.enabled)
            assertEquals(listOf(false), settings.writes)
            assertTrue(journal.publishes.isEmpty())
            assertTrue(journal.clears.isEmpty())
            assertEquals(
                OpenClawGatewayRevocationBarrierState.BLOCKED,
                manager.revocationBarrierState.value,
            )
        }

    @Test
    fun `new disable supersedes blocked enable without letting old token clear`() = runTest {
        val settings = FakeSettingsStore(enabled = false)
        val journal = FakeJournal()
        val interlock = OwnerConsentInterlock()
        val manager = manager(interlock, settings, journal, this)
        manager.recoverBeforeGateway().await()

        val enteredEnable = CompletableDeferred<Unit>()
        val releaseEnable = CompletableDeferred<Unit>()
        settings.beforeWrite = { enabled ->
            if (enabled) {
                enteredEnable.complete(Unit)
                releaseEnable.await()
            }
        }
        val enable = manager.setEnabled(true)
        enteredEnable.await()
        val oldToken = checkNotNull(journal.current)

        val disable = manager.setEnabled(false)
        assertFalse(interlock.allowed(OwnerConsentFeature.OPENCLAW_GATEWAY, durableEnabled = true))
        releaseEnable.complete(Unit)

        assertEquals(OwnerConsentMutationOutcome.SUPERSEDED, enable.await())
        assertEquals(OwnerConsentMutationOutcome.APPLIED, disable.await())
        assertFalse(settings.enabled)
        assertNull(journal.current)
        assertEquals(2, journal.publishes.size)
        assertNotEquals(oldToken, journal.publishes.last())
        assertEquals(listOf(journal.publishes.last()), journal.clears)
        assertFalse(interlock.allowed(OwnerConsentFeature.OPENCLAW_GATEWAY, durableEnabled = true))
    }

    @Test
    fun `new enable supersedes blocked disable without letting old token clear`() = runTest {
        val settings = FakeSettingsStore(enabled = true)
        val journal = FakeJournal()
        val interlock = OwnerConsentInterlock()
        val manager = manager(interlock, settings, journal, this)
        manager.recoverBeforeGateway().await()
        journal.publishes.clear()
        journal.clears.clear()

        val enteredDisable = CompletableDeferred<Unit>()
        val releaseDisable = CompletableDeferred<Unit>()
        settings.beforeWrite = { enabled ->
            if (!enabled) {
                enteredDisable.complete(Unit)
                releaseDisable.await()
            }
        }
        val disable = manager.setEnabled(false)
        enteredDisable.await()
        val oldToken = checkNotNull(journal.current)
        val enable = manager.setEnabled(true)
        releaseDisable.complete(Unit)

        assertEquals(OwnerConsentMutationOutcome.SUPERSEDED, disable.await())
        assertEquals(OwnerConsentMutationOutcome.APPLIED, enable.await())
        assertTrue(settings.enabled)
        assertNull(journal.current)
        assertNotEquals(oldToken, journal.publishes.last())
        assertEquals(listOf(journal.publishes.last()), journal.clears)
        assertTrue(interlock.allowed(OwnerConsentFeature.OPENCLAW_GATEWAY, durableEnabled = true))
    }

    @Test
    fun `CAS mismatch after false commit leaves replacement marker blocked`() = runTest {
        val settings = FakeSettingsStore(enabled = false)
        val replacement = OpenClawGatewayRevocationIntent.generate()
        val journal = FakeJournal()
        val interlock = OwnerConsentInterlock()
        val manager = manager(interlock, settings, journal, this)
        manager.recoverBeforeGateway().await()
        journal.beforeClear = { journal.current = replacement }

        val outcome = manager.setEnabled(false).await()

        assertEquals(OwnerConsentMutationOutcome.FAILED, outcome)
        assertFalse(settings.enabled)
        assertEquals(replacement, journal.current)
        assertEquals(OpenClawGatewayRevocationBarrierState.BLOCKED, manager.revocationBarrierState.value)
        assertFalse(interlock.allowed(OwnerConsentFeature.OPENCLAW_GATEWAY, durableEnabled = true))
    }

    @Test
    fun `restart after marker publish before false commit recovers stale true`() = runTest {
        val settings = FakeSettingsStore(enabled = true)
        val journal = FakeJournal()
        val firstScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val first = manager(OwnerConsentInterlock(), settings, journal, firstScope)
        first.recoverBeforeGateway().await()

        val enteredFalse = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        settings.beforeWrite = { enabled ->
            if (!enabled) {
                enteredFalse.complete(Unit)
                neverRelease.await()
            }
        }
        first.setEnabled(false)
        enteredFalse.await()
        assertNotNull(journal.current)
        assertTrue(settings.enabled)
        firstScope.cancel()
        runCurrent()

        settings.beforeWrite = { _ -> }
        val second = manager(OwnerConsentInterlock(), settings, journal, this)
        assertEquals(OwnerConsentMutationOutcome.APPLIED, second.recoverBeforeGateway().await())
        assertFalse(settings.enabled)
        assertNull(journal.current)
        assertEquals(OpenClawGatewayRevocationBarrierState.CLEAR, second.revocationBarrierState.value)
    }

    @Test
    fun `restart after false commit before marker clear completes recovery`() = runTest {
        val settings = FakeSettingsStore(enabled = true)
        val journal = FakeJournal()
        val firstScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val first = manager(OwnerConsentInterlock(), settings, journal, firstScope)
        first.recoverBeforeGateway().await()

        val enteredClear = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        journal.beforeClear = {
            enteredClear.complete(Unit)
            neverRelease.await()
        }
        first.setEnabled(false)
        enteredClear.await()
        assertFalse(settings.enabled)
        assertNotNull(journal.current)
        firstScope.cancel()
        runCurrent()

        journal.beforeClear = {}
        val second = manager(OwnerConsentInterlock(), settings, journal, this)
        assertEquals(OwnerConsentMutationOutcome.APPLIED, second.recoverBeforeGateway().await())
        assertFalse(settings.enabled)
        assertNull(journal.current)
        assertEquals(OpenClawGatewayRevocationBarrierState.CLEAR, second.revocationBarrierState.value)
    }

    private fun manager(
        interlock: OwnerConsentInterlock,
        settings: FakeSettingsStore,
        journal: FakeJournal,
        scope: CoroutineScope,
        intentFactory: () -> OpenClawGatewayRevocationIntent =
            OpenClawGatewayRevocationIntent::generate,
    ): OpenClawGatewayConsentManager = OpenClawGatewayConsentManager(
        interlock = interlock,
        settingsStore = settings,
        journal = journal,
        mutationScope = scope,
        intentFactory = intentFactory,
    )

    private fun settingsAndJournalEvents(
        settings: FakeSettingsStore,
        journal: FakeJournal,
    ): List<String> = (settings.events + journal.events).sortedBy(Event::sequence).map(Event::name)

    private class FakeSettingsStore(
        var enabled: Boolean,
    ) : OpenClawGatewayConsentSettingsStore {
        val writes = mutableListOf<Boolean>()
        val events = mutableListOf<Event>()
        var failRead = false
        var failFalseWrite = false
        var failTrueWrite = false
        var beforeWrite: suspend (Boolean) -> Unit = {}

        override suspend fun readEnabledStrict(): Boolean {
            events += Event("read")
            if (failRead) error("content-free test read failure")
            return enabled
        }

        override suspend fun writeEnabled(enabled: Boolean) {
            events += Event("write:$enabled")
            beforeWrite(enabled)
            if ((!enabled && failFalseWrite) || (enabled && failTrueWrite)) {
                error("content-free test write failure")
            }
            writes += enabled
            this.enabled = enabled
        }
    }

    private class FakeJournal(
        var current: OpenClawGatewayRevocationIntent? = null,
        var forcedSnapshot: OpenClawGatewayRevocationSnapshot? = null,
    ) : OpenClawGatewayRevocationJournal {
        val publishes = mutableListOf<OpenClawGatewayRevocationIntent>()
        val clears = mutableListOf<OpenClawGatewayRevocationIntent>()
        val events = mutableListOf<Event>()
        var failPublish = false
        var failClear = false
        var beforePublish: suspend (OpenClawGatewayRevocationIntent) -> Unit = {}
        var beforeClear: suspend (OpenClawGatewayRevocationIntent) -> Unit = {}

        override suspend fun snapshot(): OpenClawGatewayRevocationSnapshot {
            events += Event("snapshot")
            return forcedSnapshot ?: current
                ?.let(OpenClawGatewayRevocationSnapshot::Present)
                ?: OpenClawGatewayRevocationSnapshot.Absent
        }

        override suspend fun publish(intent: OpenClawGatewayRevocationIntent): Boolean {
            events += Event("publish")
            beforePublish(intent)
            if (failPublish) return false
            publishes += intent
            current = intent
            return true
        }

        override suspend fun clearIfMatches(intent: OpenClawGatewayRevocationIntent): Boolean {
            events += Event("clear")
            beforeClear(intent)
            if (failClear || current != intent) return false
            clears += intent
            current = null
            return true
        }
    }

    private class Event(
        val name: String,
        val sequence: Long = nextSequence(),
    ) {
        companion object {
            private val lock = Any()
            private var sequence = 0L

            private fun nextSequence(): Long = synchronized(lock) { ++sequence }
        }
    }
}
