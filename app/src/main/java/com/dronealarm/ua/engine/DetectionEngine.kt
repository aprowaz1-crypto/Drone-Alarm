package com.dronealarm.ua.engine

import android.location.Location
import com.dronealarm.ua.network.MqttPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

class DetectionEngine(
    private val calibrationManager: CalibrationManager,
    private val mqttPublisher: MqttPublisher,
    private val scope: CoroutineScope,
    private val isMqttEnabled: () -> Boolean,
    private val locationProvider: () -> Location?
) {
    data class Input(val timestampNanos: Long, val rssiVar: Double, val audioPower: Double, val vibRms: Double)
    data class Output(
        val confidence: Double,
        val activeChannels: Int,
        val isAlert: Boolean,
        val capXml: String?,
        val distanceLabel: String
    )

    private var calibrationStartNanos: Long? = null
    private var calibrationComplete = false
    private val runningStats = mapOf(
        "rf" to RunningStats(),
        "audio" to RunningStats(),
        "vib" to RunningStats()
    )

    fun resetCalibration() {
        calibrationStartNanos = null
        calibrationComplete = false
        runningStats.values.forEach { it.clear() }
    }

    fun consume(input: Input): Output {
        val start = calibrationStartNanos ?: input.timestampNanos.also { calibrationStartNanos = it }
        val elapsedSec = (input.timestampNanos - start) / 1_000_000_000.0

        if (!calibrationComplete && elapsedSec <= 120.0) {
            runningStats.getValue("rf").add(input.rssiVar)
            runningStats.getValue("audio").add(input.audioPower)
            runningStats.getValue("vib").add(input.vibRms)
            if (elapsedSec >= 120.0) {
                calibrationManager.save("rf", runningStats.getValue("rf").mean, runningStats.getValue("rf").variance)
                calibrationManager.save("audio", runningStats.getValue("audio").mean, runningStats.getValue("audio").variance)
                calibrationManager.save("vib", runningStats.getValue("vib").mean, runningStats.getValue("vib").variance)
                calibrationComplete = true
            }
            return Output(0.0, 0, false, null, "калібрування")
        }

        val rfNorm = normalize(input.rssiVar, calibrationManager.load("rf"))
        val audioNorm = normalize(input.audioPower, calibrationManager.load("audio"))
        val vibNorm = normalize(input.vibRms, calibrationManager.load("vib"))

        val channels = listOf(rfNorm, audioNorm, vibNorm)
        val activeChannels = channels.count { it > 0.5 }
        val confidence = rfNorm * 0.4 + audioNorm * 0.3 + vibNorm * 0.3
        val isAlert = confidence > 0.60 && activeChannels >= 2
        val distance = estimateDistance((rfNorm + audioNorm + vibNorm) / 3.0)

        val capXml = if (isAlert) {
            CapGenerator.build(confidence, distance, locationProvider())
        } else {
            null
        }

        if (capXml != null) {
            scope.launch {
                mqttPublisher.publish(capXml, isMqttEnabled())
            }
        }

        return Output(confidence, activeChannels, isAlert, capXml, distance)
    }

    private fun normalize(value: Double, baseline: CalibrationManager.Baseline): Double {
        val threshold = max(kotlin.math.sqrt(baseline.variance) * 3.0, 1e-3)
        return min(1.0, max(0.0, (value - baseline.mean) / threshold))
    }

    private fun estimateDistance(avgStrength: Double): String {
        return when {
            avgStrength > 0.8 -> "дуже близько (< 300 м)"
            avgStrength > 0.6 -> "близько (~300-700 м)"
            avgStrength > 0.35 -> "середньо (~700-1500 м)"
            else -> "далеко (> 1.5 км)"
        }
    }

    private class RunningStats {
        private var n = 0
        private var meanValue = 0.0
        private var m2 = 0.0

        val mean: Double get() = meanValue
        val variance: Double get() = if (n > 1) m2 / (n - 1) else 1.0

        fun add(x: Double) {
            n++
            val delta = x - meanValue
            meanValue += delta / n
            val delta2 = x - meanValue
            m2 += delta * delta2
        }

        fun clear() {
            n = 0
            meanValue = 0.0
            m2 = 0.0
        }
    }
}
