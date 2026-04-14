package com.aegisf6.app.engine

import com.aegisf6.app.model.ActiveSourceMode
import kotlin.math.abs

data class NoiseFilterResult(
    val filteredConfidence: Int,
    val suppressed: Boolean,
    val reason: String
)

object UrbanNoiseFilter {
    fun apply(
        rawConfidence: Int,
        backgroundBaseline: Int,
        distanceKm: Double,
        activeMode: ActiveSourceMode,
        lastConfidences: List<Int>
    ): NoiseFilterResult {
        var score = (rawConfidence - (backgroundBaseline * 0.45)).toInt().coerceIn(0, 100)
        var reason = ""

        val stableUrbanHum = lastConfidences.size >= 3 &&
            lastConfidences.takeLast(3).zipWithNext().all { (a, b) -> abs(a - b) <= 4 }

        if (stableUrbanHum) {
            score = (score - 15).coerceAtLeast(0)
            reason = "Стабільний міський гул"
        }

        if (distanceKm < 1.2 && activeMode == ActiveSourceMode.PHONE_SOLO) {
            score = (score - 10).coerceAtLeast(0)
            if (reason.isEmpty()) reason = "Близький локальний шум"
        }

        val suppressed = score < rawConfidence && reason.isNotEmpty()
        return NoiseFilterResult(score, suppressed, reason)
    }
}
