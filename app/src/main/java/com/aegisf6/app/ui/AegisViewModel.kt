package com.aegisf6.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aegisf6.app.device.BluetoothProbe
import com.aegisf6.app.engine.SmartSourceSelector
import com.aegisf6.app.engine.TrajectoryMath
import com.aegisf6.app.model.ActiveSourceMode
import com.aegisf6.app.model.AegisUiState
import com.aegisf6.app.model.DetectionSnapshot
import com.aegisf6.app.model.ForcedSourceMode
import com.aegisf6.app.model.MapStyle
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

    init {
        viewModelScope.launch {
            while (true) {
                tick()
                delay(2500)
            }
        }
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

    private fun tick() {
        val current = _state.value
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

        val telemetry = DetectionSnapshot(
            confidence = if (active == ActiveSourceMode.MULTI_ARRAY) 92 else 63,
            objectType = "Шахед-подібний акустичний профіль",
            distanceKm = simDistanceKm,
            speedKmh = 180,
            azimuthDeg = (simBearing % 360).toInt(),
            altitudeM = altitude,
            etaSec = ((simDistanceKm * 1000) / 50).toInt(),
            uncertaintyM = uncertainty
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
            telemetry = telemetry
        )
    }

    private fun initialState(): AegisUiState {
        val baseLat = 50.4501
        val baseLon = 30.5234
        return AegisUiState(
            monitorActive = true,
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
                confidence = 0,
                objectType = "Очікування",
                distanceKm = 0.0,
                speedKmh = 0,
                azimuthDeg = 0,
                altitudeM = 0,
                etaSec = 0,
                uncertaintyM = 0
            )
        )
    }
}
