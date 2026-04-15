package com.aegisf6.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aegisf6.app.audio.AudioFrame
import com.aegisf6.app.audio.AudioProcessor
import com.aegisf6.app.device.BluetoothProbe
import com.aegisf6.app.device.LocationProvider
import com.aegisf6.app.engine.AcousticRanging
import com.aegisf6.app.engine.StereoLocalization
import com.aegisf6.app.engine.TargetClassifier
import com.aegisf6.app.engine.ThreatAiAnalyzer
import com.aegisf6.app.engine.TrajectoryMath
import com.aegisf6.app.engine.UrbanNoiseFilter
import com.aegisf6.app.model.ActiveSourceMode
import com.aegisf6.app.model.AegisUiState
import com.aegisf6.app.model.ConfidenceThresholds
import com.aegisf6.app.model.DetectionSnapshot
import com.aegisf6.app.model.MapStyle
import com.aegisf6.app.model.TargetKind
import com.aegisf6.app.util.DiagnosticsLog
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

class AegisViewModel(
    private val bluetoothProbe: BluetoothProbe,
    private val audioProcessor: AudioProcessor,
    private val locationProvider: LocationProvider
) : ViewModel() {

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<AegisUiState> = _state.asStateFlow()

    private var simDistanceKm = 8.8
    private var simBearing = 145.0
    private val recentRawConfidences = ArrayDeque<Int>()
    private val calibrationSamples = mutableListOf<Int>()
    private var calibrationTicksLeft = 0
    private val azimuthHistory = ArrayDeque<Int>()
    private val altitudeHistory = ArrayDeque<Int>()
    private val frequencyHistory = ArrayDeque<Float>()
    private val rmsHistory = ArrayDeque<Double>()
    private var lastRejectReasonLogged = ""
    private var lastHouseholdNoiseLogAtMs = 0L
    private var headsetPresetLogged = false
    private var aiAnalyzerLogged = false

    init {
        DiagnosticsLog.toFix("Real-time microphone DSP detection enabled; Bluetooth headset smoothing active")
        viewModelScope.launch {
            locationProvider.location.collect { location ->
                if (location != null) {
                    val current = _state.value
                    _state.value = current.copy(
                        userLat = location.latitude,
                        userLon = location.longitude
                    )
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                tick()
                delay(250)
            }
        }
    }

    override fun onCleared() {
        audioProcessor.stop()
        locationProvider.stop()
        super.onCleared()
    }

    fun toggleMicrophone() {
        val current = _state.value
        val micEnabled = !current.microphoneEnabled

        if (micEnabled) {
            audioProcessor.start()
            locationProvider.start()
        } else {
            audioProcessor.stop()
            locationProvider.stop()
        }

        DiagnosticsLog.toFix("Microphone toggled: newState=$micEnabled")
        _state.value = current.copy(
            microphoneEnabled = micEnabled,
            monitorActive = micEnabled
        )
    }

    fun toggleJblStrictMode() {
        val current = _state.value
        val enabled = !current.jblStrictMode
        _state.value = current.copy(jblStrictMode = enabled)
        DiagnosticsLog.toFix("JBL strict mode toggled: enabled=$enabled")
    }

    fun toggleMapStyle() {
        val next = if (_state.value.mapStyle == MapStyle.OSM_STANDARD) {
            MapStyle.OSM_TOPO
        } else {
            MapStyle.OSM_STANDARD
        }
        _state.value = _state.value.copy(mapStyle = next)
    }

    fun startCalibration(): Boolean {
        val state = _state.value
        if (!state.microphoneEnabled) {
            DiagnosticsLog.notOkOnce(
                key = "calibration_without_mic",
                message = "Calibration blocked: microphone is disabled"
            )
            return false
        }
        if (state.btMicCount <= 0) {
            DiagnosticsLog.notOkOnce(
                key = "calibration_without_headset",
                message = "Calibration blocked: no Bluetooth headset connected"
            )
            DiagnosticsLog.notAddedOnce(
                key = "wired_headset_detection",
                message = "Wired headset detection flow is not implemented yet"
            )
            return false
        }

        calibrationSamples.clear()
        calibrationTicksLeft = 24
        val current = _state.value
        _state.value = current.copy(
            calibrating = true,
            calibrationSecondsLeft = 60
        )
        return true
    }

    private suspend fun tick() {
        val current = _state.value
        if (!current.microphoneEnabled) {
            if (current.monitorActive) {
                DiagnosticsLog.bugOnce(
                    key = "monitor_enabled_without_mic",
                    message = "monitorActive=true while microphoneEnabled=false; forcing monitorActive to false"
                )
                _state.value = current.copy(monitorActive = false)
            }
            return
        }

        val btCount = bluetoothProbe.connectedAudioMicDevices()
        val active = if (btCount > 0) ActiveSourceMode.MULTI_ARRAY else ActiveSourceMode.PHONE_SOLO

        if (btCount > 0 && !headsetPresetLogged) {
            DiagnosticsLog.toFix("Headset anti-noise preset active (Bluetooth): stronger household noise suppression")
            headsetPresetLogged = true
        } else if (btCount == 0) {
            headsetPresetLogged = false
        }

        if (!aiAnalyzerLogged) {
            DiagnosticsLog.toFix("On-device threat AI analyzer active for Shahed/missile frequency profiling")
            DiagnosticsLog.notAddedOnce(
                key = "qwen_not_integrated",
                message = "Qwen is not integrated; compact on-device audio AI is used instead"
            )
            aiAnalyzerLogged = true
        }

        try {
            val frame = audioProcessor.captureFrame()
            if (frame != null) {
                processAudioFrame(frame, current, active, btCount)
            } else {
                processSimulatedFrame(current, active, btCount)
            }
        } catch (e: Exception) {
            DiagnosticsLog.bugOnce(
                key = "audio_capture_error",
                message = "Error capturing audio: ${e.message}"
            )
            processSimulatedFrame(current, active, btCount)
        }
    }

    private data class CalibrationProgress(
        val baseline: Int,
        val calibrating: Boolean,
        val secondsLeft: Int
    )

    private fun updateCalibration(rawConfidence: Int, current: AegisUiState): CalibrationProgress {
        var baseline = current.backgroundBaseline
        var calibrating = current.calibrating
        var secLeft = current.calibrationSecondsLeft

        if (calibrating) {
            calibrationSamples += rawConfidence
            calibrationTicksLeft = max(0, calibrationTicksLeft - 1)
            secLeft = calibrationTicksLeft * 2 + if (calibrationTicksLeft > 0) 1 else 0
            if (calibrationTicksLeft == 0) {
                baseline = if (calibrationSamples.isEmpty()) baseline else calibrationSamples.average().toInt()
                calibrating = false
            }
        }

        return CalibrationProgress(
            baseline = baseline,
            calibrating = calibrating,
            secondsLeft = secLeft
        )
    }

    private fun processSimulatedFrame(
        current: AegisUiState,
        active: ActiveSourceMode,
        btCount: Int
    ) {
        simDistanceKm = (simDistanceKm - 0.06).coerceAtLeast(1.0)
        simBearing = (simBearing + 1.6) % 360
        if (simDistanceKm <= 1.05) {
            simDistanceKm = 9.6
            simBearing = (simBearing + 47.0) % 360
        }

        val simulatedFrequency = if (simDistanceKm <= 5.0) 112f else 280f
        val rawConfidence = if (active == ActiveSourceMode.MULTI_ARRAY) 92 else 63

        recentRawConfidences.addLast(rawConfidence)
        while (recentRawConfidences.size > 8) recentRawConfidences.removeFirst()

        val calibration = updateCalibration(rawConfidence, current)

        val filter = UrbanNoiseFilter.apply(
            rawConfidence = rawConfidence,
            backgroundBaseline = calibration.baseline,
            distanceKm = simDistanceKm,
            activeMode = active,
            lastConfidences = recentRawConfidences.toList()
        )

        val (classifiedType, classifierConfidence) = TargetClassifier.classify(
            peakFrequencyHz = simulatedFrequency,
            distanceKm = simDistanceKm,
            rmsDb = -62.0,
            backgroundDb = (calibration.baseline - 70).toDouble()
        )

        val targetKind = resolveTargetKind(classifiedType)
        val distanceForUi = simDistanceKm.coerceAtMost(targetKind.maxDistanceKm)

        val threshold = resolveThreshold(active, btCount, current.thresholds, current.jblStrictMode)
        val finalConfidence = blendConfidence(filter.filteredConfidence, classifierConfidence, btCount)
        val inRange = simDistanceKm <= targetKind.maxDistanceKm
        val accepted = !calibration.calibrating && finalConfidence >= threshold && inRange
        val reason = when {
            calibration.calibrating -> "Калібрування фону активне"
            !inRange -> "Поза дальністю ${targetKind.maxDistanceKm} км для цього типу"
            !accepted && filter.reason.isNotEmpty() -> "${filter.reason}; нижче порогу ${threshold}%"
            !accepted -> "Нижче порогу ${threshold}%"
            else -> ""
        }

        val azimuthDeg = smoothAzimuth((simBearing % 360).toInt(), btCount)
        val baseAltitude = if (targetKind == TargetKind.MISSILE) 900 else 260
        val altitudeM = smoothAltitude(baseAltitude + if (active == ActiveSourceMode.MULTI_ARRAY) 40 else 0, btCount)
        val uncertaintyM = resolveUncertaintyM(active, btCount, targetKind)

        val (targetLat, targetLon) = TrajectoryMath.project(
            lat = current.userLat,
            lon = current.userLon,
            distanceKm = distanceForUi,
            bearingDeg = azimuthDeg.toDouble()
        )

        val telemetry = DetectionSnapshot(
            rawConfidence = rawConfidence,
            confidence = finalConfidence,
            objectType = classifiedType,
            targetKind = targetKind,
            distanceKm = distanceForUi,
            speedKmh = 0,
            azimuthDeg = azimuthDeg,
            altitudeM = altitudeM,
            etaSec = 0,
            uncertaintyM = uncertaintyM,
            accepted = accepted,
            rejectReason = reason
        )

        _state.value = current.copy(
            activeMode = active,
            btMicCount = btCount,
            targetLat = targetLat,
            targetLon = targetLon,
            trajectory = listOf(
                Pair(current.userLat, current.userLon),
                Pair(targetLat, targetLon)
            ),
            telemetry = telemetry,
            calibrating = calibration.calibrating,
            calibrationSecondsLeft = calibration.secondsLeft,
            backgroundBaseline = calibration.baseline
        )
    }

    private fun processAudioFrame(
        frame: AudioFrame,
        current: AegisUiState,
        active: ActiveSourceMode,
        btCount: Int
    ) {
        val rawConfidence = (frame.rmsDb + 100).toInt().coerceIn(0, 100)

        val calibration = updateCalibration(rawConfidence, current)
        val rawDistanceKm = AcousticRanging.estimateDistance(
            rmsDb = frame.rmsDb,
            backgroundDb = calibration.baseline.toDouble() - 70.0
        )
        val rawAzimuthDeg = StereoLocalization.estimateAzimuth(frame.leftChannel, frame.rightChannel, 44100)
        val smoothedAzimuthDeg = smoothAzimuth(rawAzimuthDeg, btCount)
        val elevationDeg = StereoLocalization.estimateElevation(
            frame.leftChannel,
            frame.rightChannel,
            frame.peakFrequencyHz
        )
        val preStabilizedAltitude = stabilizeAltitude(
            rawAltitudeM = (elevationDeg * 30 + 250).coerceAtLeast(40),
            previousAltitudeM = current.telemetry.altitudeM,
            btCount = btCount
        )
        val smoothedAltitudeM = smoothAltitude(preStabilizedAltitude, btCount)

        val (objectType, classifierConfidence) = TargetClassifier.classify(
            frame.peakFrequencyHz,
            rawDistanceKm,
            frame.rmsDb,
            calibration.baseline.toDouble() - 70.0
        )
        val aiResult = ThreatAiAnalyzer.analyze(
            frame = frame,
            backgroundDb = calibration.baseline.toDouble() - 70.0,
            btCount = btCount,
            strictMode = current.jblStrictMode
        )

        val objectTypeResolved = resolveObjectTypeFromAi(aiResult, objectType)

        val likelyHouseholdNoise = isLikelyHouseholdNoise(frame, rawDistanceKm, btCount) || aiResult.noiseScore >= 72
        if (likelyHouseholdNoise) {
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastHouseholdNoiseLogAtMs > 8_000L) {
                DiagnosticsLog.notOk("Household noise suppressed (possible PS5/fan), freq=${frame.peakFrequencyHz.toInt()}Hz")
                lastHouseholdNoiseLogAtMs = nowMs
            }
        }

        val targetKind = if (aiResult.suggestedKind != TargetKind.UNKNOWN) {
            aiResult.suggestedKind
        } else {
            resolveTargetKind(objectTypeResolved)
        }
        val distanceKm = rawDistanceKm.coerceAtMost(targetKind.maxDistanceKm)
        if (targetKind == TargetKind.SHAHED && rawDistanceKm > 5.0) {
            DiagnosticsLog.notOkOnce(
                key = "shahed_outside_5km_window",
                message = "Shahed-like profile detected outside 5km precision range"
            )
        }

        recentRawConfidences.addLast(rawConfidence)
        while (recentRawConfidences.size > 8) recentRawConfidences.removeFirst()

        val filter = UrbanNoiseFilter.apply(
            rawConfidence = rawConfidence,
            backgroundBaseline = calibration.baseline,
            distanceKm = distanceKm,
            activeMode = active,
            lastConfidences = recentRawConfidences.toList()
        )

        val threshold = resolveThreshold(active, btCount, current.thresholds, current.jblStrictMode)
        val adaptiveThresholdBoost = adaptiveThresholdBoost(frame.rmsDb, current.jblStrictMode)
        val effectiveThreshold = min(99, threshold + adaptiveThresholdBoost)
        val finalConfidence = blendConfidence(
            filterConfidence = filter.filteredConfidence,
            classifierConfidence = max(classifierConfidence, aiThreatConfidence(aiResult)),
            btCount = btCount,
            likelyHouseholdNoise = likelyHouseholdNoise
        )
        val inRange = rawDistanceKm <= targetKind.maxDistanceKm
        val accepted = !calibration.calibrating &&
            finalConfidence >= effectiveThreshold &&
            inRange &&
            !likelyHouseholdNoise
        val rejectReason = when {
            calibration.calibrating -> "Калібрування фону активне"
            likelyHouseholdNoise -> "Ймовірний побутовий шум (PS5/вентилятор)"
            !inRange -> "Поза дальністю ${targetKind.maxDistanceKm} км для цього типу"
            accepted -> ""
            else -> "${filter.reason}; нижче порогу ${effectiveThreshold}%"
        }

        if (adaptiveThresholdBoost > 0) {
            DiagnosticsLog.toFixOnce(
                key = "adaptive_sensitivity_active",
                message = "Adaptive sensitivity active (night/quiet), threshold boost=$adaptiveThresholdBoost"
            )
        }

        if (rejectReason.isNotBlank() && rejectReason != lastRejectReasonLogged) {
            DiagnosticsLog.notOk("Detection rejected: $rejectReason")
            lastRejectReasonLogged = rejectReason
        }

        if (aiResult.distanceReliability < 42 && finalConfidence > 0) {
            DiagnosticsLog.notOk("AI distance reliability low: ${aiResult.distanceReliability}%")
        }

        val (targetLat, targetLon) = TrajectoryMath.project(
            lat = current.userLat,
            lon = current.userLon,
            distanceKm = distanceKm,
            bearingDeg = smoothedAzimuthDeg.toDouble()
        )

        val telemetry = DetectionSnapshot(
            rawConfidence = rawConfidence,
            confidence = finalConfidence,
            objectType = objectTypeResolved,
            targetKind = targetKind,
            distanceKm = distanceKm,
            speedKmh = 0,
            azimuthDeg = smoothedAzimuthDeg,
            altitudeM = if (likelyHouseholdNoise) current.telemetry.altitudeM else smoothedAltitudeM,
            etaSec = 0,
            uncertaintyM = resolveUncertaintyM(active, btCount, targetKind),
            accepted = accepted,
            rejectReason = rejectReason
        )

        _state.value = current.copy(
            activeMode = active,
            btMicCount = btCount,
            targetLat = targetLat,
            targetLon = targetLon,
            trajectory = if (accepted) {
                listOf(Pair(current.userLat, current.userLon), Pair(targetLat, targetLon))
            } else {
                current.trajectory
            },
            telemetry = telemetry,
            calibrating = calibration.calibrating,
            calibrationSecondsLeft = calibration.secondsLeft,
            backgroundBaseline = calibration.baseline
        )
    }

    private fun resolveTargetKind(objectType: String): TargetKind {
        val normalized = objectType.lowercase()
        return when {
            normalized.contains("ракет") -> TargetKind.MISSILE
            normalized.contains("шахед") || normalized.contains("shahed") -> TargetKind.SHAHED
            else -> TargetKind.UNKNOWN
        }
    }

    private fun resolveObjectTypeFromAi(aiResult: com.aegisf6.app.engine.ThreatAiResult, fallback: String): String {
        return when (aiResult.suggestedKind) {
            TargetKind.SHAHED -> aiResult.suggestedLabel
            TargetKind.MISSILE -> aiResult.suggestedLabel
            TargetKind.UNKNOWN -> fallback
        }
    }

    private fun aiThreatConfidence(aiResult: com.aegisf6.app.engine.ThreatAiResult): Int {
        return max(aiResult.shahedScore, aiResult.missileScore)
    }

    private fun blendConfidence(filterConfidence: Int, classifierConfidence: Int, btCount: Int): Int {
        val combined = (filterConfidence * 0.65 + classifierConfidence * 0.35).toInt()
        val headsetBoost = if (btCount > 0) 4 else 0
        return (combined + headsetBoost).coerceIn(0, 100)
    }

    private fun blendConfidence(
        filterConfidence: Int,
        classifierConfidence: Int,
        btCount: Int,
        likelyHouseholdNoise: Boolean
    ): Int {
        val base = blendConfidence(filterConfidence, classifierConfidence, btCount)
        if (!likelyHouseholdNoise) return base
        return (base - 34).coerceIn(0, 100)
    }

    private fun smoothAzimuth(rawAzimuth: Int, btCount: Int): Int {
        val window = if (btCount > 0) 10 else 6
        azimuthHistory.addLast(rawAzimuth)
        while (azimuthHistory.size > window) azimuthHistory.removeFirst()

        var sumSin = 0.0
        var sumCos = 0.0
        for (value in azimuthHistory) {
            val rad = Math.toRadians(value.toDouble())
            sumSin += sin(rad)
            sumCos += cos(rad)
        }

        val avgRad = atan2(sumSin / azimuthHistory.size, sumCos / azimuthHistory.size)
        val normalized = ((Math.toDegrees(avgRad) + 360.0) % 360.0)
        return normalized.roundToInt() % 360
    }

    private fun smoothAltitude(rawAltitudeM: Int, btCount: Int): Int {
        val window = if (btCount > 0) 8 else 5
        altitudeHistory.addLast(rawAltitudeM)
        while (altitudeHistory.size > window) altitudeHistory.removeFirst()
        return altitudeHistory.average().roundToInt().coerceAtLeast(0)
    }

    private fun stabilizeAltitude(rawAltitudeM: Int, previousAltitudeM: Int, btCount: Int): Int {
        val clampedRaw = rawAltitudeM.coerceIn(40, 3000)
        val maxStep = if (btCount > 0) 30 else 70
        val delta = (clampedRaw - previousAltitudeM).coerceIn(-maxStep, maxStep)
        return (previousAltitudeM + delta).coerceAtLeast(0)
    }

    private fun isLikelyHouseholdNoise(frame: AudioFrame, distanceKm: Double, btCount: Int): Boolean {
        frequencyHistory.addLast(frame.peakFrequencyHz)
        while (frequencyHistory.size > 14) frequencyHistory.removeFirst()
        rmsHistory.addLast(frame.rmsDb)
        while (rmsHistory.size > 14) rmsHistory.removeFirst()
        if (frequencyHistory.size < 8) return false

        val meanFreq = frequencyHistory.average().toFloat()
        val variance = frequencyHistory
            .map { (it - meanFreq) * (it - meanFreq) }
            .average()
        val stdDev = sqrt(variance.toDouble()).toFloat()

        val rmsVariance = if (rmsHistory.isNotEmpty()) {
            val meanRms = rmsHistory.average()
            rmsHistory.map { (it - meanRms) * (it - meanRms) }.average()
        } else {
            0.0
        }
        val rmsStdDev = sqrt(rmsVariance)

        val householdBand = meanFreq in 92f..220f || meanFreq in 47f..63f
        val stableTone = stdDev < if (btCount > 0) 6.8f else 9.5f
        val stableLoudness = rmsStdDev < if (btCount > 0) 1.6 else 2.8
        val nearField = distanceKm < 2.3
        val notTooLoud = frame.rmsDb < if (btCount > 0) -16.0 else -18.0
        val mainsHum = meanFreq in 48f..52f || meanFreq in 98f..102f || meanFreq in 148f..152f
        val consoleFanBand = meanFreq in 108f..138f
        val likelyJblHeadsetLocalNoise = btCount > 0 && (mainsHum || consoleFanBand) && stableTone && stableLoudness

        return (householdBand && stableTone && stableLoudness && nearField && notTooLoud) || likelyJblHeadsetLocalNoise
    }

    private fun resolveUncertaintyM(
        active: ActiveSourceMode,
        btCount: Int,
        targetKind: TargetKind
    ): Int {
        val base = if (active == ActiveSourceMode.MULTI_ARRAY) 85 else 140
        val headsetDelta = if (btCount > 0) -25 else 0
        val targetDelta = if (targetKind == TargetKind.MISSILE) 20 else 0
        return (base + headsetDelta + targetDelta).coerceIn(40, 220)
    }

    private fun resolveThreshold(
        activeMode: ActiveSourceMode,
        btMicCount: Int,
        thresholds: ConfidenceThresholds,
        strictMode: Boolean
    ): Int {
        val baseThreshold = when (activeMode) {
            ActiveSourceMode.PHONE_SOLO -> thresholds.phoneSolo
            ActiveSourceMode.MULTI_ARRAY -> {
                if (btMicCount >= 4) thresholds.btArray4plus else thresholds.btArray2plus
            }
        }
        val strictDelta = if (strictMode) 6 else 0
        return (baseThreshold + strictDelta).coerceIn(0, 100)
    }

    private fun adaptiveThresholdBoost(rmsDb: Double, strictMode: Boolean): Int {
        val night = isNightHours()
        val quiet = rmsDb < -38.0
        return when {
            strictMode && night && quiet -> 8
            strictMode && (night || quiet) -> 5
            !strictMode && night && quiet -> 4
            !strictMode && (night || quiet) -> 2
            else -> 0
        }
    }

    private fun isNightHours(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 22 || hour < 6
    }

    private fun initialState(): AegisUiState {
        // Координати Троєщини, Ніколаєва 17, Київ, Україна (fallback без GPS)
        val baseLat = 50.5252
        val baseLon = 30.5678
        return AegisUiState(
            monitorActive = false,
            microphoneEnabled = false,
            jblStrictMode = false,
            forcedMode = com.aegisf6.app.model.ForcedSourceMode.ARRAY_ONLY,
            activeMode = ActiveSourceMode.PHONE_SOLO,
            mapStyle = MapStyle.OSM_STANDARD,
            btMicCount = 0,
            userLat = baseLat,
            userLon = baseLon,
            targetLat = baseLat,
            targetLon = baseLon,
            trajectory = listOf(Pair(baseLat, baseLon), Pair(baseLat, baseLon)),
            telemetry = DetectionSnapshot(
                rawConfidence = 0,
                confidence = 0,
                objectType = "Очікування",
                targetKind = TargetKind.UNKNOWN,
                distanceKm = 0.0,
                speedKmh = 0,
                azimuthDeg = 0,
                altitudeM = 0,
                etaSec = 0,
                uncertaintyM = 0,
                accepted = false,
                rejectReason = ""
            ),
            thresholds = ConfidenceThresholds(
                phoneSolo = 88,
                btArray2plus = 78,
                btArray4plus = 70
            ),
            calibrating = false,
            calibrationSecondsLeft = 0,
            backgroundBaseline = 40
        )
    }
}
