package com.personaledge.agent

import android.os.Build
import android.os.Bundle
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.data.ConversationContext
import com.personaledge.core.data.ConversationEntity
import com.personaledge.core.data.MessageRole
import com.personaledge.core.data.SecretKeyName
import com.personaledge.core.data.StoredMessage
import com.personaledge.core.diagnostics.Api31ResourceSnapshotProvider
import com.personaledge.core.diagnostics.DiagnosticThermalStatus
import com.personaledge.core.llm.TurnId
import com.personaledge.core.tools.InterlockDecision
import com.personaledge.core.tools.InterlockPhase
import com.personaledge.core.tools.InterlockRequest
import com.personaledge.core.tools.NextAlarm
import com.personaledge.core.tools.TavilyWebSearchGateway
import com.personaledge.core.tools.ToolRisk
import com.personaledge.core.tools.WebSearchHit
import com.personaledge.core.tools.WebSearchProvider
import com.personaledge.core.tools.WebSearchResponse
import com.personaledge.core.tools.WebSearchTool
import com.personaledge.core.tools.YouKeylessMcpWebSearchGateway
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A provider-free AVD smoke test for entry points used only after physical acceptance opt-ins.
 *
 * The release test APK is separate from the minified target APK. This test deliberately touches
 * the small shared ABI that cannot be reached when physical tests stop at their AVD guard. It
 * reads only process metrics and settings, performs no provider request, and changes no state.
 */
