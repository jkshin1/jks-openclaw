package com.personaledge.agent

import com.personaledge.core.agent.RemoteAgentConnectionState
import com.personaledge.core.agent.RemoteAgentGateway
import com.personaledge.core.data.OpenClawGatewaySettings
import com.personaledge.core.data.OpenClawGatewayTrustMode
import com.personaledge.core.data.SecretHealth
import com.personaledge.core.data.SecretKeyName
import com.personaledge.core.data.SecretVault
import com.personaledge.core.data.SettingsRepository
import com.personaledge.core.openclaw.GatewayEndpoint
import com.personaledge.core.openclaw.OkHttpOpenClawWebSocketTransport
import com.personaledge.core.openclaw.OpenClawAuthToken
import com.personaledge.core.openclaw.OpenClawConnectFailureCategory
import com.personaledge.core.openclaw.OpenClawConnectOptions
import com.personaledge.core.openclaw.OpenClawConnectResult
import com.personaledge.core.openclaw.OpenClawDeviceAuth
import com.personaledge.core.openclaw.OpenClawDeviceIdentity
import com.personaledge.core.openclaw.OpenClawLeafCertificateSha256
import com.personaledge.core.openclaw.OpenClawHealthReadResult
import com.personaledge.core.openclaw.OpenClawReconnectPolicy
import com.personaledge.core.openclaw.OpenClawRemoteAgentGateway
import com.personaledge.core.openclaw.OpenClawRpcClient
import com.personaledge.core.openclaw.OpenClawSecretRecordCodec
import com.personaledge.core.openclaw.OpenClawWebSocketTransport
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Content-free connection state safe for UI presentation and diagnostics. */
enum class OpenClawGatewayUiState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    PAIRING_OR_AUTH_REQUIRED,
    DEGRADED,
}

/**
 * Owns the foreground lifetime of the optional OpenClaw connection.
 *
 * Durable settings, the process-local owner-consent interlock, and the crash-recovery revocation
 * barrier are checked independently on every connection attempt. The coordinator never schedules
 * background work. Moving to the background, disabling consent, entering revocation recovery, or
 * replacing endpoint/trust policy invalidates the current epoch and closes the socket immediately;
 * a non-cooperative stale connect is closed when it returns.
 * A live production session first enqueues bounded best-effort abort frames through its Gateway;
 * local queue acceptance is not evidence of remote cancellation. Abrupt transport/process loss
 * cannot enqueue them, so the remote run may continue until its separately enforced timeout.
 */
