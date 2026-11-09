# Redmi Buds Audio Fixer for Android 17

## 概述
本应用针对 Redmi Buds 5 Pro 在 Android 17 (LineageOS) 上出现的 LHDC 缺失驱动导致 AudioPolicy APM 崩溃问题，通过禁用 LHDC/LDAC/aptX 等编解码器并锁定 AAC，避免扬声器外放回退。

## 技术细节
- 使用 Shizuku 获取 shell (UID 2000) 权限，执行 settings 命令配置全局蓝牙编解码策略。
- 通过反射调用隐藏的 `BluetoothA2dp` API（`enableOptionalCodecs`、`setOptionalCodecsEnabled`、`setCodecConfigPreference`）。
- 使用 `ShizukuBinderWrapper` 包装 `IBluetoothA2dp`/`IBluetooth` Binder，提升调用权限。
- 日志自动保存到 Downloads 目录（`aac_fix_log_<timestamp>.txt`）。

## 操作方法
1. 安装并启动 Shizuku，授予本应用权限。
2. 打开应用，点击「切换并锁定 AAC 高清模式 (屏蔽 LHDC)」。
3. 等待操作完成，查看 Downloads 目录日志。
4. 若未生效，进入系统蓝牙设置手动选择 AAC 编解码器。

## 限制
- 无 root/系统签名时，部分隐藏 API 因 UID 校验无法执行，AAC 可能无法强制切换。
- 仅在支持 AAC 的设备与 ROM 上有效。

## 构建
```bash
./gradlew assembleDebug
```
