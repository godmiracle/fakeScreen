# fakeScreen

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![API](https://img.shields.io/badge/Xposed%20API-Modern%20API%20102-brightgreen)](https://github.com/libxposed/api)
[![Platform](https://img.shields.io/badge/Platform-Android%209%2B-orange)](https://developer.android.com/)

**FakeScreen** 是一款基于Xposed 架构开发的 Android 模块，实现熄屏挂机功能。

---

## 核心特性
模块激活并开启功能后，单击电源键仅熄灭屏幕；在 FakeScreen 主动熄屏期间双击电源键可唤醒屏幕。屏幕亮起时，系统原有的双击电源键功能保持不变。

## 问题日志
模块会将按键次数、FakeScreen 熄屏状态、显示模式请求和异常同时写入 Android 日志（`FakeScreen` 标签）与 LSPosed 模块日志。复现问题后可通过以下命令导出日志：

```bash
adb logcat -b all -d -v time -s FakeScreen
```

---
## 兼容性
已在以下设备上进行验证

| 设备         | 系统                | Android版本  |
|:-----------|:------------------|:-----------|
| Pixel 6    | GrapheneOS        | Android 16 |
| MiPad6 Pro | HyperOS 2.0.213.0 | Android 15 |
---
## 使用说明

1. **安装与激活**：
    * 下载安装 APK 后，在 LSPosed 管理器中启用 **FakeScreen** 模块。
    * **作用域说明**：选择 **系统框架**。
    * 重启设备。

2. **状态开关**：
    * 打开 **FakeScreen** App，切换主界面开关状态。
---

## 🛠️ 项目构建 (Building)

你可以使用 Android Studio 打开项目并直接构建，或者通过 Gradle 命令行构建 release APK：

```bash
git clone https://github.com/chuyb-re/fakeScreen.git
cd fakeScreen
bash ./gradlew assembleRelease
```

## GitHub Actions 构建 Release
在 GitHub 的 **Actions** 页面手动运行 **Build Release APK** 会生成 30 天有效的 `fakeScreen-release-apk` artifact。推送名称以 `v` 开头的 tag（例如 `v2.0`）会构建 APK、创建 GitHub Release，并将 APK 附加到该 Release。

当前 Release 构建沿用 Gradle 的 debug signing config。用于公开分发或后续覆盖升级前，应配置项目专用签名密钥。
