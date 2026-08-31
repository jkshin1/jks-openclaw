package com.personaledge.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherLocationSelectionTest {
    @Test
    fun `Dongtan request rejects unrelated Seoul first result`() {
        val selected = selectWeatherLocationCandidate(
            requestedLocation = "동탄",
            candidates = listOf(
                candidate(
                    index = 0,
                    label = "대한민국 서울특별시",
                    searchableParts = listOf("서울특별시", "서울"),
                    latitude = 37.5665,
                    longitude = 126.9780,
                ),
                candidate(
                    index = 1,
                    label = "대한민국 경기도 화성시 동탄3동",
                    searchableParts = listOf("경기도", "화성시", "동탄3동"),
                    latitude = 37.2050,
                    longitude = 127.0710,
                ),
            ),
        )

        assertEquals(1, selected?.sourceIndex)
        assertEquals("대한민국 경기도 화성시 동탄3동", selected?.label)
    }

    @Test
    fun `all meaningful requested place tokens must match`() {
        val candidates = listOf(
            candidate(
                index = 0,
                label = "대한민국 경기도 이천시",
                searchableParts = listOf("경기도", "이천시"),
                latitude = 37.2720,
                longitude = 127.4350,
            ),
            candidate(
                index = 1,
                label = "대한민국 강원특별자치도 화천군",
                searchableParts = listOf("강원특별자치도", "화천군"),
                latitude = 38.1060,
                longitude = 127.7080,
            ),
        )

        assertEquals(
            0,
            selectWeatherLocationCandidate("경기도 이천", candidates)?.sourceIndex,
        )
        assertNull(selectWeatherLocationCandidate("경기도 동탄", candidates))
    }

    @Test
    fun `out of Korea and non Korean country candidates fail closed`() {
        assertNull(
            selectWeatherLocationCandidate(
                "동탄",
                listOf(
                    candidate(
                        index = 0,
                        label = "동탄",
                        searchableParts = listOf("동탄"),
                        latitude = 40.0,
                        longitude = 127.0,
                    ),
                    candidate(
                        index = 1,
                        label = "동탄",
                        searchableParts = listOf("동탄"),
                        countryCode = "US",
                    ),
                ),
            ),
        )
    }

    private fun candidate(
        index: Int,
        label: String,
        searchableParts: List<String>,
        latitude: Double = 37.2,
        longitude: Double = 127.0,
        countryCode: String = "KR",
    ) = WeatherLocationCandidate(
        sourceIndex = index,
        countryCode = countryCode,
        latitude = latitude,
        longitude = longitude,
        label = label,
        searchableParts = searchableParts,
    )
}
