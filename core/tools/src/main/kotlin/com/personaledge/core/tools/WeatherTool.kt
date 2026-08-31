package com.personaledge.core.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.util.Locale

data class WeatherParams(
    val location: String,
) : ToolParams

data class GeocodedWeatherLocation(
    val label: String,
    val latitude: Double,
    val longitude: Double,
)

data class WeatherResult(
    val location: String,
    val currentAt: String,
    val condition: String,
    val temperatureCelsius: String,
    val apparentTemperatureCelsius: String,
    val relativeHumidityPercent: Int,
    val precipitationMillimetres: String,
    val windSpeedKilometresPerHour: String,
    val todayMinimumCelsius: String,
    val todayMaximumCelsius: String,
    val todayPrecipitationProbabilityPercent: Int,
    val sourceName: String,
    val sourceUrl: String,
)

/** Resolves the user's place through the device location service, never through model text. */
interface WeatherLocationGateway {
    suspend fun resolve(location: String): GeocodedWeatherLocation
}

interface WeatherGateway {
    suspend fun currentAndToday(location: String): WeatherResult
}

/**
 * Fixed-host Open-Meteo client.
 *
 * Location labels come from the Android geocoder but coordinates are the only variable values
 * sent to Open-Meteo. Provider JSON is treated as untrusted: every field and unit is checked and
 * only bounded, typed values cross back into the agent ToolResponse.
 */
class OpenMeteoWeatherGateway(
    private val transport: HttpTransport,
    private val locationGateway: WeatherLocationGateway,
) : WeatherGateway {
    override suspend fun currentAndToday(location: String): WeatherResult {
        val resolved = locationGateway.resolve(location)
        validateCoordinates(resolved)
        val response = transport.get(
            url = forecastUrl(resolved),
            headers = mapOf("Accept" to "application/json"),
        )
        if (!response.isSuccessful) throw openMeteoFailure(response.statusCode)

        val document = parseWeatherDocument(response.body)
        if (document.string("timezone") != SEOUL_TIME_ZONE) malformedWeatherResponse()
        val currentUnits = document.obj("current_units") ?: malformedWeatherResponse()
        val dailyUnits = document.obj("daily_units") ?: malformedWeatherResponse()
        requireUnit(currentUnits, "temperature_2m", "°C")
        requireUnit(currentUnits, "apparent_temperature", "°C")
        requireUnit(currentUnits, "relative_humidity_2m", "%")
        requireUnit(currentUnits, "precipitation", "mm")
        requireUnit(currentUnits, "wind_speed_10m", "km/h")
        requireUnit(dailyUnits, "temperature_2m_min", "°C")
        requireUnit(dailyUnits, "temperature_2m_max", "°C")
        requireUnit(dailyUnits, "precipitation_probability_max", "%")

        val current = document.obj("current") ?: malformedWeatherResponse()
        val daily = document.obj("daily") ?: malformedWeatherResponse()
        val currentAt = current.string("time")
            ?.takeIf(CURRENT_TIME_PATTERN::matches)
            ?: malformedWeatherResponse()
        val weatherCode = current.strictInt("weather_code", 0, 99)
        val temperature = current.strictDouble("temperature_2m", -100.0, 70.0)
        val apparent = current.strictDouble("apparent_temperature", -120.0, 80.0)
        val humidity = current.strictInt("relative_humidity_2m", 0, 100)
        val precipitation = current.strictDouble("precipitation", 0.0, 1_000.0)
        val windSpeed = current.strictDouble("wind_speed_10m", 0.0, 500.0)

        val dailyDates = daily.array("time") ?: malformedWeatherResponse()
        val minimum = daily.firstStrictDouble("temperature_2m_min", -100.0, 70.0)
        val maximum = daily.firstStrictDouble("temperature_2m_max", -100.0, 70.0)
        val precipitationProbability = daily.firstStrictInt(
            "precipitation_probability_max",
            0,
            100,
        )
        if (dailyDates.size() != 1 || dailyDates.stringAt(0)?.matches(DATE_PATTERN) != true ||
            minimum > maximum
        ) {
            malformedWeatherResponse()
        }

        return WeatherResult(
            location = UntrustedText.clean(resolved.label, MAX_LOCATION_LABEL_CHARACTERS)
                .ifBlank { location },
            currentAt = currentAt,
            condition = weatherCode.toKoreanCondition(),
            temperatureCelsius = temperature.oneDecimal(),
            apparentTemperatureCelsius = apparent.oneDecimal(),
            relativeHumidityPercent = humidity,
            precipitationMillimetres = precipitation.oneDecimal(),
            windSpeedKilometresPerHour = windSpeed.oneDecimal(),
            todayMinimumCelsius = minimum.oneDecimal(),
            todayMaximumCelsius = maximum.oneDecimal(),
            todayPrecipitationProbabilityPercent = precipitationProbability,
            sourceName = SOURCE_NAME,
            sourceUrl = SOURCE_URL,
        )
    }

    private fun forecastUrl(location: GeocodedWeatherLocation): String {
        val latitude = "%.5f".format(Locale.ROOT, location.latitude)
        val longitude = "%.5f".format(Locale.ROOT, location.longitude)
        return "$FORECAST_ENDPOINT?latitude=$latitude&longitude=$longitude" +
            "&current=$CURRENT_VARIABLES&daily=$DAILY_VARIABLES" +
            "&timezone=Asia%2FSeoul&forecast_days=1"
    }

    private fun validateCoordinates(location: GeocodedWeatherLocation) {
        if (!location.latitude.isFinite() || !location.longitude.isFinite() ||
            location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0 ||
            location.label.isBlank()
        ) {
            throw RemoteServiceException(
                ToolFailureCode.MALFORMED_RESPONSE,
                "위치 확인 결과 형식이 예상과 다릅니다.",
            )
        }
    }

    private companion object {
        const val FORECAST_ENDPOINT = "https://api.open-meteo.com/v1/forecast"
        const val CURRENT_VARIABLES =
            "temperature_2m,relative_humidity_2m,apparent_temperature,precipitation," +
                "weather_code,wind_speed_10m"
        const val DAILY_VARIABLES =
            "temperature_2m_max,temperature_2m_min,precipitation_probability_max"
        const val SEOUL_TIME_ZONE = "Asia/Seoul"
        const val SOURCE_NAME = "Open-Meteo"
        const val SOURCE_URL = "https://open-meteo.com/"
        const val MAX_LOCATION_LABEL_CHARACTERS = 160
        val CURRENT_TIME_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$")
        val DATE_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    }
}

