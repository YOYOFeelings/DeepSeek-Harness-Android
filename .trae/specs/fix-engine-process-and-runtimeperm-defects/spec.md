# 修复引擎启动模块（EngineProcess + RuntimePermissions）代码审查缺陷 Spec

## Why
对 `EngineProcess.kt` / `RuntimePermissions.kt` 的代码审查发现多处高/中优先级缺陷：探活误判、关键动态库缺失仍无声启动、符号链接实体化非原子（先删后拷）、`pkill` 在多数 Android 上不存在、目录创建失败未校验、库版本前缀匹配可能错配等。需按优先级修复，提升引擎启动的可靠性与可诊断性。

**审查项校正（与代码实测对照）**
- minSdk=26 → `java.nio.file.Files/Path`（API 26+）无需兼容处理，审阅「minSdk<26 需添加兼容」不成立。
- 应用 ABI 仅支持 arm64 / x86_64（`RuntimeUpdater.abiName()`），均 64 位 → 固有 `linker64` 存在；#5「忽略 32 位」风险低，仍加动态选择作防御。
- #14（materializeSymlinks 不递归）：当前 lib/bin 结构为平铺，无需递归，本轮不改。

## What Changes
高优先级（P1）：
- **probe 探活改用 TCP**：`probe()` 由「GET http://127.0.0.1:3080/ 判 200」改为「Socket connect 127.0.0.1:3080 成功即视为启动」。本质仍是判定 3080 可达，不影响探测语义与调用方（会话页 doStart / EngineWatchdog / 主页探活）。
- **关键库缺失阻断启动**：`ensureNodeLibsReal()` 返回 Map 存在 false 时，`start()` 抛出明确异常并附缺失库列表，由 EngineService.runCatching 捕获 → 置 `lastStartFailed/lastStartError` → 会话页内联展示，不再无声超时。
- **实体化改为原子操作**：`materializeOne()` 与 `ensureNodeLibsReal()` 改为「先复制到同目录临时文件（.tmp，带随机后缀）→ 成功后 rename 覆盖目标」，避免删除后复制失败造成永久丢失。
- **残留清理不依赖 pkill**：`cleanupStaleEngine()` 先尝试 pkill，失败/不可用时回退 `ps | kill`（解析 `ps -A` 或扫描 `/proc/*/cmdline` 精确匹配含 `lib/bin.js` 的 PID 后 kill），并仅匹配精确命令行，避免 `-f` 误杀无关进程（原缺陷 #7）。
- **目录创建校验**：`homeDir.mkdirs()`、`TMPDIR.mkdirs()` 检查返回值，失败抛明确异常。

中优先级（P2）：
- **库版本匹配精确化**：`ensureNodeLibsReal` 的 `candidates()` 由「纯 startsWith」改为「精确名最优先 → 同 base 的 `\.so\.\d+` 正则匹配并取版本号最大者」，避免 `libssl.so.3/libssl.so.1` 之类错配。
- **LD_PRELOAD 校验用可读**：`verifyCriticalFiles()` 对 termux-exec preload 由 `canExecute()` 改为 `canRead()`（动态库加载仅需可读），消除误导性失败日志。
- **dataDir 可靠后备**：`TERMUX_APP__DATA_DIR` 后备由硬编码字符串改为 `context.applicationInfo.dataDir`。
- **启动顺序修正**：`start()` 改为「先 `materializeSymlinks`+`ensureNodeLibsReal`，再 `ensureExecutable`」，保证实体化产生的新文件统一补设 exec 位。
- **停止顺序修正**：`stop()` 先 `process.destroy()` 至退出，再中断读线程，避免 `InterruptedIOException` 噪音日志。
- **linker 动态选择**：`startWithArgs()` 回退时按主 ABI 是否为 64 位选择 `/system/bin/linker64` 或 `/system/bin/linker`（防御性）。

## Impact
- Affected specs: 引擎启动链路（引擎状态探活、启动/重启、停止）。
- Affected code:
  - `engine/.../EngineProcess.kt`：probe、start、startWithArgs、stop、cleanupStaleEngine、verifyCriticalFiles、env 组装。
  - `engine/.../RuntimePermissions.kt`：materializeOne、ensureNodeLibsReal(candidates/原子化)。
  - `engine/.../EngineService.kt`：无需改动（已 runCatching 捕获 start 异常并上报）。
- 不在本次范围：更新源/镜像、会话页与主页 UI 布局、引擎运行逻辑（bin.js 侧）；#10（setfattr 移除）、#13（probe 子线程文档）、#14（递归）是否处理另行评估，不在本轮强制。

## ADDED Requirements
### Requirement: 可靠端口探活
系统 SHALL 通过 TCP connect 到 127.0.0.1:3080 判定引擎是否启动成功；连接成功即视为已启动。

#### Scenario: 引擎已监听但根路径无 200
- **WHEN** 引擎已正常监听 3080，但根路径不返回 HTTP 200
- **THEN** 探活返回 true（不再误判未启动）

### Requirement: 关键库缺失阻断启动
系统 SHALL 在 node 所需 DT_NEEDED 库存在缺失时阻止启动，抛异常并附缺失库名。

#### Scenario: libz.so.1 仍无法实体化
- **WHEN** 启动时 `ensureNodeLibsReal` 返回存在 false
- **THEN** start 抛出异常，EngineService 置 lastStartFailed/lastStartError，会话页内联展示缺失库名

### Requirement: 原子化符号链接实体化
系统 SHALL 以「临时文件 + rename」方式实体化符号链接/依赖库，保证复制失败不丢失原目标。

#### Scenario: 复制过程磁盘满/权限不足
- **WHEN** 实体化复制时发生 IOException
- **THEN** 原链接/原文件保持不被删除，可重试恢复

### Requirement: 残留引擎进程可靠清理
系统 SHALL 在不依赖 `pkill` 可用性的前提下清理残留 node；按 cmdline 精确匹配含 `lib/bin.js` 的进程，避免误杀。

#### Scenario: 设备无 pkill
- **WHEN** `/system/bin/pkill` 不存在或不生效
- **THEN** 回退 ps/proc 扫描 + kill，清掉占用 3080 的孤儿 node，新引擎成功 bind

### Requirement: 目录创建校验
系统 SHALL 校验 home、tmp 目录创建结果，失败时抛明确异常。

## MODIFIED Requirements
### Requirement: 探活语义
原「GET / 返回 200」判定替换为「TCP connect 3080 成功」；两者均判定 3080 可达，调用方无须改动。

### Requirement: 依赖库版本匹配
`candidates()` 由 startsWith 前缀匹配改为「精确名优先，同 base 取最高版本」，避免版本错配导致符号不兼容。

### Requirement: 启动前置处理顺序
由「ensureExecutable → materializeSymlinks → ensureNodeLibsReal」改为「materializeSymlinks → ensureNodeLibsReal → ensureExecutable」。

## REMOVED Requirements
无（原代码无被删除的正规需求；probe 的 HTTP-200 判定、pkill 方案、先删后拷操作为实现细节，予以替换）。