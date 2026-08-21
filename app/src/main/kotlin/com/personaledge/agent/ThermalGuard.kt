package com.personaledge.agent

import android.content.Context
import android.os.PowerManager
import com.personaledge.core.diagnostics.DiagnosticThermalStatus
import com.personaledge.core.diagnostics.DiagnosticTurnCancellationCause
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Relaxed Fold8 policy for every build: Android NONE through SEVERE remain usable. Android's
 * OEM-calibrated CRITICAL boundary, rather than a hard-coded Celsius value, stops an active turn.
 * EMERGENCY and above also request matching native cancellation immediately.
 */
internal object ThermalTurnPolicy {
    fun canStart(status: DiagnosticThermalStatus): Boolean =
        directive(status) == ThermalDirective.CONTINUE

    fun directive(status: DiagnosticThermalStatus): ThermalDirective = when (status) {
        DiagnosticThermalStatus.CRITICAL -> ThermalDirective.COOPERATIVE_CANCEL
        DiagnosticThermalStatus.EMERGENCY,
        DiagnosticThermalStatus.SHUTDOWN,
        DiagnosticThermalStatus.UNKNOWN -> ThermalDirective.IMMEDIATE_ABORT
        DiagnosticThermalStatus.NONE,
        DiagnosticThermalStatus.LIGHT,
        DiagnosticThermalStatus.MODERATE,
        DiagnosticThermalStatus.SEVERE -> ThermalDirective.CONTINUE
    }
}

internal enum class ThermalDirective(val urgency: Int) {
    CONTINUE(0),
    COOPERATIVE_CANCEL(1),
    IMMEDIATE_ABORT(2),
}

/**
 * stopSequence never decreases. A brief stop-level observation therefore remains visible even if
 * the public status has already cooled before a coroutine collector resumes.
 */
internal data class ThermalObservation(
    val status: DiagnosticThermalStatus,
    val directive: ThermalDirective,
    val stopSequence: Long,
)

internal data class ThermalStopDecision(
    val status: DiagnosticThermalStatus,
    val directive: ThermalDirective,
)

/** Turn-scoped highest-urgency latch; cooling never clears an already observed stop directive. */
internal class ThermalTurnLatch(
    val baselineStopSequence: Long,
) {
    private val stopDecision = AtomicReference<ThermalStopDecision?>(null)

    fun currentDecision(): ThermalStopDecision? = stopDecision.get()

    fun apply(observation: ThermalObservation): ThermalStopDecision? {
        if (observation.stopSequence <= baselineStopSequence) return null
        if (observation.directive == ThermalDirective.CONTINUE) return null
        while (true) {
            val applied = stopDecision.get()
            if (applied != null && observation.directive.urgency <= applied.directive.urgency) {
                return null
            }
            val next = ThermalStopDecision(observation.status, observation.directive)
            if (stopDecision.compareAndSet(applied, next)) return next
        }
    }
}

/** Thermal mitigation wins a same-turn race with a user or lifecycle cancellation. */
internal class TurnCancellationCauseLatch {
    private val cause = AtomicReference<DiagnosticTurnCancellationCause?>(null)

    fun record(candidate: DiagnosticTurnCancellationCause) {
        while (true) {
            val current = cause.get()
            if (current != null && candidate.priority <= current.priority) return
            if (cause.compareAndSet(current, candidate)) return
        }
    }

    fun current(): DiagnosticTurnCancellationCause? = cause.get()
}

internal interface ThermalStatusSource {
    fun current(): DiagnosticThermalStatus

    fun subscribe(listener: (DiagnosticThermalStatus) -> Unit): AutoCloseable
}

internal class AndroidThermalStatusSource(
    context: Context,
    private val callbackExecutor: Executor = context.mainExecutor,
) : ThermalStatusSource {
    private val powerManager = requireNotNull(
        (context.applicationContext ?: context).getSystemService(PowerManager::class.java),
    )

    override fun current(): DiagnosticThermalStatus =
        powerManager.currentThermalStatus.toDiagnosticThermalStatus()

    override fun subscribe(listener: (DiagnosticThermalStatus) -> Unit): AutoCloseable {
        val platformListener = PowerManager.OnThermalStatusChangedListener { status ->
            listener(status.toDiagnosticThermalStatus())
        }
        powerManager.addThermalStatusListener(callbackExecutor, platformListener)
        return AutoCloseable {
            powerManager.removeThermalStatusListener(platformListener)
        }
    }
}

