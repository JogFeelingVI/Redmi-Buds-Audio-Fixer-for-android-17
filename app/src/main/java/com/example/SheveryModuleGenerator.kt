package com.example

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object SheveryModuleGenerator {

    /**
     * Generates a ready-to-import Shevery "ADB Module" ZIP package.
     * Allows rootless persistent execution of A2DP Bluetooth audio stabilization scripts
     * via Shevery (Shizuku's enhanced fork with ADB Modules support).
     */
    suspend fun generateSheveryModuleZip(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }
            val zipFile = File(downloadDir, "RedmiBuds5Pro_A2DP_Fix_Shevery_ADB_Module.zip")

            val fos = FileOutputStream(zipFile)
            val zos = ZipOutputStream(fos)

            // 1. module.prop (Shevery ADB Module specification)
            val modulePropContent = """
                id=redmibuds5pro_a2dp_fix
                name=Redmi Buds 5 Pro AAC/SBC 媒体修复
                version=2.0.0
                versionCode=200
                author=RedmiBudsFixer
                description=为 LineageOS 锁定 Redmi Buds 5 Pro 的 AAC 高清媒体音频，屏蔽 LHDC 冲突，杜绝扬声器外放回退。
                action=action.sh
            """.trimIndent()
            addZipEntry(zos, "module.prop", modulePropContent)

            // 2. service.sh (Executed in background upon Shevery service startup / device boot)
            val serviceScriptContent = """
                #!/system/bin/sh
                # Redmi Buds 5 Pro A2DP Audio Fix - Shevery Background Service
                sleep 5

                # 1. 解除 ART 隐藏 API 白名单限制
                settings put global hidden_api_policy 1
                settings put global hidden_api_policy_p_apps 1
                settings put global hidden_api_policy_pre_p_apps 1

                # 2. 启用可选高清编解码器支持 (AAC 必须开启可选编解码支持: 1 = ENABLED)
                settings put global bluetooth_a2dp_supports_optional_codecs 1
                settings put global bluetooth_a2dp_optional_codecs_enabled 1

                # 3. 严格屏蔽 LineageOS 缺失驱动的 LHDC，杜绝 AudioPolicy error=1 外放崩溃
                settings put global bluetooth_disabled_codecs "lhdc,lhdc_v1,lhdc_v2,lhdc_v3,lhdc_v5,65536"
                device_config put bluetooth disabled_codecs "lhdc,lhdc_v1,lhdc_v2,lhdc_v3,lhdc_v5,65536" 2>/dev/null || true
                settings put global bluetooth_a2dp_disabled_codecs "lhdc,lhdc_v1,lhdc_v2,lhdc_v3,lhdc_v5,65536"

                # 4. 屏蔽不稳定 LE Audio 抢占
                settings put global bluetooth_le_audio_masked 1
                device_config put bluetooth leaudio_supported false 2>/dev/null || true
                device_config put bluetooth le_audio_inband_ringtone false 2>/dev/null || true

                # 5. 注入 Redmi Buds 5 Pro MAC 极高优先级 (1000) 并解锁 AAC 权限
                MAC="48:73:CB:D2:8D:3D"
                MAC_U="48_73_CB_D2_8D_3D"
                settings put global bluetooth_a2dp_src_priority_${'$'}{MAC} 1000
                settings put global bluetooth_a2dp_sink_priority_${'$'}{MAC} 1000
                settings put global bluetooth_a2dp_src_priority_${'$'}{MAC_U} 1000
                settings put global bluetooth_a2dp_sink_priority_${'$'}{MAC_U} 1000
                settings put secure bluetooth_a2dp_supports_optional_codecs_enabled_${'$'}{MAC} 1
                settings put secure bluetooth_a2dp_supports_optional_codecs_enabled_${'$'}{MAC_U} 1
                settings put global bluetooth_a2dp_supports_optional_codecs_enabled_${'$'}{MAC} 1
                settings put global bluetooth_a2dp_supports_optional_codecs_enabled_${'$'}{MAC_U} 1
                settings put global bluetooth_a2dp_supports_optional_hd_audio 1
                settings put global bluetooth_a2dp_optional_hd_audio_disabled 0
                settings put global bluetooth_a2dp_supports_optional_hd_audio_${'$'}{MAC} 1
                settings put global bluetooth_a2dp_optional_hd_audio_disabled_${'$'}{MAC} 0
                settings put global bluetooth_a2dp_supports_optional_hd_audio_${'$'}{MAC_U} 1
                settings put global bluetooth_a2dp_optional_hd_audio_disabled_${'$'}{MAC_U} 0
                device_config put bluetooth a2dp_optional_codecs_enabled true 2>/dev/null || true

                # 6. 永久停用 A2DP 硬件分流，切换为系统原生软件编码 (彻底避免高通 DSP Compress handle NULL 崩溃)
                setprop persist.bluetooth.a2dp_offload.disabled true
                setprop persist.vendor.bt.a2dp_offload_cap false
                setprop persist.vendor.qcom.bluetooth.a2dp_offload_cap false
                settings put global bluetooth_a2dp_offload_disabled 1

                # 7. 关闭扬声器外放回退
                cmd audio set-speakerphone-on false 2>/dev/null || true
            """.trimIndent()
            addZipEntry(zos, "service.sh", serviceScriptContent)

            // 3. action.sh (Triggered when user clicks "Action / 执行" in Shevery app)
            val actionScriptContent = """
                #!/system/bin/sh
                echo "======================================"
                echo "Redmi Buds 5 Pro AAC 高清媒体通道注入"
                echo "======================================"

                echo "[1/4] 解除 ART 隐藏接口限制..."
                settings put global hidden_api_policy 1
                settings put global hidden_api_policy_p_apps 1
                settings put global hidden_api_policy_pre_p_apps 1

                echo "[2/4] 屏蔽 LHDC 冲突，开启可选编解码 (AAC 模式)..."
                settings put global bluetooth_a2dp_supports_optional_codecs 1
                settings put global bluetooth_a2dp_optional_codecs_enabled 1
                settings put global bluetooth_a2dp_supports_optional_hd_audio 1
                settings put global bluetooth_a2dp_optional_hd_audio_disabled 0
                settings put global bluetooth_disabled_codecs "lhdc,lhdc_v1,lhdc_v2,lhdc_v3,lhdc_v5,65536"
                settings put global bluetooth_a2dp_disabled_codecs "lhdc,lhdc_v1,lhdc_v2,lhdc_v3,lhdc_v5,65536"
                device_config put bluetooth a2dp_optional_codecs_enabled true 2>/dev/null || true
                settings put global bluetooth_le_audio_masked 1

                echo "[3/4] 注入耳麦媒体优先级与 AAC 开关 (48:73:CB:D2:8D:3D)..."
                MAC="48:73:CB:D2:8D:3D"
                MAC_U="48_73_CB_D2_8D_3D"
                settings put global bluetooth_a2dp_src_priority_${'$'}{MAC} 1000
                settings put global bluetooth_a2dp_sink_priority_${'$'}{MAC} 1000
                settings put global bluetooth_a2dp_src_priority_${'$'}{MAC_U} 1000
                settings put global bluetooth_a2dp_sink_priority_${'$'}{MAC_U} 1000
                settings put secure bluetooth_a2dp_supports_optional_codecs_enabled_${'$'}{MAC} 1
                settings put secure bluetooth_a2dp_supports_optional_codecs_enabled_${'$'}{MAC_U} 1
                settings put global bluetooth_a2dp_supports_optional_codecs_enabled_${'$'}{MAC} 1
                settings put global bluetooth_a2dp_supports_optional_codecs_enabled_${'$'}{MAC_U} 1
                settings put global bluetooth_a2dp_supports_optional_hd_audio_${'$'}{MAC} 1
                settings put global bluetooth_a2dp_optional_hd_audio_disabled_${'$'}{MAC} 0
                settings put global bluetooth_a2dp_supports_optional_hd_audio_${'$'}{MAC_U} 1
                settings put global bluetooth_a2dp_optional_hd_audio_disabled_${'$'}{MAC_U} 0

                echo "[4/5] 永久停用 A2DP 硬件分流 (锁定为原生软件解码)..."
                setprop persist.bluetooth.a2dp_offload.disabled true
                setprop persist.vendor.bt.a2dp_offload_cap false
                setprop persist.vendor.qcom.bluetooth.a2dp_offload_cap false
                settings put global bluetooth_a2dp_offload_disabled 1

                echo "[5/5] 强制关闭扬声器外放回退..."
                cmd audio set-speakerphone-on false 2>/dev/null || true

                echo "======================================"
                echo "✅ AAC 高清媒体策略注入完成！"
                echo "LHDC 已屏蔽，硬件分流已关闭，AAC 已锁定。"
                echo "======================================"
            """.trimIndent()
            addZipEntry(zos, "action.sh", actionScriptContent)

            // 4. customize.sh (Installer script when importing into Shevery)
            val customizeScriptContent = """
                ui_print "- 正在安装 Redmi Buds 5 Pro AAC/SBC 修复模块..."
                ui_print "- 目标设备: OnePlus 8 Pro (IN2020) LineageOS 22"
                ui_print "- 核心策略: 屏蔽 LHDC 冲突，解锁并常驻 AAC 高清媒体通道"
                ui_print "- 已注入开机守护 service.sh 与即时触发 action.sh"
                ui_print "- 安装完成！无需 Root 权限。"
            """.trimIndent()
            addZipEntry(zos, "customize.sh", customizeScriptContent)

            zos.finish()
            zos.close()
            fos.close()

            Pair(true, zipFile.absolutePath)
        } catch (e: Exception) {
            Pair(false, e.localizedMessage ?: "生成 Shevery 模块失败")
        }
    }

    private fun addZipEntry(zos: ZipOutputStream, path: String, content: String) {
        val entry = ZipEntry(path)
        zos.putNextEntry(entry)
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }
}
