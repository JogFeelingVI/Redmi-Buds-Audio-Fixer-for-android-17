package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRouter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sin

enum class AudioOutputMode(val title: String, val codecTag: String, val description: String) {
    AAC_A2DP(
        "AAC / A2DP Stereo (High Definition)",
        "AAC / SBC • 44.1kHz Stereo",
        "High quality stereo music playback with active A2DP trigger and keep-alive."
    ),
    SCO_CALL(
        "SCO Communication Route (Forced Routing)",
        "mSBC / CVSD • 16kHz Mono",
        "Forces hardware call mixer directly to headset. Highest system priority."
    )
}

data class RoutingServiceState(
    val isRunning: Boolean = false,
    val currentMode: AudioOutputMode = AudioOutputMode.AAC_A2DP,
    val deviceName: String = "Bluetooth Headset",
    val codecDescription: String = "AAC / SBC High Quality Stereo",
    val elapsedSeconds: Long = 0,
    val isConnectedToHeadset: Boolean = false,
    val a2dpSwitchNotice: String? = null
)

class AudioRoutingService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var keepAliveJob: Job? = null
    private var timerJob: Job? = null
    private var watchdogJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var audioTrack: AudioTrack? = null
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    companion object {
        const val CHANNEL_ID = "audio_routing_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.action.START_ROUTING"
        const val ACTION_STOP = "com.example.action.STOP_ROUTING"
        const val ACTION_SWITCH_MODE = "com.example.action.SWITCH_MODE"
        const val EXTRA_MODE = "extra_audio_mode"

        private val _serviceState = MutableStateFlow(RoutingServiceState())
        val serviceState: StateFlow<RoutingServiceState> = _serviceState.asStateFlow()

        fun startService(context: Context, mode: AudioOutputMode = AudioOutputMode.AAC_A2DP) {
            val intent = Intent(context, AudioRoutingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, mode.name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun switchMode(context: Context, mode: AudioOutputMode) {
            val intent = Intent(context, AudioRoutingService::class.java).apply {
                action = ACTION_SWITCH_MODE
                putExtra(EXTRA_MODE, mode.name)
            }
            context.startService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, AudioRoutingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun openMediaOutputPanel(context: Context): Boolean {
            return try {
                val intent = Intent("com.android.settings.panel.action.MEDIA_OUTPUT").apply {
                    putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }

        /**
         * Plays an instant 1.5-second stereo chime test (Left then Right) directly to the Bluetooth A2DP device.
         * Returns true if successfully played.
         */
        suspend fun playAacStereoTest(context: Context): Boolean = withContext(Dispatchers.IO) {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val btDevice = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
                }

                if (btDevice == null) {
                    Log.w("AudioRoutingService", "playAacStereoTest: No Bluetooth A2DP device found! Aborting to avoid phone speaker output.")
                    return@withContext false
                }

                val sampleRate = 44100
                val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
                val encoding = AudioFormat.ENCODING_PCM_16BIT
                val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
                val bufferSize = maxOf(minBufferSize, 4096)

                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()

                val format = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(encoding)
                    .build()

                val track = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                if (btDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    track.setPreferredDevice(btDevice)
                }

                track.play()

                // Generate 1.2s tone: First 0.6s left ear 587Hz, second 0.6s right ear 880Hz
                val totalSamples = (sampleRate * 1.2).toInt()
                val halfSamples = totalSamples / 2
                val buffer = ShortArray(totalSamples * 2)

                for (i in 0 until halfSamples) {
                    val angle = 2.0 * Math.PI * i / (sampleRate / 587.33)
                    val sample = (sin(angle) * 9000).toInt().toShort()
                    buffer[i * 2] = sample // Left
                    buffer[i * 2 + 1] = 0  // Right silent
                }

                for (i in halfSamples until totalSamples) {
                    val angle = 2.0 * Math.PI * (i - halfSamples) / (sampleRate / 880.0)
                    val sample = (sin(angle) * 9000).toInt().toShort()
                    buffer[i * 2] = 0      // Left silent
                    buffer[i * 2 + 1] = sample // Right
                }

                track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
                delay(300)
                track.stop()
                track.release()
                true
            } catch (e: Exception) {
                Log.e("AudioRoutingService", "Test playback failed", e)
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RedmiBudsAudioFixer::AudioRoutingWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modeName = intent?.getStringExtra(EXTRA_MODE)
        val targetMode = if (modeName != null) {
            try { AudioOutputMode.valueOf(modeName) } catch (_: Exception) { AudioOutputMode.AAC_A2DP }
        } else {
            _serviceState.value.currentMode
        }

        when (intent?.action) {
            ACTION_STOP -> {
                stopRouting()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SWITCH_MODE -> {
                if (_serviceState.value.isRunning) {
                    applyRoutingMode(targetMode)
                } else {
                    _serviceState.value = _serviceState.value.copy(currentMode = targetMode)
                }
                return START_STICKY
            }
            ACTION_START -> {
                startRoutingForeground(targetMode)
                return START_STICKY
            }
            else -> {
                startRoutingForeground(targetMode)
                return START_STICKY
            }
        }
    }

    private fun startRoutingForeground(mode: AudioOutputMode) {
        val notification = createNotification("Audio Routing Active (${mode.codecTag})")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        wakeLock?.acquire(24 * 60 * 60 * 1000L)

        applyRoutingMode(mode)
        startTimer()
    }

    private fun applyRoutingMode(mode: AudioOutputMode) {
        stopAudioTrack()

        val btDevice = findBluetoothDevice(mode)
        val devName = btDevice?.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Redmi Buds / Bluetooth Headset"

        if (mode == AudioOutputMode.AAC_A2DP) {
            // --- AAC / A2DP High Definition Stereo Mode ---
            try {
                // 1. Release any active SCO or communication mode cleanly
                if (audioManager.isBluetoothScoOn) {
                    audioManager.isBluetoothScoOn = false
                    audioManager.stopBluetoothSco()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                }
                audioManager.mode = AudioManager.MODE_NORMAL
                audioManager.isSpeakerphoneOn = false

                // 2. Select Bluetooth route via MediaRouter2 (Android 11+)
                val transferResult = MediaRouter2Helper.transferToBluetooth(this, devName)
                Log.d("AudioRoutingService", "MediaRouter2 transfer: ${transferResult.message}")

                // 3. Request Audio Focus and trigger active A2DP sound wave to force AudioPolicy to switch to Bluetooth
                requestMediaAudioFocus()
                startAacActiveKeepAliveTrack(btDevice)

                _serviceState.value = _serviceState.value.copy(
                    isRunning = true,
                    currentMode = AudioOutputMode.AAC_A2DP,
                    deviceName = devName,
                    codecDescription = "AAC / SBC High-Def Stereo (44.1kHz)",
                    isConnectedToHeadset = (btDevice != null),
                    a2dpSwitchNotice = if (transferResult.isSuccess) transferResult.message else if (btDevice != null) "A2DP Triggered for $devName" else "No A2DP Headset detected"
                )
            } catch (e: Exception) {
                Log.e("AudioRoutingService", "Error applying AAC A2DP routing", e)
            }
        } else {
            // --- SCO Communication Force Mode ---
            try {
                abandonMediaAudioFocus()
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val commDevices = audioManager.availableCommunicationDevices
                    val target = commDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    } ?: btDevice
                    if (target != null) {
                        audioManager.setCommunicationDevice(target)
                    }
                }

                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true

                startScoKeepAliveTrack()

                _serviceState.value = _serviceState.value.copy(
                    isRunning = true,
                    currentMode = AudioOutputMode.SCO_CALL,
                    deviceName = devName,
                    codecDescription = "mSBC / CVSD Voice SCO (16kHz)",
                    isConnectedToHeadset = (btDevice != null),
                    a2dpSwitchNotice = null
                )
            } catch (e: Exception) {
                Log.e("AudioRoutingService", "Error applying SCO routing", e)
            }
        }

        startWatchdog(mode)
    }

    private fun selectMediaRouteForBluetooth(targetName: String) {
        try {
            val mediaRouter = getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter
            if (mediaRouter != null) {
                for (i in 0 until mediaRouter.routeCount) {
                    val route = mediaRouter.getRouteAt(i)
                    val rName = route.name?.toString() ?: ""
                    if (route.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH ||
                        rName.contains(targetName, ignoreCase = true) ||
                        rName.contains("Redmi", ignoreCase = true) ||
                        rName.contains("Buds", ignoreCase = true) ||
                        rName.contains("Bluetooth", ignoreCase = true)
                    ) {
                        mediaRouter.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, route)
                        Log.d("AudioRoutingService", "Selected MediaRouter route: $rName")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("AudioRoutingService", "Could not select MediaRouter route", e)
        }
    }

    private fun requestMediaAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { /* maintain focus */ }
                    .build()
                audioFocusRequest = focusRequest
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Exception) {
            Log.w("AudioRoutingService", "Error requesting AudioFocus", e)
        }
    }

    private fun abandonMediaAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w("AudioRoutingService", "Error abandoning AudioFocus", e)
        }
    }

    /**
     * Active A2DP stereo pipeline:
     * Plays a gentle activation chime (to force the Android AudioPolicy to route to A2DP),
     * followed by continuous keep-alive frames so the link doesn't idle out.
     */
    private fun startAacActiveKeepAliveTrack(targetDevice: AudioDeviceInfo?) {
        if (targetDevice == null) {
            Log.w("AudioRoutingService", "startAacActiveKeepAliveTrack: No A2DP targetDevice found! Skipping AudioTrack to prevent phone speaker playback.")
            return
        }
        try {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
            val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
            val bufferSize = maxOf(minBufferSize, 4096)

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
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
                    Log.w("AudioRoutingService", "Could not setPreferredDevice", e)
                }
            }

            track.play()
            audioTrack = track

            keepAliveJob?.cancel()
            keepAliveJob = scope.launch(Dispatchers.IO) {
                // Generate a brief initial activation chime (300ms of soft 520Hz sine wave)
                // This triggers Android AudioPolicy to recognize active audio and switch default A2DP sink
                val toneSamples = (sampleRate * 0.3).toInt()
                val toneBuffer = ShortArray(toneSamples * 2)
                for (i in 0 until toneSamples) {
                    val angle = 2.0 * Math.PI * i / (sampleRate / 520.0)
                    val sample = (sin(angle) * 3500).toInt().toShort() // Very soft volume
                    toneBuffer[i * 2] = sample     // Left
                    toneBuffer[i * 2 + 1] = sample // Right
                }
                track.write(toneBuffer, 0, toneBuffer.size, AudioTrack.WRITE_BLOCKING)

                // Release AudioFocus after chime so music apps have exclusive playback access
                abandonMediaAudioFocus()

                // Follow with gentle silence buffer to keep the A2DP channel warm
                val silence = ByteArray(bufferSize)
                while (isActive) {
                    try {
                        track.write(silence, 0, silence.size)
                        delay(500)
                    } catch (e: Exception) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AudioRoutingService", "Error starting active AAC keep-alive track", e)
        }
    }

    private fun startScoKeepAliveTrack() {
        try {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
            val bufferSize = maxOf(minBufferSize, 2048)

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
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

            track.play()
            audioTrack = track

            keepAliveJob?.cancel()
            keepAliveJob = scope.launch(Dispatchers.IO) {
                val silence = ByteArray(bufferSize)
                while (isActive) {
                    try {
                        track.write(silence, 0, silence.size)
                        delay(50)
                    } catch (e: Exception) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AudioRoutingService", "Error starting SCO keep-alive track", e)
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            var count = 0L
            while (isActive) {
                delay(1000)
                count++
                _serviceState.value = _serviceState.value.copy(elapsedSeconds = count)
            }
        }
    }

    private fun startWatchdog(mode: AudioOutputMode) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(3000)
                try {
                    val btDevice = findBluetoothDevice(mode)
                    _serviceState.value = _serviceState.value.copy(
                        isConnectedToHeadset = (btDevice != null),
                        deviceName = btDevice?.productName?.toString()?.takeIf { it.isNotBlank() } ?: _serviceState.value.deviceName
                    )

                    if (mode == AudioOutputMode.SCO_CALL) {
                        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        }
                        if (!audioManager.isBluetoothScoOn) {
                            audioManager.startBluetoothSco()
                            audioManager.isBluetoothScoOn = true
                        }
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && btDevice != null) {
                            audioTrack?.let { track ->
                                if (track.preferredDevice == null) {
                                    track.setPreferredDevice(btDevice)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("AudioRoutingService", "Watchdog error", e)
                }
            }
        }
    }

    private fun stopAudioTrack() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.w("AudioRoutingService", "AudioTrack release error", e)
        }
        audioTrack = null
    }

    private fun stopRouting() {
        watchdogJob?.cancel()
        watchdogJob = null
        timerJob?.cancel()
        timerJob = null
        stopAudioTrack()
        abandonMediaAudioFocus()

        try {
            if (audioManager.isBluetoothScoOn) {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.w("AudioRoutingService", "Audio reset error", e)
        }

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w("AudioRoutingService", "WakeLock release error", e)
        }

        _serviceState.value = RoutingServiceState(
            isRunning = false,
            elapsedSeconds = 0,
            isConnectedToHeadset = false,
            a2dpSwitchNotice = null
        )
    }

    private fun findBluetoothDevice(mode: AudioOutputMode): AudioDeviceInfo? {
        try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            return if (mode == AudioOutputMode.AAC_A2DP) {
                devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
                }
            } else {
                devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                }
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun createNotification(content: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AudioRoutingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Redmi Buds Audio Fixer")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Release Routing",
                stopPendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bluetooth Audio Routing Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Bluetooth audio routing active in background and when screen is off"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopRouting()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
