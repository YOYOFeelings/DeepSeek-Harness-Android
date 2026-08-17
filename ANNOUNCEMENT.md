# DeepSeek Harness Android — 更新公告

## v0.11.8

**中文**

- 全面代码审计，修复 10 个 bug（3 HIGH + 7 MEDIUM）：
  - 【高危】修复多镜像下载回退失效：加速镜像固化 URL 后所有回退源下载同一地址，改用原始基址逐源解析。
  - 【高危】修复解压进度条原地刷新 off-by-one：进度行无限拼接，改为正确覆盖上一行。
  - 【高危】修复 WebView JS 桥注入防护缺失：添加导航白名单（仅 localhost/127.0.0.1/data:），saveSettingsYaml 添加 50KB 限制。
  - 【中危】修复更新流程 TOCTOU 竞态：updateRunning 改为 AtomicBoolean CAS 原子占位。
  - 【中危】修复运行时原子切换无回滚：renameTo 失败时从 usr-old 恢复旧运行时。
  - 【中危】修复下载/解压失败残留数百 MB 临时文件：catch 块清理 update.tar.xz 和 update-stage。
  - 【中危】修复 Logs.tail / engineLogTail 全量读文件 OOM：改为 forEachLine + 滑动窗口。
  - 【中危】修复 StorageStats 符号链接环无限递归：增加 visited canonicalPath 集合。
  - 【中危】修复插件导入阻塞主线程：importFrom 移入后台线程执行。
  - 【中危】修复 ShizukuSupport.status TOCTOU NPE：getVersion 纳入 try/catch。
- 完整审计报告见 [AUDIT-REPORT.md](AUDIT-REPORT.md)。

**English**

- Comprehensive code audit, fixed 10 bugs (3 HIGH + 7 MEDIUM):
  - [HIGH] Fixed multi-mirror download fallback broken: CDN-prefixed URL caused all fallback sources to hit the same dead address; now uses raw base URL with per-source resolution.
  - [HIGH] Fixed appendProgress off-by-one: progress lines appended infinitely; now correctly replaces the previous line.
  - [HIGH] Fixed WebView JS bridge injection risk: added navigation whitelist (localhost/127.0.0.1/data: only); saveSettingsYaml capped at 50KB.
  - [MEDIUM] Fixed updateRunning TOCTOU race: switched to AtomicBoolean CAS.
  - [MEDIUM] Fixed non-atomic runtime switch with no rollback: restores usr from usr-old on failure.
  - [MEDIUM] Fixed stale temp files after download/extract failure: cleanup in catch block.
  - [MEDIUM] Fixed OOM in Logs.tail / engineLogTail: streaming reader with sliding window.
  - [MEDIUM] Fixed StorageStats symlink loop StackOverflow: visited canonicalPath set.
  - [MEDIUM] Fixed UI freeze on plugin import: importFrom moved to background thread.
  - [MEDIUM] Fixed ShizukuSupport.status TOCTOU NPE: getVersion wrapped in try/catch.
- Full audit report: [AUDIT-REPORT.md](AUDIT-REPORT.md).

---

## v0.11.7

**中文**

- 修复公告弹窗与 APK 更新检查不工作：三处硬编码 URL（AnnouncementManager / ApkUpdateManager / UpdateManager）从 YOYOFeelings 仓库迁移至 kcln243107 仓库，公告和更新均可正常获取。
- 修复 Web 端插件配置无法保存：移除 `injectIndexShim` 中的 `webActive` 守卫，引擎运行时仍对 localhost HTML 响应注入 localStorage 宿主化代理；新增 `ConcurrentHashMap.newKeySet` 去重防止 SPA 子路由重复注入。
- 修复引擎启动并发竞态：`ensurePrivateDshData()` 添加 `dshDataLock` 同步锁，防止 MainActivity 引导线程与 EngineService 看门狗线程并发写 DSH_HOME 标记文件。
- Workflow 新增自动版本递增：每次 push 到 main 或手动触发时，自动计算 `versionCode = BASE_CODE + commit增量` 并写回 `build.gradle.kts`，无需手动改版本号。

**English**

- Fixed announcement popup and APK update check: migrated hardcoded URLs (AnnouncementManager / ApkUpdateManager / UpdateManager) from YOYOFeelings repo to kcln243107 repo.
- Fixed Web plugin config not saving: removed `webActive` guard in `injectIndexShim`, ensuring localStorage shim is injected even when engine is running; added dedup set to prevent double-injection on SPA sub-routes.
- Fixed engine startup race condition: added `dshDataLock` synchronization in `ensurePrivateDshData()` to prevent concurrent writes from MainActivity bootstrap thread and EngineService watchdog thread.
- Added auto-version-increment to workflow: each push/manual dispatch auto-computes `versionCode = BASE_CODE + commit_count` and writes back to `build.gradle.kts`.

---

## v0.11.0

**中文**

- 版本号提升至 v0.11.0（versionCode 11）。
- 优化「关于」页：改为全屏单列纵向布局，元素按顺序排列（返回导航 → 应用 Logo + 名称 + 版本号 → 系统信息 → 全部操作按钮），所有按钮完整可见，删除多余灰色空白区域。
- 下载/安装进度改为终端内 ASCII 进度条，不再出现换行异常。
- 优化引擎重启逻辑：引擎已下载/就绪后，点击「重启引擎」直接重启引擎，不再重复下载。
- 修复日志导出、引擎错误报告、多源测速、公告拉取等多项细节。
- 主介绍已更新为中文，并新增官方 QQ 群信息（QQ 群 1：200317338；QQ 群 2：932593560）。

**English**

- Version bumped to v0.11.0 (versionCode 11).
- Redesigned the "About" page: full-screen single-column layout, elements arranged in order (back navigation → app logo + name + version → system info → all action buttons), all buttons fully visible, extra blank grey area removed.
- Download/install progress now uses an ASCII progress bar in the terminal; no more line-wrap glitches.
- Improved engine restart logic: once the engine is downloaded/ready, "Restart engine" restarts the engine directly instead of downloading again.
- Fixed several details including log export, engine error reports, multi-mirror speed test, and announcement fetching.
- The main introduction is now in Chinese, and official QQ group info was added (QQ Group 1: 200317338; QQ Group 2: 932593560).
