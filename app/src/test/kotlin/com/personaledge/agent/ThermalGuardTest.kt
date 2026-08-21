package com.personaledge.agent

import com.personaledge.core.diagnostics.DiagnosticThermalStatus
import com.personaledge.core.diagnostics.DiagnosticTurnCancellationCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class ThermalGuardTest {
    @Test
    fun `policy allows severe and stops at critical`() {
        assertTrue(ThermalTurnPolicy.canStart(DiagnosticThermalStatus.NONE))
        assertTrue(ThermalTurnPolicy.canStart(DiagnosticThermalStatus.LIGHT))
        assertTrue(ThermalTurnPolicy.canStart(DiagnosticThermalStatus.MODERATE))
        assertTrue(ThermalTurnPolicy.canStart(DiagnosticThermalStatus.SEVERE))
        assertEquals(
            ThermalDirective.COOPERATIVE_CANCEL,
            ThermalTurnPolicy.directive(DiagnosticThermalStatus.CRITICAL),
        )
        assertFalse(ThermalTurnPolicy.canStart(DiagnosticThermalStatus.CRITICAL))
        listOf(
            DiagnosticThermalStatus.EMERGENCY,
            DiagnosticThermalStatus.SHUTDOWN,
            DiagnosticThermalStatus.UNKNOWN,
        ).forEach { status ->
            assertFalse(ThermalTurnPolicy.canStart(status))
            assertEquals(ThermalDirective.IMMEDIATE_ABORT, ThermalTurnPolicy.directive(status))
        }
    }

    @Test
    fun `monitor closes registration race and unregisters exact listener`() {
        val source = FakeThermalStatusSource(
            currentValues = ArrayDeque(
                listOf(DiagnosticThermalStatus.NONE, DiagnosticThermalStatus.MODERATE),
            ),
        )
        val monitor = ThermalStatusMonitor.createForTest(source)

        assertEquals(DiagnosticThermalStatus.MODERATE, monitor.observation.value.status)
        source.emit(DiagnosticThermalStatus.SEVERE)
        assertEquals(DiagnosticThermalStatus.SEVERE, monitor.observation.value.status)

        monitor.close()
        monitor.close()
        assertEquals(1, source.closeCount)
        source.emit(DiagnosticThermalStatus.NONE)
        assertEquals(DiagnosticThermalStatus.SEVERE, monitor.observation.value.status)
    }

    @Test
    fun `listener or current status failure blocks inference as unknown`() {
        val registrationFailure = ThermalStatusMonitor.createForTest(
            object : ThermalStatusSource {
                override fun current(): DiagnosticThermalStatus = DiagnosticThermalStatus.NONE

                override fun subscribe(listener: (DiagnosticThermalStatus) -> Unit): AutoCloseable =
                    throw IllegalStateException("listener unavailable")
            },
        )
        assertEquals(DiagnosticThermalStatus.UNKNOWN, registrationFailure.observation.value.status)
        assertFalse(ThermalTurnPolicy.canStart(registrationFailure.refresh().status))

        var currentCalls = 0
        val currentFailure = ThermalStatusMonitor.createForTest(
            object : ThermalStatusSource {
                override fun current(): DiagnosticThermalStatus {
                    currentCalls++
                    if (currentCalls == 1) return DiagnosticThermalStatus.NONE
                    throw IllegalStateException("status unavailable")
                }

                override fun subscribe(listener: (DiagnosticThermalStatus) -> Unit): AutoCloseable =
                    AutoCloseable { }
            },
        )
        assertEquals(DiagnosticThermalStatus.UNKNOWN, currentFailure.observation.value.status)
        assertFalse(ThermalTurnPolicy.canStart(currentFailure.refresh().status))
    }

    @Test
    fun `brief critical observation remains latched after cooling`() {
        val source = FakeThermalStatusSource(
            currentValues = ArrayDeque(
                listOf(DiagnosticThermalStatus.NONE, DiagnosticThermalStatus.NONE),
            ),
        )
        val monitor = ThermalStatusMonitor.createForTest(source)
        val baseline = monitor.observation.value.stopSequence
        val directives = mutableListOf<ThermalDirective>()
        val listener = monitor.setDirectiveListener { directives += it.directive }

        source.emit(DiagnosticThermalStatus.CRITICAL)
        source.emit(DiagnosticThermalStatus.NONE)

        val cooled = monitor.observation.value
        assertEquals(DiagnosticThermalStatus.NONE, cooled.status)
        assertTrue(cooled.stopSequence > baseline)
        assertEquals(listOf(ThermalDirective.COOPERATIVE_CANCEL), directives)
        listener.close()
    }

    @Test
    fun `rapid severe cooling remains ordered in diagnostic status events`() = runTest {
        val source = FakeThermalStatusSource(
            currentValues = ArrayDeque(
                listOf(DiagnosticThermalStatus.NONE, DiagnosticThermalStatus.NONE),
            ),
        )
        val monitor = ThermalStatusMonitor.createForTest(source)

        source.emit(DiagnosticThermalStatus.SEVERE)
        source.emit(DiagnosticThermalStatus.NONE)

        assertEquals(
            listOf(DiagnosticThermalStatus.SEVERE, DiagnosticThermalStatus.NONE),
            monitor.statusEvents.take(2).map { it.status }.toList(),
        )
        monitor.close()
    }

    @Test
    fun `emergency escalation is delivered after critical without losing urgency`() {
        val source = FakeThermalStatusSource(
            currentValues = ArrayDeque(
                listOf(DiagnosticThermalStatus.MODERATE, DiagnosticThermalStatus.MODERATE),
            ),
        )
        val monitor = ThermalStatusMonitor.createForTest(source)
        val observations = mutableListOf<ThermalObservation>()
        monitor.setDirectiveListener(observations::add)

        source.emit(DiagnosticThermalStatus.SEVERE)
        source.emit(DiagnosticThermalStatus.CRITICAL)
        source.emit(DiagnosticThermalStatus.EMERGENCY)

        assertEquals(
            listOf(ThermalDirective.COOPERATIVE_CANCEL, ThermalDirective.IMMEDIATE_ABORT),
            observations.map(ThermalObservation::directive),
        )
        assertTrue(observations[1].stopSequence > observations[0].stopSequence)
    }

    @Test
    fun `turn latch applies each higher urgency once and rejects stale observations`() {
        val latch = ThermalTurnLatch(baselineStopSequence = 7)

        assertEquals(
            null,
            latch.apply(
                ThermalObservation(
                    DiagnosticThermalStatus.EMERGENCY,
                    ThermalDirective.IMMEDIATE_ABORT,
                    stopSequence = 7,
                ),
            ),
        )
        assertEquals(
            ThermalStopDecision(
                DiagnosticThermalStatus.CRITICAL,
                ThermalDirective.COOPERATIVE_CANCEL,
            ),
            latch.apply(
                ThermalObservation(
                    DiagnosticThermalStatus.CRITICAL,
                    ThermalDirective.COOPERATIVE_CANCEL,
                    stopSequence = 8,
                ),
            ),
        )
        assertEquals(
            null,
            latch.apply(
                ThermalObservation(
                    DiagnosticThermalStatus.CRITICAL,
                    ThermalDirective.COOPERATIVE_CANCEL,
                    stopSequence = 9,
                ),
            ),
        )
        assertEquals(
            null,
            latch.apply(
                ThermalObservation(
                    DiagnosticThermalStatus.NONE,
                    ThermalDirective.CONTINUE,
                    stopSequence = 9,
                ),
            ),
        )
        assertEquals(
            ThermalStopDecision(
                DiagnosticThermalStatus.EMERGENCY,
                ThermalDirective.IMMEDIATE_ABORT,
            ),
            latch.apply(
                ThermalObservation(
                    DiagnosticThermalStatus.EMERGENCY,
                    ThermalDirective.IMMEDIATE_ABORT,
                    stopSequence = 10,
                ),
            ),
        )

        assertEquals(
            ThermalStopDecision(
                DiagnosticThermalStatus.EMERGENCY,
                ThermalDirective.IMMEDIATE_ABORT,
            ),
            latch.currentDecision(),
        )
    }

    @Test
    fun `thermal cancellation cause overrides user and lifecycle races`() {
        val latch = TurnCancellationCauseLatch()

        latch.record(DiagnosticTurnCancellationCause.LIFECYCLE)
        latch.record(DiagnosticTurnCancellationCause.USER)
        latch.record(DiagnosticTurnCancellationCause.THERMAL)
        latch.record(DiagnosticTurnCancellationCause.USER)

        assertEquals(DiagnosticTurnCancellationCause.THERMAL, latch.current())
    }

    private class FakeThermalStatusSource(
        private val currentValues: ArrayDeque<DiagnosticThermalStatus>,
    ) : ThermalStatusSource {
        private var listener: ((DiagnosticThermalStatus) -> Unit)? = null
        var closeCount: Int = 0
            private set

        override fun current(): DiagnosticThermalStatus =
            if (currentValues.size > 1) currentValues.removeFirst() else currentValues.first()

        override fun subscribe(listener: (DiagnosticThermalStatus) -> Unit): AutoCloseable {
            this.listener = listener
            return AutoCloseable {
                closeCount++
                this.listener = null
            }
        }

        fun emit(status: DiagnosticThermalStatus) {
            listener?.invoke(status)
        }
    }
}
