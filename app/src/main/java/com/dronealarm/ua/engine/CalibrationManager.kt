package com.dronealarm.ua.engine

import android.content.SharedPreferences
import kotlin.math.max

class CalibrationManager(private val prefs: SharedPreferences) {
    data class Baseline(val mean: Double, val variance: Double)

    fun save(channel: String, mean: Double, variance: Double) {
        prefs.edit()
            .putFloat("$channel.mean", mean.toFloat())
            .putFloat("$channel.var", variance.toFloat())
            .apply()
    }

    fun load(channel: String): Baseline {
        val mean = prefs.getFloat("$channel.mean", 0f).toDouble()
        val variance = max(prefs.getFloat("$channel.var", 1f).toDouble(), 1e-6)
        return Baseline(mean, variance)
    }
}
