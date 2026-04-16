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

enum class TargetKind(val maxDistanceKm: Double) {
    SHAHED(5.0),
    MISSILE(10.0),
    UNKNOWN(5.0)
}

data class DetectionSnapshot(
    val rawConfidence: Int,
    val confidence: Int,
    val objectType: String,
    val targetKind: TargetKind,
    val distanceKm: Double,
    val speedKmh: Int,
    val azimuthDeg: Int,
    val altitudeM: Int,
    val etaSec: Int,
    val uncertaintyM: Int,
    val accepted: Boolean,
    val rejectReason: String
)

data class ConfidenceThresholds(
    val phoneSolo: Int,
    val btArray2plus: Int,
    val btArray4plus: Int
)

data class AegisUiState(
    val monitorActive: Boolean,
    val microphoneEnabled: Boolean,
    val precisionMode: Boolean,
    val forcedMode: ForcedSourceMode,
    val activeMode: ActiveSourceMode,
    val mapStyle: MapStyle,
    val btMicCount: Int,
    val userLat: Double,
    val userLon: Double,
    val locationAccuracyM: Int,
    val locationTimestamp: Long,
    val locationSourceLabel: String,
    val targetLat: Double,
    val targetLon: Double,
    val trajectory: List<Pair<Double, Double>>,
    val telemetry: DetectionSnapshot,
    val thresholds: ConfidenceThresholds,
    val calibrating: Boolean,
    val calibrationSecondsLeft: Int,
    val backgroundBaseline: Int
)
