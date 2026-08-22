# Tasks — 引擎运行时骨架落地 + 终端只读

> 实现遵循范围锁定：只改 spec.md「Impact」列出的文件，其余只读。任务 5 与 6 可并行（互不依赖）。

- [x] Task 1: 终端改为只读（范围B，独立可交付）
  - [x] 1.1 改 `TerminalScreen.kt`：删除 `input`、`run`、`buildCommandBar()`、`runCommand()`、`setRunning()`、`appendPromptLine()` 及相关 imports（`EditText`、`DeviceExecutor`、`currentMode` 保留用于权限模式标识—确认保留）；页面只保留权限模式标识 + 输出区 + 复制按钮。
  - [x] 1.2 若 `strings.xml` 的 `terminal_hint`/`terminal_run`/`terminal_empty` 不再使用则标记删除（`terminal_empty` 可改为「终端为只读查看」文案）。
  - [x] 1.3 构建验证：`assembleDebug` 通过；进入终端页无输入框与运行按钮，复制可用。

- [x] Task 2: EngineRootfs + EngineProcess（范围A 核心运行）
  - [x] 2.1 `EngineRootfs.kt`：`rootfsDir(context)=File(filesDir,"rootfs")`、`doneFile=.extracted`、`ensureExtracted(context)`（有 done 则返回，否则校验+SHA-256+tar 解压，拒绝 `..`，写 done）、`engineVersion(context)`/`isExtracted(context)`。rootfs 未就绪时返回语义化状态，不抛主线程。
  - [x] 2.2 `EngineProcess.kt`：`buildArgs(rootfsDir)` 组装 `proot -r <rootfs> -b /proc -b /dev -b /sys -b /dev/urandom:/dev/urandom -w / /usr/bin/node --expose-internals /opt/dsh/web --port 3080`；`start(context)` 返回可取消句柄；`probe(context,timeoutMs)` 对 `http://127.0.0.1:3080` 发 GET 200 判定；`stop(handle)` 优雅停。
  - [x] 2.3 构建验证：`assembleDebug` 通过；未装 rootfs 时探活返回 false，不崩溃。

- [x] Task 3: EngineService + EngineWatchdog（范围A 保活）
  - [x] 3.1 `EngineService.kt`：前台 Service，`START_STICKY`，`onStartCommand` 先 `ensureExtracted` 再 `EngineProcess.start` 再起 watchdog，`onDestroy` 停进程；前台类型 API≥34 用 `FOREGROUND_SERVICE_DATA_SYNC`，26–33 仅 `startForeground`；通知标题「deepseek HARNESS 引擎运行中」，颜色走 `dh_primary`。
  - [x] 3.2 `EngineWatchdog.kt`：单线程每 5s `probe`，失败→`lastDown`+重启，超上限停止并 `Logs.logEvent("Engine","watchdog-stop")`。
  - [x] 3.3 `AndroidManifest.xml`：声明 `EngineService`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`（`tools:targetApi="34"`）、`android:stopWithTask="false"`。

- [x] Task 4: SessionActivity + ConversationScreen + 导航 + 资源（范围A UI）
  - [x] 4.1 `SessionActivity.kt`：WebView 开 JS，加载 `http://127.0.0.1:3080`；`shouldOverrideUrlLoading` 非 127.0.0.1 转系统浏览器；`onReceivedError` 重试提示；返回键可后退。
  - [x] 4.2 `ConversationScreen.kt`：卡片显示引擎状态（`engineVersion`/`probe`）、「启动引擎」「停止引擎」「打开会话(→SessionActivity)」、「检查并更新引擎」；未解压则启动/会话按钮置灰+「先安装运行时」；更新按钮显示进度（可复用 `LoadingButton`）。
  - [x] 4.3 `MainActivity.kt`：新增 `ID_CONVERSATION`，加入 `BottomNavigationView`/`NavigationRailView`，`showScreen` 分支 + `conversationInstance` 缓存。
  - [x] 4.4 `res/drawable/ic_conversation.xml`（`dh_primary` 描边）；`strings.xml` 增 `nav_conversation`、`engine_*`、`session_*`、`engine_update_*`。
  - [x] 4.5 构建验证：`assembleDebug` 通过；会话 Tab 可见，未装 rootfs 时按钮置灰不崩溃。

- [x] Task 5: AndroidManifest 权限 + build.gradle assets + RuntimeUpdater（范围A 收尾 + 在线更新）
  - [x] 5.1 `app/build.gradle.kts`：`sourceSets.main.assets.srcDirs += "src/main/assets/rootfs"`（或确认 assets 默认即含），保持 `abiFilters arm64-v8a`。
  - [x] 5.2 `app/src/main/assets/rootfs/` 建目录 + 占位 `rootfs.tar.xz`（空/占位文件，或 README 说明由 Task 0 本地编译产出）。
  - [x] 5.3 `RuntimeUpdater.kt` 完整实现：`checkForUpdate(context): manifest?`、`download(context, manifest, onProgress)` + SHA-256 校验、`apply(context)` 阶段解压（xz/tar）+ 原子 rename 切换 + 失败回滚；已补真实解压 `extractTarXz`（拒绝 `..`/链接逃逸）+ xz/commons-compress 依赖。
  - [x] 5.4 构建验证：`assembleRelease` 通过；更新流程无网络/校验失败时段落正确返回且不中断引擎。

- [x] Task 6: 构建 + 签名 + 交付（完成后端到端）
  - [x] 6.1 JDK17 完整构建：`assembleRelease` 成功（经 `JAVA_TOOL_OPTIONS` 代理下载新增依赖）。
  - [x] 6.2 签名核对：`apksigner verify --print-certs` SHA-256 前缀 `5696…25ff` 通过。
  - [x] 6.3 APK 复制项目根 `deepseek-harness-0.13.0-Data-release.apk`，`http.server 8131` 提供下载链接。

# Task Dependencies
- [Task 3] 依赖 [Task 2]（EngineProcess 存在）
- [Task 4] 依赖 [Task 6 之前可先并行，但依赖 [Task 1]/[Task 2] 的产物做探活/状态）
- [Task 5] 与 [Task 2]/[Task 3] 无代码冲突，可并行
- [Task 6] 依赖以上全部编译通过

# 并行建议
- [Task 1] 终端口（独立）↔ [Task 2] 引擎核心（独立）可并行。
- [Task 3] 依赖 [Task 2]；[Task 4]/[Task 5] 可与 [Task 3] 并行。

# 回滚/范围锁定声明
- 只修 Impact 列出的文件；`onboarding/*`、`AboutActivity`、`HomeScreen`、`SettingsScreen`、`LogsScreen`、`PluginsScreen`、`DeviceExecutor`、`ShizukuHelper`、`RootHelper`、`PermissionMode*`、`:core`、`:native`、`colors.xml`/`styles.xml` 一律不动。
- 若在这些只读区发现其他 bug，交付末尾列「📌 建议单独处理的其他问题」。