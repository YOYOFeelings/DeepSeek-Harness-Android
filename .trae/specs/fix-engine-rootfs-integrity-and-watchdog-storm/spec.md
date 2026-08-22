# 引擎 rootfs 完整性防御 + 看门狗重启风暴收紧 Spec

## Why
设备端 rootfs 被空包/残损包覆盖后引擎不可用。`app-events.log` 实测证据链：
- `[01:11:43] update-download-ok size=0`：一次 **0 字节 rootfs 下载被判定成功并 apply**，把正常引擎覆盖破坏（随后 `bin.js` 缺失、`MODULE_NOT_FOUND`）。
- `[23:21:40] cannot create dir: .../files/rootfs/home`：rootfs 内 `home` 存在非目录/悬空符号链接，启动 `mkdirs()` 返回 false 即硬失败。
- `[01:12] start-verify before=after=lib/bin.js missing/invalid` + `proc: Cannot find module .../lib/bin.js`：入口脚本缺失，node 依旧被拉起并秒挂。
- `[01:12] watchdog-probe-fail → watchdog-restart` 无限循环**重启风暴**：`consecutiveFail` 每次 restart 后复位为 0，永远到不了 3 次的 `onStop`。

快照 `snapshot-x86_64.tar.xz` 已确认 `home/` 目录与 `usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js` 正常存在于归档内（bin.js 模式 `-rw-------`，无 exec 位）→ 设备上的缺失是**覆盖/解压损坏**造成的，非镜像源本身缺件。

## What Changes
- **拦截空/过小 rootfs**（RuntimeUpdater.download）：SHA 通过后仍校验文件大小，`< 1 MiB` 视为损坏包，删除 temp、不 rename、保留旧引擎，记 `update-download-too-small` 并返回 null（应用侧回滚，引擎不动）。
- **home/tmp 容错重建**（EngineProcess.start）：目录创建前若目标存在但非目录（悬空符号链接/损坏残留），先删再 `mkdirs()`，记 `home-rebuild`；幂等，失败才抛。
- **环境严重缺失 → 抛错引导更新**（EngineProcess.start）：原「自愈后仅记日志仍启动」改为：自愈后 `verifyCriticalFiles` 仍非空（node/bin.js/termux-exec），抛 `IllegalStateException("engine env incomplete: <issues>")`，令启动不拉起必将失败的 node，走既有「环境不完整→去更新」弹窗。
- **看门狗风暴收紧**（EngineWatchdog/EngineService）：
  - 探活连续失败后**不再因触发 restart 复位 `consecutiveFail`**，保留计数并随重启累加，≥3 次触发 `onStop` 终止（坏引擎最多被拉起 3 次即停，不再无限循环）。
  - `restartEngine` 启动前做 `EngineProcess.verifyCriticalFiles` 预检，关键文件严重缺失则跳过启动、不起新进程，记 `engine-env-broken-skip-restart`，避免 `pkill -f lib/bin.js` + 起新 node 的空转。

## Impact
- Affected specs（能力）: add-engine-runtime、engine-mirror-md3-update-and-log-fix、fix-engine-libz-materialize-and-startup-robustness、fix-engine-process-and-runtimeperm-defects、unify-update-source-home-dialog-env（复用其 DownloadSource / 环境缺失提示接线）。
- Affected code:
  - `engine/.../engine/RuntimeUpdater.kt`（download 空包拦截）
  - `engine/.../engine/EngineProcess.kt`（home 容错 + verify 后抛错）
  - `engine/.../engine/EngineWatchdog.kt`（计数不复位）
  - `engine/.../engine/EngineService.kt`（restartEngine 预检）
- 不改：DownloadSource 下载传输、镜像表、extractTarXz 解压格式、UI 弹窗外观。

## ADDED Requirements
### Requirement: 拒绝过小 rootfs 应用
系统 SHALL 在下载校验通过后、应用前校验归档大小，小于 `1 MiB` 视为损坏包拒绝应用并保留旧引擎。
#### Scenario: 空包下载
- **WHEN** 下载返回 200 且 SHA-256 与 manifest 一致但文件长度为 0（或 < 1MiB）
- **THEN** 记 `update-download-too-small`，删除临时包，不执行 apply 的切换/覆盖，重用已存在 rootfs；`EngineRootfs.isExtracted()` 状态不变。

### Requirement: home 目录容错创建
系统 SHALL 在启动前确保 `rootfs/home`（及 `home/tmp`）为真实目录；目标存在但非目录时先清理再重建。
#### Scenario: home 为损坏实体
- **WHEN** `rootfs/home` 存在但 `isDirectory` 为 false（文件/悬空符号链接）
- **THEN** 删除该实体后 `mkdirs()`（记 `home-rebuild`），目录正常用于工作目录；不再抛 `cannot create dir`。

### Requirement: 环境严重缺失时拒绝空转启动
系统 SHALL 在自愈（exec 位 + preload 修复）后仍缺 node/bin.js/termux-exec 时抛启动失败，交由 UI 引导「去更新」，而非拉起注定失败的 node。
#### Scenario: bin.js 缺失
- **WHEN** `start()` 自愈后 `verifyCriticalFiles` 仍含 `lib/bin.js missing/invalid`
- **THEN** 抛 `IllegalStateException("engine env incomplete: …")`，`EngineService` 记 `lastStartFailed`，会话页弹「环境不完整→去更新」，不产生 `proc: Cannot find module`。

### Requirement: 看门狗防重启风暴
系统 SHALL 连续探活失败累计达阈值后停止引擎，不应因反复 restart 无限运行。
#### Scenario: 坏引擎反复失败
- **WHEN** 引擎启动后探活持续失败
- **THEN** `consecutiveFail` 随每次失败累加且**不被 restart 复位**，≥3 次触发 `onStop`；`restartEngine` 在关键文件严重缺失时跳过启动并记 `engine-env-broken-skip-restart`。

## MODIFIED Requirements
### Requirement: 启动失败归因（既有，增强）
启动/校验失败信息保持经 `lastStartFailed/lastStartError` 透出，仅 `start()` 由「记日志继续」改为「严重缺失即抛」，语义更明确、不影响既有 MD3 启动弹窗与「去更新/重试」按钮。

## REMOVED Requirements
（无）