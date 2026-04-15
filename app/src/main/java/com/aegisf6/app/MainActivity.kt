package com.aegisf6.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aegisf6.app.audio.AudioProcessor
import com.aegisf6.app.databinding.ActivityMainBinding
import com.aegisf6.app.device.BluetoothProbe
import com.aegisf6.app.device.LocationProvider
import com.aegisf6.app.map.MapOverlayController
import com.aegisf6.app.model.MapStyle
import com.aegisf6.app.ui.AegisViewModel
import com.aegisf6.app.ui.AegisViewModelFactory
import com.aegisf6.app.util.DiagnosticsLog
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: AegisViewModel
    private lateinit var mapController: MapOverlayController
    private lateinit var bluetoothProbe: BluetoothProbe

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Location can still be approximated by default center if not granted.
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.toggleMicrophone()
        } else {
            DiagnosticsLog.toFixOnce(
                key = "mic_permission_denied",
                message = "RECORD_AUDIO permission denied; microphone mode stays disabled"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Configuration.getInstance().userAgentValue = packageName

        bluetoothProbe = BluetoothProbe(this)
        val audioProcessor = AudioProcessor(sampleRateHz = 44100, bufferSizeFrames = 2048)
        val locationProvider = LocationProvider(this)

        viewModel = ViewModelProvider(
            this,
            AegisViewModelFactory(bluetoothProbe, audioProcessor, locationProvider)
        )[AegisViewModel::class.java]

        mapController = MapOverlayController(binding.map)

        setupButtons()
        observeState()
        requestLocationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
        binding.map.onPause()
        super.onPause()
    }

    private fun setupButtons() {
        binding.btnMapStyle.setOnClickListener {
            viewModel.toggleMapStyle()
        }
        binding.btnCalibrate.setOnClickListener {
            if (!viewModel.startCalibration()) {
                DiagnosticsLog.notOk("Calibration button pressed while prerequisites are not met")
            }
        }
        binding.btnMic.setOnClickListener {
            if (viewModel.state.value.microphoneEnabled) {
                viewModel.toggleMicrophone()
            } else {
                requestMicrophonePermissionAndEnable()
            }
        }
        binding.btnJblStrict.setOnClickListener {
            viewModel.toggleJblStrictMode()
        }
    }

    private fun requestMicrophonePermissionAndEnable() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            viewModel.toggleMicrophone()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestLocationPermissionIfNeeded() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED) {
            return
        }
        val request = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            request += Manifest.permission.BLUETOOTH_CONNECT
        }
        permissionLauncher.launch(request.toTypedArray())
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    if (!state.microphoneEnabled && state.telemetry.confidence > 0) {
                        DiagnosticsLog.bugOnce(
                            key = "telemetry_confidence_without_mic",
                            message = "Non-zero telemetry confidence while microphone is disabled"
                        )
                    }

                    val sourceLabel = getString(R.string.mode_jbl_headset)
                    val headsetReady = bluetoothProbe.isReliableHeadsetReady()

                    // Update status with indicator
                    val (statusText, statusDrawable) = if (state.monitorActive) {
                        if (state.telemetry.accepted && state.telemetry.confidence > 70) {
                            Pair(getString(R.string.status_alert), R.drawable.status_alert_indicator)
                        } else {
                            Pair(getString(R.string.status_active), R.drawable.status_active_indicator)
                        }
                    } else {
                        Pair(getString(R.string.status_idle), R.drawable.status_idle_indicator)
                    }

                    binding.tvStatus.text = statusText
                    binding.ivStatusIndicator.setImageResource(statusDrawable)
                    binding.tvStatus.setTextColor(
                        ContextCompat.getColor(
                            this@MainActivity,
                            when {
                                !state.monitorActive -> R.color.status_idle
                                state.telemetry.accepted && state.telemetry.confidence > 70 -> R.color.status_alert
                                else -> R.color.status_active
                            }
                        )
                    )

                    binding.tvSourceResolved.text = getString(
                        R.string.source_runtime,
                        sourceLabel,
                        state.btMicCount
                    )

                    binding.tvMicStatus.text = if (state.microphoneEnabled && headsetReady) {
                        getString(R.string.mic_active_verified)
                    } else if (state.microphoneEnabled) {
                        getString(R.string.mic_active_unverified)
                    } else {
                        getString(R.string.mic_inactive)
                    }

                    val diagnosticsSummary = buildList {
                        if (!state.microphoneEnabled) add(getString(R.string.diagnostics_not_ok_mic_off))
                        if (!headsetReady) add(getString(R.string.diagnostics_not_added_headphones))
                        if (!state.telemetry.accepted && state.telemetry.rejectReason.isNotBlank()) {
                            add(getString(R.string.diagnostics_not_ok_reject, state.telemetry.rejectReason))
                        }
                    }.joinToString(separator = "\n")

                    val recentLogs = DiagnosticsLog.recent(4).joinToString(separator = "\n")
                    binding.tvDiagnostics.text = buildString {
                        if (diagnosticsSummary.isBlank()) {
                            append(getString(R.string.diagnostics_ok))
                        } else {
                            append(diagnosticsSummary)
                        }
                        if (recentLogs.isNotBlank()) {
                            append("\n\n")
                            append(getString(R.string.diagnostics_recent_logs))
                            append("\n")
                            append(recentLogs)
                        }
                    }

                    binding.btnMic.text = if (state.microphoneEnabled) {
                        getString(R.string.mic_disable)
                    } else {
                        getString(R.string.mic_enable)
                    }

                    binding.btnJblStrict.text = if (state.jblStrictMode) {
                        getString(R.string.jbl_strict_disable)
                    } else {
                        getString(R.string.jbl_strict_enable)
                    }
                    binding.tvSensitivityMode.text = when {
                        state.jblStrictMode && state.telemetry.rejectReason.contains("побутовий шум", ignoreCase = true) -> {
                            getString(R.string.adaptive_sensitivity_night_quiet)
                        }
                        state.jblStrictMode -> getString(R.string.jbl_strict_active)
                        else -> getString(R.string.adaptive_sensitivity_normal)
                    }

                    binding.tvTelemetry.text = getString(
                        R.string.telemetry_template,
                        state.telemetry.objectType,
                        state.telemetry.rawConfidence,
                        state.telemetry.confidence,
                        state.telemetry.distanceKm,
                        state.telemetry.targetKind.maxDistanceKm,
                        state.telemetry.azimuthDeg,
                        state.telemetry.altitudeM,
                        state.telemetry.uncertaintyM,
                        if (state.telemetry.accepted) {
                            getString(R.string.detection_accepted)
                        } else {
                            getString(R.string.detection_rejected, state.telemetry.rejectReason)
                        }
                    )

                    val filled = (state.telemetry.confidence / 10).coerceIn(0, 10)
                    val bar = "█".repeat(filled) + "░".repeat(10 - filled)
                    binding.tvSpectrum.text = getString(R.string.spectrum_template, bar)
                    binding.radarView.updateTelemetry(
                        azimuthDeg = state.telemetry.azimuthDeg.toFloat(),
                        altitudeM = state.telemetry.altitudeM,
                        distanceKm = state.telemetry.distanceKm,
                        confidence = state.telemetry.confidence,
                        accepted = state.telemetry.accepted,
                        isActive = state.monitorActive
                    )

                    binding.tvCalibration.text = if (state.calibrating) {
                        getString(R.string.calibration_running, state.calibrationSecondsLeft)
                    } else {
                        getString(R.string.calibration_idle, state.backgroundBaseline)
                    }
                    val canCalibrate = state.microphoneEnabled && state.btMicCount > 0
                    binding.btnCalibrate.isEnabled = canCalibrate
                    binding.btnCalibrate.alpha = if (canCalibrate) 1f else 0.55f
                    binding.tvCalibrationHint.text = if (canCalibrate) {
                        getString(R.string.calibration_ready_with_headphones)
                    } else {
                        getString(R.string.calibration_requires_headphones)
                    }

                    when (state.mapStyle) {
                        MapStyle.OSM_STANDARD -> binding.btnMapStyle.text = getString(R.string.map_style_standard)
                        MapStyle.OSM_TOPO -> binding.btnMapStyle.text = getString(R.string.map_style_topo)
                    }

                    when (state.mapStyle) {
                        MapStyle.OSM_STANDARD -> mapController.setStandardTiles()
                        MapStyle.OSM_TOPO -> mapController.setTopoTiles()
                    }

                    mapController.update(
                        userLat = state.userLat,
                        userLon = state.userLon,
                        targetLat = state.targetLat,
                        targetLon = state.targetLon,
                        targetKind = state.telemetry.targetKind,
                        accepted = state.telemetry.accepted
                    )
                }
            }
        }
    }
}
