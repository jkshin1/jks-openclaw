package com.personaledge.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A credential this app holds on the user's behalf. The enum is closed so no caller invents names. */
enum class SecretKeyName(internal val fileName: String) {
    NAVER_MAP_CLIENT_ID("naver-map-client-id.bin"),
    NAVER_MAP_CLIENT_SECRET("naver-map-client-secret.bin"),
    // Legacy identifiers stay closed over their old filenames so an upgrade never destroys or
    // aliases ciphertext that may still exist from the retired NAVER Search integration.
    NAVER_SEARCH_CLIENT_ID("naver-search-client-id.bin"),
    NAVER_SEARCH_CLIENT_SECRET("naver-search-client-secret.bin"),
    TAVILY_API_KEY("tavily-api-key.bin"),
}

enum class SecretHealth {
    ABSENT,
    READABLE,
    UNREADABLE,
}

/**
 * Stores third-party credentials encrypted under an app-scoped Android Keystore AES key.
 *
 * The key never leaves the AndroidKeyStore, so the ciphertext on disk is useless on any other
 * device, and an `adb backup` or a copied data directory cannot reveal the values. Losing the
 * key — a factory reset, an app reinstall, or a device change — makes existing ciphertext
 * permanently unreadable by design; the recovery path is re-entering the credential, which is
 * why [docs/RELEASE_AND_BACKUP.md] records where the originals live.
 *
 * Reading a secret is deliberately not exposed as a Flow: the plaintext should live in memory
 * only for the duration of one request.
 */
class SecretVault internal constructor(
    private val directory: File,
    private val keyStoreProvider: () -> SecretKey,
    private val ioDispatcher: CoroutineDispatcher,
    private val fileSystem: SecureSecretFileSystem,
) {
    private val mutex = Mutex()

    suspend fun store(name: SecretKeyName, value: String) {
        require(value.isNotBlank()) { "A blank secret would silently disable the feature." }
        require(value.length <= MAX_SECRET_CHARACTERS)

        mutex.withLock {
            withContext(ioDispatcher) {
                val key = keyStoreProvider()
                val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
                val initializationVector = cipher.iv
                check(initializationVector.size == GCM_IV_BYTES) { "Unexpected GCM IV length." }
                val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))

                val target = secretFile(name)
                val temporary = File(directory, ".${name.fileName}.tmp")
                fileSystem.atomicReplace(
                    target = target,
                    temporary = temporary,
                    bytes = initializationVector + ciphertext,
                    maxBytes = MAX_SECRET_FILE_BYTES,
                )
            }
        }
    }

    /** Returns null when the secret was never stored or can no longer be decrypted. */
    suspend fun read(name: SecretKeyName): String? = mutex.withLock {
        withContext(ioDispatcher) { decryptOrNull(secretFile(name)) }
    }

    /** Checks decryptability without returning plaintext outside this method. */
    suspend fun health(name: SecretKeyName): SecretHealth = mutex.withLock {
        withContext(ioDispatcher) {
            val file = secretFile(name)
            when {
                !fileSystem.exists(file) -> SecretHealth.ABSENT
                decryptOrNull(file) != null -> SecretHealth.READABLE
                else -> SecretHealth.UNREADABLE
            }
        }
    }

    suspend fun contains(name: SecretKeyName): Boolean = mutex.withLock {
        withContext(ioDispatcher) { fileSystem.exists(secretFile(name)) }
    }

    suspend fun remove(name: SecretKeyName): Boolean = mutex.withLock {
        withContext(ioDispatcher) { fileSystem.remove(secretFile(name)) }
    }

    /**
     * Clears every stored credential.
     *
     * Deletes the whole directory's contents rather than the known names, so a credential slot
     * removed in a later version cannot leave an orphaned ciphertext behind. The KeyStore key
     * itself is left in place for the next write.
     */
    suspend fun clear() {
        mutex.withLock {
            withContext(ioDispatcher) {
                fileSystem.clear()
            }
        }
    }

    private fun secretFile(name: SecretKeyName) = File(directory, name.fileName)

    private fun decryptOrNull(file: File): String? {
        return try {
            val stored = fileSystem.readOrNull(file, MAX_SECRET_FILE_BYTES) ?: return null
            if (stored.size <= GCM_IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    keyStoreProvider(),
                    GCMParameterSpec(GCM_TAG_BITS, stored, 0, GCM_IV_BYTES),
                )
            }
            String(
                cipher.doFinal(stored, GCM_IV_BYTES, stored.size - GCM_IV_BYTES),
                Charsets.UTF_8,
            )
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        internal const val DIRECTORY_NAME = "secrets"
        internal const val KEY_ALIAS = "personal-edge-secret-vault"
        private const val KEY_STORE_TYPE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val MAX_SECRET_CHARACTERS = 4_096
        private const val MAX_SECRET_FILE_BYTES = MAX_SECRET_CHARACTERS * 4 + GCM_IV_BYTES + 32

        fun create(
            context: Context,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): SecretVault {
            val directory = File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME)
            return SecretVault(
                directory = directory,
                keyStoreProvider = ::loadOrCreateKey,
                ioDispatcher = ioDispatcher,
                fileSystem = AndroidSecureSecretFileSystem(directory),
            )
        }

        private fun loadOrCreateKey(): SecretKey {
            val keyStore = KeyStore.getInstance(KEY_STORE_TYPE).apply { load(null) }
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
                ?.secretKey
                ?.let { existing -> return existing }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_STORE_TYPE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // No user authentication requirement: tools must run while the screen is off.
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}
