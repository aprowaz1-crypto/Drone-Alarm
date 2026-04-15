package com.aegisf6.app.engine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Розраховує дальність до об'єкту на основі потужності звуку (dB).
 * Базується на закономірності: звукова потужність спадає з дальністю за законом оберненого квадрату.
 */
object AcousticRanging {
    // Еталонна потужність дрона на дистанції 1 км (калібрована емпірично)
    private const val REFERENCE_POWER_1KM_DB = -70.0
    
    // Коефіцієнт згасання (обраний для Київської міської акустики)
    private const val ATTENUATION_PER_KM = 20.0

    fun estimateDistance(rmsDb: Double, backgroundDb: Double): Double {
        // Сигнал вище фону
        val signalAboveNoise = rmsDb - backgroundDb
        if (signalAboveNoise < 5.0) {
            return 0.0  // Нема детекції
        }

        // Дальність логарифмічна: 20*log10(r) = P_1km - P_r
        val distanceKm = 1.0 * 10.0.pow((REFERENCE_POWER_1KM_DB - rmsDb) / ATTENUATION_PER_KM)
        
        return distanceKm.coerceIn(0.1, 15.0)
    }

    private fun Double.pow(exponent: Double): Double {
        return Math.pow(this, exponent)
    }
}

/**
 * Визначає азимут та висоту за стерео затримкою.
 * Використовує міжканальну затримку часу (ITD) і міжканальну інтенсивність (IID).
 */
object StereoLocalization {
    // Розміри людської голови (в см, впливає на ITD)
    private const val HEAD_DIAMETER_CM = 20.0

    /**
     * Розраховує азимут (0-360°) за міжканальною затримкою.
     * ITD = міжканальна затримка часу в мікросекундах
     */
    fun estimateAzimuth(
        leftChannel: FloatArray,
        rightChannel: FloatArray,
        sampleRateHz: Int
    ): Int {
        val itdMicros = computeInterChannelDelay(leftChannel, rightChannel, sampleRateHz)
        
        // Максимальна можлива ITD для людської голови
        val maxItdMicros = (HEAD_DIAMETER_CM / 100.0) / 343.0 * 1_000_000  // 343 m/s - швидкість звуку
        
        // Азимут з ITD: sin(θ) = ITD / maxITD
        val normalized = (itdMicros / maxItdMicros).coerceIn(-1.0, 1.0)
        val azimuthRad = kotlin.math.asin(normalized)
        val azimuthDeg = Math.toDegrees(azimuthRad)
        
        // Конвертувати в діапазон 0-360
        return ((azimuthDeg + 90) % 360).toInt().coerceIn(0, 359)
    }

    /**
     * Розраховує висоту (підвищення) на основі міжканальної інтенсивності та спектру.
     */
    fun estimateElevation(
        leftChannel: FloatArray,
        rightChannel: FloatArray,
        peakFrequencyHz: Float
    ): Int {
        val leftPower = computeRMS(leftChannel)
        val rightPower = computeRMS(rightChannel)
        
        // IID (міжканальна різниця інтенсивності)
        val iidDb = if (leftPower > 0 && rightPower > 0) {
            20.0 * kotlin.math.log10(leftPower / rightPower)
        } else {
            0.0
        }

        // Частота також впливає на підвищення (дрони мають суттєву частоту 80-120 Hz)
        val freqInfluence = if (peakFrequencyHz in 70f..150f) {
            (peakFrequencyHz - 70f) / 80f * 15.0  // До 15° висоти
        } else {
            0.0
        }

        // Висота з IID: рухаються вверх мають інший спектр
        val elevationDeg = iidDb.coerceIn(-30.0, 30.0) / 2.0 + freqInfluence
        
        return elevationDeg.toInt().coerceIn(-45, 90)  // Реалістичні межи
    }

    private fun computeInterChannelDelay(
        left: FloatArray,
        right: FloatArray,
        sampleRateHz: Int
    ): Double {
        // Кросс-кореляція для знаходження затримки
        val maxLag = (sampleRateHz * 0.02).toInt()  // Макс 20ms затримка
        var maxCorr = 0.0
        var bestLag = 0

        for (lag in -maxLag..maxLag) {
            var correlation = 0.0
            var count = 0
            for (i in 0 until (left.size - abs(lag))) {
                val leftIdx = i
                val rightIdx = i + lag
                if (rightIdx >= 0 && rightIdx < right.size) {
                    correlation += left[leftIdx] * right[rightIdx]
                    count++
                }
            }
            if (count > 0) {
                correlation /= count
                if (abs(correlation) > abs(maxCorr)) {
                    maxCorr = correlation
                    bestLag = lag
                }
            }
        }

        return (bestLag * 1_000_000.0) / sampleRateHz
    }

    private fun computeRMS(samples: FloatArray): Double {
        var sum = 0.0
        for (sample in samples) {
            sum += sample * sample
        }
        return sqrt(sum / samples.size)
    }
}

/**
 * Класифікує об'єкт (дрон чи ракета) на основі акустичного профілю.
 */
object TargetClassifier {
    // Характеристичні частоти
    private const val DRONE_FREQ_MIN = 75f
    private const val DRONE_FREQ_MAX = 150f
    private const val DRONE_MAX_DISTANCE_KM = 5.0
    
    private const val ROCKET_FREQ_MIN = 150f
    private const val ROCKET_FREQ_MAX = 500f
    private const val ROCKET_MAX_DISTANCE_KM = 10.0

    fun classify(
        peakFrequencyHz: Float,
        distanceKm: Double,
        rmsDb: Double,
        backgroundDb: Double
    ): Pair<String, Int> {  // objectType, confidence
        val signalStrength = rmsDb - backgroundDb

        return when {
            // Шахед-подібні дрони: частота 75-150 Hz, до 5км
            peakFrequencyHz in DRONE_FREQ_MIN..DRONE_FREQ_MAX && 
            signalStrength > 8 && 
            distanceKm <= DRONE_MAX_DISTANCE_KM -> {
                "Шахед-подібний акустичний профіль" to 90
            }
            // Ракети: частота 150-500 Hz, до 10км
            peakFrequencyHz in ROCKET_FREQ_MIN..ROCKET_FREQ_MAX && 
            distanceKm in 3.0..ROCKET_MAX_DISTANCE_KM -> {
                "Ракета/потужний звуковий профіль" to 85
            }
            // Невизначений об'єкт далеко (> 5км)
            signalStrength > 12 && distanceKm > DRONE_MAX_DISTANCE_KM && distanceKm <= ROCKET_MAX_DISTANCE_KM -> {
                "Невизначений повітряний об'єкт" to 60
            }
            else -> {
                "Шум / непідтверджена подія" to 30
            }
        }
    }
}
