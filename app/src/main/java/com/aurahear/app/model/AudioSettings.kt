package com.aurahear.app.model

import android.content.Context
import android.content.SharedPreferences

enum class MicSource {
    PHONE_MIC,
    BLUETOOTH_MIC
}

enum class LatencyMode {
    ULTRA_LOW_LATENCY,
    HIGH_FIDELITY
}

enum class HearingPreset(val title: String) {
    BALANCED("Balanced"),
    CONVERSATION("Speech Focus"),
    QUIET_ROOM("Whisper / Quiet"),
    NOISY_OUTDOOR("Noisy / Traffic"),
    TV_LISTENING("TV / Media")
}

data class AudioSettings(
    var leftVolume: Float = 1.0f,          // 0.0f to 3.0f (0% to 300%)
    var rightVolume: Float = 1.0f,         // 0.0f to 3.0f (0% to 300%)
    var isLeftMuted: Boolean = false,
    var isRightMuted: Boolean = false,
    var masterBoost: Float = 1.0f,         // 0.5f to 2.0f
    var isLinked: Boolean = false,          // Adjust both ears synchronously
    var isHighPassEnabled: Boolean = true, // Low-cut rumble filter (120Hz)
    var isNoiseSuppressionEnabled: Boolean = false, // Set false by default to remove FFT lookahead delay
    var isSafetyLimiterEnabled: Boolean = true, // Soft-knee acoustic limiter
    var micSource: MicSource = MicSource.PHONE_MIC,
    var latencyMode: LatencyMode = LatencyMode.ULTRA_LOW_LATENCY
) {
    companion object {
        private const val PREFS_NAME = "aurahear_audio_prefs"
        private const val KEY_LEFT_VOL = "left_volume"
        private const val KEY_RIGHT_VOL = "right_volume"
        private const val KEY_LEFT_MUTED = "left_muted"
        private const val KEY_RIGHT_MUTED = "right_muted"
        private const val KEY_MASTER_BOOST = "master_boost"
        private const val KEY_LINKED = "is_linked"
        private const val KEY_HIGH_PASS = "high_pass_enabled"
        private const val KEY_NOISE_SUPP = "noise_supp_enabled"
        private const val KEY_SAFETY_LIMITER = "safety_limiter_enabled"
        private const val KEY_MIC_SOURCE = "mic_source"
        private const val KEY_LATENCY_MODE = "latency_mode"

        fun load(context: Context): AudioSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return AudioSettings(
                leftVolume = prefs.getFloat(KEY_LEFT_VOL, 1.0f),
                rightVolume = prefs.getFloat(KEY_RIGHT_VOL, 1.0f),
                isLeftMuted = prefs.getBoolean(KEY_LEFT_MUTED, false),
                isRightMuted = prefs.getBoolean(KEY_RIGHT_MUTED, false),
                masterBoost = prefs.getFloat(KEY_MASTER_BOOST, 1.0f),
                isLinked = prefs.getBoolean(KEY_LINKED, false),
                isHighPassEnabled = prefs.getBoolean(KEY_HIGH_PASS, true),
                isNoiseSuppressionEnabled = prefs.getBoolean(KEY_NOISE_SUPP, false),
                isSafetyLimiterEnabled = prefs.getBoolean(KEY_SAFETY_LIMITER, true),
                micSource = MicSource.valueOf(
                    prefs.getString(KEY_MIC_SOURCE, MicSource.PHONE_MIC.name) ?: MicSource.PHONE_MIC.name
                ),
                latencyMode = LatencyMode.valueOf(
                    prefs.getString(KEY_LATENCY_MODE, LatencyMode.ULTRA_LOW_LATENCY.name) ?: LatencyMode.ULTRA_LOW_LATENCY.name
                )
            )
        }
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putFloat(KEY_LEFT_VOL, leftVolume)
            putFloat(KEY_RIGHT_VOL, rightVolume)
            putBoolean(KEY_LEFT_MUTED, isLeftMuted)
            putBoolean(KEY_RIGHT_MUTED, isRightMuted)
            putFloat(KEY_MASTER_BOOST, masterBoost)
            putBoolean(KEY_LINKED, isLinked)
            putBoolean(KEY_HIGH_PASS, isHighPassEnabled)
            putBoolean(KEY_NOISE_SUPP, isNoiseSuppressionEnabled)
            putBoolean(KEY_SAFETY_LIMITER, isSafetyLimiterEnabled)
            putString(KEY_MIC_SOURCE, micSource.name)
            putString(KEY_LATENCY_MODE, latencyMode.name)
            apply()
        }
    }

    fun applyPreset(preset: HearingPreset) {
        when (preset) {
            HearingPreset.BALANCED -> {
                masterBoost = 1.0f
                isHighPassEnabled = true
                isNoiseSuppressionEnabled = true
                isSafetyLimiterEnabled = true
            }
            HearingPreset.CONVERSATION -> {
                masterBoost = 1.3f
                isHighPassEnabled = true // Cuts bass rumble to boost vocal presence
                isNoiseSuppressionEnabled = true
                isSafetyLimiterEnabled = true
            }
            HearingPreset.QUIET_ROOM -> {
                masterBoost = 1.6f // Extra sensitivity for whispers
                isHighPassEnabled = false
                isNoiseSuppressionEnabled = false
                isSafetyLimiterEnabled = true
            }
            HearingPreset.NOISY_OUTDOOR -> {
                masterBoost = 0.9f
                isHighPassEnabled = true
                isNoiseSuppressionEnabled = true
                isSafetyLimiterEnabled = true
            }
            HearingPreset.TV_LISTENING -> {
                masterBoost = 1.2f
                isHighPassEnabled = true
                isNoiseSuppressionEnabled = false
                isSafetyLimiterEnabled = true
            }
        }
    }
}
