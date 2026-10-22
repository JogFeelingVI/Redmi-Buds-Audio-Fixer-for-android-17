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

object BluetoothA2dpConnector {

    private const val TAG = "BluetoothA2dpConnector"

    /**
     * Attempts to force-connect Android's Bluetooth A2DP (Media Audio) profile
     * to the paired Redmi Buds headset using reflection on the hidden BluetoothA2dp APIs.
     */
    @SuppressLint("MissingPermission")
    suspend fun forceConnectA2dp(context: Context, targetDeviceName: String? = null): A2dpConnectResult = withContext(Dispatchers.IO) {
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

        // Find candidate device: matching target name, "Redmi", "Buds", or any headset
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

            // 1. Disable LHDC / Optional Codecs under UID 2000 privilege (avoids LineageOS APM error=1)
            try {
                val setOptionalCodecsMethod = a2dpClass.getMethod("setOptionalCodecsEnabled", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                setOptionalCodecsMethod.isAccessible = true
                val res = setOptionalCodecsMethod.invoke(a2dpProxy, candidate, 0) // 0 = OPTIONAL_CODECS_PREF_DISABLED
                logs.add("🟢 已在 UID 2000 特权下禁用可选高清编码 (LHDC): setOptionalCodecsEnabled(DISABLED) = $res")
            } catch (e: Exception) {
                logs.add("setOptionalCodecsEnabled 执行提示: ${e.message}")
            }

            try {
                val disableOptionalMethod = a2dpClass.getMethod("disableOptionalCodecs", BluetoothDevice::class.java)
                disableOptionalMethod.isAccessible = true
                val res = disableOptionalMethod.invoke(a2dpProxy, candidate)
                logs.add("🟢 已调用 disableOptionalCodecs(candidate) 彻底关闭 LHDC: result = $res")
            } catch (_: Exception) {}

            // 2. Configure A2DP codec preference to AAC (SOURCE_CODEC_TYPE_AAC = 1, priority = 1000000)
            try {
                val codecConfigClass = Class.forName("android.bluetooth.BluetoothCodecConfig")
                var codecConfigObj: Any? = null
                try {
                    val builderClass = Class.forName("android.bluetooth.BluetoothCodecConfig\$Builder")
                    val builder = builderClass.getDeclaredConstructor().newInstance()
                    builderClass.getMethod("setCodecType", Int::class.javaPrimitiveType).invoke(builder, 1) // 1 = AAC
                    builderClass.getMethod("setCodecPriority", Int::class.javaPrimitiveType).invoke(builder, 1000000) // HIGHEST
                    codecConfigObj = builderClass.getMethod("build").invoke(builder)
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
                        codecConfigObj = constructor.newInstance(1, 1000000, 0, 0, 0, 0L, 0L, 0L, 0L)
                    } catch (_: Exception) {}
                }

                if (codecConfigObj != null) {
                    val setCodecConfigMethod = a2dpClass.getMethod(
                        "setCodecConfigPreference",
                        BluetoothDevice::class.java,
                        codecConfigClass
                    )
                    setCodecConfigMethod.isAccessible = true
                    val res = setCodecConfigMethod.invoke(a2dpProxy, candidate, codecConfigObj)
                    logs.add("🟢 已向蓝牙栈注入 AAC 优先编解码配置: setCodecConfigPreference(AAC) = $res")
                }
            } catch (e: Exception) {
                logs.add("setCodecConfigPreference 执行提示: ${e.message}")
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

            // 4. Clean A2DP re-negotiation: Disconnect and Reconnect A2DP profile so AAC takes effect cleanly
            try {
                val disconnectMethod = a2dpClass.getMethod("disconnect", BluetoothDevice::class.java)
                disconnectMethod.isAccessible = true
                disconnectMethod.invoke(a2dpProxy, candidate)
                logs.add("已触发临时重置 A2DP 通道，准备以 AAC 重建...")
                kotlinx.coroutines.delay(400)
            } catch (_: Exception) {}

            try {
                val connectMethod = a2dpClass.getMethod("connect", BluetoothDevice::class.java)
                connectMethod.isAccessible = true
                val res = connectMethod.invoke(a2dpProxy, candidate) as? Boolean
                connectCalled = (res == true)
                logs.add("Invoked BluetoothA2dp.connect($devName): result = $res")
            } catch (e: Exception) {
                logs.add("BluetoothA2dp.connect reflection error: ${e.message}")
            }

            // Pause for codec renegotiation from LHDC to AAC
            kotlinx.coroutines.delay(600)

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

            // 7. Query active codec info
            try {
                val getCodecStatusMethod = a2dpClass.getMethod("getCodecStatus", BluetoothDevice::class.java)
                getCodecStatusMethod.isAccessible = true
                val statusObj = getCodecStatusMethod.invoke(a2dpProxy, candidate)
                if (statusObj != null) {
                    val getCodecConfigMethod = statusObj.javaClass.getMethod("getCodecConfig")
                    val currentCfg = getCodecConfigMethod.invoke(statusObj)
                    logs.add("当前活跃编解码状态: $currentCfg")
                }
            } catch (_: Exception) {}

            // 5. Query connection state and wait for A2DP state changes
            try {
                kotlinx.coroutines.delay(1200)
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
                "Sent force-connect & active-device command for $devName"
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
