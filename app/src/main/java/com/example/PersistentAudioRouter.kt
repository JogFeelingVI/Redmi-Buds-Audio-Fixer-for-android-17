package com.example

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class RoutingMode(val label: String, val description: String) {
    MEDIA_A2DP(
        "Media Mode (A2DP / Stereo)",
        "Keeps high-definition stereo media channel permanently active for music, video & apps."
    ),
    COMMUNICATION_SCO(
        "Call Mode (SCO / Communication)",
        "Locks system voice communication route to Bluetooth headset without 5-second timeout."
    )
}

data class RoutingEngineState(
    val isHolding: Boolean = false,
    val activeDeviceName: String? = null,
    val activeDeviceType: String? = null,
    val selectedMode: RoutingMode = RoutingMode.MEDIA_A2DP,
    val elapsedSeconds: Long = 0,
    val reRouteCount: Int = 0,
    val statusMessage: String = "Status: Idle",
    val isRealDeviceConnected: Boolean = false
)

class PersistentAudioRouter(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private var keepAliveJob: Job? = null
    private var timerJob: Job? = null
    private var watchdogJob: Job? = null
    private var activeAudioTrack: AudioTrack? = null

    private val _engineState = MutableStateFlow(RoutingEngineState())
    val engineState: StateFlow<RoutingEngineState> = _engineState.asStateFlow()

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

    private var deviceCallback: AudioDeviceCallback? = null

    init {
        updateDeviceStatus()
        registerDeviceListener()
    }

    private fun registerDeviceListener() {
        deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                updateDeviceStatus()
                if (_engineState.value.isHolding) {
                    reapplyRouting()
                }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                updateDeviceStatus()
                if (_engineState.value.isHolding) {
                    reapplyRouting()
                }
            }
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
    }

    fun setMode(mode: RoutingMode) {
        _engineState.value = _engineState.value.copy(selectedMode = mode)
        if (_engineState.value.isHolding) {
            // Re-apply routing with new mode
            startPersistentRouting()
        }
    }

    fun updateDeviceStatus() {
        val btDevice = findBestBluetoothDevice()
        _engineState.value = _engineState.value.copy(
            isRealDeviceConnected = (btDevice != null),
            activeDeviceName = btDevice?.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth Device",
            activeDeviceType = btDevice?.let { getDeviceTypeName(it.type) }
        )
    }

    fun findBestBluetoothDevice(): AudioDeviceInfo? {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.firstOrNull { it.type in bluetoothTypes }
    }

    /**
     * Starts persistent audio routing:
     * 1. Applies hardware routing to Bluetooth.
     * 2. Starts a persistent silent AudioTrack to keep the hardware audio pipeline active.
     * 3. Runs a watchdog to prevent Android OS from dropping back to phone speaker.
     */
    fun startPersistentRouting(): Boolean {
        stopPersistentRoutingInternal()

        val btDevice = findBestBluetoothDevice()
        val mode = _engineState.value.selectedMode

        val deviceName = btDevice?.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth Headset"
        val deviceTypeName = btDevice?.let { getDeviceTypeName(it.type) } ?: "Bluetooth Audio"

        try {
            // 1. Hardware routing setup
            if (mode == RoutingMode.COMMUNICATION_SCO) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val commDevices = audioManager.availableCommunicationDevices
                    val targetComm = commDevices.firstOrNull { it.type in bluetoothTypes } ?: btDevice
                    if (targetComm != null) {
                        audioManager.setCommunicationDevice(targetComm)
                    }
                } else {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }
            } else {
                // Media A2DP mode: keep MODE_NORMAL to preserve stereo HD audio quality
                audioManager.mode = AudioManager.MODE_NORMAL
            }

            // 2. Launch Silent Keep-Alive AudioTrack
            startSilentKeepAliveTrack(btDevice, mode)

            // 3. Update engine state
            _engineState.value = _engineState.value.copy(
                isHolding = true,
                activeDeviceName = deviceName,
                activeDeviceType = deviceTypeName,
                elapsedSeconds = 0,
                statusMessage = "Status: Routing Command Executed"
            )

            // 4. Start timer & watchdog
            startElapsedTimer()
            startRoutingWatchdog()

            return true
        } catch (e: Exception) {
            Log.e("PersistentAudioRouter", "Failed to start persistent routing", e)
            _engineState.value = _engineState.value.copy(
                isHolding = false,
                statusMessage = "Status: Routing Failed (${e.localizedMessage})"
            )
            return false
        }
    }

    private fun startSilentKeepAliveTrack(targetDevice: AudioDeviceInfo?, mode: RoutingMode) {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
        val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        val bufferSize = maxOf(minBufferSize, 4096)

        val usage = if (mode == RoutingMode.COMMUNICATION_SCO) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else {
            AudioAttributes.USAGE_MEDIA
        }

        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .setEncoding(audioEncoding)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (targetDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                track.setPreferredDevice(targetDevice)
            } catch (e: Exception) {
                Log.w("PersistentAudioRouter", "Could not setPreferredDevice", e)
            }
        }

        try {
            track.play()
        } catch (e: Exception) {
            Log.w("PersistentAudioRouter", "AudioTrack play error", e)
        }
        activeAudioTrack = track

        // Background loop feeding silence
        keepAliveJob = scope.launch(Dispatchers.IO) {
            val silenceBuffer = ByteArray(bufferSize)
            while (isActive) {
                try {
                    track.write(silenceBuffer, 0, silenceBuffer.size)
                    delay(40)
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    private fun startElapsedTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            var seconds = 0L
            while (isActive && _engineState.value.isHolding) {
                delay(1000)
                seconds++
                _engineState.value = _engineState.value.copy(elapsedSeconds = seconds)
            }
        }
    }

    private fun startRoutingWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && _engineState.value.isHolding) {
                delay(3000)
                // Periodically check if target is still active
                val currentDevice = findBestBluetoothDevice()
                if (currentDevice == null && _engineState.value.isRealDeviceConnected) {
                    // Bluetooth disconnected
                    _engineState.value = _engineState.value.copy(isRealDeviceConnected = false)
                } else if (currentDevice != null) {
                    // Re-affirm preferred device
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        activeAudioTrack?.let { track ->
                            if (track.preferredDevice == null) {
                                track.setPreferredDevice(currentDevice)
                            }
                        }
                    }
                }
            }
        }
    }

    fun reapplyRouting() {
        if (_engineState.value.isHolding) {
            val count = _engineState.value.reRouteCount + 1
            _engineState.value = _engineState.value.copy(reRouteCount = count)
            startPersistentRouting()
        }
    }

    fun stopPersistentRouting() {
        stopPersistentRoutingInternal()
        _engineState.value = _engineState.value.copy(
            isHolding = false,
            elapsedSeconds = 0,
            statusMessage = "Status: Idle"
        )
    }

    private fun stopPersistentRoutingInternal() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        timerJob?.cancel()
        timerJob = null
        watchdogJob?.cancel()
        watchdogJob = null

        try {
            activeAudioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.w("PersistentAudioRouter", "Error releasing AudioTrack", e)
        }
        activeAudioTrack = null

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
            Log.w("PersistentAudioRouter", "Error resetting audioManager", e)
        }
    }

    fun destroy() {
        stopPersistentRouting()
        deviceCallback?.let {
            audioManager.unregisterAudioDeviceCallback(it)
        }
    }

    private fun getDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP (High Definition Stereo)"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO (Voice Communication)"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Audio Headset"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE Audio Speaker"
            AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing Aid"
            else -> "Bluetooth Audio"
        }
    }
}
