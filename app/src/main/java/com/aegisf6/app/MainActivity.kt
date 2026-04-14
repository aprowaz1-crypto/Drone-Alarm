package com.aegisf6.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aegisf6.app.databinding.ActivityMainBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var userMarker: Marker
    private lateinit var targetMarker: Marker
    private lateinit var trajectory: Polyline

    private var simAngleDeg = 145.0
    private var simDistanceKm = 3.2

    private val tick = object : Runnable {
        override fun run() {
            simulateAndRender()
            uiHandler.postDelayed(this, 2500)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        renderUserPoint()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Configuration.getInstance().userAgentValue = packageName

        setupSourceSelector()
        setupMap()
        requestLocationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        uiHandler.post(tick)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(tick)
        binding.map.onPause()
        super.onPause()
    }

    private fun setupSourceSelector() {
        val items = listOf("Auto (Smart Switch)", "Phone Solo", "Multi-Array")
        binding.spSource.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
    }

    private fun setupMap() {
        binding.map.setTileSource(TileSourceFactory.MAPNIK)
        binding.map.setMultiTouchControls(true)
        binding.map.controller.setZoom(14.5)

        val center = GeoPoint(50.4501, 30.5234)
        binding.map.controller.setCenter(center)

        userMarker = Marker(binding.map).apply {
            title = "Ти"
            position = center
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }

        targetMarker = Marker(binding.map).apply {
            title = "Ціль (оцінка)"
            position = center
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }

        trajectory = Polyline().apply {
            outlinePaint.strokeWidth = 6f
            setPoints(listOf(center, center))
        }

        binding.map.overlays.add(trajectory)
        binding.map.overlays.add(userMarker)
        binding.map.overlays.add(targetMarker)
        binding.map.invalidate()
    }

    private fun requestLocationPermissionIfNeeded() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED) {
            renderUserPoint()
            return
        }
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun renderUserPoint() {
        val user = GeoPoint(50.4501, 30.5234)
        userMarker.position = user
        binding.map.controller.animateTo(user)
        binding.map.invalidate()
    }

    private fun simulateAndRender() {
        val user = userMarker.position
        simDistanceKm = (simDistanceKm - 0.08).coerceAtLeast(0.7)
        simAngleDeg += 1.8

        val target = project(user, simDistanceKm, simAngleDeg)
        targetMarker.position = target
        trajectory.setPoints(listOf(user, target))

        binding.tvTelemetry.text =
            "Тип: акустична ціль (демо) | Дистанція: %.2f км | Напрямок: %.0f° | ETA: ~%d c".format(
                simDistanceKm,
                simAngleDeg,
                ((simDistanceKm * 1000) / 50).toInt()
            )

        binding.map.invalidate()
    }

    private fun project(start: GeoPoint, distanceKm: Double, bearingDeg: Double): GeoPoint {
        val latStep = (distanceKm / 111.0) * cos(Math.toRadians(bearingDeg))
        val lonStep = (distanceKm / (111.0 * cos(Math.toRadians(start.latitude)))) * sin(Math.toRadians(bearingDeg))
        return GeoPoint(start.latitude + latStep, start.longitude + lonStep)
    }
}
