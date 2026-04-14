package com.aegisf6.app.engine

import kotlin.math.cos
import kotlin.math.sin

object TrajectoryMath {
    fun project(lat: Double, lon: Double, distanceKm: Double, bearingDeg: Double): Pair<Double, Double> {
        val latStep = (distanceKm / 111.0) * cos(Math.toRadians(bearingDeg))
        val lonStep = (distanceKm / (111.0 * cos(Math.toRadians(lat)))) * sin(Math.toRadians(bearingDeg))
        return Pair(lat + latStep, lon + lonStep)
    }
}
