package com.aurahear.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.view.View
import com.aurahear.app.databinding.ActivityMainBinding
import com.aurahear.app.model.AudioSettings
import com.aurahear.app.model.HearingPreset
import com.aurahear.app.model.MicSource
import com.aurahear.app.receiver.BluetoothAudioMonitor
import com.aurahear.app.service.HearingAidService
import com.aurahear.app.update.AppUpdateManager
import kotlin.math.log10
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var updateManager: AppUpdateManager

    private var hearingService: HearingAidService? = null
    private var isServiceBound = false

    private lateinit var bluetoothMonitor: BluetoothAudioMonitor

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (recordAudioGranted) {
            toggleHearingAid()
        } else {
            Toast.makeText(this, "Microphone permission is required to amplify sound.", Toast.LENGTH_LONG).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? HearingAidService.LocalBinder
            hearingService = localBinder?.getService()
            isServiceBound = true

            hearingService?.setOnLevelsListener { leftLevel, rightLevel ->
                runOnUiThread {
                    binding.vuMeter.setLevels(leftLevel, rightLevel)
                }
            }

            hearingService?.setOnStateListener { isRunning ->
                runOnUiThread {
                    updateServiceStateUI(isRunning)
                }
            }

            syncUIWithSettings(hearingService?.currentSettings ?: AudioSettings.load(this@MainActivity))
            updateServiceStateUI(hearingService?.isHearingAidRunning() == true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            hearingService = null
            isServiceBound = false
            updateServiceStateUI(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateManager = AppUpdateManager(this)
        binding.tvAppVersion.text = "AuraHear v${updateManager.currentVersionName}"

        setupListeners()
        setupBluetoothMonitor()

        // Bind to existing service if running
        val serviceIntent = Intent(this, HearingAidService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Automatically check for OTA updates 2.5s after launch
        binding.root.postDelayed({
            checkForUpdates(silent = true)
        }, 2500)
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothMonitor.stop()
        if (isServiceBound) {
            hearingService?.setOnLevelsListener(null)
            hearingService?.setOnStateListener(null)
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    private fun setupBluetoothMonitor() {
        bluetoothMonitor = BluetoothAudioMonitor(this) { isConnected, name ->
            runOnUiThread {
                if (isConnected) {
                    binding.tvBluetoothStatus.text = getString(R.string.device_connected, name ?: "Headset")
                    binding.tvBluetoothStatus.setTextColor(ContextCompat.getColor(this, R.color.primary_cyan))
                    binding.ivBluetoothIcon.setColorFilter(ContextCompat.getColor(this, R.color.primary_cyan))
                } else {
                    binding.tvBluetoothStatus.text = getString(R.string.no_headset_connected)
                    binding.tvBluetoothStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                    binding.ivBluetoothIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_muted))
                }
            }
        }
        bluetoothMonitor.start()
    }

    private fun setupListeners() {
        // Master Power Toggle
        binding.btnTogglePower.setOnClickListener {
            checkPermissionsAndToggle()
        }

        // Left Ear Slider
        binding.sliderLeftEar.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val settings = getCurrentSettings()
                val delta = value - settings.leftVolume
                settings.leftVolume = value
                updateEarReadout(isLeft = true, value = value)

                if (settings.isLinked) {
                    val newRight = (settings.rightVolume + delta).coerceIn(0.0f, 3.0f)
                    settings.rightVolume = newRight
                    binding.sliderRightEar.value = newRight
                    updateEarReadout(isLeft = false, value = newRight)
                }
                applySettings(settings)
            }
        }

        // Right Ear Slider
        binding.sliderRightEar.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val settings = getCurrentSettings()
                val delta = value - settings.rightVolume
                settings.rightVolume = value
                updateEarReadout(isLeft = false, value = value)

                if (settings.isLinked) {
                    val newLeft = (settings.leftVolume + delta).coerceIn(0.0f, 3.0f)
                    settings.leftVolume = newLeft
                    binding.sliderLeftEar.value = newLeft
                    updateEarReadout(isLeft = true, value = newLeft)
                }
                applySettings(settings)
            }
        }

        // Link/Unlink Ears Toggle
        binding.btnLinkEars.setOnClickListener {
            val settings = getCurrentSettings()
            settings.isLinked = !settings.isLinked
            updateLinkButtonUI(settings.isLinked)
            applySettings(settings)
        }

        // Left Ear Mute
        binding.btnMuteLeft.setOnClickListener {
            val settings = getCurrentSettings()
            settings.isLeftMuted = !settings.isLeftMuted
            updateMuteButtonUI(isLeft = true, isMuted = settings.isLeftMuted)
            applySettings(settings)
        }

        // Right Ear Mute
        binding.btnMuteRight.setOnClickListener {
            val settings = getCurrentSettings()
            settings.isRightMuted = !settings.isRightMuted
            updateMuteButtonUI(isLeft = false, isMuted = settings.isRightMuted)
            applySettings(settings)
        }

        // Master Boost Slider
        binding.sliderMasterBoost.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val settings = getCurrentSettings()
                settings.masterBoost = value
                binding.tvMasterBoostValue.text = "${String.format("%.1f", value)}x"
                applySettings(settings)
            }
        }

        // Presets ChipGroup
        binding.chipGroupPresets.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val settings = getCurrentSettings()
            when (checkedIds.first()) {
                R.id.chipPresetBalanced -> settings.applyPreset(HearingPreset.BALANCED)
                R.id.chipPresetConversation -> settings.applyPreset(HearingPreset.CONVERSATION)
                R.id.chipPresetQuiet -> settings.applyPreset(HearingPreset.QUIET_ROOM)
                R.id.chipPresetNoisy -> settings.applyPreset(HearingPreset.NOISY_OUTDOOR)
                R.id.chipPresetTV -> settings.applyPreset(HearingPreset.TV_LISTENING)
            }
            syncUIWithSettings(settings)
            applySettings(settings)
        }

        // Filters and Safety Switches
        binding.switchSafetyLimiter.setOnCheckedChangeListener { _, isChecked ->
            val settings = getCurrentSettings()
            settings.isSafetyLimiterEnabled = isChecked
            applySettings(settings)
        }

        binding.switchSpeechClarity.setOnCheckedChangeListener { _, isChecked ->
            val settings = getCurrentSettings()
            settings.isHighPassEnabled = isChecked
            applySettings(settings)
        }

        binding.switchNoiseSuppression.setOnCheckedChangeListener { _, isChecked ->
            val settings = getCurrentSettings()
            settings.isNoiseSuppressionEnabled = isChecked
            applySettings(settings)
        }

        // Mic Source RadioGroup
        binding.radioGroupMicSource.setOnCheckedChangeListener { _, checkedId ->
            val settings = getCurrentSettings()
            settings.micSource = if (checkedId == R.id.rbBluetoothMic) {
                MicSource.BLUETOOTH_MIC
            } else {
                MicSource.PHONE_MIC
            }
            applySettings(settings)
        }

        // OTA Auto-Update Controls
        binding.btnCheckUpdate.setOnClickListener {
            checkForUpdates(silent = false)
        }

        binding.btnDismissUpdate.setOnClickListener {
            binding.cardUpdateBanner.visibility = View.GONE
        }

        binding.btnApplyUpdate.setOnClickListener {
            startInAppUpdate()
        }
    }

    private fun checkForUpdates(silent: Boolean = false) {
        if (!silent) {
            Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show()
        }
        updateManager.checkForUpdate { info ->
            runOnUiThread {
                if (info.hasUpdate) {
                    binding.tvUpdateTag.text = info.tagName
                    binding.tvUpdateNotes.text = info.releaseNotes
                    binding.cardUpdateBanner.visibility = View.VISIBLE
                    binding.layoutUpdateActions.visibility = View.VISIBLE
                    binding.pbUpdateProgress.visibility = View.GONE
                    binding.tvUpdateProgressText.visibility = View.GONE
                } else if (!silent) {
                    val msg = if (info.error != null) {
                        "Update check failed: ${info.error}"
                    } else {
                        "AuraHear is up to date (v${updateManager.currentVersionName})"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startInAppUpdate() {
        binding.layoutUpdateActions.visibility = View.GONE
        binding.pbUpdateProgress.visibility = View.VISIBLE
        binding.pbUpdateProgress.progress = 0
        binding.tvUpdateProgressText.visibility = View.VISIBLE
        binding.tvUpdateProgressText.text = "Downloading update... 0%"

        updateManager.downloadAndInstall(
            activity = this,
            onProgress = { progress ->
                binding.pbUpdateProgress.progress = progress
                binding.tvUpdateProgressText.text = "Downloading update... $progress%"
            },
            onError = { error ->
                binding.layoutUpdateActions.visibility = View.VISIBLE
                binding.pbUpdateProgress.visibility = View.GONE
                binding.tvUpdateProgressText.visibility = View.GONE
                Toast.makeText(this, "Update failed: $error", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun checkPermissionsAndToggle() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            toggleHearingAid()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun toggleHearingAid() {
        val intent = Intent(this, HearingAidService::class.java)
        val isCurrentlyRunning = hearingService?.isHearingAidRunning() == true

        if (isCurrentlyRunning) {
            hearingService?.stopHearingAid()
            updateServiceStateUI(false)
        } else {
            intent.action = HearingAidService.ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            if (isServiceBound) {
                hearingService?.startHearingAid()
            }
            updateServiceStateUI(true)
        }
    }

    private fun updateServiceStateUI(isRunning: Boolean) {
        if (isRunning) {
            binding.btnTogglePower.apply {
                text = getString(R.string.stop_hearing_aid)
                setBackgroundResource(R.drawable.bg_power_button_active)
            }
            binding.tvServiceStatusBadge.apply {
                text = getString(R.string.status_active)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_active))
            }
        } else {
            binding.btnTogglePower.apply {
                text = getString(R.string.start_hearing_aid)
                setBackgroundResource(R.drawable.bg_power_button_inactive)
            }
            binding.tvServiceStatusBadge.apply {
                text = getString(R.string.status_stopped)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_inactive))
            }
            binding.vuMeter.setLevels(0f, 0f)
        }
    }

    private fun syncUIWithSettings(settings: AudioSettings) {
        binding.sliderLeftEar.value = settings.leftVolume.coerceIn(0.0f, 3.0f)
        updateEarReadout(isLeft = true, value = settings.leftVolume)

        binding.sliderRightEar.value = settings.rightVolume.coerceIn(0.0f, 3.0f)
        updateEarReadout(isLeft = false, value = settings.rightVolume)

        binding.sliderMasterBoost.value = settings.masterBoost.coerceIn(0.5f, 2.5f)
        binding.tvMasterBoostValue.text = "${String.format("%.1f", settings.masterBoost)}x"

        updateLinkButtonUI(settings.isLinked)
        updateMuteButtonUI(isLeft = true, isMuted = settings.isLeftMuted)
        updateMuteButtonUI(isLeft = false, isMuted = settings.isRightMuted)

        binding.switchSafetyLimiter.isChecked = settings.isSafetyLimiterEnabled
        binding.switchSpeechClarity.isChecked = settings.isHighPassEnabled
        binding.switchNoiseSuppression.isChecked = settings.isNoiseSuppressionEnabled

        if (settings.micSource == MicSource.BLUETOOTH_MIC) {
            binding.rbBluetoothMic.isChecked = true
        } else {
            binding.rbPhoneMic.isChecked = true
        }
    }

    private fun updateEarReadout(isLeft: Boolean, value: Float) {
        val pct = (value * 100).roundToInt()
        val db = if (value <= 0.001f) -60f else (20f * log10(value))
        val sign = if (db > 0) "+" else ""
        val dbStr = "${sign}${String.format("%.1f", db)} dB"
        val displayText = "$pct% ($dbStr)"

        if (isLeft) {
            binding.tvLeftGainValue.text = displayText
        } else {
            binding.tvRightGainValue.text = displayText
        }
    }

    private fun updateLinkButtonUI(isLinked: Boolean) {
        binding.btnLinkEars.apply {
            if (isLinked) {
                text = "Linked"
                setIconResource(R.drawable.ic_link)
                setIconTintResource(R.color.primary_cyan)
            } else {
                text = "Independent"
                setIconResource(R.drawable.ic_link)
                setIconTintResource(R.color.text_muted)
            }
        }
    }

    private fun updateMuteButtonUI(isLeft: Boolean, isMuted: Boolean) {
        val btn = if (isLeft) binding.btnMuteLeft else binding.btnMuteRight
        if (isMuted) {
            btn.setImageResource(R.drawable.ic_volume_mute)
            btn.setColorFilter(ContextCompat.getColor(this, R.color.accent_red))
        } else {
            btn.setImageResource(R.drawable.ic_volume_up)
            btn.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun getCurrentSettings(): AudioSettings {
        return hearingService?.currentSettings ?: AudioSettings.load(this)
    }

    private fun applySettings(settings: AudioSettings) {
        if (isServiceBound) {
            hearingService?.updateSettings(settings)
        } else {
            settings.save(this)
        }
    }
}
