package com.personaledge.agent

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Presence and consent only; the stored home label itself is never exposed back to UI state. */
data class NetworkSetupState(
    val routeLookupEnabled: Boolean = false,
    val webSearchEnabled: Boolean = false,
    val defaultOriginConfigured: Boolean = false,
    val error: String? = null,
)

/** Owns read-only network consent and the presence-only default-origin setting. */
class NetworkCoordinator(
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(NetworkSetupState())
    val state: StateFlow<NetworkSetupState> = _state.asStateFlow()
    private val refreshSequence = AtomicLong(0)

    /** Re-read because DataStore can fail independently and every failure must look like opt-out. */
    fun refresh() {
        requestLoad()
    }

    fun setRouteLookupEnabled(enabled: Boolean) {
        setOwnerConsent(
            feature = OwnerConsentFeature.ROUTE_LOOKUP,
            enabled = enabled,
            failureMessage = "경로 조회 동의를 저장하지 못했습니다.",
        )
    }

    fun setWebSearchEnabled(enabled: Boolean) {
        setOwnerConsent(
            feature = OwnerConsentFeature.WEB_SEARCH,
            enabled = enabled,
            failureMessage = "웹 검색 동의를 저장하지 못했습니다.",
        )
    }

    private fun setOwnerConsent(
        feature: OwnerConsentFeature,
        enabled: Boolean,
        failureMessage: String,
    ) {
        val mutation = container.ownerConsentMutator.setEnabled(feature, enabled)
        if (!enabled) {
            refreshSequence.incrementAndGet()
            _state.update { state ->
                when (feature) {
                    OwnerConsentFeature.ROUTE_LOOKUP ->
                        state.copy(routeLookupEnabled = false)
                    OwnerConsentFeature.WEB_SEARCH -> state.copy(webSearchEnabled = false)
                    else -> state
                }
            }
        }
        scope.launch {
            val outcome = mutation.await()
            requestLoad(if (outcome == OwnerConsentMutationOutcome.FAILED) failureMessage else null)
        }
    }

    fun storeDefaultOrigin(raw: String) {
        when (val validation = SettingsTextPolicy.validateDefaultOrigin(raw)) {
            is SettingsTextValidation.Invalid -> {
                _state.update { state -> state.copy(error = validation.reason) }
            }
            is SettingsTextValidation.Valid -> scope.launch {
                val failure = runCatching {
                    container.settings.setDefaultOriginLabel(validation.value)
                }.exceptionOrNull()
                requestLoad(if (failure == null) null else "기본 출발지를 저장하지 못했습니다.")
            }
        }
    }

    fun deleteDefaultOrigin() {
        scope.launch {
            val failure = runCatching { container.settings.setDefaultOriginLabel(null) }
                .exceptionOrNull()
            requestLoad(if (failure == null) null else "기본 출발지를 삭제하지 못했습니다.")
        }
    }

    private fun requestLoad(error: String? = null) {
        val sequence = refreshSequence.incrementAndGet()
        scope.launch {
            val settings = runCatching { container.settings.current() }.getOrNull()
            val refreshed = NetworkSetupState(
                routeLookupEnabled = settings?.let { current ->
                    container.ownerConsentInterlock.allowed(
                        OwnerConsentFeature.ROUTE_LOOKUP,
                        current.routeLookupEnabled,
                    )
                } ?: false,
                webSearchEnabled = settings?.let { current ->
                    container.ownerConsentInterlock.allowed(
                        OwnerConsentFeature.WEB_SEARCH,
                        current.webSearchEnabled,
                    )
                } ?: false,
                defaultOriginConfigured = settings?.defaultOriginLabel != null,
                error = error ?: if (settings == null) "네트워크 설정을 읽지 못했습니다." else null,
            )
            if (refreshSequence.get() == sequence) _state.value = refreshed
        }
    }
}
