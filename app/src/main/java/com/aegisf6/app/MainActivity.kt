package com.aegisf6.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AdapterView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aegisf6.app.databinding.ActivityMainBinding
import com.aegisf6.app.device.BluetoothProbe
import com.aegisf6.app.map.MapOverlayController
import com.aegisf6.app.model.ActiveSourceMode
import com.aegisf6.app.model.ForcedSourceMode
import com.aegisf6.app.model.MapStyle
import com.aegisf6.app.ui.AegisViewModel
import com.aegisf6.app.ui.AegisViewModelFactory
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: AegisViewModel
    private lateinit var mapController: MapOverlayController

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Location can still be approximated by default center if not granted.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Configuration.getInstance().userAgentValue = packageName

        viewModel = ViewModelProvider(
            this,
            AegisViewModelFactory(BluetoothProbe(this))
        )[AegisViewModel::class.java]

        mapController = MapOverlayController(binding.map)

        setupSourceSelector()
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

    private fun setupSourceSelector() {
        val items = listOf(
            getString(R.string.source_auto),
            getString(R.string.source_phone),
            getString(R.string.source_array)
        )
        binding.spSource.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        binding.spSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                when (position) {
                    1 -> viewModel.setForcedMode(ForcedSourceMode.PHONE_ONLY)
                    2 -> viewModel.setForcedMode(ForcedSourceMode.ARRAY_ONLY)
                    else -> viewModel.setForcedMode(ForcedSourceMode.AUTO)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupButtons() {
        binding.btnMapStyle.setOnClickListener {
            viewModel.toggleMapStyle()
        }
        binding.btnCalibrate.setOnClickListener {
            viewModel.startCalibration()
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
                    val sourceLabel = when (state.activeMode) {
                        ActiveSourceMode.MULTI_ARRAY -> getString(R.string.mode_multi_array)
                        ActiveSourceMode.PHONE_SOLO -> getString(R.string.mode_phone_solo)
                    }

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

                    binding.tvSourceResolved.text = getString(
                        R.string.source_runtime,
                        sourceLabel,
                        state.btMicCount
                    )

                    binding.tvTelemetry.text = getString(
                        R.string.telemetry_template,
                        state.telemetry.objectType,
                        state.telemetry.rawConfidence,
                        state.telemetry.confidence,
                        state.telemetry.distanceKm,
                        state.telemetry.speedKmh,
                        state.telemetry.azimuthDeg,
                        state.telemetry.altitudeM,
                        state.telemetry.etaSec,
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
                        confidence = state.telemetry.confidence,
                        accepted = state.telemetry.accepted
                    )

                    binding.tvCalibration.text = if (state.calibrating) {
                        getString(R.string.calibration_running, state.calibrationSecondsLeft)
                    } else {
                        getString(R.string.calibration_idle, state.backgroundBaseline)
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
                        targetLon = state.targetLon
                    )
                }
            }
        }
    }
}
