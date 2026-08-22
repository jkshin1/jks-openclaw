package com.personaledge.core.diagnostics

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

internal interface SecureDiagnosticsFileSystem {
    fun ensureLogDirectory(): File

    /** Returns the direct children of the already-validated diagnostics log directory. */
    fun listLogFiles(): Set<File>

    /** Returns null when absent and throws when the existing node is not a private single-link file. */
    fun secureFileSize(file: File): Long?

    fun append(file: File, bytes: ByteArray, maxFileBytes: Long): AppendResult

    fun remove(file: File)

    fun rename(source: File, target: File)

    fun read(file: File, maxBytes: Int): ByteArray?

    fun atomicReplace(target: File, temporary: File, bytes: ByteArray, maxBytes: Int)
}

internal enum class AppendResult {
    WRITTEN,
    WOULD_EXCEED_CAP,
}

internal data class DiagnosticsPaths(
    val trustedRoot: File,
) {
    val logDirectory = File(trustedRoot, LOG_DIRECTORY_NAME)
    val activeLog = File(logDirectory, ACTIVE_LOG_NAME)
    val archiveLogs = (1..ARCHIVE_COUNT).map { index ->
        File(logDirectory, "diagnostics.$index.jsonl")
    }
    val checkpoint = File(trustedRoot, CHECKPOINT_NAME)
    val checkpointTemporary = File(trustedRoot, CHECKPOINT_TEMPORARY_NAME)

    val allowedLogFiles: Set<File> =
        (listOf(activeLog) + archiveLogs)
            .map(File::getAbsoluteFile)
            .toSet()

    val allowedFiles: Set<File> =
        (allowedLogFiles + listOf(checkpoint, checkpointTemporary))
            .map(File::getAbsoluteFile)
            .toSet()

    companion object {
        const val LOG_DIRECTORY_NAME = "diagnostics"
        const val ACTIVE_LOG_NAME = "diagnostics.jsonl"
        const val ARCHIVE_COUNT = 3
        const val CHECKPOINT_NAME = "diagnostics-exit.checkpoint"
        const val CHECKPOINT_TEMPORARY_NAME = ".diagnostics-exit.checkpoint.tmp"
    }
}

/**
 * Android's public APIs do not offer fd-relative rename/unlink. Every read and append is pinned to
 * one `O_NOFOLLOW` descriptor, while rotation validates fixed app-private names immediately before
 * path operations. A malicious actor already able to race path replacement as this app's UID is
 * outside that boundary; the process-wide recorder mutex prevents races among recorder instances.
 */
