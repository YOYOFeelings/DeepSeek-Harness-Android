# 删除终端页 + 引擎独立成模块 Spec

## Why
1. 用户反馈终端页「留着没用」，要求**彻底删掉**（不再保留只读终端）。
2. 用户强调「我们的项目是模块化」，要求把**完整的引擎子系统搬进独立 Gradle 模块** `:engine`（类比 `:core`/`:native`），让引擎可独立维护/复用，`:app` 只做 UI 壳。

## What Changes
- **范围A（删终端页）**：移除「终端」Tab 及其全部关联——`MainActivity` 的 `ID_TERMINAL`/菜单项/`showScreen` 分支/`terminalInstance` 缓存/import；删除 `TerminalScreen.kt`；删除 `res/drawable/ic_terminal.xml`；清理 `strings.xml` 中只被终端使用的 `terminal_*` 文案。
- **范围B（引擎模块化）**：新建 `:engine` Android library 模块，装下**完整引擎运行时核心**：
  - 迁移 `EngineRootfs.kt`、`EngineProcess.kt`、`EngineService.kt`、`EngineWatchdog.kt`、`RuntimeUpdater.kt`，包名由 `com.yoyo.dshmobile.shell.engine` 改为 `com.yoyo.dshmobile.engine`。
  - 模块自带 `AndroidManifest.xml`（声明 `EngineService` + `FOREGROUND_SERVICE*` 权限 + `INTERNET`）、`res/values/strings.xml`（引擎前台通知文案）、`assets/rootfs/`（rootfs 资产位）。
  - **共享日志上移**：将 `Logs.kt`/`LogFox.kt`（`com.yoyo.dshmobile.shell.log`）移入 `:core`，使 `:engine` 与 `:app` 共用（包名不变，所有既有 import 不改）。
  - **引擎 UI 留在 `:app`**：`SessionActivity.kt`（依赖 app 主题/`dh_*` 颜色/`Ui` 工具）与 `ConversationScreen.kt` 继续在 `:app`，仅把引擎类的 import 改为 `com.yoyo.dshmobile.engine.*`。
  - 依赖链：`:app → :engine → :core`；`:app → :core`。`settings.gradle.kts` 增 `include(":engine")`；`:app/build.gradle.kts` 增 `implementation(project(":engine"))`，并删除 app 内引擎代码。
  - `:app/AndroidManifest.xml` 移除已移至 `:engine` 模块的 `EngineService` 声明与 FGS 权限，保留 `SessionActivity` 声明。

> 属 `spec.md` 明确范围之外的（`onboarding/*`、`AboutActivity`、`HomeScreen`、`SettingsScreen`、`LogsScreen`、`PluginsScreen`、`DeviceExecutor`、`PermissionMode*`、`:native`、`colors.xml`/`themes.xml`）一律不动；rootfs 二进制资产（原 Task 0 本地 arm64 chroot 交叉编译，约 80MB）仍不在本次范围。

## Impact
- **Affected specs（能力）**：导航（删 1 项）、应用结构（新增 `:engine` 模块、`Logs` 上移到 `:core`）、引擎（迁入独立模块）。
- **Affected code**：
  - 新增：`engine/build.gradle.kts`、`engine/src/main/AndroidManifest.xml`、`engine/src/main/java/com/yoyo/dshmobile/engine/{EngineRootfs,EngineProcess,EngineService,EngineWatchdog,RuntimeUpdater}.kt`、`engine/src/main/res/values/strings.xml`、`engine/src/main/assets/rootfs/.gitkeep`。
  - 修改：`settings.gradle.kts`（`include(":engine")`）、`app/build.gradle.kts`（依赖 `:engine`）、`app/src/main/AndroidManifest.xml`（移除 EngineService/FGS 权限）、`MainActivity.kt`（删终端）、`ConversationScreen.kt`（引擎 import 改 `com.yoyo.dshmobile.engine.*`）、`SessionActivity.kt`（引擎 import 改 `com.yoyo.dshmobile.engine.*`）、`app/src/main/res/values/strings.xml`（删 `terminal_*`）、`core/build.gradle.kts`（新增 androidX 依赖如需要）。
  - 移动：`Logs.kt`/`LogFox.kt` `app/.../log` → `core/.../log`（包名不变）。
  - 删除：`app/.../ui/screen/TerminalScreen.kt`、`app/.../shell/engine/{EngineRootfs,EngineProcess,EngineService,EngineWatchdog,RuntimeUpdater}.kt`、`res/drawable/ic_terminal.xml`。
  - **不在本次范围（只读）**：其他所有文件。

## ADDED Requirements

### Requirement: `:engine` 引擎运行时模块
系统 SHALL 提供独立 Gradle library 模块 `:engine`，承载引擎运行时核心（rootfs 解压、proot 启动、前台保活、看门狗、在线更新），不与 `:app` 的 UI/主题耦合。

#### Scenario: app 依赖 engine
- **WHEN** 构建 `:app`
- **THEN** `:app` 通过 `implementation(project(":engine"))` 使用引擎 API（`com.yoyo.dshmobile.engine.*`），引擎类不再存在于 `:app` 源码内

#### Scenario: 引擎前台 Service 出包
- **WHEN** 安装并启动 App
- **THEN** 最终 APK 合并后的 manifest 同时包含 `SessionActivity`（app 声明）与 `EngineService`（engine 模块声明）及 FGS 权限，二者正常共存

### Requirement: 终端页删除
`:app` SHALL NOT 再提供「终端」Tab 与其入口。

#### Scenario: 导航无终端
- **WHEN** 用户打开主界面底部/侧边导航
- **THEN** 导航仅显示 主页/插件/会话/设置，无「终端」项；`MainActivity` 无 `ID_TERMINAL` 相关代码

## MODIFIED Requirements

### Requirement: 共享日志从 `:app` 上移至 `:core`（改 `Logs.kt`/`LogFox.kt` 位置）
原日志类位于 `:app` 的 `com.yoyo.dshmobile.shell.log`。本次移动到 `:core` 模块**同一包名**下，`:engine` 与 `:app` 皆可 `import com.yoyo.dshmobile.shell.log.Logs` 调用，无需改任何调用点。

### Requirement: 引擎 UI 收敛到 `:app`（改 `SessionActivity.kt`/`ConversationScreen.kt`）
原两者在 `:app` 的 `com.yoyo.dshmobile.shell.engine` 包。本次引擎核心迁走后，引擎 UI 依赖 app 主题/`dh_*` 颜色/`Ui` 工具，故**留在 `:app`**，仅把对引擎核心类的 import 由 `com.yoyo.dshmobile.shell.engine.*` 改为 `com.yoyo.dshmobile.engine.*`。

## REMOVED Requirements

### Requirement: 终端页（范围B 只读版）
**Reason**: 用户要求「终端留着没用，删掉吧」，不再保留任何终端入口。
**Migration**: 仅删 `TerminalScreen` 及其导航引用/资源/文案；`DeviceExecutor`、`PermissionMode`、`ShizukuHelper`、`RootHelper` 等共享能力文件**保留不动**（他处仍可能使用）。