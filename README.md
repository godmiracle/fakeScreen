# fakeScreen

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![API](https://img.shields.io/badge/Xposed%20API-Modern%20API%20102-brightgreen)](https://github.com/libxposed/api)
[![Platform](https://img.shields.io/badge/Platform-Android%2015%2B-orange)](https://developer.android.com/)

**FakeScreen** 是一款基于Xposed 架构开发的 Android 模块，实现熄屏挂机功能。

---

## 核心特性
模块激活并开启功能后，单击电源键仅熄灭屏幕；在 FakeScreen 主动熄屏期间双击电源键尝试恢复屏幕。屏幕亮起时收到的系统双击回调会交还系统处理。长按电源键时，模块会先尝试恢复屏幕，再放行系统长按动作。

在 OPPO Android 17（API 37）上，恢复时会通过系统显示服务重新应用屏幕状态和亮度，以处理显示服务认为屏幕已亮但硬件亮度仍为零的问题。恢复期间应用可能收到显示状态变化；其他厂商或系统版本继续使用原有恢复方式。

功能开启期间，**10 秒内短按音量上键 5 次**会关闭 FakeScreen 并尝试恢复屏幕；这些按键仍由系统处理，可能改变音量。发出恢复请求后的 1 分钟内，即使功能已关闭，也可再次使用该手势重试。恢复接口没有提供面板是否实际亮起的可靠确认，不同设备上的效果需要验证。

模块会观察上游按键事件并与系统电源键回调关联。如果没有观察到完整的电源键按下和松开事件，会放行系统原有行为，因此设备可能正常锁屏或休眠，不会进入假熄屏。厂商系统可能使用其他按键或长按路径，诊断日志会记录已安装的 Hook、按键事件与恢复请求。

App 和快捷磁贴的控制广播使用签名权限保护；原有 ADB 广播由 `android.permission.DUMP` 保护，普通应用不能通过这两个入口切换功能。首页和诊断日志中的版本号来自构建配置。

## 问题日志
模块会记录功能开关、按键参数、FakeScreen 熄屏状态、显示模式请求和异常。在 App 首页打开 **诊断日志**，可以刷新、查看、导出为文本文件或清除日志。日志保存在应用私有的设备加密存储中，正常重启后仍可查看；最多保留两份各约 512 KiB 的文件，界面展示最近的内容，导出时包含全部保留记录。卸载 App 会删除这些日志。

系统进程使用后台队列将日志写入 App。开机初期如果 App 日志入口尚未就绪，记录会暂存在有上限的内存队列中并重试；队列溢出或系统进程在写入前退出时，部分记录可能丢失。系统日志和 LSPosed 模块日志仍然可用。App 只能记录安装本版本之后发生的事件。

也可以通过以下命令查看 Android 日志（`FakeScreen` 标签）：

```bash
adb logcat -b all -d -v time -s FakeScreen
```

---
## 兼容性
项目历史版本曾在以下设备上验证。v2.4 要求 Android 15（API 35）及以上，并以 Android 17（API 37）为目标。OPPO PMX110 的 ColorOS 17 已验证单击熄屏、双击亮屏和广播权限；Android 15、16 及其他设备尚未验证本版。

| 设备         | 系统                | Android版本  |
|:-----------|:------------------|:-----------|
| Pixel 6    | GrapheneOS        | Android 16 |
| MiPad6 Pro | HyperOS 2.0.213.0 | Android 15 |
---
## 使用说明

1. **安装与激活**：
    * 从旧包名 `top.chuyb.fakeScreen` 迁移时，先导出需要保留的日志，关闭旧版功能并在 LSPosed 停用旧模块，再卸载旧版。卸载会删除旧版应用数据。
    * 下载安装包名为 `com.godmiracle.fakescreen` 的 APK，在 LSPosed 管理器中启用 **FakeScreen** 模块。
    * **作用域说明**：选择 **系统框架**。
    * 重启设备。
    * 包名变更后的首次安装不能覆盖旧包；以后相同包名、签名一致且版本号不降低的版本可覆盖升级。
    * 新安装默认隐藏桌面图标，可从 LSPosed 的模块页面打开 App，并在首页关闭“隐藏桌面图标”开关来显示图标。也可使用 `adb shell am start -n com.godmiracle.fakescreen/top.chuyb.fakeScreen.MainActivity` 打开。

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
git clone https://github.com/godmiracle/fakeScreen.git
cd fakeScreen
bash ./gradlew assembleRelease
```

## GitHub Actions 构建 Release
在 GitHub 的 **Actions** 页面手动运行 **Build Release APK** 会先执行单元测试，再构建 APK 并生成 30 天有效的 `fakeScreen-release-apk` artifact。推送名称以 `v` 开头的 tag（例如 `v2.4`）会在测试和构建通过后创建 GitHub Release，并将 APK 附加到该 Release。

Release 使用固定的项目专用签名。Actions 需要仓库 Secrets `FAKESCREEN_KEYSTORE_BASE64` 和 `FAKESCREEN_KEYSTORE_PASSWORD`；缺失时构建失败，不会退回临时 debug 签名。本机构建默认读取 `.signing/fakescreen-release.p12` 和 `.signing/keystore-password`，也可以通过环境变量 `FAKESCREEN_KEYSTORE_FILE` 和 `FAKESCREEN_KEYSTORE_PASSWORD` 指定。

发布签名证书的 SHA-256 指纹为 `9e1b1b684de60cc12a8e9911f0af77f2253333c65e05d2c5a2b65e08788fce97`。Actions 在上传前校验证书，避免误用其他签名发布。

`.signing/` 已被 Git 忽略。请将签名密钥和口令备份到安全位置，切勿提交到 Git；丢失或更换密钥会导致已安装版本无法正常覆盖升级。
