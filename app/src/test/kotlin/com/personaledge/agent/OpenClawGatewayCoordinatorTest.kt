package com.personaledge.agent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.personaledge.core.agent.RemoteAgentCancelResult
import com.personaledge.core.agent.RemoteAgentConnectionState
import com.personaledge.core.agent.RemoteAgentEvent
import com.personaledge.core.agent.RemoteAgentGateway
import com.personaledge.core.agent.RemoteAgentRunId
import com.personaledge.core.agent.RemoteAgentStartRequest
import com.personaledge.core.agent.RemoteAgentStartResult
import com.personaledge.core.agent.RemoteAgentStatusResult
import com.personaledge.core.agent.RemoteAgentWaitResult
import com.personaledge.core.agent.RemoteAgentWaitTimeout
import com.personaledge.core.data.OpenClawGatewaySettings
import com.personaledge.core.data.OpenClawGatewayTrustMode
import com.personaledge.core.data.SecretKeyName
import com.personaledge.core.openclaw.GatewayEndpoint
import com.personaledge.core.openclaw.OpenClawAuthToken
import com.personaledge.core.openclaw.OpenClawConnectFailureCategory
import com.personaledge.core.openclaw.OpenClawConnectResult
import com.personaledge.core.openclaw.OpenClawDeviceAuth
import com.personaledge.core.openclaw.OpenClawReconnectPolicy
import com.personaledge.core.openclaw.OpenClawSecretRecordCodec
import com.personaledge.core.openclaw.OpenClawWebSocketTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpenClawGatewayCoordinatorTest {
    @Test
    fun `default off never reads secrets or starts a connection`() = runTest {
        val settings = MutableStateFlow(OpenClawGatewaySettings())
        val store = FakeSecretStore()
        val factory = FakeConnectionFactory { error("must not connect") }
        val coordinator = coordinator(settings, OwnerConsentInterlock(), store, factory)

        coordinator.setForeground(true)
        runCurrent()

        assertEquals(OpenClawGatewayUiState.DISCONNECTED, coordinator.state.value)
        assertEquals(0, factory.requests.size)
        assertTrue(store.reads.isEmpty())
        assertNull(coordinator.activeGateway())
        coordinator.close()
    }

    @Test
    fun `revocation recovery barrier blocks connect and closes an active session`() = runTest {
        val settings = MutableStateFlow(configured(PRIMARY_ENDPOINT))
        val store = validStore(PRIMARY_ENDPOINT)
        val session = FakeSession()
        val factory = FakeConnectionFactory {
            OpenClawGatewayConnectResult.Connected(session)
        }
        val gate = FakeRevocationGate(OpenClawGatewayRevocationBarrierState.BLOCKED)
        val coordinator = OpenClawGatewayCoordinator(
            settingsSource = object : OpenClawGatewaySettingsSource {
                override val settings: Flow<OpenClawGatewaySettings> = settings
            },
            ownerConsentInterlock = OwnerConsentInterlock(),
            revocationGate = gate,
            secretStore = store,
            connectionFactory = factory,
            reconnectPolicy = OpenClawReconnectPolicy(),
            parentScope = this,
            clientVersion = "test-version",
        )

        coordinator.setForeground(true)
        runCurrent()
        assertEquals(0, factory.requests.size)
        assertEquals(OpenClawGatewayUiState.DISCONNECTED, coordinator.state.value)

        gate.revocationBarrierState.value = OpenClawGatewayRevocationBarrierState.CLEAR
        runCurrent()
        assertEquals(1, factory.requests.size)
        assertEquals(OpenClawGatewayUiState.CONNECTED, coordinator.state.value)

        gate.revocationBarrierState.value = OpenClawGatewayRevocationBarrierState.BLOCKED
        runCurrent()
        assertEquals(1, session.closeCount)
        assertNull(coordinator.activeGateway())
        assertEquals(OpenClawGatewayUiState.DISCONNECTED, coordinator.state.value)
        coordinator.close()
    }

    @Test
    fun `identity absent is generated and stored before a foreground connection`() = runTest {
        val endpoint = endpoint(PRIMARY_ENDPOINT)
        val settings = MutableStateFlow(configured(PRIMARY_ENDPOINT))
        val store = FakeSecretStore()
        val session = FakeSession()
        val factory = FakeConnectionFactory {
            OpenClawGatewayConnectResult.Connected(session)
        }
        val coordinator = coordinator(settings, OwnerConsentInterlock(), store, factory)

        coordinator.setForeground(true)
        runCurrent()

        val storedIdentity = store.records[SecretKeyName.OPENCLAW_DEVICE_IDENTITY_RECORD]
        assertNotNull(storedIdentity)
        assertNotNull(
            OpenClawSecretRecordCodec.restoreDeviceIdentity(endpoint, storedIdentity!!),
        )
        assertEquals(OpenClawGatewayUiState.CONNECTED, coordinator.state.value)
        assertEquals(1, factory.requests.size)
        coordinator.close()
    }

    @Test
    fun `pinned trust is parsed and passed to the narrow transport factory`() = runTest {
        val settings = MutableStateFlow(
            OpenClawGatewaySettings(
                enabled = true,
                endpointUrl = PRIMARY_ENDPOINT,
                trustMode = OpenClawGatewayTrustMode.PINNED_CERT_SHA256,
                leafCertificateDerSha256 = "ab".repeat(32),
            ),
        )
        val factory = FakeConnectionFactory { request ->
            assertNotNull(request.leafCertificatePin)
            OpenClawGatewayConnectResult.AuthenticationRequired
        }
        val coordinator = coordinator(
            settings,
            OwnerConsentInterlock(),
            validStore(PRIMARY_ENDPOINT),
            factory,
        )

        coordinator.setForeground(true)
        runCurrent()

        assertEquals(OpenClawGatewayUiState.PAIRING_OR_AUTH_REQUIRED, coordinator.state.value)
        assertEquals(1, factory.requests.size)
        coordinator.close()
    }

    @Test
    fun `process consent close wins synchronously over durable enabled state`() = runTest {
        val settings = MutableStateFlow(configured(PRIMARY_ENDPOINT))
        val store = validStore(PRIMARY_ENDPOINT)
        val interlock = OwnerConsentInterlock()
        val session = FakeSession()
        val factory = FakeConnectionFactory {
            OpenClawGatewayConnectResult.Connected(session)
        }
        val coordinator = coordinator(settings, interlock, store, factory)
        coordinator.setForeground(true)
        runCurrent()
        assertEquals(OpenClawGatewayUiState.CONNECTED, coordinator.state.value)

        interlock.requestEnabled(OwnerConsentFeature.OPENCLAW_GATEWAY, enabled = false)

        assertTrue(session.closed)
        assertNull(coordinator.activeGateway())
        assertEquals(OpenClawGatewayUiState.DISCONNECTED, coordinator.state.value)
        coordinator.close()
    }

    @Test
    fun `consent revoked during non cooperative connect discards the stale session`() = runTest {
        val settings = MutableStateFlow(configured(PRIMARY_ENDPOINT))
        val interlock = OwnerConsentInterlock()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val staleSession = FakeSession()
        val factory = FakeConnectionFactory {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            OpenClawGatewayConnectResult.Connected(staleSession)
        }
        val coordinator = coordinator(
            settings,
            interlock,
            validStore(PRIMARY_ENDPOINT),
            factory,
        )
        coordinator.setForeground(true)
        runCurrent()
        entered.await()

        interlock.requestEnabled(OwnerConsentFeature.OPENCLAW_GATEWAY, enabled = false)
        release.complete(Unit)
        runCurrent()

        assertTrue(staleSession.closed)
        assertNull(coordinator.activeGateway())
        assertEquals(OpenClawGatewayUiState.DISCONNECTED, coordinator.state.value)
        coordinator.close()
    }

    @Test
    fun `background closes immediately and schedules no reconnect`() = runTest {
        val settings = MutableStateFlow(configured(PRIMARY_ENDPOINT))
        val session = FakeSession()
        val factory = FakeConnectionFactory {
            OpenClawGatewayConnectResult.Connected(session)
        }
        val coordinator = coordinator(
            settings,
            OwnerConsentInterlock(),
            validStore(PRIMARY_ENDPOINT),
            factory,
        )
        coordinator.setForeground(true)
        runCurrent()

        coordinator.setForeground(false)

        assertTrue(session.closed)
        assertEquals(OpenClawGatewayUiState.DISCONNECTED, coordinator.state.value)
        assertNull(coordinator.activeGateway())
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(1, factory.requests.size)
        coordinator.close()
    }

    @Test
    fun `durable disable emission closes the published connection`() = runTest {
        val settings = MutableStateFlow(configured(PRIMARY_ENDPOINT))
        val session = FakeSession()
        val factory = FakeConnectionFactory {
            OpenClawGatewayConnectResult.Connected(session)
        }
        val coordinator = coordinator(
            settings,
            OwnerConsentInterlock(),
            validStore(PRIMARY_ENDPOINT),
            factory,
        )
        coordinator.setForeground(true)
        runCurrent()

        settings.value = OpenClawGatewaySettings()
        runCurrent()

        assertTrue(session.closed)
        assertNull(coordinator.activeGateway())
        assertEquals(OpenClawGatewayUiState.DISCONNECTED, coordinator.state.value)
        coordinator.close()
    }

    @Test
    fun `endpoint swap closes old session and stale connect cannot publish`() = runTest {
        val primaryEndpoint = endpoint(PRIMARY_ENDPOINT)
        val secondaryEndpoint = endpoint(SECONDARY_ENDPOINT)
        val settings = MutableStateFlow(configured(PRIMARY_ENDPOINT))
        val store = validStore(PRIMARY_ENDPOINT)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = FakeSession()
        val second = FakeSession()
        val factory = FakeConnectionFactory { request ->
            if (request.endpoint == primaryEndpoint) {
                firstEntered.complete(Unit)
                withContext(NonCancellable) { releaseFirst.await() }
                OpenClawGatewayConnectResult.Connected(first)
            } else {
                assertEquals(secondaryEndpoint, request.endpoint)
                OpenClawGatewayConnectResult.Connected(second)
            }
        }
        val coordinator = coordinator(settings, OwnerConsentInterlock(), store, factory)
        coordinator.setForeground(true)
        runCurrent()
        firstEntered.await()

        store.putValidIdentity(SECONDARY_ENDPOINT)
        settings.value = configured(SECONDARY_ENDPOINT)
        runCurrent()

        assertEquals(OpenClawGatewayUiState.CONNECTED, coordinator.state.value)
        assertTrue(coordinator.activeGateway() === second.gateway)
        releaseFirst.complete(Unit)
        runCurrent()

        assertTrue(first.closed)
        assertFalse(second.closed)
        assertTrue(coordinator.activeGateway() === second.gateway)
        assertEquals(2, factory.requests.size)
        coordinator.close()
    }

    @Test
    fun `corrupt credential record fails closed without opening a socket`() = runTest {
        val settings = MutableStateFlow(configured(PRIMARY_ENDPOINT))
        val store = validStore(PRIMARY_ENDPOINT).apply {
            records[SecretKeyName.OPENCLAW_GATEWAY_CREDENTIAL_RECORD] = "not-a-record"
        }
        val factory = FakeConnectionFactory { error("must not connect") }
        val coordinator = coordinator(settings, OwnerConsentInterlock(), store, factory)

        coordinator.setForeground(true)
        runCurrent()

        assertEquals(OpenClawGatewayUiState.DEGRADED, coordinator.state.value)
        assertEquals(0, factory.requests.size)
        assertNull(coordinator.activeGateway())
        coordinator.close()
    }

    @Test
    fun `cross origin identity fails closed and is not silently replaced`() = runTest {
        val settings = MutableStateFlow(configured(PRIMARY_ENDPOINT))
        val store = validStore(SECONDARY_ENDPOINT)
        val original = store.records.getValue(SecretKeyName.OPENCLAW_DEVICE_IDENTITY_RECORD)
        val factory = FakeConnectionFactory { error("must not connect") }
        val coordinator = coordinator(settings, OwnerConsentInterlock(), store, factory)

        coordinator.setForeground(true)
        runCurrent()

        assertEquals(OpenClawGatewayUiState.DEGRADED, coordinator.state.value)
        assertEquals(original, store.records[SecretKeyName.OPENCLAW_DEVICE_IDENTITY_RECORD])
        assertEquals(0, factory.requests.size)
        coordinator.close()
    }

    @Test
    fun `issued device token atomically replaces the origin-bound credential before publish`() =
        runTest {
            val endpoint = endpoint(PRIMARY_ENDPOINT)
            val oldCredential = token("old-gateway-token")
            val issuedCredential = token("new-issued-device-token")
            val settings = MutableStateFlow(configured(PRIMARY_ENDPOINT))
            val store = validStore(PRIMARY_ENDPOINT).apply {
                records[SecretKeyName.OPENCLAW_GATEWAY_CREDENTIAL_RECORD] =
                    OpenClawSecretRecordCodec.encodeGatewayCredential(endpoint, oldCredential)
            }
            val session = FakeSession()
            val factory = FakeConnectionFactory {
                OpenClawGatewayConnectResult.Connected(session, issuedCredential)
            }
            val coordinator = coordinator(settings, OwnerConsentInterlock(), store, factory)

            coordinator.setForeground(true)
            runCurrent()

            assertEquals(
                OpenClawSecretRecordCodec.encodeGatewayCredential(endpoint, issuedCredential),
                store.records[SecretKeyName.OPENCLAW_GATEWAY_CREDENTIAL_RECORD],
            )
            assertEquals(OpenClawGatewayUiState.CONNECTED, coordinator.state.value)
            assertTrue(coordinator.activeGateway() === session.gateway)
            coordinator.close()
        }

    @Test
    fun `issued token storage failure closes the connected socket and never publishes it`() =
        runTest {
            val settings = MutableStateFlow(configured(PRIMARY_ENDPOINT))
            val store = validStore(PRIMARY_ENDPOINT).apply {
                failStores += SecretKeyName.OPENCLAW_GATEWAY_CREDENTIAL_RECORD
            }
            val session = FakeSession()
            val factory = FakeConnectionFactory {
                OpenClawGatewayConnectResult.Connected(
                    session,
                    token("new-issued-device-token"),
                )
            }
            val coordinator = coordinator(settings, OwnerConsentInterlock(), store, factory)

            coordinator.setForeground(true)
            runCurrent()

            assertTrue(session.closed)
            assertNull(coordinator.activeGateway())
            assertEquals(OpenClawGatewayUiState.DEGRADED, coordinator.state.value)
            coordinator.close()
        }

    @Test
    fun `background during non cooperative issued token write still closes the new socket`() =
        runTest {
            val settings = MutableStateFlow(configured(PRIMARY_ENDPOINT))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val store = validStore(PRIMARY_ENDPOINT).apply {
                beforeCompareAndStore = { name, _, _ ->
                    if (name == SecretKeyName.OPENCLAW_GATEWAY_CREDENTIAL_RECORD) {
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                    }
                }
            }
            val session = FakeSession()
            val factory = FakeConnectionFactory {
                OpenClawGatewayConnectResult.Connected(session, token("issued-token"))
            }
            val coordinator = coordinator(settings, OwnerConsentInterlock(), store, factory)
            coordinator.setForeground(true)
            runCurrent()
            entered.await()

            coordinator.setForeground(false)
            assertTrue(session.closed)
            assertEquals(1, session.closeCount)
            release.complete(Unit)
            runCurrent()

            assertTrue(session.closed)
            assertEquals(1, session.closeCount)
            assertNull(coordinator.activeGateway())
            assertNull(store.records[SecretKeyName.OPENCLAW_GATEWAY_CREDENTIAL_RECORD])
            assertEquals(OpenClawGatewayUiState.DISCONNECTED, coordinator.state.value)
            coordinator.close()
        }

    @Test
    fun `process consent disable closes a pending issued token session before storage returns`() =
        runTest {
            val settings = MutableStateFlow(configured(PRIMARY_ENDPOINT))
            val interlock = OwnerConsentInterlock()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val store = validStore(PRIMARY_ENDPOINT).apply {
                beforeCompareAndStore = { name, _, _ ->
                    if (name == SecretKeyName.OPENCLAW_GATEWAY_CREDENTIAL_RECORD) {
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                    }
                }
            }
            val session = FakeSession()
            val factory = FakeConnectionFactory {
                OpenClawGatewayConnectResult.Connected(session, token("issued-token"))
            }
            val coordinator = coordinator(settings, interlock, store, factory)
            coordinator.setForeground(true)
            runCurrent()
            entered.await()

            interlock.requestEnabled(OwnerConsentFeature.OPENCLAW_GATEWAY, enabled = false)

            assertEquals(1, session.closeCount)
            assertNull(coordinator.activeGateway())
            assertEquals(OpenClawGatewayUiState.DISCONNECTED, coordinator.state.value)
            release.complete(Unit)
            runCurrent()
            assertEquals(1, session.closeCount)
            coordinator.close()
        }

    @Test
    fun `late issued token CAS cannot overwrite a newer endpoint credential`() = runTest {
        val primaryEndpoint = endpoint(PRIMARY_ENDPOINT)
        val secondaryEndpoint = endpoint(SECONDARY_ENDPOINT)
        val oldCredential = token("old-primary-token")
        val secondaryCredential = token("current-secondary-token")
        val expectedPrimaryRecord = OpenClawSecretRecordCodec.encodeGatewayCredential(
            primaryEndpoint,
            oldCredential,
        )
        val secondaryRecord = OpenClawSecretRecordCodec.encodeGatewayCredential(
            secondaryEndpoint,
            secondaryCredential,
        )
        val settings = MutableStateFlow(configured(PRIMARY_ENDPOINT))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = validStore(PRIMARY_ENDPOINT).apply {
            records[SecretKeyName.OPENCLAW_GATEWAY_CREDENTIAL_RECORD] = expectedPrimaryRecord
            beforeCompareAndStore = { name, expected, _ ->
                if (
                    name == SecretKeyName.OPENCLAW_GATEWAY_CREDENTIAL_RECORD &&
                    expected == expectedPrimaryRecord
                ) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
            }
        }
        val primarySession = FakeSession()
        val secondarySession = FakeSession()
        val factory = FakeConnectionFactory { request ->
            if (request.endpoint == primaryEndpoint) {
                OpenClawGatewayConnectResult.Connected(
                    primarySession,
                    token("late-issued-primary-token"),
                )
            } else {
                OpenClawGatewayConnectResult.Connected(secondarySession)
            }
        }
        val coordinator = coordinator(settings, OwnerConsentInterlock(), store, factory)
        coordinator.setForeground(true)
        runCurrent()
        entered.await()

        store.putValidIdentity(SECONDARY_ENDPOINT)
        store.records[SecretKeyName.OPENCLAW_GATEWAY_CREDENTIAL_RECORD] = secondaryRecord
        settings.value = configured(SECONDARY_ENDPOINT)
        runCurrent()

        assertEquals(1, primarySession.closeCount)
        assertTrue(coordinator.activeGateway() === secondarySession.gateway)
        release.complete(Unit)
        runCurrent()

        val storedCredential = store.records.getValue(
            SecretKeyName.OPENCLAW_GATEWAY_CREDENTIAL_RECORD,
        )
        assertNotNull(
            OpenClawSecretRecordCodec.restoreGatewayCredential(secondaryEndpoint, storedCredential),
        )
        assertNull(
            OpenClawSecretRecordCodec.restoreGatewayCredential(primaryEndpoint, storedCredential),
        )
        assertEquals(1, primarySession.closeCount)
        assertEquals(2, factory.requests.size)
        coordinator.close()
    }

    @Test
    fun `late identity CAS cannot overwrite a newer endpoint identity`() = runTest {
        val primaryEndpoint = endpoint(PRIMARY_ENDPOINT)
        val secondaryEndpoint = endpoint(SECONDARY_ENDPOINT)
        val settings = MutableStateFlow(configured(PRIMARY_ENDPOINT))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = FakeSecretStore().apply {
            beforeCompareAndStore = { name, expected, value ->
                if (
                    name == SecretKeyName.OPENCLAW_DEVICE_IDENTITY_RECORD &&
                    expected == null &&
                    OpenClawSecretRecordCodec.restoreDeviceIdentity(primaryEndpoint, value) != null
                ) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
            }
        }
        val secondarySession = FakeSession()
        val factory = FakeConnectionFactory { request ->
            assertEquals(secondaryEndpoint, request.endpoint)
            OpenClawGatewayConnectResult.Connected(secondarySession)
        }
        val coordinator = coordinator(settings, OwnerConsentInterlock(), store, factory)
        coordinator.setForeground(true)
        runCurrent()
        entered.await()

        settings.value = configured(SECONDARY_ENDPOINT)
        runCurrent()

        assertTrue(coordinator.activeGateway() === secondarySession.gateway)
        release.complete(Unit)
        runCurrent()

        val stored = store.records.getValue(SecretKeyName.OPENCLAW_DEVICE_IDENTITY_RECORD)
        assertNotNull(OpenClawSecretRecordCodec.restoreDeviceIdentity(secondaryEndpoint, stored))
        assertNull(OpenClawSecretRecordCodec.restoreDeviceIdentity(primaryEndpoint, stored))
        assertEquals(1, factory.requests.size)
        coordinator.close()
    }

    @Test
    fun `connect then immediate disconnect churn is bounded to six attempts per foreground epoch`() =
        runTest {
            val settings = MutableStateFlow(configured(PRIMARY_ENDPOINT))
            val sessions = mutableListOf<FakeSession>()
            val delays = mutableListOf<Long>()
            val factory = FakeConnectionFactory {
                val session = FakeSession()
                sessions += session
                launch { session.mutableConnectionState.value = RemoteAgentConnectionState.DISCONNECTED }
                OpenClawGatewayConnectResult.Connected(session)
            }
            val coordinator = coordinator(
                settings = settings,
                interlock = OwnerConsentInterlock(),
                store = validStore(PRIMARY_ENDPOINT),
                factory = factory,
                waitBeforeReconnect = { milliseconds ->
                    delays += milliseconds
                    delay(milliseconds)
                },
            )
            coordinator.setForeground(true)
            advanceUntilIdle()

            assertEquals(6, factory.requests.size)
            assertEquals(6, sessions.size)
            assertTrue(sessions.all { session -> session.closeCount == 1 })
            assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), delays)
            assertNull(coordinator.activeGateway())
            assertEquals(OpenClawGatewayUiState.DEGRADED, coordinator.state.value)
            coordinator.close()
        }

    @Test
    fun `typed connect failures map without using credential presence`() {
        AUTH_FAILURE_CODES_FOR_TEST.forEach { code ->
            val mapped = OpenClawConnectResult.Rejected(
                code = code,
                retryable = true,
                retryAfterMillis = 5_000L,
            ).toGatewayFailureForApp()
            assertEquals(OpenClawGatewayConnectResult.AuthenticationRequired, mapped)
        }
        assertEquals(
            OpenClawGatewayConnectResult.AuthenticationRequired,
            OpenClawConnectResult.Rejected(
                code = "DEVICE_AUTH_SIGNATURE_INVALID",
                retryable = false,
                retryAfterMillis = null,
            ).toGatewayFailureForApp(),
        )
        listOf(
            OpenClawConnectFailureCategory.TLS,
            OpenClawConnectFailureCategory.IO,
            OpenClawConnectFailureCategory.TIMEOUT,
        ).forEach { category ->
            assertTrue(
                OpenClawConnectResult.Failed(category).toGatewayFailureForApp() is
                    OpenClawGatewayConnectResult.RetryableFailure,
            )
        }
        listOf(
            OpenClawConnectFailureCategory.PROTOCOL,
            OpenClawConnectFailureCategory.MALFORMED,
        ).forEach { category ->
            assertEquals(
                OpenClawGatewayConnectResult.ProtocolFailure,
                OpenClawConnectResult.Failed(category).toGatewayFailureForApp(),
            )
        }

        val retryable = OpenClawConnectResult.Rejected(
            code = "RATE_LIMITED",
            retryable = true,
            retryAfterMillis = 5_000L,
        ).toGatewayFailureForApp()
        assertTrue(retryable is OpenClawGatewayConnectResult.RetryableFailure)
        assertEquals(
            5_000L,
            (retryable as OpenClawGatewayConnectResult.RetryableFailure).retryAfterMillis,
        )
        val authRateLimited = OpenClawConnectResult.Rejected(
            code = "AUTH_RATE_LIMITED",
            retryable = null,
            retryAfterMillis = 9_000L,
        ).toGatewayFailureForApp()
        assertTrue(authRateLimited is OpenClawGatewayConnectResult.RetryableFailure)
        assertEquals(
            9_000L,
            (authRateLimited as OpenClawGatewayConnectResult.RetryableFailure).retryAfterMillis,
        )
        listOf("PROTOCOL_MISMATCH", "CLIENT_VERSION_MISMATCH").forEach { code ->
            assertEquals(
                OpenClawGatewayConnectResult.ProtocolFailure,
                OpenClawConnectResult.Rejected(
                    code = code,
                    retryable = true,
                    retryAfterMillis = 1_000L,
                ).toGatewayFailureForApp(),
            )
        }
        assertEquals(
            OpenClawGatewayConnectResult.ProtocolFailure,
            OpenClawConnectResult.Rejected(
                code = "UNKNOWN_REJECTION",
                retryable = null,
                retryAfterMillis = null,
            ).toGatewayFailureForApp(),
        )
    }

    @Test
    fun `actual v2026 pairing rejection wire reaches content free coordinator auth state`() =
        runTest {
            val remoteMessage = "pairing needed for private owner request"
            val remoteRequestId = "private-pairing-request-id"
            val credential = "wire-credential-that-must-not-appear"
            val transport = PairingRejectingTransport(remoteMessage, remoteRequestId)
            val factory = FakeConnectionFactory { request ->
                connectOpenClawGatewayWithTransport(request, transport)
            }
            val coordinator = coordinator(
                settings = MutableStateFlow(configured(PRIMARY_ENDPOINT)),
                interlock = OwnerConsentInterlock(),
                store = validStore(PRIMARY_ENDPOINT, credential),
                factory = factory,
            )

            coordinator.setForeground(true)
            runCurrent()

            assertEquals(
                OpenClawGatewayUiState.PAIRING_OR_AUTH_REQUIRED,
                coordinator.state.value,
            )
            assertEquals(listOf("connect"), transport.methods)
            assertTrue(transport.socketClosed)
            val rendered = listOf(
                coordinator,
                coordinator.state.value,
                factory.requests.single(),
            ).joinToString("\n")
            assertFalse(rendered.contains(remoteMessage))
            assertFalse(rendered.contains(remoteRequestId))
            assertFalse(rendered.contains(credential))
            assertFalse(rendered.contains(PRIMARY_ENDPOINT))
            coordinator.close()
        }

    @Test
    fun `server retry after is honored only inside the bounded reconnect policy`() = runTest {
        val settings = MutableStateFlow(configured(PRIMARY_ENDPOINT))
        val delays = mutableListOf<Long>()
        var attempts = 0
        val factory = FakeConnectionFactory {
            attempts += 1
            if (attempts == 1) {
                OpenClawGatewayConnectResult.RetryableFailure(retryAfterMillis = 5_000L)
            } else {
                OpenClawGatewayConnectResult.AuthenticationRequired
            }
        }
        val coordinator = coordinator(
            settings = settings,
            interlock = OwnerConsentInterlock(),
            store = validStore(PRIMARY_ENDPOINT),
            factory = factory,
            waitBeforeReconnect = { milliseconds -> delays += milliseconds },
        )
        coordinator.setForeground(true)
        runCurrent()

        assertEquals(2, attempts)
        assertEquals(listOf(5_000L), delays)
        assertEquals(OpenClawGatewayUiState.PAIRING_OR_AUTH_REQUIRED, coordinator.state.value)
        coordinator.close()

        val excessiveFactory = FakeConnectionFactory {
            OpenClawGatewayConnectResult.RetryableFailure(
                retryAfterMillis = OpenClawReconnectPolicy.MAX_DELAY_MILLIS + 1L,
            )
        }
        val excessiveCoordinator = coordinator(
            settings = settings,
            interlock = OwnerConsentInterlock(),
            store = validStore(PRIMARY_ENDPOINT),
            factory = excessiveFactory,
            waitBeforeReconnect = { error("must not retry beyond the local bound") },
        )
        excessiveCoordinator.setForeground(true)
        runCurrent()

        assertEquals(1, excessiveFactory.requests.size)
        assertEquals(OpenClawGatewayUiState.DEGRADED, excessiveCoordinator.state.value)
        excessiveCoordinator.close()
    }

    @Test
    fun `reconnect delay is bounded and background cancels it`() = runTest {
        val settings = MutableStateFlow(configured(PRIMARY_ENDPOINT))
        val delays = mutableListOf<Long>()
        val factory = FakeConnectionFactory {
            OpenClawGatewayConnectResult.RetryableFailure()
        }
        val coordinator = coordinator(
            settings = settings,
            interlock = OwnerConsentInterlock(),
            store = validStore(PRIMARY_ENDPOINT, credential = "retry-token"),
            factory = factory,
            waitBeforeReconnect = { milliseconds ->
                delays += milliseconds
                delay(milliseconds)
            },
        )
        coordinator.setForeground(true)
        runCurrent()

        assertEquals(1, factory.requests.size)
        assertEquals(listOf(OpenClawReconnectPolicy.INITIAL_DELAY_MILLIS), delays)
        coordinator.setForeground(false)
        advanceTimeBy(OpenClawReconnectPolicy.MAX_DELAY_MILLIS * 2)
        runCurrent()

        assertEquals(1, factory.requests.size)
        assertEquals(OpenClawGatewayUiState.DISCONNECTED, coordinator.state.value)
        coordinator.close()
    }

    @Test
    fun `states requests records and coordinator strings redact endpoint and credentials`() =
        runTest {
            val secret = "credential-that-must-not-appear"
            val settings = MutableStateFlow(configured(PRIMARY_ENDPOINT))
            val store = validStore(PRIMARY_ENDPOINT, credential = secret)
            val session = FakeSession()
            val factory = FakeConnectionFactory {
                OpenClawGatewayConnectResult.Connected(session)
            }
            val coordinator = coordinator(settings, OwnerConsentInterlock(), store, factory)
            coordinator.setForeground(true)
            runCurrent()

            val rendered = listOf(
                coordinator,
                coordinator.state.value,
                factory.requests.single(),
                OpenClawStoredRecord.Present(store.records.values.first()),
                OpenClawGatewayConnectResult.Connected(session, token(secret)),
                session,
            ).joinToString("\n")

            assertFalse(rendered.contains(secret))
            assertFalse(rendered.contains(PRIMARY_ENDPOINT))
            assertFalse(rendered.contains(store.records.values.first()))
            coordinator.close()
        }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        settings: MutableStateFlow<OpenClawGatewaySettings>,
        interlock: OwnerConsentInterlock,
        store: FakeSecretStore,
        factory: FakeConnectionFactory,
        waitBeforeReconnect: suspend (Long) -> Unit = { delay(it) },
    ): OpenClawGatewayCoordinator = OpenClawGatewayCoordinator(
        settingsSource = object : OpenClawGatewaySettingsSource {
            override val settings: Flow<OpenClawGatewaySettings> = settings
        },
        ownerConsentInterlock = interlock,
        revocationGate = FakeRevocationGate(OpenClawGatewayRevocationBarrierState.CLEAR),
        secretStore = store,
        connectionFactory = factory,
        reconnectPolicy = OpenClawReconnectPolicy(),
        parentScope = this,
        clientVersion = "test-version",
        waitBeforeReconnect = waitBeforeReconnect,
    )

    private fun validStore(endpointUrl: String, credential: String? = null): FakeSecretStore =
        FakeSecretStore().apply {
            putValidIdentity(endpointUrl)
            if (credential != null) {
                records[SecretKeyName.OPENCLAW_GATEWAY_CREDENTIAL_RECORD] =
                    OpenClawSecretRecordCodec.encodeGatewayCredential(
                        endpoint(endpointUrl),
                        token(credential),
                    )
            }
        }

    private fun FakeSecretStore.putValidIdentity(endpointUrl: String) {
        records[SecretKeyName.OPENCLAW_DEVICE_IDENTITY_RECORD] =
            OpenClawSecretRecordCodec.encodeDeviceIdentity(
                endpoint(endpointUrl),
                OpenClawDeviceAuth.generate(),
            )
    }

    private fun configured(endpointUrl: String): OpenClawGatewaySettings =
        OpenClawGatewaySettings(enabled = true, endpointUrl = endpointUrl)

    private fun endpoint(value: String): GatewayEndpoint =
        requireNotNull(GatewayEndpoint.parseRelease(value))

    private fun token(value: String): OpenClawAuthToken = requireNotNull(OpenClawAuthToken.parse(value))

    private class FakeSecretStore : OpenClawSecretRecordStore {
        val records = mutableMapOf<SecretKeyName, String>()
        val reads = mutableListOf<SecretKeyName>()
        val failStores = mutableSetOf<SecretKeyName>()
        var beforeCompareAndStore: suspend (SecretKeyName, String?, String) -> Unit =
            { _, _, _ -> }

        override suspend fun read(name: SecretKeyName): OpenClawStoredRecord {
            reads += name
            return records[name]?.let(OpenClawStoredRecord::Present) ?: OpenClawStoredRecord.Absent
        }

        override suspend fun compareAndStore(
            name: SecretKeyName,
            expectedValue: String?,
            value: String,
        ): Boolean {
            beforeCompareAndStore(name, expectedValue, value)
            if (name in failStores) error("content-free test failure")
            if (records[name] != expectedValue) return false
            records[name] = value
            return true
        }

        override suspend fun compareAndRemove(
            name: SecretKeyName,
            expectedValue: String,
        ): Boolean {
            if (records[name] != expectedValue) return false
            records.remove(name)
            return true
        }
    }

    private class FakeConnectionFactory(
        private val result: suspend (OpenClawGatewayConnectRequest) -> OpenClawGatewayConnectResult,
    ) : OpenClawGatewayConnectionFactory {
        val requests = mutableListOf<OpenClawGatewayConnectRequest>()

        override suspend fun connect(
            request: OpenClawGatewayConnectRequest,
        ): OpenClawGatewayConnectResult {
            requests += request
            return result(request)
        }
    }

    private class FakeRevocationGate(
        initial: OpenClawGatewayRevocationBarrierState,
    ) : OpenClawGatewayRevocationGate {
        override val revocationBarrierState = MutableStateFlow(initial)
    }

    /** Emits the exact v2026.8.1 NOT_PAIRED + details.code response shape, without networking. */
    private class PairingRejectingTransport(
        private val remoteMessage: String,
        private val remoteRequestId: String,
    ) : OpenClawWebSocketTransport {
        val methods = mutableListOf<String>()
        var socketClosed = false
            private set

        override fun open(
            endpoint: GatewayEndpoint,
            listener: OpenClawWebSocketTransport.Listener,
        ): OpenClawWebSocketTransport.Socket {
            listener.onOpen()
            listener.onText(
                """{"type":"event","event":"connect.challenge","payload":{"nonce":"nonce-1","ts":1700000000000}}""",
            )
            return object : OpenClawWebSocketTransport.Socket {
                override val queueSizeBytes: Long = 0L

                override fun sendText(text: String): Boolean {
                    val request = JsonParser.parseString(text).asJsonObject
                    methods += request["method"].asString
                    listener.onText(
                        JsonObject().apply {
                            addProperty("type", "res")
                            addProperty("id", request["id"].asString)
                            addProperty("ok", false)
                            add("error", JsonObject().apply {
                                addProperty("code", "NOT_PAIRED")
                                addProperty("message", remoteMessage)
                                add("details", JsonObject().apply {
                                    addProperty("code", "PAIRING_REQUIRED")
                                    addProperty("reason", "not-paired")
                                    addProperty("requestId", remoteRequestId)
                                    addProperty("remediationHint", "private remediation")
                                })
                            })
                        }.toString(),
                    )
                    return true
                }

                override fun close(code: Int): Boolean {
                    socketClosed = true
                    return true
                }

                override fun cancel() {
                    socketClosed = true
                }
            }
        }
    }

    private class FakeSession : OpenClawGatewaySession {
        val mutableConnectionState = MutableStateFlow(RemoteAgentConnectionState.CONNECTED)
        override val connectionState: StateFlow<RemoteAgentConnectionState> = mutableConnectionState
        override val gateway: RemoteAgentGateway = FakeGateway(mutableConnectionState)
        var closeCount = 0
            private set
        val closed: Boolean
            get() = closeCount > 0

        override fun close() {
            closeCount += 1
        }

        override fun toString(): String =
            "FakeSession(state=${connectionState.value}, closed=$closed)"
    }

    private class FakeGateway(
        override val connectionState: StateFlow<RemoteAgentConnectionState>,
    ) : RemoteAgentGateway {
        override suspend fun start(request: RemoteAgentStartRequest): RemoteAgentStartResult =
            error("not used")

        override fun events(runId: RemoteAgentRunId): Flow<RemoteAgentEvent> = emptyFlow()

        override suspend fun status(runId: RemoteAgentRunId): RemoteAgentStatusResult =
            RemoteAgentStatusResult.NotFound

        override suspend fun cancel(runId: RemoteAgentRunId): RemoteAgentCancelResult =
            RemoteAgentCancelResult.NotFound

        override suspend fun waitForTerminal(
            runId: RemoteAgentRunId,
            timeout: RemoteAgentWaitTimeout,
        ): RemoteAgentWaitResult = RemoteAgentWaitResult.NotFound

        override fun toString(): String = "FakeGateway(state=${connectionState.value})"
    }

    private companion object {
        const val PRIMARY_ENDPOINT = "wss://gateway.example.test/socket"
        const val SECONDARY_ENDPOINT = "wss://other.example.test/socket"
        val AUTH_FAILURE_CODES_FOR_TEST = listOf(
            "PAIRING_REQUIRED",
            "AUTH_REQUIRED",
            "AUTH_UNAUTHORIZED",
            "AUTH_TOKEN_MISSING",
            "AUTH_TOKEN_MISMATCH",
            "AUTH_TOKEN_NOT_CONFIGURED",
            "AUTH_PASSWORD_MISSING",
            "AUTH_PASSWORD_MISMATCH",
            "AUTH_PASSWORD_NOT_CONFIGURED",
            "AUTH_BOOTSTRAP_TOKEN_INVALID",
            "AUTH_DEVICE_TOKEN_MISMATCH",
            "AUTH_SCOPE_MISMATCH",
            "AUTH_TAILSCALE_IDENTITY_MISSING",
            "AUTH_TAILSCALE_PROXY_MISSING",
            "AUTH_TAILSCALE_WHOIS_FAILED",
            "AUTH_TAILSCALE_IDENTITY_MISMATCH",
            "AUTH_IDENTITY_HEADER_REQUIRED",
            "DEVICE_IDENTITY_REQUIRED",
            "CONTROL_UI_DEVICE_IDENTITY_REQUIRED",
        )
    }
}
