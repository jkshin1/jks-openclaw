package com.personaledge.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCaptureInterlockTest {
    @Test
    fun `capture allowed intersects durable consent with an immediate disable request`() {
        val interlock = NotificationCaptureInterlock()

        assertFalse(interlock.captureAllowed(durableEnabled = false))
        assertTrue(interlock.captureAllowed(durableEnabled = true))
        interlock.requestCaptureEnabled(false)
        assertFalse(interlock.captureAllowed(durableEnabled = true))
    }

    @Test
    fun `disable request rejects a new capture before asynchronous persistence`() = runBlocking {
        val interlock = NotificationCaptureInterlock()

        interlock.requestCaptureEnabled(false)

        assertNull(interlock.withCaptureBoundary { "sensitive" })
    }

    @Test
    fun `newer setting request invalidates an older token without revealing its value`() {
        val interlock = NotificationCaptureInterlock()
        val older = interlock.requestCaptureEnabled(false)
        val newer = interlock.requestCaptureEnabled(true)

        assertFalse(older === newer)
        assertEquals(
            "NotificationCaptureInterlock.SettingRequest",
            older.toString(),
        )
    }

    @Test
    fun `setting mutation and capture boundary cannot overlap`() = runBlocking {
        val interlock = NotificationCaptureInterlock()
        val captureEntered = CompletableDeferred<Unit>()
        val releaseCapture = CompletableDeferred<Unit>()
        val mutationEntered = CompletableDeferred<Unit>()
        val capture = async {
            interlock.withCaptureBoundary {
                captureEntered.complete(Unit)
                releaseCapture.await()
                "stored"
            }
        }
        captureEntered.await()
        val mutation = async {
            interlock.withMutationBoundary {
                mutationEntered.complete(Unit)
            }
        }

        assertFalse(mutationEntered.isCompleted)
        releaseCapture.complete(Unit)
        assertEquals("stored", capture.await())
        mutation.await()
        assertTrue(mutationEntered.isCompleted)
    }

    @Test
    fun `rapid enable then disable persists the newest request and stays closed`() = runBlocking {
        val interlock = NotificationCaptureInterlock()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val persisted = mutableListOf<Boolean>()
        val enable = interlock.requestCaptureEnabled(true)
        val first = async {
            interlock.setCaptureEnabled(enable) { value ->
                firstEntered.complete(Unit)
                releaseFirst.await()
                persisted += value
            }
        }
        firstEntered.await()

        val disable = interlock.requestCaptureEnabled(false)
        val second = async {
            interlock.setCaptureEnabled(disable) { value -> persisted += value }
        }
        assertNull(interlock.prepareCapture())
        releaseFirst.complete(Unit)

        assertFalse(first.await())
        assertTrue(second.await())
        assertEquals(listOf(true, false), persisted)
        assertNull(interlock.withCaptureBoundary { "must stay closed" })
    }

    @Test
    fun `rapid disable then enable opens only after the newest durable write`() = runBlocking {
        val interlock = NotificationCaptureInterlock()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val persisted = mutableListOf<Boolean>()
        val disable = interlock.requestCaptureEnabled(false)
        val first = async {
            interlock.setCaptureEnabled(disable) { value ->
                firstEntered.complete(Unit)
                releaseFirst.await()
                persisted += value
            }
        }
        firstEntered.await()

        val enable = interlock.requestCaptureEnabled(true)
        val second = async {
            interlock.setCaptureEnabled(enable) { value -> persisted += value }
        }
        assertNull(interlock.prepareCapture())
        releaseFirst.complete(Unit)

        assertFalse(first.await())
        assertTrue(second.await())
        assertEquals(listOf(false, true), persisted)
        assertEquals("enabled", interlock.withCaptureBoundary { "enabled" })
    }

    @Test
    fun `owner delete invalidates a listener callback queued before the request`() = runBlocking {
        val interlock = NotificationCaptureInterlock()
        val queued = requireNotNull(interlock.prepareCapture())

        interlock.invalidatePendingCaptures()

        assertNull(interlock.withCaptureBoundary(queued) { "stale write" })
        val afterDeleteRequest = requireNotNull(interlock.prepareCapture())
        assertEquals(
            "new write",
            interlock.withCaptureBoundary(afterDeleteRequest) { "new write" },
        )
    }

    @Test
    fun `disable waits behind an already linearized capture and rejects every later post`() =
        runBlocking {
            val interlock = NotificationCaptureInterlock()
            val prepared = requireNotNull(interlock.prepareCapture())
            val captureEntered = CompletableDeferred<Unit>()
            val releaseCapture = CompletableDeferred<Unit>()
            val capture = async {
                interlock.withCaptureBoundary(prepared) {
                    captureEntered.complete(Unit)
                    releaseCapture.await()
                    "already authorized"
                }
            }
            captureEntered.await()

            val disable = interlock.requestCaptureEnabled(false)
            val persistenceEntered = CompletableDeferred<Unit>()
            val persistence = async {
                interlock.setCaptureEnabled(disable) {
                    persistenceEntered.complete(Unit)
                }
            }
            assertNull(interlock.prepareCapture())
            assertFalse(persistenceEntered.isCompleted)

            releaseCapture.complete(Unit)
            assertEquals("already authorized", capture.await())
            assertTrue(persistence.await())
            assertTrue(persistenceEntered.isCompleted)
            assertNull(interlock.withCaptureBoundary { "late write" })
        }

    @Test
    fun `delete runs after an in flight capture and before stale queued captures`() = runBlocking {
        val interlock = NotificationCaptureInterlock()
        val inFlightRequest = requireNotNull(interlock.prepareCapture())
        val queuedRequest = requireNotNull(interlock.prepareCapture())
        val captureEntered = CompletableDeferred<Unit>()
        val releaseCapture = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val inFlight = async {
            interlock.withCaptureBoundary(inFlightRequest) {
                captureEntered.complete(Unit)
                releaseCapture.await()
                order += "capture"
            }
        }
        captureEntered.await()

        interlock.invalidatePendingCaptures()
        val deletion = async {
            interlock.withMutationBoundary { order += "delete" }
        }
        releaseCapture.complete(Unit)

        inFlight.await()
        deletion.await()
        assertNull(interlock.withCaptureBoundary(queuedRequest) { order += "stale" })
        assertEquals(listOf("capture", "delete"), order)
    }

    @Test
    fun `reply disable closes execution before asynchronous persistence`() = runBlocking {
        val interlock = NotificationCaptureInterlock()
        val persistenceEntered = CompletableDeferred<Unit>()
        val releasePersistence = CompletableDeferred<Unit>()
        val request = interlock.requestReplyEnabled(false)
        val persistence = async {
            interlock.setReplyEnabled(request) {
                persistenceEntered.complete(Unit)
                releasePersistence.await()
            }
        }

        assertFalse(interlock.replyAllowed(durableEnabled = true))
        persistenceEntered.await()
        assertFalse(interlock.replyAllowed(durableEnabled = true))
        releasePersistence.complete(Unit)
        assertTrue(persistence.await())
        assertFalse(interlock.replyAllowed(durableEnabled = true))
    }

    @Test
    fun `rapid reply enable then disable persists newest and stays closed`() = runBlocking {
        val interlock = NotificationCaptureInterlock()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val persisted = mutableListOf<Boolean>()
        val enable = interlock.requestReplyEnabled(true)
        val first = async {
            interlock.setReplyEnabled(enable) { value ->
                firstEntered.complete(Unit)
                releaseFirst.await()
                persisted += value
            }
        }
        firstEntered.await()

        val disable = interlock.requestReplyEnabled(false)
        val second = async {
            interlock.setReplyEnabled(disable) { value -> persisted += value }
        }
        assertFalse(interlock.replyAllowed(durableEnabled = true))
        releaseFirst.complete(Unit)

        assertFalse(first.await())
        assertTrue(second.await())
        assertEquals(listOf(true, false), persisted)
        assertFalse(interlock.replyAllowed(durableEnabled = true))
    }

    @Test
    fun `rapid reply disable then enable opens only after newest durable write`() = runBlocking {
        val interlock = NotificationCaptureInterlock()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val persisted = mutableListOf<Boolean>()
        val disable = interlock.requestReplyEnabled(false)
        val first = async {
            interlock.setReplyEnabled(disable) { value ->
                firstEntered.complete(Unit)
                releaseFirst.await()
                persisted += value
            }
        }
        firstEntered.await()

        val enable = interlock.requestReplyEnabled(true)
        val second = async {
            interlock.setReplyEnabled(enable) { value -> persisted += value }
        }
        assertFalse(interlock.replyAllowed(durableEnabled = true))
        releaseFirst.complete(Unit)

        assertFalse(first.await())
        assertTrue(second.await())
        assertEquals(listOf(false, true), persisted)
        assertTrue(interlock.replyAllowed(durableEnabled = true))
    }

    @Test
    fun `reply disable does not close the independent capture boundary`() = runBlocking {
        val interlock = NotificationCaptureInterlock()
        val captureRequest = requireNotNull(interlock.prepareCapture())

        interlock.requestReplyEnabled(false)

        assertEquals(
            "capture remains enabled",
            interlock.withCaptureBoundary(captureRequest) { "capture remains enabled" },
        )
    }
}
