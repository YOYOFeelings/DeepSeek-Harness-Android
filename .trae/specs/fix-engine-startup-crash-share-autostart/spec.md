# 修复引擎启动闪退 + 日志分享 + 解压缺陷 + 自动启动引擎 Spec

## Why
用户导出的 `/workspace/logs_export.zip` 暴露两类致命问题：
1. **启动引擎闪退**：`crash-snapshot.txt` 显示 `IOException: Cannot run program ".../rootfs/usr/bin/proot": error=2, No such file or directory`。rootfs 快照（snapshot-{arm64|x86_64}.tar.xz）**根本不含 proot**，且引擎入口实际为 `usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js`（当前代码写死 `/opt/dsh/web`，同样不存在）。IOException 在 `EngineService` 主线程冒泡 → 整个应用闪退。
2. **无法正常分享日志**：`app-events.log` 显示 `share-logs-fail -> StringIndexOutOfBoundsException: length=57; index=58`（`FileProvider$SimplePathStrategy.getUriForFile`）。`file_paths.xml` 把 `logs_export.zip` 当作**目录根**（root path 指向 zip 文件本身），FileProvider 要求目标文件必须位于所配置根目录**之下**。

日志中其余问题：`update-manifest-http=404`（旧构建子目录 404，本轮已用 basename 修复）、`update-download-fail -> SocketTimeoutException`（直连超时，本轮已用镜像 `EngineMirrors` 修复）——这两项代码已修，仅需复核。另发现隐藏缺陷：`RuntimeUpdater.extractTarXz` **跳过符号链接**，且未补设 exec 位 / 未打 `security.android.exec` 属性，会导致解压出的 rootfs 缺符号链接、Android 15+ 上 node 无法执行（error=13）。

同时用户新增需求：**设置中增加「自动启动引擎」开关，默认关闭**，开启后 App 启动时自动拉起引擎。

## What Changes
- `engine/.../RuntimePermissions.kt`（**新增**）：移植旧项目 `dsh-mobile-apk` 的能力——`resolveTermuxExecPreload()`（解析 termux-exec preload）、`ensureExecutable()`（幂等补设 usr/bin、usr/lib exec 位 + 打 `security.android.exec` 属性，经 `/system/bin/setfattr` 批量打标）。
- `engine/.../EngineProcess.kt`：`buildArgs`/`start` **去 proot**，改为直接启动 node：`<rootfs>/usr/bin/node --expose-internals <rootfs>/usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web --port 3080`；注入 `LD_LIBRARY_PATH`、`LD_PRELOAD`(termux-exec)、`PATH`、`HOME`、`DSH_HOME`、`TMPDIR`、`TERMUX_*` 等环境变量；目录设为 `<rootfs>/home`；直接 exec 被拒（Android 15+ 权限）时回退 `/system/bin/linker64` + 参数重试；启动前 `RuntimePermissions.ensureExecutable`。
- `engine/.../RuntimeUpdater.kt`：`extractTarXz` 保留符号链接（`Files.createSymbolicLink`，先 `deleteIfExists` 防悬空重解压冲突）；`usr/bin` 与 `usr/lib` 文件补设 exec 位；解压后打 `security.android.exec` 属性 + 断言关键文件（node / bin.js / termux-exec preload）存在。
- `engine/.../EngineService.kt`：`EngineProcess.start` 用 `runCatching` 包裹，失败写日志 + 更新通知文案为启动失败，且**不起看门狗**（避免反复拉起坏引擎），不再向主线程抛异常闪退。
- `app/.../AboutActivity.kt` + `app/res/xml/file_paths.xml`：日志 zip 改写至 `cacheDir/logs_export/logs_export.zip`，`file_paths.xml` 的 `path="logs_export.zip"` 改为 `path="logs_export/"`（FileProvider 根目录必须是目录）。
- `app/.../SettingsScreen.kt` + `app/.../DshApp.kt` + `app/res/values/strings.xml`：新增「自动启动引擎」开关（默认关闭，SharedPreferences `engine_prefs/auto_start` 持久化）；`DshApp.onCreate` 读取开关，开启且 rootfs 已解压时 `EngineService.start(this)`。
- 文档同步：`PITFALLS.md`（补 proot 不存在 / FileProvider 根目录须为目录 / 符号链接与 exec 属性约定）、`PROJECT_STRUCTURE.md`、`USER_HABITS.md`、`/workspace/INDEX.md`、本 spec。

