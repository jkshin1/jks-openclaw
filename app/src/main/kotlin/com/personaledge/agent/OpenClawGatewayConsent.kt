package com.personaledge.agent

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.personaledge.core.data.SettingsRepository
import java.io.File
import java.io.FileDescriptor
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Content-free startup barrier checked in addition to durable consent and the process interlock. */
internal enum class OpenClawGatewayRevocationBarrierState {
    BLOCKED,
    CLEAR,
}

internal interface OpenClawGatewayRevocationGate {
    val revocationBarrierState: StateFlow<OpenClawGatewayRevocationBarrierState>
}

/** A random CAS identity. Its bytes are never exposed through logs, state, or diagnostics. */
internal class OpenClawGatewayRevocationIntent private constructor(
    private val token: String,
) {
    internal fun encode(): ByteArray = "$MARKER_PREFIX$token\n".toByteArray(Charsets.US_ASCII)

    internal fun sameIdentity(other: OpenClawGatewayRevocationIntent): Boolean =
        token == other.token

    override fun equals(other: Any?): Boolean =
        other is OpenClawGatewayRevocationIntent && token == other.token

    override fun hashCode(): Int = token.hashCode()

    override fun toString(): String = "OpenClawGatewayRevocationIntent(<redacted>)"

    internal companion object {
        private const val MARKER_PREFIX = "personal-edge-openclaw-revocation-v1\n"
        private const val TOKEN_HEX_CHARACTERS = 32
        private val TOKEN_PATTERN = Regex("[0-9A-F]{$TOKEN_HEX_CHARACTERS}")
        private val HEX = "0123456789ABCDEF".toCharArray()
        internal const val ENCODED_BYTES =
            37 + TOKEN_HEX_CHARACTERS + 1 // marker prefix (including newline), token, newline

        fun generate(random: SecureRandom = SecureRandom()): OpenClawGatewayRevocationIntent {
            val bytes = ByteArray(TOKEN_HEX_CHARACTERS / 2)
            random.nextBytes(bytes)
            val token = CharArray(TOKEN_HEX_CHARACTERS)
            bytes.forEachIndexed { index, value ->
                val unsigned = value.toInt() and 0xff
                token[index * 2] = HEX[unsigned ushr 4]
                token[index * 2 + 1] = HEX[unsigned and 0x0f]
            }
            bytes.fill(0)
            return OpenClawGatewayRevocationIntent(String(token))
        }

        fun decode(bytes: ByteArray): OpenClawGatewayRevocationIntent? {
            if (bytes.size != ENCODED_BYTES) return null
            val value = runCatching { bytes.toString(Charsets.US_ASCII) }.getOrNull() ?: return null
            if (!value.startsWith(MARKER_PREFIX) || !value.endsWith('\n')) return null
            val token = value.substring(MARKER_PREFIX.length, value.length - 1)
            return token.takeIf(TOKEN_PATTERN::matches)?.let(::OpenClawGatewayRevocationIntent)
        }
    }
}

internal sealed interface OpenClawGatewayRevocationSnapshot {
    data object Absent : OpenClawGatewayRevocationSnapshot
    data object Unclear : OpenClawGatewayRevocationSnapshot

    class Present(internal val intent: OpenClawGatewayRevocationIntent) :
        OpenClawGatewayRevocationSnapshot {
        override fun toString(): String = "OpenClawGatewayRevocationSnapshot.Present(<redacted>)"
    }
}

/** Narrow seam for the fixed, app-private, content-free revocation marker. */
internal interface OpenClawGatewayRevocationJournal {
    suspend fun snapshot(): OpenClawGatewayRevocationSnapshot
    suspend fun publish(intent: OpenClawGatewayRevocationIntent): Boolean
    suspend fun clearIfMatches(intent: OpenClawGatewayRevocationIntent): Boolean
}

/** Strict read is intentionally distinct from SettingsRepository's safe-default public flow. */
internal interface OpenClawGatewayConsentSettingsStore {
    suspend fun readEnabledStrict(): Boolean
    suspend fun writeEnabled(enabled: Boolean)
}

/** Pure permission predicate kept JVM-testable; file type still comes from lstat/fstat. */
internal object OpenClawGatewayRevocationFilePolicy {
    private const val PERMISSION_MASK = 0x1FF
    private const val PRIVATE_DIRECTORY_MODE = 0x1C0 // 0700
    private const val PRIVATE_FILE_MODE = 0x180 // 0600

