# 引擎运行时骨架落地 + 终端只读 Spec

## Why

当前 yoyo 原生壳只是「终端/插件/日志/设置」的空壳，没有真正的 dsh 智能体引擎撑起「会话」能力。
用户希望：① 把调研确认的引擎运行时骨架（内嵌 rootfs → proot → 前台 Service + 看门狗 → WebView 会话页）真正落地到代码；
② 终端页原来可输入命令执行，用户反馈「还不如之前只能看」，要求改成**只读（禁止输入，只能查看+复制）**。

本变更把任务拆成两个清晰范围：
- **范围A（引擎骨架）**：新增 `engine/*` 子系统，新增「会话」导航页接引擎端口。
- **范围B（终端只读）**：删掉命令输入行与「运行」按钮，终端仅查看当前权限模式 + 输出 + 复制。

> 前置依赖：`rootfs.tar.xz` 资产需本地 arm64 chroot 编译产出（Task 0）。骨架阶段先「探活=未就绪则提示」，不阻塞开发与打包。

## What Changes

- **引擎运行时骨架**（新增 `app/src/main/java/com/yoyo/dshmobile/shell/engine/` 下 5 个文件）：
  - `EngineRootfs.kt`：`rootfs.tar.xz` 资产 → SHA-256 校验 → 幂等解压 `filesDir/rootfs`（done 标记文件）。
  - `EngineProcess.kt`：组装 proot 命令、`ProcessBuilder` 启动 node 引擎、`127.0.0.1:3080` 探活、优雅停止。
  - `EngineService.kt`：前台 `Service`（前台通知 + 分版本 `foregroundServiceType`），启动/停止幂等。
  - `EngineWatchdog.kt`：5s 轮询 3080，探测失败则重启，超上限停止。
  - `RuntimeUpdater.kt`：**在线运行时更新**：拉取 manifest JSON（`version/url/sha256`）→ 下载新 `rootfs.tar.xz` 到 `cacheDir` → SHA-256 校验 → 阶段解压 `rootfs-new` → 原子 `rename` 切换 → 写版本标记 → 由看门狗重启；失败回滚旧 rootfs。**换完即可直接使用，无需装新版 APK**。
  - `SessionActivity.kt`：全屏 WebView 加载 `http://127.0.0.1:3080`，外链走系统浏览器，同源页留在 WebView。
  - `ui/screen/ConversationScreen.kt`：原生壳「会话」导航页（引擎状态 + 启动/停止/打开会话按钮）。
- **新增导航项**：`MainActivity.kt` 增 `ID_CONVERSATION`，`BottomNavigationView` / `NavigationRailView` 加入 Tab（`ic_conversation` + `nav_conversation`），`showScreen` 分支复用实例。
- **总屏蔽描述**：预留 `EngineProcess.probe` / `EngineRootfs.engineVersion` 供 UI 显示；未解压时按钮置灰并提示「先安装运行时」。
- **终端只读**（改 `TerminalScreen.kt` + `strings.xml`）：移除 `EditText` 输入框与「运行」按钮、`runCommand`/`setRunning`、`DeviceExecutor` 执行逻辑；保留「查看 + 复制」。
- **收尾**：`AndroidManifest.xml` 加 Service + 权限；`app/build.gradle.kts` 的 `assets.srcDirs` 含 `src/main/assets/rootfs`；索引/文档同步。

## Impact

- **Affected specs（能力）**：终端（能力要员降级为只读）、导航（新增会话 Tab）、应用外壳（新增前台 Service + WebView）。
- **Affected code（受影响文件）**：
  - 新增：`engine/EngineRootfs.kt`、`engine/EngineProcess.kt`、`engine/EngineService.kt`、`engine/EngineWatchdog.kt`、`engine/RuntimeUpdater.kt`、`engine/SessionActivity.kt`、`ui/screen/ConversationScreen.kt`、`res/drawable/ic_conversation.xml`、`app/src/main/assets/rootfs/rootfs.tar.xz`（占位）。
  - 修改：`MainActivity.kt`、`TerminalScreen.kt`、`res/values/strings.xml`、`AndroidManifest.xml`、`app/build.gradle.kts`。
  - **不在本次范围（只读/不动）**：`onboarding/*`、`AboutActivity`、`HomeScreen`、`SettingsScreen`、`LogsScreen`、`PluginsScreen`、`DeviceExecutor`/`ShizukuHelper`/`RootHelper`、`PermissionMode*`、`:core`、`:native`、主题色主题（`colors.xml`/`styles.xml`）。
  - 若开发中发现这些只读模块有其他 bug，**只记录不修改**，在交付末尾列「📌 建议单独处理」。

## ADDED Requirements

### Requirement: 引擎运行时骨架（范围A）
系统 SHALL 提供内嵌 rootfs 解压、proot 起引擎、前台保活、WebView 会话页的引擎子系统；在 rootfs 资产未就绪时，系统 SHALL 不崩溃，仅提示「先安装运行时」。

