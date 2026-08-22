# Tasks

- [x] Task 1: 引擎模块新增镜像支持
  - 新增 `engine/src/main/java/com/yoyo/dshmobile/engine/EngineMirrors.kt`：
    - `data class Mirror(val id: String, val name: String, val prefix: String)`，`fun resolve(url: String): String`（`prefix.isEmpty()` 直连返回原样；host ∈ `EngineMirrors.GH_HOSTS` 才 prepend 前缀）。
    - `object EngineMirrors`：`val GH_HOSTS = setOf("github.com","objects.githubusercontent.com","github-release-assets.githubusercontent.com","codeload.github.com")`；`val builtins: List<Mirror>` 复用旧项目内置 25 项（akaere/gh-proxy/ghproxy.net/cdn… 等，**仅代码内置，无自定义入口**）；`fun byId(id): Mirror?`；`fun all(): List<Mirror> = builtins`（不含用户自定义）。
    - `suspend fun speedTest(url: String, mirror: Mirror, timeoutMs: Int = 4000): Long?`：对 `mirror.resolve(url)` 做 HEAD/GET 首字节计时返回延迟 ms，失败 null。
  - `RuntimeUpdater.download(context, manifest, onProgress)` 增加入参 `mirror: Mirror = EngineMirrors.byId("official")!!`；用 `URL(mirror.resolve(manifest.url))` 替换 `URL(manifest.url)`；直连超时被镜像转发规避。
  - `RuntimeUpdater.apply(context, manifest)` 增加入参 `onPhase: (phase: String, percent: Int?) -> Unit = { _, _ -> }`：校验→`("verify",null)`、解压→随 XZ 已读字节/包长给 0..100、停引擎/切换/重启→`(label,null)`；`extractTarXz` 返回时通过累计 `archiveBytesRead` 推算百分比。

- [x] Task 2: ConversationScreen 选择更新 UI（镜像选择 + MD3 进度弹窗）
  - 复写 `doUpdate`：
    1. 读 SharedPreferences `"engine_mirror_id"` 得上次选择并置顶标注。
    2. 弹**镜像选择弹窗**（用 `themedDialog`）：标题、可滚动镜像列表（每行 名称 + 延迟/「检测中…」），启动并发测速（对 `RuntimeUpdater` 的默认 manifest 地址 `EngineMirrors.speedTest`），每行完成即刷新；**点击某行立即** `mirror=该镜像` 关闭弹窗继续更新并 `prefs.putString`.
    3. 弹 **MD3 更新进度弹窗**：标题「引擎更新」、醒目 `ProgressBar`（水平、`android:progress`=percent 或 indeterminate、主色 accent）、阶段文案 + 百分比/字节、取消按钮；跟随 `onProgress(done,total)` 与 `onPhase(phase,pct)` 更新；成功文案「更新完成」后关闭并 `refreshStatus()`；失败文案「更新失败: <原因>」可重试/关闭。
  - 复用现有 `themedDialog`（Content 视图装配）、`dp/color/roundedBg`；进度弹窗用 `MaterialAlertDialogBuilder` + 自定义 contentView 实现更醒目的进度条。
  - `formatBytes/showProgress/hideProgressOnUi/postUi` 保留，进度展示迁入弹窗 contentView。

- [x] Task 3: 修复 About「发送日志」分享失败静默
  - `AboutActivity.sendLogs` 末尾把 `runCatching { startActivity(...) }` 改为 `.onFailure { t -> toast(分享失败); Logs.logEvent(this,"App","share-logs-fail", t) }`。
  - `strings.xml` 新增 `about_logs_share_fail=无法打开分享: %1$s`。

- [x] Task 4: 文案与资源
  - `strings.xml` 新增：`engine_mirror_dialog_title=选择更新源（代理）`、`engine_mirror_last=上次使用`、`engine_mirror_testing=检测中…`、`engine_update_dialog_title=引擎更新`、`engine_update_phase_download=正在下载`、`engine_update_phase_verify=校验完整性`、`engine_update_phase_extract=正在解压`、`engine_update_phase_switch=切换内核`、`engine_update_phase_restart=重启引擎`、`engine_update_done=更新完成`、`engine_update_failed=更新失败: %1$s`、`engine_progress_cancel=取消`。
  - 复用既有 `engine_progress`/`engine_update_downloading`/`engine_update_fail`。

- [x] Task 5: 文档同步（PITFALLS §19 补镜像直连超时约定 / PROJECT_STRUCTURE / USER_HABITS / INDEX / 三件套）

# Task Dependencies
- Task 2、3、5 依赖 Task 1；Task 4 可在 Task 1、2、3 落地后补齐最终文案集合。
