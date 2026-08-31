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

data class RouteEstimate(
    val originLabel: String,
    val destinationLabel: String,
    val durationMinutes: Int,
    val distanceMeters: Int,
)

class RemoteServiceException(
    failureCode: ToolFailureCode,
    message: String,
    cause: Throwable? = null,
) : ToolExecutionException(failureCode, message, cause) {
    /** Compatibility for older callers that have not assigned a provider failure yet. */
    constructor(message: String, cause: Throwable? = null) : this(
        ToolFailureCode.OTHER_PROVIDER_ERROR,
        message,
        cause,
    )
}

interface RouteGateway {
    suspend fun credentialsPresent(): Boolean

    /** Driving time between two free-text place names. */
    suspend fun estimate(origin: String, destination: String): RouteEstimate
}

/**
 * Driving time via NAVER Cloud Platform.
 *
 * Three calls, because the Directions API takes coordinates rather than place names: geocode each
 * endpoint separately, then ask for the route. The user types place names, so the geocoded address
 * is echoed back in the result — if "강남역" resolved to the wrong 강남역, the answer should show
 * that rather than hide it behind a number.
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
        val keys = credentials() ?: throw RemoteServiceException(
            ToolFailureCode.CREDENTIALS_MISSING,
            "네이버 지도 키가 설정되지 않았습니다.",
        )

        val start = geocode(origin, "출발지", keys)
        val goal = geocode(destination, "도착지", keys)

        val response = transport.get(
            url = "$DIRECTIONS_URL?start=${start.coordinate}&goal=${goal.coordinate}&option=$ROUTE_OPTION",
            headers = headers(keys),
        )
        if (!response.isSuccessful) {
            throw directionsFailure(response)
        }

        val document = parseObject(response.body, "경로 응답을 해석하지 못했습니다.")
        if (document.long("code") != 0L) {
            throw RemoteServiceException(
                ToolFailureCode.MALFORMED_RESPONSE,
                "네이버 지도 경로 응답 형식이 예상과 다릅니다.",
            )
        }
        val summary = document
            .obj("route")
            ?.array(ROUTE_OPTION)
            ?.objectAt(0)
            ?.obj("summary")
            ?: throw RemoteServiceException(
                ToolFailureCode.MALFORMED_RESPONSE,
                "네이버 지도 경로 응답 형식이 예상과 다릅니다.",
            )

        val durationMillis = summary.long("duration")
        val distanceMeters = summary.long("distance")
        val durationMinutes = durationMillis?.div(MILLIS_PER_MINUTE)
        if (durationMillis == null || distanceMeters == null || durationMinutes == null ||
            durationMillis < 0 || distanceMeters < 0 ||
            durationMinutes > Int.MAX_VALUE || distanceMeters > Int.MAX_VALUE
        ) {
            throw RemoteServiceException(
                ToolFailureCode.MALFORMED_RESPONSE,
                "네이버 지도 경로 응답 형식이 예상과 다릅니다.",
            )
        }

        return RouteEstimate(
            originLabel = start.label,
            destinationLabel = goal.label,
            // The API reports milliseconds; minutes are the only precision worth showing.
            durationMinutes = durationMinutes.toInt(),
            distanceMeters = distanceMeters.toInt(),
        )
    }

    private suspend fun geocode(
        place: String,
        placeRole: String,
        keys: NcpCredentials,
    ): GeocodedPlace {
        val response = transport.get(
            url = "$GEOCODE_URL?query=${place.urlEncoded()}",
            headers = headers(keys) + ("Accept" to "application/json"),
        )
        if (!response.isSuccessful) {
            throw geocodingFailure(response)
        }

        val document = parseObject(response.body, "주소 응답을 해석하지 못했습니다.")
        when (document.string("status")) {
            "OK" -> Unit
            "INVALID_REQUEST" -> throw RemoteServiceException(
                ToolFailureCode.INVALID_REQUEST,
                "네이버 지도 주소 요청 형식이 올바르지 않습니다.",
            )
            "SYSTEM_ERROR" -> throw RemoteServiceException(
                ToolFailureCode.PROVIDER_UNAVAILABLE,
                "네이버 지도 서비스가 일시적으로 응답하지 않습니다.",
            )
            else -> throw RemoteServiceException(
                ToolFailureCode.MALFORMED_RESPONSE,
                "네이버 지도 주소 응답 형식이 예상과 다릅니다.",
            )
        }
        val addresses = document.array("addresses") ?: throw RemoteServiceException(
            ToolFailureCode.MALFORMED_RESPONSE,
            "네이버 지도 주소 응답 형식이 예상과 다릅니다.",
        )
        if (addresses.size() == 0) {
            throw RemoteServiceException(
                ToolFailureCode.PLACE_NOT_FOUND,
                "$placeRole 주소를 찾지 못했습니다. 도로명 주소를 포함해 다시 입력하세요.",
            )
        }
        val address = addresses.objectAt(0) ?: throw RemoteServiceException(
            ToolFailureCode.MALFORMED_RESPONSE,
            "네이버 지도 주소 응답 형식이 예상과 다릅니다.",
        )

        val longitude = address.validCoordinate("x", -180.0, 180.0)
        val latitude = address.validCoordinate("y", -90.0, 90.0)
        if (longitude == null || latitude == null) {
            throw RemoteServiceException(
                ToolFailureCode.MALFORMED_RESPONSE,
                "네이버 지도 주소 응답 형식이 예상과 다릅니다.",
            )
        }

        val label = listOf("roadAddress", "jibunAddress")
            .firstNotNullOfOrNull { field -> address.string(field) }
            ?: UntrustedText.clean(place, MAX_LABEL_CHARACTERS)

        return GeocodedPlace(
            coordinate = "${longitude.urlEncoded()},${latitude.urlEncoded()}",
            label = UntrustedText.clean(label, MAX_LABEL_CHARACTERS),
        )
    }

    private fun geocodingFailure(response: HttpResponse): RemoteServiceException {
        val status = parseObjectOrNull(response.body)?.string("status")
        return when (status) {
            "INVALID_REQUEST" -> RemoteServiceException(
                ToolFailureCode.INVALID_REQUEST,
                "네이버 지도 주소 요청 형식이 올바르지 않습니다.",
            )
            "SYSTEM_ERROR" -> RemoteServiceException(
                ToolFailureCode.PROVIDER_UNAVAILABLE,
                "네이버 지도 서비스가 일시적으로 응답하지 않습니다.",
            )
            else -> commonMapsFailure(response, "Geocoding")
        }
    }

    private fun directionsFailure(response: HttpResponse): RemoteServiceException {
        val routeCode = parseObjectOrNull(response.body)?.long("code")
        if (response.statusCode == 400 && routeCode != null) {
            return when (routeCode) {
                1L -> RemoteServiceException(
                    ToolFailureCode.SAME_LOCATION,
                    "출발지와 도착지가 같은 위치로 확인됐습니다.",
                )
                2L, 4L -> RemoteServiceException(
                    ToolFailureCode.POINT_NOT_NEAR_ROAD,
                    "경로 지점이 자동차 도로와 너무 멉니다. 가까운 도로명 주소로 다시 입력하세요.",
                )
                3L -> RemoteServiceException(
                    ToolFailureCode.NO_DRIVING_ROUTE,
                    "두 위치 사이의 자동차 경로를 제공할 수 없습니다.",
                )
                5L -> RemoteServiceException(
                    ToolFailureCode.ROUTE_TOO_LONG,
                    "요청한 경로가 네이버 지도 허용 거리 1,500km를 초과합니다.",
                )
                else -> RemoteServiceException(
                    ToolFailureCode.OTHER_PROVIDER_ERROR,
                    "네이버 지도 경로 요청이 실패했습니다.",
                )
            }
        }
        return commonMapsFailure(response, "Directions 5")
    }

    private fun headers(keys: NcpCredentials) = mapOf(
        "x-ncp-apigw-api-key-id" to keys.keyId,
        "x-ncp-apigw-api-key" to keys.key,
    )

    private data class GeocodedPlace(val coordinate: String, val label: String)

    private companion object {
        const val HOST = "maps.apigw.ntruss.com"
        const val GEOCODE_URL = "https://$HOST/map-geocode/v2/geocode"
        const val DIRECTIONS_URL = "https://$HOST/map-direction/v1/driving"

        /** Balanced route. The other options optimise for speed or tolls, which the user did not ask for. */
        const val ROUTE_OPTION = "traoptimal"
        const val MAX_LABEL_CHARACTERS = 120
        const val MILLIS_PER_MINUTE = 60_000L
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
        ?: throw RemoteServiceException(ToolFailureCode.MALFORMED_RESPONSE, failureMessage)
} catch (failure: JsonParseException) {
    throw RemoteServiceException(ToolFailureCode.MALFORMED_RESPONSE, failureMessage, failure)
}

