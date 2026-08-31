package com.personaledge.agent

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personaledge.core.data.SecretKeyName
import com.personaledge.core.tools.TavilyWebSearchGateway
import com.personaledge.core.tools.WebSearchHit
import com.personaledge.core.tools.WebSearchProvider
import com.personaledge.core.tools.WebSearchResponse
import com.personaledge.core.tools.YouKeylessMcpWebSearchGateway
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Non-destructive, explicitly opted-in acceptance for the two live web-search providers.
 *
 * Ordinary connected tests skip both methods. Each provider has its own runner argument and both
 * methods additionally require a physical device. They send only the fixed public query below,
 * call the provider gateway directly, and never alter the credential vault, model, or history.
 */
@RunWith(AndroidJUnit4::class)
class WebSearchProviderLiveAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun keylessYouComReturnsSanitizedHttpsHits() = runBlocking {
        requireLiveOptIn(YOU_LIVE_ARGUMENT, "Live You.com search requires -e liveYouSearch true.")

        val response = withTimeout(PROVIDER_TIMEOUT_MILLIS) {
            YouKeylessMcpWebSearchGateway(container.httpTransport).search(
                query = PUBLIC_QUERY,
                limit = MAX_HITS,
            )
        }

        assertSanitizedResponse(response, WebSearchProvider.YOU_COM)
    }

    @Test
    fun savedTavilyCredentialReturnsSanitizedHttpsHits() = runBlocking {
        requireLiveOptIn(TAVILY_LIVE_ARGUMENT, "Live Tavily search requires -e liveTavilySearch true.")
        assumeTrue(
            "A saved Tavily API key is required for live acceptance.",
            container.secretVault.contains(SecretKeyName.TAVILY_API_KEY),
        )

        val response = withTimeout(PROVIDER_TIMEOUT_MILLIS) {
            TavilyWebSearchGateway(container.httpTransport) {
                container.secretVault.read(SecretKeyName.TAVILY_API_KEY)
            }.search(
                query = PUBLIC_QUERY,
                limit = MAX_HITS,
            )
        }

        assertSanitizedResponse(response, WebSearchProvider.TAVILY)
    }

    private fun requireLiveOptIn(argument: String, message: String) {
        assumeTrue(
            message,
            InstrumentationRegistry.getArguments().getString(argument) == "true",
        )
        assumeTrue("Live provider acceptance must run on a physical device.", !isEmulator())
    }

    private fun assertSanitizedResponse(
        response: WebSearchResponse,
        expectedProvider: WebSearchProvider,
    ) {
        assertTrue("The live search provider identity was unexpected.", response.provider == expectedProvider)
        assertTrue("The live search returned no usable hits.", response.hits.isNotEmpty())
        assertTrue("The live search returned too many hits.", response.hits.size <= MAX_HITS)
        assertTrue("The live search returned an unsanitized hit.", response.hits.all(::isSanitizedHit))
    }

    private fun isSanitizedHit(hit: WebSearchHit): Boolean =
        hit.title.isNotBlank() &&
            hit.title.codePointLength() <= MAX_TITLE_CHARACTERS &&
            hit.snippet.codePointLength() <= MAX_SNIPPET_CHARACTERS &&
            hit.title.isSanitizedProviderText() &&
            hit.snippet.isSanitizedProviderText() &&
            hit.link.isCanonicalHttpsLink()

    private fun String.isSanitizedProviderText(): Boolean =
        this == trim() &&
            !contains(MODEL_CONTROL_TOKEN_OPEN) &&
            !contains(MODEL_CONTROL_TOKEN_CLOSE) &&
            !CONSECUTIVE_WHITESPACE.containsMatchIn(this) &&
            codePoints().noneMatch(::isUnsafeTextCodePoint)

    private fun String.isCanonicalHttpsLink(): Boolean {
        if (isEmpty() || length > MAX_LINK_CHARACTERS) return false
        if (contains(MODEL_CONTROL_TOKEN_OPEN) || contains(MODEL_CONTROL_TOKEN_CLOSE)) return false
        if (indexOf('\\') >= 0 || codePoints().anyMatch(::isUnsafeLinkCodePoint)) return false

        val uri = runCatching { URI(this) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.isAbsolute &&
            !uri.isOpaque &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.rawFragment == null &&
            uri.port in -1..65_535 &&
            uri.normalize().toASCIIString() == this
    }

    private fun String.codePointLength(): Int = codePointCount(0, length)

    private fun isUnsafeTextCodePoint(codePoint: Int): Boolean =
        Character.isISOControl(codePoint) || when (Character.getType(codePoint)) {
            Character.FORMAT.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
            -> true
            else -> false
        }

    private fun isUnsafeLinkCodePoint(codePoint: Int): Boolean =
        Character.isWhitespace(codePoint) || isUnsafeTextCodePoint(codePoint)

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk", ignoreCase = true)

    private val container: AppContainer
        get() = (instrumentation.targetContext.applicationContext as PersonalEdgeApplication).container

    private companion object {
        const val YOU_LIVE_ARGUMENT = "liveYouSearch"
        const val TAVILY_LIVE_ARGUMENT = "liveTavilySearch"
        const val PUBLIC_QUERY = "대한민국 기상청 공식 홈페이지"
        const val MAX_HITS = 5
        const val MAX_TITLE_CHARACTERS = 120
        const val MAX_SNIPPET_CHARACTERS = 240
        const val MAX_LINK_CHARACTERS = 500
        const val PROVIDER_TIMEOUT_MILLIS = 30_000L
        const val MODEL_CONTROL_TOKEN_OPEN = "<|"
        const val MODEL_CONTROL_TOKEN_CLOSE = "|>"
        val CONSECUTIVE_WHITESPACE = Regex("\\s{2,}")
    }
}
