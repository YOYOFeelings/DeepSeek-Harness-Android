# Tasks

- [x] Task 1: 让 manifest 检查走所选镜像（核心修复）
  - [x] SubTask 1.1: `RuntimeUpdater.checkForUpdate(context)` 增加参数 `mirror: Mirror = EngineMirrors.byId("official") ?: EngineMirrors.all().first()`（默认官方直连）。
  - [x] SubTask 1.2: 在方法体内对 DEFAULT/FALLBACK 两个候选 URL 调用 `mirror.resolve(url)` 后再 `URL(...).openConnection()`，使 github 被墙时仍可从镜像代理拉取 manifest。
- [x] Task 2: 调用方透传镜像
  - [x] SubTask 2.1: `ConversationScreen.runUpdate` 中 `RuntimeUpdater.checkForUpdate(context)` 改为 `RuntimeUpdater.checkForUpdate(context, mirror)`，与 `download(…, mirror)` 一致。
- [x] Task 3: 编译验证
  - [x] SubTask 3.1: `assembleRelease` 编译通过；给出 APK 下载链接（签名 SHA-256 前缀 `5696…25ff`）。
  - [x] SubTask 3.2: 确认未改动依赖库/解压/启动链路（scope 锁定）。
- [x] Task 4: 启动目录创建幂等化（回归修复「点击启动引擎显示路径无法创建」）
  - [x] SubTask 4.1: `EngineProcess.start` 的 `homeDir`/`tmpDir` 创建由「`mkdirs()` 返回 false 即抛」改为「已存在目录（`isDirectory`）视为成功；仅当非目录且 `mkdirs()` 失败才抛」。rootfs 已解压（home 已存在）时不再误抛 `cannot create dir: …/home`（原错误信息即 UI 显示的路径）。

# Task Dependencies
- Task 2 依赖 Task 1（方法签名先变更，调用点才能传镜像）。