package com.personaledge.agent

import com.personaledge.core.data.SecretKeyName
import com.personaledge.core.data.SecretVault

/**
 * A credential the user can supply. The enum is closed, so the UI cannot invent a slot and no
 * caller can name one the vault does not know.
 */
enum class CredentialSlot(
    val key: SecretKeyName,
    val label: String,
    val hint: String,
) {
    // Two different NAVER consoles issue two unrelated key pairs: Cloud Platform for maps,
    // Developers for search. Mixing them up is the likeliest setup mistake, so the labels say which.
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
    NAVER_SEARCH_CLIENT_ID(
        key = SecretKeyName.NAVER_SEARCH_CLIENT_ID,
        label = "네이버 검색 Client ID (개발자센터)",
        hint = "developers.naver.com 애플리케이션의 Client ID",
    ),
    NAVER_SEARCH_CLIENT_SECRET(
        key = SecretKeyName.NAVER_SEARCH_CLIENT_SECRET,
        label = "네이버 검색 Client Secret (개발자센터)",
        hint = "같은 개발자센터 애플리케이션의 Client Secret",
    ),
}

/**
 * Whether a credential is present — never its value.
 *
 * There is deliberately no field holding the secret. Once stored, the only thing this app can
 * answer about a credential is that it exists; reading it back is reserved for the request that
 * actually needs it, and no such request exists yet.
 */
data class CredentialStatus(
    val slot: CredentialSlot,
    val stored: Boolean,
)

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
) {
    suspend fun statuses(): List<CredentialStatus> = CredentialSlot.entries.map { slot ->
        CredentialStatus(
            slot = slot,
            stored = runCatching { vault.contains(slot.key) }.getOrDefault(false),
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
            else -> if (runCatching { vault.store(slot.key, trimmed) }.isSuccess) {
                CredentialStoreResult.Stored
            } else {
                CredentialStoreResult.Rejected("키를 저장하지 못했습니다.")
            }
        }
    }

    suspend fun delete(slot: CredentialSlot): Boolean =
        runCatching { vault.remove(slot.key) }.getOrDefault(false)

    companion object {
        const val MAX_CREDENTIAL_CHARACTERS = 200
    }
}
