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
                name=Redmi Buds 5 Pro A2DP 音频修复
                version=1.0.0
                versionCode=100
                author=RedmiBudsFixer
                description=为 LineageOS (Android 17) 锁定 Redmi Buds 5 Pro 的 SBC 与 AAC 媒体音频协议，屏蔽 LHDC 冲突，解决扬声器外放问题。
                action=action.sh
            """.trimIndent()
            addZipEntry(zos, "module.prop", modulePropContent)

            // 2. service.sh (Executed in background upon Shevery service startup / device boot)
            val serviceScriptContent = """
                #!/system/bin/sh
                # Redmi Buds 5 Pro A2DP Audio Fix - Shevery Background Service
                sleep 5

                # 1. Turn off ART hidden API blacklist restrictions
                settings put global hidden_api_policy 1
                settings put global hidden_api_policy_p_apps 1
                settings put global hidden_api_policy_pre_p_apps 1

                # 2. Disable optional codecs (LHDC) globally to avoid AudioPolicy error=1
                settings put global bluetooth_a2dp_supports_optional_codecs 0
                settings put global bluetooth_a2dp_optional_codecs_enabled 0
                settings put global bluetooth_disabled_codecs "lhdc,lhdc_v1,lhdc_v2,lhdc_v3,lhdc_v5,65536"

                # 3. Mask unstable LE Audio
                settings put global bluetooth_le_audio_masked 1
                device_config put bluetooth leaudio_supported false 2>/dev/null || true
                device_config put bluetooth le_audio_inband_ringtone false 2>/dev/null || true

                # 4. Inject highest A2DP priority for Redmi Buds 5 Pro MAC
                MAC="48:73:CB:D2:8D:3D"
                MAC_U="48_73_CB_D2_8D_3D"
                settings put global bluetooth_a2dp_src_priority_${'$'}{MAC} 1000
                settings put global bluetooth_a2dp_sink_priority_${'$'}{MAC} 1000
                settings put global bluetooth_a2dp_src_priority_${'$'}{MAC_U} 1000
                settings put global bluetooth_a2dp_sink_priority_${'$'}{MAC_U} 1000
                settings put secure bluetooth_a2dp_supports_optional_codecs_enabled_${'$'}{MAC} 0
                settings put secure bluetooth_a2dp_supports_optional_codecs_enabled_${'$'}{MAC_U} 0
                settings put global bluetooth_a2dp_supports_optional_codecs_enabled_${'$'}{MAC} 0
                settings put global bluetooth_a2dp_supports_optional_codecs_enabled_${'$'}{MAC_U} 0

                # 5. Disable speakerphone route preemption
                cmd audio set-speakerphone-on false 2>/dev/null || true
            """.trimIndent()
            addZipEntry(zos, "service.sh", serviceScriptContent)

            // 3. action.sh (Triggered when user clicks "Action / 执行" in Shevery app)
            val actionScriptContent = """
                #!/system/bin/sh
                echo "======================================"
                echo "Redmi Buds 5 Pro A2DP 媒体通道修复"
                echo "======================================"

                echo "[1/4] 解除 ART 隐藏接口限制..."
                settings put global hidden_api_policy 1
                settings put global hidden_api_policy_p_apps 1
                settings put global hidden_api_policy_pre_p_apps 1

                echo "[2/4] 屏蔽 LHDC 冲突并锁定 SBC/AAC 优先..."
                settings put global bluetooth_a2dp_supports_optional_codecs 0
                settings put global bluetooth_a2dp_optional_codecs_enabled 0
                settings put global bluetooth_disabled_codecs "lhdc,lhdc_v1,lhdc_v2,lhdc_v3,lhdc_v5,65536"
                settings put global bluetooth_le_audio_masked 1

                echo "[3/4] 注入耳麦媒体优先级 (48:73:CB:D2:8D:3D)..."
                MAC="48:73:CB:D2:8D:3D"
                MAC_U="48_73_CB_D2_8D_3D"
                settings put global bluetooth_a2dp_src_priority_${'$'}{MAC} 1000
                settings put global bluetooth_a2dp_sink_priority_${'$'}{MAC} 1000
                settings put global bluetooth_a2dp_src_priority_${'$'}{MAC_U} 1000
                settings put global bluetooth_a2dp_sink_priority_${'$'}{MAC_U} 1000
                settings put secure bluetooth_a2dp_supports_optional_codecs_enabled_${'$'}{MAC} 0
                settings put secure bluetooth_a2dp_supports_optional_codecs_enabled_${'$'}{MAC_U} 0

                echo "[4/4] 强制关闭扬声器外放回退..."
                cmd audio set-speakerphone-on false 2>/dev/null || true

                echo "======================================"
                echo "✅ 媒体策略注入完成！"
                echo "耳麦现在可以稳定通过 SBC/AAC 正常发声。"
                echo "======================================"
            """.trimIndent()
            addZipEntry(zos, "action.sh", actionScriptContent)

            // 4. customize.sh (Installer script when importing into Shevery)
            val customizeScriptContent = """
                ui_print "- 正在安装 Redmi Buds 5 Pro A2DP 修复模块..."
                ui_print "- 目标设备: OnePlus 8 Pro (IN2020) LineageOS 22"
                ui_print "- 核心策略: 屏蔽 LHDC 冲突，常驻 SBC/AAC 媒体通道"
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
