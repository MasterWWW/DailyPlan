# DailyPlan macOS 桌面端

Tauri 2 + Vue 3 + Rust。首版交付 macOS 13+ 的 arm64 应用，同一工程保留 Windows 构建入口。

## 开发运行

```bash
cd apps/desktop
pnpm install --frozen-lockfile
pnpm tauri dev
```

本次构建使用 Node 25、pnpm 11.25、Rust 1.95 和 macOS Command Line Tools，不要求完整 Xcode。

## 构建安装包

```bash
cd apps/desktop
./scripts/package-macos.sh
```

或在仓库根目录执行：

```bash
make package-macos
```

脚本先运行 `pnpm tauri build --bundles app`，再用 `hdiutil` 直接生成 DMG，不依赖 Tauri 默认 DMG 脚本中的 Finder AppleEvent。产物：

```text
apps/desktop/src-tauri/target/release/bundle/macos/DailyPlan.app
apps/desktop/src-tauri/target/release/bundle/dmg/DailyPlan_0.1.0_aarch64.dmg
```

本次产物为 ad-hoc 签名，没有 Developer ID 签名和公证。跨机器分发后首次打开可能被 Gatekeeper 拦截：在 Finder 中右键应用选择“打开”，或到“系统设置 > 隐私与安全性”允许打开。

## 首次授权

1. 麦克风：首次录音时允许“麦克风”。
2. 屏幕录制：首次截图时允许“屏幕与系统音频录制”，授权后通常需要重启 DailyPlan。
3. 本地网络：连接公司内网 API 时允许本地网络访问。

截图使用系统 `/usr/sbin/screencapture` 截取当前显示器，再在 DailyPlan 自己的全屏选择窗口中框选。因此没有使用第三方 `scap` 依赖，也不需要 Xcode 工程。

## 日常操作

- 默认文字快捷键：`Command+Shift+Space`。
- 默认截图快捷键：`Command+Shift+Enter`。
- 快捷键可在设置中修改；修改后立即重新注册。
- 快速记录按回车保存并收起，Esc 取消；截图选择按 Esc 或右键取消。
- 勾选框只切换对应待办；点击标题打开详情。
- 录音转写失败时可在详情中原位重试，录音原件保持不变。
- 设置内可填写服务器地址、访问令牌、工作时间、工作日、下班前提前量和开机自启。
- 独立桌面便签默认显示，可直接勾选；点标题打开主界面详情，右上角可收起。
- 关闭主窗口只隐藏主清单，桌面便签继续保留；托盘可重新显示便签或打开完整清单。

## 本地数据

本地 SQLite、附件和待同步操作保存在：

```text
~/Library/Application Support/com.dailyplan.desktop/
```

访问令牌保存在 macOS Keychain，不写入 SQLite。断网时任务、截图、录音和完成状态先写本地；恢复网络后按顺序补传。

## 验证

```bash
cd apps/desktop
pnpm install --frozen-lockfile
pnpm build

cd src-tauri
cargo fmt --check
cargo test

cd ..
pnpm tauri build --bundles app
```

Tauri 编译期依赖 `apps/desktop/dist`，因此需要先构建前端再运行 `cargo test`。`cargo test` 包含共享规则用例、录音重采样测试，以及 SQLite 关闭重开后的任务/附件/待传状态持久化测试。

本次未完成真实麦克风、屏幕录制授权和连续 20 次全局快捷键焦点恢复的压力验收；这些项目需要在本机交互授权后手工执行，详见 [验收记录](../../docs/acceptance.md)。