@RunWith(AndroidJUnit4::class)
class ReleasePhysicalAbiLinkageTest {
    @Test
    fun boundedPostGuardEntrypointsResolveFromTheMinifiedTarget() = runBlocking {
        assumeTrue(
            "Release physical ABI smoke requires -e releasePhysicalAbiLinkage true.",
            InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true",
        )
        assumeTrue("Release physical ABI smoke is AVD-only.", isEmulator())

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as PersonalEdgeApplication
        val container = application.container

        val query = SimpleSQLiteQuery("SELECT 1")
        assertEquals("SELECT 1", query.sql)
        assertEquals(0, query.argCount)
        container.database.openHelper.readableDatabase.query(query).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        val settings = container.settings.current()
        val webConsent = NetworkToolConsent.isEnabled(WebSearchTool.NAME, settings)
        assertTrue(ThermalTurnPolicy.canStart(DiagnosticThermalStatus.NONE))

        val resources = Api31ResourceSnapshotProvider(application).snapshot()
        assertTrue(resources.pssBytes > 0L)
        assertTrue(resources.javaHeapBytes >= 0L)

        val pair = Pair("left", "right")
        val (left, right) = pair
        assertEquals("left", left)
        assertEquals("right", right)
        assertEquals(left, pair.first)
        assertEquals(right, pair.second)

        val storedMessage = StoredMessage(
            id = "abi-message",
            ordinal = 1L,
            role = MessageRole.USER,
            text = "linked",
            createdAtEpochMillis = 0L,
        )
        val context = ConversationContext(
            conversationId = "abi-conversation",
            summary = "linked",
            recentMessages = listOf(storedMessage),
        )
        val conversation = ConversationEntity(
            id = "abi-conversation",
            title = "linked",
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
            summary = context.summary,
            summarizedThroughMessageOrdinal = 1L,
        )
        assertEquals("linked", context.recentMessages.single().text)
        assertEquals("abi-conversation", conversation.id)
        assertEquals("linked", conversation.title)
        assertEquals("linked", conversation.summary)
        assertEquals(1L, conversation.summarizedThroughMessageOrdinal)

        val calendar = CalendarOption(
            id = 1L,
            label = "linked",
            accountName = "local",
            accountType = "local",
            writable = true,
        )
        val calendarSetup = CalendarSetupState(
            calendars = listOf(calendar),
            pinnedCalendarId = calendar.id,
        )
        assertTrue(calendar.writable)
        assertEquals(calendar.id, calendarSetup.pinnedCalendarId)
        assertEquals(calendar, calendarSetup.calendars.single())

        val notificationSetup = NotificationSetupState(
            accessGranted = true,
            captureEnabled = false,
        )
        assertTrue(notificationSetup.accessGranted)
        assertFalse(notificationSetup.captureEnabled)
        assertEquals(
            listOf(CredentialSlot.NAVER_MAP_CLIENT_ID, CredentialSlot.NAVER_MAP_CLIENT_SECRET),
            listOf(CredentialSlot.NAVER_MAP_CLIENT_ID, CredentialSlot.NAVER_MAP_CLIENT_SECRET),
        )

        val nextAlarm = NextAlarm(123L)
        assertEquals(123L, nextAlarm.triggerAtEpochMillis)
        assertTrue(container.alarms.nextAlarm()?.triggerAtEpochMillis?.let { it >= 0L } != false)

        val interlock = DeviceExecutionInterlock(
            context = application,
            thermalStatus = { DiagnosticThermalStatus.NONE },
            pinnedCalendarId = container::pinnedCalendarId,
            calendarIsReadable = container::calendarIsReadable,
            alarmGateway = container.alarms,
            notificationGateway = container.notificationGateway,
            networkConsent = { false },
            sideEffectingToolsEnabled = true,
        )
        val interlockDecision = interlock.evaluate(
            InterlockRequest(
                toolName = WebSearchTool.NAME,
                risk = ToolRisk.READ_ONLY,
                requiredCapabilities = setOf(com.personaledge.core.tools.ToolCapability.NETWORK),
                phase = InterlockPhase.PREPARE,
            ),
        )
        assertTrue(interlockDecision is InterlockDecision.Block)
        assertTrue((interlockDecision as InterlockDecision.Block).reason.isNotBlank())

        val turnAsAny: Any = TurnId("abi-turn")
        val turn = turnAsAny as TurnId
        assertEquals("abi-turn", turn.value)
        assertTrue(turn == TurnId("abi-turn"))

        val intRef = kotlin.jvm.internal.Ref.IntRef().apply { element = 1 }
        val longRef = kotlin.jvm.internal.Ref.LongRef().apply { element = 2L }
        val objectRef = kotlin.jvm.internal.Ref.ObjectRef<String>().apply { element = "linked" }
        assertEquals(1, intRef.element)
        assertEquals(2L, longRef.element)
        assertEquals("linked", objectRef.element)

        val hit = WebSearchHit(
            title = "linked",
            link = "https://example.com/",
            snippet = "linked",
        )
        val response = WebSearchResponse(WebSearchProvider.YOU_COM, listOf(hit))
        assertEquals(WebSearchProvider.YOU_COM, response.provider)
        assertFalse(response.provider == WebSearchProvider.TAVILY)
        assertEquals("linked", response.hits.single().title)
        assertEquals("linked", response.hits.single().snippet)
        assertEquals("https://example.com/", response.hits.single().link)

        val tavily = TavilyWebSearchGateway(container.httpTransport) { null }
        assertFalse(tavily.credentialsPresent())
        val you = YouKeylessMcpWebSearchGateway(container.httpTransport)
        assertTrue(you.credentialsPresent())
        val vaultHasTavily = container.secretVault.contains(SecretKeyName.TAVILY_API_KEY)
        val vaultTavily = container.secretVault.read(SecretKeyName.TAVILY_API_KEY)
        assertEquals(vaultHasTavily, vaultTavily != null)

        val result = runCatching { java.net.URI(hit.link) }
        assertFalse(result.isFailure)
        val failedResult = runCatching<Unit> { error("linked") }
        assertTrue(failedResult.isFailure)
        assertEquals("linked", withTimeout(TIMEOUT_MILLIS) { "linked" })

        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("release_physical_abi_linked", "true")
                putString("release_physical_abi_web_consent", webConsent.toString())
                putString("release_physical_abi_thermal", resources.thermalStatus.name)
            },
        )
    }

    private companion object {
        const val ARGUMENT = "releasePhysicalAbiLinkage"
        const val TIMEOUT_MILLIS = 1_000L
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk", ignoreCase = true)
}
