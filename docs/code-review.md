# DailyPlan 代码审查报告

审查日期：2026-11-24（Asia/Shanghai）

## 审查范围与方法

- 仓库尚无任何提交，全部文件未跟踪，审查表面为整个源码树（排除 `node_modules`、`dist`、`build`、`*.lock`、图标与二进制资源）。
- 规格依据：`docs/implementation-plan.md`（产品/技术规格）、`docs/acceptance.md`（验收记录）、`shared/protocol/openapi.yaml` 与 `shared/protocol/rules/cases.json`（协议规格）。
- 标准依据：仓库内无 `CODING_STANDARDS.md`、`CONTRIBUTING.md`、`AGENTS.md` 等文档化规范，Standards 轴采用通用坏味道基线（Fowler《重构》第 3 章），均为判断性建议；工具已强制的事项（gofmt、cargo fmt、vue-tsc）不重复列出。
- 双轴独立审查：Standards（编码标准）与 Spec（规格符合度），不合并、不重排。

## 结论速览

| 轴 | P0 | P1 | P2 | 存疑 | 合计 |
| --- | --- | --- | --- | --- | --- |
| Standards | 0 | 2 | 6 | 0 | 8 |
| Spec | 0 | 3 | 4 | 0 | 7 |

上表统计首次审查发现；原「关闭主窗口后的独立便签」存疑项已经需求方确认为必需行为，不再计入缺陷。

最严重问题：Spec 轴「上班提醒内容与规格正文矛盾」（规格内部不一致，需产品裁定）；Standards 轴 `db.rs` 远程附件写入的重复实现。

## Standards 轴发现

### P1

- Duplicated Code：`apps/desktop/src-tauri/src/db.rs:961` 的 `insert_remote_attachment` 与 `db.rs:994` 的 `insert_remote_attachment_conn` 约 30 行 SQL 几乎相同，仅入参类型（`&Transaction` / `&Connection`）不同；`db.rs:1082` `read_pending` 与 `db.rs:470` `pending_operations` 重复相同的行映射。建议事务版转调连接版、提取共用映射函数。
- Duplicated Code：`apps/mobile/shared/src/commonMain/kotlin/com/dailyplan/shared/data/PlanRepository.kt` 中 `applySnapshot`（411）、`applyChanges`（470）、`reapplyPending`（515）三处重复相同的 work_settings 拷贝块（`workStart` 等 5 个字段，见 427、498、545 行）。建议提取 `copyWorkSettings` 助手。

### P2

- 死代码：`apps/mobile/shared/src/commonMain/kotlin/com/dailyplan/shared/sync/SyncApi.kt:162` 私有扩展 `Change.pullKey()` 未被使用；`PlanRepository.kt:323` `queueSync() = Unit` 为空占位实现。
- 死参数：`server/internal/store/mysql/mysql.go:912` `FinishTranscription` 第 5 个参数 `_ string` 被忽略，调用方传入 `WhisperModelLabel` 却从不存储，建议落库或删除该参数。
- 脆弱实现：`mysql.go:153` `splitStatements` 按 `;` 朴素切分，遇注释或字符串中的分号会切错；`PlanRepository.kt:437` 用 `payloadJson.contains("\"task.create\"")` 嗅探操作类型，应解码 JSON 后判断 `type` 字段。
- 静默吞错：`apps/desktop/src-tauri/src/db.rs:671` `read_attachment_path(&conn, ...).unwrap_or(None)` 吞掉数据库读错误，附件定位失败会被误判为「无本地文件」，建议显式记录错误。
- Primitive Obsession：`apps/mobile/shared/src/commonMain/kotlin/com/dailyplan/shared/rules/Rules.kt:161`、`Rules.kt:167` 用 `LocalDate.parse` / `LocalTime.parse` 反复解析日期时钟，规则模块可收拢为专用小类型。

## Spec 轴发现

### P1

