package com.example

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

data class BluetoothAudioDevice(
    val id: Int,
    val name: String,
    val typeName: String,
    val type: Int,
    val isCommunicationCandidate: Boolean
)

data class AudioSwitchResult(
    val success: Boolean,
    val message: String,
    val targetDeviceName: String?,
    val isRealDeviceSwitched: Boolean
)

class AudioRoutingManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val bluetoothTypes = buildSet {
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(AudioDeviceInfo.TYPE_BLE_BROADCAST)
        }
        add(AudioDeviceInfo.TYPE_HEARING_AID)
    }

    /**
     * Retrieves currently connected Bluetooth audio output devices.
     */
    fun getConnectedBluetoothDevices(): List<BluetoothAudioDevice> {
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputDevices
            .filter { it.type in bluetoothTypes }
            .map { device ->
                BluetoothAudioDevice(
                    id = device.id,
                    name = device.productName?.toString().takeIf { !it.isNullOrBlank() } ?: "Bluetooth Audio Device",
                    typeName = getDeviceTypeName(device.type),
                    type = device.type,
                    isCommunicationCandidate = true
                )
            }
    }

    /**
     * Switches phone audio routing to the connected Bluetooth device.
     */
    fun switchToBluetoothAudio(): AudioSwitchResult {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ (API 31+) official setCommunicationDevice API
                val availableCommDevices = audioManager.availableCommunicationDevices
                val btCommDevice = availableCommDevices.firstOrNull { it.type in bluetoothTypes }

                if (btCommDevice != null) {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    val switched = audioManager.setCommunicationDevice(btCommDevice)
                    val devName = btCommDevice.productName?.toString() ?: "Bluetooth Audio"
                    AudioSwitchResult(
                        success = switched,
                        message = if (switched) "Audio output switched to $devName" else "Failed to switch routing to $devName",
                        targetDeviceName = devName,
                        isRealDeviceSwitched = switched
                    )
                } else {
                    // Check general output devices
                    val generalDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    val generalBt = generalDevices.firstOrNull { it.type in bluetoothTypes }
                    if (generalBt != null) {
                        val devName = generalBt.productName?.toString() ?: "Bluetooth Audio"
                        AudioSwitchResult(
                            success = true,
                            message = "Connected to $devName (A2DP media active)",
                            targetDeviceName = devName,
                            isRealDeviceSwitched = true
                        )
                    } else {
                        AudioSwitchResult(
                            success = false,
                            message = "No Bluetooth audio device connected. Please connect your Redmi Buds first.",
                            targetDeviceName = null,
                            isRealDeviceSwitched = false
                        )
                    }
                }
            } else {
                // Android < 12 fallback (Bluetooth SCO)
                val generalDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val generalBt = generalDevices.firstOrNull { it.type in bluetoothTypes }
                val devName = generalBt?.productName?.toString() ?: "Bluetooth Headset"

                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true

                AudioSwitchResult(
                    success = true,
                    message = "Bluetooth SCO routing initiated for $devName",
                    targetDeviceName = devName,
                    isRealDeviceSwitched = (generalBt != null)
                )
            }
        } catch (e: Exception) {
            Log.e("AudioRoutingManager", "Failed to switch audio", e)
            AudioSwitchResult(
                success = false,
                message = "Audio routing error: ${e.localizedMessage}",
                targetDeviceName = null,
                isRealDeviceSwitched = false
            )
        }
    }

    /**
     * Resets communication routing to system default.
     */
    fun resetAudioRouting() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            audioManager.mode = AudioManager.MODE_NORMAL
            if (audioManager.isBluetoothScoOn) {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
            }
        } catch (e: Exception) {
            Log.e("AudioRoutingManager", "Failed to reset audio", e)
        }
    }

    /**
     * Registers an AudioDeviceCallback to listen for Bluetooth connections/disconnections.
     */
    fun registerDeviceCallback(onDevicesChanged: () -> Unit): AudioDeviceCallback {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                onDevicesChanged()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                onDevicesChanged()
            }
        }
        audioManager.registerAudioDeviceCallback(callback, null)
        return callback
    }

    fun unregisterDeviceCallback(callback: AudioDeviceCallback) {
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    private fun getDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP (High Quality Media)"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO (Headset Voice)"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Audio Headset"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE Audio Speaker"
            AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing Aid"
            else -> "Bluetooth Audio"
        }
    }
}
