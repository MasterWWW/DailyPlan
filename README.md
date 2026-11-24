# DailyPlan

DailyPlan 是一个单用户、离线优先的每日工作计划工具。macOS 桌面端负责快速记录、桌面清单和提醒，Android 端负责主清单、录音、截图和桌面小组件；两端通过公司内网的 Go + MySQL 服务同步。

仓库结构与实施边界见 [实施计划](docs/implementation-plan.md)。当前首发交付包含：

- `server/`：Go `/v1` 同步 API、附件、whisper.cpp 转写队列、导出与恢复命令。
- `deploy/`：API 与专用 MySQL 的 Docker Compose 配置。
- `apps/desktop/`：Tauri 2 + Vue 3 + Rust 的 macOS 客户端，同一工程保留 Windows 构建入口。
- `apps/mobile/`：Kotlin Multiplatform + Compose Multiplatform 的 Android 客户端及 Glance 小组件。
- `shared/protocol/`：OpenAPI、JSON 样例和两端共用的作息/提醒规则用例。

## Docker 快速启动

服务端的 API、专用 MySQL 和 whisper.cpp 都运行在 Compose 中，不需要在宿主机安装或启动 MySQL。

```bash
cp deploy/.env.example deploy/.env
```

编辑 `deploy/.env`，把 `DAILYPLAN_API_TOKEN`、`MYSQL_PASSWORD` 和 `MYSQL_ROOT_PASSWORD` 换成独立强随机值，例如用 `openssl rand -hex 32` 生成。准备中文转写模型：

```bash
mkdir -p deploy/models
curl -fL --retry 3 \
  -o deploy/models/ggml-base.bin \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin
```

构建并启动：

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env build
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d
curl -fsS http://127.0.0.1:8787/healthz
```

健康检查返回 `{"status":"ok"}` 后，在客户端填写 `http://<运行 Docker 的机器内网 IP>:8787` 和 `deploy/.env` 中的访问令牌。完整部署、模型、备份和恢复步骤见 [部署说明](docs/deployment.md)。

### 桌面端

```bash
cd apps/desktop
pnpm install --frozen-lockfile
pnpm tauri dev
```

macOS 首次使用需要允许屏幕录制和麦克风权限。开发者签名、安装包及真实设备验收记录见 [桌面端说明](apps/desktop/README.md)。

### 移动端

```bash
cd apps/mobile
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :androidApp:assembleDebug
```

APK 输出在 `apps/mobile/androidApp/build/outputs/apk/debug/`。Android 真机、vivo X300 小组件和通知验收入口见 [移动端说明](apps/mobile/README.md)。

## 验证

先按“移动端”一节设置 JDK 21 和 Android SDK 环境变量，再执行：

```bash
make test
```

共享规则用例由 Rust 和 Kotlin 测试直接读取，协议 JSON 样例由 Go、Rust 和 Kotlin 测试直接读取。Go 单元测试不需要数据库；完整服务端 MySQL 集成测试可用 Docker 运行：

```bash
make test-go-integration
```

该目标启动临时 MySQL 容器，执行测试后自动清理。真实 Xcode 和真机步骤在各自说明中列出。