internal class AndroidSecureDiagnosticsFileSystem(
    private val paths: DiagnosticsPaths,
) : SecureDiagnosticsFileSystem {
    private val ownerUid = Process.myUid()
    private val trustedRootStat = Os.lstat(paths.trustedRoot.absoluteFile.path)

    init {
        check(
            OsConstants.S_ISDIR(trustedRootStat.st_mode) && trustedRootStat.st_uid == ownerUid,
        ) {
            "Untrusted diagnostics root"
        }
    }

    override fun ensureLogDirectory(): File {
        val directory = paths.logDirectory.absoluteFile
        require(directory.parentFile == paths.trustedRoot.absoluteFile)
        val existing = lstatOrNull(directory)
        if (existing == null) {
            try {
                Os.mkdir(directory.path, DIRECTORY_MODE)
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EEXIST) throw error
            }
        }

        val descriptor = Os.open(
            directory.path,
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
            0,
        )
        try {
            val stat = Os.fstat(descriptor)
            check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == ownerUid) {
                "Untrusted diagnostics directory"
            }
            Os.fchmod(descriptor, DIRECTORY_MODE)
        } finally {
            Os.close(descriptor)
        }

        check(directory.canonicalFile == directory && directory.parentFile == paths.trustedRoot) {
            "Diagnostics directory escaped its trusted root"
        }
        return directory
    }

    override fun listLogFiles(): Set<File> {
        val directory = ensureLogDirectory()
        val children = checkNotNull(directory.listFiles()) {
            "Unable to inspect diagnostics directory"
        }
        return children.mapTo(linkedSetOf()) { child ->
            child.absoluteFile.also { absolute ->
                check(absolute.parentFile == directory) {
                    "Diagnostics child escaped its trusted directory"
                }
            }
        }
    }

    override fun secureFileSize(file: File): Long? {
        requireAllowed(file)
        val stat = lstatOrNull(file) ?: return null
        validatePrivateRegularFile(stat)
        return stat.st_size
    }

    override fun append(file: File, bytes: ByteArray, maxFileBytes: Long): AppendResult {
        requireAllowed(file)
        check(bytes.isNotEmpty() && bytes.size.toLong() <= maxFileBytes)

        // Reject FIFOs/devices before open; fstat below closes the remaining same-UID race window.
        lstatOrNull(file)?.let(::validatePrivateRegularFile)
        val descriptor = Os.open(
            file.path,
            OsConstants.O_RDWR or OsConstants.O_APPEND or OsConstants.O_CREAT or
                OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK,
            FILE_MODE,
        )
        try {
            validatePrivateRegularFile(Os.fstat(descriptor))
            Os.fchmod(descriptor, FILE_MODE)
            val size = repairTrailingPartialLine(descriptor, Os.fstat(descriptor).st_size)
            if (size < 0 || size > maxFileBytes - bytes.size) {
                return AppendResult.WOULD_EXCEED_CAP
            }
            try {
                writeFully(descriptor, bytes)
                Os.fsync(descriptor)
                check(Os.fstat(descriptor).st_size <= maxFileBytes) { "Diagnostics cap exceeded" }
            } catch (failure: Throwable) {
                // A short write (including ENOSPC) must not leave a JSON fragment behind.
                runCatching {
                    Os.ftruncate(descriptor, size)
                    Os.fsync(descriptor)
                }
                throw failure
            }
            return AppendResult.WRITTEN
        } finally {
            Os.close(descriptor)
        }
    }

    override fun remove(file: File) {
        requireAllowed(file)
        secureFileSize(file) ?: return
        Os.remove(file.path)
    }

    override fun rename(source: File, target: File) {
        requireAllowed(source)
        requireAllowed(target)
        check(secureFileSize(source) != null) { "Missing diagnostics rotation source" }
        check(secureFileSize(target) == null) { "Diagnostics rotation target exists" }
        Os.rename(source.path, target.path)
        check(secureFileSize(target) != null) { "Diagnostics rotation validation failed" }
    }

    override fun read(file: File, maxBytes: Int): ByteArray? {
        requireAllowed(file)
        lstatOrNull(file)?.let(::validatePrivateRegularFile) ?: return null
        val descriptor = Os.open(
            file.path,
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or
                OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK,
            0,
        )
        try {
            val stat = Os.fstat(descriptor)
            validatePrivateRegularFile(stat)
            if (stat.st_size !in 0..maxBytes.toLong()) return ByteArray(0)
            val buffer = ByteArray(maxBytes + 1)
            var offset = 0
            while (offset < buffer.size) {
                val count = Os.read(descriptor, buffer, offset, buffer.size - offset)
                if (count == 0) break
                offset += count
            }
            if (offset > maxBytes) return ByteArray(0)
            return buffer.copyOf(offset)
        } finally {
            Os.close(descriptor)
        }
    }

    override fun atomicReplace(
        target: File,
        temporary: File,
        bytes: ByteArray,
        maxBytes: Int,
    ) {
        requireAllowed(target)
        requireAllowed(temporary)
        check(bytes.isNotEmpty() && bytes.size <= maxBytes)
        if (secureFileSize(temporary) != null) remove(temporary)

        val descriptor = Os.open(
            temporary.path,
            OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK,
            FILE_MODE,
        )
        try {
            validatePrivateRegularFile(Os.fstat(descriptor))
            Os.fchmod(descriptor, FILE_MODE)
            writeFully(descriptor, bytes)
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }

        secureFileSize(temporary)
        // rename(2) atomically replaces a previously validated regular target.
        secureFileSize(target)
        Os.rename(temporary.path, target.path)
        check(secureFileSize(target) == bytes.size.toLong()) { "Checkpoint replace failed" }
        syncRootDirectory()
    }

    private fun validatePrivateRegularFile(stat: android.system.StructStat) {
        check(
            OsConstants.S_ISREG(stat.st_mode) &&
                stat.st_nlink == 1L &&
                stat.st_uid == ownerUid &&
                stat.st_mode and FORBIDDEN_FILE_PERMISSION_BITS == 0,
        ) { "Untrusted diagnostics file" }
    }

    private fun requireAllowed(file: File) {
        require(file.absoluteFile in paths.allowedFiles) { "Unexpected diagnostics file" }
    }

    private fun lstatOrNull(file: File): android.system.StructStat? = try {
        Os.lstat(file.path)
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) null else throw error
    }

    private fun writeFully(descriptor: java.io.FileDescriptor, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = Os.write(descriptor, bytes, offset, bytes.size - offset)
            check(count > 0) { "Diagnostics write made no progress" }
            offset += count
        }
    }

    private fun repairTrailingPartialLine(
        descriptor: java.io.FileDescriptor,
        originalSize: Long,
    ): Long {
        if (originalSize <= 0) return 0
        val lastByte = ByteArray(1)
        check(readFullyAt(descriptor, lastByte, originalSize - 1) == 1)
        if (lastByte[0] == '\n'.code.toByte()) return originalSize

        val block = ByteArray(REPAIR_SCAN_BLOCK_BYTES)
        var endExclusive = originalSize
        while (endExclusive > 0) {
            val start = (endExclusive - block.size).coerceAtLeast(0)
            val length = (endExclusive - start).toInt()
            check(readFullyAt(descriptor, block, start, length) == length)
            for (index in length - 1 downTo 0) {
                if (block[index] == '\n'.code.toByte()) {
                    val repairedSize = start + index + 1
                    Os.ftruncate(descriptor, repairedSize)
                    Os.fsync(descriptor)
                    return repairedSize
                }
            }
            endExclusive = start
        }
        Os.ftruncate(descriptor, 0)
        Os.fsync(descriptor)
        return 0
    }

    private fun readFullyAt(
        descriptor: java.io.FileDescriptor,
        destination: ByteArray,
        fileOffset: Long,
        length: Int = destination.size,
    ): Int {
        var total = 0
        while (total < length) {
            val count = Os.pread(
                descriptor,
                destination,
                total,
                length - total,
                fileOffset + total,
            )
            if (count == 0) break
            total += count
        }
        return total
    }

    private fun syncRootDirectory() {
        val descriptor = Os.open(
            paths.trustedRoot.path,
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
            0,
        )
        try {
            val stat = Os.fstat(descriptor)
            check(
                OsConstants.S_ISDIR(stat.st_mode) &&
                    stat.st_uid == ownerUid &&
                    stat.st_dev == trustedRootStat.st_dev &&
                    stat.st_ino == trustedRootStat.st_ino,
            ) { "Diagnostics root changed" }
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private companion object {
        const val FILE_MODE = 0x180 // 0600
        const val DIRECTORY_MODE = 0x1C0 // 0700
        const val FORBIDDEN_FILE_PERMISSION_BITS = 0x3F // any group/other permission
        const val REPAIR_SCAN_BLOCK_BYTES = 4 * 1024
    }
}
