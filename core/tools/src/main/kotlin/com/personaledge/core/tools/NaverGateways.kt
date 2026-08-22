package com.personaledge.core.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.net.URLEncoder

/** NAVER Cloud Platform application key pair, used for geocoding and directions. */
data class NcpCredentials(
    val keyId: String,
    val key: String,
)

/** NAVER Developers application key pair, used for the search API. Different console, different keys. */
data class NaverSearchCredentials(
    val clientId: String,
    val clientSecret: String,
)

data class RouteEstimate(
    val originLabel: String,
    val destinationLabel: String,
    val durationMinutes: Int,
    val distanceMeters: Int,
)

data class WebSearchHit(
    val title: String,
    val link: String,
    val snippet: String,
)

class RemoteServiceException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface RouteGateway {
    suspend fun credentialsPresent(): Boolean

    /** Driving time between two free-text place names. */
    suspend fun estimate(origin: String, destination: String): RouteEstimate
}

interface WebSearchGateway {
    suspend fun credentialsPresent(): Boolean

    suspend fun search(query: String, limit: Int): List<WebSearchHit>
}

/**
 * Driving time via NAVER Cloud Platform.
 *
 * Two calls, because the Directions API takes coordinates rather than place names: geocode each
 * endpoint, then ask for the route. The user types place names, so the geocoded address is echoed
 * back in the result — if "강남역" resolved to the wrong 강남역, the answer should show that rather
 * than hide it behind a number.
 *
 * Credentials are fetched per call. A key deleted in settings takes effect on the next request
 * instead of at the next process start.
 */
class NaverRouteGateway(
    private val transport: HttpTransport,
    private val credentials: suspend () -> NcpCredentials?,
) : RouteGateway {

    override suspend fun credentialsPresent(): Boolean =
        runCatching { credentials() }.getOrNull() != null

    override suspend fun estimate(origin: String, destination: String): RouteEstimate {
        val keys = credentials() ?: throw RemoteServiceException("네이버 지도 키가 설정되지 않았습니다.")

        val start = geocode(origin, keys)
        val goal = geocode(destination, keys)

        val response = transport.get(
            url = "$DIRECTIONS_URL?start=${start.coordinate}&goal=${goal.coordinate}&option=$ROUTE_OPTION",
            headers = headers(keys),
        )
        if (!response.isSuccessful) {
            throw RemoteServiceException(directionsFailureReason(response))
        }

        val summary = parseObject(response.body, "경로 응답을 해석하지 못했습니다.")
            .obj("route")
            ?.array(ROUTE_OPTION)
            ?.objectAt(0)
            ?.obj("summary")
            ?: throw RemoteServiceException("두 지점 사이의 자동차 경로를 찾지 못했습니다.")

        val durationMillis = summary.long("duration")
        val distanceMeters = summary.long("distance")
        if (durationMillis == null || distanceMeters == null ||
            durationMillis < 0 || distanceMeters < 0
        ) {
            throw RemoteServiceException("경로 응답에 소요 시간이 없습니다.")
        }

        return RouteEstimate(
            originLabel = start.label,
            destinationLabel = goal.label,
            // The API reports milliseconds; minutes are the only precision worth showing.
            durationMinutes = Math.toIntExact(durationMillis / 60_000),
            distanceMeters = Math.toIntExact(distanceMeters),
        )
    }

    private suspend fun geocode(place: String, keys: NcpCredentials): GeocodedPlace {
        val response = transport.get(
            url = "$GEOCODE_URL?query=${place.urlEncoded()}",
            headers = headers(keys) + ("Accept" to "application/json"),
        )
        if (!response.isSuccessful) {
            throw RemoteServiceException(
                if (response.statusCode == 401 || response.statusCode == 403) {
                    "네이버 지도 키가 거부되었습니다. 설정에서 다시 입력하세요."
                } else {
                    "주소를 찾는 중 오류가 발생했습니다."
                },
            )
        }

        val document = parseObject(response.body, "주소 응답을 해석하지 못했습니다.")
        if (document.string("status") != "OK") {
            throw RemoteServiceException("주소를 찾지 못했습니다: $place")
        }
        val address = document.array("addresses")?.objectAt(0)
            ?: throw RemoteServiceException("\"$place\" 위치를 찾지 못했습니다.")

        val longitude = address.string("x")
        val latitude = address.string("y")
        if (longitude == null || latitude == null) {
            throw RemoteServiceException("\"$place\" 좌표를 찾지 못했습니다.")
        }

        val label = listOf("roadAddress", "jibunAddress")
            .firstNotNullOfOrNull { field -> address.string(field) }
            ?: place

        return GeocodedPlace(
            coordinate = "${longitude.urlEncoded()},${latitude.urlEncoded()}",
            label = UntrustedText.clean(label, MAX_LABEL_CHARACTERS),
        )
    }

    private fun directionsFailureReason(response: HttpResponse): String = when {
        response.statusCode == 401 || response.statusCode == 403 ->
            "네이버 지도 키가 거부되었습니다. 설정에서 다시 입력하세요."
        response.statusCode == 429 -> "네이버 지도 호출 한도를 초과했습니다."
        else -> "경로를 가져오지 못했습니다."
    }

    private fun headers(keys: NcpCredentials) = mapOf(
        "x-ncp-apigw-api-key-id" to keys.keyId,
        "x-ncp-apigw-api-key" to keys.key,
    )

    private data class GeocodedPlace(val coordinate: String, val label: String)

    private companion object {
        const val HOST = "naveropenapi.apigw.ntruss.com"
        const val GEOCODE_URL = "https://$HOST/map-geocode/v2/geocode"
        const val DIRECTIONS_URL = "https://$HOST/map-direction/v1/driving"

        /** Balanced route. The other options optimise for speed or tolls, which the user did not ask for. */
        const val ROUTE_OPTION = "traoptimal"
        const val MAX_LABEL_CHARACTERS = 120
    }
}

