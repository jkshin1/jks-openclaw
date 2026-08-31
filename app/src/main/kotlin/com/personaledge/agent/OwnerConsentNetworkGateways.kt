package com.personaledge.agent

import com.personaledge.core.tools.RouteEstimate
import com.personaledge.core.tools.RouteGateway
import com.personaledge.core.tools.HttpResponse
import com.personaledge.core.tools.HttpTransport
import com.personaledge.core.tools.ToolExecutionException
import com.personaledge.core.tools.ToolFailureCode
import com.personaledge.core.tools.WeatherGateway
import com.personaledge.core.tools.WeatherResult
import com.personaledge.core.tools.WebSearchGateway
import com.personaledge.core.tools.WebSearchResponse

/** Revalidates before every GET/POST, including provider fallback and multi-hop gateway calls. */
internal class OwnerConsentHttpTransport(
    private val delegate: HttpTransport,
    private val allowed: suspend () -> Boolean,
) : HttpTransport {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        requireOwnerConsent(allowed())
        return delegate.get(url, headers)
    }

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): HttpResponse {
        requireOwnerConsent(allowed())
        return delegate.post(url, headers, body)
    }
}

/** Final high-level gateway checks after orchestrator validation and execution interlocks. */
internal class OwnerConsentRouteGateway(
    private val delegate: RouteGateway,
    private val allowed: suspend () -> Boolean,
) : RouteGateway {
    override suspend fun credentialsPresent(): Boolean = delegate.credentialsPresent()

    override suspend fun estimate(origin: String, destination: String): RouteEstimate {
        requireOwnerConsent(allowed())
        return delegate.estimate(origin, destination)
    }
}

internal class OwnerConsentWebSearchGateway(
    private val delegate: WebSearchGateway,
    private val allowed: suspend () -> Boolean,
) : WebSearchGateway {
    override suspend fun credentialsPresent(): Boolean = delegate.credentialsPresent()

    override suspend fun search(query: String, limit: Int): WebSearchResponse {
        requireOwnerConsent(allowed())
        return delegate.search(query, limit)
    }
}

internal class OwnerConsentWeatherGateway(
    private val delegate: WeatherGateway,
    private val allowed: suspend () -> Boolean,
) : WeatherGateway {
    override suspend fun currentAndToday(location: String): WeatherResult {
        requireOwnerConsent(allowed())
        return delegate.currentAndToday(location)
    }
}

private fun requireOwnerConsent(allowed: Boolean) {
    if (!allowed) {
        throw ToolExecutionException(
            ToolFailureCode.PERMISSION_DENIED,
            "Owner consent is disabled.",
        )
    }
}
