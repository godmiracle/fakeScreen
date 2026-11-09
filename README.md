# fakeScreen

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![API](https://img.shields.io/badge/Xposed%20API-Modern%20API%20102-brightgreen)](https://github.com/libxposed/api)
[![Platform](https://img.shields.io/badge/Platform-Android%209%2B-orange)](https://developer.android.com/)

**FakeScreen** 是一款基于Xposed 架构开发的 Android 模块，实现熄屏挂机功能。

---

## 核心特性
模块激活并开启功能后，单击电源键仅熄灭屏幕；在 FakeScreen 主动熄屏期间双击电源键尝试恢复屏幕。屏幕亮起时收到的系统双击回调会交还系统处理。长按电源键时，模块会先尝试恢复屏幕，再放行系统长按动作。

在 OPPO Android 17（API 37）上，恢复时会通过系统显示服务重新应用屏幕状态和亮度，以处理显示服务认为屏幕已亮但硬件亮度仍为零的问题。恢复期间应用可能收到显示状态变化；其他厂商或系统版本继续使用原有恢复方式。

功能开启期间，**10 秒内短按音量上键 5 次**会关闭 FakeScreen 并尝试恢复屏幕；这些按键仍由系统处理，可能改变音量。发出恢复请求后的 1 分钟内，即使功能已关闭，也可再次使用该手势重试。恢复接口没有提供面板是否实际亮起的可靠确认，不同设备上的效果需要验证。

模块会观察上游按键事件并与系统电源键回调关联。如果没有观察到完整的电源键按下和松开事件，会放行系统原有行为，因此设备可能正常锁屏或休眠，不会进入假熄屏。厂商系统可能使用其他按键或长按路径，诊断日志会记录已安装的 Hook、按键事件与恢复请求。

## 问题日志
模块会记录功能开关、按键参数、FakeScreen 熄屏状态、显示模式请求和异常。在 App 首页打开 **诊断日志**，可以刷新、查看、导出为文本文件或清除日志。日志保存在应用私有的设备加密存储中，正常重启后仍可查看；最多保留两份各约 512 KiB 的文件，界面展示最近的内容，导出时包含全部保留记录。卸载 App 会删除这些日志。

系统进程使用后台队列将日志写入 App。开机初期如果 App 日志入口尚未就绪，记录会暂存在有上限的内存队列中并重试；队列溢出或系统进程在写入前退出时，部分记录可能丢失。系统日志和 LSPosed 模块日志仍然可用。App 只能记录安装本版本之后发生的事件。

也可以通过以下命令查看 Android 日志（`FakeScreen` 标签）：

```bash
adb logcat -b all -d -v time -s FakeScreen
```

---
## 兼容性
项目历史版本曾在以下设备上验证。v2.3 的完整按键与恢复流程尚待真机验收；OPPO PMX110 的 ColorOS 17 已单独验证显示服务能够在保持系统唤醒的情况下恢复屏幕和亮度。

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

3. **尝试恢复**：
    * 如果屏幕未按预期亮起，尝试在 10 秒内短按音量上键 5 次。
    * 如果设备已连接 ADB，且模块的系统广播接收器工作正常，也可以尝试关闭功能并请求恢复：

      ```bash
      adb shell am broadcast -a top.chuyb.fakeScreen.ACTION_TOGGLE --ez enabled false
      ```

    * 以上操作依赖系统接收到事件及显示接口正常工作，不能保证所有厂商系统都可恢复；请在可安全重启设备的条件下测试。
---

## 🛠️ 项目构建 (Building)

你可以使用 Android Studio 打开项目并直接构建，或者通过 Gradle 命令行构建 release APK：

```bash
git clone https://github.com/chuyb-re/fakeScreen.git
cd fakeScreen
bash ./gradlew assembleRelease
```

## GitHub Actions 构建 Release
在 GitHub 的 **Actions** 页面手动运行 **Build Release APK** 会先执行单元测试，再构建 APK 并生成 30 天有效的 `fakeScreen-release-apk` artifact。推送名称以 `v` 开头的 tag（例如 `v2.3`）会在测试和构建通过后创建 GitHub Release，并将 APK 附加到该 Release。

当前 Release 构建沿用 Gradle 的 debug signing config，不同 Actions 运行生成的签名可能不同，导致无法覆盖安装旧版。卸载旧版会删除应用日志，请先导出需要保留的日志；用于长期公开分发和覆盖升级时，应配置项目专用签名密钥。
