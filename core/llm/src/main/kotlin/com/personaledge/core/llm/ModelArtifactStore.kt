package com.personaledge.core.llm

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val FILE_PERMISSION_MASK = 0x1FF
private const val POINTER_PERMISSION_MODE = 0x180 // 0600

data class ModelManifest(
    val schemaVersion: Long,
    val repository: String,
    val revision: String,
    val file: String,
    val sizeBytes: Long,
    val sha256: String,
    val litertLmVersion: String,
    val contextTokens: Int,
    val maxOutputTokens: Int,
    /**
     * Declared multimodal prefill support for this exact pinned artifact.
     *
     * These are trust-root facts, not runtime probes: the runtime refuses an image or audio turn
     * unless the manifest the store verified declares that modality. A text-only artifact
     * therefore cannot be handed media even if the caller asks.
     */
    val supportsImageInput: Boolean,
    val supportsAudioInput: Boolean,
)

object PinnedModelManifest {
    val value = ModelManifest(
        schemaVersion = BuildConfig.MODEL_SCHEMA_VERSION,
        repository = BuildConfig.MODEL_REPOSITORY,
        revision = BuildConfig.MODEL_REVISION,
        file = BuildConfig.MODEL_FILE,
        sizeBytes = BuildConfig.MODEL_SIZE_BYTES,
        sha256 = BuildConfig.MODEL_SHA256,
        litertLmVersion = BuildConfig.MODEL_LITERT_LM_VERSION,
        contextTokens = BuildConfig.MODEL_CONTEXT_TOKENS,
        maxOutputTokens = BuildConfig.MODEL_MAX_OUTPUT_TOKENS,
        supportsImageInput = BuildConfig.MODEL_SUPPORTS_IMAGE_INPUT,
        supportsAudioInput = BuildConfig.MODEL_SUPPORTS_AUDIO_INPUT,
    )
}

class VerifiedInstalledModel internal constructor(
    internal val file: File,
    internal val inode: Long,
    val manifest: ModelManifest,
) {
    /**
     * Keeps a reference FD to the verified inode for runtime comparisons. LiteRT-LM 0.16.1
     * requires a `.litertlm`-suffixed path and exposes no Android FD API, so the runtime also
     * sandwiches synchronous native initialization between path/inode checks. This detects a
     * stable replacement but cannot eliminate a transient swap by malicious same-UID code.
     */
    internal fun acquireRuntimeLease(): VerifiedModelLease {
        if (manifest != PinnedModelManifest.value) {
            throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
        }

        val absoluteFile = file.absoluteFile
        val canonicalFile = try {
            absoluteFile.canonicalFile
        } catch (_: Exception) {
            throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
        }
        if (
            canonicalFile != absoluteFile ||
            canonicalFile.name != "${manifest.sha256}.litertlm"
        ) {
            throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
        }

        val descriptor = try {
            Os.open(
                canonicalFile.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,
                0,
            )
        } catch (_: Exception) {
            throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
        }

        try {
            val descriptorStat = Os.fstat(descriptor)
            val pathStat = Os.lstat(canonicalFile.absolutePath)
            if (
                !OsConstants.S_ISREG(descriptorStat.st_mode) ||
                !OsConstants.S_ISREG(pathStat.st_mode) ||
                descriptorStat.st_ino != inode ||
                pathStat.st_ino != inode ||
                descriptorStat.st_dev != pathStat.st_dev ||
                descriptorStat.st_ino != pathStat.st_ino ||
                descriptorStat.st_size != manifest.sizeBytes ||
                pathStat.st_size != manifest.sizeBytes ||
                descriptorStat.st_nlink != 1L ||
                pathStat.st_nlink != 1L ||
                descriptorStat.st_mode and FILE_PERMISSION_MASK != OsConstants.S_IRUSR ||
                pathStat.st_mode and FILE_PERMISSION_MASK != OsConstants.S_IRUSR
            ) {
                throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
            }
            val pinnedDescriptor = ParcelFileDescriptor.dup(descriptor)
            try {
                Os.close(descriptor)
            } catch (error: Exception) {
                pinnedDescriptor.close()
                throw error
            }
            return VerifiedModelLease(
                file = canonicalFile,
                descriptor = pinnedDescriptor,
                inode = inode,
                sizeBytes = manifest.sizeBytes,
            )
        } catch (error: Exception) {
            try {
                Os.close(descriptor)
            } catch (_: Exception) {
                // Preserve the typed verification failure.
            }
            if (error is ModelStoreException) throw error
            throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
        }
    }
}

