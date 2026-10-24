package com.example

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.lang.reflect.Field

data class A2dpConnectResult(
    val isSuccess: Boolean,
    val deviceName: String?,
    val macAddress: String?,
    val message: String,
    val logs: List<String>
)

enum class TargetCodec(val typeInt: Int, val displayName: String) {
    AAC(1, "AAC 高清音频 (44.1/48kHz 16bit)"),
    SBC(0, "SBC 标准兼容 (通用基线)")
}

data class LiveCodecInfo(
    val currentCodecName: String,
    val codecType: Int,
    val sampleRateStr: String,
    val bitsPerSampleStr: String,
    val channelModeStr: String,
    val selectableCodecs: List<String>,
    val isOptionalCodecsEnabled: Boolean?,
    val isConnected: Boolean,
    val rawDetails: String
)

object BluetoothA2dpConnector {

    private const val TAG = "BluetoothA2dpConnector"

    /**
     * Queries current active Bluetooth A2DP Codec Status (e.g. AAC, SBC, sample rate, etc.)
     */
    @SuppressLint("MissingPermission")
    suspend fun queryLiveCodecStatus(context: Context, targetDeviceName: String? = null): LiveCodecInfo? = withContext(Dispatchers.IO) {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@withContext null
            if (!adapter.isEnabled) return@withContext null

            val bondedDevices = adapter.bondedDevices?.toList() ?: emptyList()
            val candidate = bondedDevices.firstOrNull { dev ->
                val name = dev.name ?: ""
                (targetDeviceName != null && name.contains(targetDeviceName, ignoreCase = true)) ||
                name.contains("Redmi", ignoreCase = true) ||
                name.contains("Buds", ignoreCase = true) ||
                name.contains("48:73:CB:D2:8D:3D", ignoreCase = true)
            } ?: bondedDevices.firstOrNull() ?: return@withContext null

            val a2dpDeferred = CompletableDeferred<BluetoothProfile?>()
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    if (profile == BluetoothProfile.A2DP) {
                        a2dpDeferred.complete(proxy)
                    }
                }
                override fun onServiceDisconnected(profile: Int) {}
            }
            val gotProxy = adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP)
            if (!gotProxy) return@withContext null

            val a2dpProxy = withTimeoutOrNull(2500) { a2dpDeferred.await() } ?: return@withContext null
            try {
                // Inject Shizuku privilege to avoid SecurityException
                val tempLogs = mutableListOf<String>()
                injectShizukuPrivilege(a2dpProxy, tempLogs)

                val a2dpClass = a2dpProxy.javaClass
                val isConnected = a2dpProxy.getConnectionState(candidate) == BluetoothProfile.STATE_CONNECTED

                var optionalEnabled: Boolean? = null
                try {
                    val isOptMethod = a2dpClass.getMethod("isOptionalCodecsEnabled", BluetoothDevice::class.java)
                    val optVal = isOptMethod.invoke(a2dpProxy, candidate) as? Int
                    optionalEnabled = when (optVal) {
                        1 -> true // OPTIONAL_CODECS_PREF_ENABLED = 1 in AOSP
                        0 -> false // OPTIONAL_CODECS_PREF_DISABLED = 0 in AOSP
                        else -> null
                    }
                } catch (_: Exception) {}

                val getCodecStatusMethod = a2dpClass.getMethod("getCodecStatus", BluetoothDevice::class.java)
                val statusObj = getCodecStatusMethod.invoke(a2dpProxy, candidate) ?: return@withContext null

                val getCodecConfigMethod = statusObj.javaClass.getMethod("getCodecConfig")
                val currentCfg = getCodecConfigMethod.invoke(statusObj) ?: return@withContext null
                val cfgClass = currentCfg.javaClass

                val typeVal = cfgClass.getMethod("getCodecType").invoke(currentCfg) as? Int ?: 0
                val codecName = when (typeVal) {
                    0 -> "SBC"
                    1 -> "AAC"
                    2 -> "aptX"
                    3 -> "aptX HD"
                    4 -> "LDAC"
                    5 -> "LC3"
                    6 -> "Opus"
                    else -> try {
                        cfgClass.getMethod("getCodecName").invoke(currentCfg) as? String ?: "Type $typeVal"
                    } catch (_: Exception) { "Type $typeVal" }
                }

                val sampleRateVal = cfgClass.getMethod("getSampleRate").invoke(currentCfg) as? Int ?: 0
                val sampleRateStr = when (sampleRateVal) {
                    1 -> "44.1 kHz"
                    2 -> "48.0 kHz"
                    4 -> "88.2 kHz"
                    8 -> "96.0 kHz"
                    16 -> "176.4 kHz"
                    32 -> "192.0 kHz"
                    else -> if (sampleRateVal > 1000) "$sampleRateVal Hz" else "44.1 kHz"
                }

                val bitsVal = cfgClass.getMethod("getBitsPerSample").invoke(currentCfg) as? Int ?: 0
                val bitsStr = when (bitsVal) {
                    1 -> "16 bit"
                    2 -> "24 bit"
                    4 -> "32 bit"
                    else -> "16 bit"
                }

                val channelVal = cfgClass.getMethod("getChannelMode").invoke(currentCfg) as? Int ?: 0
                val channelStr = when (channelVal) {
                    1 -> "单声道"
                    2 -> "双声道立体声"
                    else -> "立体声"
                }

                val selectableNames = mutableListOf<String>()
                try {
                    val getSelectableMethod = statusObj.javaClass.getMethod("getCodecsSelectableCapabilities")
                    val selectableList = getSelectableMethod.invoke(statusObj) as? List<*>
                    selectableList?.forEach { item ->
                        if (item != null) {
                            val t = item.javaClass.getMethod("getCodecType").invoke(item) as? Int ?: -1
                            val n = when (t) {
                                0 -> "SBC"
                                1 -> "AAC"
                                2 -> "aptX"
                                3 -> "aptX HD"
                                4 -> "LDAC"
                                5 -> "LC3"
                                else -> item.javaClass.getMethod("getCodecName").invoke(item) as? String ?: "Codec $t"
                            }
                            if (!selectableNames.contains(n)) selectableNames.add(n)
                        }
                    }
                } catch (_: Exception) {}

                LiveCodecInfo(
                    currentCodecName = codecName,
                    codecType = typeVal,
                    sampleRateStr = sampleRateStr,
                    bitsPerSampleStr = bitsStr,
                    channelModeStr = channelStr,
                    selectableCodecs = selectableNames,
                    isOptionalCodecsEnabled = optionalEnabled,
                    isConnected = isConnected,
                    rawDetails = currentCfg.toString()
                )
            } finally {
                try {
                    adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dpProxy)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Attempts to force-connect Android's Bluetooth A2DP (Media Audio) profile
     * to the paired Redmi Buds headset, injecting either AAC (Type 1) or SBC (Type 0).
     */
    @SuppressLint("MissingPermission")
    suspend fun forceConnectA2dp(
        context: Context,
        targetDeviceName: String? = null,
        targetCodec: TargetCodec = TargetCodec.AAC
    ): A2dpConnectResult = withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) {
                logs.add("BLUETOOTH_CONNECT permission not granted.")
                return@withContext A2dpConnectResult(
                    isSuccess = false,
                    deviceName = null,
                    macAddress = null,
                    message = "BLUETOOTH_CONNECT permission not granted.",
                    logs = logs
                )
            }
        }

        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (e: Exception) {
            logs.add("Could not get BluetoothAdapter: ${e.message}")
            null
        }

        if (adapter == null || !adapter.isEnabled) {
            logs.add("Bluetooth adapter is disabled or unavailable.")
            return@withContext A2dpConnectResult(
                isSuccess = false,
                deviceName = null,
                macAddress = null,
                message = "Bluetooth is turned off. Please turn on Bluetooth first.",
                logs = logs
            )
        }

        val bondedDevices = try {
            adapter.bondedDevices?.toList() ?: emptyList()
        } catch (e: Exception) {
            logs.add("Error getting bonded devices: ${e.message}")
            emptyList()
        }

        logs.add("Found ${bondedDevices.size} bonded devices in system.")

        val candidate = bondedDevices.firstOrNull { dev ->
            val name = dev.name ?: ""
            (targetDeviceName != null && name.contains(targetDeviceName, ignoreCase = true)) ||
            name.contains("Redmi", ignoreCase = true) ||
            name.contains("Buds", ignoreCase = true) ||
            name.contains("Xiaomi", ignoreCase = true) ||
            name.contains("耳机", ignoreCase = true) ||
            name.contains("Bluetooth", ignoreCase = true)
        } ?: bondedDevices.firstOrNull()

        if (candidate == null) {
            logs.add("No paired Bluetooth headset found. Please pair Redmi Buds in system Bluetooth settings first.")
            return@withContext A2dpConnectResult(
                isSuccess = false,
                deviceName = null,
                macAddress = null,
                message = "No paired Bluetooth headset found. Please pair Redmi Buds in system settings.",
                logs = logs
            )
        }

        val devName = candidate.name ?: "Bluetooth Device"
        val devMac = candidate.address ?: "Unknown MAC"
        logs.add("Target candidate device: $devName ($devMac)")
        logs.add("🎯 目标目标编解码器: ${targetCodec.displayName}")

        val a2dpDeferred = CompletableDeferred<BluetoothProfile?>()

        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                if (profile == BluetoothProfile.A2DP) {
                    logs.add("BluetoothA2dp proxy service connected.")
                    a2dpDeferred.complete(proxy)
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.A2DP) {
                    logs.add("BluetoothA2dp proxy service disconnected.")
                }
            }
        }

        val gotProxy = adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP)
        if (!gotProxy) {
            logs.add("getProfileProxy(A2DP) returned false.")
            return@withContext A2dpConnectResult(
                isSuccess = false,
                deviceName = devName,
                macAddress = devMac,
                message = "System refused Bluetooth A2DP proxy connection.",
                logs = logs
            )
        }

        val a2dpProxy = withTimeoutOrNull(4000) { a2dpDeferred.await() }

        if (a2dpProxy == null) {
            logs.add("Timed out waiting for BluetoothA2dp proxy service.")
            return@withContext A2dpConnectResult(
                isSuccess = false,
                deviceName = devName,
                macAddress = devMac,
                message = "Timed out waiting for system BluetoothA2dp service.",
                logs = logs
            )
        }

        var connectCalled = false
        var setActiveCalled = false

        try {
            // 0. Inject Shizuku Shell (UID 2000) privileged binder to bypass BLUETOOTH_PRIVILEGED SecurityException
            injectShizukuPrivilege(a2dpProxy, logs)
            injectShizukuPrivilegeToAdapter(adapter, logs)

            val a2dpClass = a2dpProxy.javaClass

            // 1. Codec Management based on TargetCodec
            if (targetCodec == TargetCodec.AAC) {
                // For AAC: Enable optional codecs (value 1 = OPTIONAL_CODECS_PREF_ENABLED, 0 = DISABLED)
                try {
                    val enableOptionalMethod = a2dpClass.getMethod("enableOptionalCodecs", BluetoothDevice::class.java)
                    enableOptionalMethod.isAccessible = true
                    val res = enableOptionalMethod.invoke(a2dpProxy, candidate)
                    logs.add("🟢 激活可选高清编解码 (AAC) 支持: enableOptionalCodecs = $res")
                } catch (e: Exception) {
                    logs.add("enableOptionalCodecs 提示: ${e.message}")
                }

                try {
                    val setOptionalCodecsMethod = a2dpClass.getMethod("setOptionalCodecsEnabled", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                    setOptionalCodecsMethod.isAccessible = true
                    val res = setOptionalCodecsMethod.invoke(a2dpProxy, candidate, 1) // 1 = OPTIONAL_CODECS_PREF_ENABLED
                    logs.add("🟢 开启高清音频开关 (AAC): setOptionalCodecsEnabled(ENABLED=1) = $res")
                } catch (e: Exception) {
                    logs.add("setOptionalCodecsEnabled 执行提示: ${e.message}")
                }
            } else {
                // For SBC: Disable optional codecs (value 0 = OPTIONAL_CODECS_PREF_DISABLED)
                try {
                    val disableOptionalMethod = a2dpClass.getMethod("disableOptionalCodecs", BluetoothDevice::class.java)
                    disableOptionalMethod.isAccessible = true
                    val res = disableOptionalMethod.invoke(a2dpProxy, candidate)
                    logs.add("🟡 已禁用可选编解码能力 (锁定 SBC 基础编码): disableOptionalCodecs = $res")
                } catch (_: Exception) {}

                try {
                    val setOptionalCodecsMethod = a2dpClass.getMethod("setOptionalCodecsEnabled", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                    setOptionalCodecsMethod.isAccessible = true
                    val res = setOptionalCodecsMethod.invoke(a2dpProxy, candidate, 0) // 0 = OPTIONAL_CODECS_PREF_DISABLED
                    logs.add("🟡 已关闭高清音频开关: setOptionalCodecsEnabled(DISABLED=0) = $res")
                } catch (_: Exception) {}
            }

            // 2. Multi-Codec Priority Configuration:
            // Proactively DISABLE LHDC, LDAC, aptX, and LC3 to prevent AudioPolicy APM error=1 crash!
            // LineageOS lacks proprietary vendor LHDC HAL, causing createDevice APM crash if LHDC is selected.
            val codecConfigClass = Class.forName("android.bluetooth.BluetoothCodecConfig")
            val setCodecConfigMethod = try {
                a2dpClass.getMethod("setCodecConfigPreference", BluetoothDevice::class.java, codecConfigClass).apply {
                    isAccessible = true
                }
            } catch (e: Exception) {
                logs.add("setCodecConfigPreference 反射获取失败: ${e.message}")
                null
            }

            fun buildCodecConfig(typeInt: Int, priority: Int, sampleRate: Int = 1, bits: Int = 1, channel: Int = 2): Any? {
                try {
                    val builderClass = Class.forName("android.bluetooth.BluetoothCodecConfig\$Builder")
                    val builder = builderClass.getDeclaredConstructor().newInstance()
                    builderClass.getMethod("setCodecType", Int::class.javaPrimitiveType).invoke(builder, typeInt)
                    builderClass.getMethod("setCodecPriority", Int::class.javaPrimitiveType).invoke(builder, priority)
                    builderClass.getMethod("setSampleRate", Int::class.javaPrimitiveType).invoke(builder, sampleRate)
                    builderClass.getMethod("setBitsPerSample", Int::class.javaPrimitiveType).invoke(builder, bits)
                    builderClass.getMethod("setChannelMode", Int::class.javaPrimitiveType).invoke(builder, channel)
                    return builderClass.getMethod("build").invoke(builder)
                } catch (_: Exception) {
                    try {
                        val constructor = codecConfigClass.getConstructor(
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Long::class.javaPrimitiveType,
                            Long::class.javaPrimitiveType,
                            Long::class.javaPrimitiveType,
                            Long::class.javaPrimitiveType
                        )
                        return constructor.newInstance(typeInt, priority, sampleRate, bits, channel, 0L, 0L, 0L, 0L)
                    } catch (_: Exception) {
                        return null
                    }
                }
            }

            if (setCodecConfigMethod != null) {
                if (targetCodec == TargetCodec.AAC) {
                    // 2a. First, systematically disable conflicting codecs:
                    // LHDC variants: 65536 (0x10000), 7 (V5), 8 (V3), 9 (V2), 10 (V1)
                    // LDAC: 4, aptX HD: 3, aptX: 2, LC3: 5, Opus: 6
                    val codecsToDisable = listOf(65536, 7, 8, 9, 10, 4, 3, 2, 5, 6)
                    for (cType in codecsToDisable) {
                        val disabledObj = buildCodecConfig(typeInt = cType, priority = -1, sampleRate = 0, bits = 0, channel = 0)
                        if (disabledObj != null) {
                            try {
                                setCodecConfigMethod.invoke(a2dpProxy, candidate, disabledObj)
                            } catch (_: Exception) {}
                        }
                    }
                    logs.add("🛡️ 已向底层蓝牙协议栈下发 LHDC/LDAC/aptX 禁用指令 (Priority = -1，避免 AudioPolicy 崩溃)")

                    // 2b. Set SBC to fallback priority (1)
                    val sbcFallback = buildCodecConfig(typeInt = 0, priority = 1, sampleRate = 1, bits = 1, channel = 2)
                    if (sbcFallback != null) {
                        try {
                            setCodecConfigMethod.invoke(a2dpProxy, candidate, sbcFallback)
                        } catch (_: Exception) {}
                    }

                    // 2c. Set AAC to HIGHEST priority (1,000,000)
                    val aacHighest = buildCodecConfig(typeInt = 1, priority = 1000000, sampleRate = 1, bits = 1, channel = 2)
                    if (aacHighest != null) {
                        try {
                            val res = setCodecConfigMethod.invoke(a2dpProxy, candidate, aacHighest)
                            logs.add("🟢 已注入 AAC 极高优先级 (1000000 / 44.1kHz 16bit 立体声): $res")
                        } catch (e: Exception) {
                            logs.add("注入 AAC 提示: ${e.message}")
                        }
                    }
                } else {
                    // SBC Mode: Disable AAC and optional codecs
                    val aacDisabled = buildCodecConfig(typeInt = 1, priority = -1, sampleRate = 0, bits = 0, channel = 0)
                    if (aacDisabled != null) {
                        try {
                            setCodecConfigMethod.invoke(a2dpProxy, candidate, aacDisabled)
                        } catch (_: Exception) {}
                    }
                    val sbcHighest = buildCodecConfig(typeInt = 0, priority = 1000000, sampleRate = 1, bits = 1, channel = 2)
                    if (sbcHighest != null) {
                        try {
                            val res = setCodecConfigMethod.invoke(a2dpProxy, candidate, sbcHighest)
                            logs.add("🟡 已锁定 SBC 编解码极高优先级 (1000000): $res")
                        } catch (e: Exception) {
                            logs.add("注入 SBC 提示: ${e.message}")
                        }
                    }
                }
            }

            // 3. Set Connection Policy & Auto-connect priority
            try {
                val setPolicyMethod = a2dpClass.getMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                setPolicyMethod.isAccessible = true
                val res = setPolicyMethod.invoke(a2dpProxy, candidate, 100) as? Boolean
                logs.add("Invoked BluetoothA2dp.setConnectionPolicy(ALLOWED): result = $res")
            } catch (_: Exception) {
                try {
                    val setPriorityMethod = a2dpClass.getMethod("setPriority", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                    setPriorityMethod.isAccessible = true
                    val res = setPriorityMethod.invoke(a2dpProxy, candidate, 1000) as? Boolean
                    logs.add("Invoked BluetoothA2dp.setPriority(AUTO_CONNECT): result = $res")
                } catch (_: Exception) {}
            }

            // 4. In-place connection handling:
            // If already connected, do NOT disconnect/reconnect! AOSP performs in-place AVDTP Reconfigure.
            // Disconnecting triggers an unneeded link teardown and SDP attribute cache misses.
            val currentConnState = a2dpProxy.getConnectionState(candidate)
            if (currentConnState != BluetoothProfile.STATE_CONNECTED) {
                logs.add("设备当前未在 A2DP 媒体通道连接，正在发起连接...")
                try {
                    val connectMethod = a2dpClass.getMethod("connect", BluetoothDevice::class.java)
                    connectMethod.isAccessible = true
                    val res = connectMethod.invoke(a2dpProxy, candidate) as? Boolean
                    connectCalled = (res == true)
                    logs.add("Invoked BluetoothA2dp.connect($devName): result = $res")
                } catch (e: Exception) {
                    logs.add("BluetoothA2dp.connect reflection error: ${e.message}")
                }
            } else {
                logs.add("设备已处于 A2DP 连接中，协议栈已原位热重载编解码偏好。")
                connectCalled = true
            }

            // Pause for codec renegotiation
            kotlinx.coroutines.delay(500)

            // 5. Try invoking hidden setActiveDevice(BluetoothDevice)
            try {
                val setActiveMethod = a2dpClass.getMethod("setActiveDevice", BluetoothDevice::class.java)
                setActiveMethod.isAccessible = true
                val res = setActiveMethod.invoke(a2dpProxy, candidate) as? Boolean
                setActiveCalled = (res == true)
                logs.add("Invoked BluetoothA2dp.setActiveDevice($devName): result = $res")
            } catch (e: Exception) {
                logs.add("BluetoothA2dp.setActiveDevice reflection error: ${e.message}")
            }

            // 6. Try on BluetoothAdapter itself: setActiveDevice(BluetoothDevice, int)
            try {
                val adapterClass = adapter.javaClass
                val setActiveAdapterMethod = adapterClass.getMethod("setActiveDevice", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                setActiveAdapterMethod.isAccessible = true
                val res = setActiveAdapterMethod.invoke(adapter, candidate, 0) as? Boolean
                logs.add("Invoked BluetoothAdapter.setActiveDevice(AUDIO): result = $res")
            } catch (_: Exception) {}

            // 7. Query active codec info after connection
            try {
                val getCodecStatusMethod = a2dpClass.getMethod("getCodecStatus", BluetoothDevice::class.java)
                getCodecStatusMethod.isAccessible = true
                val statusObj = getCodecStatusMethod.invoke(a2dpProxy, candidate)
                if (statusObj != null) {
                    val getCodecConfigMethod = statusObj.javaClass.getMethod("getCodecConfig")
                    val currentCfg = getCodecConfigMethod.invoke(statusObj)
                    logs.add("当前活跃编解码配置: $currentCfg")
                }
            } catch (_: Exception) {}

            // 8. Query connection state and wait for A2DP state changes
            try {
                kotlinx.coroutines.delay(1000)
                val state = a2dpProxy.getConnectionState(candidate)
                val stateName = when (state) {
                    BluetoothProfile.STATE_CONNECTED -> "CONNECTED (已连接成功 - 媒体通道可用！)"
                    BluetoothProfile.STATE_CONNECTING -> "CONNECTING (正在连接中...)"
                    BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING (正在断开)"
                    BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED (未连接 - 媒体通道未建立)"
                    else -> "State $state"
                }
                logs.add("A2DP 连接状态: $stateName")

                val connectedDevs = a2dpProxy.connectedDevices
                logs.add("当前已连接 A2DP 设备: ${connectedDevs.map { "${it.name} (${it.address})" }}")
            } catch (e: Exception) {
                logs.add("查询 A2DP 状态提示: ${e.message}")
            }

        } catch (e: Exception) {
            logs.add("Reflection execution error: ${e.localizedMessage}")
        } finally {
            try {
                adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dpProxy)
            } catch (_: Exception) {}
        }

        A2dpConnectResult(
            isSuccess = connectCalled || setActiveCalled,
            deviceName = devName,
            macAddress = devMac,
            message = if (connectCalled || setActiveCalled) {
                "Sent force-connect & active-device command for $devName [${targetCodec.name}]"
            } else {
                "Command dispatched to Bluetooth stack for $devName"
            },
            logs = logs
        )
    }

    private fun injectShizukuPrivilege(a2dpProxy: BluetoothProfile, logs: MutableList<String>) {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            logs.add("Shizuku 未授权或未运行，无法注入 Shell (UID 2000) 特权 Binder。")
            return
        }

        try {
            // Use Meta-Reflection (Double Reflection) to bypass Android 14/15/17 hidden API blocked list
            val getDeclaredFieldMethod = Class::class.java.getDeclaredMethod("getDeclaredField", String::class.java)
            getDeclaredFieldMethod.isAccessible = true

            var clazz: Class<*>? = a2dpProxy.javaClass
            var serviceField: Field? = null
            while (clazz != null && serviceField == null) {
                try {
                    serviceField = getDeclaredFieldMethod.invoke(clazz, "mService") as? Field
                } catch (_: Exception) {
                    clazz = clazz.superclass
                }
            }

            if (serviceField == null) {
                logs.add("未在 BluetoothA2dp 中找到 mService 字段")
                return
            }

            serviceField.isAccessible = true
            val realService = serviceField.get(a2dpProxy) ?: run {
                logs.add("BluetoothA2dp.mService 尚未连接")
                return
            }

            val asBinderMethod = realService.javaClass.getMethod("asBinder")
            val realBinder = asBinderMethod.invoke(realService) as android.os.IBinder

            // Wrap with ShizukuBinderWrapper so transactions execute as UID 2000 (Shell)
            val wrappedBinder = ShizukuBinderWrapper(realBinder)
            val stubClass = Class.forName("android.bluetooth.IBluetoothA2dp\$Stub")
            val privilegedService = stubClass.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, wrappedBinder)

            serviceField.set(a2dpProxy, privilegedService)
            logs.add("🟢 成功注入 ShizukuBinderWrapper！已接管 BluetoothA2dp 为 UID 2000 (BLUETOOTH_PRIVILEGED) 特权。")
        } catch (e: Exception) {
            logs.add("⚠️ 注入 Shizuku 特权代理异常: ${e.message}")
        }
    }

    private fun injectShizukuPrivilegeToAdapter(adapter: BluetoothAdapter, logs: MutableList<String>) {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
        try {
            val getDeclaredFieldMethod = Class::class.java.getDeclaredMethod("getDeclaredField", String::class.java)
            getDeclaredFieldMethod.isAccessible = true

            var clazz: Class<*>? = adapter.javaClass
            var serviceField: Field? = null
            while (clazz != null && serviceField == null) {
                try {
                    serviceField = getDeclaredFieldMethod.invoke(clazz, "mService") as? Field
                } catch (_: Exception) {
                    clazz = clazz.superclass
                }
            }
            if (serviceField != null) {
                serviceField.isAccessible = true
                val realService = serviceField.get(adapter)
                if (realService != null) {
                    val asBinderMethod = realService.javaClass.getMethod("asBinder")
                    val realBinder = asBinderMethod.invoke(realService) as android.os.IBinder
                    val wrappedBinder = ShizukuBinderWrapper(realBinder)
                    val stubClass = Class.forName("android.bluetooth.IBluetooth\$Stub")
                    val privilegedService = stubClass.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, wrappedBinder)
                    serviceField.set(adapter, privilegedService)
                    logs.add("🟢 成功注入 BluetoothAdapter 特权 Binder！")
                }
            }
        } catch (_: Exception) {}
    }
}
