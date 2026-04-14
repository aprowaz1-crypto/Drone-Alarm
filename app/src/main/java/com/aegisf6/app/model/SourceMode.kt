package com.aegisf6.app.model

enum class ForcedSourceMode {
    AUTO,
    PHONE_ONLY,
    ARRAY_ONLY
}

enum class ActiveSourceMode {
    PHONE_SOLO,
    MULTI_ARRAY
}

enum class MapStyle {
    OSM_STANDARD,
    OSM_TOPO
}

data class DetectionSnapshot(
    val confidence: Int,
    val objectType: String,
    val distanceKm: Double,
    val speedKmh: Int,
    val azimuthDeg: Int,
    val altitudeM: Int,
    val etaSec: Int,
    val uncertaintyM: Int
)

data class AegisUiState(
    val monitorActive: Boolean,
    val forcedMode: ForcedSourceMode,
    val activeMode: ActiveSourceMode,
    val mapStyle: MapStyle,
    val btMicCount: Int,
    val userLat: Double,
    val userLon: Double,
    val targetLat: Double,
    val targetLon: Double,
    val trajectory: List<Pair<Double, Double>>,
    val telemetry: DetectionSnapshot
)
