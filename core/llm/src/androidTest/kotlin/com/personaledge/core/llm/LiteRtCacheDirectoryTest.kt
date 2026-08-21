package com.personaledge.core.llm

import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiteRtCacheDirectoryTest {
    @Test
    fun cachePathIsFixedUnderNoBackupRootWithOwnerOnlyPermissions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = AppPrivateLiteRtCacheDirectory(context)

        val first = provider.getOrCreateOpaquePath()
        val second = provider.getOrCreateOpaquePath()
        val directory = File(first)
        val stat = Os.lstat(first)

        assertEquals(first, second)
        assertEquals(context.noBackupFilesDir.canonicalFile, directory.parentFile)
        assertEquals(directory.absoluteFile, directory.canonicalFile)
        assertTrue(OsConstants.S_ISDIR(stat.st_mode))
        assertEquals(Os.getuid(), stat.st_uid)
        assertEquals(OWNER_DIRECTORY_MODE, stat.st_mode and PERMISSION_MASK)
    }

    private companion object {
        const val PERMISSION_MASK = 0x1FF
        val OWNER_DIRECTORY_MODE = OsConstants.S_IRUSR or
            OsConstants.S_IWUSR or OsConstants.S_IXUSR
    }
}
