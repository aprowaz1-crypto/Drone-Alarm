package com.aegisf6.app.engine

import com.aegisf6.app.audio.AudioFrame
import com.aegisf6.app.model.TargetKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

data class ThreatAiResult(
    val shahedScore: Int,
    val missileScore: Int,
    val noiseScore: Int,
    val distanceReliability: Int,
    val suggestedKind: TargetKind,
    val suggestedLabel: String
)

object ThreatAiAnalyzer {
    private const val SAMPLE_RATE_HZ = 44_100.0

    fun analyze(
        frame: AudioFrame,
        backgroundDb: Double,
        btCount: Int,
        strictMode: Boolean
    ): ThreatAiResult {
        val mono = mixDown(frame.leftChannel, frame.rightChannel)
        if (mono.isEmpty()) {
            return ThreatAiResult(0, 0, 100, 0, TargetKind.UNKNOWN, "Недостатньо даних")
        }

        val snrDb = frame.rmsDb - backgroundDb
        val band50 = goertzelPower(mono, 50.0)
        val band100 = goertzelPower(mono, 100.0)
        val band125 = goertzelPower(mono, 125.0)
        val band180 = goertzelPower(mono, 180.0)
        val band250 = goertzelPower(mono, 250.0)
        val band350 = goertzelPower(mono, 350.0)
        val band450 = goertzelPower(mono, 450.0)

        val spectralFlatness = computeSpectralFlatness(
            listOf(band50, band100, band125, band180, band250, band350, band450)
        )
        val harmonicRatio = safeRatio(band125 + band250, band50 + band450 + 1e-6)
        val missileBandRatio = safeRatio(band180 + band250 + band350 + band450, band100 + band125 + 1e-6)
        val mainsHumRatio = safeRatio(band50 + band100, band180 + band250 + band350 + 1e-6)
        val stereoDiff = computeStereoDifference(frame.leftChannel, frame.rightChannel)

        val strictBias = if (strictMode) 0.35 else 0.0
        val headsetBoost = if (btCount > 0) 0.28 else 0.0

        val shahedLogit =
            (snrDb * 0.16) +
            (normalizedBand(frame.peakFrequencyHz, 95f, 145f) * 2.2) +
            (ln(1.0 + harmonicRatio) * 1.4) +
            ((1.0 - spectralFlatness) * 1.1) +
            (headsetBoost * 0.8) -
            (mainsHumRatio * (1.05 + strictBias)) -
            (missileBandRatio * 0.4)

        val missileLogit =
            (snrDb * 0.14) +
            (normalizedBand(frame.peakFrequencyHz, 165f, 480f) * 2.0) +
            (ln(1.0 + missileBandRatio) * 1.25) +
            (spectralFlatness * 0.65) +
            (abs(stereoDiff) * 0.2) -
            (mainsHumRatio * (0.85 + strictBias * 0.4))

        val noiseLogit =
            (mainsHumRatio * (1.55 + strictBias)) +
            ((1.0 - normalizedBand(frame.peakFrequencyHz, 90f, 480f)) * 1.1) +
            ((1.0 - abs(stereoDiff).coerceIn(0.0, 1.0)) * 0.45) +
            ((1.0 - snrDb.coerceIn(0.0, 20.0) / 20.0) * 1.0)

        val shahedScore = (sigmoid(shahedLogit - 1.45) * 100).toInt().coerceIn(0, 100)
        val missileScore = (sigmoid(missileLogit - 1.35) * 100).toInt().coerceIn(0, 100)
        val noiseScore = (sigmoid(noiseLogit - 1.10) * 100).toInt().coerceIn(0, 100)

        val suggestedKind = when {
            noiseScore >= maxOf(shahedScore, missileScore) -> TargetKind.UNKNOWN
            shahedScore >= missileScore -> TargetKind.SHAHED
            else -> TargetKind.MISSILE
        }

        val suggestedLabel = when (suggestedKind) {
            TargetKind.SHAHED -> "AI: Шахед-подібний профіль"
            TargetKind.MISSILE -> "AI: Крилата ракета / ракетний профіль"
            TargetKind.UNKNOWN -> "AI: Шум / непідтверджений профіль"
        }

        val winningScore = maxOf(shahedScore, missileScore)
        val gap = abs(shahedScore - missileScore)
        val reliability = (
            winningScore * 0.55 +
                gap * 0.25 +
                snrDb.coerceIn(0.0, 24.0) * 1.2 +
                if (btCount > 0) 6.0 else 0.0 -
                noiseScore * 0.25
            ).toInt().coerceIn(0, 100)

        return ThreatAiResult(
            shahedScore = shahedScore,
            missileScore = missileScore,
            noiseScore = noiseScore,
            distanceReliability = reliability,
            suggestedKind = suggestedKind,
            suggestedLabel = suggestedLabel
        )
    }

    private fun mixDown(left: FloatArray, right: FloatArray): FloatArray {
        val size = minOf(left.size, right.size)
        return FloatArray(size) { index -> (left[index] + right[index]) * 0.5f }
    }

    private fun goertzelPower(samples: FloatArray, targetFrequencyHz: Double): Double {
        if (samples.isEmpty()) return 0.0
        val normalizedFrequency = targetFrequencyHz / SAMPLE_RATE_HZ
        val coeff = 2.0 * kotlin.math.cos(2.0 * PI * normalizedFrequency)
        var q0 = 0.0
        var q1 = 0.0
        var q2 = 0.0
        for (sample in samples) {
            q0 = coeff * q1 - q2 + sample
            q2 = q1
            q1 = q0
        }
        return (q1 * q1 + q2 * q2 - coeff * q1 * q2).coerceAtLeast(0.0)
    }

    private fun computeSpectralFlatness(bands: List<Double>): Double {
        val safeBands = bands.map { it.coerceAtLeast(1e-9) }
        val geometricMean = exp(safeBands.map { ln(it) }.average())
        val arithmeticMean = safeBands.average().coerceAtLeast(1e-9)
        return (geometricMean / arithmeticMean).coerceIn(0.0, 1.0)
    }

    private fun computeStereoDifference(left: FloatArray, right: FloatArray): Double {
        val size = minOf(left.size, right.size)
        if (size == 0) return 0.0
        var diff = 0.0
        for (index in 0 until size) {
            diff += abs(left[index] - right[index])
        }
        return (diff / size).coerceIn(0.0, 1.0)
    }

    private fun normalizedBand(value: Float, min: Float, max: Float): Double {
        if (value <= min || value >= max) return 0.0
        val center = (min + max) * 0.5f
        val halfWidth = (max - min) * 0.5f
        return (1.0 - abs(value - center) / halfWidth).coerceIn(0.0, 1.0).toDouble()
    }

    private fun safeRatio(numerator: Double, denominator: Double): Double {
        return (numerator / denominator).coerceAtLeast(0.0)
    }

    private fun sigmoid(value: Double): Double {
        return 1.0 / (1.0 + exp(-value))
    }
}