internal class VerifiedModelLease(
    private val file: File,
    private val descriptor: ParcelFileDescriptor,
    private val inode: Long,
    private val sizeBytes: Long,
) : RuntimeModelLease {
    private val closed = AtomicBoolean(false)

    /** Returns a path only after both the pinned descriptor and current directory entry match. */
    override fun revalidateAndGetOpaquePath(): String {
        if (closed.get()) throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
        try {
            if (file.canonicalFile != file.absoluteFile) {
                throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
            }
            val descriptorStat = Os.fstat(descriptor.fileDescriptor)
            val pathStat = Os.lstat(file.absolutePath)
            if (
                !OsConstants.S_ISREG(descriptorStat.st_mode) ||
                !OsConstants.S_ISREG(pathStat.st_mode) ||
                descriptorStat.st_ino != inode ||
                pathStat.st_ino != inode ||
                descriptorStat.st_dev != pathStat.st_dev ||
                descriptorStat.st_ino != pathStat.st_ino ||
                descriptorStat.st_size != sizeBytes ||
                pathStat.st_size != sizeBytes ||
                descriptorStat.st_nlink != 1L ||
                pathStat.st_nlink != 1L ||
                descriptorStat.st_mode and FILE_PERMISSION_MASK != OsConstants.S_IRUSR ||
                pathStat.st_mode and FILE_PERMISSION_MASK != OsConstants.S_IRUSR
            ) {
                throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
            }
            // LiteRT-LM 0.16.1 selects the container loader by the .litertlm suffix and
            // exposes no Android FD API, so a bare /proc/self/fd/N path is incompatible.
            // Keep the inode pinned and return the revalidated app-private entry only here.
            return file.absolutePath
        } catch (error: Exception) {
            if (error is ModelStoreException) throw error
            throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            descriptor.close()
        } catch (_: Exception) {
            // The lease is already terminal; do not expose descriptor details.
        }
    }
}

enum class ModelStoreErrorCode {
    SOURCE_UNAVAILABLE,
    INSUFFICIENT_STORAGE,
    INVALID_SIZE,
    INVALID_DIGEST,
    INVALID_FILE_TYPE,
    STORAGE_FAILURE,
}

class ModelStoreException(
    val code: ModelStoreErrorCode,
) : Exception(code.name)

sealed interface InstalledModelState {
    data object Missing : InstalledModelState

    data class Ready(val model: VerifiedInstalledModel) : InstalledModelState

    data class Rejected(val code: ModelStoreErrorCode) : InstalledModelState
}

