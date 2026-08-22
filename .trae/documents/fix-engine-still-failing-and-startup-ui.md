# 修复引擎仍无法启动（libz.so.1 not found 根治）+ 启动 UI（不确定事件动画 / 不弹假弹窗）

## 摘要（Summary）
用户提供的 `/workspace/logs_export (1).zip` 最新日志（app-events 22:0x）证实：引擎**仍无法启动**，错误码恒定不变：

```
[Engine] direct-exec-denied-fallback-linker64
[Engine] engine-started
[Engine] proc: CANNOT LINK EXECUTABLE ".../files/rootfs/usr/bin/node":
        library "libz.so.1" not found: needed by main executable
```
且看门狗每 ~6s 反复重启，全部同样失败。

（注：zip 内 crash-snapshot 02:49 报的是旧版 `usr/bin/proot` 路径，属**旧安装产物**，以 app-events 为准——它走的是新版 link64 回退 + node。）

本次要解决两件事：
1. **根治 libz.so.1 not found**：通过「把 node 依赖库真文件化」做健壮兜底，覆盖既有符号链接实体化处理不到的情形。
2. **启动/结果 UI 改造**：启动中不再用「中间一大块日志」的卡片，改用**不确定事件（Indeterminate 转圈）**判断启动中；成功后自动关闭；**未真正启动则不弹任何结果弹窗**，改为主卡片内联展示真实状态。

## 现状分析（Current State Analysis）
已读文件：
- `engine/.../EngineProcess.kt`：`start()` 现有 `ensureExecutable(usrDir)` → `materializeSymlinks(usrDir)`（上一轮新增）→ 设 `LD_LIBRARY_PATH=usr/lib`、`LD_PRELOAD`=termux-exec → `startWithArgs`（直接 exec node，`Permission denied` 时回退 `/system/bin/linker64 node ...`）。
- `engine/.../EngineService.kt`：`onStartCommand` → `EngineRootfs.ensureExtracted` → `EngineProcess.start` → 起 watchdog；companion 暴露 `lastStartSeq/lastStartFailed/lastStartError`。
- `engine/.../RuntimeUpdater.kt` `extractTarXz`：`Files.createSymbolicLink` 保留符号链接（241 行，失败即抛→整体回滚）；解压后 `materializeSymlinks`（268 行）。
- `engine/.../RuntimePermissions.kt`：有 `materializeSymlinks`（只处理**已存在的**符号链接；对「链接缺失/无法 readback」无能为力）。
- `app/.../ConversationScreen.kt`：`doStart()` 起 `showStartDialog()`，弹窗体 = 状态行 + **120dp 可滚动日志区**；启动失败走 `showStartFailed`、超时走 `showStartStuck`、未就绪走 `showStartNotReady`（都**再弹一个终态弹窗**）。

快照实测（`snapshot-x86_64.tar.xz`，readelf / tar 解包）：
- `usr/bin/node`：ELF64/x86_64，`RUNPATH=/data/data/com.termux/files/usr/lib`（设备上不存在），`DT_NEEDED` = `libz.so.1, libcares.so, libsqlite3.so, libcrypto.so.3, libssl.so.3, libicui18n.so.78, libicuuc.so.78, libc.so, libm.so, libdl.so, libc++_shared.so`。
- 其中 libc/libm/libdl 由 Android linker 提供；**8 个必须落在 usr/lib**：libz.so.1 / libcares.so / libsqlite3.so / libcrypto.so.3 / libssl.so.3 / libicui18n.so.78 / libicuuc.so.78 / libc++_shared.so。
- `usr/lib/libz.so.1 -> libz.so.1.3.2`（实体 81776B 存在）；`libc++_shared.so`、`libtermux-exec-ld-preload.so` 均为真实文件。

**根因结论**：node 的非系统依赖库在 rootfs 内多为符号链接；app 私有 data（Android FUSE）下 linker 对符号链接解析不可靠，导致 `libz.so.1 not found`。上一轮 `materializeSymlinks` 只覆盖「链接存在」分支，未覆盖「链接创建失败/无法读回 → libz.so.1 直接缺失」的情形，故设备上可能仍缺文件导致错误码不变。需改为**对 node 的精确 NEEDED 库名强制合成实体文件**，彻底兜底。

## 拟改动（Proposed Changes）
### 改动点 A（核心）：`engine/.../RuntimePermissions.kt` 新增 `ensureNodeLibsReal(usrDir)`
新增方法，职责：**保证 node 精确依赖的库名在 `usr/lib` 下都是真实文件**（非符号链接、`length>0`）。
实现要点：
- 硬编码 node 的 8 个非系统 NEEDED 库名（上表）。
- 对每个库名 `N`：
  - 若 `usr/lib/N` 已是真实文件（`!isSymbolicLink && isFile && length>0`）→ 跳过（幂等）。
  - 否则删除 `usr/lib/N`（若存在），去 `usr/lib` 下找最匹配的真实文件：优先精确名 N；没有则取同前缀的实体（如 N=`libz.so.1` → 取 `libz.so.1.3.2`；`libib*.so.78` 类似），把实体内容 `copyTo` 为 `N`，并 `setExecutable/Readable/Writable`。