/** Best-effort parsing for an error response; its HTTP status remains authoritative if malformed. */
private fun parseObjectOrNull(body: String): JsonObject? = try {
    JsonParser.parseString(body) as? JsonObject
} catch (_: JsonParseException) {
    null
}

private fun commonMapsFailure(
    response: HttpResponse,
    apiName: String,
): RemoteServiceException {
    val errorCode = parseObjectOrNull(response.body)
        ?.obj("error")
        ?.primitiveText("errorCode")

    return when {
        response.statusCode == 401 && errorCode == "210" -> RemoteServiceException(
            ToolFailureCode.PERMISSION_DENIED,
            "이 Maps Application에 지도 API 권한이 없습니다. Application 설정을 확인하세요.",
        )
        response.statusCode == 401 -> RemoteServiceException(
            ToolFailureCode.AUTHENTICATION_FAILED,
            "네이버 지도 인증에 실패했습니다. Client ID와 Client Secret을 확인하세요.",
        )
        response.statusCode == 403 -> RemoteServiceException(
            ToolFailureCode.PERMISSION_DENIED,
            "이 Maps Application에 지도 API 권한이 없습니다. Application 설정을 확인하세요.",
        )
        response.statusCode == 429 && errorCode in setOf("410", "420") -> RemoteServiceException(
            ToolFailureCode.RATE_LIMITED,
            "네이버 지도 요청이 너무 많습니다. 잠시 후 다시 시도하세요.",
        )
        response.statusCode == 429 -> RemoteServiceException(
            ToolFailureCode.API_DISABLED_OR_QUOTA_EXCEEDED,
            "$apiName 선택 여부와 무료 제공량을 확인하세요.",
        )
        response.statusCode == 400 -> RemoteServiceException(
            ToolFailureCode.INVALID_REQUEST,
            "네이버 지도 요청 형식이 올바르지 않습니다.",
        )
        response.statusCode == 404 -> RemoteServiceException(
            ToolFailureCode.ENDPOINT_NOT_FOUND,
            "네이버 지도 연결 주소를 찾지 못했습니다.",
        )
        response.statusCode == 413 -> RemoteServiceException(
            ToolFailureCode.REQUEST_TOO_LARGE,
            "네이버 지도 요청이 허용 크기를 초과했습니다.",
        )
        response.statusCode == 504 || errorCode == "510" -> RemoteServiceException(
            ToolFailureCode.PROVIDER_TIMEOUT,
            "네이버 지도 서비스 응답 시간이 초과되었습니다.",
        )
        response.statusCode in setOf(500, 502, 503) || errorCode in setOf("500", "900") ->
            RemoteServiceException(
                ToolFailureCode.PROVIDER_UNAVAILABLE,
                "네이버 지도 서비스가 일시적으로 응답하지 않습니다.",
            )
        else -> RemoteServiceException(
            ToolFailureCode.OTHER_PROVIDER_ERROR,
            "네이버 지도 요청이 실패했습니다.",
        )
    }
}

