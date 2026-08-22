package com.personaledge.core.diagnostics

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
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

    /**
     * Captures one immutable, bounded view of every rotated JSONL source. The recorder mutex must
     * be held by the caller, so rotation cannot interleave between validation and descriptor reads.
     */
    fun snapshotForExport(): DiagnosticLogSnapshot {
        val directoryChildren = fileSystem.listLogFiles()
        check(directoryChildren.all { it in paths.allowedLogFiles }) {
            "Unexpected diagnostics path"
        }

        val chronologicalFiles = paths.archiveLogs.asReversed() + paths.activeLog
        val sizes = chronologicalFiles.associateWith(fileSystem::secureFileSize)
        check(sizes.values.all { size ->
            size == null || size in 0..maxFileBytes && size <= MAX_EXPORT_FILE_BYTES
        }) { "Diagnostics export source exceeds its cap" }

        var totalBytes = 0L
        val parts = buildList {
            chronologicalFiles.forEach { file ->
                val expectedSize = sizes.getValue(file) ?: return@forEach
                if (expectedSize == 0L) return@forEach
                val bytes = checkNotNull(fileSystem.read(file, MAX_EXPORT_FILE_BYTES.toInt())) {
                    "Diagnostics export source disappeared"
                }
                check(bytes.size.toLong() == expectedSize) {
                    "Diagnostics export source changed during snapshot"
                }
                validateExportJsonl(bytes)
                totalBytes = Math.addExact(totalBytes, bytes.size.toLong())
                check(totalBytes <= MAX_EXPORT_BYTES) { "Diagnostics export exceeds its cap" }
                add(bytes)
            }
        }
        return DiagnosticLogSnapshot(parts = parts, byteCount = totalBytes)
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
        const val MAX_EXPORT_FILE_BYTES: Long = DEFAULT_MAX_FILE_BYTES
        const val MAX_EXPORT_BYTES: Long =
            MAX_EXPORT_FILE_BYTES * (DiagnosticsPaths.ARCHIVE_COUNT + 1L)

        private const val MAX_EXPORT_RECORD_BYTES = 4 * 1024

        private fun validateExportJsonl(bytes: ByteArray) {
            // Decoder instances are stateful, so duplicate the configuration for every snapshot.
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))

            check(bytes.isNotEmpty() && bytes.last() == '\n'.code.toByte()) {
                "Incomplete diagnostics JSONL tail"
            }
            var lineStart = 0
            bytes.indices.forEach { index ->
                val unsigned = bytes[index].toInt() and 0xff
                if (unsigned == '\n'.code) {
                    check(index > lineStart) { "Blank diagnostics JSONL record" }
                    val recordByteCount = index - lineStart
                    check(recordByteCount <= MAX_EXPORT_RECORD_BYTES) {
                        "Diagnostics JSONL record exceeds its cap"
                    }
                    val record = String(
                        bytes,
                        lineStart,
                        recordByteCount,
                        StandardCharsets.UTF_8,
                    )
                    DiagnosticExportJsonValidator.validate(record)
                    lineStart = index + 1
                } else {
                    check(unsigned >= 0x20) { "Raw control byte in diagnostics JSONL" }
                }
            }
            check(lineStart == bytes.size) { "Incomplete diagnostics JSONL source" }
        }
    }
}

internal data class DiagnosticLogSnapshot(
    val parts: List<ByteArray>,
    val byteCount: Long,
)