- 逐文件 `runCatching`，失败静默；不改变公共方法签名（新增私有构图方法，公开 `ensureNodeLibsReal`）。
- 返回 `Map<库名, Boolean>`（到位与否），供诊断日志使用。
- 保留现有 `materializeSymlinks`（通用），`ensureNodeLibsReal` 叠加其上，覆盖「已存在链接」「文件缺失」两况。

### 改动点 B：`engine/.../RuntimeUpdater.kt` `extractTarXz`
在现有 `materializeSymlinks(File(destDir,"usr"))`（268 行）之后追加一行：
```kotlin
RuntimePermissions.ensureNodeLibsReal(File(destDir, "usr"))
```
→ 在线更新/覆盖安装的新 rootfs 解压即真文件化。

### 改动点 C：`engine/.../EngineProcess.kt` `start()`
在 `materializeSymlinks(usrDir)`（45 行）之后追加：
```kotlin
RuntimePermissions.ensureNodeLibsReal(usrDir)   // 已装 rootfs 兜底：强制 node 依赖库为真实文件
```
并对其返回的到位 map 写一条诊断 `Logs.logEvent(context, "Engine", "node-deps ${到位/缺失摘要}")`，便于下一步若仍失败能精确定位。

### 改动点 D：`app/.../ConversationScreen.kt` 启动 UI 改造（用户明确要求）
1. **启动中改「不确定事件」动画**：`showStartDialog` 弹窗体由「状态行 + 日志滚动区」改为「状态行 + 居中 `Indeterminate ProgressBar`（旋转，走 dh_primary 色）」。日志区默认收起，保留一个「查看日志」小按钮点击展开（不再占满中间）。
2. **用不确定事件判断启动中**：协程仍以 `EngineService.lastStartSeq/lastStartFailed` + `EngineProcess.probe(3080)` 为结果判定（与既有约定一致），不确定进度条仅在「启动中」显示，成功即停。
3. **未真正启动不弹弹窗**：移除 `showStartFailed`、`showStartStuck` 的**独立终态弹窗**；失败/超时改为在**主卡片**的 `statusText`/`detailText` 内联展示（失败原因 / 仍在启动中）。即「启动成功 → 关弹窗回卡片；没启动 → 不弹结果弹窗，把真实状态写回卡片」。
4. `showStartNotReady`（rootfs 未就绪）保留，因为它是「去更新」引导而非启动结果。
5. `StartUi` 类增加 `progress` 引用（Indeterminate ProgressBar）与「查看日志」展开逻辑；移除其中不再使用的 `log` 常量滚动刷新。

### 改动点 E（防御，仅当快照缺 libc++ 时启用，不改默认行为）
若 `usr/lib/libc++_shared.so` 缺失，从 `context.applicationInfo.nativeLibraryDir` 拷贝一份（参照旧项目 `EngineManager.copyPreloadFromNativeLibs`）。当前快照已含实体，无需启用，仅代码留兜底。

## 决策与假设（Assumptions & Decisions）
- **假设 A**：设备上已装 rootfs 由本 APK `extractTarXz` 生成（链接创建失败即整体回滚），故已装 rootfs 内符号链接**存在**；`materializeSymlinks` + `ensureNodeLibsReal` 双保险覆盖「链接不可读」与「文件缺失」两况，错误码不再维持。
- **决策 1**：真文件化 node 精确依赖库，而非回到 proot（proot 二进制不在 rootfs）。改动最小、只触及 node 动态依赖加载。
- **决策 2**：启动成功判定 = 3080 端口 `probe` == true（项目既有唯一约定）；结果「不弹弹窗」，仅成功时自动关闭启动弹窗，失败/超时内联到卡片。
- **决策 3**：不动 `EngineService`/`EngineRootfs` 公共签名、不动更新覆盖确认弹窗、不动主页统计。

## 验证（Verification）
0. **沙箱逻辑验证**：对 `release-assets/snapshot-x86_64.tar.xz` 解包，用等价逻辑先**故意删除/保留链接两种场景**跑 `ensureNodeLibsReal`：断言 `usr/lib/libz.so.1`、`libsqlite3.so`、`libicui18n.so.78`、`libicuuc.so.78`、`libcrypto.so.3`、`libssl.so.3`、`libcares.so`、`libc++_shared.so` 全部为**真实文件且 size>0**（含「删除全部链接后仍能补出」场景）。
1. **编译**：`JAVA_HOME=/root/.local/share/mise/installs/java/17.0.2`，`JAVA_TOOL_OPTIONS="-Xmx1g -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=18080 ..."`，`./gradlew :app:assembleRelease --no-daemon --max-workers=1 -PenableNative=true` 通过；`apksigner verify --print-certs` SHA-256 前缀 `5696…25ff` 不变（PITFALLS §6/§18）。
3. **代码审查**：改动集中在 RuntimePermissions（新方法）+ 两处调用 + 一处诊断 + ConversationScreen 的 doStart/弹窗。无越界、无硬编码视觉值（用 dh_ 主题令牌 + dp 工具）。
4. **回归自检（≥3 项无关功能）**：更新覆盖确认弹窗仍正常；主页存储/插件统计仍含 rootfs；自动启动引擎开关不受影响；`updates`/`probe` 约定不变；日志轮询区（会话页下载进度）仍在。