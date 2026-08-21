package com.personaledge.core.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Debug
import android.os.PowerManager

fun interface HistoricalExitProvider {
    fun load(maxCount: Int): List<HistoricalExitRecord>
}

fun interface ResourceSnapshotProvider {
    fun snapshot(): ResourceMetrics
}

fun interface ProcessStateSummaryWriter {
    fun mark(phase: DiagnosticPhase)
}

data class DiagnosticProviders(
    val historicalExits: HistoricalExitProvider,
    val resources: ResourceSnapshotProvider,
    val phaseSummary: ProcessStateSummaryWriter,
)

class Api31HistoricalExitProvider(
    context: Context,
) : HistoricalExitProvider {
    private val applicationContext = context.applicationContext ?: context

    override fun load(maxCount: Int): List<HistoricalExitRecord> {
        val boundedCount = maxCount.coerceIn(1, MAX_HISTORICAL_EXIT_RECORDS)
        val activityManager = requireNotNull(
            applicationContext.getSystemService(ActivityManager::class.java),
        )
        return activityManager.getHistoricalProcessExitReasons(
            applicationContext.packageName,
            0,
            boundedCount,
        ).map { info ->
            HistoricalExitRecord(
                reason = info.reason.toDiagnosticExitReason(),
                status = info.status,
                importance = info.importance,
                pssBytes = kibibytesToBytes(info.pss),
                rssBytes = kibibytesToBytes(info.rss),
                timestampMillis = info.timestamp.coerceAtLeast(0),
                phase = DiagnosticPhaseCodec.decode(info.processStateSummary),
            )
        }
    }
}

class Api31ResourceSnapshotProvider(
    context: Context,
) : ResourceSnapshotProvider {
    private val applicationContext = context.applicationContext ?: context

    override fun snapshot(): ResourceMetrics {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        val runtime = Runtime.getRuntime()
        val powerManager = requireNotNull(
            applicationContext.getSystemService(PowerManager::class.java),
        )
        return ResourceMetrics(
            pssBytes = kibibytesToBytes(memoryInfo.totalPss.toLong()),
            javaHeapBytes = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0),
            thermalStatus = powerManager.currentThermalStatus.toDiagnosticThermalStatus(),
        )
    }
}

class Api31ProcessStateSummaryWriter(
    context: Context,
) : ProcessStateSummaryWriter {
    private val applicationContext = context.applicationContext ?: context

    override fun mark(phase: DiagnosticPhase) {
        val activityManager = requireNotNull(
            applicationContext.getSystemService(ActivityManager::class.java),
        )
        activityManager.setProcessStateSummary(DiagnosticPhaseCodec.encode(phase))
    }
}

internal object DiagnosticPhaseCodec {
    private const val VERSION: Byte = 1

    fun encode(phase: DiagnosticPhase): ByteArray = byteArrayOf(VERSION, phase.code)

    fun decode(summary: ByteArray?): DiagnosticPhase? {
        if (summary == null || summary.size != 2 || summary[0] != VERSION) return null
        return DiagnosticPhase.entries.firstOrNull { it.code == summary[1] }
    }

    private val DiagnosticPhase.code: Byte
        get() = when (this) {
            DiagnosticPhase.PROCESS_START -> 1
            DiagnosticPhase.SESSION_START -> 2
            DiagnosticPhase.MODEL_INSPECT -> 3
            DiagnosticPhase.MODEL_IMPORT -> 4
            DiagnosticPhase.RUNTIME_INITIALIZATION -> 5
            DiagnosticPhase.TURN_PROCESSING -> 6
            DiagnosticPhase.TOOL_CONFIRMATION -> 7
            DiagnosticPhase.TOOL_EXECUTION -> 8
            DiagnosticPhase.IDLE -> 9
            DiagnosticPhase.SHUTDOWN -> 10
        }
}

internal fun Int.toDiagnosticExitReason(): DiagnosticExitReason = when (this) {
    ApplicationExitInfo.REASON_EXIT_SELF -> DiagnosticExitReason.EXIT_SELF
    ApplicationExitInfo.REASON_SIGNALED -> DiagnosticExitReason.SIGNALED
    ApplicationExitInfo.REASON_LOW_MEMORY -> DiagnosticExitReason.LOW_MEMORY
    ApplicationExitInfo.REASON_CRASH -> DiagnosticExitReason.CRASH
    ApplicationExitInfo.REASON_CRASH_NATIVE -> DiagnosticExitReason.CRASH_NATIVE
    ApplicationExitInfo.REASON_ANR -> DiagnosticExitReason.ANR
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> DiagnosticExitReason.INITIALIZATION_FAILURE
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> DiagnosticExitReason.PERMISSION_CHANGE
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> DiagnosticExitReason.EXCESSIVE_RESOURCE_USAGE
    ApplicationExitInfo.REASON_USER_REQUESTED -> DiagnosticExitReason.USER_REQUESTED
    ApplicationExitInfo.REASON_USER_STOPPED -> DiagnosticExitReason.USER_STOPPED
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> DiagnosticExitReason.DEPENDENCY_DIED
    ApplicationExitInfo.REASON_OTHER -> DiagnosticExitReason.OTHER
    ApplicationExitInfo.REASON_FREEZER -> DiagnosticExitReason.FREEZER
    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> DiagnosticExitReason.PACKAGE_STATE_CHANGE
    ApplicationExitInfo.REASON_PACKAGE_UPDATED -> DiagnosticExitReason.PACKAGE_UPDATED
    else -> DiagnosticExitReason.UNKNOWN
}

internal fun Int.toDiagnosticThermalStatus(): DiagnosticThermalStatus = when (this) {
    PowerManager.THERMAL_STATUS_NONE -> DiagnosticThermalStatus.NONE
    PowerManager.THERMAL_STATUS_LIGHT -> DiagnosticThermalStatus.LIGHT
    PowerManager.THERMAL_STATUS_MODERATE -> DiagnosticThermalStatus.MODERATE
    PowerManager.THERMAL_STATUS_SEVERE -> DiagnosticThermalStatus.SEVERE
    PowerManager.THERMAL_STATUS_CRITICAL -> DiagnosticThermalStatus.CRITICAL
    PowerManager.THERMAL_STATUS_EMERGENCY -> DiagnosticThermalStatus.EMERGENCY
    PowerManager.THERMAL_STATUS_SHUTDOWN -> DiagnosticThermalStatus.SHUTDOWN
    else -> DiagnosticThermalStatus.UNKNOWN
}

private fun kibibytesToBytes(kibibytes: Long): Long = when {
    kibibytes <= 0 -> 0
    kibibytes > Long.MAX_VALUE / 1024 -> Long.MAX_VALUE
    else -> kibibytes * 1024
}

internal const val MAX_HISTORICAL_EXIT_RECORDS = 32
