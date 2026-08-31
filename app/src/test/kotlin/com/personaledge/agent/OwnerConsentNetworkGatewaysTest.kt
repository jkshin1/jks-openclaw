package com.personaledge.agent

import com.personaledge.core.tools.HttpResponse
import com.personaledge.core.tools.HttpTransport
import com.personaledge.core.tools.ToolExecutionException
import com.personaledge.core.tools.ToolFailureCode
import com.personaledge.core.tools.WebSearchGateway
import com.personaledge.core.tools.WebSearchResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerConsentNetworkGatewaysTest {
    @Test
    fun `transport rechecks before every provider hop`() = runBlocking {
        val feature = OwnerConsentFeature.WEB_SEARCH
        val interlock = OwnerConsentInterlock()
        var delegateCalls = 0
        val delegate = object : HttpTransport {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
            ): HttpResponse {
                delegateCalls += 1
                interlock.requestEnabled(feature, enabled = false)
                return HttpResponse(statusCode = 200, body = "{}")
            }
        }
        val transport = OwnerConsentHttpTransport(delegate) {
            interlock.allowed(feature, durableEnabled = true)
        }

        assertEquals(200, transport.get("https://example.test/first", emptyMap()).statusCode)
        val failure = captureToolFailure {
            transport.get("https://example.test/second", emptyMap())
        }

        assertEquals(ToolFailureCode.PERMISSION_DENIED, failure.failureCode)
        assertEquals(1, delegateCalls)
    }

    @Test
    fun `transport rechecks before every post fallback hop`() = runBlocking {
        val feature = OwnerConsentFeature.WEB_SEARCH
        val interlock = OwnerConsentInterlock()
        var delegateCalls = 0
        val delegate = object : HttpTransport {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
            ): HttpResponse = error("GET is not expected")

            override suspend fun post(
                url: String,
                headers: Map<String, String>,
                body: String,
            ): HttpResponse {
                delegateCalls += 1
                interlock.requestEnabled(feature, enabled = false)
                return HttpResponse(statusCode = 200, body = "{}")
            }
        }
        val transport = OwnerConsentHttpTransport(delegate) {
            interlock.allowed(feature, durableEnabled = true)
        }

        assertEquals(
            200,
            transport.post("https://example.test/first", emptyMap(), "{}").statusCode,
        )
        val failure = captureToolFailure {
            transport.post("https://example.test/fallback", emptyMap(), "{}")
        }

        assertEquals(ToolFailureCode.PERMISSION_DENIED, failure.failureCode)
        assertEquals(1, delegateCalls)
    }

    @Test
    fun `high level gateway rejects a new operation before delegate entry`() = runBlocking {
        val feature = OwnerConsentFeature.WEB_SEARCH
        val interlock = OwnerConsentInterlock()
        var delegateCalled = false
        val gateway = OwnerConsentWebSearchGateway(
            delegate = object : WebSearchGateway {
                override suspend fun credentialsPresent(): Boolean = true

                override suspend fun search(query: String, limit: Int): WebSearchResponse {
                    delegateCalled = true
                    error("must not be called")
                }
            },
            allowed = { interlock.allowed(feature, durableEnabled = true) },
        )
        interlock.requestEnabled(feature, enabled = false)

        val failure = captureToolFailure { gateway.search("query", 1) }

        assertEquals(ToolFailureCode.PERMISSION_DENIED, failure.failureCode)
        assertTrue(!delegateCalled)
    }

    private suspend fun captureToolFailure(block: suspend () -> Unit): ToolExecutionException =
        try {
            block()
            error("expected ToolExecutionException")
        } catch (failure: ToolExecutionException) {
            failure
        }
}
