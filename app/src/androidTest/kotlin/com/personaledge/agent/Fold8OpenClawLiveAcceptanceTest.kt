package com.personaledge.agent

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.data.ConversationRepository
import com.personaledge.core.data.MessageRole
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicitly selected, signed Fold8 acceptance through the production ViewModel and Gateway.
 *
 * A token never enters instrumentation arguments, shared storage, shell text, status, or diagnostics.
 * Bootstrap uses one ephemeral in-memory RSA keypair and a device-loopback TCP listener forwarded
 * by the host. Status contains only its public SPKI, port, random public challenge and protocol.
 * The host sends a 4-byte big-endian length followed by this bounded UTF-8 JSON envelope:
 * {"version":1,"wrappedKey":"BASE64","iv":"BASE64","ciphertext":"BASE64"}.
 * wrappedKey = RSA-OAEP(SHA-256, MGF1-SHA-256, empty label) of a fresh 32-byte AES key.
 * ciphertext = AES-256-GCM(iv=12 random bytes, tag=128 bits) of UTF-8 JSON containing exactly
 * {"endpoint":"EXPECTED_HTTPS_ENDPOINT","token":"GATEWAY_BOOTSTRAP_TOKEN"}.
 * AAD is the UTF-8 string "PEOC1|PUBLIC_CHALLENGE_BASE64|EXPECTED_HTTPS_ENDPOINT".
 * The exact expected endpoint is a non-secret instrumentation argument and is checked again after
 * decrypt. The listener closes immediately after its one accepted packet, including on rejection.
 * Clear buffers are overwritten best effort; JVM Strings remain process-memory-only until GC.
 *
 * A remote turn is stored in the ordinary conversation exactly like a local one, so a method that
 * sends adds its question and answer to the owner's transcript; every other method must add
 * nothing. Each method asserts its own exact expected row delta.
 *
 * Root execution must validate the host's 2,048-token output cap before authorizing paid methods;
 * the current app protocol enforces 60 seconds but has no per-request provider-token override.
 * Test statuses never contain model output. Local conversations/memories are not seeded or cleared.
 */
