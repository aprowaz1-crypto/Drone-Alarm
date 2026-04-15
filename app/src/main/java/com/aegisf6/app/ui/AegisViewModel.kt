package com.aegisf6.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aegisf6.app.device.BluetoothProbe
import com.aegisf6.app.engine.SmartSourceSelector
import com.aegisf6.app.engine.TrajectoryMath
import com.aegisf6.app.engine.UrbanNoiseFilter
import com.aegisf6.app.model.ActiveSourceMode
import com.aegisf6.app.model.AegisUiState
import com.aegisf6.app.model.ConfidenceThresholds
import com.aegisf6.app.model.DetectionSnapshot
import com.aegisf6.app.model.ForcedSourceMode
import com.aegisf6.app.model.MapStyle
import com.aegisf6.app.util.DiagnosticsLog
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AegisViewModel(private val bluetoothProbe: BluetoothProbe) : ViewModel() {

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<AegisUiState> = _state.asStateFlow()

    private var simDistanceKm = 3.6
    private var simBearing = 145.0
    private val recentRawConfidences = ArrayDeque<Int>()
    private val calibrationSamples = mutableListOf<Int>()
    private var calibrationTicksLeft = 0

    init {
        DiagnosticsLog.missingOnce(
            key = "simulated_detection_engine",
            message = "Detection pipeline is still simulated; real microphone DSP/ML inference is not connected yet"
        )
        viewModelScope.launch {
            while (true) {
                tick()
                delay(2500)
            }
        }
    }

    fun toggleMicrophone() {
        val current = _state.value
        val micEnabled = !current.microphoneEnabled
        DiagnosticsLog.toFix("Microphone toggled: newState=$micEnabled")
        _state.value = current.copy(
            microphoneEnabled = micEnabled,
            monitorActive = micEnabled
        )
    }

    fun setForcedMode(mode: ForcedSourceMode) {
        val current = _state.value
        val active = SmartSourceSelector.resolve(mode, current.btMicCount)
        _state.value = current.copy(forcedMode = mode, activeMode = active)
    }

    fun toggleMapStyle() {
        val next = if (_state.value.mapStyle == MapStyle.OSM_STANDARD) {
            MapStyle.OSM_TOPO
        } else {
            MapStyle.OSM_STANDARD
        }
        _state.value = _state.value.copy(mapStyle = next)
    }

    fun startCalibration() {
        if (!_state.value.microphoneEnabled) {
            DiagnosticsLog.toFixOnce(
                key = "calibration_without_mic",
                message = "Calibration started while microphone is disabled; samples may not represent real audio input"
            )
        }
        calibrationSamples.clear()
        calibrationTicksLeft = 24
        val current = _state.value
        _state.value = current.copy(
            calibrating = true,
            calibrationSecondsLeft = 60
        )
    }

    private fun tick() {
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
        val active = SmartSourceSelector.resolve(current.forcedMode, btCount)

        simDistanceKm = (simDistanceKm - 0.08).coerceAtLeast(0.8)
        simBearing += 1.7

        val (targetLat, targetLon) = TrajectoryMath.project(
            lat = current.userLat,
            lon = current.userLon,
            distanceKm = simDistanceKm,
            bearingDeg = simBearing
        )

        val uncertainty = if (active == ActiveSourceMode.MULTI_ARRAY) 70 else 180
        val altitude = if (active == ActiveSourceMode.MULTI_ARRAY) 180 else 120
        val rawConfidence = if (active == ActiveSourceMode.MULTI_ARRAY) 92 else 63

        recentRawConfidences.addLast(rawConfidence)
        while (recentRawConfidences.size > 8) recentRawConfidences.removeFirst()

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

        val filter = UrbanNoiseFilter.apply(
            rawConfidence = rawConfidence,
            backgroundBaseline = baseline,
            distanceKm = simDistanceKm,
            activeMode = active,
            lastConfidences = recentRawConfidences.toList()
        )

        val threshold = resolveThreshold(active, btCount, current.thresholds)
        val accepted = !calibrating && filter.filteredConfidence >= threshold
        val reason = when {
            calibrating -> "Калібрування фону активне"
            !accepted && filter.reason.isNotEmpty() -> "${filter.reason}; нижче порогу ${threshold}%"
            !accepted -> "Нижче порогу ${threshold}%"
            else -> ""
        }

        val telemetry = DetectionSnapshot(
            rawConfidence = rawConfidence,
            confidence = filter.filteredConfidence,
            objectType = if (accepted) "Шахед-подібний акустичний профіль" else "Непідтверджена подія",
            distanceKm = simDistanceKm,
            speedKmh = 180,
            azimuthDeg = (simBearing % 360).toInt(),
            altitudeM = altitude,
            etaSec = ((simDistanceKm * 1000) / 50).toInt(),
            uncertaintyM = uncertainty,
            accepted = accepted,
            rejectReason = reason
        )

        if (!current.monitorActive && telemetry.accepted) {
            DiagnosticsLog.bugOnce(
                key = "accepted_telemetry_while_idle",
                message = "Accepted telemetry generated while monitor is inactive"
            )
        }

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
            calibrating = calibrating,
            calibrationSecondsLeft = secLeft,
            backgroundBaseline = baseline
        )
    }

    private fun resolveThreshold(
        activeMode: ActiveSourceMode,
        btMicCount: Int,
        thresholds: ConfidenceThresholds
    ): Int {
        return when (activeMode) {
            ActiveSourceMode.PHONE_SOLO -> thresholds.phoneSolo
            ActiveSourceMode.MULTI_ARRAY -> {
                if (btMicCount >= 4) thresholds.btArray4plus else thresholds.btArray2plus
            }
        }
    }

    private fun initialState(): AegisUiState {
        // Координати Троєщини, Ніколаєва 13, Київ, Україна
        val baseLat = 50.5249
        val baseLon = 30.5672
        return AegisUiState(
            monitorActive = false,
            microphoneEnabled = false,
            forcedMode = ForcedSourceMode.AUTO,
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
                phoneSolo = 85,
                btArray2plus = 75,
                btArray4plus = 65
            ),
            calibrating = false,
            calibrationSecondsLeft = 0,
            backgroundBaseline = 40
        )
    }
}
