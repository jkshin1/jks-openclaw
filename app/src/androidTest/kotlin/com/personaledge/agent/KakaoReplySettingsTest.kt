package com.personaledge.agent

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.data.SettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KakaoReplySettingsTest {
    private lateinit var file: File
    private lateinit var scope: CoroutineScope
    private lateinit var repository: SettingsRepository

    @Before
    fun createIsolatedSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "kakao-reply-${System.nanoTime()}.preferences_pb")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { file },
        )
    }

    @After
    fun closeSettings() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun replyPermissionIsOffByDefaultAndPersistsOnlyAfterOptIn() = runBlocking {
        assertFalse(repository.current().kakaoNotificationReplyEnabled)

        repository.setKakaoNotificationReplyEnabled(true)
        assertTrue(repository.current().kakaoNotificationReplyEnabled)

        repository.setKakaoNotificationReplyEnabled(false)
        assertFalse(repository.current().kakaoNotificationReplyEnabled)
    }
}
