package com.aurahear.app.receiver

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

class BluetoothAudioMonitor(
    private val context: Context,
    private val onDeviceChanged: (isConnected: Boolean, deviceName: String?) -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            checkActiveHeadset()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            checkActiveHeadset()
        }
    }

    fun start() {
        audioManager.registerAudioDeviceCallback(callback, handler)
        checkActiveHeadset()
    }

    fun stop() {
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    fun checkActiveHeadset() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var foundHeadset = false
        var name: String? = null

        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_HEARING_AID -> {
                    foundHeadset = true
                    name = device.productName?.toString() ?: "Bluetooth Headset"
                    break
                }
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET -> {
                    foundHeadset = true
                    name = device.productName?.toString() ?: "Wired Headset"
                    break
                }
            }
        }

        onDeviceChanged(foundHeadset, name)
    }
}
