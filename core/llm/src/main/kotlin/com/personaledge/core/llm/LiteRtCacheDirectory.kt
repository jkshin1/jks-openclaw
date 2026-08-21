package com.personaledge.core.llm

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

internal fun interface RuntimeCacheDirectoryProvider {
    fun getOrCreateOpaquePath(): String
}

/** Resolves one fixed, app-private LiteRT cache directory without exposing a path API. */
internal class AppPrivateLiteRtCacheDirectory(
    context: Context,
) : RuntimeCacheDirectoryProvider {
    // Android legitimately aliases /data/user/0 and /data/data. Canonicalize the trusted
    // framework-provided root once, then reject aliases only below that fixed boundary.
    private val root = context.applicationContext.noBackupFilesDir.canonicalFile

    override fun getOrCreateOpaquePath(): String {
        val trustedRoot = canonicalDirectory(root)
        val cache = File(trustedRoot, CACHE_DIRECTORY).absoluteFile
        if (cache.parentFile != trustedRoot) throw CacheDirectoryException()

        try {
            Os.mkdir(cache.absolutePath, OWNER_DIRECTORY_MODE)
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.EEXIST) throw CacheDirectoryException()
        } catch (_: Exception) {
            throw CacheDirectoryException()
        }

        if (canonicalDirectory(cache) != cache) throw CacheDirectoryException()
        var stat = lstatDirectory(cache)
        if (stat.st_uid != Os.getuid()) throw CacheDirectoryException()
        if (stat.st_mode and PERMISSION_MASK != OWNER_DIRECTORY_MODE) {
            try {
                Os.chmod(cache.absolutePath, OWNER_DIRECTORY_MODE)
            } catch (_: Exception) {
                throw CacheDirectoryException()
            }
            stat = lstatDirectory(cache)
        }
        if (
            stat.st_uid != Os.getuid() ||
            stat.st_mode and PERMISSION_MASK != OWNER_DIRECTORY_MODE
        ) {
            throw CacheDirectoryException()
        }
        return cache.absolutePath
    }

    private fun canonicalDirectory(directory: File): File {
        val absolute = directory.absoluteFile
        val canonical = try {
            absolute.canonicalFile
        } catch (_: Exception) {
            throw CacheDirectoryException()
        }
        if (canonical != absolute) throw CacheDirectoryException()
        lstatDirectory(absolute)
        return absolute
    }

    private fun lstatDirectory(directory: File): android.system.StructStat {
        val stat = try {
            Os.lstat(directory.absolutePath)
        } catch (_: Exception) {
            throw CacheDirectoryException()
        }
        if (!OsConstants.S_ISDIR(stat.st_mode)) throw CacheDirectoryException()
        return stat
    }

    private class CacheDirectoryException : Exception()

    private companion object {
        const val CACHE_DIRECTORY = "litertlm-cache"
        const val PERMISSION_MASK = 0x1FF
        val OWNER_DIRECTORY_MODE = OsConstants.S_IRUSR or
            OsConstants.S_IWUSR or OsConstants.S_IXUSR
    }
}