#### Scenario: 资产已就绪，启动引擎
- **WHEN** 用户进入「会话」页并点击「启动引擎」，且 `filesDir/rootfs` 已解压
- **THEN** 系统启动前台 Service，proot 起 node 引擎监听 `127.0.0.1:3080`，通知显示「引擎运行中」，看门狗每 5s 探活

#### Scenario: 资产未就绪，启动引擎
- **WHEN** 用户进入「会话」页但 rootfs 未解压（无 `.extracted` 标记）
- **THEN** 系统显示「先安装运行时」，启动/打开会话按钮置灰，不抛异常

#### Scenario: 引擎崩溃
- **WHEN** 看门狗探测 `127.0.0.1:3080` 失败
- **THEN** 系统记录 `Logs.logEvent("Engine","watchdog-restart")` 并重启引擎；连续失败超上限则停止并写 `watchdog-stop`

### Requirement: 终端只读（范围B）
终端页 SHALL 仅支持「查看输出 + 复制输出」，SHALL NOT 提供命令输入或执行能力。

#### Scenario: 终端页渲染
- **WHEN** 用户进入「终端」Tab
- **THEN** 页面只显示：权限模式标识、只读输出区（等宽字体、`dh_text_primary` 文字）、顶部「复制」按钮；无输入框、无「运行」按钮

#### Scenario: 复制输出
- **WHEN** 用户点击「复制」
- **THEN** 系统输出区全部文本写入剪贴板并 `Toast`「已复制」

### Requirement: 会话导航项（范围A）
主界面的底部/侧边导航新增「会话」Tab，加载 `ConversationScreen`。

#### Scenario: 导航到会话页
- **WHEN** 用户点击底部导航或侧边栏的「会话」项
- **THEN** 系统在当前导航容器展示 `ConversationScreen`（复用单例，与终端同款缓存），图标 `ic_conversation`

### Requirement: 会话页打开引擎 Web UI（范围A）
系统通过内嵌 WebView 打开引擎端口页面，且外链不劫持。

#### Scenario: 打开会话
- **WHEN** 引擎运行中，用户点击「打开会话」
- **THEN** 系统启动 `SessionActivity`，WebView 加载 `http://127.0.0.1:3080`，开启 JS；点击非 `127.0.0.1` 链接转系统浏览器

### Requirement: 在线运行时更新（范围A · 用户反馈补充）
系统 SHALL 支持在**不改装新 APK** 的前提下，把后续 DSH 引擎运行时（rootfs）在线替换为最新版，**替换完成即可直接使用**。

#### Scenario: 获取新版本并下载
- **WHEN** 用户触发「检查并更新引擎」且有可用的 manifest（含 `version/url/sha256`）且 `version > 当前`
- **THEN** 系统下载新的 `rootfs.tar.xz` 到 `cacheDir`，显示进度，并对下载文件做 SHA-256 校验

#### Scenario: 校验通过并原子切换
- **WHEN** 新 rootfs 校验通过且旧引擎已停止
- **THEN** 系统将新 rootfs 阶段解压到 `rootfs-new`，`rename` 原子切换为新 rootfs，更新版本标记，由看门狗重启引擎

#### Scenario: 校验失败或下载失败
- **WHEN** SHA-256 不匹配、下载中断或切换异常
- **THEN** 系统丢弃 `rootfs-new`，保留旧 rootfs 继续使用，并写 `Logs.logEvent("Engine","update-fail")` 提示用户

#### Scenario: manifest 服务不可达
- **WHEN** manifest URL 拉取失败或超时
- **THEN** 系统提示「检查更新失败」，不中断当前引擎

## MODIFIED Requirements

### Requirement: 终端从「可执行」改为「只读」（改 `TerminalScreen.kt`）
原终端支持输入命令并通过 `DeviceExecutor` 执行（Shizuku→Root）。本次改为**只读查看**：删除 `EditText` 输入行、「运行」按钮、`runCommand()`/`setRunning()`/`appendPromptLine()`/`DeviceExecutor.run()` 调用路径。保留「复制」与输出渲染。`context.dp` 本地包装等未用代码一并清理。

### Requirement: 主界面导航从 4 项变为 5 项（改 `MainActivity.kt`）
原导航项：主页/终端/插件/设置（`ID_TERMINAL=3` 等）。本次新增 `ID_CONVERSATION` 会话项，加入 `BottomNavigationView`/`NavigationRailView`，`showScreen` 增加会话分支并缓存 `ConversationScreen?`。

## REMOVED Requirements

### Requirement: 终端命令执行能力
**Reason**: 用户明确「不能输指令，只能查看」，原可执行终端能力移除。
**Migration**: 仅在 `TerminalScreen` 内移除输入/执行相关代码；`DeviceExecutor`、`PermissionMode`、`ShizukuHelper`、`RootHelper` 等共享能力**保留不动**（其他页面/引导仍可能使用），不删除这些文件。