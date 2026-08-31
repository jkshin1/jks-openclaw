package com.personaledge.agent

import com.personaledge.core.data.SecretKeyName
import com.personaledge.core.data.SecretHealth
import com.personaledge.core.data.SecretVault
import kotlinx.coroutines.CancellationException

/**
 * A credential the user can supply. The enum is closed, so the UI cannot invent a slot and no
 * caller can name one the vault does not know.
 */
enum class CredentialSlot(
    val key: SecretKeyName,
    val label: String,
    val hint: String,
) {
    // Maps keeps its NCP key pair. Web search exposes only the single Tavily fallback key; legacy
    // NAVER Search identifiers remain in SecretKeyName so an update never deletes old ciphertext.
    NAVER_MAP_CLIENT_ID(
        key = SecretKeyName.NAVER_MAP_CLIENT_ID,
        label = "네이버 지도 Key ID (클라우드 플랫폼)",
        hint = "NCP 애플리케이션의 Client ID",
    ),
    NAVER_MAP_CLIENT_SECRET(
        key = SecretKeyName.NAVER_MAP_CLIENT_SECRET,
        label = "네이버 지도 Key (클라우드 플랫폼)",
        hint = "같은 NCP 애플리케이션의 Client Secret",
    ),
    TAVILY_API_KEY(
        key = SecretKeyName.TAVILY_API_KEY,
        label = "Tavily 백업 검색 API 키",
        hint = "단일 API 키 · 저장할 때 Tavily에서 확인",
    ),
}

enum class CredentialVerificationFailure {
    INVALID_CREDENTIAL,
    RATE_LIMITED,
    NETWORK_UNAVAILABLE,
    PROVIDER_UNAVAILABLE,
}

/** A closed verification result; provider text and the candidate value cannot enter the UI. */
sealed interface CredentialVerification {
    data object Accepted : CredentialVerification

    data class Rejected(
        val failure: CredentialVerificationFailure,
    ) : CredentialVerification
}

/** Checks a credential without retaining it or exposing a provider response to settings. */
fun interface CredentialVerifier {
    suspend fun verify(slot: CredentialSlot, value: String): CredentialVerification

    companion object {
        val ALLOW_ALL = CredentialVerifier { _, _ -> CredentialVerification.Accepted }
    }
}

/**
 * Whether a credential is present — never its value.
 *
 * There is deliberately no field holding the secret. Once stored, the only thing this app can
 * answer in settings is that it exists; reading it back is reserved for the gated provider request
 * that actually needs it.
 */
data class CredentialStatus(
    val slot: CredentialSlot,
    val health: SecretHealth,
) {
    constructor(slot: CredentialSlot, stored: Boolean) : this(
        slot = slot,
        health = if (stored) SecretHealth.READABLE else SecretHealth.ABSENT,
    )

    val stored: Boolean
        get() = health == SecretHealth.READABLE

    val hasCiphertext: Boolean
        get() = health != SecretHealth.ABSENT
}

sealed interface CredentialStoreResult {
    data object Stored : CredentialStoreResult

    /** [reason] is app-authored and never echoes the value the user typed. */
    data class Rejected(val reason: String) : CredentialStoreResult
}

/**
 * The settings-screen view of the Keystore vault.
 *
 * Values move one way: in. The vault encrypts under an app-scoped Android Keystore key whose key
 * material is not exposed to this app, so losing the key — a reinstall, a factory reset, a new
 * phone — means re-entering the credential rather than recovering it.
 */
class CredentialSettings(
    private val vault: SecretVault,
    private val verifier: CredentialVerifier = CredentialVerifier.ALLOW_ALL,
) {
    suspend fun statuses(): List<CredentialStatus> = CredentialSlot.entries.map { slot ->
        CredentialStatus(
            slot = slot,
            health = runCatching { vault.health(slot.key) }.getOrDefault(SecretHealth.UNREADABLE),
        )
    }

    suspend fun store(slot: CredentialSlot, value: String): CredentialStoreResult {
        // Trailing newlines come free with most clipboard copies; internal whitespace does not,
        // and almost always means part of the surrounding page was pasted too.
        val trimmed = value.trim()
        return when {
            trimmed.isEmpty() -> CredentialStoreResult.Rejected("값을 입력하세요.")
            trimmed.length > MAX_CREDENTIAL_CHARACTERS ->
                CredentialStoreResult.Rejected("키가 너무 깁니다. 붙여넣은 내용을 확인하세요.")
            trimmed.any(Char::isWhitespace) ->
                CredentialStoreResult.Rejected("공백이 포함되어 있습니다. 키만 붙여넣었는지 확인하세요.")
            trimmed.any(Char::isISOControl) ->
                CredentialStoreResult.Rejected("사용할 수 없는 문자가 포함되어 있습니다.")
            else -> verifyAndStore(slot, trimmed)
        }
    }

    private suspend fun verifyAndStore(
        slot: CredentialSlot,
        value: String,
    ): CredentialStoreResult {
        val verification = try {
            verifier.verify(slot, value)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CredentialVerification.Rejected(
                CredentialVerificationFailure.PROVIDER_UNAVAILABLE,
            )
        }
        if (verification is CredentialVerification.Rejected) {
            return CredentialStoreResult.Rejected(
                verificationFailureReason(slot, verification.failure),
            )
        }

        return try {
            vault.store(slot.key, value)
            CredentialStoreResult.Stored
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CredentialStoreResult.Rejected("키를 저장하지 못했습니다.")
        }
    }

    suspend fun delete(slot: CredentialSlot): Boolean =
        runCatching { vault.remove(slot.key) }.getOrDefault(false)

    companion object {
        const val MAX_CREDENTIAL_CHARACTERS = 200
        const val TAVILY_INVALID_CREDENTIAL_REASON =
            "Tavily가 이 API 키를 거부했습니다. 대시보드의 API Keys에서 키 전체를 다시 복사하세요."
        const val TAVILY_RATE_LIMITED_REASON =
            "Tavily 키 확인 요청이 일시 제한되었습니다. 잠시 후 다시 시도하세요."
        const val TAVILY_NETWORK_UNAVAILABLE_REASON =
            "Tavily에 연결하지 못했습니다. 인터넷 연결을 확인한 뒤 다시 시도하세요."
        const val TAVILY_PROVIDER_UNAVAILABLE_REASON =
            "Tavily 키 확인 서비스 응답을 처리하지 못했습니다. 잠시 후 다시 시도하세요."

        private fun verificationFailureReason(
            slot: CredentialSlot,
            failure: CredentialVerificationFailure,
        ): String = if (slot == CredentialSlot.TAVILY_API_KEY) {
            when (failure) {
                CredentialVerificationFailure.INVALID_CREDENTIAL -> TAVILY_INVALID_CREDENTIAL_REASON
                CredentialVerificationFailure.RATE_LIMITED -> TAVILY_RATE_LIMITED_REASON
                CredentialVerificationFailure.NETWORK_UNAVAILABLE ->
                    TAVILY_NETWORK_UNAVAILABLE_REASON
                CredentialVerificationFailure.PROVIDER_UNAVAILABLE ->
                    TAVILY_PROVIDER_UNAVAILABLE_REASON
            }
        } else {
            "키를 확인하지 못했습니다. 발급한 키와 네트워크 연결을 확인하세요."
        }
    }
}
