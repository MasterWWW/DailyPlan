# 首版验收记录

验收日期：2026-11-24（Asia/Shanghai）

## 环境

| 项目 | 实际版本/状态 |
| --- | --- |
| 主机 | macOS 27.2.1，arm64 |
| Docker | 29.9.3 |
| Go | 1.27.4 |
| Rust | 1.95.0 |
| Node / pnpm | 25.0.0 / 11.25.0 |
| JDK / Gradle | 21.0.13.1 / 8.14.3 |
| Android SDK | compileSdk 36，Build Tools 36.0.0 |
| MySQL | 8.4.13，Docker |
| whisper.cpp | 1.8.2，Docker |
| 转写模型 | ggml-base.bin，SHA-256 `60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe` |
| Xcode | 无，仅 Command Line Tools |
| Android 真机/模拟器 | 无，`adb devices` 为空 |

## 自动化测试

### 服务端

```bash
make test-go-integration
```

结果：**29 个测试通过，9 个包**。测试通过 `deploy/docker-compose.test.yml` 启动临时 MySQL 容器，结束后自动清理，本机 MySQL 未启动。

覆盖：迁移、幂等操作、并发游标、快照、附件上传/Range 下载、同 ID 原件修复与并发冲突、恢复失败清理、只读模式、备份版本、共享数据模型和协议样例解码。

### 桌面

```bash
cd apps/desktop
pnpm install --frozen-lockfile
pnpm build

cd src-tauri
cargo fmt --check
cargo test
```

结果：**6 个 Rust 测试通过，3 个测试套件**；Vue 类型检查和生产前端构建通过。Tauri 编译期需要 `apps/desktop/dist`，因此必须先构建前端。Rust 测试直接读取共享规则用例和服务端 JSON 样例。

新增的 SQLite 回归测试验证：创建任务和附件、关闭数据库、重新打开后，任务、附件本地路径、未上传状态和待同步操作仍然存在。

### Android

```bash
cd apps/mobile
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew :androidApp:assembleDebug :shared:testDebugUnitTest
```

结果：APK 构建成功，**3 个共享测试通过**。规则与协议测试直接读取 `shared/protocol/` 下的用例和样例。

## Docker 与服务端验收

### 镜像与启动

- `dailyplan-api:local` 构建成功，大小约 1.16 GB。
- Compose 中 `dailyplan-api-1` 和 `dailyplan-db-1` 均健康。
- `/healthz` 返回 `{"status":"ok"}`。
- `/v1/status` 返回 `protocolVersion: 1`、`transcriptionEnabled: true`。
- 容器内 `whisper-cli`、`mysql`、`mysqldump` 均可执行。

whisper.cpp 首次构建在目标指令集探测上失败，加入 `-DGGML_NATIVE=OFF` 后成功。

### 真实数据流

通过真实 HTTP API 完成：

- 未授权请求返回 401。
- 创建 2 条任务、更新 1 次设置。
- 重复提交同一 `opId` 返回原游标，不新增任务。
- 增量分页从游标 0 读到 7。
- 上传 1 张 PNG 原图和 1 段中文 WAV。
- 附件 Range 下载返回 `RIFF`。
- 下载原图 SHA-256 与上传值一致。
- whisper 将“明早给客户回电话”转写为“明早給客戶回電話”（base 模型输出繁体字），任务标题自动更新，耗时约 3 秒。
- 将截图任务标记完成，快照保留完成时间。

### 容器重建持久化

执行 `docker compose down` 后重新 `up -d`，未删除卷。复查：

- 任务数仍为 2。
- 附件数仍为 2。
- 完成状态仍为已完成。
- 转写标题仍在。
- 重新下载 PNG 的 SHA-256 未变化。

## 备份恢复验收

导出结果：

- 2 条任务。
- 2 份附件。
- 恢复版本 1。
- 数据库、图片、WAV 和 manifest 摘要一致。

在独立 Compose 项目、全新 MySQL 卷和全新附件卷中恢复：

- 恢复命令成功。
- 任务数 2、附件数 2。
- `restoreVersion` 从 1 变为 2。
- 完成状态、完成时间、作息、转写文本均保留。
- 图片和 WAV 下载后的 SHA-256 与备份一致。
- Range 下载仍返回 `RIFF`。

损坏测试：

- 复制备份并修改 PNG 的一个字节。
- 在空目标栈恢复时被拒绝，错误包含附件 ID 和文件名：

```text
attachment <attachment-id> (<attachment-id>.png) checksum mismatch
```

恢复过程中还发现并修复了一个真实问题：原暂存目录位于附件卷外，Docker 内跨文件系统 `rename` 返回 `invalid cross-device link`。修复后暂存目录位于附件卷内，跨栈恢复通过。

## 构建产物

| 产物 | 路径 | 大小 | SHA-256 |
| --- | --- | --- | --- |
| macOS `.app` | `apps/desktop/src-tauri/target/release/bundle/macos/DailyPlan.app` | 19 MB | 可执行文件 `3a1d86157fcb364afb42e1e628f6e8aee0f59c513c52a2013bd37ad9cbe38b23` |
| macOS DMG | `apps/desktop/src-tauri/target/release/bundle/dmg/DailyPlan_0.1.0_aarch64.dmg` | 7.7 MB | `71ab164353dd4f2f21d76ffa67efa75d47030a44f5fad952905cff01bbd96b32` |
| Android APK | `apps/mobile/androidApp/build/outputs/apk/debug/androidApp-debug.apk` | 30 MB | `5853162b2dd6d56da22c26101c4ddd12791714e84b0663f2c38453f545310a64` |

macOS 产物为 ad-hoc 签名，未公证。Tauri 默认 DMG 脚本在本机因 Finder AppleEvent 超时失败，交付的 DMG 由 `apps/desktop/scripts/package-macos.sh` 使用 `hdiutil` 直接生成并通过 `hdiutil verify`。

桌面应用已在当前机器实际启动并成功渲染主界面；此前通过 UI 输入“核对运费”并回车后，SQLite 中确实新增了该任务，验证了打包后的 Vue、Tauri IPC 和 Rust 本地写库链路。最终发布包重新生成后再次启动，主界面正常显示该本地任务。

## 尚未完成的真机验收

以下项目需要交互授权或实际设备，当前环境无法给出通过结论：

- macOS 麦克风录音、屏幕录制授权、提醒面板显示。
- 从其他应用连续唤起 20 次的焦点、首字、P95 延迟和空闲资源占用。
- 截图取消后的焦点恢复和空任务检查（代码路径已实现）。
- vivo X300 上小组件添加、点击勾选、打字/语音入口和高度适配。
- Android 锁屏/待机下的通知到达时间、通知关闭后恢复。
- Android 录音权限、Photo Picker 选图、移走相册原图后仍可打开。
- 两端真实离线冲突与四设备同步联调。

建议按 [移动端说明](../apps/mobile/README.md) 和 [桌面端说明](../apps/desktop/README.md) 完成上述真机步骤后再作为正式首发验收。
