# Tasks

- [x] Task 1: UpdateManager 扩展历史版本能力
  - [x] `ReleaseInfo` 增加 `publishedAt: String = ""`（默认空，不破坏现有调用）。
  - [x] 新增 `suspend fun fetchHistoryReleases(): List<ReleaseInfo>`：GET `https://api.github.com/repos/YOYOFeelings/DeepSeek-Harness-Android/releases`，逐条解析 tag_name/published_at/首个 .apk asset（browser_download_url），过滤掉 tag 等于最新版者；失败返回空列表；全程 runCatching。
- [x] Task 2: 启动弹窗简化（ConversationScreen.kt）
  - [x] 删除 `showStartDialog()`、`StartUi`、`showStartNotReady()`。
  - [x] 新增 `showStartDialogSimple(): AlertDialog`（不确定转圈 + 文案「启动中…」，`setCancelable(false)`，无按钮）。
  - [x] 新增 `showUpdateRequiredDialog()`（复用原 not_ready 引导：文案「引擎文件未就绪，请先检查更新」+「关闭」/「去更新」→ `doUpdate()`）。
  - [x] 新增 `showStartErrorDialog(errorMsg: String)`（标题「启动失败」+ 错误信息 + 最近 30 行日志 `Logs.tail(Logs.appEventsLog(context),30)` + 按钮「重试」→`doStart()` /「关闭」）。
  - [x] 重写 `doStart()`：`showStartDialogSimple()` → 检查 rootfs 解压（未就绪关闭转圈弹 `showUpdateRequiredDialog`）→ `EngineService.start` → 20s 探活循环 → 成功刷新状态；失败/超时关闭转圈弹 `showStartErrorDialog`（错误取 `lastStartError`，兜底 `engine_start_failed_unknown`）。
  - [x] 清理 `doStart()` 对旧 `StartUi`/日志展开逻辑的所有引用。
- [x] Task 3: 更新页 GUI 改进（UpdateScreen.kt）
  - [x] 版本卡片右上加「刷新」入口，点击重新执行 `startFetch()`（同时刷新最新版与历史列表）。
  - [x] 进度条高度 ≥8dp、加圆角；状态/大小文案细化（下载中按钮文字 + 百分比下方 `已下载/总大小`）。
  - [x] 下载完成按钮态切换（「安装」手动触发 /「已下载」），并按逻辑启用/禁用。
- [x] Task 4: 往期版本列表（UpdateScreen.kt）
  - [x] 更新说明卡片下方加可折叠「往期版本」区：标题行（点击展开/收起）+ 条目列表（LinearLayout 循环）。
  - [x] 每条目：版本号、发布日期（`publishedAt` 若有）、「下载」按钮。
  - [x] 点击下载：复用主进度区，进度回调标注正在下载的版本号；完成走 `UpdateManager.install`。
  - [x] 列表为空/失败显示「暂无历史版本」；刷新时同步重建列表。
- [x] Task 5: 字符串资源
  - [x] `strings.xml` 新增：`engine_retry`、`engine_start_failed_title`、`engine_start_failed_unknown`、`engine_no_rootfs_update_required`、`update_history_title`、`update_history_empty`、`update_history_download`、`update_history_downloading`、`update_history_install`、`update_refresh`。
- [x] Task 6: 编译验证
  - [x] `:app:assembleRelease` 编译通过、无新增警告；签名 SHA-256 前缀 `5696…25ff`；给出 APK 下载链接。
  - [x] 确认未越界修改其他模块（引擎后端/更新核心/RuntimeUpdater 不动）。

# Task Dependencies
- Task 5（字符串）可与 Task 2/3/4 并行，但实现引用前需先落地。
- Task 4 依赖 Task 1（`fetchHistoryReleases`）。
- Task 2/3 相互独立，可并行。