private fun JsonObject.obj(name: String): JsonObject? = get(name) as? JsonObject

private fun JsonObject.array(name: String): JsonArray? = get(name) as? JsonArray

private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)
    ?.takeIf(JsonPrimitive::isString)
    ?.asString
    ?.takeIf(String::isNotBlank)

private fun JsonObject.long(name: String): Long? = (get(name) as? JsonPrimitive)
    ?.takeIf(JsonPrimitive::isNumber)
    ?.asString
    ?.toLongOrNull()

private fun JsonObject.primitiveText(name: String): String? = (get(name) as? JsonPrimitive)
    ?.takeIf { primitive -> primitive.isString || primitive.isNumber }
    ?.asString
    ?.takeIf(String::isNotBlank)

private fun JsonObject.validCoordinate(
    name: String,
    minimum: Double,
    maximum: Double,
): String? {
    val value = string(name) ?: return null
    val coordinate = value.toDoubleOrNull() ?: return null
    return value.takeIf { coordinate.isFinite() && coordinate in minimum..maximum }
}

private fun JsonArray.objectAt(index: Int): JsonObject? =
    if (index < size()) get(index) as? JsonObject else null

/** NAVER Maps is the only remaining NAVER network product used by this app. */
val NAVER_ALLOWED_HOSTS: Set<String> = setOf("maps.apigw.ntruss.com")
