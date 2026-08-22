# Tasks — 删除终端页 + 引擎独立成模块

> 范围锁定：只改 spec.md「Impact」列出的文件。任务 1（删终端）与任务 2-5（引擎模块化）无代码耦合，可并行；任务 2 的 `Logs` 上移是任务 3/4 的前置。
> 每次改动后需 `assembleDebug`/`assembleRelease` 至少编译通过一次再进入下一步。

- [x] Task 1: 删除终端页
  - [x] 1.1 `MainActivity.kt`：删除 `ID_TERMINAL` 常量、`menuSpec()` 中的 `Triple(ID_TERMINAL,...)`、`showScreen()` 的 `ID_TERMINAL` 分支、`terminalInstance` 字段、`import ...TerminalScreen`。
  - [x] 1.2 删除 `app/.../ui/screen/TerminalScreen.kt`、`res/drawable/ic_terminal.xml`。
  - [x] 1.3 `strings.xml`：删除仅终端使用且无其他引用的 `terminal_title/terminal_copy/terminal_copied/terminal_empty`（先 grep 确认无其他引用）。
  - [x] 1.4 构建验证：`assembleDebug` 通过；导航无「终端」项，终端 file/资源已删除。

- [x] Task 2: 共享日志 `Logs`/`LogFox` 上移到 `:core`
  - [x] 2.1 将 `app/.../log/Logs.kt`、`LogFox.kt` 移动到 `core/.../log/`（包名保持 `com.yoyo.dshmobile.shell.log`，import 不变）。
  - [x] 2.2 确认 `core/build.gradle.kts` 为 Android library 且 `:app`/`:engine` 均已依赖 `:core`；`Logs`/`LogFox` 无 `R` 依赖（已核）。
  - [x] 2.3 构建验证：`assembleDebug` 通过（`:app` 内所有 `import com.yoyo.dshmobile.shell.log.*` 及 `DshApp` 调用 `LogFox.start` 仍可解析）。

- [x] Task 3: 新建 `:engine` 模块（引擎运行时核心）
  - [x] 3.1 目录骨架：`engine/build.gradle.kts`（namespace `com.yoyo.dshmobile.engine`，`com.android.library`+kotlin；依赖 `:core` + `org.tukaani:xz` + `org.apache.commons:commons-compress` + `androidx.core:core-ktx` + `androidx.appcompat`；`compileSdk 36/minSdk 26/targetSdk 34`）。
  - [x] 3.2 迁移 5 个引擎核心文件到 `engine/src/main/java/com/yoyo/dshmobile/engine/`，包名改为 `com.yoyo.dshmobile.engine`：`EngineRootfs.kt`、`EngineProcess.kt`、`EngineService.kt`、`EngineWatchdog.kt`、`RuntimeUpdater.kt`。
  - [x] 3.3 `engine/src/main/AndroidManifest.xml`：声明 `<service com.yoyo.dshmobile.engine.EngineService foregroundServiceType/dataSync stopWithTask=false exported=false/>` + `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` + `INTERNET` 权限。
  - [x] 3.4 `engine/src/main/res/values/strings.xml`：`engine_channel_name`、`engine_notify_title`、`engine_running`、`engine_no_rootfs`、`engine_stopped`（EngineService 所需）。
  - [x] 3.5 `engine/src/main/assets/rootfs/.gitkeep` 占位。
  - [x] 3.6 `settings.gradle.kts`：`include(":engine")`。

- [x] Task 4: 接线 `:app` 依赖 `:engine` 并清理 app 内引擎代码
  - [x] 4.1 `app/build.gradle.kts`：`implementation(project(":engine"))`。
  - [x] 4.2 删除 `app/.../shell/engine/{EngineRootfs,EngineProcess,EngineService,EngineWatchdog,RuntimeUpdater}.kt`。
  - [x] 4.3 `SessionActivity.kt`（留在 app）：引擎 import 改为 `com.yoyo.dshmobile.engine.*`（`EngineProcess`/日志）。
  - [x] 4.4 `ConversationScreen.kt`：引擎 import 改为 `com.yoyo.dshmobile.engine.*`（`EngineProcess/EngineRootfs/EngineService/Manifest/RuntimeUpdater`）。
  - [x] 4.5 构建验证：`assembleDebug` 通过，无残留 `com.yoyo.dshmobile.shell.engine` 引用（grep 确认）。

- [x] Task 5: 引擎 manifest 收敛
  - [x] 5.1 `app/src/main/AndroidManifest.xml`：移除 `.engine.EngineService` 的 `<service>` 声明与 `FOREGROUND_SERVICE`/`FOREGROUND_SERVICE_DATA_SYNC` 权限（已移至 engine 模块 manifest；权限合并去重自动处理）；保留 `.engine.SessionActivity` 声明与 `INTERNET`。
  - [x] 5.2 构建验证：`assembleRelease` 通过；确认合并后 APK manifest 仍含 EngineService + FGS 权限 + SessionActivity 共存。

- [x] Task 6: 端到端构建 + 签名 + 交付
  - [x] 6.1 JDK17 全量构建：`export JAVA_HOME=/root/.local/share/mise/installs/java/17.0.2; export JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=18080 ..."; ./gradlew :app:assembleRelease --no-daemon`（代理见 PITFALLS §2）。
  - [x] 6.2 签名核对：`apksigner verify --print-certs <apk>`，SHA-256 前缀 `5696…25ff`。
  - [x] 6.3 APK 复制项目根，`http.server` 提供下载链接。
  - [x] 6.4 回归自检：主页/关于/设置/日志/权限模式/会话页编译与导航正常；`grep` 确认无 `TerminalScreen`/`shell.engine` 残留引用。

# Task Dependencies
- [Task 2]（Logs 上移）是 [Task 3]/[Task 4] 前置（`:engine` 依赖 `:core` 的 Logs）。
- [Task 4] 依赖 [Task 2]+[Task 3]（engine 模块存在 + Logs 可解析）。
- [Task 5] 依赖 [Task 3]（EngineService 已入模块 manifest）。
- [Task 6] 依赖全部编译通过。

# 并行建议
- [Task 1]（删终端）与 [Task 3]（建模块）互不依赖，可并行完成后再做 [Task 4]/[Task 5]。

# 回滚/范围锁定声明
- 只改 Impact 列出的文件；`onboarding/*`、`AboutActivity`、`HomeScreen`、`SettingsScreen`、`LogsScreen`、`PluginsScreen`、`DeviceExecutor`、`ShizukuHelper`、`RootHelper`、`PermissionMode*`、`:native`、`colors.xml`/`themes.xml` 一律不动。
- 若在这些只读区发现其他 bug，交付末尾列「📌 建议单独处理的其他问题」。