/**
 * Web search via the NAVER Developers search API.
 *
 * Result text is written by strangers on the open web and ends up in a model prompt, so titles and
 * snippets are stripped of markup and neutralized before they leave this class, and a result whose
 * link is not a plain absolute URL is dropped rather than shown.
 */
class NaverWebSearchGateway(
    private val transport: HttpTransport,
    private val credentials: suspend () -> NaverSearchCredentials?,
) : WebSearchGateway {

    override suspend fun credentialsPresent(): Boolean =
        runCatching { credentials() }.getOrNull() != null

    override suspend fun search(query: String, limit: Int): List<WebSearchHit> {
        require(limit in 1..MAX_DISPLAY)
        val keys = credentials() ?: throw RemoteServiceException("네이버 검색 키가 설정되지 않았습니다.")

        val response = transport.get(
            url = "$SEARCH_URL?query=${query.urlEncoded()}&display=$limit&start=1",
            headers = mapOf(
                "X-Naver-Client-Id" to keys.clientId,
                "X-Naver-Client-Secret" to keys.clientSecret,
            ),
        )
        if (!response.isSuccessful) {
            throw RemoteServiceException(
                when (response.statusCode) {
                    401, 403 -> "네이버 검색 키가 거부되었습니다. 설정에서 다시 입력하세요."
                    429 -> "네이버 검색 호출 한도를 초과했습니다."
                    else -> "검색 결과를 가져오지 못했습니다."
                },
            )
        }

        val items = parseObject(response.body, "검색 응답을 해석하지 못했습니다.")
            .array("items")
            ?: return emptyList()

        return (0 until minOf(items.size(), limit)).mapNotNull { index ->
            items.objectAt(index)?.toHit()
        }
    }

    private fun JsonObject.toHit(): WebSearchHit? {
        val link = string("link").orEmpty()
        if (!UntrustedText.isDisplayableLink(link)) return null

        val title = UntrustedText.clean(string("title").orEmpty(), MAX_TITLE_CHARACTERS)
        if (title.isEmpty()) return null

        return WebSearchHit(
            title = title,
            link = link,
            snippet = UntrustedText.clean(string("description").orEmpty(), MAX_SNIPPET_CHARACTERS),
        )
    }

    private companion object {
        const val HOST = "openapi.naver.com"
        const val SEARCH_URL = "https://$HOST/v1/search/webkr.json"
        const val MAX_DISPLAY = 20
        const val MAX_TITLE_CHARACTERS = 120
        const val MAX_SNIPPET_CHARACTERS = 200
    }
}

internal fun String.urlEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

/**
 * Null-safe reads over a response body written by someone else.
 *
 * A missing member, a wrong type, or an outright malformed document all read as absent, so a
 * server change degrades into a typed [RemoteServiceException] instead of an exception escaping
 * from the middle of a tool.
 */
private fun parseObject(body: String, failureMessage: String): JsonObject = try {
    JsonParser.parseString(body) as? JsonObject
        ?: throw RemoteServiceException(failureMessage)
} catch (failure: JsonParseException) {
    throw RemoteServiceException(failureMessage, failure)
}

private fun JsonObject.obj(name: String): JsonObject? = get(name) as? JsonObject

private fun JsonObject.array(name: String): JsonArray? = get(name) as? JsonArray

private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)
    ?.takeIf(JsonPrimitive::isString)
    ?.asString
    ?.takeIf(String::isNotBlank)

private fun JsonObject.long(name: String): Long? = (get(name) as? JsonPrimitive)
    ?.takeIf(JsonPrimitive::isNumber)
    ?.asLong

private fun JsonArray.objectAt(index: Int): JsonObject? =
    if (index < size()) get(index) as? JsonObject else null

/** Hosts the transport may talk to. Nothing else in this app makes an outbound request. */
val NAVER_ALLOWED_HOSTS: Set<String> = setOf(
    "naveropenapi.apigw.ntruss.com",
    "openapi.naver.com",
)
