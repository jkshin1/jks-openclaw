package com.personaledge.core.diagnostics

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal class ExitCheckpointStore(
    private val paths: DiagnosticsPaths,
    private val fileSystem: SecureDiagnosticsFileSystem,
) {
    fun load(): ExitCheckpoint {
        val bytes = fileSystem.read(paths.checkpoint, MAX_CHECKPOINT_BYTES) ?: return ExitCheckpoint.EMPTY
        return decode(bytes) ?: ExitCheckpoint.EMPTY
    }

    fun save(checkpoint: ExitCheckpoint) {
        val encoded = encode(checkpoint)
        check(encoded.size <= MAX_CHECKPOINT_BYTES)
        fileSystem.atomicReplace(
            target = paths.checkpoint,
            temporary = paths.checkpointTemporary,
            bytes = encoded,
            maxBytes = MAX_CHECKPOINT_BYTES,
        )
    }

    private fun encode(checkpoint: ExitCheckpoint): ByteArray = buildString {
        append(CHECKPOINT_VERSION).append('\n')
        append(checkpoint.maxTimestampMillis).append('\n')
        checkpoint.identitiesAtMaxTimestamp.sorted().forEach { identity ->
            append(identity).append('\n')
        }
    }.toByteArray(StandardCharsets.US_ASCII)

    private fun decode(bytes: ByteArray): ExitCheckpoint? {
        val text = bytes.toString(StandardCharsets.US_ASCII)
        if (!text.all { it == '\n' || it.code in 0x20..0x7e }) return null
        val lines = text.lineSequence().filter(String::isNotEmpty).toList()
        if (lines.size !in 2..(MAX_IDENTITIES_AT_TIMESTAMP + 2)) return null
        if (lines[0] != CHECKPOINT_VERSION) return null
        val timestamp = lines[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val identities = lines.drop(2)
        if (identities.any { !IDENTITY_PATTERN.matches(it) }) return null
        return ExitCheckpoint(timestamp, identities.toSet())
    }

    private companion object {
        const val CHECKPOINT_VERSION = "1"
        const val MAX_CHECKPOINT_BYTES = 4 * 1024
        val IDENTITY_PATTERN = Regex("[0-9a-f]{64}")
    }
}

internal data class ExitCheckpoint(
    val maxTimestampMillis: Long,
    val identitiesAtMaxTimestamp: Set<String>,
) {
    fun contains(exit: HistoricalExitRecord): Boolean {
        val identity = exit.stableIdentitySha256()
        return exit.timestampMillis < maxTimestampMillis ||
            (exit.timestampMillis == maxTimestampMillis && identity in identitiesAtMaxTimestamp)
    }

    fun advance(exit: HistoricalExitRecord): ExitCheckpoint {
        val identity = exit.stableIdentitySha256()
        return when {
            exit.timestampMillis > maxTimestampMillis -> ExitCheckpoint(
                maxTimestampMillis = exit.timestampMillis,
                identitiesAtMaxTimestamp = setOf(identity),
            )
            exit.timestampMillis == maxTimestampMillis -> copy(
                identitiesAtMaxTimestamp =
                    (identitiesAtMaxTimestamp + identity).take(MAX_IDENTITIES_AT_TIMESTAMP).toSet(),
            )
            else -> this
        }
    }

    companion object {
        val EMPTY = ExitCheckpoint(-1, emptySet())
    }
}

internal fun HistoricalExitRecord.stableIdentitySha256(): String {
    val canonical = buildString(192) {
        append(reason.name).append('|')
        append(status).append('|')
        append(importance).append('|')
        append(pssBytes).append('|')
        append(rssBytes).append('|')
        append(timestampMillis).append('|')
        append(phase?.name.orEmpty())
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.US_ASCII))
        .toLowerHex()
}

private fun ByteArray.toLowerHex(): String {
    val alphabet = "0123456789abcdef"
    return buildString(size * 2) {
        this@toLowerHex.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(alphabet[unsigned ushr 4])
            append(alphabet[unsigned and 0x0f])
        }
    }
}

private const val MAX_IDENTITIES_AT_TIMESTAMP = 32
