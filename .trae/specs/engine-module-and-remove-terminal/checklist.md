# Checklist — 删除终端页 + 引擎独立成模块

## 范围A · 终端删除
- [x] `MainActivity` 无 `ID_TERMINAL`、无终端菜单项、无 `showScreen` 终端分支、无 `terminalInstance`、无 `TerminalScreen` import
- [x] `TerminalScreen.kt` 已删除（`ic_terminal.xml` 保留：仍被 PermissionModeScreen/HomeScreen 复用）
- [x] `strings.xml` 中仅终端使用的 `terminal_*` 已删除（无残留引用；`terminal_copy/copied` 被 LogsScreen 复用保留）

## 范围B · 引擎模块化
- [x] `:engine` 模块存在：namespace `com.yoyo.dshmobile.engine`，Android library，依赖 `:core` + xz + commons-compress + androidX
- [x] 5 个引擎核心文件（Rootfs/Process/Service/Watchdog/Updater）已迁到 `:engine` 且包名为 `com.yoyo.dshmobile.engine`
- [x] `Logs`/`LogFox` 已上移到 `:core`（包名 `com.yoyo.dshmobile.shell.log` 不变，`:app`/`:engine` 均能引用）
- [x] `:engine` manifest 声明 `EngineService` + FGS 权限 + `INTERNET`
- [x] `:engine` 含 `assets/rootfs/` 占位与引擎通知 `strings.xml`
- [x] `settings.gradle.kts` 含 `:engine`；`:app` 依赖 `:engine`
- [x] `SessionActivity`/`ConversationScreen` 留在 `:app`，引擎 import 改为 `com.yoyo.dshmobile.engine.*`
- [x] app 内引擎源码已删除，无 `com.yoyo.dshmobile.shell.engine` 残留引用（grep 通过）
- [x] app manifest 移除 EngineService/FGS 权限，保留 SessionActivity；合并后 APK 含 EngineService + 权限 + SessionActivity 共存

## 构建与交付
- [x] JDK17 `assembleRelease` 成功（含新增 `:engine` 模块）
- [x] APK 签名 SHA-256 前缀 `5696…25ff` 通过（apksigner verify）
- [x] APK 复制项目根，`http.server` 提供下载链接
- [x] 自检：主页/关于/设置/日志/权限模式/会话导航编译通过；无终端 Tab；无残留 `TerminalScreen`/`shell.engine` 引用