    /** Android creates app-private roots such as no_backup as 0771 and relies on UID/SELinux. */
    fun isTrustedNoBackupRoot(
        isDirectory: Boolean,
        actualUid: Int,
        ownerUid: Int,
        mode: Int,
    ): Boolean {
        val permissions = mode and PERMISSION_MASK
        return isDirectory && actualUid == ownerUid &&
            (permissions == PRIVATE_DIRECTORY_MODE ||
                permissions == PLATFORM_PRIVATE_DIRECTORY_MODE)
    }

    /** Our fixed child is stricter than the platform root and must stay owner-only (0700). */
    fun isPrivateStateDirectory(
        isDirectory: Boolean,
        actualUid: Int,
        ownerUid: Int,
        mode: Int,
    ): Boolean = isDirectory && actualUid == ownerUid &&
        mode and PERMISSION_MASK == PRIVATE_DIRECTORY_MODE

    fun isPrivateRegularFile(
        isRegularFile: Boolean,
        linkCount: Long,
        actualUid: Int,
        ownerUid: Int,
        mode: Int,
    ): Boolean = isRegularFile && linkCount == 1L && actualUid == ownerUid &&
        mode and PERMISSION_MASK == PRIVATE_FILE_MODE

    private const val PLATFORM_PRIVATE_DIRECTORY_MODE = 0x1F9 // 0771, ContextImpl default
}

internal class SettingsRepositoryOpenClawGatewayConsentStore(
    private val repository: SettingsRepository,
) : OpenClawGatewayConsentSettingsStore {
    override suspend fun readEnabledStrict(): Boolean =
        repository.currentOpenClawGatewayEnabledForRecovery()

    override suspend fun writeEnabled(enabled: Boolean) {
        repository.setOpenClawGatewayEnabled(enabled)
    }

    override fun toString(): String = "SettingsRepositoryOpenClawGatewayConsentStore"
}

/**
 * OpenClaw-only durable consent controller.
 *
 * Every mutation closes the process gate and the startup barrier before doing I/O. Normally a
 * marker is then atomically published and fsynced before DataStore is touched. If publication
 * cannot be proved, enable never writes true; disable still best-effort writes false but remains a
 * failed, blocked operation. Successful DataStore commit is necessary but not sufficient to
 * reopen: the exact marker token must be removed and its directory fsynced while the request is
 * still the latest epoch. A stale operation can therefore leave the feature conservatively
 * blocked, but cannot clear a newer operation's marker or reopen the Gateway.
 *
 * A process killed before the marker publish completes cannot receive a successful mutation
 * result. No userspace API can make that pre-fsync interval durable; callers must not present an
 * enable/disable operation as complete until its returned [Deferred] reports `APPLIED`.
 */
