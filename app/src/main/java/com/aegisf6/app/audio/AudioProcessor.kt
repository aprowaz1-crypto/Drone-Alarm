package com.aegisf6.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.aegisf6.app.util.DiagnosticsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

data class AudioFrame(
    val timestamp: Long,
    val rmsDb: Double,
    val leftChannel: FloatArray,
    val rightChannel: FloatArray,
    val peakFrequencyHz: Float
)

class AudioProcessor(
    private val sampleRateHz: Int = 44100,
    private val bufferSizeFrames: Int = 2048
) {
    private var audioRecord: AudioRecord? = null
    private val audioBuffer = ShortArray(bufferSizeFrames)
    private var isRunning = false
    private var lastFrameTime = 0L

    fun start(): Boolean {
        return try {
            val requiredSize = AudioRecord.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(requiredSize, bufferSizeFrames * 2)
            )

            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord?.startRecording()
                isRunning = true
                DiagnosticsLog.toFix("AudioProcessor started, sampleRate=$sampleRateHz Hz")
                true
            } else {
                DiagnosticsLog.bugOnce(
                    key = "audio_record_failed_init",
                    message = "AudioRecord failed to initialize"
                )
                false
            }
        } catch (e: Exception) {
            DiagnosticsLog.bugOnce(
                key = "audio_record_exception",
                message = "AudioRecord initialization failed: ${e.message}"
            )
            false
        }
    }

    suspend fun captureFrame(): AudioFrame? = withContext(Dispatchers.Default) {
        if (!isRunning || audioRecord == null) return@withContext null

        try {
            val bytesRead = audioRecord?.read(audioBuffer, 0, bufferSizeFrames, AudioRecord.READ_BLOCKING) ?: 0
            if (bytesRead <= 0) return@withContext null

            val timestamp = System.currentTimeMillis()
            val frameDurationMs = (bytesRead * 1000L) / (sampleRateHz * 2)
            lastFrameTime = timestamp

            // Розділити на ліву і праву реальні частини (за припущенням інтерліву)
            val frameCount = bytesRead / 4  // 2 bytes per sample, 2 channels
            val leftChannel = FloatArray(frameCount)
            val rightChannel = FloatArray(frameCount)

            var leftSum = 0.0
            var rightSum = 0.0

            for (i in 0 until frameCount) {
                val leftSample = audioBuffer[i * 2].toFloat() / 32768f
                val rightSample = audioBuffer[i * 2 + 1].toFloat() / 32768f
                leftChannel[i] = leftSample
                rightChannel[i] = rightSample
                leftSum += leftSample * leftSample
                rightSum += rightSample * rightSample
            }

            val leftRms = sqrt(leftSum / frameCount)
            val rightRms = sqrt(rightSum / frameCount)
            val avgRms = (leftRms + rightRms) / 2.0
            
            // RMS to dB (відносно 1.0)
            val rmsDb = if (avgRms > 0.00001) {
                20.0 * kotlin.math.log10(avgRms)
            } else {
                -120.0  // Мінімум
            }

            // Спростити для отримання базової частоти (детальний FFT пізніше)
            val peakFreq = estimatePeakFrequency(leftChannel)

            AudioFrame(
                timestamp = timestamp,
                rmsDb = rmsDb.coerceIn(-120.0, 0.0),
                leftChannel = leftChannel,
                rightChannel = rightChannel,
                peakFrequencyHz = peakFreq
            )
        } catch (e: Exception) {
            Log.e("AudioProcessor", "Frame capture error: ${e.message}")
            null
        }
    }

    private fun estimatePeakFrequency(samples: FloatArray): Float {
        // Простий ZeroCrossingRate для базової оцінки частоти
        var zeroCrossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i - 1] < 0 && samples[i] >= 0) ||
                (samples[i - 1] >= 0 && samples[i] < 0)
            ) {
                zeroCrossings++
            }
        }
        // frequency ≈ (zeroCrossings * sampleRate) / (2 * samples.size)
        return (zeroCrossings * sampleRateHz.toFloat()) / (2 * samples.size)
    }

    fun stop() {
        isRunning = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        DiagnosticsLog.toFix("AudioProcessor stopped")
    }

    fun isRecording(): Boolean = isRunning
}
