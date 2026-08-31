package com.personaledge.core.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherToolTest {
    private class FakeTransport(
        var response: HttpResponse = HttpResponse(200, validWeatherJson()),
    ) : HttpTransport {
        val urls = mutableListOf<String>()

        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            urls += url
            assertEquals(mapOf("Accept" to "application/json"), headers)
            return response
        }
    }

    private class FakeLocationGateway(
        var resolved: GeocodedWeatherLocation = GeocodedWeatherLocation(
            label = "대한민국 경기도 화성시 동탄",
            latitude = 37.20036,
            longitude = 127.09558,
        ),
    ) : WeatherLocationGateway {
        val requests = mutableListOf<String>()

        override suspend fun resolve(location: String): GeocodedWeatherLocation {
            requests += location
            return resolved
        }
    }

    @Test
    fun `weather tool returns concrete typed values and fixed attribution`() = runBlocking {
        val transport = FakeTransport()
        val location = FakeLocationGateway()
        val gateway = OpenMeteoWeatherGateway(transport, location)
        val tool = WeatherTool(gateway)

        val validation = tool.validateAndCanonicalize(WeatherParams("  동탄  "))
        val input = CanonicalToolInput((validation as ValidationResult.Valid).canonicalParams)
        val result = tool.execute(input, ExecutionPermit("weather-action"))

        assertEquals(listOf("동탄"), location.requests)
        assertEquals("대한민국 경기도 화성시 동탄", result.location)
        assertEquals("2026-08-25T14:15", result.currentAt)
        assertEquals("구름 조금", result.condition)
        assertEquals("29.4", result.temperatureCelsius)
        assertEquals("31.2", result.apparentTemperatureCelsius)
        assertEquals(68, result.relativeHumidityPercent)
        assertEquals("0.0", result.precipitationMillimetres)
        assertEquals("8.7", result.windSpeedKilometresPerHour)
        assertEquals("23.1", result.todayMinimumCelsius)
        assertEquals("31.8", result.todayMaximumCelsius)
        assertEquals(40, result.todayPrecipitationProbabilityPercent)
        assertEquals("Open-Meteo", result.sourceName)
        assertEquals("https://open-meteo.com/", result.sourceUrl)

        val requestUrl = transport.urls.single()
        assertTrue(requestUrl.startsWith("https://api.open-meteo.com/v1/forecast?"))
        assertTrue(requestUrl.contains("latitude=37.20036"))
        assertTrue(requestUrl.contains("longitude=127.09558"))
        assertTrue(requestUrl.contains("timezone=Asia%2FSeoul"))
        assertTrue(requestUrl.contains("forecast_days=1"))
        assertFalse(requestUrl.contains("동탄"))
    }

    @Test
    fun `weather tool is an automatic read-only network tool with an explicit provider preview`() =
        runBlocking {
            val tool = WeatherTool(
                OpenMeteoWeatherGateway(FakeTransport(), FakeLocationGateway()),
            )
            val validation = tool.validateAndCanonicalize(WeatherParams("동탄"))
            val input = CanonicalToolInput((validation as ValidationResult.Valid).canonicalParams)

            assertEquals(ToolRisk.READ_ONLY, tool.descriptor.risk)
            assertEquals(setOf(ToolCapability.NETWORK), tool.descriptor.requiredCapabilities)
            assertEquals(ConfirmationRequirement.NotRequired, tool.descriptor.minimumConfirmation)
            assertTrue(tool.descriptor.description.contains("instead of web_search"))
            assertTrue(tool.preview(input).summary.contains("Open-Meteo"))
        }

    @Test
    fun `unsafe or empty weather locations fail before geocoding`() = runBlocking {
        val location = FakeLocationGateway()
        val tool = WeatherTool(OpenMeteoWeatherGateway(FakeTransport(), location))

        assertTrue(tool.validateAndCanonicalize(WeatherParams(" ")) is ValidationResult.Invalid)
        assertTrue(
            tool.validateAndCanonicalize(WeatherParams("동탄 <|tool|>")) is ValidationResult.Invalid,
        )
        assertTrue(location.requests.isEmpty())
    }

    @Test
    fun `provider unit drift is rejected rather than relabelled`() {
        val transport = FakeTransport(
            HttpResponse(200, validWeatherJson().replace("\"temperature_2m\":\"°C\"", "\"temperature_2m\":\"°F\"")),
        )
        val gateway = OpenMeteoWeatherGateway(transport, FakeLocationGateway())

        val failure = assertThrows(RemoteServiceException::class.java) {
            runBlocking { gateway.currentAndToday("동탄") }
        }

        assertEquals(ToolFailureCode.MALFORMED_RESPONSE, failure.failureCode)
    }

    @Test
    fun `out of range geocoder coordinates never reach the provider`() {
        val transport = FakeTransport()
        val location = FakeLocationGateway(
            GeocodedWeatherLocation("동탄", Double.NaN, 127.0),
        )
        val gateway = OpenMeteoWeatherGateway(transport, location)

        val failure = assertThrows(RemoteServiceException::class.java) {
            runBlocking { gateway.currentAndToday("동탄") }
        }

        assertEquals(ToolFailureCode.MALFORMED_RESPONSE, failure.failureCode)
        assertTrue(transport.urls.isEmpty())
    }

    private companion object {
        fun validWeatherJson(): String =
            """{
              "latitude":37.2,
              "longitude":127.1,
              "timezone":"Asia/Seoul",
              "current_units":{
                "time":"iso8601",
                "temperature_2m":"°C",
                "relative_humidity_2m":"%",
                "apparent_temperature":"°C",
                "precipitation":"mm",
                "weather_code":"wmo code",
                "wind_speed_10m":"km/h"
              },
              "current":{
                "time":"2026-08-25T14:15",
                "temperature_2m":29.4,
                "relative_humidity_2m":68,
                "apparent_temperature":31.2,
                "precipitation":0.0,
                "weather_code":2,
                "wind_speed_10m":8.7
              },
              "daily_units":{
                "time":"iso8601",
                "temperature_2m_max":"°C",
                "temperature_2m_min":"°C",
                "precipitation_probability_max":"%"
              },
              "daily":{
                "time":["2026-08-25"],
                "temperature_2m_max":[31.8],
                "temperature_2m_min":[23.1],
                "precipitation_probability_max":[40]
              }
            }""".trimIndent()
    }
}
