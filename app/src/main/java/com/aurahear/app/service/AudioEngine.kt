package com.aurahear.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioDeviceInfo
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.util.Log
import com.aurahear.app.model.AudioSettings
import com.aurahear.app.model.LatencyMode
import com.aurahear.app.model.MicSource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tanh

class AudioEngine(
    private val context: Context,
    private val onLevelsUpdate: (leftNorm: Float, rightNorm: Float) -> Unit
) {
    companion object {
        private const val TAG = "AuraHear_AudioEngine"
        private const val SAMPLE_RATE = 44100
        private const val CHUNK_SIZE = 256 // ~5.8ms chunks for ultra-low latency
        private const val LIMITER_THRESHOLD = 24000f
        private const val MAX_PCM_VALUE = 32767f
    }

    @Volatile
    private var isRunning = false
    private var workerThread: Thread? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    @Volatile
    var settings: AudioSettings = AudioSettings()

    // 1st order High-Pass Filter state (fc ≈ 120Hz at 44.1kHz -> alpha ≈ 0.983f)
    private val hpAlpha = 0.983f
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Synchronized
    fun start(currentSettings: AudioSettings) {
        if (isRunning) return
        this.settings = currentSettings

        try {
            initAudioHardware()
            isRunning = true

            workerThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                audioLoop()
            }, "AuraHear-AudioLoop").apply {
                start()
            }
            Log.i(TAG, "AudioEngine started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioEngine", e)
            stop()
            throw e
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        try {
            workerThread?.interrupt()
            workerThread?.join(500)
        } catch (_: Exception) {}
        workerThread = null

        releaseAudioHardware()
        Log.i(TAG, "AudioEngine stopped")
    }

    fun isEngineRunning(): Boolean = isRunning

    @SuppressLint("MissingPermission")
    private fun initAudioHardware() {
        val isUltraLow = settings.latencyMode == LatencyMode.ULTRA_LOW_LATENCY

        if (isUltraLow) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val commDevices = audioManager.availableCommunicationDevices
                val targetDevice = commDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_HEARING_AID ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                }
                if (targetDevice != null) {
                    audioManager.setCommunicationDevice(targetDevice)
                }
            } else {
                try {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                } catch (e: Exception) {
                    Log.w(TAG, "Bluetooth SCO start failed", e)
                }
            }
        } else {
            audioManager.mode = AudioManager.MODE_NORMAL
            if (settings.micSource == MicSource.BLUETOOTH_MIC) {
                try {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                } catch (_: Exception) {}
            }
        }

        val micSource = when {
            isUltraLow -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            settings.micSource == MicSource.PHONE_MIC -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        }

        val minRecBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recBufferSize = max(minRecBuf, CHUNK_SIZE * 2)

        audioRecord = AudioRecord(
            micSource,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            recBufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord initialization failed")
        }

        val sessionId = audioRecord?.audioSessionId ?: 0
        if (sessionId != 0 && settings.isNoiseSuppressionEnabled) {
            if (NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {
                        enabled = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not attach NoiseSuppressor", e)
                }
            }
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                        enabled = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not attach AcousticEchoCanceler", e)
                }
            }
        }

        val minTrackBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val trackBufferSize = max(minTrackBuf, CHUNK_SIZE * 4)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(if (isUltraLow) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(trackBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            throw IllegalStateException("AudioTrack initialization failed")
        }

        // On API 24+, shrink the internal playback buffer to minimal underrun threshold
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                audioTrack?.setBufferSizeInFrames(CHUNK_SIZE * 2)
            } catch (_: Exception) {}
        }

        audioRecord?.startRecording()
        audioTrack?.play()
    }

    private fun releaseAudioHardware() {
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
        } catch (_: Exception) {}

        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {}
        noiseSuppressor = null

        try {
            echoCanceler?.release()
        } catch (_: Exception) {}
        echoCanceler = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }

    private fun audioLoop() {
        val inputMono = ShortArray(CHUNK_SIZE)
        val outputStereo = ShortArray(CHUNK_SIZE * 2)

        var vuFramesCount = 0
        var sumSquaresL = 0.0
        var sumSquaresR = 0.0
        val vuUpdateIntervalFrames = CHUNK_SIZE * 4 // Update ~ every 46ms (~21 FPS)

        while (isRunning && !Thread.currentThread().isInterrupted) {
            val record = audioRecord ?: break
            val track = audioTrack ?: break

            val readShorts = record.read(inputMono, 0, CHUNK_SIZE)
            if (readShorts <= 0) {
                continue
            }

            // Snapshot settings for this chunk to avoid concurrency jitter
            val currentSettings = settings
            val master = currentSettings.masterBoost
            val effectiveLeftGain = if (currentSettings.isLeftMuted) 0f else (currentSettings.leftVolume * master)
            val effectiveRightGain = if (currentSettings.isRightMuted) 0f else (currentSettings.rightVolume * master)
            val useHighPass = currentSettings.isHighPassEnabled
            val useLimiter = currentSettings.isSafetyLimiterEnabled

            var outIdx = 0
            for (i in 0 until readShorts) {
                var rawSample = inputMono[i].toFloat()

                // 1. High-Pass Filter (Low-cut rumble removal)
                if (useHighPass) {
                    val hpOut = hpAlpha * (hpPrevOut + rawSample - hpPrevIn)
                    hpPrevIn = rawSample
                    hpPrevOut = hpOut
                    rawSample = hpOut
                }

                // 2. Individual Channel Gain & Amplification
                var sampleLeft = rawSample * effectiveLeftGain
                var sampleRight = rawSample * effectiveRightGain

                // 3. Acoustic Safety Limiter (Hearing Protection)
                if (useLimiter) {
                    sampleLeft = applySoftKneeLimiter(sampleLeft)
                    sampleRight = applySoftKneeLimiter(sampleRight)
                } else {
                    sampleLeft = sampleLeft.coerceIn(-MAX_PCM_VALUE, MAX_PCM_VALUE)
                    sampleRight = sampleRight.coerceIn(-MAX_PCM_VALUE, MAX_PCM_VALUE)
                }

                val finalShortL = sampleLeft.toInt().toShort()
                val finalShortR = sampleRight.toInt().toShort()

                // Interleaved stereo: Left then Right
                outputStereo[outIdx++] = finalShortL
                outputStereo[outIdx++] = finalShortR

                // VU Meter calculation
                sumSquaresL += (finalShortL * finalShortL)
                sumSquaresR += (finalShortR * finalShortR)
                vuFramesCount++
            }

            // Write processed stereo audio directly to headset
            track.write(outputStereo, 0, outIdx)

            // Dispatch VU Meter updates
            if (vuFramesCount >= vuUpdateIntervalFrames) {
                val rmsL = sqrt(sumSquaresL / vuFramesCount).toFloat() / MAX_PCM_VALUE
                val rmsR = sqrt(sumSquaresR / vuFramesCount).toFloat() / MAX_PCM_VALUE
                onLevelsUpdate(min(1f, rmsL * 3.5f), min(1f, rmsR * 3.5f))

                sumSquaresL = 0.0
                sumSquaresR = 0.0
                vuFramesCount = 0
            }
        }
    }

    /**
     * Soft-knee limiter that gracefully compresses loud peaks using hyperbolic tangent.
     * Prevents harsh digital clipping and protects user ears from sudden loud clatter.
     */
    private fun applySoftKneeLimiter(sample: Float): Float {
        val absVal = abs(sample)
        if (absVal <= LIMITER_THRESHOLD) {
            return sample
        }

        val excess = absVal - LIMITER_THRESHOLD
        val headroom = MAX_PCM_VALUE - LIMITER_THRESHOLD
        val normalizedExcess = excess / headroom
        val compressedExcess = headroom * tanh(normalizedExcess.toDouble()).toFloat()

        val limitedVal = LIMITER_THRESHOLD + compressedExcess
        return sign(sample) * min(limitedVal, MAX_PCM_VALUE)
    }

    fun updateSettings(newSettings: AudioSettings) {
        val oldSource = this.settings.micSource
        val oldLatency = this.settings.latencyMode
        val oldNoiseSupp = this.settings.isNoiseSuppressionEnabled
        this.settings = newSettings

        if (isRunning) {
            if (oldNoiseSupp != newSettings.isNoiseSuppressionEnabled) {
                try {
                    noiseSuppressor?.enabled = newSettings.isNoiseSuppressionEnabled
                } catch (_: Exception) {}
            }
            // If mic source or latency mode changed, restart hardware
            if (oldSource != newSettings.micSource || oldLatency != newSettings.latencyMode) {
                restart()
            }
        }
    }

    @Synchronized
    private fun restart() {
        if (!isRunning) return
        stop()
        start(settings)
    }
}