/** Current and today's actual forecast values; use this instead of web search for weather. */
class WeatherTool(
    private val gateway: WeatherGateway,
) : AgentTool<WeatherParams, WeatherResult> {
    override val descriptor = ToolDescriptor(
        name = NAME,
        description = "Get concrete current conditions and today's forecast for a place in Korea. " +
            "Use this for weather requests instead of web_search. The final answer must report " +
            "the numeric weather values and include the returned source URL.",
        risk = ToolRisk.READ_ONLY,
        requiredCapabilities = setOf(ToolCapability.NETWORK),
    )

    override suspend fun validateAndCanonicalize(params: WeatherParams): ValidationResult {
        val location = params.location.trim()
        if (location.isEmpty() || CalendarText.codePointLength(location) > MAX_LOCATION_CHARACTERS) {
            return ValidationResult.Invalid(
                "날씨 위치는 1자 이상 ${MAX_LOCATION_CHARACTERS}자 이하여야 합니다.",
            )
        }
        if (!CalendarText.isSafeText(location)) {
            return ValidationResult.Invalid("날씨 위치에 허용되지 않는 문자가 있습니다.")
        }
        return ValidationResult.Valid(CanonicalFields.encode(mapOf(FIELD_LOCATION to location)))
    }

    override fun preview(input: CanonicalToolInput): ActionPreview {
        val location = CanonicalFields.decode(input).requiredString(FIELD_LOCATION)
        return ActionPreview(
            title = "날씨 조회",
            summary = "$location 위치를 기기 위치 서비스로 확인하고 Open-Meteo에서 날씨를 조회합니다.",
        )
    }

    override suspend fun execute(
        input: CanonicalToolInput,
        permit: ExecutionPermit,
    ): WeatherResult = gateway.currentAndToday(
        CanonicalFields.decode(input).requiredString(FIELD_LOCATION),
    )

    companion object {
        const val NAME = "weather_current"
        const val MAX_LOCATION_CHARACTERS = 80
        internal const val FIELD_LOCATION = "location"
    }
}

