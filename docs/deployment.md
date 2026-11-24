# 部署与运维

DailyPlan 首版只使用 Docker 运行服务端中间件：Go API、专用 MySQL 和 whisper.cpp 都在 Compose 内，不需要在宿主机安装或启动 MySQL。

## 前置条件

- Docker Engine 或 Docker Desktop 29.x。
- 同一 CPU 架构的构建机。Apple Silicon 上可直接构建 `linux/arm64` 镜像；给 x86 服务器交付时应在 x86 构建机构建，或明确使用 `--platform linux/amd64` 交叉构建。
- 放行局域网到宿主机 `8787` 端口的访问。只在公司内网使用，不要把 API 直接暴露到公网。

本次验收使用的版本：

- Docker 29.9.3
- MySQL 8.4.13
- whisper.cpp 1.8.2

## 首次部署

从仓库根目录执行：

```bash
cp deploy/.env.example deploy/.env
mkdir -p deploy/models
```

编辑 `deploy/.env`：

- `DAILYPLAN_API_TOKEN`、`MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD` 都换成独立强随机值，可用 `openssl rand -hex 32` 生成。
- `DAILYPLAN_PORT` 默认 `8787`。
- `DAILYPLAN_DATASET_ID` 默认 `office-default`。恢复旧备份时应保持原数据集 ID。
- `WHISPER_MODEL_FILE` 默认 `ggml-base.bin`。

下载中文转写模型：

```bash
curl -fL --retry 3 \
  -o deploy/models/ggml-base.bin \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin
shasum -a 256 deploy/models/ggml-base.bin
```

本次下载的 `ggml-base.bin` 大小约 141 MiB，SHA-256 为：

```text
60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe
```

模型文件不提交到 Git。未放置模型时 API 仍可启动，但录音转写任务会失败；放入模型后可通过转写重试接口重新排队。

构建并启动：

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env build
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d
docker compose -f deploy/docker-compose.yml --env-file deploy/.env ps
curl -fsS http://127.0.0.1:8787/healthz
```

健康检查应返回：

```json
{"status":"ok"}
```

首次镜像构建已处理 whisper.cpp 的 Docker CPU 探测问题，使用 `-DGGML_NATIVE=OFF` 生成兼容镜像。首次构建会编译 whisper.cpp；后续只改 Go 代码时通常几秒即可完成。

## 内网地址与客户端接入

在 Mac 上查看局域网地址：

```bash
ipconfig getifaddr en0
```

客户端填写：

```text
服务器地址：http://<构建机局域网 IP>:8787
访问令牌：deploy/.env 中的 DAILYPLAN_API_TOKEN
```

API 状态检查：

```bash
set -a
source deploy/.env
set +a
curl -fsS \
  -H "Authorization: Bearer ${DAILYPLAN_API_TOKEN}" \
  "http://127.0.0.1:${DAILYPLAN_PORT:-8787}/v1/status"
```

返回内容应包含 `protocolVersion: 1`、数据集 ID、恢复版本和当前游标。

## 持久化数据

Compose 使用三个命名卷：

- `dailyplan_dailyplan-mysql`：MySQL 数据。
- `dailyplan_dailyplan-attachments`：原图和 WAV 原件。
- `dailyplan_dailyplan-backups`：容器内导出的备份。

执行 `docker compose down` 不会删除这些卷；`docker compose down -v` 会删除，属于不可逆操作。升级 API 镜像时：

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env build api
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d
```

容器重建不会影响任务和附件。本次验收已实际执行一次 `down` 后重新 `up`，任务、完成状态、转写和附件哈希均保持不变。

## 导出备份

先停止 API，保证数据库和附件在同一个静态时间点：

```bash
backup="dailyplan-$(date +%Y%m%d-%H%M%S)"
docker compose -f deploy/docker-compose.yml --env-file deploy/.env stop api
docker compose -f deploy/docker-compose.yml --env-file deploy/.env \
  run --rm api export --out "/backups/$backup"
docker cp "dailyplan-api-1:/backups/$backup" "./$backup"
docker compose -f deploy/docker-compose.yml --env-file deploy/.env start api
```

备份目录应包含：

- `manifest.json`
- `database.sql`
- `attachments/<storage-key>`

`manifest.json` 记录数据库版本、任务数、恢复版本、数据库摘要、模型版本/摘要和每份附件的 SHA-256。导出会在发布前重新校验附件大小与摘要。

将备份目录复制到另一台机器或离线介质后再清理本地临时副本。

## 恢复到空服务器

恢复目标必须同时满足：

- MySQL 的 `dailyplan` 数据库没有任何表。
- 附件目录为空。

**不要先启动 API**。API 启动时会执行迁移并创建表，之后数据库就不再是空库。

新服务器上将备份目录放到例如 `/tmp/dailyplan-backup`，然后：

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d db
docker compose -f deploy/docker-compose.yml --env-file deploy/.env \
  run --rm \
  -v "/tmp/dailyplan-backup:/backup:ro" \
  api restore --in /backup
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d
```

容器内以 UID 999 运行，挂载的宿主机备份目录需要可读：

```bash
chmod -R a+rX /tmp/dailyplan-backup
```

恢复流程会先校验数据库摘要和全部附件，再导入数据库并安装附件。恢复成功后：

- `restoreVersion` 增加 1。
- 任务数和附件数与 manifest 一致。
- 附件 SHA-256 与 manifest 一致。
- 转写文本、完成状态和作息一并保留。

如果目标环境需要清空旧数据，以下命令会删除数据卷，必须明确确认：

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env down
docker volume rm dailyplan_dailyplan-mysql dailyplan_dailyplan-attachments
```

## 损坏备份

恢复会在导入前校验每个附件。损坏文件会报告附件 ID 和存储文件名，例如：

```text
attachment <attachment-id> (<attachment-id>.png) checksum mismatch
```

数据库摘要有误时会报告 `database dump checksum mismatch`。不要在恢复失败后启动 API 继续写入。

## 日志与排障

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs -f api
docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs -f db
```

常见检查：

- `healthz` 失败：查看 API 日志和 MySQL 健康状态。
- 手机能解析名称但打不开：优先改用同一 Wi-Fi 下的 IPv4，并检查 macOS 防火墙。
- 录音一直待转写：确认 `/models/ggml-base.bin` 存在、`WHISPER_MODEL_FILE` 正确、`transcriptionEnabled` 为 `true`。
- whisper 构建失败：确认使用了当前 Dockerfile 的 `-DGGML_NATIVE=OFF`，不要恢复为目标机器指令集探测。
- 备份恢复提示目录非空：先确认是否误启动了 API；空库和空附件卷是恢复的硬条件。