internal class ThermalStatusMonitor private constructor(
    private val source: ThermalStatusSource,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val observationLock = Any()
    private val initialStatus = runCatching(source::current)
        .getOrDefault(DiagnosticThermalStatus.UNKNOWN)
    private val mutableObservation = MutableStateFlow(
        ThermalObservation(
            status = initialStatus,
            directive = ThermalTurnPolicy.directive(initialStatus),
            stopSequence = if (ThermalTurnPolicy.canStart(initialStatus)) 0 else 1,
        ),
    )
    val observation: StateFlow<ThermalObservation> = mutableObservation.asStateFlow()
    private val statusEventsChannel = Channel<ThermalObservation>(Channel.UNLIMITED)
    /** Ordered status transitions for diagnostics; unlike StateFlow, rapid transitions are not conflated. */
    val statusEvents: Flow<ThermalObservation> = statusEventsChannel.receiveAsFlow()
    private val directiveListener = AtomicReference<((ThermalObservation) -> Unit)?>(null)

    private val subscriptionResult = runCatching {
        source.subscribe { observed ->
            publish(observed)
        }
    }
    private val subscription: AutoCloseable = subscriptionResult.getOrElse { AutoCloseable { } }

    init {
        // Close the read-before-register race with a fresh value after listener registration.
        if (subscriptionResult.isSuccess) refresh() else publish(DiagnosticThermalStatus.UNKNOWN)
    }

    /** Re-reads the platform immediately before a turn; a change callback is not assumed. */
    fun refresh(): ThermalObservation {
        val observed = if (subscriptionResult.isSuccess) {
            runCatching(source::current).getOrDefault(DiagnosticThermalStatus.UNKNOWN)
        } else {
            DiagnosticThermalStatus.UNKNOWN
        }
        return publish(observed)
    }

    /** The ViewModel owns exactly one O(1) directive callback for the monitor lifetime. */
    fun setDirectiveListener(listener: (ThermalObservation) -> Unit): AutoCloseable {
        check(directiveListener.compareAndSet(null, listener))
        return AutoCloseable {
            directiveListener.compareAndSet(listener, null)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            directiveListener.set(null)
            runCatching(subscription::close)
            statusEventsChannel.close()
        }
    }

    private fun publish(status: DiagnosticThermalStatus): ThermalObservation {
        val directive = ThermalTurnPolicy.directive(status)
        var statusChanged = false
        val next = synchronized(observationLock) {
            if (closed.get()) return@synchronized mutableObservation.value
            val previous = mutableObservation.value
            statusChanged = previous.status != status
            val nextSequence = if (directive == ThermalDirective.CONTINUE) {
                previous.stopSequence
            } else {
                saturatedIncrement(previous.stopSequence)
            }
            ThermalObservation(status, directive, nextSequence).also {
                mutableObservation.value = it
            }
        }
        if (!closed.get() && statusChanged) {
            statusEventsChannel.trySend(next)
        }
        if (!closed.get() && directive != ThermalDirective.CONTINUE) {
            directiveListener.get()?.invoke(next)
        }
        return next
    }

    companion object {
        fun create(context: Context): ThermalStatusMonitor = runCatching {
            ThermalStatusMonitor(AndroidThermalStatusSource(context))
        }.getOrElse {
            ThermalStatusMonitor(UnknownThermalStatusSource)
        }

        internal fun createForTest(source: ThermalStatusSource): ThermalStatusMonitor =
            ThermalStatusMonitor(source)
    }
}

private fun Int.toDiagnosticThermalStatus(): DiagnosticThermalStatus = when (this) {
    PowerManager.THERMAL_STATUS_NONE -> DiagnosticThermalStatus.NONE
    PowerManager.THERMAL_STATUS_LIGHT -> DiagnosticThermalStatus.LIGHT
    PowerManager.THERMAL_STATUS_MODERATE -> DiagnosticThermalStatus.MODERATE
    PowerManager.THERMAL_STATUS_SEVERE -> DiagnosticThermalStatus.SEVERE
    PowerManager.THERMAL_STATUS_CRITICAL -> DiagnosticThermalStatus.CRITICAL
    PowerManager.THERMAL_STATUS_EMERGENCY -> DiagnosticThermalStatus.EMERGENCY
    PowerManager.THERMAL_STATUS_SHUTDOWN -> DiagnosticThermalStatus.SHUTDOWN
    else -> DiagnosticThermalStatus.UNKNOWN
}

private object UnknownThermalStatusSource : ThermalStatusSource {
    override fun current(): DiagnosticThermalStatus = DiagnosticThermalStatus.UNKNOWN

    override fun subscribe(listener: (DiagnosticThermalStatus) -> Unit): AutoCloseable =
        AutoCloseable { }
}

private fun saturatedIncrement(value: Long): Long =
    if (value == Long.MAX_VALUE) Long.MAX_VALUE else value + 1

private val DiagnosticTurnCancellationCause.priority: Int
    get() = when (this) {
        DiagnosticTurnCancellationCause.THERMAL -> 3
        DiagnosticTurnCancellationCause.USER -> 2
        DiagnosticTurnCancellationCause.LIFECYCLE -> 1
        DiagnosticTurnCancellationCause.UNKNOWN -> 0
    }
