package com.example

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsBluetooth
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.RedmiBudsAudioFixerTheme
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.AmberWarning
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val systemDark = isSystemInDarkTheme()
            var isDarkTheme by remember { mutableStateOf(systemDark) }

            RedmiBudsAudioFixerTheme(darkTheme = isDarkTheme) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        AudioFixerTopBar(
                            isDarkTheme = isDarkTheme,
                            onToggleTheme = { isDarkTheme = !isDarkTheme }
                        )
                    }
                ) { innerPadding ->
                    SimplifiedAudioFixerScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioFixerTopBar(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Headphones,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Redmi Buds 音频修复助手",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "LineageOS (Android 17) 专用精简版",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.5.sp
                    )
                }
            }
        },
        actions = {
            IconButton(
                onClick = onToggleTheme,
                modifier = Modifier.testTag("theme_toggle_button")
            ) {
                Icon(
                    imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = "Toggle Theme",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.statusBarsPadding()
    )
}

@Composable
fun SimplifiedAudioFixerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Bluetooth permissions
    val btPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needsPerm = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
            if (needsPerm) {
                btPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                    )
                )
            }
        }
    }

    // Dynamic output detection
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var outputDevices by remember { mutableStateOf(audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) }
    val hasA2dp = outputDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }

    DisposableEffect(Unit) {
        val routingManager = AudioRoutingManager(context)
        val callback = routingManager.registerDeviceCallback {
            outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }
        onDispose {
            routingManager.unregisterDeviceCallback(callback)
        }
    }

    // Shizuku status
    var shizukuStatus by remember { mutableStateOf(ShizukuManager.checkStatus(context)) }

    DisposableEffect(Unit) {
        val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            shizukuStatus = shizukuStatus.copy(
                isPermissionGranted = (grantResult == PackageManager.PERMISSION_GRANTED)
            )
        }
        val binderReceivedListener = Shizuku.OnBinderReceivedListener {
            shizukuStatus = ShizukuManager.checkStatus(context)
        }

        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderReceivedListener(binderReceivedListener)
        } catch (_: Exception) {}

        onDispose {
            try {
                Shizuku.removeRequestPermissionResultListener(permissionListener)
                Shizuku.removeBinderReceivedListener(binderReceivedListener)
            } catch (_: Exception) {}
        }
    }

    // Action states
    var isAacExecuting by remember { mutableStateOf(false) }
    var aacLog by remember { mutableStateOf<String?>(null) }
    var aacSuccess by remember { mutableStateOf<Boolean?>(null) }

    var isTestTonePlaying by remember { mutableStateOf(false) }
    var testToneResult by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        // 1. Status Card: Device & Privileges
        DeviceAndPrivilegeCard(
            hasA2dp = hasA2dp,
            shizukuStatus = shizukuStatus,
            onRequestShizuku = { ShizukuManager.requestPermission() },
            onRefresh = {
                shizukuStatus = ShizukuManager.checkStatus(context)
                outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // 2. Simplified Codec & Sound Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "编解码控制与发声测试",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "针对无法通过常规设置启用 AAC 的问题：一键屏蔽引发底层 APM 崩溃的 LHDC 并强行锁定 AAC 优先级，随后可直接测试发声。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Section 1: AAC / SBC Switching
                PipelineStepRow(
                    stepNumber = "1",
                    title = "编解码切换 (屏蔽 LHDC)",
                    description = "屏蔽缺失驱动的 LHDC，注入 AAC 极高优先级 (1000000)，接管底层蓝牙协议栈握手",
                    isExecuting = isAacExecuting,
                    isSuccess = aacSuccess,
                    customContent = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        isAacExecuting = true
                                        aacLog = null
                                        val mac = "48:73:CB:D2:8D:3D"
                                        val macU = "48_73_CB_D2_8D_3D"
                                        val cmds = listOf(
                                            "settings put global hidden_api_policy 1",
                                            "settings put global hidden_api_policy_p_apps 1",
                                            "settings put global hidden_api_policy_pre_p_apps 1",
                                            "settings put global bluetooth_a2dp_supports_optional_codecs 1",
                                            "settings put global bluetooth_a2dp_optional_codecs_enabled 1",
                                            "settings put global bluetooth_a2dp_supports_optional_hd_audio 1",
                                            "settings put global bluetooth_a2dp_optional_hd_audio_disabled 0",
                                            "settings put global bluetooth_disabled_codecs \"lhdc,lhdc_v1,lhdc_v2,lhdc_v3,lhdc_v5,65536,ldac\"",
                                            "settings put global bluetooth_a2dp_disabled_codecs \"lhdc,lhdc_v1,lhdc_v2,lhdc_v3,lhdc_v5,65536,ldac\"",
                                            "device_config put bluetooth a2dp_optional_codecs_enabled true 2>/dev/null || true",
                                            "settings put secure bluetooth_a2dp_supports_optional_codecs_enabled_$mac 1",
                                            "settings put secure bluetooth_a2dp_supports_optional_codecs_enabled_$macU 1",
                                            "settings put global bluetooth_a2dp_supports_optional_codecs_enabled_$mac 1",
                                            "settings put global bluetooth_a2dp_supports_optional_codecs_enabled_$macU 1",
                                            "settings put global bluetooth_a2dp_supports_optional_hd_audio_$mac 1",
                                            "settings put global bluetooth_a2dp_supports_optional_hd_audio_disabled_$mac 0",
                                            "settings put global bluetooth_a2dp_supports_optional_hd_audio_$macU 1",
                                            "settings put global bluetooth_a2dp_supports_optional_hd_audio_disabled_$macU 0",
                                            "settings put global bluetooth_a2dp_offload_disabled 1",
                                            "cmd audio set-speakerphone-on false 2>/dev/null || true"
                                        ).joinToString(" && ")
                                        ShizukuManager.executeCommand(cmds)

                                        // Force A2DP reflection connect with AAC and LHDC disabled
                                        val a2dpRes = BluetoothA2dpConnector.forceConnectA2dp(context, "Redmi Buds 5 Pro", TargetCodec.AAC)
                                        isAacExecuting = false
                                        aacSuccess = a2dpRes.isSuccess
                                        aacLog = buildString {
                                            append("🎉 已写入 AAC 策略并下发 LHDC 屏蔽规则 (避免 AudioPolicy APM 崩溃)！\n")
                                            append(a2dpRes.logs.joinToString("\n"))
                                        }
                                    }
                                },
                                enabled = !isAacExecuting,
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                if (isAacExecuting) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("正在切换编解码...", fontSize = 11.5.sp)
                                } else {
                                    Text("🚀 切换并锁定 AAC 高清模式 (屏蔽 LHDC)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        isAacExecuting = true
                                        aacLog = null
                                        val mac = "48:73:CB:D2:8D:3D"
                                        val macU = "48_73_CB_D2_8D_3D"
                                        val cmds = listOf(
                                            "settings put global hidden_api_policy 1",
                                            "settings put global bluetooth_a2dp_supports_optional_codecs 0",
                                            "settings put global bluetooth_a2dp_optional_codecs_enabled 0",
                                            "settings put secure bluetooth_a2dp_supports_optional_codecs_enabled_$mac 0",
                                            "settings put secure bluetooth_a2dp_supports_optional_codecs_enabled_$macU 0",
                                            "cmd audio set-speakerphone-on false 2>/dev/null || true"
                                        ).joinToString(" && ")
                                        ShizukuManager.executeCommand(cmds)

                                        // Force A2DP reflection connect with SBC
                                        val a2dpRes = BluetoothA2dpConnector.forceConnectA2dp(context, "Redmi Buds 5 Pro", TargetCodec.SBC)
                                        isAacExecuting = false
                                        aacSuccess = a2dpRes.isSuccess
                                        aacLog = buildString {
                                            append("🟡 已锁定 SBC 基础编码兼容模式！\n")
                                            append(a2dpRes.logs.joinToString("\n"))
                                        }
                                    }
                                },
                                enabled = !isAacExecuting,
                                modifier = Modifier.fillMaxWidth().height(34.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("🛡️ 切换回 SBC 兼容模式", fontSize = 11.sp)
                            }
                        }
                    }
                )

                if (aacLog != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = aacLog ?: "",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Section 2: Sound Test
                PipelineStepRow(
                    stepNumber = "2",
                    title = "立体声发声测试 (验证耳麦物理发声)",
                    description = "播放 1.5 秒双声道独立提示音，强制使用蓝牙通路，严禁扬声器外放",
                    isExecuting = isTestTonePlaying,
                    isSuccess = if (hasA2dp) true else null,
                    actionText = if (isTestTonePlaying) "播放中..." else "发声测试",
                    onAction = {
                        coroutineScope.launch {
                            isTestTonePlaying = true
                            testToneResult = null
                            val success = AudioRoutingService.playAacStereoTest(context)
                            isTestTonePlaying = false
                            testToneResult = if (success) {
                                "🔊 已向耳麦注入测试音！左右耳是否听到清晰的提示声？"
                            } else {
                                "⚠️ 未检测到已激活的 A2DP 通道，为了防止扬声器扰民已主动放弃外放。请先点击上方的「切换并锁定 AAC 高清模式」！"
                            }
                        }
                    }
                )

                if (testToneResult != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (hasA2dp) SuccessGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = testToneResult ?: "",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (hasA2dp) SuccessGreen else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceAndPrivilegeCard(
    hasA2dp: Boolean,
    shizukuStatus: ShizukuStatus,
    onRequestShizuku: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 420.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.BluetoothConnected,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "设备状态与运行环境",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // A2DP Channel Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "A2DP 媒体音频通道",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (hasA2dp) "🟢 已挂载激活 (耳机媒体在线)" else "🔴 未挂载 (外放诱因)",
                        fontSize = 10.5.sp,
                        color = if (hasA2dp) SuccessGreen else MaterialTheme.colorScheme.error
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = (if (hasA2dp) SuccessGreen else MaterialTheme.colorScheme.error).copy(alpha = 0.15f)
                ) {
                    Text(
                        text = if (hasA2dp) "已生效" else "未连通",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (hasA2dp) SuccessGreen else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Shizuku Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Shizuku ADB 特权通道",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (shizukuStatus.isRunning && shizukuStatus.isPermissionGranted) {
                            "🟢 已就绪 (Shell UID 2000)"
                        } else {
                            "⚠️ 需授权 Shizuku 才能免 Root 注入配置"
                        },
                        fontSize = 10.5.sp,
                        color = if (shizukuStatus.isRunning && shizukuStatus.isPermissionGranted) SuccessGreen else MaterialTheme.colorScheme.error
                    )
                }

                if (!shizukuStatus.isPermissionGranted && shizukuStatus.isRunning) {
                    Button(
                        onClick = onRequestShizuku,
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("授权", fontSize = 10.5.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun PipelineStepRow(
    stepNumber: String,
    title: String,
    description: String,
    isExecuting: Boolean = false,
    isSuccess: Boolean? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    customContent: (@Composable () -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            when (isSuccess) {
                true -> SuccessGreen.copy(alpha = 0.4f)
                false -> MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                null -> MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(
                                when (isSuccess) {
                                    true -> SuccessGreen
                                    false -> MaterialTheme.colorScheme.error
                                    null -> MaterialTheme.colorScheme.primary
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isExecuting) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), color = Color.White, strokeWidth = 1.5.dp)
                        } else if (isSuccess == true) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                        } else {
                            Text(text = stepNumber, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                    }
                }

                if (actionText != null && onAction != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = onAction,
                        enabled = !isExecuting,
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(actionText, fontSize = 10.5.sp)
                    }
                }
            }

            if (customContent != null) {
                customContent()
            }
        }
    }
}