- 缺失｜Android 无「已查看」入口。规格「点已查看：收起这台设备的本次提醒」，但 `PlanRepository.kt:393` 的 `acknowledgeReminder` 在移动端无任何 UI 调用（桌面端 `apps/desktop/src/App.vue:424` 有按钮），`apps/mobile/androidApp/src/main/kotlin/com/dailyplan/android/ReminderNotifier.kt` 的通知也无操作按钮。需在通知/清单页补入口。
- 偏差｜桌面截图未按规格使用 scap。规格「截图用 scap 调 ScreenCaptureKit」，实际 `apps/desktop/src-tauri/src/screenshot.rs:240` 用 `/usr/sbin/screencapture` 子进程。功能等价（无损 PNG、多显示器、Retina 换算），但选型与规格不符，且未过真机授权验收。处理时二选一：改实现或更新规格文档。
- 矛盾｜上班提醒内容与规格正文相反。规格正文「上班弹出紧急、临期和逾期事项」，但 `apps/desktop/src-tauri/src/rules.rs:173` `active_reminder` 与移动端 `Rules.kt` 把全部未完成事项放入提醒；共享用例 `attention_order_prefers_overdue_then_urgent_then_due_soon` 期望 morning 包含普通远期任务，即实现与用例一致、与正文矛盾。规格内部不一致，需产品裁定后统一正文或实现，并同步两端代码与共享用例。

### P2

- 缺失｜同步失败无递增退避。规格「连接失败则逐步延长重试间隔」；桌面 `apps/desktop/src-tauri/src/sync.rs:45-47` 固定 15 秒（成功）/ 30 秒（失败），移动端 `SyncWorker.kt:42` 固定 15 分钟周期 + 前台 15 秒轮询，均无递增退避。
- 缺失｜Android 缺网络变化与回前台触发。规格「网络变化和本地操作都会触发连接」，实现只有启动、前台轮询、本地操作 `SyncWorker.kt:56` `syncNow`、开机广播，无 ConnectivityManager 监听与 onResume 显式触发。
- 偏差｜转写回写后 `title_source` 置为 manual（`mysql.go` `FinishTranscription`）。行为满足「手动命名保留标题」，但来源语义失真，建议单独标记来源。

### 需求确认（非缺陷）

- 需求确认｜规格「关闭主窗口后仍能在桌面直接勾选便签」：需求方确认关闭主窗口后必须保留独立便签窗口。已实现独立常驻便签，关闭主窗口只隐藏主清单，便签继续可勾选，托盘可重新显示；不作为缺陷处理。

## 已核实达标项（勿误伤）

以下项已与规格核对一致，处理上表问题时不要改动其行为：

- 服务端 `op_id` 幂等重放：唯一约束 + 重传返回原回执。
- `SELECT ... FOR UPDATE` 游标推进、新设备 `REPEATABLE READ` 快照基准游标。
- 附件事务外流式写入、长度与摘要校验、原子归档、按 `attachment_id` 唯一建转写作业。
- whisper 转写队列重启后重新领取中断作业。
- `export` / `restore` 子命令、逻辑备份与摘要核对、损坏拦截。
- 桌面 Rust 与移动 Kotlin 共享 `shared/protocol/rules/cases.json` 规则用例与协议 JSON 样例，测试直接读取断言。
- 桌面独立便签：关闭主窗口只隐藏主清单，便签窗口继续保留并可直接勾选，点标题可打开主窗口详情，右上角收起后可从托盘恢复。这是需求方确认的必需行为，不得移除或改为随主窗口一起关闭。

## 建议处理顺序

1. 产品裁定：上班提醒范围（Spec P1 矛盾），确定后同步正文、两端实现与共享用例。
2. 截图选型：改 scap 实现或更新规格（Spec P1）。
3. Android「已查看」入口（Spec P1）。
4. Standards 两处重复代码（P1）。
5. 其余 P2 按需处理；关闭主窗口后的独立便签已由需求方确认保留。

## 验证方式

- 服务端：`make test-go`；完整 MySQL 集成测试用 `make test-go-integration`（需 Docker）。
- 桌面：`make test-rust` 会先构建前端，再执行 Rust 测试；静态检查用 `cd apps/desktop/src-tauri && cargo fmt --check && cargo clippy --all-targets -- -D warnings`。
- 移动端：设置 JDK 21 与 Android SDK 后执行 `make test-mobile`；构建 APK 用 `cd apps/mobile && ./gradlew :androidApp:assembleDebug`。

## 本轮修复记录

处理日期：2026-11-24（Asia/Shanghai）

