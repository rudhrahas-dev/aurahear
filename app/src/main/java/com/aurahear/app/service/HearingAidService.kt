package com.aurahear.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aurahear.app.MainActivity
import com.aurahear.app.R
import com.aurahear.app.model.AudioSettings

class HearingAidService : Service() {

    companion object {
        private const val TAG = "AuraHear_Service"
        const val CHANNEL_ID = "aurahear_foreground_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.aurahear.app.action.START"
        const val ACTION_STOP = "com.aurahear.app.action.STOP"
        const val ACTION_TOGGLE_MUTE = "com.aurahear.app.action.TOGGLE_MUTE"
    }

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioEngine: AudioEngine? = null

    var currentSettings = AudioSettings()
        private set

    private var onLevelsListener: ((Float, Float) -> Unit)? = null
    private var onStateListener: ((Boolean) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): HearingAidService = this@HearingAidService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        currentSettings = AudioSettings.load(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AuraHear:AudioStreamLock")

        createNotificationChannel()

        audioEngine = AudioEngine(this) { leftNorm, rightNorm ->
            onLevelsListener?.invoke(leftNorm, rightNorm)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startHearingAid()
            ACTION_STOP -> stopHearingAid()
            ACTION_TOGGLE_MUTE -> toggleGlobalMute()
            else -> {
                if (audioEngine?.isEngineRunning() == true) {
                    updateNotification()
                }
            }
        }
        return START_STICKY
    }

    fun setOnLevelsListener(listener: ((Float, Float) -> Unit)?) {
        this.onLevelsListener = listener
    }

    fun setOnStateListener(listener: ((Boolean) -> Unit)?) {
        this.onStateListener = listener
        listener?.invoke(isHearingAidRunning())
    }

    fun isHearingAidRunning(): Boolean = audioEngine?.isEngineRunning() == true

    fun startHearingAid() {
        if (isHearingAidRunning()) return

        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(24 * 60 * 60 * 1000L) // Safe 24hr timeout
            }

            val notification = buildNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                }
                startForeground(NOTIFICATION_ID, notification, serviceTypes)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            audioEngine?.start(currentSettings)
            onStateListener?.invoke(true)
            Log.i(TAG, "Hearing aid active in background")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting hearing aid service", e)
            stopHearingAid()
        }
    }

    fun stopHearingAid() {
        try {
            audioEngine?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio engine", e)
        }

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        onStateListener?.invoke(false)
        onLevelsListener?.invoke(0f, 0f)
        Log.i(TAG, "Hearing aid service stopped")
    }

    fun updateSettings(newSettings: AudioSettings) {
        this.currentSettings = newSettings
        currentSettings.save(this)
        audioEngine?.updateSettings(newSettings)
        updateNotification()
    }

    fun toggleGlobalMute() {
        val currentlyMuted = currentSettings.isLeftMuted && currentSettings.isRightMuted
        currentSettings.isLeftMuted = !currentlyMuted
        currentSettings.isRightMuted = !currentlyMuted
        updateSettings(currentSettings)
    }

    private fun updateNotification() {
        if (!isHearingAidRunning()) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, HearingAidService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(this, HearingAidService::class.java).apply {
            action = ACTION_TOGGLE_MUTE
        }
        val pendingMute = PendingIntent.getService(
            this, 2, muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isMuted = currentSettings.isLeftMuted && currentSettings.isRightMuted
        val leftPct = (currentSettings.leftVolume * 100).toInt()
        val rightPct = (currentSettings.rightVolume * 100).toInt()

        val statusText = if (isMuted) {
            "Muted • Tap to unmute"
        } else {
            "Active • Ear Volumes: L ${leftPct}% | R ${rightPct}%"
        }

        val muteLabel = if (isMuted) "Unmute" else "Mute"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AuraHear Hearing Aid")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, "Stop", pendingStop)
            .addAction(android.R.drawable.ic_lock_silent_mode, muteLabel, pendingMute)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hearing Aid Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time status of your Bluetooth hearing aid service."
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopHearingAid()
        super.onDestroy()
    }
}
