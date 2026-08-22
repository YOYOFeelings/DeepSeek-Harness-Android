# Tasks

- [x] Task 1: 修复「无法更新引擎」空版本误判（RuntimeUpdater + ConversationScreen）
  - `engine/.../RuntimeUpdater.kt`：无需改 `checkForUpdate` 返回值结构；确认 `Manifest(version = "")` 语义（远端 manifest 无版本号，视为「允许主动更新」）。
  - `app/.../ConversationScreen.kt` `runUpdate`：「已是最新」门槛改为 `manifest.version.isNotEmpty() && current.isNotEmpty() && current >= manifest.version` 才跳过；空版本一律放行继续下载。
  - 验证：代码走查该分支；`assembleRelease` 编译通过。

- [x] Task 2: 更新覆盖确认弹窗（ConversationScreen.doUpdate）
  - `doUpdate` 重构：`pending = File(cacheDir,"rootfs-new.tar.xz").exists()`；`installed = EngineRootfs.isExtracted(context)`；任一为 true 时先 `context.themedDialog(标题=更新引擎, message=「更新将覆盖现有引擎数据，确定继续吗？」, negative=取消, positive=确定, onPositive=进镜像选择)`；否则直接进镜像选择。
  - 镜像选择/`runUpdate`/进度弹窗逻辑保持不动（抽成 `proceedUpdate()` 供确认回调复用）。
  - `app/res/values/strings.xml` 新增：`engine_update_overwrite_title`、`engine_update_overwrite_msg`（文案可用中英）。

- [x] Task 3: 启动引擎 MD3 状态弹窗（ConversationScreen.doStart + EngineService 状态）
  - `engine/.../EngineService.kt` companion 新增：`@Volatile var lastStartSeq = 0L`、`@Volatile var lastStartFailed = false`、`@Volatile var lastStartError: String? = null`；`onStartCommand` 与 `restartEngine` 每次 `EngineProcess.start` 尝试后递增 `lastStartSeq` 并写入 `lastStartFailed`/`lastStartError`（成功=失败 false、error null；失败=取 `t.message`）。
  - `ConversationScreen.doStart` 重写：
    1. 捕获 `val seq = EngineService.lastStartSeq`；弹 MD3 状态弹窗（状态行 + 日志区，复用 `themedDialog` + 既有 `logBody` 样式）。
    2. IO 协程 `EngineRootfs.isExtracted(context)`：false → 弹窗显示「运行时未就绪，请先检查并更新引擎」+ 日志，按钮「去更新（进 doUpdate）/ 关闭」，结束。
    3. true → 状态「启动中…」→ `EngineService.start(context)`。
    4. 轮询（~700ms）：刷新弹窗日志尾部；`EngineService.lastStartSeq > seq` 时若 `lastStartFailed` → 显示「启动失败: <原因>」+ 日志 + 按钮「重试（重新走 doStart）/ 关闭」并结束；否则标记已成功。`EngineProcess.probe(context,1500)` 为 true → 状态「运行中」，约 1s 后 `dismiss` + `refreshStatus()` 并结束。
    5. 超时（如 20s）仍无结果 → 显示「仍在启动中，请查看日志」+「关闭」。
  - 复用小工具 `themedDialog`/`dp`/`color`/`roundedBg`；日志用 `Logs.tail(appEventsLog, 40)`。
  - `app/res/values/strings.xml` 新增启动弹窗相关文案（状态行/失败/未就绪/去更新/重试/关闭）。

- [x] Task 4: 主页数据/占用统计修复（HomeScreen）
  - `appStorageBytes()`：删除 `filter { it.name != "rootfs" }`，改为统计 `filesDir` 全部子项（含 rootfs）；测量仍在 `startSystemRefresh` 的 IO 协程中，不阻塞主线程。
  - 插件数量动态刷新：`pluginValue`（当前 `pluginCount()` 静态值）改为可刷新字段；在 `startSystemRefresh` 的 30s 周期内 IO 协程读 `pluginCount` 并回填（首帧先填一次初始值）。
  - 验证：主页「存储占用」不再恒 0；「插件数量」随 plugins 目录变化动态更新。

- [x] Task 5: 编译验证 + 文档同步
  - [x] `./gradlew :app:assembleRelease --no-daemon --max-workers=1`（PITFALLS §18 环境变量）编译通过（BUILD SUCCESSFUL，1m25s）；APK 输出项目根 `deepseek-harness-1.0-release.apk`。
  - [x] `apksigner verify --print-certs` 核对签名 SHA-256 前缀 `5696…25ff` 不变（实测 56968172…b25ff）。
  - [x] 同步 `PITFALLS.md`（新增 §23：空版本误判/覆盖确认/启动 MD3 弹窗/占用含 rootfs，并修订 §22 口径）、`PROJECT_STRUCTURE.md`、`USER_HABITS.md`、`/workspace/INDEX.md`、本 spec。
  - [x] 编译完成后告知用户 APK 下载链接。

# Task Dependencies
- Task 1、2 均改 `ConversationScreen.kt` 且都涉及 `doUpdate/runUpdate`，由同一 sub-agent 顺序完成（先空版本修复后覆盖确认），避免同文件并行冲突。
- Task 3 改 `ConversationScreen.doStart`（与 Task 1/2 同文件不同函数）——可与 Task 1/2 同 sub-agent 一并完成，或独立 sub-agent 串行处理该文件。
- Task 4 改 `HomeScreen.kt`，独立于 Task 1–3，可并行。
- Task 5 依赖 Task 1–4 全部完成。
