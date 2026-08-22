# 引擎代理/镜像下载 + MD3 更新进度弹窗 + 日志导出修复 Spec

## Why
用户导出的 `/workspace/logs_export.zip` 显示两批引擎失败：
1. `update-manifest-http=404` / `update-download-fail FileNotFoundException .../snapshot/snapshot-arm64.tar.xz`（旧构建的**子目录 404**，本轮已用 basename 修复）。
2. `update-download-fail -> SocketTimeoutException: timeout`（**直连 GitHub 拉 75MB rootfs 超时**）——这是国内直连 GitHub 不可靠导致，必须走**镜像/代理**才能稳定下载。

同时用户要求：
- **代理**：加回与旧项目 (`dsh-mobile-apk`) 一致的多镜像加速，但**只有开发者（我）能添加镜像，终端用户不能添加**（即内置镜像、无「自定义源」入口）。下载时先**检测各镜像延迟**，用户**可选择用哪个镜像更新**；且**检测过程中可直接点某个镜像立即开始更新**（不必等全部检测完）。上次选择需记忆。
- **更新 UI/UX**：更新过程用 **MD3 弹窗**，带**醒目进度条**，覆盖「下载 / 校验 / 解压」等阶段。
- **发送日志分享**：修复 About「发送日志」无法弹出系统分享的问题。

## What Changes
- `engine` 模块：新增 `EngineMirrors`（`Mirror(id,name,prefix)` + 内置镜像表 + `resolve(url)`，仅代码内置、无用户自定义）。
- `RuntimeUpdater.download`：新增 `mirror` 入参，先经 `mirror.resolve(manifest.url)` 解析后用镜像地址下载，解决直连超时；失败记日志。
- `RuntimeUpdater.apply`：新增 `onPhase` 回调（阶段文案 + 0..100 或 null=不确定），实现解压阶段进度。
- `app/.../ConversationScreen.kt`：重做 `doUpdate` → 先弹**镜像选择弹窗**（并发测延迟、逐行显示、点某行即立刻用它更新、持久化选择），选定后弹 **MD3 进度弹窗**（醒目 ProgressBar + 阶段/百分比 + %字节），成功/失败有明确反馈。
- `app/.../AboutActivity.kt`：`sendLogs` 的 `startActivity` 失败不再静默，改为 toast + 写日志（修复「无法弹出分享」）。
- `app/res/values/strings.xml`：新增镜像/进度弹窗相关文案。
- 文档同步：`PITFALLS.md`（§19 补镜像直连超时/转发约定）、`PROJECT_STRUCTURE.md`、`USER_HABITS.md`、`/workspace/INDEX.md`、本 spec。

## Impact
- 影响代码：`engine/.../RuntimeUpdater.kt`（新增 + 新增文件 `EngineMirrors.kt`）、`app/.../ConversationScreen.kt`、`app/.../AboutActivity.kt`、`app/res/values/strings.xml`。
- 受影响 spec：无重叠。
- 回归风险（⚠️）：`RuntimeUpdater.download` 加了 `mirror` 参数（默认参数避免破坏其它调用）；`RuntimeUpdater.apply` 加 `onPhase` 默认参数。`EngineRootfs/EngineProcess/EngineService` 与端口 3080 的启动链路**不改**；`ConversationScreen` 之外若还有 `doUpdate` 调用需复核（本轮仅此一处）。

## ADDED Requirements

### Requirement: 镜像选择 + 直连检测 + 立即更新
引擎更新 SHALL 先展示镜像选择弹窗：内置镜像（仅开发者可添加、无用户自定义入口），各镜像并发测速并逐行刷新延迟；用户**可随时点击某行为该镜像立即开始更新**（无需等全部检测完）；上次选择的镜像 SHALL 被持久化，下次默认排在最前。

#### Scenario: 选择镜像即时更新
- **WHEN** 用户点击镜像某一行（即使其余镜像仍在检测）
- **THEN** 弹窗关闭、记住该镜像、立即用该镜像走下载

#### Scenario: 记忆上次选择
- **WHEN** 用户下次进入更新
- **THEN** 上次选中的镜像置顶标注「上次使用」

### Requirement: MD3 醒目更新进度弹窗
更新过程 SHALL 弹出 MD3 样式弹窗，含醒目横向进度条、阶段文案（检测/下载/校验/解压/应用）与百分比（下载/解压为确定进度，检测/校验/切换为不确定），失败时显示原因并可重试/关闭。

### Requirement: 引擎下载走镜像
`RuntimeUpdater.download` SHALL 用选定镜像的 `resolve()` 改写下载地址（对 GitHub 域 prepend 镜像前缀），避免直连超时；失败记 `update-download-fail` 并返回 null。

### Requirement: 解压阶段进度
`RuntimeUpdater.apply` SHALL 报告各阶段（校验/解压/停引擎/切换/重启）；解压阶段按已读 XZ 字节/包大小给出 0..100 确定进度，其余阶段为不确定进度。

### Requirement: 发送日志失败不再静默
`AboutActivity.sendLogs` 若系统分享启动失败 SHALL toast 失败原因并写入日志，而非静默无反应。

#### Scenario: 无处分享
- **WHEN** 设备没有任何可处理 `application/zip` 分享的应用
- **THEN** 提示分享启动失败原因（不崩溃、不静默）

## MODIFIED Requirements

### Requirement: 日志 ZIP 导出（保存/发送）
保持「保存=SAF 落盘」「发送=系统分享」不变；仅修复发送环节的异常反馈与失败提示。

## REMOVED Requirements
（无删除项）