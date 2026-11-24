# DailyPlan Android 客户端

Kotlin Multiplatform + Compose Multiplatform 宿主，使用 Room/SQLite 做离线数据库，Glance 提供桌面小组件。

## 构建环境

本次验收使用：

- JDK 21.0.13.1
- Gradle 8.14.3
- Android compileSdk/targetSdk 36
- Build Tools 36.0.0
- Kotlin 2.3.21
- Compose Multiplatform 1.11.1
- Room 2.8.5

构建 Debug APK：

```bash
cd apps/mobile
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :androidApp:assembleDebug
```

产物：

```text
apps/mobile/androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

这是 Debug 签名 APK，适合内网试用；仓库尚未配置正式签名和 AAB。

安装：

```bash
adb install -r apps/mobile/androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

## 首次设置

1. 打开应用，允许通知。
2. 进入“设置”，填写公司内网地址和访问令牌。
3. 点“测试连接”，确认协议版本为 1。
4. 点“立即同步”首次拉取快照。

目标 Android 版本默认禁止明文 HTTP。应用通过 `network_security_config.xml` 允许用户填写的内网 `http://` 地址；请求只会发往用户自己配置的服务器。不要在生产环境使用公网明文 HTTP。

## 功能入口

- 今天页：文字新增、勾选完成、打开详情、选相册图片、录音、立即同步。
- 详情页：修改标题、截止日期/时间、优先级、完成状态，查看/播放附件。
- 离线：所有写入先进入 Room 和待传操作队列，重启不丢。
- 后台同步：WorkManager 每 15 分钟按网络条件同步；应用在前台时每 15 秒尝试一次，失败后按 30 秒、1 分钟、2 分钟、4 分钟、5 分钟递增。回到前台或网络恢复时立即补一次。
- 录音：16 kHz 单声道 PCM16 WAV，保存后自动排队转写。
- 截图：使用系统 Photo Picker，把原图复制到应用私有目录并计算 SHA-256，不依赖相册原文件继续存在。
- 提醒：按工作日、上班时间和下班前提前量安排本地通知；上午只提醒逾期、紧急和临期事项，傍晚提醒全部未完成事项。通知和今天页都提供“已查看”。Android 12+ 若未授予精确闹钟权限，会退化为允许待机唤醒的非精确闹钟。

## 添加桌面小组件

在 vivo X300 或其他 Android 桌面：

1. 长按桌面空白处，进入“小组件/添加工具”。
2. 找到 DailyPlan。
3. 选择尺寸并放到桌面。
4. 点“打字”“语音”进入对应模式；点行首圆圈完成该任务；点标题打开详情。

小组件会根据可用高度显示 1 到 4 行，每行触控区至少 48dp。

## 数据与权限

- SQLite 和附件都在应用私有目录，卸载应用会删除本地数据。
- 访问令牌使用 `EncryptedSharedPreferences` 保存。
- 录音需要 `RECORD_AUDIO`。
- 通知需要 `POST_NOTIFICATIONS`。
- 精确提醒可选 `SCHEDULE_EXACT_ALARM`；没有权限时仍会提醒，但系统可能延迟。
- 选图使用系统 Photo Picker，不申请全盘照片权限。

## 验证

共享规则与 Kotlin：

```bash
cd apps/mobile
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew :shared:testDebugUnitTest
```

本次已成功编译 APK 并通过共享规则测试。当前环境没有 Android 真机或模拟器，因此 X300 小组件、通知到达时间、锁屏/待机、录音权限和 Photo Picker 仍需按 [验收记录](../../docs/acceptance.md) 在真机补测。
