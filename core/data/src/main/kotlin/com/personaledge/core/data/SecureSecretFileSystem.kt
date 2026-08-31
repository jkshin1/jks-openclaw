package com.personaledge.core.data

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor

/** Minimal filesystem boundary for encrypted credential blobs. Plaintext never enters this API. */
internal interface SecureSecretFileSystem {
    fun atomicReplace(target: File, temporary: File, bytes: ByteArray, maxBytes: Int)
    fun readOrNull(target: File, maxBytes: Int): ByteArray?
    fun exists(target: File): Boolean
    fun remove(target: File): Boolean
    fun clear()
}

/**
 * Android-safe atomic publisher pinned to one app-private, non-symlink directory.
 *
 * Public Android APIs do not expose fd-relative rename/unlink. Fixed allowlisted names are checked
 * with lstat immediately before path operations, while every actual read/write is pinned to an
 * `O_NOFOLLOW` descriptor. A same-UID attacker able to replace paths between those operations is
 * outside the app-private trust boundary; the vault's process mutex closes in-process races.
 */
internal class AndroidSecureSecretFileSystem(
    directory: File,
    private val beforeRename: () -> Unit = {},
) : SecureSecretFileSystem {
    private val directory = directory.absoluteFile
    private val trustedRoot = checkNotNull(this.directory.parentFile).absoluteFile
    private val ownerUid = Process.myUid()
    private val trustedRootStat = Os.lstat(trustedRoot.path).also(::validateTrustedDirectory)
    private val allowedNames = SecretKeyName.entries.flatMapTo(mutableSetOf()) { key ->
        listOf(key.fileName, ".${key.fileName}.tmp")
    }

    init {
        ensureDirectory()
    }

    override fun atomicReplace(
        target: File,
        temporary: File,
        bytes: ByteArray,
        maxBytes: Int,
    ) {
        requireAllowed(target)
        requireAllowed(temporary)
        require(target.name != temporary.name)
        check(bytes.isNotEmpty() && bytes.size <= maxBytes)
        ensureDirectory()

        lstatOrNull(target)?.let(::validatePrivateRegularFile)
        lstatOrNull(temporary)?.let { stale ->
            validatePrivateRegularFile(stale)
            Os.remove(temporary.path)
        }

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
            check(Os.fstat(descriptor).st_size == bytes.size.toLong()) {
                "Secret temporary write was incomplete"
            }
        } finally {
            Os.close(descriptor)
        }

        // Test hook models process interruption after durable temp write. A subsequent call safely
        // validates/removes that complete stale temp while the previous target remains untouched.
        beforeRename()
        validatePrivateRegularFile(checkNotNull(lstatOrNull(temporary)))
        lstatOrNull(target)?.let(::validatePrivateRegularFile)
        Os.rename(temporary.path, target.path)
        val published = checkNotNull(lstatOrNull(target))
        validatePrivateRegularFile(published)
        check(published.st_size == bytes.size.toLong()) { "Secret replace failed" }
        syncDirectory(directory, expected = null)
    }

    override fun readOrNull(target: File, maxBytes: Int): ByteArray? {
        requireAllowed(target)
        require(maxBytes > 0)
        ensureDirectory()
        val pathStat = try {
            lstatOrNull(target)?.also(::validatePrivateRegularFile)
        } catch (_: IllegalStateException) {
            return null
        } ?: return null
        if (pathStat.st_size <= 0L || pathStat.st_size > maxBytes.toLong()) return null

        return try {
            val descriptor = Os.open(
                target.path,
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW or
                    OsConstants.O_NONBLOCK,
                0,
            )
            try {
                val opened = Os.fstat(descriptor)
                validatePrivateRegularFile(opened)
                check(opened.st_dev == pathStat.st_dev && opened.st_ino == pathStat.st_ino) {
                    "Secret changed during open"
                }
                if (opened.st_size != pathStat.st_size) return null
                val bytes = ByteArray(opened.st_size.toInt())
                var offset = 0
                while (offset < bytes.size) {
                    val count = Os.read(descriptor, bytes, offset, bytes.size - offset)
                    if (count <= 0) return null
                    offset += count
                }
                val after = Os.fstat(descriptor)
                check(after.st_size == opened.st_size) { "Secret changed during read" }
                bytes
            } finally {
                Os.close(descriptor)
            }
        } catch (_: ErrnoException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    /**
     * Reports path presence independently of readability.
     *
     * [SecretVault.health] must distinguish a missing credential from an existing ciphertext that
     * is empty, oversized, malformed, permission-weakened, or replaced by an untrusted entry. The
     * latter requires owner repair and must never be presented as a cleanly absent credential.
     */
    override fun exists(target: File): Boolean {
        requireAllowed(target)
        ensureDirectory()
        return lstatOrNull(target) != null
    }

    override fun remove(target: File): Boolean {
        requireAllowed(target)
        ensureDirectory()
        val stat = lstatOrNull(target) ?: return false
        check(!OsConstants.S_ISDIR(stat.st_mode)) { "Secret path is a directory" }
        // unlink(2) never follows a symlink, so owner-requested removal can safely recover from an
        // untrusted entry without touching its referent.
        Os.remove(target.path)
        syncDirectory(directory, expected = null)
        return true
    }

    override fun clear() {
        ensureDirectory()
        val children = checkNotNull(directory.listFiles()) { "Unable to inspect secret directory" }
        var changed = false
        children.forEach { child ->
            val absolute = child.absoluteFile
            check(absolute.parentFile == directory) { "Secret child escaped directory" }
            val stat = lstatOrNull(absolute) ?: return@forEach
            check(!OsConstants.S_ISDIR(stat.st_mode)) { "Nested secret directory refused" }
            Os.remove(absolute.path)
            changed = true
        }
        if (changed) syncDirectory(directory, expected = null)
    }

    private fun ensureDirectory() {
        check(directory.parentFile == trustedRoot) { "Secret directory escaped trusted root" }
        var created = false
        if (lstatOrNull(directory) == null) {
            try {
                Os.mkdir(directory.path, DIRECTORY_MODE)
                created = true
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EEXIST) throw error
            }
        }
        val pathStat = checkNotNull(lstatOrNull(directory))
        validateTrustedDirectory(pathStat)
        syncDirectory(directory, expected = pathStat, chmod = true)
        if (created) syncDirectory(trustedRoot, expected = trustedRootStat)
    }

    private fun syncDirectory(
        value: File,
        expected: StructStat?,
        chmod: Boolean = false,
    ) {
        val descriptor = Os.open(
            value.path,
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
            0,
        )
        try {
            val opened = Os.fstat(descriptor)
            validateTrustedDirectory(opened)
            if (expected != null) {
                check(opened.st_dev == expected.st_dev && opened.st_ino == expected.st_ino) {
                    "Secret directory changed during open"
                }
            }
            if (chmod) {
                Os.fchmod(descriptor, DIRECTORY_MODE)
                val secured = Os.fstat(descriptor)
                validateTrustedDirectory(secured)
                check(secured.st_mode and PERMISSION_MASK == DIRECTORY_MODE) {
                    "Secret directory permissions could not be secured"
                }
            }
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private fun validateTrustedDirectory(stat: StructStat) {
        check(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == ownerUid) {
            "Untrusted secret directory"
        }
    }

    private fun validatePrivateRegularFile(stat: StructStat) {
        check(
            OsConstants.S_ISREG(stat.st_mode) &&
                stat.st_nlink == 1L &&
                stat.st_uid == ownerUid &&
                stat.st_mode and FORBIDDEN_FILE_PERMISSION_BITS == 0,
        ) { "Untrusted secret file" }
    }

    private fun requireAllowed(file: File) {
        val absolute = file.absoluteFile
        require(absolute.parentFile == directory && absolute.name in allowedNames) {
            "Unexpected secret file"
        }
    }

    private fun lstatOrNull(file: File): StructStat? = try {
        Os.lstat(file.path)
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) null else throw error
    }

    private fun writeFully(descriptor: FileDescriptor, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = Os.write(descriptor, bytes, offset, bytes.size - offset)
            check(count > 0) { "Secret write made no progress" }
            offset += count
        }
    }

    private companion object {
        const val FILE_MODE = 0x180 // 0600
        const val DIRECTORY_MODE = 0x1C0 // 0700
        const val PERMISSION_MASK = 0x1FF // 0777
        const val FORBIDDEN_FILE_PERMISSION_BITS = 0x3F // any group/other permission
    }
}
