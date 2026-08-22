# 引擎启动状态弹窗 + 修复无法更新 + 更新覆盖确认 + 主页占用统计 Spec

## Why
用户反馈四类问题，均为引擎/主页链路的核心体验缺陷：
1. **引擎无法正常启动**：本 APK 不内置 rootfs（`assets/rootfs` 仅 `.gitkeep`），运行时完全依赖「检查并更新引擎」在线安装；而在线更新链路有致命 bug，导致 rootfs 装不上 → 引擎永远无法启动，且点「启动引擎」无任何可见反馈。
2. **无法更新引擎**：`RuntimeUpdater.checkForUpdate` 返回的 `Manifest.version` 恒为 `""`；`ConversationScreen.runUpdate` 中 `current.isNotEmpty() && current >= manifest.version` 对空版本恒为真 → 已安装 rootfs 后点击更新永远误判「已是最新」并 `return`，更新被静默跳过。
3. **更新无覆盖确认**：点击「检查并更新引擎」直接进镜像选择/下载，从不提示「将覆盖之前的数据」，用户已有文件时被无感覆盖。
4. **主页数据/占用统计不统计**：`HomeScreen.appStorageBytes()` 排除 `files/rootfs`（占用大头）→ 存储占用恒显示 ≈0 MB；「插件数量」在 `buildStatusGrid` 构建时取一次静态快照，永不刷新 → 引擎跑起来后计数仍为 0。

## What Changes
- `app/.../ConversationScreen.kt`：重写 `doStart()` —— 点击「启动引擎」弹出 **MD3 状态弹窗**，实时展示启动动向（检查运行时→启动中→探活中）、实时刷新弹窗内引擎日志尾部；成功显示「运行中」后自动关闭；失败在弹窗内显示**失败原因 + 最近日志**并提供「重试/关闭」；rootfs 未就绪时提示「请先检查并更新引擎」并提供「去更新/关闭」。重写 `doUpdate()` —— 若 `cacheDir/rootfs-new.tar.xz` 已存在或 rootfs 已安装，先弹 **MD3 覆盖确认弹窗**（「更新将覆盖现有引擎数据，确定继续吗？」，取消/确定），确定后才进镜像选择 → 更新进度弹窗（既有实现）。
- `engine/.../RuntimeUpdater.kt`：修复空版本误判——`checkForUpdate` 保持返回，但不再以空 version 阻塞；同时 `runUpdate` 的「已是最新」门槛改为仅在 `manifest.version` 非空且真实 >= 当前版本时才跳过（空版本一律放行，允许用户主动重装覆盖）。
- `engine/.../EngineService.kt`：companion 新增轻量启动结果状态（`lastStartSeq`/`lastStartFailed`/`lastStartError`），`onStartCommand` 与 `restartEngine` 每次启动尝试后写入；供状态弹窗轮询判断「启动中/成功/失败+原因」。不改变公共方法签名。
- `app/.../HomeScreen.kt`：`appStorageBytes()` 重新纳入 `files/rootfs`（测量已在 IO 协程，不阻塞主线程）→ 存储占用显示真实值；「插件数量」改为随 30s 刷新周期在 IO 协程动态读取（不再静态快照）。
- 资源与文案：`app/res/values/strings.xml` 新增引擎启动弹窗 / 覆盖确认 / 主页统计相关文案（引擎模块 strings 若需补充同源）。
- 文档同步：`PITFALLS.md`（空版本误判「已是最新」/ 存储统计排除 rootfs 的取舍）、`PROJECT_STRUCTURE.md`、`USER_HABITS.md`、`/workspace/INDEX.md`、本 spec。

## Impact
- 影响代码：`app/.../ui/screen/ConversationScreen.kt`、`app/.../ui/screen/HomeScreen.kt`、`app/res/values/strings.xml`、`engine/.../RuntimeUpdater.kt`、`engine/.../EngineService.kt`。
- 受影响 spec：`engine-mirror-md3-update-and-log-fix`（更新进度弹窗，已完成，本次叠加覆盖确认与空版本修复）、`fix-engine-startup-crash-share-autostart`（引擎启动链路，已完成，本次叠加状态弹窗）、`fix-homepage-performance-engine-status-buttons`（主页统计，已完成，本次修正存储统计口径）。
- 回归风险（⚠️）：
  - `EngineService` 仅**新增 companion 字段**（不删不改既有成员、不改公共方法签名）——调用方 `ConversationScreen`/`RuntimeUpdater`/`DshApp` 不受影响。
  - `RuntimeUpdater.apply` 无改动；`checkForUpdate` 返回值结构不变，仅调用方 `runUpdate` 的比较条件变化——`engine-mirror-md3-update-and-log-fix` 的既有更新进度流程保留。
  - `HomeScreen.appStorageBytes()` 口径变化（重新含 rootfs）——仅影响主页「存储占用」展示值，不影响其它模块；测量在 IO 协程，不回归主页性能。
  - `doStart` 由「无弹窗直接起服务」改为「弹窗 + 起服务」——服务启动语义不变（仍走 `EngineService.start`），弹窗仅观察状态。
  - ⚠️ 修改点均为**私有方法/私有 UI 流程/新增 companion 字段**；无公共方法签名变更、无 SharedPreferences key 变更、无全局状态改动。