private fun parseWeatherDocument(body: String): JsonObject = try {
    JsonParser.parseString(body) as? JsonObject ?: malformedWeatherResponse()
} catch (failure: JsonParseException) {
    throw RemoteServiceException(
        ToolFailureCode.MALFORMED_RESPONSE,
        "Open-Meteo 날씨 응답 형식이 예상과 다릅니다.",
        failure,
    )
}

private fun malformedWeatherResponse(): Nothing = throw RemoteServiceException(
    ToolFailureCode.MALFORMED_RESPONSE,
    "Open-Meteo 날씨 응답 형식이 예상과 다릅니다.",
)

private fun openMeteoFailure(statusCode: Int): RemoteServiceException = when (statusCode) {
    400 -> RemoteServiceException(ToolFailureCode.INVALID_REQUEST, "날씨 요청 형식이 올바르지 않습니다.")
    429 -> RemoteServiceException(ToolFailureCode.RATE_LIMITED, "날씨 요청이 너무 많습니다.")
    in 500..599 -> RemoteServiceException(
        ToolFailureCode.PROVIDER_UNAVAILABLE,
        "Open-Meteo가 일시적으로 응답하지 않습니다.",
    )
    else -> RemoteServiceException(
        ToolFailureCode.OTHER_PROVIDER_ERROR,
        "Open-Meteo 날씨 요청이 실패했습니다.",
    )
}

private fun requireUnit(units: JsonObject, field: String, expected: String) {
    if (units.string(field) != expected) malformedWeatherResponse()
}

private fun JsonObject.string(field: String): String? = get(field)
    ?.takeIf { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString }
    ?.asString

private fun JsonObject.obj(field: String): JsonObject? = get(field) as? JsonObject

private fun JsonObject.array(field: String): JsonArray? = get(field) as? JsonArray

private fun JsonObject.strictDouble(field: String, minimum: Double, maximum: Double): Double {
    val primitive = get(field)?.takeIf { value -> value.isJsonPrimitive }?.asJsonPrimitive
        ?: malformedWeatherResponse()
    if (!primitive.isNumber) malformedWeatherResponse()
    val value = primitive.asString.toDoubleOrNull()
        ?.takeIf { number -> number.isFinite() && number in minimum..maximum }
        ?: malformedWeatherResponse()
    return value
}

private fun JsonObject.strictInt(field: String, minimum: Int, maximum: Int): Int {
    val value = strictDouble(field, minimum.toDouble(), maximum.toDouble())
    if (value % 1.0 != 0.0) malformedWeatherResponse()
    return value.toInt()
}

private fun JsonObject.firstStrictDouble(field: String, minimum: Double, maximum: Double): Double {
    val values = array(field) ?: malformedWeatherResponse()
    if (values.size() != 1) malformedWeatherResponse()
    val primitive = values.get(0)?.takeIf { value -> value.isJsonPrimitive }?.asJsonPrimitive
        ?: malformedWeatherResponse()
    if (!primitive.isNumber) malformedWeatherResponse()
    return primitive.asString.toDoubleOrNull()
        ?.takeIf { number -> number.isFinite() && number in minimum..maximum }
        ?: malformedWeatherResponse()
}

private fun JsonObject.firstStrictInt(field: String, minimum: Int, maximum: Int): Int {
    val value = firstStrictDouble(field, minimum.toDouble(), maximum.toDouble())
    if (value % 1.0 != 0.0) malformedWeatherResponse()
    return value.toInt()
}

private fun JsonArray.stringAt(index: Int): String? = get(index)
    ?.takeIf { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString }
    ?.asString

private fun Double.oneDecimal(): String = "%.1f".format(Locale.ROOT, this)

private fun Int.toKoreanCondition(): String = when (this) {
    0 -> "맑음"
    1 -> "대체로 맑음"
    2 -> "구름 조금"
    3 -> "흐림"
    45, 48 -> "안개"
    51, 53, 55 -> "이슬비"
    56, 57 -> "어는 이슬비"
    61, 63, 65 -> "비"
    66, 67 -> "어는 비"
    71, 73, 75, 77 -> "눈"
    80, 81, 82 -> "소나기"
    85, 86 -> "눈 소나기"
    95 -> "뇌우"
    96, 99 -> "우박을 동반한 뇌우"
    else -> "날씨 코드 $this"
}