class OpenClawGatewayCoordinator internal constructor(
    private val settingsSource: OpenClawGatewaySettingsSource,
    private val ownerConsentInterlock: OwnerConsentInterlock,
    private val revocationGate: OpenClawGatewayRevocationGate,
    private val secretStore: OpenClawSecretRecordStore,
    private val connectionFactory: OpenClawGatewayConnectionFactory,
    private val reconnectPolicy: OpenClawReconnectPolicy,
    parentScope: CoroutineScope,
    clientVersion: String,
    private val identityFactory: () -> OpenClawDeviceIdentity = OpenClawDeviceAuth::generate,
    private val waitBeforeReconnect: suspend (Long) -> Unit = { milliseconds ->
        delay(milliseconds)
    },
) : AutoCloseable {
    internal constructor(
        settingsRepository: SettingsRepository,
        ownerConsentInterlock: OwnerConsentInterlock,
        revocationGate: OpenClawGatewayRevocationGate,
        secretVault: SecretVault,
        parentScope: CoroutineScope,
        clientVersion: String,
    ) : this(
        settingsSource = SettingsRepositoryOpenClawGatewaySettingsSource(settingsRepository),
        ownerConsentInterlock = ownerConsentInterlock,
        revocationGate = revocationGate,
        secretStore = SecretVaultOpenClawSecretRecordStore(secretVault),
        connectionFactory = DefaultOpenClawGatewayConnectionFactory,
        reconnectPolicy = OpenClawReconnectPolicy(),
        parentScope = parentScope,
        clientVersion = clientVersion,
    )

    private val lock = Any()
    private val coordinatorJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + coordinatorJob)
    private val mutableState = MutableStateFlow(OpenClawGatewayUiState.DISCONNECTED)
    private val validatedClientVersion = clientVersion.also { value ->
        require(value.isNotBlank() && value.length <= MAX_CLIENT_VERSION_CHARACTERS)
        require(value.none(Char::isISOControl))
    }
    private var settings = OpenClawGatewaySettings()
    private var foreground = false
    private var epoch = 0L
    private var connectJob: Job? = null
    private var pendingSession: OpenClawGatewaySession? = null
    private var activeSession: OpenClawGatewaySession? = null
    private var closed = false
    private val shutdownStarted = AtomicBoolean(false)

    val state: StateFlow<OpenClawGatewayUiState> = mutableState.asStateFlow()

    private val consentObservation = ownerConsentInterlock.observe(
        OwnerConsentFeature.OPENCLAW_GATEWAY,
    ) {
        restartForCurrentPolicy()
    }

    init {
        coordinatorJob.invokeOnCompletion { shutdown(cancelCoordinatorJob = false) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            revocationGate.revocationBarrierState.collect {
                restartForCurrentPolicy()
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            settingsSource.settings
                .catch { failure ->
                    if (failure is CancellationException) throw failure
                    // SettingsRepository retries recoverable I/O failures. Any other source
                    // failure is terminal for this coordinator instance and closes fail-safe.
                    emit(OpenClawGatewaySettings())
                }
                .collect(::acceptSettings)
        }
    }

    /** The Activity calls this synchronously from its visible lifecycle boundary. */
    fun setForeground(isForeground: Boolean) {
        val changed = synchronized(lock) {
            if (closed || foreground == isForeground) false else {
                foreground = isForeground
                true
            }
        }
        if (changed) restartForCurrentPolicy()
    }

    /** Reconciles a credential add/delete without exposing or caching the value in UI state. */
    fun credentialsChanged() {
        restartForCurrentPolicy()
    }

    /** Returns only the adapter for the currently published, fully durable connection. */
    fun activeGateway(): RemoteAgentGateway? = synchronized(lock) {
        activeSession?.gateway
    }

    fun supportsHealthSnapshot(): Boolean = synchronized(lock) {
        activeSession?.supportsHealthSnapshot == true && state.value == OpenClawGatewayUiState.CONNECTED
    }

    suspend fun readHealthSnapshot(): OpenClawHealthReadResult {
        val session = synchronized(lock) { activeSession } ?: return OpenClawHealthReadResult.NotConnected
        val result = session.readHealthSnapshot()
        return synchronized(lock) {
            if (activeSession === session && state.value == OpenClawGatewayUiState.CONNECTED) result
            else OpenClawHealthReadResult.NotConnected
        }
    }

    override fun close() {
        shutdown(cancelCoordinatorJob = true)
    }

    override fun toString(): String =
        "OpenClawGatewayCoordinator(state=${state.value}, gatewayPublished=${activeGateway() != null})"

    private fun acceptSettings(next: OpenClawGatewaySettings) {
        val changed = synchronized(lock) {
            if (closed || settings == next) false else {
                settings = next
                true
            }
        }
        if (changed) restartForCurrentPolicy()
    }

    /**
     * Invalidates before closing. A connect/store operation that resumes afterward therefore
     * cannot publish its session even when a dependency ignored coroutine cancellation.
     */
    private fun restartForCurrentPolicy() {
        val previousJob: Job?
        val previousPendingSession: OpenClawGatewaySession?
        val previousActiveSession: OpenClawGatewaySession?
        val connectionSpec: ConnectionSpec?
        val expectedEpoch: Long
        synchronized(lock) {
            if (closed) return
            epoch += 1L
            expectedEpoch = epoch
            previousJob = connectJob
            connectJob = null
            previousPendingSession = pendingSession
            pendingSession = null
            previousActiveSession = activeSession
            activeSession = null
            connectionSpec = connectionSpecLocked()
            mutableState.value = if (connectionSpec == null) {
                OpenClawGatewayUiState.DISCONNECTED
            } else {
                OpenClawGatewayUiState.CONNECTING
            }
        }
        previousJob?.cancel()
        previousPendingSession?.close()
        if (previousActiveSession !== previousPendingSession) previousActiveSession?.close()
        if (connectionSpec == null) return

        val launched = scope.launch {
            runConnectionLoop(expectedEpoch, connectionSpec)
        }
        synchronized(lock) {
            if (!closed && epoch == expectedEpoch) {
                connectJob = launched
            } else {
                launched.cancel()
            }
        }
    }

    private fun connectionSpecLocked(): ConnectionSpec? {
        if (!foreground || !settings.enabled || !settings.foregroundOnly) return null
        if (
            revocationGate.revocationBarrierState.value !=
            OpenClawGatewayRevocationBarrierState.CLEAR
        ) {
            return null
        }
        if (!ownerConsentInterlock.allowed(
                OwnerConsentFeature.OPENCLAW_GATEWAY,
                durableEnabled = settings.enabled,
            )
        ) {
            return null
        }
        val endpoint = settings.endpointUrl?.let(GatewayEndpoint::parseRelease) ?: return null
        val leafPin = when (settings.trustMode) {
            OpenClawGatewayTrustMode.SYSTEM -> null
            OpenClawGatewayTrustMode.PINNED_CERT_SHA256 -> settings
                .leafCertificateDerSha256
                ?.let(OpenClawLeafCertificateSha256::parse)
                ?: return null
        }
        return ConnectionSpec(settings = settings, endpoint = endpoint, leafPin = leafPin)
    }

    private suspend fun runConnectionLoop(expectedEpoch: Long, spec: ConnectionSpec) {
        var connectAttempts = 0
        var serverRetryAfterMillis: Long? = null
        try {
            while (
                connectAttempts < MAX_CONNECT_ATTEMPTS &&
                isCurrentAndAllowed(expectedEpoch, spec.settings)
            ) {
                if (connectAttempts > 0) {
                    val policyDelayMillis = reconnectPolicy.delayMillis(connectAttempts - 1)
                    val delayMillis = maxOf(policyDelayMillis, serverRetryAfterMillis ?: 0L)
                    serverRetryAfterMillis = null
                    waitBeforeReconnect(delayMillis)
                    if (!isCurrentAndAllowed(expectedEpoch, spec.settings)) return
                    updateState(expectedEpoch, OpenClawGatewayUiState.CONNECTING)
                }

                connectAttempts += 1
                when (val attempt = connectOnce(expectedEpoch, spec)) {
                    ConnectOnceResult.Stale -> return
                    ConnectOnceResult.LocalFailure -> {
                        updateState(expectedEpoch, OpenClawGatewayUiState.DEGRADED)
                        return
                    }
                    is ConnectOnceResult.Remote -> when (val result = attempt.result) {
                        OpenClawGatewayConnectResult.AuthenticationRequired -> {
                            updateState(
                                expectedEpoch,
                                OpenClawGatewayUiState.PAIRING_OR_AUTH_REQUIRED,
                            )
                            return
                        }
                        OpenClawGatewayConnectResult.ProtocolFailure -> {
                            updateState(expectedEpoch, OpenClawGatewayUiState.DEGRADED)
                            return
                        }
                        is OpenClawGatewayConnectResult.RetryableFailure -> {
                            updateState(expectedEpoch, OpenClawGatewayUiState.DEGRADED)
                            val retryAfterMillis = result.retryAfterMillis
                            if (retryAfterMillis != null &&
                                retryAfterMillis > OpenClawReconnectPolicy.MAX_DELAY_MILLIS
                            ) {
                                return
                            }
                            serverRetryAfterMillis = retryAfterMillis
                        }
                        is OpenClawGatewayConnectResult.Connected -> {
                            when (
                                useConnectedSession(
                                    expectedEpoch = expectedEpoch,
                                    spec = spec,
                                    result = result,
                                    expectedCredentialRecord = attempt.expectedCredentialRecord,
                                )
                            ) {
                                ConnectedSessionResult.Stop -> return
                                ConnectedSessionResult.Retry -> Unit
                            }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            updateState(expectedEpoch, OpenClawGatewayUiState.DEGRADED)
        } finally {
            val currentJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            synchronized(lock) {
                if (connectJob === currentJob) {
                    connectJob = null
                }
            }
        }
    }

    private suspend fun connectOnce(
        expectedEpoch: Long,
        spec: ConnectionSpec,
    ): ConnectOnceResult {
        val storedCredential = readRecord(
            SecretKeyName.OPENCLAW_GATEWAY_CREDENTIAL_RECORD,
        )
        val expectedCredentialRecord = when (storedCredential) {
            OpenClawStoredRecord.Absent -> null
            OpenClawStoredRecord.Unreadable -> return ConnectOnceResult.LocalFailure
            is OpenClawStoredRecord.Present -> storedCredential.value
        }
        val credential = when (storedCredential) {
            OpenClawStoredRecord.Absent -> null
            OpenClawStoredRecord.Unreadable -> return ConnectOnceResult.LocalFailure
            is OpenClawStoredRecord.Present -> OpenClawSecretRecordCodec
                .restoreGatewayCredential(spec.endpoint, storedCredential.value)
                ?: return ConnectOnceResult.LocalFailure
        }
        val identity = when (val record = readRecord(
            SecretKeyName.OPENCLAW_DEVICE_IDENTITY_RECORD,
        )) {
            OpenClawStoredRecord.Unreadable -> return ConnectOnceResult.LocalFailure
            is OpenClawStoredRecord.Present -> OpenClawSecretRecordCodec
                .restoreDeviceIdentity(spec.endpoint, record.value)
                ?: return ConnectOnceResult.LocalFailure
            OpenClawStoredRecord.Absent -> createAndStoreIdentity(expectedEpoch, spec.endpoint)
                ?: return if (isCurrentAndAllowed(expectedEpoch, spec.settings)) {
                    ConnectOnceResult.LocalFailure
                } else {
                    ConnectOnceResult.Stale
                }
        }
        if (!isCurrentAndAllowed(expectedEpoch, spec.settings)) return ConnectOnceResult.Stale
        val result = try {
            connectionFactory.connect(
                OpenClawGatewayConnectRequest(
                    endpoint = spec.endpoint,
                    leafCertificatePin = spec.leafPin,
                    identity = identity,
                    credential = credential,
                    clientVersion = validatedClientVersion,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            OpenClawGatewayConnectResult.RetryableFailure()
        }
        if (result is OpenClawGatewayConnectResult.Connected) {
            val closeOnceSession = CloseOnceOpenClawGatewaySession(result.session)
            if (!registerPendingSession(expectedEpoch, spec.settings, closeOnceSession)) {
                closeOnceSession.close()
                return ConnectOnceResult.Stale
            }
            return ConnectOnceResult.Remote(
                result = OpenClawGatewayConnectResult.Connected(
                    session = closeOnceSession,
                    issuedDeviceCredential = result.issuedDeviceCredential,
                ),
                expectedCredentialRecord = expectedCredentialRecord,
            )
        }
        return ConnectOnceResult.Remote(result, expectedCredentialRecord = null)
    }

    /** Always closes a just-created session, including cancellation during token persistence. */
    private suspend fun useConnectedSession(
        expectedEpoch: Long,
        spec: ConnectionSpec,
        result: OpenClawGatewayConnectResult.Connected,
        expectedCredentialRecord: String?,
    ): ConnectedSessionResult {
        val session = result.session
        return try {
            val tokenStored = persistIssuedCredentialIfPresent(
                expectedEpoch = expectedEpoch,
                endpoint = spec.endpoint,
                credential = result.issuedDeviceCredential,
                expectedRecord = expectedCredentialRecord,
            )
            if (!tokenStored || !publishSession(expectedEpoch, spec.settings, session)) {
                if (isCurrentAndAllowed(expectedEpoch, spec.settings)) {
                    updateState(expectedEpoch, OpenClawGatewayUiState.DEGRADED)
                }
                ConnectedSessionResult.Stop
            } else {
                val lostState = session.connectionState.first { state ->
                    state != RemoteAgentConnectionState.CONNECTED
                }
                if (!isCurrentAndAllowed(expectedEpoch, spec.settings)) {
                    ConnectedSessionResult.Stop
                } else {
                    updateState(expectedEpoch, OpenClawGatewayUiState.DEGRADED)
                    if (lostState == RemoteAgentConnectionState.DEGRADED) {
                        ConnectedSessionResult.Stop
                    } else {
                        ConnectedSessionResult.Retry
                    }
                }
            }
        } finally {
            detachSession(session)
            session.close()
        }
    }

    private suspend fun createAndStoreIdentity(
        expectedEpoch: Long,
        endpoint: GatewayEndpoint,
    ): OpenClawDeviceIdentity? {
        if (!isEpochCurrent(expectedEpoch)) return null
        val identity = runCatching(identityFactory).getOrNull() ?: return null
        val record = runCatching {
            OpenClawSecretRecordCodec.encodeDeviceIdentity(endpoint, identity)
        }.getOrNull() ?: return null
        return try {
            withContext(NonCancellable) {
                if (!isEpochCurrent(expectedEpoch)) return@withContext null
                val inserted = secretStore.compareAndStore(
                    name = SecretKeyName.OPENCLAW_DEVICE_IDENTITY_RECORD,
                    expectedValue = null,
                    value = record,
                )
                if (!inserted) {
                    return@withContext restoreIdentityAfterConcurrentInsert(
                        expectedEpoch = expectedEpoch,
                        endpoint = endpoint,
                    )
                }
                if (isEpochCurrent(expectedEpoch)) {
                    identity
                } else {
                    secretStore.compareAndRemove(
                        SecretKeyName.OPENCLAW_DEVICE_IDENTITY_RECORD,
                        expectedValue = record,
                    )
                    null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun restoreIdentityAfterConcurrentInsert(
        expectedEpoch: Long,
        endpoint: GatewayEndpoint,
    ): OpenClawDeviceIdentity? {
        if (!isEpochCurrent(expectedEpoch)) return null
        return when (val stored = readRecord(SecretKeyName.OPENCLAW_DEVICE_IDENTITY_RECORD)) {
            is OpenClawStoredRecord.Present -> OpenClawSecretRecordCodec
                .restoreDeviceIdentity(endpoint, stored.value)
                ?.takeIf { isEpochCurrent(expectedEpoch) }
            OpenClawStoredRecord.Absent,
            OpenClawStoredRecord.Unreadable,
            -> null
        }
    }

    private suspend fun persistIssuedCredentialIfPresent(
        expectedEpoch: Long,
        endpoint: GatewayEndpoint,
        credential: OpenClawAuthToken?,
        expectedRecord: String?,
    ): Boolean {
        if (credential == null) return isEpochCurrent(expectedEpoch)
        if (!isEpochCurrent(expectedEpoch)) return false
        val record = runCatching {
            OpenClawSecretRecordCodec.encodeGatewayCredential(endpoint, credential)
        }.getOrNull() ?: return false
        return try {
            withContext(NonCancellable) {
                val replaced = secretStore.compareAndStore(
                    name = SecretKeyName.OPENCLAW_GATEWAY_CREDENTIAL_RECORD,
                    expectedValue = expectedRecord,
                    value = record,
                )
                if (!replaced) return@withContext false
                if (isEpochCurrent(expectedEpoch)) {
                    true
                } else {
                    rollbackRecord(
                        name = SecretKeyName.OPENCLAW_GATEWAY_CREDENTIAL_RECORD,
                        writtenRecord = record,
                        previousRecord = expectedRecord,
                    )
                    false
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun rollbackRecord(
        name: SecretKeyName,
        writtenRecord: String,
        previousRecord: String?,
    ) {
        if (previousRecord == null) {
            secretStore.compareAndRemove(name, expectedValue = writtenRecord)
        } else {
            secretStore.compareAndStore(
                name = name,
                expectedValue = writtenRecord,
                value = previousRecord,
            )
        }
    }

    private suspend fun readRecord(name: SecretKeyName): OpenClawStoredRecord = try {
        secretStore.read(name)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        OpenClawStoredRecord.Unreadable
    }

    private fun registerPendingSession(
        expectedEpoch: Long,
        expectedSettings: OpenClawGatewaySettings,
        session: OpenClawGatewaySession,
    ): Boolean = synchronized(lock) {
        if (!isCurrentAndAllowedLocked(expectedEpoch, expectedSettings) ||
            pendingSession != null ||
            activeSession != null ||
            session.connectionState.value != RemoteAgentConnectionState.CONNECTED
        ) {
            false
        } else {
            pendingSession = session
            true
        }
    }

    private fun publishSession(
        expectedEpoch: Long,
        expectedSettings: OpenClawGatewaySettings,
        session: OpenClawGatewaySession,
    ): Boolean = synchronized(lock) {
        if (!isCurrentAndAllowedLocked(expectedEpoch, expectedSettings) ||
            pendingSession !== session ||
            activeSession != null ||
            session.connectionState.value != RemoteAgentConnectionState.CONNECTED
        ) {
            false
        } else {
            pendingSession = null
            activeSession = session
            mutableState.value = OpenClawGatewayUiState.CONNECTED
            true
        }
    }

    private fun detachSession(session: OpenClawGatewaySession) {
        synchronized(lock) {
            if (pendingSession === session) pendingSession = null
            if (activeSession === session) activeSession = null
        }
    }

    private fun isEpochCurrent(expectedEpoch: Long): Boolean = synchronized(lock) {
        !closed && epoch == expectedEpoch
    }

    private fun isCurrentAndAllowed(
        expectedEpoch: Long,
        expectedSettings: OpenClawGatewaySettings,
    ): Boolean = synchronized(lock) {
        isCurrentAndAllowedLocked(expectedEpoch, expectedSettings)
    }

    private fun isCurrentAndAllowedLocked(
        expectedEpoch: Long,
        expectedSettings: OpenClawGatewaySettings,
    ): Boolean = !closed &&
        epoch == expectedEpoch &&
        foreground &&
        settings == expectedSettings &&
        settings.enabled &&
        settings.foregroundOnly &&
        revocationGate.revocationBarrierState.value ==
        OpenClawGatewayRevocationBarrierState.CLEAR &&
        ownerConsentInterlock.allowed(
            OwnerConsentFeature.OPENCLAW_GATEWAY,
            durableEnabled = settings.enabled,
        )

    private fun updateState(expectedEpoch: Long, next: OpenClawGatewayUiState) {
        synchronized(lock) {
            if (!closed && epoch == expectedEpoch) mutableState.value = next
        }
    }

    private fun shutdown(cancelCoordinatorJob: Boolean) {
        if (!shutdownStarted.compareAndSet(false, true)) return
        val job: Job?
        val pending: OpenClawGatewaySession?
        val active: OpenClawGatewaySession?
        synchronized(lock) {
            closed = true
            epoch += 1L
            foreground = false
            job = connectJob
            connectJob = null
            pending = pendingSession
            pendingSession = null
            active = activeSession
            activeSession = null
            mutableState.value = OpenClawGatewayUiState.DISCONNECTED
        }
        consentObservation.close()
        job?.cancel()
        pending?.close()
        if (active !== pending) active?.close()
        if (cancelCoordinatorJob) coordinatorJob.cancel()
    }

    private class ConnectionSpec(
        val settings: OpenClawGatewaySettings,
        val endpoint: GatewayEndpoint,
        val leafPin: OpenClawLeafCertificateSha256?,
    ) {
        override fun toString(): String =
            "ConnectionSpec(settings=$settings, endpoint=$endpoint, leafPin=<redacted>)"
    }

    private sealed interface ConnectOnceResult {
        data object Stale : ConnectOnceResult
        data object LocalFailure : ConnectOnceResult

        class Remote(
            val result: OpenClawGatewayConnectResult,
            val expectedCredentialRecord: String?,
        ) : ConnectOnceResult {
            override fun toString(): String = "ConnectOnceResult.Remote(<redacted>)"
        }
    }

    private enum class ConnectedSessionResult {
        Stop,
        Retry,
    }

    private companion object {
        const val MAX_CONNECT_ATTEMPTS = 6
        const val MAX_CLIENT_VERSION_CHARACTERS = 96
    }
}

/** SettingsRepository adapter kept narrow so host tests need no Android DataStore runtime. */
internal interface OpenClawGatewaySettingsSource {
    val settings: Flow<OpenClawGatewaySettings>
}

private class SettingsRepositoryOpenClawGatewaySettingsSource(
    repository: SettingsRepository,
) : OpenClawGatewaySettingsSource {
    override val settings: Flow<OpenClawGatewaySettings> = repository.settings
        .map { current -> current.openClawGateway }
}

internal sealed interface OpenClawStoredRecord {
    data object Absent : OpenClawStoredRecord
    data object Unreadable : OpenClawStoredRecord

    class Present(val value: String) : OpenClawStoredRecord {
        override fun toString(): String = "OpenClawStoredRecord.Present(<redacted>)"
    }
}

/** SecretVault adapter; callers never receive a raw record in state or exception text. */
internal interface OpenClawSecretRecordStore {
    suspend fun read(name: SecretKeyName): OpenClawStoredRecord

    suspend fun compareAndStore(
        name: SecretKeyName,
        expectedValue: String?,
        value: String,
    ): Boolean

    suspend fun compareAndRemove(name: SecretKeyName, expectedValue: String): Boolean
}

private class SecretVaultOpenClawSecretRecordStore(
    private val vault: SecretVault,
) : OpenClawSecretRecordStore {
    override suspend fun read(name: SecretKeyName): OpenClawStoredRecord = when (
        vault.health(name)
    ) {
        SecretHealth.ABSENT -> OpenClawStoredRecord.Absent
        SecretHealth.UNREADABLE -> OpenClawStoredRecord.Unreadable
        SecretHealth.READABLE -> vault.read(name)
            ?.let(OpenClawStoredRecord::Present)
            ?: OpenClawStoredRecord.Unreadable
    }

    override suspend fun compareAndStore(
        name: SecretKeyName,
        expectedValue: String?,
        value: String,
    ): Boolean = vault.compareAndStore(name, expectedValue, value)

    override suspend fun compareAndRemove(
        name: SecretKeyName,
        expectedValue: String,
    ): Boolean = vault.compareAndRemove(name, expectedValue)
}

internal class OpenClawGatewayConnectRequest(
    val endpoint: GatewayEndpoint,
    val leafCertificatePin: OpenClawLeafCertificateSha256?,
    val identity: OpenClawDeviceIdentity,
    val credential: OpenClawAuthToken?,
    val clientVersion: String,
) {
    override fun toString(): String =
        "OpenClawGatewayConnectRequest(endpoint=$endpoint, leafPin=<redacted>, " +
            "identity=<redacted>, credential=<redacted>)"
}

internal fun interface OpenClawGatewayConnectionFactory {
    suspend fun connect(request: OpenClawGatewayConnectRequest): OpenClawGatewayConnectResult
}

internal sealed interface OpenClawGatewayConnectResult {
    class Connected(
        val session: OpenClawGatewaySession,
        val issuedDeviceCredential: OpenClawAuthToken? = null,
    ) : OpenClawGatewayConnectResult {
        override fun toString(): String =
            "OpenClawGatewayConnectResult.Connected(session=<redacted>, issuedToken=<redacted>)"
    }

    data object AuthenticationRequired : OpenClawGatewayConnectResult
    data object ProtocolFailure : OpenClawGatewayConnectResult
    class RetryableFailure(
        val retryAfterMillis: Long? = null,
    ) : OpenClawGatewayConnectResult {
        init {
            require(retryAfterMillis == null || retryAfterMillis >= 0L)
        }

        override fun toString(): String =
            "OpenClawGatewayConnectResult.RetryableFailure(" +
                "retryAfterMillis=$retryAfterMillis)"
    }
}

internal interface OpenClawGatewaySession : AutoCloseable {
    val gateway: RemoteAgentGateway
    val connectionState: StateFlow<RemoteAgentConnectionState>
    val supportsHealthSnapshot: Boolean get() = false
    suspend fun readHealthSnapshot(): OpenClawHealthReadResult = OpenClawHealthReadResult.Unsupported
}

/** Makes every coordinator close path safe to race without calling the transport twice. */
private class CloseOnceOpenClawGatewaySession(
    private val delegate: OpenClawGatewaySession,
) : OpenClawGatewaySession {
    private val closed = AtomicBoolean(false)
    override val gateway: RemoteAgentGateway = delegate.gateway
    override val connectionState: StateFlow<RemoteAgentConnectionState> = delegate.connectionState
    override val supportsHealthSnapshot: Boolean get() = !closed.get() && delegate.supportsHealthSnapshot
    override suspend fun readHealthSnapshot(): OpenClawHealthReadResult =
        if (closed.get()) OpenClawHealthReadResult.NotConnected else delegate.readHealthSnapshot()

    override fun close() {
        if (closed.compareAndSet(false, true)) delegate.close()
    }

    override fun toString(): String =
        "CloseOnceOpenClawGatewaySession(state=${connectionState.value}, closed=${closed.get()})"
}

/** Production construction boundary: strict endpoint/pin, OkHttp, v4 RPC, then typed adapter. */
private object DefaultOpenClawGatewayConnectionFactory : OpenClawGatewayConnectionFactory {
    override suspend fun connect(
        request: OpenClawGatewayConnectRequest,
    ): OpenClawGatewayConnectResult = connectOpenClawGatewayWithTransport(
        request = request,
        transport = OkHttpOpenClawWebSocketTransport(request.leafCertificatePin),
    )
}

/** Narrow production-protocol seam used by JVM tests without opening a real socket. */
internal suspend fun connectOpenClawGatewayWithTransport(
    request: OpenClawGatewayConnectRequest,
    transport: OpenClawWebSocketTransport,
): OpenClawGatewayConnectResult {
    val client = OpenClawRpcClient(transport)
    val connectResult = try {
        client.connectDetailed(
            endpoint = request.endpoint,
            options = OpenClawConnectOptions(
                identity = request.identity,
                clientVersion = request.clientVersion,
                displayName = "Personal Edge",
                token = request.credential,
            ),
        )
    } catch (cancelled: CancellationException) {
        client.close()
        throw cancelled
    } catch (_: Throwable) {
        OpenClawConnectResult.Failed(OpenClawConnectFailureCategory.PROTOCOL)
    }
    if (connectResult !is OpenClawConnectResult.Connected) {
        client.close()
        return connectResult.toGatewayFailureForApp()
    }
    val hello = connectResult.hello
    val issuedCredential = hello.issuedDeviceToken?.let(OpenClawAuthToken::parse)
    if (hello.issuedDeviceToken != null && issuedCredential == null) {
        client.close()
        return OpenClawGatewayConnectResult.ProtocolFailure
    }
    val gateway = OpenClawRemoteAgentGateway(client)
    return OpenClawGatewayConnectResult.Connected(
        session = LiveOpenClawGatewaySession(client, gateway),
        issuedDeviceCredential = issuedCredential,
    )
}

internal fun OpenClawConnectResult.toGatewayFailureForApp(): OpenClawGatewayConnectResult =
    when (this) {
        is OpenClawConnectResult.Connected -> error("Connected result is not a failure")
        is OpenClawConnectResult.Rejected -> code.uppercase(Locale.ROOT).let { normalizedCode ->
            when {
                normalizedCode == AUTH_RATE_LIMITED_CODE ->
                    OpenClawGatewayConnectResult.RetryableFailure(retryAfterMillis)
                normalizedCode == PAIRING_REQUIRED_CODE ||
                    normalizedCode in DEVICE_IDENTITY_REQUIRED_CODES ||
                    normalizedCode.startsWith(AUTH_CODE_PREFIX) ||
                    normalizedCode.startsWith(DEVICE_AUTH_CODE_PREFIX) ->
                    OpenClawGatewayConnectResult.AuthenticationRequired
                normalizedCode in PROTOCOL_REJECTION_CODES ->
                    OpenClawGatewayConnectResult.ProtocolFailure
                retryable == true ->
                    OpenClawGatewayConnectResult.RetryableFailure(retryAfterMillis)
                else -> OpenClawGatewayConnectResult.ProtocolFailure
            }
        }
        is OpenClawConnectResult.Failed -> when (category) {
            OpenClawConnectFailureCategory.TLS,
            OpenClawConnectFailureCategory.IO,
            OpenClawConnectFailureCategory.TIMEOUT,
            -> OpenClawGatewayConnectResult.RetryableFailure()
            OpenClawConnectFailureCategory.PROTOCOL,
            OpenClawConnectFailureCategory.MALFORMED,
            -> OpenClawGatewayConnectResult.ProtocolFailure
        }
    }

private val DEVICE_IDENTITY_REQUIRED_CODES = setOf(
    "DEVICE_IDENTITY_REQUIRED",
    "CONTROL_UI_DEVICE_IDENTITY_REQUIRED",
)

private val PROTOCOL_REJECTION_CODES = setOf("PROTOCOL_MISMATCH", "CLIENT_VERSION_MISMATCH")
private const val AUTH_RATE_LIMITED_CODE = "AUTH_RATE_LIMITED"
private const val PAIRING_REQUIRED_CODE = "PAIRING_REQUIRED"
private const val AUTH_CODE_PREFIX = "AUTH_"
private const val DEVICE_AUTH_CODE_PREFIX = "DEVICE_AUTH_"

private class LiveOpenClawGatewaySession(
    private val client: OpenClawRpcClient,
    override val gateway: OpenClawRemoteAgentGateway,
) : OpenClawGatewaySession {
    private val closed = AtomicBoolean(false)
    override val connectionState: StateFlow<RemoteAgentConnectionState> = gateway.connectionState
    override val supportsHealthSnapshot: Boolean get() = !closed.get() && client.supportsHealthSnapshot
    override suspend fun readHealthSnapshot(): OpenClawHealthReadResult = client.readHealthSnapshot()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Gateway close seals submissions and enqueues response-independent aborts first. The
        // client owns the socket, so it must remain open until that bounded enqueue pass returns.
        gateway.close()
        client.close()
    }

    override fun toString(): String =
        "LiveOpenClawGatewaySession(state=${connectionState.value}, closed=${closed.get()})"
}
