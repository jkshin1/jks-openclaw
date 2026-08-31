package com.personaledge.agent

import android.content.Context
import android.location.Address
import android.location.Geocoder
import com.personaledge.core.tools.GeocodedWeatherLocation
import com.personaledge.core.tools.RemoteServiceException
import com.personaledge.core.tools.ToolFailureCode
import com.personaledge.core.tools.WeatherLocationGateway
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One explicit, end-user-triggered Android geocoding request for a Korean weather place.
 *
 * The system service chooses its backend; Personal Edge receives only the resolved label and
 * coordinates. It never asks for device GPS location and never retains either the query or result.
 */
class AndroidWeatherLocationGateway(context: Context) : WeatherLocationGateway {
    private val applicationContext = context.applicationContext

    override suspend fun resolve(location: String): GeocodedWeatherLocation =
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) {
                throw RemoteServiceException(
                    ToolFailureCode.PROVIDER_UNAVAILABLE,
                    "기기 위치 검색 서비스를 사용할 수 없습니다.",
                )
            }
            val addresses = try {
                @Suppress("DEPRECATION")
                Geocoder(applicationContext, Locale.KOREA).getFromLocationName(
                    "$location, 대한민국",
                    MAX_RESULTS,
                ).orEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                throw RemoteServiceException(
                    ToolFailureCode.NETWORK_FAILURE,
                    "기기 위치 검색에 실패했습니다.",
                    failure,
                )
            } catch (failure: IllegalArgumentException) {
                throw RemoteServiceException(
                    ToolFailureCode.INVALID_REQUEST,
                    "날씨 위치 요청 형식이 올바르지 않습니다.",
                    failure,
                )
            } catch (failure: RuntimeException) {
                throw RemoteServiceException(
                    ToolFailureCode.PROVIDER_UNAVAILABLE,
                    "기기 위치 검색 서비스를 사용할 수 없습니다.",
                    failure,
                )
            }

            val candidates = addresses.mapIndexed { index, address ->
                WeatherLocationCandidate(
                    sourceIndex = index,
                    countryCode = address.countryCode,
                    latitude = address.latitude,
                    longitude = address.longitude,
                    label = address.displayLabel(location),
                    searchableParts = listOfNotNull(
                        runCatching { address.getAddressLine(0) }.getOrNull(),
                        address.featureName,
                        address.subLocality,
                        address.locality,
                        address.subAdminArea,
                        address.adminArea,
                        address.thoroughfare,
                        address.countryName,
                    ),
                )
            }
            val selected = selectWeatherLocationCandidate(location, candidates)
                ?: throw RemoteServiceException(
                ToolFailureCode.PLACE_NOT_FOUND,
                "대한민국 안에서 요청한 이름과 일치하는 날씨 위치를 찾지 못했습니다.",
            )
            val address = addresses[selected.sourceIndex]

            GeocodedWeatherLocation(
                label = selected.label,
                latitude = address.latitude,
                longitude = address.longitude,
            )
        }

    private fun Address.displayLabel(fallback: String): String {
        val line = runCatching { getAddressLine(0) }
            .getOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        if (line != null) return line
        return listOfNotNull(
            subLocality,
            locality,
            subAdminArea,
            adminArea,
            countryName,
        ).map(String::trim).filter(String::isNotEmpty).distinct().joinToString(" ")
            .ifBlank { fallback }
    }

    private companion object {
        const val MAX_RESULTS = 5
    }
}

/** Pure candidate form so semantic selection can be host-tested without Android framework mocks. */
internal data class WeatherLocationCandidate(
    val sourceIndex: Int,
    val countryCode: String?,
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val searchableParts: List<String>,
)

/**
 * Rejects a geographically valid but semantically unrelated first geocoder result. Every
 * meaningful requested token must appear in the selected Korean address.
 */
internal fun selectWeatherLocationCandidate(
    requestedLocation: String,
    candidates: List<WeatherLocationCandidate>,
): WeatherLocationCandidate? {
    val requestedTokens = PLACE_TOKEN_PATTERN.findAll(requestedLocation.lowercase(Locale.ROOT))
        .map(MatchResult::value)
        .filterNot(PLACE_STOP_WORDS::contains)
        .toList()
    if (requestedTokens.isEmpty()) return null

    return candidates.asSequence()
        .filter { candidate ->
            candidate.countryCode.equals(KOREA_COUNTRY_CODE, ignoreCase = true) &&
                candidate.latitude.isFinite() && candidate.longitude.isFinite() &&
                candidate.latitude in KOREA_LATITUDE_RANGE &&
                candidate.longitude in KOREA_LONGITUDE_RANGE
        }
        .mapNotNull { candidate ->
            val fields = (candidate.searchableParts + candidate.label)
                .map { value -> value.lowercase(Locale.ROOT) }
            if (!requestedTokens.all { token -> fields.any { field -> field.contains(token) } }) {
                return@mapNotNull null
            }
            val exactMatches = requestedTokens.count { token ->
                fields.any { field -> field == token }
            }
            candidate to exactMatches
        }
        .sortedWith(
            compareByDescending<Pair<WeatherLocationCandidate, Int>> { (_, score) -> score }
                .thenBy { (candidate, _) -> candidate.sourceIndex },
        )
        .map { (candidate, _) -> candidate }
        .firstOrNull()
}

private const val KOREA_COUNTRY_CODE = "KR"
private val KOREA_LATITUDE_RANGE = 32.0..39.5
private val KOREA_LONGITUDE_RANGE = 123.0..133.0
private val PLACE_TOKEN_PATTERN = Regex("[\\p{L}\\p{N}]+")
private val PLACE_STOP_WORDS = setOf("대한민국", "한국")
