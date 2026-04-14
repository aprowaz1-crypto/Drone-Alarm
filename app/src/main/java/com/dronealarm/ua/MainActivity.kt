package com.dronealarm.ua

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
import androidx.core.content.ContextCompat
import com.dronealarm.ua.databinding.ActivityMainBinding
import com.dronealarm.ua.service.MonitorForegroundService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var monitoring = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (audioGranted && locationGranted) {
            startMonitoring()
        } else {
            Toast.makeText(this, "Потрібні дозволи AUDIO та LOCATION", Toast.LENGTH_LONG).show()
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MonitorForegroundService.ACTION_STATUS) return
            val confidence = intent.getDoubleExtra(MonitorForegroundService.EXTRA_CONFIDENCE, 0.0)
            val channels = intent.getIntExtra(MonitorForegroundService.EXTRA_CHANNELS, 0)
            val alert = intent.getBooleanExtra(MonitorForegroundService.EXTRA_ALERT, false)

            binding.tvChannels.text = "Канали: $channels/3"
            binding.tvConfidence.text = "Впевненість: ${(confidence * 100).toInt()}%"
            if (alert) {
                binding.tvLastAlert.text = "Остання тривога: щойно"
                Toast.makeText(this@MainActivity, "Тривога: ймовірна активність БпЛА", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("drone_alarm_prefs", Context.MODE_PRIVATE)
        binding.switchMqtt.isChecked = prefs.getBoolean("mqtt_enabled", true)

        binding.switchMqtt.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("mqtt_enabled", isChecked).apply()
        }

        binding.btnStartStop.setOnClickListener {
            if (monitoring) stopMonitoring() else ensurePermissionsThenStart()
        }

        binding.btnCalibrate.setOnClickListener {
            val intent = Intent(this, MonitorForegroundService::class.java)
                .setAction(MonitorForegroundService.ACTION_CALIBRATE)
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "Калібрування перезапущено", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(MonitorForegroundService.ACTION_STATUS),
            RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun ensurePermissionsThenStart() {
        val needed = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startMonitoring()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startMonitoring() {
        val intent = Intent(this, MonitorForegroundService::class.java)
            .setAction(MonitorForegroundService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        monitoring = true
        binding.btnStartStop.text = "Зупинити"
    }

    private fun stopMonitoring() {
        val intent = Intent(this, MonitorForegroundService::class.java)
        stopService(intent)
        monitoring = false
        binding.btnStartStop.text = "Старт моніторингу"
    }
}