@RunWith(AndroidJUnit4::class)
class Fold8OpenClawLiveAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var phase = "gating"

    /**
     * Run after a confirmed pairing/token issuance and a host-recorded force-stop/relaunch.
     * This method never imports, receives, replaces, or prints any credential. The resulting
     * authentication is evidence of stored-credential reuse; the host supplies process-restart
     * evidence separately because Activity recreation is not a process restart.
     */
    @Test
    fun reconnectsUsingStoredDeviceCredentialWithoutBootstrap() = guardedAcceptance(requirePaidApproval = false) {
        phase = "stored_credential_startup"
        val expectedEndpoint = expectedEndpoint().replaceFirst("https://", "wss://") + "/"
        val application = instrumentation.targetContext.applicationContext as PersonalEdgeApplication
        val repository = application.container.conversations
        val conversationCount = repository.conversationCount()
        val messageCount = repository.messageCount()
        val memoryCount = application.container.memories.count()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val viewModel = scenario.viewModel()
            try {
                awaitState("Stored remote configuration was not restored.", 10_000) {
                    val state = viewModel.remoteState.value
                    state.foreground && state.configured && !state.busy
                }
                assertFalse("The local app has an active turn or recording.", viewModel.uiState.value.isBusy)
                assertTrue("The local composer has an attachment.", viewModel.uiState.value.pendingAttachment == null)
                assertEquals("Stored endpoint does not match the reviewed host.",
                    expectedEndpoint, viewModel.remoteState.value.endpointUrl)
                delay(1_000)
                assertEquals(OpenClawGatewayUiState.DISCONNECTED, viewModel.remoteState.value.connection)
                assertFalse(viewModel.remoteState.value.running)
                assertEquals("", viewModel.remoteState.value.sentPrompt)
                phase = "stored_credential_authentication"
                scenario.onActivity {
                    viewModel.selectRemote(true)
                    viewModel.connectRemote()
                }
                awaitState("The stored credential did not authenticate without bootstrap.", 30_000) {
                    viewModel.remoteState.value.connection == OpenClawGatewayUiState.CONNECTED
                }
                assertFalse(viewModel.remoteState.value.running)
                assertEquals("", viewModel.remoteState.value.sentPrompt)
                emit("stored_credential_reconnect_passed", extras = Bundle().apply {
                    putBoolean("remote_authenticated", true)
                    putBoolean("remote_bootstrap_received", false)
                    putBoolean("remote_initially_disconnected", true)
                    putInt("remote_submissions", 0)
                })
            } finally {
                scenario.onActivity {
                    viewModel.disconnectRemote()
                    viewModel.selectRemote(false)
                }
                awaitState("Stored-credential connection cleanup did not complete.", 5_000) {
                    viewModel.remoteState.value.connection == OpenClawGatewayUiState.DISCONNECTED &&
                        !viewModel.remoteState.value.running
                }
            }
        }
        phase = "stored_credential_local_data_preservation"
        assertEquals("Local conversation count changed.", conversationCount, repository.conversationCount())
        assertEquals("Local message count changed.", messageCount, repository.messageCount())
        assertEquals("Local memory count changed.", memoryCount, application.container.memories.count())
        emit("local_data_counts_preserved", extras = Bundle().apply {
            putBoolean("remote_local_conversation_count_unchanged", true)
            putBoolean("remote_local_message_count_unchanged", true)
            putBoolean("remote_local_memory_count_unchanged", true)
        })
    }

    @Test
    fun pairsAndReceivesOneUsefulKoreanAnswer() = guardedAcceptance(requirePaidApproval = true) {
        withConnectedRemote(expectedStoredMessages = 2) { scenario, viewModel ->
            phase = "fixed_korean_question"
            scenario.onActivity {
                viewModel.updateRemotePrompt(KOREAN_QUESTION)
                viewModel.sendRemotePrompt()
            }
            awaitState("Remote question was not accepted by the app.", 5_000) {
                viewModel.remoteState.value.sentPrompt == KOREAN_QUESTION
            }
            awaitState("Remote question did not reach a bounded terminal state.", 85_000) {
                !viewModel.remoteState.value.running
            }
            val state = viewModel.remoteState.value
            val answer = state.answer
            val useful = "서울" in answer && "수도" in answer && "대한민국" in answer &&
                "REMOTE-KO-OK" in answer && answer.length <= 2_048
            assertTrue("A confirmed, useful Korean answer was not observed.",
                state.notice == "답변을 받았습니다." && useful)
            emit("korean_answer_passed", extras = Bundle().apply {
                putInt("remote_submissions", 1)
                putInt("remote_answer_utf8_bytes", answer.toByteArray(Charsets.UTF_8).size)
                putBoolean("remote_korean_claims_passed", useful)
                putBoolean("remote_terminal_answer_confirmed", true)
            })
        }
    }

    /**
     * The 2026-09-06 owner change: one send, no per-question approval, stored like a local turn.
     *
     * Durability is asserted by reading the two rows back out of Room, not by trusting the screen.
     */
    @Test
    fun oneSendNeedsNoApprovalAndIsStoredInTheSharedConversation() =
        guardedAcceptance(requirePaidApproval = true) {
            withStoredCredentialRemote({ 2 }) { scenario, viewModel, repository ->
                phase = "send_without_approval"
                // There is no approval step to call: this is the whole production send path.
                scenario.onActivity {
                    viewModel.updateRemotePrompt(KOREAN_QUESTION)
                    viewModel.sendRemotePrompt()
                }
                awaitState("The send did not start without a separate approval.", 10_000) {
                    val state = viewModel.remoteState.value
                    state.running && state.sentPrompt == KOREAN_QUESTION
                }
                awaitState("The question was not stored before the run finished.", 15_000) {
                    viewModel.remoteState.value.questionStored
                }
                awaitState("Remote question did not reach a bounded terminal state.", 85_000) {
                    !viewModel.remoteState.value.running
                }

                phase = "answer_and_storage"
                val state = viewModel.remoteState.value
                val answer = state.answer
                val useful = "서울" in answer && "수도" in answer && "대한민국" in answer &&
                    "REMOTE-KO-OK" in answer && answer.length <= 2_048
                assertTrue("A confirmed, useful Korean answer was not observed.",
                    state.notice == "답변을 받았습니다." && useful)
                awaitState("The answer was not stored in the conversation.", 10_000) {
                    viewModel.remoteState.value.answerStored
                }
                assertStoredPair(viewModel, repository, KOREAN_QUESTION, answer)
                emit("send_stored_in_shared_conversation", extras = Bundle().apply {
                    putInt("remote_submissions", 1)
                    putInt("remote_answer_utf8_bytes", answer.toByteArray(Charsets.UTF_8).size)
                    putBoolean("remote_korean_claims_passed", useful)
                    putBoolean("remote_question_stored", true)
                    putBoolean("remote_answer_stored", true)
                })
            }
        }

    /**
     * Cancellation on the same stored-credential path, and what a cancelled turn leaves behind.
     *
     * Two things were measured before this shape was chosen. First, this Gateway hands the app its
     * answer when the run completes rather than as deltas, so a long question produces no visible
     * text at all and simply reaches its own 60-second timeout; waiting for a partial answer to
     * cancel cannot work here. Second, OpenClaw 2026.8.1 surfaces an aborted run to the client as
     * an error rather than an aborted terminal, even though its agent channel reports the same run
     * as aborted. The client now requires both pieces of evidence — its own accepted `chat.abort`
     * and that aborted agent terminal — before the error wrapper may be read as the cancellation
     * it asked for, so a confirmed cancellation is expected here again.
     *
     * What is asserted is what matters: the cancel stops the run promptly instead of letting it
     * run to its timeout, the terminal is a confirmed cancellation rather than a failure, and the
     * turn stores the question without inventing an answer that never arrived.
     */
    @Test
    fun cancelsOneStartedAnswerAndStoresOnlyWhatArrived() = guardedAcceptance(
        requirePaidApproval = true,
        requireCancellationApproval = true,
    ) {
        var expectedRows = 1
        withStoredCredentialRemote({ expectedRows }) { scenario, viewModel, repository ->
            phase = "cancellation_question"
            scenario.onActivity {
                viewModel.updateRemotePrompt(CANCELLATION_QUESTION)
                viewModel.sendRemotePrompt()
            }
            awaitState("The cancellable run did not start.", 15_000) {
                viewModel.remoteState.value.running
            }
            awaitState("The question was not stored before cancellation.", 15_000) {
                viewModel.remoteState.value.questionStored
            }
            // `running` is true from dispatch, but the run id only exists once start returns.
            // Cancelling before that seals the connection and reports an unknown outcome, which
            // is a different behavior and cannot stand in for a confirmed remote cancellation.
            delay(6_000)
            assertTrue("The run ended before it could be cancelled.",
                viewModel.remoteState.value.running)

            phase = "cancellation_request"
            val cancelledAt = SystemClock.elapsedRealtime()
            scenario.onActivity { viewModel.cancelRemoteTurn() }
            assertTrue("Cancellation was not marked as requested.",
                viewModel.remoteState.value.cancellationRequested)
            awaitState("Remote cancellation was not observed within the run bound.", 75_000) {
                !viewModel.remoteState.value.running
            }
            val stopMillis = SystemClock.elapsedRealtime() - cancelledAt
            val terminal = viewModel.remoteState.value
            val cancellationConfirmed = terminal.notice == "원격 실행 취소가 확인되었습니다."
            // Well inside the run timeout: the cancel stopped it, it did not expire.
            assertTrue("The run did not stop promptly after the cancel.", stopMillis < 20_000)
            assertTrue("The remote cancellation terminal was not confirmed.", cancellationConfirmed)

            phase = "cancelled_turn_storage"
            val answer = terminal.answer
            expectedRows = if (answer.isBlank()) 1 else 2
            if (answer.isBlank()) {
                // Nothing arrived, so nothing may be stored as an answer.
                assertFalse("A blank answer must not be reported as stored.", terminal.answerStored)
                val conversationId = checkNotNull(viewModel.chatHistory.value.activeConversationId)
                val stored = repository.listMessages(conversationId).takeLast(1)
                assertEquals("The cancelled turn must leave the question and nothing else.",
                    listOf(MessageRole.USER), stored.map { it.role })
                assertEquals("The stored question is not the owner's typed text.",
                    CANCELLATION_QUESTION, stored.single().text)
            } else {
                awaitState("The partial answer was not stored after cancellation.", 10_000) {
                    viewModel.remoteState.value.answerStored
                }
                assertStoredPair(viewModel, repository, CANCELLATION_QUESTION, answer)
            }
            emit("cancellation_observed", extras = Bundle().apply {
                putInt("remote_submissions", 1)
                putBoolean("remote_cancellation_confirmed", cancellationConfirmed)
                putInt("remote_answer_utf8_bytes_at_cancel", answer.toByteArray(Charsets.UTF_8).size)
                putBoolean("remote_question_stored", true)
                putBoolean("remote_answer_stored", terminal.answerStored)
                // App-authored fixed prose only; it carries no owner or provider content, and it
                // is what tells a failed run apart from a sealed connection next time.
                putString("remote_terminal_notice", terminal.notice.take(96))
                putLong("remote_cancel_to_terminal_millis", stopMillis)
            })
        }
    }

    /**
     * A cancel tapped in the window before the run id exists must not close the connection.
     *
     * That window is real on this device: the run is marked running at dispatch, while the run id
     * only arrives when start returns over Tailscale. The old build sealed the socket there and
     * reported an unknown outcome, leaving the remote run to cost until its own timeout.
     */
    @Test
    fun holdsACancelTappedBeforeTheRunIdExists() = guardedAcceptance(
        requirePaidApproval = true,
        requireCancellationApproval = true,
    ) {
        var expectedRows = 1
        withStoredCredentialRemote({ expectedRows }) { scenario, viewModel, repository ->
            phase = "immediate_cancellation"
            scenario.onActivity {
                viewModel.updateRemotePrompt(CANCELLATION_QUESTION)
                viewModel.sendRemotePrompt()
            }
            awaitState("The cancellable run did not start.", 15_000) {
                viewModel.remoteState.value.running
            }
            // No grace at all: cancel inside the pre-run-id window this test exists for.
            scenario.onActivity { viewModel.cancelRemoteTurn() }
            val requested = viewModel.remoteState.value
            assertTrue("Cancellation was not marked as requested.", requested.cancellationRequested)
            assertEquals("The cancel closed the connection instead of being held.",
                OpenClawGatewayUiState.CONNECTED, requested.connection)

            awaitState("The held cancel did not reach a terminal.", 75_000) {
                !viewModel.remoteState.value.running
            }
            val terminal = viewModel.remoteState.value
            assertEquals("The connection must survive a held cancel.",
                OpenClawGatewayUiState.CONNECTED, terminal.connection)
            assertFalse("The sealed-connection path must no longer be reachable here.",
                terminal.notice.contains("전송 중 연결을 닫았습니다"))
            awaitState("The question was not stored.", 10_000) {
                viewModel.remoteState.value.questionStored
            }
            expectedRows = if (terminal.answer.isBlank()) 1 else 2
            emit("held_cancellation_observed", extras = Bundle().apply {
                putInt("remote_submissions", 1)
                putBoolean("remote_connection_retained", true)
                putBoolean("remote_cancellation_confirmed",
                    terminal.notice == "원격 실행 취소가 확인되었습니다.")
                putString("remote_terminal_notice", terminal.notice.take(96))
                putInt("remote_answer_utf8_bytes_at_cancel",
                    terminal.answer.toByteArray(Charsets.UTF_8).size)
            })
        }
    }

    /**
     * A long answer must complete instead of expiring, now that the run timeout is three minutes.
     *
     * The per-run timeout the Gateway applies is the one this app sends, so the old 60 seconds was
     * the whole reason a long question produced nothing at all: this mode delivers its text at
     * completion rather than as deltas, and the run died before that point.
     */
    @Test
    fun completesALongAnswerWithinTheRaisedRunTimeout() =
        guardedAcceptance(requirePaidApproval = true) {
            withStoredCredentialRemote({ 2 }) { scenario, viewModel, repository ->
                phase = "long_answer"
                scenario.onActivity {
                    viewModel.updateRemotePrompt(CANCELLATION_QUESTION)
                    viewModel.sendRemotePrompt()
                }
                awaitState("The long run did not start.", 15_000) {
                    viewModel.remoteState.value.running
                }
                awaitState("The long answer did not reach a terminal in time.", 210_000) {
                    !viewModel.remoteState.value.running
                }
                val terminal = viewModel.remoteState.value
                assertEquals("The long answer did not complete.",
                    "답변을 받았습니다.", terminal.notice)
                assertTrue("The long answer was empty.", terminal.answer.isNotBlank())
                awaitState("The long answer was not stored.", 10_000) {
                    viewModel.remoteState.value.answerStored
                }
                assertStoredPair(viewModel, repository, CANCELLATION_QUESTION, terminal.answer)
                emit("long_answer_completed", extras = Bundle().apply {
                    putInt("remote_submissions", 1)
                    putInt("remote_answer_utf8_bytes",
                        terminal.answer.toByteArray(Charsets.UTF_8).size)
                    putBoolean("remote_answer_stored", true)
                })
            }
        }

    /**
     * Network loss during a live run must seal it without inventing an outcome.
     *
     * The host drops the phone's radios after this method reports that its run is in flight, and
     * restores them afterwards. What matters is that the app stops claiming anything it cannot
     * observe: no completed answer, no confirmed cancellation, and no answer row for text that
     * never arrived.
     */
    @Test
    fun networkLossDuringARunSealsItWithoutInventingAnAnswer() =
        guardedAcceptance(requirePaidApproval = true) {
            var expectedRows = 1
            withStoredCredentialRemote({ expectedRows }) { scenario, viewModel, repository ->
                phase = "run_before_network_drop"
                scenario.onActivity {
                    viewModel.updateRemotePrompt(CANCELLATION_QUESTION)
                    viewModel.sendRemotePrompt()
                }
                awaitState("The run did not start.", 15_000) {
                    viewModel.remoteState.value.running
                }
                awaitState("The question was not stored before the drop.", 15_000) {
                    viewModel.remoteState.value.questionStored
                }
                phase = "awaiting_owner_network_drop"
                emit("awaiting_owner_network_drop")
                awaitState("The host-directed network interruption was not observed.", 150_000) {
                    !viewModel.remoteState.value.running
                }

                phase = "sealed_run"
                val terminal = viewModel.remoteState.value
                assertEquals("Network loss must close the connection.",
                    OpenClawGatewayUiState.DISCONNECTED, terminal.connection)
                assertFalse("A sealed run must not claim a completed answer.",
                    terminal.notice == "답변을 받았습니다.")
                assertFalse("A sealed run must not claim a confirmed cancellation.",
                    terminal.notice == "원격 실행 취소가 확인되었습니다.")
                expectedRows = if (terminal.answer.isBlank()) 1 else 2
                emit("network_loss_during_run_observed", extras = Bundle().apply {
                    putInt("remote_submissions", 1)
                    putBoolean("remote_question_stored", terminal.questionStored)
                    putBoolean("remote_answer_stored", terminal.answerStored)
                    putInt("remote_answer_utf8_bytes_at_loss",
                        terminal.answer.toByteArray(Charsets.UTF_8).size)
                    putString("remote_terminal_notice", terminal.notice.take(96))
                })
            }
        }

    /**
     * Half one of the process-restart check: it deliberately leaves a run in flight.
     *
     * Instrumentation completion kills the app process, so ending here with the run live is the
     * process kill itself — no host trickery, and closer to a real crash or eviction than Activity
     * recreation. [aRunKilledWithItsProcessLeavesTheQuestionAndNoInventedAnswer] inspects what
     * survived, and must be run next.
     */
    @Test
    fun startsARunLeftInFlightForTheProcessRestartCheck() =
        guardedAcceptance(requirePaidApproval = true) {
            phase = "run_left_in_flight"
            val expectedEndpoint = expectedEndpoint().replaceFirst("https://", "wss://") + "/"
            val application = instrumentation.targetContext.applicationContext as PersonalEdgeApplication
            val repository = application.container.conversations
            val messageCount = repository.messageCount()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val viewModel = scenario.viewModel()
                awaitState("Stored remote configuration was not restored.", 10_000) {
                    val state = viewModel.remoteState.value
                    state.foreground && state.configured && !state.busy
                }
                assertEquals("Stored endpoint does not match the reviewed host.",
                    expectedEndpoint, viewModel.remoteState.value.endpointUrl)
                scenario.onActivity {
                    viewModel.selectRemote(true)
                    viewModel.connectRemote()
                }
                awaitState("The stored credential did not authenticate.", 30_000) {
                    viewModel.remoteState.value.connection == OpenClawGatewayUiState.CONNECTED
                }
                scenario.onActivity {
                    viewModel.updateRemotePrompt(CANCELLATION_QUESTION)
                    viewModel.sendRemotePrompt()
                }
                awaitState("The run did not start.", 15_000) {
                    viewModel.remoteState.value.running
                }
                awaitState("The question was not stored before the kill.", 15_000) {
                    viewModel.remoteState.value.questionStored
                }
                assertEquals("Only the question may be stored at this point.",
                    messageCount + 1, repository.messageCount())
                emit("run_left_in_flight", extras = Bundle().apply {
                    putInt("remote_submissions", 1)
                    putBoolean("remote_question_stored", true)
                    putBoolean("remote_answer_stored", viewModel.remoteState.value.answerStored)
                })
            }
            // Deliberately no disconnect: the process dies with the run still live.
        }

    /**
     * Half two: a fresh process after a run was killed with it.
     *
     * The app must come back with nothing connected and nothing running, the question the owner
     * asked must still be in the transcript, and no answer may have been attributed to it.
     */
    @Test
    fun aRunKilledWithItsProcessLeavesTheQuestionAndNoInventedAnswer() =
        guardedAcceptance(requirePaidApproval = false) {
            phase = "after_process_restart"
            val application = instrumentation.targetContext.applicationContext as PersonalEdgeApplication
            val repository = application.container.conversations
            val messageCount = repository.messageCount()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val viewModel = scenario.viewModel()
                awaitState("The restarted app did not settle.", 10_000) {
                    val state = viewModel.remoteState.value
                    state.foreground && state.configured && !state.busy
                }
                delay(1_000)
                val state = viewModel.remoteState.value
                assertEquals("A restarted process must not reconnect on its own.",
                    OpenClawGatewayUiState.DISCONNECTED, state.connection)
                assertFalse("A restarted process must not resume a run.", state.running)
                assertEquals("A restarted process must not restore remote text.", "", state.sentPrompt)
                assertEquals("A restarted process must not restore an answer.", "", state.answer)

                val conversationId = checkNotNull(viewModel.chatHistory.value.activeConversationId)
                val stored = repository.listMessages(conversationId)
                val last = stored.last()
                assertEquals("The killed run must leave its question as the last row.",
                    MessageRole.USER, last.role)
                assertEquals("The stored question is not the owner's typed text.",
                    CANCELLATION_QUESTION, last.text)
                emit("process_restart_observed", extras = Bundle().apply {
                    putInt("remote_submissions", 0)
                    putBoolean("remote_automatic_reconnect", false)
                    putBoolean("remote_answer_invented", false)
                })
            }
            assertEquals("A restart must not add or remove transcript rows.",
                messageCount, repository.messageCount())
        }

    /**
     * The context picker, proved by what actually reached the model.
     *
     * A first turn plants a fixed nonce in the transcript. The second turn asks a question that
     * does not contain the nonce and selects only that stored row as its reference, so the nonce
     * can appear in the answer only if the selected quote genuinely left the device. It also
     * checks the picker offers nothing until it is opened and preselects nothing.
     */
    @Test
    fun selectedContextIsWhatReachesTheModel() = guardedAcceptance(requirePaidApproval = true) {
        withStoredCredentialRemote({ 4 }) { scenario, viewModel, repository ->
            phase = "plant_nonce"
            scenario.onActivity {
                viewModel.updateRemotePrompt(CONTEXT_NONCE_QUESTION)
                viewModel.sendRemotePrompt()
            }
            awaitState("The nonce turn did not complete.", 210_000) {
                !viewModel.remoteState.value.running &&
                    viewModel.remoteState.value.sentPrompt == CONTEXT_NONCE_QUESTION
            }
            awaitState("The nonce turn was not stored.", 10_000) {
                viewModel.remoteState.value.answerStored
            }

            phase = "open_picker"
            assertTrue("The picker must offer nothing before it is opened.",
                viewModel.remoteState.value.contextItems.isEmpty())
            scenario.onActivity {
                viewModel.updateRemotePrompt(CONTEXT_RECALL_QUESTION)
                viewModel.loadRemoteContext()
            }
            awaitState("The picker did not offer the local conversation.", 15_000) {
                viewModel.remoteState.value.contextItems.isNotEmpty()
            }
            assertTrue("The picker must preselect nothing.",
                viewModel.remoteState.value.selectedContextKeys.isEmpty())
            val plantedKey = viewModel.remoteState.value.contextItems
                .firstOrNull { CONTEXT_NONCE in it.text }
                ?.key
            assertTrue("The planted row was not offered as a reference.", plantedKey != null)

            phase = "send_with_selected_context"
            scenario.onActivity {
                viewModel.selectRemoteContext(checkNotNull(plantedKey), true)
                viewModel.finishRemoteContextSelection()
                viewModel.sendRemotePrompt()
            }
            awaitState("The context turn did not complete.", 210_000) {
                !viewModel.remoteState.value.running &&
                    viewModel.remoteState.value.sentPrompt == CONTEXT_RECALL_QUESTION
            }
            val answer = viewModel.remoteState.value.answer
            // The current question never contains the nonce, so this can only come from the quote.
            val quoteReached = CONTEXT_NONCE in answer
            assertTrue("The selected quote did not reach the model.", quoteReached)
            assertTrue("The selection must be cleared after sending.",
                viewModel.remoteState.value.selectedContextKeys.isEmpty() &&
                    viewModel.remoteState.value.contextItems.isEmpty())
            awaitState("The context turn was not stored.", 10_000) {
                viewModel.remoteState.value.answerStored
            }
            // Only the owner's own question is stored, never the quotes composed around it.
            assertStoredPair(viewModel, repository, CONTEXT_RECALL_QUESTION, answer)
            emit("selected_context_reached_model", extras = Bundle().apply {
                putInt("remote_submissions", 2)
                putBoolean("remote_quote_reached_model", quoteReached)
                putBoolean("remote_picker_preselected_nothing", true)
                putInt("remote_answer_utf8_bytes", answer.toByteArray(Charsets.UTF_8).size)
            })
        }
    }

    /** The stored pair must be readable back from Room, in order, with the exact text. */
    private suspend fun assertStoredPair(
        viewModel: PersonalEdgeViewModel,
        repository: ConversationRepository,
        question: String,
        answer: String,
    ) {
        val conversationId = checkNotNull(viewModel.chatHistory.value.activeConversationId)
        val stored = repository.listMessages(conversationId).takeLast(2)
        assertEquals("The stored pair is not one question and one answer.",
            listOf(MessageRole.USER, MessageRole.ASSISTANT), stored.map { it.role })
        assertEquals("The stored question is not the owner's typed text.", question, stored.first().text)
        assertEquals("The stored answer is not the received answer.", answer, stored.last().text)
    }

    /**
     * Connects with the stored device credential instead of a fresh bootstrap pairing.
     *
     * No credential crosses the host boundary here and no device registration is created, so this
     * lane is only valid after pairing already happened. [expectedStoredMessages] is the exact
     * transcript row delta the method must produce, asserted after the connection is closed.
     */
    private suspend fun withStoredCredentialRemote(
        expectedStoredMessages: () -> Int = { 0 },
        action: suspend (ActivityScenario<MainActivity>, PersonalEdgeViewModel, ConversationRepository) -> Unit,
    ) {
        phase = "stored_credential_startup"
        val expectedEndpoint = expectedEndpoint().replaceFirst("https://", "wss://") + "/"
        val application = instrumentation.targetContext.applicationContext as PersonalEdgeApplication
        val repository = application.container.conversations
        val conversationCount = repository.conversationCount()
        val messageCount = repository.messageCount()
        val memoryCount = application.container.memories.count()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val viewModel = scenario.viewModel()
            try {
                awaitState("Stored remote configuration was not restored.", 10_000) {
                    val state = viewModel.remoteState.value
                    state.foreground && state.configured && !state.busy
                }
                assertFalse("The local app has an active turn or recording.", viewModel.uiState.value.isBusy)
                assertEquals("Stored endpoint does not match the reviewed host.",
                    expectedEndpoint, viewModel.remoteState.value.endpointUrl)
                phase = "stored_credential_authentication"
                scenario.onActivity {
                    viewModel.selectRemote(true)
                    viewModel.connectRemote()
                }
                awaitState("The stored credential did not authenticate without bootstrap.", 30_000) {
                    viewModel.remoteState.value.connection == OpenClawGatewayUiState.CONNECTED
                }
                action(scenario, viewModel, repository)
            } finally {
                scenario.onActivity {
                    viewModel.disconnectRemote()
                    viewModel.selectRemote(false)
                }
                awaitState("Stored-credential connection cleanup did not complete.", 5_000) {
                    viewModel.remoteState.value.connection == OpenClawGatewayUiState.DISCONNECTED &&
                        !viewModel.remoteState.value.running
                }
            }
        }
        phase = "stored_credential_data_expectation"
        val storedMessages = repository.messageCount() - messageCount
        val newConversations = repository.conversationCount() - conversationCount
        val expectedRows = expectedStoredMessages()
        assertEquals("Stored transcript rows did not match what this method actually received.",
            expectedRows.toLong(), storedMessages.toLong())
        assertTrue("Local conversation count changed unexpectedly.",
            newConversations == 0L || (newConversations == 1L && expectedRows > 0))
        assertEquals("Local memory count changed.", memoryCount, application.container.memories.count())
        emit("local_data_counts_expected", extras = Bundle().apply {
            putLong("remote_stored_transcript_messages", storedMessages)
            putLong("remote_new_conversations", newConversations)
            putBoolean("remote_local_memory_count_unchanged", true)
        })
    }

    @Test
    fun backgroundAndRecreationRequireExplicitReconnection() = guardedAcceptance(requirePaidApproval = false) {
        withConnectedRemote { scenario, viewModel ->
            phase = "background_revoke"
            scenario.moveToState(Lifecycle.State.CREATED)
            awaitState("Background did not close the remote connection.", 5_000) {
                !viewModel.remoteState.value.foreground &&
                    viewModel.remoteState.value.connection == OpenClawGatewayUiState.DISCONNECTED
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            delay(1_000)
            assertEquals(OpenClawGatewayUiState.DISCONNECTED, viewModel.remoteState.value.connection)
            assertFalse(viewModel.remoteState.value.running)
            phase = "recreate_without_reconnect"
            scenario.recreate()
            val recreated = scenario.viewModel()
            awaitState("Recreated Activity did not become foreground.", 5_000) {
                recreated.remoteState.value.foreground
            }
            assertEquals(OpenClawGatewayUiState.DISCONNECTED, recreated.remoteState.value.connection)
            assertFalse(recreated.remoteState.value.running)
            emit("background_recreation_passed", extras = Bundle().apply {
                putInt("remote_submissions", 0)
                putBoolean("remote_automatic_reconnect", false)
            })
        }
    }

    @Test
    fun networkLossClosesConnectionWithoutStartingAModel() = guardedAcceptance(requirePaidApproval = false) {
        withConnectedRemote { _, viewModel ->
            phase = "awaiting_owner_network_drop"
            emit("awaiting_owner_network_drop")
            awaitState("The host-directed network interruption was not observed.", 120_000) {
                viewModel.remoteState.value.connection != OpenClawGatewayUiState.CONNECTED
            }
            assertFalse(viewModel.remoteState.value.running)
            assertEquals("", viewModel.remoteState.value.sentPrompt)
            emit("network_loss_observed", extras = Bundle().apply {
                putInt("remote_submissions", 0)
                putBoolean("remote_connected", false)
            })
        }
    }

    @Test
    fun cancelsOneStartedRemoteAnswer() = guardedAcceptance(
        requirePaidApproval = true,
        requireCancellationApproval = true,
    ) {
        // The question and the partial answer the owner already saw are both stored.
        withConnectedRemote(expectedStoredMessages = 2) { scenario, viewModel ->
            phase = "cancellation_question"
            scenario.onActivity {
                viewModel.updateRemotePrompt(CANCELLATION_QUESTION)
                viewModel.sendRemotePrompt()
            }
            awaitState("No active remote answer was available for cancellation.", 65_000) {
                val current = viewModel.remoteState.value
                current.running && current.answer.isNotBlank()
            }
            scenario.onActivity { viewModel.cancelRemoteTurn() }
            awaitState("Remote cancellation was not observed within the run bound.", 75_000) {
                !viewModel.remoteState.value.running
            }
            val cancellationConfirmed = viewModel.remoteState.value.notice == "원격 실행 취소가 확인되었습니다."
            emit("cancellation_observed", extras = Bundle().apply {
                putInt("remote_submissions", 1)
                putBoolean("remote_cancellation_confirmed", cancellationConfirmed)
            })
            assertTrue("The remote cancellation terminal was not confirmed.", cancellationConfirmed)
        }
    }

    private fun guardedAcceptance(
        requirePaidApproval: Boolean,
        requireCancellationApproval: Boolean = false,
        action: suspend () -> Unit,
    ) = runBlocking {
        assumeTrue("Physical remote acceptance requires explicit opt-in.", argument("liveOpenClaw") == "true")
        assumeTrue("Physical remote acceptance is restricted to the owner's Fold8.", Build.MODEL == "SM-F971N")
        assumeTrue("Physical remote acceptance requires the signed release target.", !BuildConfig.DEBUG)
        if (requirePaidApproval) {
            assumeTrue("Provider inference requires fresh owner approval for this invocation.",
                argument("liveOpenClawProvider") == "true")
            assumeTrue("The host output cap must first be independently verified.",
                argument("verifiedRemoteOutputTokenCap") == "2048")
        }
        if (requireCancellationApproval) {
            assumeTrue("Remote paid cancellation acceptance requires explicit opt-in.",
                argument("liveOpenClawCancel") == "true")
        }
        try {
            action()
        } catch (failure: Throwable) {
            // Never attach a cause: library exception prose may contain remote content or a token.
            emit("failed", extras = Bundle().apply {
                putString("remote_failure_phase", phase)
                putString("remote_failure_type", failure.javaClass.simpleName.take(96))
            })
            throw AssertionError("Fold8 remote acceptance failed at $phase.")
        }
    }

    /**
     * [expectedStoredMessages] is the exact number of transcript rows this method should add.
     *
     * A remote turn is stored in the ordinary conversation, so a sending method adds its question
     * and its answer. Zero still means the strict old boundary: connecting, pairing, backgrounding
     * and recreating must not write anything.
     */
    private suspend fun withConnectedRemote(
        expectedStoredMessages: Int = 0,
        action: suspend (ActivityScenario<MainActivity>, PersonalEdgeViewModel) -> Unit,
    ) {
        phase = "secure_bootstrap"
        val expectedEndpoint = expectedEndpoint()
        val bootstrap = receiveBootstrap(expectedEndpoint)
        val application = instrumentation.targetContext.applicationContext as PersonalEdgeApplication
        val repository = application.container.conversations
        val conversationCount = repository.conversationCount()
        val messageCount = repository.messageCount()
        val memoryCount = application.container.memories.count()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val viewModel = scenario.viewModel()
            try {
                assertFalse("The local app has an active turn or recording.", viewModel.uiState.value.isBusy)
                assertTrue("The local composer has an attachment.", viewModel.uiState.value.pendingAttachment == null)
                phase = "configure_remote"
                scenario.onActivity {
                    viewModel.selectRemote(true)
                    viewModel.configureRemote(bootstrap.endpoint, bootstrap.token, "")
                }
                bootstrap.clear()
                awaitState("Remote configuration did not save safely.", 15_000) {
                    val state = viewModel.remoteState.value
                    state.selected && !state.busy && state.configured &&
                        state.endpointUrl == expectedEndpoint.replaceFirst("https://", "wss://") + "/" &&
                        state.notice == "연결 정보를 저장했습니다. 사용 동의 후 연결하세요."
                }
                phase = "pairing_and_authentication"
                connectWithBoundedPairingWait(scenario, viewModel)
                emit("connected", extras = Bundle().apply { putBoolean("remote_authenticated", true) })
                action(scenario, viewModel)
            } finally {
                bootstrap.clear()
                scenario.onActivity {
                    viewModel.disconnectRemote()
                    viewModel.selectRemote(false)
                }
                awaitState("Remote connection cleanup did not complete.", 5_000) {
                    viewModel.remoteState.value.connection == OpenClawGatewayUiState.DISCONNECTED &&
                        !viewModel.remoteState.value.running
                }
            }
        }
        phase = "local_data_preservation"
        val storedMessages = repository.messageCount() - messageCount
        val newConversations = repository.conversationCount() - conversationCount
        assertEquals("Stored transcript rows did not match the questions this method sent.",
            expectedStoredMessages.toLong(), storedMessages.toLong())
        // A stored turn may open the first conversation on an empty device, and nothing else.
        assertTrue("Local conversation count changed unexpectedly.",
            newConversations == 0L || (newConversations == 1L && expectedStoredMessages > 0))
        assertEquals("Local memory count changed.", memoryCount, application.container.memories.count())
        emit("local_data_counts_expected", extras = Bundle().apply {
            putLong("remote_stored_transcript_messages", storedMessages.toLong())
            putLong("remote_new_conversations", newConversations.toLong())
            putBoolean("remote_local_memory_count_unchanged", true)
        })
    }

    private suspend fun connectWithBoundedPairingWait(
        scenario: ActivityScenario<MainActivity>,
        viewModel: PersonalEdgeViewModel,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 180_000
        var attempts = 0
        var waitingReported = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = viewModel.remoteState.value
            if (current.connection == OpenClawGatewayUiState.CONNECTED) return
            if (current.connection == OpenClawGatewayUiState.PAIRING_OR_AUTH_REQUIRED && !waitingReported) {
                waitingReported = true
                emit("pairing_waiting", extras = Bundle().apply { putBoolean("remote_pairing_required", true) })
            }
            if (!current.busy && current.connection != OpenClawGatewayUiState.CONNECTING && attempts < 24) {
                scenario.onActivity { viewModel.connectRemote() }
                attempts++
            }
            delay(5_000)
        }
        throw AssertionError("Authenticated remote pairing did not complete within its bound.")
    }

    private fun receiveBootstrap(expectedEndpoint: String): BootstrapCredential {
        phase = "bootstrap_key_generate"
        val random = SecureRandom()
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(3_072, random) }.generateKeyPair()
        val publicChallenge = ByteArray(32).also(random::nextBytes)
        val challenge = Base64.encodeToString(publicChallenge, Base64.NO_WRAP)
        val associatedData = "PEOC1|$challenge|$expectedEndpoint".toByteArray(Charsets.UTF_8)
        val server = ServerSocket().apply {
            reuseAddress = false
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
            soTimeout = 180_000
        }
        server.use {
            emit("bootstrap_ready", extras = Bundle().apply {
                putString("remote_bootstrap_protocol", "PEOC1_RSA_OAEP_SHA256_AES256_GCM")
                putInt("remote_bootstrap_port", server.localPort)
                putString("remote_bootstrap_public_key_spki", Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
                putString("remote_bootstrap_challenge", challenge)
            })
            server.accept().use { socket ->
                phase = "bootstrap_socket_read"
                server.close() // One candidate connection only; no reusable credential service.
                check(socket.inetAddress.isLoopbackAddress)
                socket.soTimeout = 15_000
                val input = DataInputStream(socket.getInputStream())
                val length = input.readInt()
                check(length in 1..MAX_ENVELOPE_BYTES)
                val encoded = ByteArray(length)
                input.readFully(encoded)
                phase = "bootstrap_envelope_parse"
                val envelope = try {
                    JSONObject(encoded.toString(Charsets.UTF_8))
                } finally {
                    java.util.Arrays.fill(encoded, 0.toByte())
                }
                check(hasExactKeys(envelope, setOf("version", "wrappedKey", "iv", "ciphertext")))
                check(envelope.getInt("version") == 1)
                val wrapped = boundedBase64(envelope.getString("wrappedKey"), 384)
                check(wrapped.size == 384)
                val iv = boundedBase64(envelope.getString("iv"), 12)
                check(iv.size == 12)
                val encrypted = boundedBase64(envelope.getString("ciphertext"), 8_192)
                check(encrypted.size >= 17)
                phase = "bootstrap_key_unwrap"
                val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
                rsa.init(Cipher.DECRYPT_MODE, pair.private,
                    OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
                val aesKey = rsa.doFinal(wrapped)
                try {
                    check(aesKey.size == 32)
                    phase = "bootstrap_payload_decrypt"
                    val aes = Cipher.getInstance("AES/GCM/NoPadding")
                    aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
                    aes.updateAAD(associatedData)
                    val clear = aes.doFinal(encrypted)
                    try {
                        phase = "bootstrap_endpoint_validate"
                        check(clear.size <= 6_144)
                        val payload = JSONObject(clear.toString(Charsets.UTF_8))
                        check(hasExactKeys(payload, setOf("endpoint", "token")))
                        val endpoint = payload.getString("endpoint")
                        val token = payload.getString("token")
                        check(endpoint == expectedEndpoint)
                        check(token.isNotBlank() && token.length <= 2_910 && token.none(Char::isISOControl))
                        return BootstrapCredential(endpoint, token)
                    } finally { java.util.Arrays.fill(clear, 0.toByte()) }
                } finally {
                    // Direct Java calls stay available across the minified target/test APK ABI.
                    java.util.Arrays.fill(aesKey, 0.toByte())
                    java.util.Arrays.fill(wrapped, 0.toByte())
                    java.util.Arrays.fill(iv, 0.toByte())
                    java.util.Arrays.fill(encrypted, 0.toByte())
                    java.util.Arrays.fill(associatedData, 0.toByte())
                    java.util.Arrays.fill(publicChallenge, 0.toByte())
                }
            }
        }
    }

    private fun boundedBase64(value: String, maxDecodedBytes: Int): ByteArray {
        check(value.length <= ((maxDecodedBytes + 2) / 3) * 4)
        check(value.matches(Regex("[A-Za-z0-9+/]+={0,2}")))
        return Base64.decode(value, Base64.NO_WRAP).also {
            check(it.size <= maxDecodedBytes)
            check(Base64.encodeToString(it, Base64.NO_WRAP) == value)
        }
    }

    /** Direct Java iteration avoids adding a Kotlin Sequences facade to the release test ABI. */
    private fun hasExactKeys(value: JSONObject, expected: Set<String>): Boolean {
        val actual = java.util.HashSet<String>()
        val keys = value.keys()
        while (keys.hasNext()) actual.add(keys.next())
        return actual == expected
    }

    private fun expectedEndpoint(): String {
        val expected = argument("expectedRemoteEndpoint") ?: error("Expected endpoint missing.")
        val parsed = URI(expected)
        check(parsed.scheme == "https" && parsed.host == EXPECTED_HOST && parsed.port == -1)
        check(parsed.rawUserInfo == null && parsed.rawQuery == null && parsed.rawFragment == null)
        check(parsed.rawPath.isNullOrEmpty())
        return expected
    }

    private fun ActivityScenario<MainActivity>.viewModel(): PersonalEdgeViewModel {
        val reference = AtomicReference<PersonalEdgeViewModel>()
        onActivity { reference.set(ViewModelProvider(it)[PersonalEdgeViewModel::class.java]) }
        return reference.get()
    }

    private suspend fun awaitState(message: String, timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            delay(100)
        }
        assertTrue(message, condition())
    }

    private fun emit(status: String, extras: Bundle = Bundle()) {
        extras.putString("remote_live_stage", status)
        instrumentation.sendStatus(0, extras)
    }

    private fun argument(name: String): String? = InstrumentationRegistry.getArguments().getString(name)

    private class BootstrapCredential(val endpoint: String, private var value: String?) {
        val token: String get() = checkNotNull(value)
        fun clear() { value = null }
        override fun toString(): String = "BootstrapCredential(<redacted>)"
    }

    companion object {
        private const val MAX_ENVELOPE_BYTES = 16 * 1_024
        private const val EXPECTED_HOST = "jongkwan-macmini.tail4510fa.ts.net"
        private const val KOREAN_QUESTION =
            "대한민국의 수도가 어디인지 한국어 한 문장으로 정확히 답하고, 다음 줄에 REMOTE-KO-OK를 써줘. " +
                "검색이나 도구를 사용하지 말고 설명은 두 줄 이내로 끝내줘."
        private const val CONTEXT_NONCE = "PE-CTX-7Q4M9"
        private const val CONTEXT_NONCE_QUESTION =
            "다음 코드를 그대로 한 번만 답해줘: $CONTEXT_NONCE. 다른 설명은 하지 마."
        private const val CONTEXT_RECALL_QUESTION =
            "참고 자료에 있는 코드를 그대로 한 줄로 답해줘. 다른 설명은 하지 마."
        private const val CANCELLATION_QUESTION =
            "한국어로 소프트웨어 테스트의 장점을 40개 설명해줘. 각 항목은 서로 다른 관점에서 " +
                "두 문장으로 자세히 쓰고 번호를 붙여줘. 검색이나 도구는 사용하지 마."
    }
}