/** Installs one APK-pinned model into immutable, content-addressed app-private storage. */
class ModelArtifactStore private constructor(
    context: Context,
    val manifest: ModelManifest,
    rootName: String,
) {
    constructor(context: Context) : this(
        context = context,
        manifest = PinnedModelManifest.value,
        rootName = BuildConfig.MODEL_STORE_ROOT,
    )

    private val appContext = context.applicationContext
    private val operationMutex = Mutex()
    private val trustedBase = appContext.noBackupFilesDir.canonicalFile
    private val storeRoot = File(trustedBase, rootName)
    private val artifactFile = File(storeRoot, "${manifest.sha256}.litertlm")
    private val currentPointer = File(storeRoot, CURRENT_POINTER)
    private val installerLock = File(storeRoot, INSTALLER_LOCK)

    suspend fun inspect(
        onProgress: (verifiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): InstalledModelState = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                ensureStoreRoot()
                if (!currentPointer.exists()) return@withContext InstalledModelState.Missing
                if (readPointer() != expectedPointer()) {
                    return@withContext InstalledModelState.Rejected(
                        ModelStoreErrorCode.INVALID_FILE_TYPE,
                    )
                }
                InstalledModelState.Ready(verifyArtifact(onProgress))
            } catch (error: ModelStoreException) {
                InstalledModelState.Rejected(error.code)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                InstalledModelState.Rejected(ModelStoreErrorCode.STORAGE_FAILURE)
            }
        }
    }

    suspend fun importFrom(
        uri: Uri,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): VerifiedInstalledModel = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            ensureStoreRoot()
            installerProcessMutex.withLock {
                withInstallerFileLock {
                    recoverExistingArtifact()?.also {
                        commitCurrentPointer()
                        return@withInstallerFileLock it
                    }

                    ensureCapacityForImport()

                    val input = try {
                        appContext.contentResolver.openInputStream(uri)
                    } catch (_: Exception) {
                        throw ModelStoreException(ModelStoreErrorCode.SOURCE_UNAVAILABLE)
                    } ?: throw ModelStoreException(ModelStoreErrorCode.SOURCE_UNAVAILABLE)
                    input.use { installFromStream(it, onProgress) }
                }
            }
        }
    }

    internal suspend fun importFromStreamForTest(
        input: InputStream,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): VerifiedInstalledModel = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            ensureStoreRoot()
            installerProcessMutex.withLock {
                withInstallerFileLock {
                    recoverExistingArtifact()?.also {
                        commitCurrentPointer()
                        return@withInstallerFileLock it
                    }
                    ensureCapacityForImport()
                    installFromStream(input, onProgress)
                }
            }
        }
    }

    private suspend fun installFromStream(
        input: InputStream,
        onProgress: (Long, Long) -> Unit,
    ): VerifiedInstalledModel {
        val temporary = File(storeRoot, ".import-${UUID.randomUUID()}.partial")
        val descriptor = try {
            Os.open(
                temporary.absolutePath,
                OsConstants.O_CREAT or OsConstants.O_EXCL or
                    OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or OsConstants.O_WRONLY,
                OsConstants.S_IRUSR or OsConstants.S_IWUSR,
            )
        } catch (_: ErrnoException) {
            throw ModelStoreException(ModelStoreErrorCode.STORAGE_FAILURE)
        }

        var streamOwnsDescriptor = false
        try {
            val initialStat = Os.fstat(descriptor)
            if (!OsConstants.S_ISREG(initialStat.st_mode) || initialStat.st_nlink != 1L) {
                throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            val output = FileOutputStream(descriptor)
            streamOwnsDescriptor = true
            output.use {
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    copied += read
                    if (copied > manifest.sizeBytes) {
                        throw ModelStoreException(ModelStoreErrorCode.INVALID_SIZE)
                    }
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    onProgress(copied, manifest.sizeBytes)
                }
                output.flush()
                output.fd.sync()
                Os.fchmod(descriptor, OsConstants.S_IRUSR)
                output.fd.sync()
            }

            if (copied != manifest.sizeBytes) {
                throw ModelStoreException(ModelStoreErrorCode.INVALID_SIZE)
            }
            if (digest.digest().toHex() != manifest.sha256) {
                throw ModelStoreException(ModelStoreErrorCode.INVALID_DIGEST)
            }

            val completedStat = Os.lstat(temporary.absolutePath)
            if (
                !OsConstants.S_ISREG(completedStat.st_mode) ||
                completedStat.st_dev != initialStat.st_dev ||
                completedStat.st_ino != initialStat.st_ino ||
                completedStat.st_size != manifest.sizeBytes ||
                completedStat.st_nlink != 1L ||
                completedStat.st_mode and FILE_PERMISSION_MASK != OsConstants.S_IRUSR
            ) {
                throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
            }

            publishWithoutOverwrite(
                temporary = temporary,
                expectedDevice = initialStat.st_dev,
                expectedInode = initialStat.st_ino,
            )
            commitCurrentPointer()
            return verifyArtifact()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ModelStoreException) {
            throw error
        } catch (_: Exception) {
            throw ModelStoreException(ModelStoreErrorCode.STORAGE_FAILURE)
        } finally {
            if (!streamOwnsDescriptor) safeClose(descriptor)
            safeUnlink(temporary)
        }
    }

    private suspend fun publishWithoutOverwrite(
        temporary: File,
        expectedDevice: Long,
        expectedInode: Long,
    ) {
        // Android app sandboxes reject hard links (EACCES) and the public Os API has no
        // renameat2(RENAME_NOREPLACE). Under the process mutex + cross-process installer lock,
        // an O_EXCL reservation makes the final rename non-overwriting for every trusted writer.
        // Code running under the same UID but bypassing this store is outside this threat model.
        val sourceStat = Os.lstat(temporary.absolutePath)
        if (
            !OsConstants.S_ISREG(sourceStat.st_mode) ||
            sourceStat.st_dev != expectedDevice ||
            sourceStat.st_ino != expectedInode ||
            sourceStat.st_size != manifest.sizeBytes ||
            sourceStat.st_nlink != 1L ||
            sourceStat.st_mode and FILE_PERMISSION_MASK != OsConstants.S_IRUSR
        ) {
            throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
        }

        val reservation = try {
            Os.open(
                artifactFile.absolutePath,
                OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or
                    OsConstants.O_CLOEXEC or OsConstants.O_WRONLY,
                OsConstants.S_IRUSR,
            )
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.EEXIST) {
                throw ModelStoreException(ModelStoreErrorCode.STORAGE_FAILURE)
            }
            verifyArtifact()
            return
        }

        val reservationStat = try {
            val descriptorStat = Os.fstat(reservation)
            val pathStat = Os.lstat(artifactFile.absolutePath)
            if (
                !OsConstants.S_ISREG(descriptorStat.st_mode) ||
                !OsConstants.S_ISREG(pathStat.st_mode) ||
                descriptorStat.st_dev != pathStat.st_dev ||
                descriptorStat.st_ino != pathStat.st_ino ||
                descriptorStat.st_size != 0L ||
                pathStat.st_size != 0L ||
                descriptorStat.st_nlink != 1L ||
                pathStat.st_nlink != 1L
            ) {
                throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
            }
            Os.fsync(reservation)
            descriptorStat
        } catch (error: ModelStoreException) {
            safeClose(reservation)
            throw error
        } catch (_: Exception) {
            safeClose(reservation)
            throw ModelStoreException(ModelStoreErrorCode.STORAGE_FAILURE)
        }
        safeClose(reservation)

        var published = false
        try {
            val claimedStat = Os.lstat(artifactFile.absolutePath)
            if (
                !OsConstants.S_ISREG(claimedStat.st_mode) ||
                claimedStat.st_dev != reservationStat.st_dev ||
                claimedStat.st_ino != reservationStat.st_ino ||
                claimedStat.st_size != 0L ||
                claimedStat.st_nlink != 1L
            ) {
                throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
            }
            Os.rename(temporary.absolutePath, artifactFile.absolutePath)
            published = true
        } finally {
            if (!published) {
                removeReservationIfUnchanged(reservationStat)
            }
        }

        val publishedStat = Os.lstat(artifactFile.absolutePath)
        if (
            !OsConstants.S_ISREG(publishedStat.st_mode) ||
            publishedStat.st_dev != sourceStat.st_dev ||
            publishedStat.st_ino != sourceStat.st_ino ||
            publishedStat.st_nlink != 1L ||
            publishedStat.st_mode and FILE_PERMISSION_MASK != OsConstants.S_IRUSR
        ) {
            throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
        }
        fsyncStoreRoot()
    }

    private fun removeReservationIfUnchanged(expected: android.system.StructStat) {
        val current = try {
            Os.lstat(artifactFile.absolutePath)
        } catch (_: ErrnoException) {
            return
        }
        if (
            OsConstants.S_ISREG(current.st_mode) &&
            current.st_dev == expected.st_dev &&
            current.st_ino == expected.st_ino &&
            current.st_size == 0L &&
            current.st_nlink == 1L
        ) {
            safeUnlink(artifactFile)
        }
    }

    private suspend fun recoverExistingArtifact(): VerifiedInstalledModel? {
        if (!artifactFile.exists()) return null
        return try {
            verifyArtifact()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ModelStoreException) {
            if (
                error.code != ModelStoreErrorCode.INVALID_SIZE &&
                error.code != ModelStoreErrorCode.INVALID_DIGEST &&
                error.code != ModelStoreErrorCode.INVALID_FILE_TYPE
            ) {
                throw error
            }
            discardRejectedArtifact()
            null
        }
    }

    private fun discardRejectedArtifact() {
        val originalStat = try {
            Os.lstat(artifactFile.absolutePath)
        } catch (_: ErrnoException) {
            throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
        }
        if (!OsConstants.S_ISREG(originalStat.st_mode) || originalStat.st_nlink != 1L) {
            throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
        }

        val rejected = File(storeRoot, ".rejected-${UUID.randomUUID()}.partial")
        try {
            Os.rename(artifactFile.absolutePath, rejected.absolutePath)
            val rejectedStat = Os.lstat(rejected.absolutePath)
            if (
                !OsConstants.S_ISREG(rejectedStat.st_mode) ||
                rejectedStat.st_dev != originalStat.st_dev ||
                rejectedStat.st_ino != originalStat.st_ino ||
                rejectedStat.st_nlink != 1L
            ) {
                throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
            }
            fsyncStoreRoot()
            safeUnlink(rejected)
            fsyncStoreRoot()
        } catch (error: ModelStoreException) {
            throw error
        } catch (_: Exception) {
            throw ModelStoreException(ModelStoreErrorCode.STORAGE_FAILURE)
        }
    }

    private fun ensureCapacityForImport() {
        val requiredBytes = if (manifest.sizeBytes > Long.MAX_VALUE - STORAGE_HEADROOM_BYTES) {
            Long.MAX_VALUE
        } else {
            manifest.sizeBytes + STORAGE_HEADROOM_BYTES
        }
        val availableBytes = try {
            StatFs(storeRoot.absolutePath).availableBytes
        } catch (_: Exception) {
            throw ModelStoreException(ModelStoreErrorCode.STORAGE_FAILURE)
        }
        if (availableBytes < requiredBytes) {
            throw ModelStoreException(ModelStoreErrorCode.INSUFFICIENT_STORAGE)
        }
    }

    private suspend fun verifyArtifact(
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): VerifiedInstalledModel {
        val descriptor = try {
            Os.open(
                artifactFile.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,
                0,
            )
        } catch (_: ErrnoException) {
            throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
        }

        var streamOwnsDescriptor = false
        try {
            val stat = Os.fstat(descriptor)
            if (stat.st_size != manifest.sizeBytes) {
                throw ModelStoreException(ModelStoreErrorCode.INVALID_SIZE)
            }
            if (
                !OsConstants.S_ISREG(stat.st_mode) ||
                stat.st_nlink != 1L ||
                stat.st_mode and FILE_PERMISSION_MASK != OsConstants.S_IRUSR
            ) {
                throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var verified = 0L
            val input = FileInputStream(descriptor)
            streamOwnsDescriptor = true
            input.use {
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    verified += read
                    digest.update(buffer, 0, read)
                    onProgress(verified, manifest.sizeBytes)
                }
            }
            if (verified != manifest.sizeBytes || digest.digest().toHex() != manifest.sha256) {
                throw ModelStoreException(ModelStoreErrorCode.INVALID_DIGEST)
            }
            val completedStat = Os.lstat(artifactFile.absolutePath)
            if (
                !OsConstants.S_ISREG(completedStat.st_mode) ||
                completedStat.st_ino != stat.st_ino ||
                completedStat.st_dev != stat.st_dev ||
                completedStat.st_size != stat.st_size ||
                completedStat.st_nlink != 1L ||
                completedStat.st_mode and FILE_PERMISSION_MASK != OsConstants.S_IRUSR
            ) {
                throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
            }
            return VerifiedInstalledModel(
                file = artifactFile,
                inode = stat.st_ino,
                manifest = manifest,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ModelStoreException) {
            throw error
        } catch (_: Exception) {
            throw ModelStoreException(ModelStoreErrorCode.STORAGE_FAILURE)
        } finally {
            if (!streamOwnsDescriptor) safeClose(descriptor)
        }
    }

    private fun commitCurrentPointer() {
        val temporary = File(storeRoot, ".current-${UUID.randomUUID()}.partial")
        val descriptor = try {
            Os.open(
                temporary.absolutePath,
                OsConstants.O_CREAT or OsConstants.O_EXCL or
                    OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or OsConstants.O_WRONLY,
                OsConstants.S_IRUSR or OsConstants.S_IWUSR,
            )
        } catch (_: ErrnoException) {
            throw ModelStoreException(ModelStoreErrorCode.STORAGE_FAILURE)
        }

        var streamOwnsDescriptor = false
        try {
            val output = FileOutputStream(descriptor)
            streamOwnsDescriptor = true
            output.use {
                output.write(expectedPointer().toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            Os.rename(temporary.absolutePath, currentPointer.absolutePath)
            fsyncStoreRoot()
        } catch (_: Exception) {
            throw ModelStoreException(ModelStoreErrorCode.STORAGE_FAILURE)
        } finally {
            if (!streamOwnsDescriptor) safeClose(descriptor)
            safeUnlink(temporary)
        }
    }

    private fun readPointer(): String {
        val descriptor = try {
            Os.open(
                currentPointer.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,
                0,
            )
        } catch (_: ErrnoException) {
            throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
        }

        var streamOwnsDescriptor = false
        try {
            val stat = Os.fstat(descriptor)
            if (
                !OsConstants.S_ISREG(stat.st_mode) ||
                stat.st_size !in 1..MAX_POINTER_BYTES ||
                stat.st_nlink != 1L ||
                stat.st_mode and FILE_PERMISSION_MASK != POINTER_PERMISSION_MODE
            ) {
                throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
            }
            val input = FileInputStream(descriptor)
            streamOwnsDescriptor = true
            val value = input.use { it.readBytes().toString(Charsets.UTF_8) }
            val completedStat = Os.lstat(currentPointer.absolutePath)
            if (
                !OsConstants.S_ISREG(completedStat.st_mode) ||
                completedStat.st_dev != stat.st_dev ||
                completedStat.st_ino != stat.st_ino ||
                completedStat.st_size != stat.st_size ||
                completedStat.st_nlink != 1L ||
                completedStat.st_mode and FILE_PERMISSION_MASK != POINTER_PERMISSION_MODE
            ) {
                throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
            }
            return value
        } finally {
            if (!streamOwnsDescriptor) safeClose(descriptor)
        }
    }

    private suspend fun <T> withInstallerFileLock(block: suspend () -> T): T {
        val descriptor = try {
            Os.open(
                installerLock.absolutePath,
                OsConstants.O_CREAT or OsConstants.O_NOFOLLOW or
                    OsConstants.O_CLOEXEC or OsConstants.O_RDWR,
                OsConstants.S_IRUSR or OsConstants.S_IWUSR,
            )
        } catch (_: ErrnoException) {
            throw ModelStoreException(ModelStoreErrorCode.STORAGE_FAILURE)
        }
        val stat = try {
            Os.fstat(descriptor)
        } catch (_: Exception) {
            safeClose(descriptor)
            throw ModelStoreException(ModelStoreErrorCode.STORAGE_FAILURE)
        }
        val pathStat = try {
            Os.lstat(installerLock.absolutePath)
        } catch (_: ErrnoException) {
            safeClose(descriptor)
            throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
        }
        if (
            !OsConstants.S_ISREG(stat.st_mode) ||
            !OsConstants.S_ISREG(pathStat.st_mode) ||
            stat.st_dev != pathStat.st_dev ||
            stat.st_ino != pathStat.st_ino ||
            stat.st_nlink != 1L ||
            pathStat.st_nlink != 1L ||
            stat.st_mode and FILE_PERMISSION_MASK != POINTER_PERMISSION_MODE ||
            pathStat.st_mode and FILE_PERMISSION_MASK != POINTER_PERMISSION_MODE
        ) {
            safeClose(descriptor)
            throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
        }

        val stream = try {
            FileOutputStream(descriptor)
        } catch (_: Exception) {
            safeClose(descriptor)
            throw ModelStoreException(ModelStoreErrorCode.STORAGE_FAILURE)
        }
        stream.use {
            val channel = stream.channel
            var acquiredLock: java.nio.channels.FileLock? = null
            while (acquiredLock == null) {
                currentCoroutineContext().ensureActive()
                val candidate = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                } catch (_: Exception) {
                    throw ModelStoreException(ModelStoreErrorCode.STORAGE_FAILURE)
                }
                if (candidate == null) {
                    delay(FILE_LOCK_RETRY_MILLIS)
                } else {
                    acquiredLock = candidate
                }
            }
            val lock = checkNotNull(acquiredLock)
            lock.use {
                val lockedPathStat = try {
                    Os.lstat(installerLock.absolutePath)
                } catch (_: ErrnoException) {
                    throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
                }
                if (
                    !OsConstants.S_ISREG(lockedPathStat.st_mode) ||
                    lockedPathStat.st_dev != pathStat.st_dev ||
                    lockedPathStat.st_ino != stat.st_ino ||
                    lockedPathStat.st_nlink != 1L
                ) {
                    throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
                }
                cleanupStalePartials()
                return block()
            }
        }
    }

    private fun cleanupStalePartials() {
        storeRoot.listFiles()?.forEach { candidate ->
            if (
                !STALE_IMPORT_PATTERN.matches(candidate.name) &&
                !STALE_REJECTED_PATTERN.matches(candidate.name)
            ) {
                return@forEach
            }
            val stat = try {
                Os.lstat(candidate.absolutePath)
            } catch (_: ErrnoException) {
                return@forEach
            }
            if (OsConstants.S_ISREG(stat.st_mode) && stat.st_nlink in 1L..2L) {
                safeUnlink(candidate)
            }
        }
    }

    private fun ensureStoreRoot() {
        try {
            if (!storeRoot.exists() && !storeRoot.mkdirs() && !storeRoot.isDirectory) {
                throw ModelStoreException(ModelStoreErrorCode.STORAGE_FAILURE)
            }
            if (
                java.nio.file.Files.isSymbolicLink(storeRoot.toPath()) ||
                !storeRoot.isDirectory ||
                storeRoot.canonicalFile.parentFile != trustedBase
            ) {
                throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
            }

            val descriptor = Os.open(
                storeRoot.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,
                0,
            )
            try {
                val descriptorStat = Os.fstat(descriptor)
                val pathStat = Os.lstat(storeRoot.absolutePath)
                if (
                    !OsConstants.S_ISDIR(descriptorStat.st_mode) ||
                    !OsConstants.S_ISDIR(pathStat.st_mode) ||
                    descriptorStat.st_dev != pathStat.st_dev ||
                    descriptorStat.st_ino != pathStat.st_ino
                ) {
                    throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
                }
                Os.fchmod(
                    descriptor,
                    OsConstants.S_IRUSR or OsConstants.S_IWUSR or OsConstants.S_IXUSR,
                )
            } finally {
                safeClose(descriptor)
            }
        } catch (error: ModelStoreException) {
            throw error
        } catch (_: Exception) {
            throw ModelStoreException(ModelStoreErrorCode.STORAGE_FAILURE)
        }
    }

    private fun fsyncStoreRoot() {
        val descriptor = Os.open(
            storeRoot.absolutePath,
            OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,
            0,
        )
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) {
                throw ModelStoreException(ModelStoreErrorCode.INVALID_FILE_TYPE)
            }
            Os.fsync(descriptor)
        } finally {
            safeClose(descriptor)
        }
    }

    private fun expectedPointer(): String = "${manifest.revision}\n${manifest.sha256}\n"

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(Locale.ROOT, byte)
    }

    private fun safeUnlink(file: File) {
        try {
            Os.remove(file.absolutePath)
        } catch (_: ErrnoException) {
            // A leftover partial is never considered active without the committed pointer.
        }
    }

    private fun safeClose(descriptor: java.io.FileDescriptor) {
        try {
            Os.close(descriptor)
        } catch (_: ErrnoException) {
            // Best-effort cleanup without exposing filesystem details.
        }
    }

    companion object {
        private const val CURRENT_POINTER = "current"
        private const val INSTALLER_LOCK = ".install.lock"
        private const val COPY_BUFFER_SIZE = 1024 * 1024
        private const val MAX_POINTER_BYTES = 256L
        private const val STORAGE_HEADROOM_BYTES = 512L * 1024L * 1024L
        private const val FILE_LOCK_RETRY_MILLIS = 50L
        private val STALE_IMPORT_PATTERN = Regex("\\.import-[0-9a-fA-F-]{36}\\.partial")
        private val STALE_REJECTED_PATTERN = Regex("\\.rejected-[0-9a-fA-F-]{36}\\.partial")
        private val installerProcessMutex = Mutex()

        internal fun forTesting(
            context: Context,
            manifest: ModelManifest,
            namespace: String,
        ): ModelArtifactStore {
            require(namespace.matches(Regex("[a-z0-9-]{1,64}")))
            return ModelArtifactStore(context, manifest, "test-$namespace")
        }
    }
}
