# 修复引擎无法启动(目录创建回归) + 更新/下载无法进行(manifest 不走镜像) Spec

## Why
最新包实测日志暴露两处致命问题：
1. **引擎无法启动（回归）**：`EngineProcess.start` 抛 `IllegalStateException: cannot create dir: .../files/rootfs/home`。根因是上一轮「目录创建校验」未考虑 `mkdirs()` 语义：当目录**已存在**时（rootfs 解压后本就含 `home/`，见快照 `drwxrwxrwx home/.dsh/...`），`mkdirs()` 返回 **false**（未新建），却被当作失败抛出 → 引擎所有启动/重启全部失败。
2. **更新/下载无法进行**：`runUpdate(mirror)` 里 `checkForUpdate(context)` **不传镜像**，而 `checkForUpdate` 硬编码直连 `github.com` 的 DEFAULT/FALLBACK manifest；设备连 github:443 超时（`SocketTimeoutException`）→ `update-manifest-unreachable` → `update-check-null`，更新在拿到 manifest 前就中止。而 `download()` 已用 `mirror.resolve()`。即**镜像只生效于下载、不生效于 manifest 检查**，github 被墙时整个更新卡死。

## What Changes
- **目录创建幂等化（回归修复）**：`EngineProcess.start` 中 `homeDir`/`tmpDir` 由「`mkdirs()` 返回 false 即抛」改为「已存在目录视为成功；`isDirectory || (mkdirs() && isDirectory)` 失败才抛」。含路径的明确异常保留。
- **manifest 检查走所选镜像**：`RuntimeUpdater.checkForUpdate` 增加参数 `mirror: Mirror = EngineMirrors.byId("official")…`（默认官方直连），候选 manifest URL（DEFAULT/FALLBACK）经 `mirror.resolve(url)` 前缀化后再请求；下载 URL 的 basename 拼接沿用解析后 URL 基址，逻辑不变。
- **调用方透传镜像**：`ConversationScreen.runUpdate` 把选定的 `mirror` 传入 `checkForUpdate(context, mirror)`，与 `download(…, mirror)` 一致。

## Impact
- Affected code:
  - `engine/.../EngineProcess.kt`：`start()` 的 home/tmp 目录创建（`verifyCriticalFiles`/env/spawn 等其它不变）。
  - `engine/.../RuntimeUpdater.kt`：`checkForUpdate` 签名与方法体（新增 mirror 参数并 resolve manifest URL）。
  - `app/.../ConversationScreen.kt`：`runUpdate` 调用点加传 `mirror`。
- 受影响 spec：无（`fix-engine-download-log-save-version` 已完成且与本问题不同）。
- 回归风险：`checkForUpdate` 默认参数不破坏其它调用（仅 ConversationScreen 一处调用）；目录校验只放宽为幂等，不再误伤已存在目录。

## ADDED Requirements
### Requirement: 目录创建幂等
系统 SHALL 在确保 home/tmp 目录可用时，已存在的目录视为成功；仅当尝试后仍非目录才抛异常。

#### Scenario: rootfs 已解压（home 已存在）
- **WHEN** `.../rootfs/home` 已是目录
- **THEN** 幂等通过，不再抛 IllegalArgumentException，引擎正常启动

### Requirement: manifest 检查可由镜像代理
系统 SHALL 在 checkForUpdate 中按传入镜像解析 manifest URL，使 github 被墙时仍可从代理源拉取 manifest。

#### Scenario: 用户选择代理镜像
- **WHEN** github.com 直连超时，但用户选了可用加速镜像（如 akaere/gh-proxy）
- **THEN** manifest 经 `mirror.resolve(DEFAULT/FALLBACK)` 拉取成功，`download(…, mirror)` 继续完成更新

## MODIFIED Requirements
### Requirement: checkForUpdate 签名
原 `checkForUpdate(context)` 增补默认镜像参数 `checkForUpdate(context, mirror = <官方>)`；所有 candidate URL 经 `mirror.resolve` 处理后请求。默认行为不变（官方直连）。

## REMOVED Requirements
无。