package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

data class ShizukuStatus(
    val isInstalled: Boolean = false,
    val isRunning: Boolean = false,
    val isPermissionGranted: Boolean = false,
    val version: Int = 0
)

data class ShizukuExecResult(
    val command: String,
    val output: String,
    val error: String,
    val exitCode: Int,
    val isSuccess: Boolean
)

object ShizukuManager {

    private val SHIZUKU_PACKAGES = listOf(
        "moe.shizuku.privileged.api",
        "moe.shizuku.manager"
    )

    fun checkStatus(context: Context): ShizukuStatus {
        try {
            ShizukuProvider.requestBinderForNonProviderProcess(context)
        } catch (_: Exception) {}

        val isInstalled = SHIZUKU_PACKAGES.any { pkg ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        pkg,
                        PackageManager.PackageInfoFlags.of(0)
                    ) != null
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(pkg, 0) != null
                }
            } catch (_: Exception) {
                false
            }
        }

        val isRunning = try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }

        val isPermissionGranted = if (isRunning) {
            try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }

        val version = if (isRunning) {
            try {
                Shizuku.getVersion()
            } catch (_: Exception) {
                0
            }
        } else 0

        return ShizukuStatus(
            isInstalled = isInstalled,
            isRunning = isRunning,
            isPermissionGranted = isPermissionGranted,
            version = version
        )
    }

    fun openShizukuApp(context: Context): Boolean {
        for (pkg in SHIZUKU_PACKAGES) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return true
            }
        }
        return false
    }

    fun requestPermission(requestCode: Int = 771) {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (e: Exception) {
            Log.e("ShizukuManager", "Failed to request Shizuku permission", e)
        }
    }

    /**
     * Executes an ADB shell command using Shizuku privileged service (UID 2000 shell).
     */
    suspend fun executeCommand(command: String): ShizukuExecResult = withContext(Dispatchers.IO) {
        try {
            if (!Shizuku.pingBinder()) {
                return@withContext ShizukuExecResult(
                    command = command,
                    output = "",
                    error = "Shizuku service is not running. Please start Shizuku first.",
                    exitCode = -1,
                    isSuccess = false
                )
            }

            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return@withContext ShizukuExecResult(
                    command = command,
                    output = "",
                    error = "Shizuku permission not granted. Please authorize in Shizuku.",
                    exitCode = -1,
                    isSuccess = false
                )
            }

            val binder = Shizuku.getBinder()
            val service = IShizukuService.Stub.asInterface(binder)
            val remoteProcess = service.newProcess(arrayOf("sh", "-c", command), null, null)

            val inputStream = ParcelFileDescriptor.AutoCloseInputStream(remoteProcess.inputStream)
            val output = inputStream.bufferedReader().use { it.readText() }

            val errorStream = ParcelFileDescriptor.AutoCloseInputStream(remoteProcess.errorStream)
            val error = errorStream.bufferedReader().use { it.readText() }

            val exitCode = remoteProcess.waitFor()
            remoteProcess.destroy()

            ShizukuExecResult(
                command = command,
                output = output.trim(),
                error = error.trim(),
                exitCode = exitCode,
                isSuccess = (exitCode == 0)
            )
        } catch (e: Exception) {
            ShizukuExecResult(
                command = command,
                output = "",
                error = e.localizedMessage ?: "Unknown execution error",
                exitCode = -1,
                isSuccess = false
            )
        }
    }

    /**
     * Executes deep ADB-level commands via Shizuku to force Android to switch A2DP (AAC) audio route.
     * Also disables A2DP hardware offload, grants system permissions, and triggers MediaRouter2.
     */
    suspend fun fixAacAudioViaShizuku(packageName: String, context: Context): List<ShizukuExecResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ShizukuExecResult>()

        // 1. Grant privileged media & audio routing permissions to this app
        val grantPermCmds = listOf(
            "pm grant $packageName android.permission.MEDIA_CONTENT_CONTROL 2>/dev/null || true",
            "pm grant $packageName android.permission.MEDIA_ROUTING_CONTROL 2>/dev/null || true",
            "pm grant $packageName android.permission.MODIFY_AUDIO_ROUTING 2>/dev/null || true",
            "pm grant $packageName android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true",
            "pm grant $packageName android.permission.BLUETOOTH_CONNECT 2>/dev/null || true"
        )
        for (cmd in grantPermCmds) {
            results.add(executeCommand(cmd))
        }

        // 2. Set global audio routing & disable speakerphone fallback
        val offloadCmd = "cmd audio set-speakerphone-on false 2>/dev/null || true"
        results.add(executeCommand(offloadCmd))

        // 3. Disable LHDC / Optional Codecs globally in system settings to prevent APM error=1 (missing AUDIO_FORMAT_LHDC HAL)
        val settingsCmds = listOf(
            "settings put global bluetooth_a2dp_supports_optional_codecs 0",
            "settings put global bluetooth_a2dp_optional_codecs_enabled 0",
            "settings put global bluetooth_disabled_codecs \"lhdc,lhdc_v1,lhdc_v2,lhdc_v3,lhdc_v5,65536\""
        )
        for (cmd in settingsCmds) {
            results.add(executeCommand(cmd))
        }

        // 4. Query Bluetooth A2DP device address, strictly filtering out dummy 00:00:00:00:00:00
        val getBtAddressCmd = "dumpsys bluetooth_manager | grep -E -o '([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}' | grep -v -i -E '^00:00:00:00:00:00' | head -n 1 || true"
        val addrResult = executeCommand(getBtAddressCmd)
        results.add(addrResult)
        var mac = addrResult.output.lineSequence().firstOrNull()?.trim()

        if (mac.isNullOrBlank() || mac.contains("00:00:00:00:00:00") || mac.length != 17) {
            // Fallback: search dumpsys audio
            val audioAddrCmd = "dumpsys audio | grep -E -o '([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}' | grep -v -i -E '^00:00:00:00:00:00' | head -n 1 || true"
            val audioAddrResult = executeCommand(audioAddrCmd)
            results.add(audioAddrResult)
            mac = audioAddrResult.output.lineSequence().firstOrNull()?.trim()
        }

        // 5. If valid MAC address found, set A2DP device priority and disable optional codecs (LHDC)
        if (!mac.isNullOrBlank() && mac.length == 17 && !mac.contains("00:00:00:00:00:00")) {
            val macUnderscore = mac.replace(":", "_")
            val priorityCmd = "settings put global bluetooth_a2dp_src_priority_$mac 1000 && settings put global bluetooth_a2dp_sink_priority_$mac 1000 && settings put global bluetooth_a2dp_src_priority_$macUnderscore 1000 && settings put global bluetooth_a2dp_sink_priority_$macUnderscore 1000 && settings put secure bluetooth_a2dp_supports_optional_codecs_enabled_$mac 0 && settings put secure bluetooth_a2dp_supports_optional_codecs_enabled_$macUnderscore 0 && settings put global bluetooth_a2dp_supports_optional_codecs_enabled_$mac 0 && settings put global bluetooth_a2dp_supports_optional_codecs_enabled_$macUnderscore 0"
            results.add(executeCommand(priorityCmd))
        }

        // 6. Force audio subsystem to turn off speakerphone routing
        val speakerOffCmd = "cmd audio set-speakerphone-on false 2>/dev/null || true"
        results.add(executeCommand(speakerOffCmd))

        // 7. Force audio system to re-evaluate media session routing
        val reevaluateCmd = "cmd media_session dispatch play-pause && sleep 0.1 && cmd media_session dispatch play-pause"
        results.add(executeCommand(reevaluateCmd))

        // 8. Force-connect A2DP profile via BluetoothA2dp reflection
        val a2dpResult = BluetoothA2dpConnector.forceConnectA2dp(context)
        results.add(
            ShizukuExecResult(
                command = "BluetoothA2dp.forceConnectA2dp()",
                output = a2dpResult.message + "\n" + a2dpResult.logs.joinToString("\n"),
                error = if (!a2dpResult.isSuccess) a2dpResult.message else "",
                exitCode = if (a2dpResult.isSuccess) 0 else 1,
                isSuccess = a2dpResult.isSuccess
            )
        )

        // 9. Call MediaRouter2 transfer in Android app
        val routeTransfer = MediaRouter2Helper.transferToBluetooth(context)
        results.add(
            ShizukuExecResult(
                command = "MediaRouter2.transferTo(bluetooth)",
                output = routeTransfer.message + "\nAvailable Routes:\n" + routeTransfer.availableRoutes.joinToString("\n"),
                error = if (!routeTransfer.isSuccess) routeTransfer.message else "",
                exitCode = if (routeTransfer.isSuccess) 0 else 1,
                isSuccess = routeTransfer.isSuccess
            )
        )

        // 10. Query current audio device routing state
        val diagCmd = "dumpsys audio | grep -E \"(DEVICE_OUT_BLUETOOTH_A2DP|Connected devices|curDevice|sink)\" | head -n 6 || true"
        results.add(executeCommand(diagCmd))

        results
    }

    /**
     * Runs full deep diagnosis of Bluetooth and Audio routing state via Shizuku.
     */
    suspend fun runAudioDiagnostics(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.append("=== Bluetooth & Audio System Telemetry ===\n")

        val r1 = executeCommand("dumpsys bluetooth_manager | grep -E \"(Connected devices|Bonded devices|Active Device|A2dp|Profile: A2DP|State:)\" | head -n 16 || true")
        sb.append("[Bluetooth State]\n").append(r1.output.ifEmpty { "No data" }).append("\n\n")

        val rBuds = executeCommand("dumpsys bluetooth_manager | grep -C 2 -i \"Buds\" | head -n 10 || true")
        if (rBuds.output.isNotBlank()) {
            sb.append("[Buds Profile Details]\n").append(rBuds.output).append("\n\n")
        }

        val r2 = executeCommand("dumpsys audio | grep -E \"(DEVICE_OUT_BLUETOOTH|DEVICE_OUT_SPEAKER|curDevice|mConnectedDevices|devices:)\" | head -n 14 || true")
        sb.append("[Audio Output Routing]\n").append(r2.output.ifEmpty { "No data" }).append("\n\n")

        val r3 = executeCommand("getprop persist.bluetooth.a2dp_offload.disabled")
        sb.append("A2DP Offload Disabled (硬件卸载停用): ").append(r3.output.ifEmpty { "false/default" }).append("\n")

        val r4 = executeCommand("settings get global bluetooth_a2dp_optional_codecs_enabled")
        sb.append("Optional Codecs (AAC/高清编码) Enabled: ").append(r4.output.ifEmpty { "unknown" })

        sb.toString()
    }

    /**
     * Specifically toggles A2DP hardware offload off via Shizuku.
     */
    suspend fun disableA2dpHardwareOffload(): ShizukuExecResult {
        return executeCommand("setprop persist.bluetooth.a2dp_offload.disabled true && setprop persist.sys.bluetooth.a2dp_offload false")
    }

    /**
     * LineageOS (Android 17 / AOSP) Deep Audio Stack Fix:
     * 1. Disables LE Audio (which fails & suppresses A2DP on custom ROMs with Redmi Buds 5 Pro)
     * 2. Disables A2DP Hardware Offload
     * 3. Configures standard software codec fallback
     */
    suspend fun fixLineageOsAospAudio(): List<ShizukuExecResult> = withContext(Dispatchers.IO) {
        val cmds = listOf(
            "settings put global bluetooth_le_audio_masked 1",
            "settings put global bluetooth_a2dp_supports_optional_codecs 0",
            "settings put global bluetooth_a2dp_optional_codecs_enabled 0",
            "settings put global bluetooth_disabled_codecs \"lhdc,lhdc_v1,lhdc_v2,lhdc_v3,lhdc_v5,65536\"",
            "cmd audio set-speakerphone-on false 2>/dev/null || true"
        )
        val results = mutableListOf<ShizukuExecResult>()
        for (cmd in cmds) {
            results.add(executeCommand(cmd))
        }
        results
    }
}