| 原发现 | 处理 |
| --- | --- |
| Standards P1：`db.rs` 远程附件写入与待传行映射重复 | 合并为单一 `insert_remote_attachment`，`read_pending` 与 `pending_operations` 共用行映射函数 |
| Standards P1：`PlanRepository` 三处 work_settings 拷贝 | 提取 `copyWorkSettings` 扩展 |
| Standards P2：`pullKey`、`queueSync` 死代码 | 删除 |
| Standards P2：`FinishTranscription` 死参数 | 从 `store.Store`、MySQL 实现、worker 和集成测试中移除 |
| Standards P2：`splitStatements` 朴素切分 | 改为识别单/双引号、反引号、行注释和块注释；新增 `mysql_test.go` 覆盖分号转义 |
| Standards P2：`payloadJson.contains` 嗅探类型 | 解码 `Operation` 后按 `type` 与 `taskId` 判断，无效队列表项仍保留 |
| Standards P2：附件路径读取吞错 | `mark_attachment_remote` 改为向上返回数据库错误 |
| Standards P2：规则模块反复解析日期时钟 | Kotlin 规则收拢 `TaskSchedule` 与 `WorkdayWindow`，每项任务只解析一次 |
| Spec P1：上班提醒范围与正文矛盾 | 按规格正文统一：上午只提醒逾期、紧急和临期事项，傍晚提醒全部未完成事项；两端实现和共享用例同步 |
| Spec P1：Android 无“已查看”入口 | 通知新增“已查看”操作，今天页新增提醒卡片与按钮；通知和卡片均收起本设备本次提醒 |
| Spec P1：桌面截图未用 scap | 保留系统 `screencapture` 实现，更新规格与 README 说明；功能保持无损 PNG、Retina 和外接显示器等价 |
| Spec P2：同步失败无递增退避 | 桌面失败后 30s、60s、120s、240s、300s；Android 前台轮询同样递增，WorkManager 立即任务使用指数退避 |
| Spec P2：Android 缺网络变化与回前台触发 | `MainActivity` 在前台启动时立即触发同步，并监听网络恢复；回到前台同时补展示提醒 |
| Spec P2：转写后 `title_source` 语义 | 保持现有公共枚举与行为，明确 `title_source` 只区分占位与最终标题，转写来源由附件 `transcript` 保存 |
| 需求确认：关闭主窗口后的独立便签 | 需求方确认必须保留。已实现独立常驻便签窗口：关闭主窗口仅隐藏主清单，便签继续可勾选；便签右上角可收起，托盘可重新显示 |

回归结果：

- `cd server && go test ./...`：16 个测试通过。
- `cd apps/desktop/src-tauri && cargo fmt --check && cargo test`：6 个测试通过。
- `cd apps/desktop && pnpm build`：类型检查与生产构建通过。
- `cd apps/mobile && ./gradlew :shared:testDebugUnitTest :androidApp:assembleDebug`：3 个共享测试通过，Debug APK 构建成功。

## 第二轮审查与修复

处理日期：2026-11-24（Asia/Shanghai）

本轮对修复后的工作树重新审查，重点检查提醒恢复、附件并发、同步错误传播、备份回滚、Android 生命周期和录音边界。复审发现的两项问题已完成修复，最后一轮检查未再发现需要留待后续处理的代码级 P0/P1/P2；仍只能由真机完成的项目列在文末。

