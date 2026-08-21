package com.personaledge.core.diagnostics

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

internal class TestSecureDiagnosticsFileSystem(
    private val paths: DiagnosticsPaths,
) : SecureDiagnosticsFileSystem {
    override fun ensureLogDirectory(): File {
        val directory = paths.logDirectory.absoluteFile
        check(directory.parentFile == paths.trustedRoot.absoluteFile)
        if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(directory.toPath())
        }
        check(!Files.isSymbolicLink(directory.toPath()))
        check(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
        check(directory.canonicalFile == directory)
        setPermissions(directory, DIRECTORY_PERMISSIONS)
        return directory
    }

    override fun secureFileSize(file: File): Long? {
        requireAllowed(file)
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        check(attributes.isRegularFile && !attributes.isSymbolicLink)
        val linkCount = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number
        check(linkCount.toLong() == 1L)
        return attributes.size()
    }

    override fun append(file: File, bytes: ByteArray, maxFileBytes: Long): AppendResult {
        requireAllowed(file)
        secureFileSize(file)
        FileChannel.open(
            file.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        ).use { channel ->
            setPermissions(file, FILE_PERMISSIONS)
            check(secureFileSize(file) != null)
            repairTrailingPartialLine(channel)
            if (channel.size() > maxFileBytes - bytes.size) return AppendResult.WOULD_EXCEED_CAP
            val originalSize = channel.size()
            channel.position(originalSize)
            val buffer = ByteBuffer.wrap(bytes)
            try {
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
                check(channel.size() <= maxFileBytes)
            } catch (failure: Throwable) {
                channel.truncate(originalSize)
                channel.force(true)
                throw failure
            }
        }
        return AppendResult.WRITTEN
    }

    override fun remove(file: File) {
        requireAllowed(file)
        secureFileSize(file) ?: return
        Files.delete(file.toPath())
    }

    override fun rename(source: File, target: File) {
        requireAllowed(source)
        requireAllowed(target)
        check(secureFileSize(source) != null)
        check(secureFileSize(target) == null)
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        check(secureFileSize(target) != null)
    }

    override fun read(file: File, maxBytes: Int): ByteArray? {
        requireAllowed(file)
        val size = secureFileSize(file) ?: return null
        if (size > maxBytes) return ByteArray(0)
        return Files.readAllBytes(file.toPath()).let { bytes ->
            if (bytes.size > maxBytes) ByteArray(0) else bytes
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
        check(bytes.size in 1..maxBytes)
        if (secureFileSize(temporary) != null) remove(temporary)
        FileChannel.open(
            temporary.toPath(),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { channel ->
            setPermissions(temporary, FILE_PERMISSIONS)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        secureFileSize(temporary)
        secureFileSize(target)
        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        check(secureFileSize(target) == bytes.size.toLong())
    }

    private fun requireAllowed(file: File) {
        require(file.absoluteFile in paths.allowedFiles)
    }

    private fun setPermissions(file: File, permissions: Set<PosixFilePermission>) {
        Files.setPosixFilePermissions(file.toPath(), permissions)
    }

    private fun repairTrailingPartialLine(channel: FileChannel) {
        val size = channel.size()
        if (size == 0L) return
        val oneByte = ByteBuffer.allocate(1)
        channel.read(oneByte, size - 1)
        if (oneByte.array()[0] == '\n'.code.toByte()) return

        val buffer = ByteBuffer.allocate(4096)
        var endExclusive = size
        while (endExclusive > 0) {
            val start = (endExclusive - buffer.capacity()).coerceAtLeast(0)
            val length = (endExclusive - start).toInt()
            buffer.clear().limit(length)
            var total = 0
            while (total < length) {
                val count = channel.read(buffer, start + total)
                if (count <= 0) break
                total += count
            }
            val bytes = buffer.array()
            for (index in total - 1 downTo 0) {
                if (bytes[index] == '\n'.code.toByte()) {
                    channel.truncate(start + index + 1)
                    channel.force(true)
                    return
                }
            }
            endExclusive = start
        }
        channel.truncate(0)
        channel.force(true)
    }

    private companion object {
        val FILE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        val DIRECTORY_PERMISSIONS = FILE_PERMISSIONS + PosixFilePermission.OWNER_EXECUTE
    }
}
