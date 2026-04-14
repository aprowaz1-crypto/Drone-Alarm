package com.dronealarm.ua.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dronealarm.ua.MainActivity
import com.dronealarm.ua.R
import com.dronealarm.ua.engine.CalibrationManager
import com.dronealarm.ua.engine.DetectionEngine
import com.dronealarm.ua.network.MqttPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MonitorForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var sensorCollector: SensorCollector
    private lateinit var detectionEngine: DetectionEngine
    private lateinit var mqttPublisher: MqttPublisher

    override fun onCreate() {
        super.onCreate()
        createChannel()

        val prefs = getSharedPreferences("drone_alarm_prefs", Context.MODE_PRIVATE)
        mqttPublisher = MqttPublisher()
        detectionEngine = DetectionEngine(
            calibrationManager = CalibrationManager(prefs),
            mqttPublisher = mqttPublisher,
            scope = serviceScope,
            isMqttEnabled = { prefs.getBoolean("mqtt_enabled", true) },
            locationProvider = { lastKnownLocation() }
        )

        sensorCollector = SensorCollector(this, serviceScope) { timestampNanos, rssiVar, audioPower, vibRms ->
            val output = detectionEngine.consume(
                DetectionEngine.Input(timestampNanos, rssiVar, audioPower, vibRms)
            )
            sendUiUpdate(output.confidence, output.activeChannels, output.isAlert)
            if (output.isAlert) {
                notifyAlert(output.confidence, output.distanceLabel)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildPersistentNotification())
                sensorCollector.start()
            }
            ACTION_STOP -> {
                stopSelf()
            }
            ACTION_CALIBRATE -> {
                detectionEngine.resetCalibration()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        sensorCollector.stop()
        mqttPublisher.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildPersistentNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.monitoring_active))
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun notifyAlert(confidence: Double, distance: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Виявлено підозрілу активність")
            .setContentText("Впевненість: ${(confidence * 100).toInt()}%, $distance")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun sendUiUpdate(confidence: Double, channels: Int, isAlert: Boolean) {
        val intent = Intent(ACTION_STATUS)
            .setPackage(packageName)
            .putExtra(EXTRA_CONFIDENCE, confidence)
            .putExtra(EXTRA_CHANNELS, channels)
            .putExtra(EXTRA_ALERT, isAlert)
        sendBroadcast(intent)
    }

    private fun lastKnownLocation(): Location? {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.getProviders(true)
            .mapNotNull { provider -> lm.getLastKnownLocation(provider) }
            .maxByOrNull { it.time }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.dronealarm.ua.action.START"
        const val ACTION_STOP = "com.dronealarm.ua.action.STOP"
        const val ACTION_CALIBRATE = "com.dronealarm.ua.action.CALIBRATE"
        const val ACTION_STATUS = "com.dronealarm.ua.action.STATUS"

        const val EXTRA_CONFIDENCE = "extra_confidence"
        const val EXTRA_CHANNELS = "extra_channels"
        const val EXTRA_ALERT = "extra_alert"

        private const val CHANNEL_ID = "drone_alarm_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_ID = 1002
    }
}