| 原发现 | 处理 | 回归验证 |
| --- | --- | --- |
| 桌面提醒只按日期/时段比较，重启后无法恢复 | 每次按当前任务集合重算；已查看前重启可从 evaluation 恢复；任务变化即时刷新，首次展示才主动显示窗口 | Rust 共享规则测试、桌面构建 |
| 已有附件元数据但原件缺失/损坏仍返回成功 | 上传串行化；校验并修复已有原件；仅确认数据库仍无元数据时清理失败文件；发布比较 `ByteSize` | HTTP 集成测试新增同 ID 重传修复、并发不同内容冲突；MySQL 测试新增大小冲突 |
| 附件上传失败被同步流程覆盖为成功 | 桌面端保留附件错误并以失败结束；移动端继续拉取变更后抛出附件错误；待同步数量包含 `remote_ready=0` 附件 | 桌面 SQLite 测试、Android 构建 |
| 备份恢复后段失败会留下非空数据库，无法重试 | 导入前校验清单；导入后失败时清理已安装附件并删除全部表；成功前再校验数据集、任务数和附件元数据 | backup 清理测试、Go 全量集成测试 |
| Android 列表只显示图片占位符 | 提取共享采样解码逻辑，列表实际显示裁剪缩略图，失败时保留占位 | Android Debug APK |
| Android 前台轮询退后台后继续运行 | 使用 `repeatOnLifecycle(STARTED)` 包裹前台同步循环 | Android Debug APK |
| Android 录音无上限 | 限制 20 分钟（38,400,000 字节 PCM），达到上限停止写入并释放录音硬件 | Android Debug APK |
| Rust 规则一次任务重复解析日期时间 | `classify` 返回已计算截止时刻，排序复用 | Rust 测试 |
| 死代码和多余通知依赖 | 删除 `ValidateManifest`、未用命令/API、移动端无调用方法及 `tauri-plugin-notification`，同步更新文档 | `cargo test`、Vue 构建 |
| 审查文档测试数不一致 | 更新 README 与验收记录；补充第二轮审查记录 | 文档核对 |
| 复审中发现恢复清单未校验 `storage_key` | 安装原件前核对数据库元数据中的存储键 | Go 集成测试 |
| 复审中发现恢复超时或取消时回滚复用原上下文 | 回滚改用独立 30 秒上下文，确保目标库仍能清空并重试 | 备份回滚单测、Go 全量集成测试 |
| 复审中发现 Rust 测试模块位于公共函数之前 | 将 `sync.rs` 测试模块移至文件末尾，消除 Clippy 结构错误 | `cargo clippy --all-targets -D warnings` |
| 复审中发现干净工作树下总测试入口不可用（缺 `dist`，且未说明移动端 JDK/SDK 前置） | `make test-rust` 先生成前端产物；文档改为先前端构建、后 Rust 测试，并注明移动端环境变量 | 删除 `dist` 后带 JDK/SDK 环境执行 `make test` |

当前自动化结果：

- `make test-go-integration`：29 个 Go 测试通过，9 个包；`go vet ./...` 通过。
- `JAVA_HOME=... ANDROID_HOME=... make test`：Go 单元测试、6 个 Rust 测试和 3 个共享 Kotlin 测试通过。
- `make test-rust`：先前端构建，后 6 个 Rust 测试通过（3 个测试套件）；`cargo fmt --check` 与 `cargo clippy --all-targets -- -D warnings` 通过。
- `cd apps/desktop && pnpm build`：Vue 类型检查与生产构建通过。
- `cd apps/mobile && ./gradlew :shared:testDebugUnitTest :androidApp:assembleDebug`：3 个共享测试通过，Debug APK 构建成功。

最终复审结论：原审查项、复审新发现项和自动化回归均已闭环，未再发现代码级 P0/P1/P2；剩余风险仅为真机授权、系统调度和真实网络环境。

仍待真机验证：macOS 麦克风/屏幕录制/提醒面板授权与焦点交接，Android 真机通知、锁屏和省电场景，以及 vivo X300 小组件和两端真实离线同步。

## 第三轮复查与处理

处理日期：2026-11-24（Asia/Shanghai）

对本轮新发现的两处 P2 及观察项逐条复核当前代码，结论如下：

| 原发现 | 复核结论 | 处理 |
| --- | --- | --- |
| P2｜`api.go` 上传错误语义不一致 | 复查不成立。`server/internal/api/api.go:216`（重传路径）与 `api.go:257`（新附件路径）的 `WriteTemp` 错误分支均调用 `a.fail`，`storage.ErrTooLarge` 统一映射 413 `attachment_too_large`，大小/摘要不匹配统一 422 `checksum_mismatch`。原发现引用的 `api.go:189` / `api.go:252` 行号陈旧，与当前代码不符 | 无需改动 |
| P2｜Android 精确闹钟被永久拒绝会收不到提醒 | 已有回退。`ReminderScheduler.kt` 在 `SDK >= S && !canScheduleExactAlarms()` 时回退到 `setAndAllowWhileIdle`，符合规格「未获授权时使用系统允许的时间窗口」 | 无需改动，作为真机验证项保留 |
| 观察｜`OccurredAt` 服务端接收但未用于时间戳计算 | 属预留字段，不影响当前正确性 | 暂不改动 |
| 文档｜Go 测试数 29 与 `go test ./...` 的 19 不一致 | 两者是不同命令：`go test ./...`=19（单元，无 DB），`make test-go-integration`=29（含 Docker 集成测试），文档已分别标注 | 无需改动 |
