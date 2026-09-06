package com.personaledge.agent

import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.agent.ui.AgentModeSelector
import com.personaledge.agent.ui.OpenClawRemoteActions
import com.personaledge.agent.ui.OpenClawRemotePane
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A debug AVD fixture for the real Compose remote pane, with no backend or owner-data access.
 *
 * These callbacks only count local UI actions and never call a Gateway, provider, vault, or database.
 * Physical acceptance uses the separate opt-in production-surface test below this fixture lane.
 */
@RunWith(AndroidJUnit4::class)
class OpenClawRemoteUiAcceptanceTest {
    @Before
    fun requireDebugEmulator() {
        assumeTrue("Remote fixture UI acceptance is debug-only.", BuildConfig.DEBUG)
        assumeTrue("Remote fixture UI acceptance is AVD-only.", isOpenClawTestEmulator())
    }

    @Test
    fun sendingTheVisibleQuestionNeedsNoSeparateApproval() {
        launchFixture().use { scenario ->
            lateinit var fixture: OpenClawRemoteFixture
            scenario.onActivity { activity -> fixture = activity }
            clickOpenClawText("Mac · 원격")
            scenario.onActivity { activity ->
                activity.remote = activity.remote.copy(prompt = "나중에 바뀐 질문")
            }

            clickOpenClawText("전송")

            // Remote mode is the standing decision, so the tap sends the question that is on
            // screen at that moment; no second dialog stands between the control and the send.
            assertEquals("나중에 바뀐 질문", fixture.submittedPrompt.get())
            assertEquals(1, fixture.submissions.get())
            awaitOpenClawText("원격 실행 취소")
            clickOpenClawText("원격 실행 취소")
            awaitOpenClawText("취소 확인 중")
            assertEquals(1, fixture.cancellations.get())
        }
    }

    @Test
    fun connectionConsentStatesThatLaterSendsNeedNoFurtherConfirmation() {
        launchFixture().use { scenario ->
            scenario.onActivity { activity ->
                activity.remote = activity.remote.copy(
                    selected = true,
                    connection = OpenClawGatewayUiState.DISCONNECTED,
                )
            }
            clickOpenClawText("연결")
            awaitOpenClawText("동의하고 연결")
            assertTrue(hasOpenClawTextContaining("추가 확인 없이"))
        }
    }

    @Test
    fun leavingForegroundDismissesPendingConnectionConsent() {
        launchFixture().use { scenario ->
            lateinit var fixture: OpenClawRemoteFixture
            scenario.onActivity { activity ->
                fixture = activity
                activity.remote = activity.remote.copy(
                    selected = true,
                    connection = OpenClawGatewayUiState.DISCONNECTED,
                )
            }
            clickOpenClawText("연결")
            awaitOpenClawText("동의하고 연결")
            scenario.onActivity { activity -> activity.remote = activity.remote.copy(foreground = false) }

            awaitOpenClawTextAbsent("동의하고 연결")
            assertEquals(0, fixture.connections.get())
            scenario.onActivity { activity -> activity.remote = activity.remote.copy(foreground = true) }
            assertFalse(hasOpenClawText("동의하고 연결"))
            clickOpenClawText("연결")
            awaitOpenClawText("동의하고 연결")
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitOpenClawTextAbsent("동의하고 연결")
            assertEquals(0, fixture.connections.get())
        }
    }

    @Test
    fun recreationSendsNothingAndAnUnstoredKoreanTurnStillRenders() {
        launchFixture().use { scenario ->
            clickOpenClawText("Mac · 원격")
            scenario.recreate()

            awaitOpenClawText("Mac · 원격")
            scenario.onActivity { activity ->
                assertEquals(0, activity.submissions.get())
                // questionStored and answerStored stay false here, which is the storage-failure
                // shape: the screen must still show the owner their own question and answer.
                activity.remote = activity.remote.copy(
                    selected = true,
                    prompt = "",
                    sentPrompt = FIXTURE_QUESTION,
                    answer = "서울은 대한민국의 수도입니다.",
                )
            }
            awaitOpenClawTextContaining(FIXTURE_QUESTION)
            awaitOpenClawTextContaining("서울은 대한민국의 수도입니다.")
        }
    }

    @Test
    fun aStoredRemoteTurnIsRenderedOnceFromTheSharedConversation() {
        launchFixture().use { scenario ->
            clickOpenClawText("Mac · 원격")
            scenario.onActivity { activity ->
                activity.messages = listOf(
                    ChatEntry("stored-user", ChatRole.USER, FIXTURE_QUESTION),
                    ChatEntry("stored-assistant", ChatRole.ASSISTANT, "서울은 대한민국의 수도입니다."),
                )
                activity.remote = activity.remote.copy(
                    prompt = "",
                    sentPrompt = FIXTURE_QUESTION,
                    answer = "서울은 대한민국의 수도입니다.",
                    questionStored = true,
                    answerStored = true,
                )
            }
            awaitOpenClawTextContaining("서울은 대한민국의 수도입니다.")
            assertEquals(1, countOpenClawNodesContaining("서울은 대한민국의 수도입니다."))
            assertEquals(1, countOpenClawNodesContaining(FIXTURE_QUESTION))
        }
    }

    private fun launchFixture(): RemoteUiScenario = RemoteUiScenario()
}

private class RemoteUiScenario : AutoCloseable {
    private val scenario = ActivityScenario.launch(MainActivity::class.java)
    private lateinit var fixture: OpenClawRemoteFixture

    init {
        attachFixture()
    }

    private fun attachFixture() {
        scenario.onActivity { activity ->
            fixture = OpenClawRemoteFixture()
            fixture.showIn(activity)
        }
    }

