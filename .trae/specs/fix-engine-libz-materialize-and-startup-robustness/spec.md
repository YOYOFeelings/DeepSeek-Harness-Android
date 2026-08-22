# 修复引擎仍无法启动（libz.so.1 not found）并补齐启动健壮性 Spec

## Why
当前引擎仍然报错无法启动，日志反复出现：
`proc: CANNOT LINK EXECUTABLE ".../rootfs/usr/bin/node": library "libz.so.1" not found: needed by main executable`。

根因（已通过快照 `snapshot-x86_64.tar.xz` 与 node ELF 确认）：
- node 的 DT_NEEDED 运行时库（`libz.so.1` / `libcares.so` / `libsqlite3.so` / `libcrypto.so.3` / `libssl.so.3` / `libicui18n.so.78` / `libicuuc.so.78` / `libc++_shared.so`）在 rootfs 的 `usr/lib` 里是**符号链接**（如 `libz.so.1 -> libz.so.1.3.2`），而 `libz.so.1.3.2` 等真实库其实是存在的。
- Android 11+ 对 app data 走 FUSE，bionic linker 无法按符号链接读取这些库名，导致 `libz.so.1 not found`。
- 上一轮已新增 `RuntimePermissions.ensureNodeLibsReal`（把精确 DT_NEEDED 库名实体化为真实文件）。但产出报错日志的 APK 中**没有出现 `node-deps real=…` 诊断行**，说明该修复未被正常部署/运行；此外新代码缺少旧项目启动路径已有的健壮性兜底。

## What Changes
- 每次 `EngineProcess.start` 启动前，把 node 精确 DT_NEEDED 的 8 个库在 `usr/lib` 下**保证为真实、非空、可读文件**：
  - 库名已是真实文件且非空 → 直接放行；
  - 是符号链接（FUSE 读不到）或缺文件 → 从 `usr/lib` 下**同前缀真实库**（如 `libz.so.1.3.2`）复制实体内容覆盖到该库名上（删除旧链接后复制）。
- 幂等、逐库 runCatching、失败静默，返回「库名→是否到位」；每次启动打印 `node-deps real=n/m` 诊断。
- （自旧项目 EngineManager 回填）启动前关键文件校验：`usr/bin/node`、`lib/node_modules/@deepseek-ai/dsh/lib/bin.js`、termux-exec preload 的 存在/非零大小/exec 位；发现问题先执行 `ensureExecutable` + 强制 `resolveTermuxExecPreload` 自愈，仍缺则记录原因。
- （自旧项目 EngineManager 回填）启动前清理残留引擎进程（`pkill -f lib/bin.js`），避免残留 node 占用 3080 端口导致 `EADDRINUSE`，并规避新引擎起来后探活误判已运行。
- 修复必须同时覆盖**新装 rootfs（解压后）**与**已装旧 rootfs（启动时）**两种路径。

## Impact
- Affected specs: engine runtime 启动链路（会话页/主页「引擎状态」取决于 3080 探活，本次不改变探活判定）。
- Affected code:
  - `engine/.../RuntimePermissions.kt`（实体化逻辑加固）
  - `engine/.../EngineProcess.kt`（启动前校验/自愈 + 残留清理）
  - `engine/.../RuntimeUpdater.kt`（解压后实体化调用，保持）
- 不在本次范围：引擎运行时更新源/镜像、会话页 UI、主页状态卡片。

## ADDED Requirements
### Requirement: node DT_NEEDED 库实体化
系统 SHALL 在每次引擎启动前，确保 `usr/lib` 下存在 node DT_NEEDED 8 库名的真实可读文件；若该名是符号链接或缺文件，SHALL 从同前缀真实库复制实体内容覆盖。

#### Scenario: 干净解压后的新 rootfs
- **WHEN** rootfs 解压完成并首次启动引擎
- **THEN** `ensureNodeLibsReal` 把 `libz.so.1`（等）从 `libz.so.1.3.2` 复制为真实文件，`node-deps real=8/8`，引擎正常 LINK 并监听 3080

#### Scenario: 已装旧的/被 FUSE 影响的 rootfs（自愈）
- **WHEN** 设备上已有旧 rootfs，其 `libz.so.1` 是符号链接
- **THEN** 启动时直接实体化修复，无需重下/重装，引擎正常启动

### Requirement: 启动前关键文件校验与自愈
系统 SHALL 在 spawn 前校验 node、bin.js、termux-exec preload 的存在性/大小/exec 位，发现问题先执行 `ensureExecutable` 与 `resolveTermuxExecPreload`，并在日志记录。

#### Scenario: 关键文件缺 exec 位或 preload 缺失
- **WHEN** `usr/bin/node` 缺 exec 位或 preload 解析失败
- **THEN** 启动前完成自愈或记录明确失败原因，不静默崩溃

### Requirement: 残留引擎进程清理
系统 SHALL 在启动前 `pkill -f lib/bin.js` 清理残留 node，防止端口占用导致的 `EADDRINUSE`。

#### Scenario: 上次 app 被杀残留孤儿 node
- **WHEN** 3080 端口仍被上次残留 node 占用
- **THEN** 新启动先清理残留再拉起，新引擎成功 bind 3080

## MODIFIED Requirements
### Requirement: 启动链路复用并注入环境
[EngineProcess.start] 保留既有 node 命令与 LD_LIBRARY_PATH/PATH/HOME/DSH_HOME/TERMUX_* 注入及直接 exec 失败回退 `/system/bin/linker64` 的行为，仅在 spawn 前插入「库实体化 → 关键文件校验/自愈 → 残留清理」。未改动签名与调用方。

## REMOVED Requirements
无。