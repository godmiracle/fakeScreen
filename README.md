# fakeScreen

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![API](https://img.shields.io/badge/Xposed%20API-Modern%20API%20102-brightgreen)](https://github.com/libxposed/api)
[![Platform](https://img.shields.io/badge/Platform-Android%209%2B-orange)](https://developer.android.com/)

**FakeScreen** 是一款基于Xposed 架构开发的 Android 模块，实现熄屏挂机功能。

---

## 核心特性
模块激活并开启功能后，单击电源键仅熄灭屏幕；在 FakeScreen 主动熄屏期间双击电源键可唤醒屏幕。屏幕亮起时，系统原有的双击电源键功能保持不变。

## 问题日志
模块会记录功能开关、按键参数、FakeScreen 熄屏状态、显示模式请求和异常。在 App 首页打开 **诊断日志**，可以刷新、查看、导出为文本文件或清除日志。日志保存在应用私有的设备加密存储中，正常重启后仍可查看；最多保留两份各约 512 KiB 的文件，界面展示最近的内容，导出时包含全部保留记录。卸载 App 会删除这些日志。

系统进程使用后台队列将日志写入 App。开机初期如果 App 日志入口尚未就绪，记录会暂存在有上限的内存队列中并重试；队列溢出或系统进程在写入前退出时，部分记录可能丢失。系统日志和 LSPosed 模块日志仍然可用。App 只能记录安装本版本之后发生的事件。

也可以通过以下命令查看 Android 日志（`FakeScreen` 标签）：

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
在 GitHub 的 **Actions** 页面手动运行 **Build Release APK** 会生成 30 天有效的 `fakeScreen-release-apk` artifact。推送名称以 `v` 开头的 tag（例如 `v2.1`）会构建 APK、创建 GitHub Release，并将 APK 附加到该 Release。

当前 Release 构建沿用 Gradle 的 debug signing config。用于公开分发或后续覆盖升级前，应配置项目专用签名密钥。
