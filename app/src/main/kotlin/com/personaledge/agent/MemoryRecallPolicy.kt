package com.personaledge.agent

import com.personaledge.core.data.MemoryCategory
import java.text.Normalizer
import java.util.Locale

/** Keeps route turns from inheriting non-place memories as implicit locations. */
object MemoryRecallPolicy {
    fun categoriesFor(prompt: String): Set<MemoryCategory> {
        val normalized = Normalizer.normalize(prompt, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        return if (ROUTE_TERMS.any(normalized::contains)) {
            setOf(MemoryCategory.PLACE)
        } else {
            MemoryCategory.entries.toSet()
        }
    }

    private val ROUTE_TERMS = setOf(
        "경로", "길찾기", "얼마나 걸", "출발", "도착", "교통", "운전", "route", "directions",
    )
}