## ADDED Requirements

### Requirement: 启动引擎 MD3 状态弹窗
`ConversationScreen.doStart` SHALL 在点击「启动引擎」时弹出 MD3 弹窗，弹窗内容包含：状态行（检查运行时 / 启动中 / 探活中 / 运行中 / 启动失败）、实时刷新的引擎日志尾部（读 `app-events.log`）。启动交由 `EngineService.start` 执行，弹窗轮询 `EngineService` 启动结果 + `EngineProcess.probe` 判断结果。

#### Scenario: 启动成功
- **WHEN** rootfs 已就绪，用户点击「启动引擎」
- **THEN** 弹窗依次显示「检查运行时 → 启动中 → 探活中」，探活成功显示「运行中」，约 1s 后自动关闭弹窗并刷新状态卡

#### Scenario: 启动失败
- **WHEN** 引擎进程启动抛异常（如 node 缺失 / exec 被拒 / 库缺失）
- **THEN** 弹窗显示「启动失败: <原因>」+ 最近引擎日志尾部，提供「重试 / 关闭」；重试重新执行启动流程

#### Scenario: 运行时未就绪
- **WHEN** rootfs 未安装（`EngineRootfs.isExtracted` 为 false）
- **THEN** 弹窗显示「运行时未就绪，请先检查并更新引擎」+ 最近日志，提供「去更新 / 关闭」；「去更新」进入更新覆盖确认流程

### Requirement: 修复「无法更新引擎」（空版本误判）
`runUpdate` 的「已是最新」跳过条件 SHALL 改为仅在 `manifest.version` 非空且 `current` 非空且 `current >= manifest.version` 时成立；`manifest.version` 为空（当前远端 manifest 无版本号）时不得跳过，必须继续下载/安装流程。

#### Scenario: 已装 rootfs 后仍可更新
- **WHEN** 已通过在线更新安装过 rootfs，再次点击「检查并更新引擎」
- **THEN** 不再误判「已是最新」而 `return`，正常进入覆盖确认 → 镜像选择 → 下载 → 更新进度弹窗流程

### Requirement: 更新覆盖确认弹窗
`ConversationScreen.doUpdate` SHALL 在满足以下任一条件时，先弹 MD3 确认弹窗「更新将覆盖现有引擎数据，确定继续吗？」（取消 / 确定）：`cacheDir/rootfs-new.tar.xz` 已存在（上次下载残留），或 `EngineRootfs.isExtracted` 为 true（已安装 rootfs）。点「确定」才进入镜像选择与更新进度流程。

#### Scenario: 已有文件时点击更新
- **WHEN** 设备上已存在待更新的 rootfs 包或已安装引擎，用户点击「检查并更新引擎」
- **THEN** 先弹出覆盖确认弹窗；点「确定」后进入更新（镜像选择 → 下载进度弹窗）；点「取消」则不执行任何下载

### Requirement: 主页数据/占用统计正常统计
`HomeScreen` 的「存储占用」SHALL 包含 `files/rootfs`（在 IO 协程测量，不阻塞主线程），不再显示 ≈0 MB；「插件数量」SHALL 随 30s 刷新周期在 IO 协程动态读取 `PluginStore.pluginCount` 并回填，不再使用构建时静态快照。

#### Scenario: 安装引擎后占用与插件数正确显示
- **WHEN** 引擎 rootfs 已安装并运行，进入主页
- **THEN** 「存储占用」显示含 rootfs 的真实大小（非 0 MB），「插件数量」随引擎接入插件后动态增长，内存/存储数值随周期自动刷新

## MODIFIED Requirements

### Requirement: 更新进度弹窗（保持既有行为）
`engine-mirror-md3-update-and-log-fix` 的「镜像选择弹窗 + MD3 更新进度弹窗（下载/校验/解压/切换/重启 + 百分比）」行为保持不变；本次仅在入口 `doUpdate` 增加覆盖确认，在 `runUpdate` 修正空版本跳过条件。

## REMOVED Requirements
（无删除项）
