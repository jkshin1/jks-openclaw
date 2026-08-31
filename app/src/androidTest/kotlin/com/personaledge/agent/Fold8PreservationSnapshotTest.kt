package com.personaledge.agent

import android.os.Bundle
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Content-free before/after snapshot for a same-certificate release update.
 *
 * It reads no credential value, conversation, memory, notification, address, calendar label, or
 * model bytes into test output. Counts, presence flags, and a digest of non-content settings are
 * sufficient to detect an accidental uninstall or reset around `adb install -r`.
 */
@RunWith(AndroidJUnit4::class)
class Fold8PreservationSnapshotTest {
    @Test
    fun emitContentFreeOwnerStateSnapshot() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "Fold8 preservation snapshot requires -e preservationSnapshot true.",
            InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true",
        )
        assumeTrue("Physical-device acceptance only.", !isEmulator())
        val application = instrumentation.targetContext.applicationContext as PersonalEdgeApplication
        val settings = application.container.settings.current()
        var modelArtifactCount = 0
        var modelArtifactSize = -1L
        File(application.noBackupFilesDir, MODEL_ROOT).listFiles()?.let { files ->
            for (file in files) {
                if (file.isFile && file.name.endsWith(".litertlm")) {
                    modelArtifactCount++
                    modelArtifactSize = file.length()
                }
            }
        }
        assertTrue(
            "The installed model artifact must remain present with the pinned size.",
            modelArtifactCount == 1 && modelArtifactSize == MODEL_SIZE_BYTES,
        )

        val credentialPresence = StringBuilder()
        var firstCredential = true
        for (status in application.container.credentials.statuses()) {
            if (!firstCredential) credentialPresence.append(',')
            firstCredential = false
            credentialPresence.append(status.slot.name).append(':').append(status.stored)
        }
        val safeSettings = StringBuilder()
            .append(settings.defaultCalendarId != null).append('|')
            .append(settings.defaultCalendarLabel != null).append('|')
            .append(settings.readCalendarIds.size).append('|')
            .append(settings.defaultOriginLabel != null).append('|')
            .append(settings.notificationCaptureEnabled).append('|')
            .append(settings.commitmentProposalsEnabled).append('|')
            .append(settings.notificationRetentionDays).append('|')
            .append(settings.routeLookupEnabled).append('|')
            .append(settings.webSearchEnabled).append('|')
            .append(settings.memoryEnabled).append('|')
            .append(settings.dailyBriefEnabled).append('|')
            .append(settings.dailyBriefMinutesOfDay).append('|')
            .append(settings.proactiveRoutePlanningEnabled).append('|')
            .append(settings.quietHoursEnabled).append('|')
            .append(settings.weekendBriefEnabled).append('|')
            .append(settings.recentMessageWindow)
            .toString()
        var replyEnabled = "not_supported"
        for (method in settings.javaClass.methods) {
            if (method.name == "getKakaoNotificationReplyEnabled") {
                replyEnabled = try {
                    method.invoke(settings)?.toString() ?: "unavailable"
                } catch (_: Exception) {
                    "unavailable"
                }
                break
            }
        }

        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("preservation_model", "present_size_matched")
                putString("preservation_model_size", MODEL_SIZE_BYTES.toString())
                putString("preservation_settings_sha256", safeSettings.sha256())
                putString("preservation_credentials", credentialPresence.toString())
                putString(
                    "preservation_notification_count",
                    application.container.notifications.count().toString(),
                )
                putString(
                    "preservation_conversation_count",
                    application.container.conversations.conversationCount().toString(),
                )
                putString(
                    "preservation_message_count",
                    application.container.conversations.messageCount().toString(),
                )
                putString(
                    "preservation_memory_count",
                    application.container.memories.count().toString(),
                )
                putString("preservation_kakao_reply_enabled", replyEnabled)
            },
        )
    }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        val result = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            result.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return result.toString()
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk", ignoreCase = true)

    private companion object {
        const val ARGUMENT = "preservationSnapshot"
        const val MODEL_ROOT = "personal-edge-models-v1"
        const val MODEL_SIZE_BYTES = 3_659_530_240L
        const val HEX = "0123456789abcdef"
    }
}