internal class OpenClawGatewayConsentManager(
    private val interlock: OwnerConsentInterlock,
    private val settingsStore: OpenClawGatewayConsentSettingsStore,
    private val journal: OpenClawGatewayRevocationJournal,
    private val mutationScope: CoroutineScope,
    private val intentFactory: () -> OpenClawGatewayRevocationIntent =
        OpenClawGatewayRevocationIntent::generate,
) : OpenClawGatewayRevocationGate {
    private val stateLock = Any()
    private val mutationMutex = Mutex()
    private val mutableBarrier = MutableStateFlow(OpenClawGatewayRevocationBarrierState.BLOCKED)
    private var epoch = 0L
    private val startupRequest = interlock.requestEnabled(
        OwnerConsentFeature.OPENCLAW_GATEWAY,
        enabled = false,
    )

    override val revocationBarrierState: StateFlow<OpenClawGatewayRevocationBarrierState> =
        mutableBarrier.asStateFlow()

    private val startupRecovery: Deferred<OwnerConsentMutationOutcome> = mutationScope.async {
        mutationMutex.withLock { recoverAtStartup(expectedEpoch = 0L) }
    }

    /** Eagerly created with this manager; exposed so process startup and tests can await it. */
    fun recoverBeforeGateway(): Deferred<OwnerConsentMutationOutcome> = startupRecovery

    /**
     * Closes the in-process gate synchronously, then performs the durable mutation in app scope.
     */
    fun setEnabled(enabled: Boolean): Deferred<OwnerConsentMutationOutcome> {
        val operation = synchronized(stateLock) {
            epoch += 1L
            mutableBarrier.value = OpenClawGatewayRevocationBarrierState.BLOCKED
            Mutation(
                epoch = epoch,
                request = interlock.requestEnabled(
                    OwnerConsentFeature.OPENCLAW_GATEWAY,
                    enabled,
                ),
            )
        }
        return mutationScope.async {
            mutationMutex.withLock { applyMutation(operation) }
        }
    }

    override fun toString(): String =
        "OpenClawGatewayConsentManager(barrier=${revocationBarrierState.value})"

    private suspend fun recoverAtStartup(expectedEpoch: Long): OwnerConsentMutationOutcome {
        if (!isCurrent(expectedEpoch)) return OwnerConsentMutationOutcome.SUPERSEDED
        return when (val snapshot = safeSnapshot()) {
            OpenClawGatewayRevocationSnapshot.Absent -> {
                val durableEnabled = try {
                    settingsStore.readEnabledStrict()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return recoverFalseWithFreshMarker(expectedEpoch)
                }
                if (durableEnabled) {
                    restoreDurableEnableWithFreshMarker(expectedEpoch)
                } else if (clearBarrierIfCurrent(expectedEpoch)) {
                    OwnerConsentMutationOutcome.APPLIED
                } else {
                    OwnerConsentMutationOutcome.SUPERSEDED
                }
            }

            is OpenClawGatewayRevocationSnapshot.Present ->
                recoverFalseWithKnownMarker(expectedEpoch, snapshot.intent)

            OpenClawGatewayRevocationSnapshot.Unclear -> {
                // Try to make the intent canonical, but never erase or bypass an entry that the
                // journal could not authenticate. A successful false commit remains closed until
                // the fixed path is repaired and a later recovery can prove it clear.
                val replacement = safeNewIntent()
                val published = replacement != null && safePublish(replacement)
                val persisted = persistStartupFalse(expectedEpoch)
                if (published && persisted == OwnerConsentMutationOutcome.APPLIED) {
                    finishAfterFalse(expectedEpoch, replacement)
                } else {
                    if (persisted == OwnerConsentMutationOutcome.SUPERSEDED) {
                        OwnerConsentMutationOutcome.SUPERSEDED
                    } else {
                        OwnerConsentMutationOutcome.FAILED
                    }
                }
            }
        }
    }

    private suspend fun restoreDurableEnableWithFreshMarker(
        expectedEpoch: Long,
    ): OwnerConsentMutationOutcome {
        val intent = safeNewIntent() ?: return OwnerConsentMutationOutcome.FAILED
        if (!safePublish(intent)) return OwnerConsentMutationOutcome.FAILED
        if (!isCurrent(expectedEpoch)) return OwnerConsentMutationOutcome.SUPERSEDED

        val request = synchronized(stateLock) {
            if (epoch != expectedEpoch) return OwnerConsentMutationOutcome.SUPERSEDED
            interlock.requestEnabled(
                OwnerConsentFeature.OPENCLAW_GATEWAY,
                enabled = true,
            )
        }
        val persisted = interlock.persistLatest(request) { _, _ ->
            settingsStore.writeEnabled(true)
        }
        if (persisted != OwnerConsentMutationOutcome.APPLIED) return persisted
        return finishAfterEnable(expectedEpoch, intent)
    }

    private suspend fun recoverFalseWithFreshMarker(
        expectedEpoch: Long,
    ): OwnerConsentMutationOutcome {
        val intent = safeNewIntent()
        if (intent == null || !safePublish(intent)) {
            // Still attempt the requested fail-closed DataStore repair. The process barrier and
            // interlock remain closed because no authenticated marker can be cleared.
            val persisted = persistStartupFalse(expectedEpoch)
            return if (persisted == OwnerConsentMutationOutcome.SUPERSEDED) {
                persisted
            } else {
                OwnerConsentMutationOutcome.FAILED
            }
        }
        return recoverFalseWithKnownMarker(expectedEpoch, intent)
    }

    private suspend fun recoverFalseWithKnownMarker(
        expectedEpoch: Long,
        intent: OpenClawGatewayRevocationIntent,
    ): OwnerConsentMutationOutcome {
        val persisted = persistStartupFalse(expectedEpoch)
        if (persisted != OwnerConsentMutationOutcome.APPLIED) return persisted
        return finishAfterFalse(expectedEpoch, intent)
    }

    private suspend fun persistStartupFalse(expectedEpoch: Long): OwnerConsentMutationOutcome {
        if (!isCurrent(expectedEpoch)) return OwnerConsentMutationOutcome.SUPERSEDED
        return interlock.persistLatest(startupRequest) { _, _ ->
            settingsStore.writeEnabled(false)
        }
    }

    private suspend fun applyMutation(operation: Mutation): OwnerConsentMutationOutcome {
        if (!isCurrent(operation.epoch)) return OwnerConsentMutationOutcome.SUPERSEDED
        val intent = safeNewIntent()
            ?: return failAfterBestEffortFalse(operation)
        if (!safePublish(intent)) return failAfterBestEffortFalse(operation)
        if (!isCurrent(operation.epoch)) return OwnerConsentMutationOutcome.SUPERSEDED

        val persisted = interlock.persistLatest(operation.request) { _, enabled ->
            settingsStore.writeEnabled(enabled)
        }
        if (persisted != OwnerConsentMutationOutcome.APPLIED) return persisted
        return if (operation.request.enabled) {
            finishAfterEnable(operation.epoch, intent)
        } else {
            finishAfterFalse(operation.epoch, intent)
        }
    }

    private suspend fun failAfterBestEffortFalse(
        operation: Mutation,
    ): OwnerConsentMutationOutcome {
        if (operation.request.enabled) return OwnerConsentMutationOutcome.FAILED

        // A missing or failed marker publish cannot make disable successful, but skipping this
        // conservative DataStore repair would knowingly leave a stale durable true. The barrier
        // stays blocked and any pre-existing, partial, or unclear marker remains untouched.
        val persisted = interlock.persistLatest(operation.request) { _, _ ->
            settingsStore.writeEnabled(false)
        }
        return if (persisted == OwnerConsentMutationOutcome.SUPERSEDED) {
            persisted
        } else {
            OwnerConsentMutationOutcome.FAILED
        }
    }

    private suspend fun finishAfterEnable(
        expectedEpoch: Long,
        intent: OpenClawGatewayRevocationIntent,
    ): OwnerConsentMutationOutcome = finishAfterDurableCommit(expectedEpoch, intent)

    private suspend fun finishAfterFalse(
        expectedEpoch: Long,
        intent: OpenClawGatewayRevocationIntent,
    ): OwnerConsentMutationOutcome = finishAfterDurableCommit(expectedEpoch, intent)

    private suspend fun finishAfterDurableCommit(
        expectedEpoch: Long,
        intent: OpenClawGatewayRevocationIntent,
    ): OwnerConsentMutationOutcome {
        if (!isCurrent(expectedEpoch)) return OwnerConsentMutationOutcome.SUPERSEDED
        if (!safeClear(intent)) return OwnerConsentMutationOutcome.FAILED
        return if (clearBarrierIfCurrent(expectedEpoch)) {
            OwnerConsentMutationOutcome.APPLIED
        } else {
            OwnerConsentMutationOutcome.SUPERSEDED
        }
    }

    private fun clearBarrierIfCurrent(expectedEpoch: Long): Boolean = synchronized(stateLock) {
        if (epoch != expectedEpoch) {
            false
        } else {
            mutableBarrier.value = OpenClawGatewayRevocationBarrierState.CLEAR
            true
        }
    }

    private fun isCurrent(expectedEpoch: Long): Boolean = synchronized(stateLock) {
        epoch == expectedEpoch
    }

    private fun safeNewIntent(): OpenClawGatewayRevocationIntent? = try {
        intentFactory()
    } catch (_: Exception) {
        null
    }

    private suspend fun safeSnapshot(): OpenClawGatewayRevocationSnapshot = try {
        journal.snapshot()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        OpenClawGatewayRevocationSnapshot.Unclear
    }

    private suspend fun safePublish(intent: OpenClawGatewayRevocationIntent): Boolean = try {
        journal.publish(intent)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun safeClear(intent: OpenClawGatewayRevocationIntent): Boolean = try {
        journal.clearIfMatches(intent)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private class Mutation(
        val epoch: Long,
        val request: OwnerConsentInterlock.SettingRequest,
    )
}

/**
 * Atomic Android implementation pinned to a fixed 0700 child and two fixed file names beneath
 * `noBackupFilesDir`.
 *
 * A complete temporary marker is itself a revocation intent. If the process died after its file
 * fsync but before rename, [snapshot] reports that intent and startup stays blocked. No path,
 * endpoint, prompt, credential, or error text enters the marker.
 */
internal class AndroidOpenClawGatewayRevocationJournal(
    noBackupFilesDir: File,
) : OpenClawGatewayRevocationJournal {
    private val trustedRoot = noBackupFilesDir.absoluteFile
    private val directory = File(trustedRoot, STATE_DIRECTORY_NAME).absoluteFile
    private val target = File(directory, MARKER_NAME).absoluteFile
    private val temporary = File(directory, TEMPORARY_NAME).absoluteFile
    private val ownerUid = Process.myUid()
    private val operationLock = Any()

    override suspend fun snapshot(): OpenClawGatewayRevocationSnapshot = synchronized(operationLock) {
        inspectLocked()
    }

    override suspend fun publish(intent: OpenClawGatewayRevocationIntent): Boolean =
        synchronized(operationLock) {
            try {
                publishLocked(intent)
            } catch (_: Exception) {
                false
            }
        }

    override suspend fun clearIfMatches(intent: OpenClawGatewayRevocationIntent): Boolean =
        synchronized(operationLock) {
            try {
                clearLocked(intent)
            } catch (_: Exception) {
                false
            }
        }

    override fun toString(): String = "AndroidOpenClawGatewayRevocationJournal"

    private fun inspectLocked(): OpenClawGatewayRevocationSnapshot = try {
        validateDirectory()
        val temporaryStat = lstatOrNull(temporary)
        val targetStat = lstatOrNull(target)
        if (temporaryStat == null && targetStat == null) {
            return OpenClawGatewayRevocationSnapshot.Absent
        }
        if (temporaryStat != null) {
            validatePrivateRegularFile(temporaryStat)
            targetStat?.let(::validatePrivateRegularFile)
            val intent = readIntent(temporary, temporaryStat)
                ?: return OpenClawGatewayRevocationSnapshot.Unclear
            return OpenClawGatewayRevocationSnapshot.Present(intent)
        }
        checkNotNull(targetStat)
        validatePrivateRegularFile(targetStat)
        readIntent(target, targetStat)
            ?.let(OpenClawGatewayRevocationSnapshot::Present)
            ?: OpenClawGatewayRevocationSnapshot.Unclear
    } catch (_: Exception) {
        OpenClawGatewayRevocationSnapshot.Unclear
    }

    private fun publishLocked(intent: OpenClawGatewayRevocationIntent): Boolean {
        validateDirectory()
        lstatOrNull(target)?.let(::validatePrivateRegularFile)

        // A complete temp file survived a crash before rename. Publish it first so replacing it
        // with this operation's temp never creates a durable no-marker interval.
        lstatOrNull(temporary)?.let { stale ->
            validatePrivateRegularFile(stale)
            check(readIntent(temporary, stale) != null) { "Untrusted revocation temporary" }
            Os.rename(temporary.path, target.path)
            syncDirectory()
        }

        val bytes = intent.encode()
        check(bytes.size == OpenClawGatewayRevocationIntent.ENCODED_BYTES)
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
            check(Os.fstat(descriptor).st_size == bytes.size.toLong())
        } finally {
            Os.close(descriptor)
            bytes.fill(0)
        }

        validatePrivateRegularFile(checkNotNull(lstatOrNull(temporary)))
        lstatOrNull(target)?.let(::validatePrivateRegularFile)
        Os.rename(temporary.path, target.path)
        val published = checkNotNull(lstatOrNull(target))
        validatePrivateRegularFile(published)
        check(readIntent(target, published)?.sameIdentity(intent) == true)
        syncDirectory()
        return true
    }

    private fun clearLocked(intent: OpenClawGatewayRevocationIntent): Boolean {
        validateDirectory()
        val temporaryStat = lstatOrNull(temporary)
        val targetStat = lstatOrNull(target)
        if (temporaryStat != null) {
            validatePrivateRegularFile(temporaryStat)
            if (readIntent(temporary, temporaryStat)?.sameIdentity(intent) != true) return false
            targetStat?.let(::validatePrivateRegularFile)
            // Remove target first. A crash before temp unlink therefore still leaves the newer,
            // authoritative intent visible to startup recovery.
            if (targetStat != null) Os.remove(target.path)
            Os.remove(temporary.path)
            syncDirectory()
            return true
        }
        if (targetStat == null) return false
        validatePrivateRegularFile(targetStat)
        if (readIntent(target, targetStat)?.sameIdentity(intent) != true) return false
        Os.remove(target.path)
        syncDirectory()
        return true
    }

    private fun readIntent(file: File, pathStat: StructStat): OpenClawGatewayRevocationIntent? {
        if (pathStat.st_size != OpenClawGatewayRevocationIntent.ENCODED_BYTES.toLong()) return null
        val descriptor = Os.open(
            file.path,
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW or
                OsConstants.O_NONBLOCK,
            0,
        )
        return try {
            val opened = Os.fstat(descriptor)
            validatePrivateRegularFile(opened)
            check(opened.st_dev == pathStat.st_dev && opened.st_ino == pathStat.st_ino)
            check(opened.st_size == pathStat.st_size)
            val bytes = ByteArray(opened.st_size.toInt())
            try {
                var offset = 0
                while (offset < bytes.size) {
                    val count = Os.read(descriptor, bytes, offset, bytes.size - offset)
                    if (count <= 0) return null
                    offset += count
                }
                check(Os.fstat(descriptor).st_size == opened.st_size)
                OpenClawGatewayRevocationIntent.decode(bytes)
            } finally {
                bytes.fill(0)
            }
        } finally {
            Os.close(descriptor)
        }
    }

    private fun validateDirectory() {
        ensureDirectory()
        check(target.parentFile == directory && temporary.parentFile == directory)
    }

    private fun ensureDirectory() {
        check(trustedRoot.isAbsolute && directory.parentFile == trustedRoot)
        validateTrustedRoot(Os.lstat(trustedRoot.path))
        if (lstatOrNull(directory) == null) {
            try {
                Os.mkdir(directory.path, STATE_DIRECTORY_MODE)
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EEXIST) throw error
            }
        }
        val stateDirectory = checkNotNull(lstatOrNull(directory))
        validatePrivateStateDirectory(stateDirectory)
        syncDirectory(directory, stateDirectory)
        // Also covers a directory left by a process that died between mkdir and parent fsync.
        syncDirectory(trustedRoot, expected = null, isTrustedRoot = true)
    }

    private fun syncDirectory(
        value: File = directory,
        expected: StructStat? = null,
        isTrustedRoot: Boolean = false,
    ) {
        val pathStat = expected ?: Os.lstat(value.path).also { stat ->
            if (isTrustedRoot) validateTrustedRoot(stat) else validatePrivateStateDirectory(stat)
        }
        val descriptor = Os.open(
            value.path,
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
            0,
        )
        try {
            val opened = Os.fstat(descriptor)
            if (isTrustedRoot) validateTrustedRoot(opened) else validatePrivateStateDirectory(opened)
            check(opened.st_dev == pathStat.st_dev && opened.st_ino == pathStat.st_ino)
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private fun validateTrustedRoot(stat: StructStat) {
        check(
            OpenClawGatewayRevocationFilePolicy.isTrustedNoBackupRoot(
                isDirectory = OsConstants.S_ISDIR(stat.st_mode),
                actualUid = stat.st_uid,
                ownerUid = ownerUid,
                mode = stat.st_mode,
            ),
        ) {
            "Untrusted no-backup root"
        }
    }

    private fun validatePrivateStateDirectory(stat: StructStat) {
        check(
            OpenClawGatewayRevocationFilePolicy.isPrivateStateDirectory(
                isDirectory = OsConstants.S_ISDIR(stat.st_mode),
                actualUid = stat.st_uid,
                ownerUid = ownerUid,
                mode = stat.st_mode,
            ),
        ) {
            "Untrusted revocation state directory"
        }
    }

    private fun validatePrivateRegularFile(stat: StructStat) {
        check(
            OpenClawGatewayRevocationFilePolicy.isPrivateRegularFile(
                isRegularFile = OsConstants.S_ISREG(stat.st_mode),
                linkCount = stat.st_nlink,
                actualUid = stat.st_uid,
                ownerUid = ownerUid,
                mode = stat.st_mode,
            ),
        ) { "Untrusted revocation marker" }
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
            check(count > 0) { "Revocation marker write made no progress" }
            offset += count
        }
    }

    private companion object {
        const val STATE_DIRECTORY_NAME = ".openclaw-gateway-revocation-state"
        const val MARKER_NAME = ".openclaw-gateway-revocation"
        const val TEMPORARY_NAME = ".openclaw-gateway-revocation.tmp"
        const val STATE_DIRECTORY_MODE = 0x1C0 // 0700
        const val FILE_MODE = 0x180 // 0600
    }
}