    fun onActivity(action: (OpenClawRemoteFixture) -> Unit) {
        scenario.onActivity { action(fixture) }
    }

    fun recreate() {
        scenario.recreate()
        attachFixture()
    }

    fun moveToState(state: Lifecycle.State) {
        scenario.moveToState(state)
    }

    override fun close() = scenario.close()
}

/** Test-memory fixture rendered only after the debug AVD guard; no production hook. */
private class OpenClawRemoteFixture {
    var remote by mutableStateOf(
        OpenClawRemoteUiState(
            foreground = true,
            configured = true,
            connection = OpenClawGatewayUiState.CONNECTED,
            prompt = FIXTURE_QUESTION,
        ),
    )
    var messages by mutableStateOf(emptyList<ChatEntry>())
    val submittedPrompt = AtomicReference<String?>(null)
    val submissions = AtomicInteger(0)
    val connections = AtomicInteger(0)
    val cancellations = AtomicInteger(0)

    fun showIn(activity: ComponentActivity) {
        val actions = OpenClawRemoteActions(
            select = { selected -> remote = remote.copy(selected = selected) },
            connect = { connections.incrementAndGet() },
            updatePrompt = { prompt -> remote = remote.copy(prompt = prompt) },
            send = {
                // The production send reads the same visible question this fixture holds.
                val prompt = remote.prompt
                submittedPrompt.set(prompt)
                submissions.incrementAndGet()
                remote = remote.copy(running = true, sentPrompt = prompt, prompt = "")
            },
            cancel = {
                cancellations.incrementAndGet()
                remote = remote.copy(cancellationRequested = true)
            },
        )
        activity.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    AgentModeSelector(remote, localBusy = false, actions = actions)
                    if (remote.selected) OpenClawRemotePane(remote, actions, messages)
                }
            }
        }
    }
}

private const val FIXTURE_QUESTION = "대한민국의 수도를 한국어 한 문장으로 알려줘."

internal fun isOpenClawTestEmulator(): Boolean =
    Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.startsWith("google/sdk_gphone") ||
        Build.MODEL.contains("Emulator") || Build.MODEL.contains("sdk_gphone") ||
        Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"

internal fun hasOpenClawText(text: String): Boolean = findOpenClawText(text) != null

/**
 * Chat bubbles merge their descendants and carry a role content description, so an exact node
 * match is not guaranteed for message text. Containment keeps the assertion about the rendered
 * message while tolerating that merge.
 */
internal fun hasOpenClawTextContaining(text: String): Boolean =
    findOpenClawNode { node -> node.nodeTextContains(text) } != null

internal fun awaitOpenClawTextContaining(text: String) {
    awaitOpenClawCondition { hasOpenClawTextContaining(text) }
}

internal fun countOpenClawNodesContaining(text: String): Int =
    countOpenClawNodes { node -> node.nodeTextContains(text) }

private fun AccessibilityNodeInfo.nodeTextContains(value: String): Boolean =
    text?.toString()?.contains(value) == true || contentDescription?.toString()?.contains(value) == true

internal fun awaitOpenClawText(text: String) {
    awaitOpenClawCondition { hasOpenClawText(text) }
}

internal fun awaitOpenClawTextAbsent(text: String) {
    awaitOpenClawCondition { !hasOpenClawText(text) }
}

internal fun clickOpenClawText(text: String) {
    awaitOpenClawCondition { findClickableOpenClawText(text)?.isEnabled == true }
    val node = checkNotNull(findClickableOpenClawText(text))
    assertTrue("Expected a clickable UI control.", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
}

private fun findClickableOpenClawText(text: String): AccessibilityNodeInfo? {
    var node = findOpenClawText(text) ?: return null
    while (!node.isClickable) node = node.parent ?: return null
    return node
}

internal fun awaitOpenClawPasswordField() {
    awaitOpenClawCondition { findOpenClawNode { it.isPassword } != null }
}

private fun findOpenClawText(text: String): AccessibilityNodeInfo? = findOpenClawNode { node ->
    node.text?.toString() == text || node.contentDescription?.toString() == text ||
        node.hintText?.toString() == text
}

private fun countOpenClawNodes(predicate: (AccessibilityNodeInfo) -> Boolean): Int {
    val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return 0
    val nodes = java.util.ArrayDeque<AccessibilityNodeInfo>()
    nodes.add(root)
    var visited = 0
    var matches = 0
    while (nodes.isNotEmpty() && visited++ < 2_048) {
        val node = nodes.removeFirst()
        // A merged bubble and its own text node must not both count, so only leaf matches count.
        if (predicate(node) && (0 until node.childCount).none { index ->
                node.getChild(index)?.let(predicate) == true
            }
        ) {
            matches++
        }
        for (index in 0 until node.childCount) node.getChild(index)?.let(nodes::add)
    }
    return matches
}

private fun findOpenClawNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
    val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return null
    // Compose virtual nodes are discoverable by tree traversal even when the framework's
    // findAccessibilityNodeInfosByText query does not search the virtual provider's children.
    val nodes = java.util.ArrayDeque<AccessibilityNodeInfo>()
    nodes.add(root)
    var visited = 0
    while (nodes.isNotEmpty() && visited++ < 2_048) {
        val node = nodes.removeFirst()
        if (predicate(node)) return node
        for (index in 0 until node.childCount) node.getChild(index)?.let(nodes::add)
    }
    return null
}

private fun awaitOpenClawCondition(condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + 5_000L
    while (SystemClock.elapsedRealtime() < deadline) {
        if (condition()) return
        SystemClock.sleep(50L)
    }
    assertTrue("Expected remote UI state did not appear.", condition())
}
