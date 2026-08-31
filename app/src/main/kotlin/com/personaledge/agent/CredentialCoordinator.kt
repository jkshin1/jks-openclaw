package com.personaledge.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Owns presence-only credential health and mutations without retaining secret values. */
class CredentialCoordinator(
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(CredentialsState())
    val state: StateFlow<CredentialsState> = _state.asStateFlow()

    fun refresh() {
        scope.launch {
            _state.value = CredentialsState(statuses = container.credentials.statuses())
        }
    }

    /** The candidate value is consumed by the vault and is never retained in coordinator state. */
    fun store(slot: CredentialSlot, value: String) {
        scope.launch {
            val error = when (val result = container.credentials.store(slot, value)) {
                CredentialStoreResult.Stored -> null
                is CredentialStoreResult.Rejected -> result.reason
            }
            _state.value = CredentialsState(
                statuses = container.credentials.statuses(),
                error = error,
            )
        }
    }

    fun delete(slot: CredentialSlot) {
        scope.launch {
            val deleted = container.credentials.delete(slot)
            _state.value = CredentialsState(
                statuses = container.credentials.statuses(),
                error = if (deleted) null else "키를 삭제하지 못했습니다.",
            )
        }
    }
}