## Impact
- 影响代码：`engine/.../EngineProcess.kt`、`engine/.../RuntimeUpdater.kt`、`engine/.../EngineService.kt`、`engine/.../RuntimePermissions.kt`（新增）、`app/.../AboutActivity.kt`、`app/res/xml/file_paths.xml`、`app/.../SettingsScreen.kt`、`app/.../DshApp.kt`、`app/res/values/strings.xml`。
- 受影响 spec：`engine-module-and-remove-terminal`（引擎启动链路）、`engine-mirror-md3-update-and-log-fix`（日志分享/下载，已完成）。
- 回归风险（⚠️）：
  - `EngineProcess.buildArgs/start` 是公共方法，被 `EngineService`、`ConversationScreen` 调用——本次只改实现不改签名；`ConversationScreen` 不经 `buildArgs` 直接启动，无影响。
  - `RuntimeUpdater.extractTarXz` 行为变化（保留 symlink + exec 位）——影响在线更新解压链路，属正向修复。
  - `AboutActivity.zipLogs` 输出路径变化——「保存日志」走 SAF 不受影响，「发送日志」经 FileProvider 被修复。
  - `DshApp.onCreate` 新增自动启动——默认关闭，不影响现有启动流程；开启时仅在 App 前台启动（`startForegroundService`）。
  - ⚠️ 公共方法签名**不变**；无全局状态/单例改动；SharedPreferences **新增** key `auto_start`（不改既有 key）。

## ADDED Requirements

### Requirement: 引擎直接启动 node（去 proot）
`EngineProcess` SHALL 直接用 rootfs 内 `usr/bin/node` 启动引擎（不再使用不存在的 proot），入口为 `usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js`，工作目录为 `rootfs/home`，并注入 `LD_LIBRARY_PATH`/`LD_PRELOAD`(termux-exec)/`PATH`/`HOME`/`DSH_HOME`/`TMPDIR`/`TERMUX_*` 环境变量；直接 exec 被拒时回退 `/system/bin/linker64`。

#### Scenario: 启动不再闪退
- **WHEN** rootfs 已解压并点击「启动引擎」
- **THEN** 应用不闪退，node 进程被拉起并对 `127.0.0.1:3080` 探活

### Requirement: 解压保留符号链接与可执行权限
`RuntimeUpdater.extractTarXz` SHALL 保留归档内的符号链接（先删后建避免悬空冲突），对 `usr/bin`、`usr/lib` 下文件补设 exec 位，解压后打 `security.android.exec` 属性，并断言 node / bin.js / termux-exec preload 关键文件存在（缺失视为解压失败回滚）。

### Requirement: 引擎启动失败不闪退
`EngineService.onStartCommand` SHALL 用 `runCatching` 包裹引擎启动，失败时写日志、更新通知为「引擎启动失败」，且不启动看门狗，绝不向主线程抛异常导致应用闪退。

### Requirement: 日志分享可用
`AboutActivity.sendLogs` SHALL 通过 `FileProvider` 正常生成并分享日志 zip（zip 位于 `cacheDir/logs_export/` 目录下，`file_paths.xml` 根目录配置为目录而非文件），分享失败时 toast + 写日志。

#### Scenario: 正常分享
- **WHEN** 点击「发送日志」
- **THEN** 系统分享弹窗正常弹出（不抛 `StringIndexOutOfBoundsException`）

### Requirement: 自动启动引擎开关（默认关闭）
设置页 SHALL 提供「自动启动引擎」开关，默认关闭，状态持久化于 SharedPreferences；开启时 App 启动（`DshApp.onCreate`）且 rootfs 已解压则自动 `EngineService.start`。

#### Scenario: 默认关闭
- **WHEN** 首次进入设置页
- **THEN** 开关显示为关闭，App 启动不自动拉起引擎

#### Scenario: 开启后自动启动
- **WHEN** 用户打开开关后重启 App（rootfs 已就绪）
- **THEN** 引擎自动启动

## MODIFIED Requirements

### Requirement: 日志 ZIP 导出（保存/发送）
保持「保存=SAF 落盘」「发送=系统分享」语义不变；仅修复发送环节 FileProvider 根目录配置错误与启动崩溃防护。

## REMOVED Requirements
（无删除项）
