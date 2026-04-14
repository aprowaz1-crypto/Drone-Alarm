package com.dronealarm.ua.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class SensorCollector(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onMetricsUpdated: (timestampNanos: Long, rssiVar: Double, audioPower: Double, vibRms: Double) -> Unit
) : SensorEventListener {

    private val jobs = mutableListOf<Job>()
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val metricWindow = ArrayDeque<MetricSample>()
    private val rfWindow = ArrayDeque<Double>()
    private val audioWindow = ArrayDeque<Double>()

    @Volatile
    private var vibrationRms = 0.0

    fun start() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        }

        jobs += scope.launch(Dispatchers.Default) {
            while (isActive) {
                val ts = SystemClock.elapsedRealtimeNanos()
                val wifiRssi = wifiManager.connectionInfo?.rssi?.toDouble() ?: -100.0
                val cellDbm = readCellDbm()
                val merged = (wifiRssi + cellDbm) / 2.0
                rfWindow.addLast(merged)
                trimWindow(rfWindow, 12)
                val variance = variance(rfWindow)
                pushMetrics(ts, rssiVar = variance, audioPower = latestAudioPower(), vibRms = vibrationRms)
                delay(500)
            }
        }

        jobs += scope.launch(Dispatchers.IO) {
            val sampleRate = 16_000
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuffer, sampleRate)
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            val shortBuffer = ShortArray(bufferSize)
            audioRecord.startRecording()
            try {
                while (isActive) {
                    val read = audioRecord.read(shortBuffer, 0, shortBuffer.size)
                    if (read > 0) {
                        val power = bandPower30to100(shortBuffer, read, sampleRate)
                        audioWindow.addLast(power)
                        trimWindow(audioWindow, 12)
                    }
                }
            } finally {
                audioRecord.stop()
                audioRecord.release()
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()
        vibrationRms = sqrt((x * x + y * y + z * z) / 3.0)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun readCellDbm(): Double {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return -100.0

        val info = telephonyManager.allCellInfo ?: return -100.0
        val first = info.firstOrNull() ?: return -100.0
        return when (first) {
            is CellInfoLte -> first.cellSignalStrength.dbm.toDouble()
            is CellInfoGsm -> first.cellSignalStrength.dbm.toDouble()
            is CellInfoWcdma -> first.cellSignalStrength.dbm.toDouble()
            is CellInfoNr -> first.cellSignalStrength.dbm.toDouble()
            else -> -100.0
        }
    }

    private fun latestAudioPower(): Double = audioWindow.lastOrNull() ?: 0.0

    private fun pushMetrics(timestampNanos: Long, rssiVar: Double, audioPower: Double, vibRms: Double) {
        metricWindow.addLast(MetricSample(timestampNanos, rssiVar, audioPower, vibRms))
        val cutoff = timestampNanos - 6_000_000_000L
        while (metricWindow.isNotEmpty() && metricWindow.first().timestampNanos < cutoff) {
            metricWindow.removeFirst()
        }
        onMetricsUpdated(timestampNanos, rssiVar, audioPower, vibRms)
    }

    private fun variance(values: Collection<Double>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.sum() / values.size
        return values.fold(0.0) { acc, x ->
            val d = x - mean
            acc + d * d
        } / values.size
    }

    private fun trimWindow(window: ArrayDeque<Double>, maxSize: Int) {
        while (window.size > maxSize) window.removeFirst()
    }

    // Simple cascaded one-pole HP + LP to approximate 30-100 Hz band.
    private fun bandPower30to100(samples: ShortArray, read: Int, sampleRate: Int): Double {
        val dt = 1.0 / sampleRate
        val rcHigh = 1.0 / (2.0 * Math.PI * 30.0)
        val alphaHigh = rcHigh / (rcHigh + dt)

        val rcLow = 1.0 / (2.0 * Math.PI * 100.0)
        val alphaLow = dt / (rcLow + dt)

        var hpPrev = 0.0
        var inPrev = 0.0
        var lpPrev = 0.0
        var acc = 0.0

        for (i in 0 until read) {
            val x = samples[i] / 32768.0
            val hp = alphaHigh * (hpPrev + x - inPrev)
            val lp = lpPrev + alphaLow * (hp - lpPrev)
            hpPrev = hp
            inPrev = x
            lpPrev = lp
            acc += lp * lp
        }

        return if (read > 0) acc / read else 0.0
    }

    private data class MetricSample(
        val timestampNanos: Long,
        val rssiVar: Double,
        val audioPower: Double,
        val vibRms: Double
    )
}
