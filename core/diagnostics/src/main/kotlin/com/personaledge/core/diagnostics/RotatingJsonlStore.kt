package com.personaledge.core.diagnostics

import java.nio.charset.StandardCharsets

internal class RotatingJsonlStore(
    private val paths: DiagnosticsPaths,
    private val fileSystem: SecureDiagnosticsFileSystem,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
) {
    init {
        require(maxFileBytes > 1)
        fileSystem.ensureLogDirectory()
    }

    fun append(json: String): Boolean {
        val line = (json + "\n").toByteArray(StandardCharsets.UTF_8)
        if (line.size.toLong() > maxFileBytes) return false

        val sizes = (listOf(paths.activeLog) + paths.archiveLogs)
            .map(fileSystem::secureFileSize)
        if (sizes.any { it != null && it > maxFileBytes }) return false

        return when (fileSystem.append(paths.activeLog, line, maxFileBytes)) {
            AppendResult.WRITTEN -> true
            AppendResult.WOULD_EXCEED_CAP -> {
                rotate()
                fileSystem.append(paths.activeLog, line, maxFileBytes) == AppendResult.WRITTEN
            }
        }
    }

    private fun rotate() {
        // Validate every fixed name before the first mutation. A symlink, hardlink, or non-regular
        // node therefore fails the entire append without moving or unlinking any path.
        val existing = (listOf(paths.activeLog) + paths.archiveLogs)
            .associateWith(fileSystem::secureFileSize)

        val oldest = paths.archiveLogs.last()
        if (existing.getValue(oldest) != null) fileSystem.remove(oldest)

        for (index in paths.archiveLogs.lastIndex downTo 1) {
            val source = paths.archiveLogs[index - 1]
            val target = paths.archiveLogs[index]
            if (existing.getValue(source) != null) fileSystem.rename(source, target)
        }
        if (existing.getValue(paths.activeLog) != null) {
            fileSystem.rename(paths.activeLog, paths.archiveLogs.first())
        }
    }

    companion object {
        const val DEFAULT_MAX_FILE_BYTES: Long = 5L * 1024 * 1024
    }
}
